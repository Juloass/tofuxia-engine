package fr.tofuxia.desktop;

import fr.tofuxia.app.ClientSettingsState;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Atomic local settings store with conflict-aware bindings and safe display rollback. */
final class DesktopClientSettings {
    enum Action {
        QUIT("quit", "interface", "ESCAPE"), CHAT("chat", "social", "ENTER"),
        STATS("stats", "developer", "F3"), RELOAD("reload", "developer", "F5"),
        PAUSE("pause", "interface", "SPACE"), UI_SCALE("ui_scale", "interface", "F4"),
        COMBAT_TOGGLE("combat_toggle", "combat", "F11"), SCENE_1("scene_1", "developer", "F1"),
        SCENE_2("scene_2", "developer", "F2"), GAME_MODE_TOGGLE("game_mode_toggle", "developer", "F6"),
        DEBUG_ALBEDO("debug_albedo", "developer", "F8"),
        DEBUG_SHADOW_FACTOR("debug_shadow_factor", "developer", "F9"),
        MIPMAP_TOGGLE("mipmap_toggle", "developer", "F10"),
        DEBUG_TONE_MAPPED("debug_tone_mapped", "developer", "F12"),
        UI_BACKSPACE("ui_backspace", "interface", "BACKSPACE"),
        UI_DELETE("ui_delete", "interface", "DELETE"), UI_LEFT("ui_left", "interface", "LEFT"),
        UI_RIGHT("ui_right", "interface", "RIGHT"), UI_HOME("ui_home", "interface", "HOME"),
        UI_END("ui_end", "interface", "END"), MOVE_LEFT("move_left", "movement", "Q,A"),
        MOVE_RIGHT("move_right", "movement", "D"), MOVE_FORWARD("move_forward", "movement", "Z,W"),
        MOVE_BACK("move_back", "movement", "S"), HOTBAR_1("hotbar_1", "combat", "1"),
        HOTBAR_2("hotbar_2", "combat", "2"), HOTBAR_3("hotbar_3", "combat", "3"),
        HOTBAR_4("hotbar_4", "combat", "4"), HOTBAR_5("hotbar_5", "combat", "5"),
        HOTBAR_6("hotbar_6", "combat", "6"), HOTBAR_7("hotbar_7", "combat", "7"),
        HOTBAR_8("hotbar_8", "combat", "8"), HOTBAR_9("hotbar_9", "combat", "9");

        final String id;
        final String categoryId;
        final String defaults;
        Action(String id, String categoryId, String defaults) {
            this.id = id; this.categoryId = categoryId; this.defaults = defaults;
        }
    }
    static final int SCHEMA = 3;
    static final List<String> DISPLAY_MODES = List.of("WINDOWED", "BORDERLESS", "FULLSCREEN");
    static final List<String> RESOLUTIONS = List.of("1280x720", "1600x900", "1920x1080", "2560x1440");
    static final List<String> QUALITIES = List.of("LOW", "MEDIUM", "HIGH", "ULTRA");
    static final List<String> AUDIO_BUSES = List.of("master", "music", "effects", "ui");
    static final List<Double> TEXT_SCALES = List.of(1d, 1.15d, 1.3d, 1.5d);
    private static final ClientSettingsState.Graphics DEFAULT_GRAPHICS =
            new ClientSettingsState.Graphics("WINDOWED", "1280x720", true, "HIGH", 8, 1);
    private static final ClientSettingsState.Accessibility DEFAULT_ACCESSIBILITY =
            new ClientSettingsState.Accessibility(1, false, false);
    private static final List<ClientSettingsState.ControlCategory> CONTROL_CATEGORIES =
            List.of(new ClientSettingsState.ControlCategory("movement", 0),
                    new ClientSettingsState.ControlCategory("combat", 1),
                    new ClientSettingsState.ControlCategory("interface", 2),
                    new ClientSettingsState.ControlCategory("social", 3),
                    new ClientSettingsState.ControlCategory("developer", 4));

    private final Path path;
    private final LinkedHashMap<String, List<String>> defaults = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> labels = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> categories = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<String>> bindings = new LinkedHashMap<>();
    private final LinkedHashMap<String, ClientSettingsState.AudioBus> buses = new LinkedHashMap<>();
    private List<String> resolutions = RESOLUTIONS;
    private ClientSettingsState.Graphics graphics = DEFAULT_GRAPHICS;
    private ClientSettingsState.Graphics previousGraphics = graphics;
    private ClientSettingsState.Accessibility accessibility = DEFAULT_ACCESSIBILITY;
    private String locale = "fr_fr";
    private LinkedHashMap<String, List<String>> draftBindings;
    private LinkedHashMap<String, ClientSettingsState.AudioBus> draftBuses;
    private ClientSettingsState.Graphics draftGraphics;
    private ClientSettingsState.Accessibility draftAccessibility;
    private String draftLocale;
    private String pendingConflict = "", diagnostic = "";
    private long revertDeadline;

    DesktopClientSettings() { this(Path.of(System.getProperty("user.home"), ".tofuxia", "client-settings.properties")); }
    DesktopClientSettings(Path path) { this.path = path; registerActions(); load(); }

    synchronized ClientSettingsState state() {
        revertExpiredDisplay();
        Map<String, List<String>> visibleBindings = editing() ? draftBindings : bindings;
        Map<String, ClientSettingsState.AudioBus> visibleBuses = editing() ? draftBuses : buses;
        List<ClientSettingsState.ActionBinding> actions = visibleBindings.entrySet().stream().map(entry ->
                new ClientSettingsState.ActionBinding(entry.getKey(), categories.get(entry.getKey()),
                        labels.get(entry.getKey()), entry.getValue(), defaults.get(entry.getKey()), true)).toList();
        return new ClientSettingsState(SCHEMA, actions, pendingConflict,
                editing() ? draftGraphics : graphics, visibleBuses,
                editing() ? draftAccessibility : accessibility, revertDeadline > 0, revertDeadline,
                editing() ? draftLocale : locale, definitions(), dirty(), diagnostic);
    }

    synchronized void displayResolutions(List<String> available) {
        List<String> normalized = available == null ? List.of() : available.stream()
                .filter(value -> value != null && value.matches("[1-9][0-9]{2,4}x[1-9][0-9]{2,4}"))
                .distinct().toList();
        if (!normalized.isEmpty()) resolutions = normalized;
    }

    private ClientSettingsState.Definitions definitions() {
        return new ClientSettingsState.Definitions(CONTROL_CATEGORIES, DISPLAY_MODES, resolutions, QUALITIES,
                new ClientSettingsState.NumericRange(4, 16, 1),
                new ClientSettingsState.NumericRange(.75, 2, .25), TEXT_SCALES, "fr_fr");
    }

    synchronized void beginEdit() { ensureDraft(); }

    synchronized void rebind(String actionId, String rawKey, boolean replace) {
        rebind(actionId, 0, rawKey, replace);
    }

    synchronized void rebind(String actionId, int slot, String rawKey, boolean replace) {
        ensureDraft();
        String key = normalizeKey(rawKey);
        if (!draftBindings.containsKey(actionId)) { diagnostic = "UNKNOWN_ACTION:" + actionId; return; }
        if (slot < 0 || slot > 1) { diagnostic = "INVALID_BINDING_SLOT"; return; }
        if (key.isBlank()) { diagnostic = "INVALID_KEY"; return; }
        String conflict = draftBindings.entrySet().stream().filter(value -> !value.getKey().equals(actionId) && value.getValue().contains(key))
                .map(Map.Entry::getKey).findFirst().orElse("");
        if (!conflict.isBlank() && !replace) {
            pendingConflict = actionId + ":" + slot + ":" + key + ":" + conflict;
            diagnostic = "KEY_CONFLICT";
            return;
        }
        if (!conflict.isBlank()) draftBindings.put(conflict,
                draftBindings.get(conflict).stream().filter(value -> !value.equals(key)).toList());
        ArrayList<String> keys = new ArrayList<>(draftBindings.get(actionId));
        keys.remove(key);
        while (keys.size() <= slot) keys.add("");
        keys.set(slot, key);
        draftBindings.put(actionId, keys.stream().filter(value -> !value.isBlank()).limit(2).toList());
        pendingConflict = "";
        diagnostic = "binding staged";
    }

    synchronized void cancelRebind() { pendingConflict = ""; diagnostic = "binding change cancelled"; }
    synchronized void restoreDefaults() { resetSection("commands"); }

    synchronized void graphics(String mode, String resolution, boolean vsync, String quality, int renderDistance, double uiScale) {
        ensureDraft();
        String validMode = enumValue(DISPLAY_MODES, mode, "WINDOWED");
        String validResolution = enumValue(resolutions, resolution, graphics.resolution());
        String validQuality = enumValue(QUALITIES, quality, "HIGH");
        draftGraphics = new ClientSettingsState.Graphics(validMode, validResolution, vsync, validQuality,
                Math.clamp(renderDistance, 4, 16), Math.clamp(uiScale, .75, 2));
        diagnostic = "graphics staged";
    }

    synchronized void confirmDisplay(boolean keep) {
        if (revertDeadline == 0) return;
        if (!keep) graphics = previousGraphics;
        revertDeadline = 0;
        if (editing()) draftGraphics = graphics;
        diagnostic = keep ? "display settings kept" : "display settings reverted";
        save();
    }

    synchronized void audio(String id, double volume, boolean muted) {
        ensureDraft();
        if (!AUDIO_BUSES.contains(id)) { diagnostic = "UNKNOWN_AUDIO_BUS:" + id; return; }
        draftBuses.put(id, new ClientSettingsState.AudioBus(Math.clamp(volume, 0, 1), muted));
        diagnostic = id + " audio staged";
    }

    synchronized void accessibility(double textScale, boolean reducedUiAnimations, boolean highContrast) {
        ensureDraft();
        draftAccessibility = new ClientSettingsState.Accessibility(Math.clamp(textScale, 1, 1.5),
                reducedUiAnimations, highContrast);
        diagnostic = "accessibility settings staged";
    }

    synchronized String locale() { return locale; }

    synchronized void locale(String value) {
        try { locale = io.github.juloass.localization.LocaleId.of(value).value(); }
        catch (RuntimeException invalid) { locale = "fr_fr"; }
        if (editing()) draftLocale = locale;
        save();
    }

    synchronized void draftLocale(String value) {
        ensureDraft();
        try { draftLocale = io.github.juloass.localization.LocaleId.of(value).value(); }
        catch (RuntimeException invalid) { diagnostic = "INVALID_LOCALE"; return; }
        diagnostic = "locale staged";
    }

    synchronized void resetSection(String section) {
        ensureDraft();
        switch (section == null ? "" : section.toLowerCase(Locale.ROOT)) {
            case "general" -> draftLocale = definitions().defaultLocale();
            case "audio" -> {
                draftBuses.clear();
                AUDIO_BUSES.forEach(id -> draftBuses.put(id, new ClientSettingsState.AudioBus(1, false)));
            }
            case "video" -> draftGraphics = new ClientSettingsState.Graphics(DEFAULT_GRAPHICS.displayMode(),
                    DEFAULT_GRAPHICS.resolution(), DEFAULT_GRAPHICS.vsync(), DEFAULT_GRAPHICS.quality(),
                    DEFAULT_GRAPHICS.renderDistance(), draftGraphics.uiScale());
            case "interface" -> {
                draftGraphics = new ClientSettingsState.Graphics(draftGraphics.displayMode(), draftGraphics.resolution(),
                        draftGraphics.vsync(), draftGraphics.quality(), draftGraphics.renderDistance(), DEFAULT_GRAPHICS.uiScale());
                draftAccessibility = DEFAULT_ACCESSIBILITY;
            }
            case "commands" -> {
                draftBindings.clear();
                defaults.forEach((id, keys) -> draftBindings.put(id, List.copyOf(keys)));
                pendingConflict = "";
            }
            default -> { diagnostic = "UNKNOWN_SETTINGS_SECTION:" + section; return; }
        }
        diagnostic = "section reset staged";
    }

    synchronized void cancelChanges() {
        clearDraft();
        pendingConflict = "";
        diagnostic = "settings changes cancelled";
    }

    synchronized void applyChanges() {
        if (!editing()) return;
        boolean disruptive = !draftGraphics.displayMode().equals(graphics.displayMode())
                || !draftGraphics.resolution().equals(graphics.resolution());
        if (disruptive) {
            previousGraphics = graphics;
            revertDeadline = System.currentTimeMillis() + 15_000;
        }
        bindings.clear();
        draftBindings.forEach((id, keys) -> bindings.put(id, List.copyOf(keys)));
        buses.clear();
        buses.putAll(draftBuses);
        graphics = draftGraphics;
        accessibility = draftAccessibility;
        locale = draftLocale;
        clearDraft();
        pendingConflict = "";
        diagnostic = disruptive ? "confirm display within 15 seconds" : "settings applied";
        save();
    }

    synchronized ClientSettingsState.Graphics appliedGraphics() {
        revertExpiredDisplay();
        return graphics;
    }

    synchronized List<String> keys(String actionId) {
        return (editing() ? draftBindings : bindings).getOrDefault(actionId, List.of());
    }

    synchronized List<String> appliedKeys(String actionId) {
        return bindings.getOrDefault(actionId, List.of());
    }

    synchronized Map<String, List<String>> appliedBindings() {
        LinkedHashMap<String, List<String>> snapshot = new LinkedHashMap<>();
        bindings.forEach((id, keys) -> snapshot.put(id, List.copyOf(keys)));
        return Map.copyOf(snapshot);
    }

    private boolean editing() { return draftBindings != null; }

    private void ensureDraft() {
        if (editing()) return;
        draftBindings = new LinkedHashMap<>();
        bindings.forEach((id, keys) -> draftBindings.put(id, List.copyOf(keys)));
        draftBuses = new LinkedHashMap<>(buses);
        draftGraphics = graphics;
        draftAccessibility = accessibility;
        draftLocale = locale;
    }

    private boolean dirty() {
        return editing() && (!draftBindings.equals(bindings) || !draftBuses.equals(buses)
                || !draftGraphics.equals(graphics) || !draftAccessibility.equals(accessibility)
                || !draftLocale.equals(locale));
    }

    private void clearDraft() {
        draftBindings = null;
        draftBuses = null;
        draftGraphics = null;
        draftAccessibility = null;
        draftLocale = null;
    }

    private void revertExpiredDisplay() {
        if (revertDeadline > 0 && System.currentTimeMillis() >= revertDeadline) {
            graphics = previousGraphics;
            if (editing()) draftGraphics = graphics;
            revertDeadline = 0;
            diagnostic = "display settings reverted after timeout";
            save();
        }
    }

    private void load() {
        defaults.forEach((id, keys) -> bindings.put(id, List.copyOf(keys)));
        AUDIO_BUSES.forEach(id -> buses.put(id, new ClientSettingsState.AudioBus(1, false)));
        if (!Files.exists(path)) { save(); return; }
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(path)) { values.load(in); }
        catch (IOException error) { diagnostic = "settings unreadable; defaults restored"; return; }
        String schema = values.getProperty("schema");
        if (!"1".equals(schema) && !"2".equals(schema) && !"3".equals(schema)) { diagnostic = "obsolete settings schema; defaults restored"; save(); return; }
        bindings.replaceAll((id, keys) -> parseKeys(values.getProperty("input." + id), keys));
        graphics = new ClientSettingsState.Graphics(enumValue(DISPLAY_MODES, values.getProperty("graphics.displayMode"), "WINDOWED"),
                enumValue(resolutions, values.getProperty("graphics.resolution"), "1280x720"),
                Boolean.parseBoolean(values.getProperty("graphics.vsync", "true")),
                enumValue(QUALITIES, values.getProperty("graphics.quality"), "HIGH"),
                parseInt(values.getProperty("graphics.renderDistance"), 8, 4, 16),
                parseDouble(values.getProperty("graphics.uiScale"), 1, .75, 2));
        buses.replaceAll((id, ignored) -> new ClientSettingsState.AudioBus(
                parseDouble(values.getProperty("audio." + id + ".volume"), 1, 0, 1),
                Boolean.parseBoolean(values.getProperty("audio." + id + ".muted", "false"))));
        accessibility = new ClientSettingsState.Accessibility(
                parseDouble(values.getProperty("accessibility.textScale"), 1, 1, 1.5),
                Boolean.parseBoolean(values.getProperty("accessibility.reducedUiAnimations",
                        values.getProperty("accessibility.reducedMotion", "false"))),
                Boolean.parseBoolean(values.getProperty("accessibility.highContrast", "false")));
        try { locale = io.github.juloass.localization.LocaleId.of(values.getProperty("locale", "fr_fr")).value(); }
        catch (RuntimeException invalid) { locale = "fr_fr"; diagnostic = "invalid locale reset to fr_fr"; }
        if (!"3".equals(schema)) { diagnostic = "settings migrated from schema " + schema; save(); }
    }

    private void save() {
        Properties values = new Properties(); values.setProperty("schema", Integer.toString(SCHEMA));
        bindings.forEach((id, keys) -> values.setProperty("input." + id, String.join(",", keys)));
        values.setProperty("graphics.displayMode", graphics.displayMode()); values.setProperty("graphics.resolution", graphics.resolution());
        values.setProperty("graphics.vsync", Boolean.toString(graphics.vsync())); values.setProperty("graphics.quality", graphics.quality());
        values.setProperty("graphics.renderDistance", Integer.toString(graphics.renderDistance()));
        values.setProperty("graphics.uiScale", Double.toString(graphics.uiScale()));
        buses.forEach((id, bus) -> { values.setProperty("audio." + id + ".volume", Double.toString(bus.volume()));
            values.setProperty("audio." + id + ".muted", Boolean.toString(bus.muted())); });
        values.setProperty("accessibility.textScale", Double.toString(accessibility.textScale()));
        values.setProperty("accessibility.reducedUiAnimations", Boolean.toString(accessibility.reducedUiAnimations()));
        values.setProperty("accessibility.highContrast", Boolean.toString(accessibility.highContrast()));
        values.setProperty("locale", locale);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent()); Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) { values.store(out, "Tofuxia client settings schema " + SCHEMA); }
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException error) { diagnostic = "settings save failed:" + error.getClass().getSimpleName(); }
    }

    private void registerActions() {
        for (Action action : Action.values())
            add(action.id, action.categoryId, action.id.replace('_', ' '), action.defaults.split(","));
    }
    private void add(String id, String categoryId, String label, String... keys) {
        categories.put(id, categoryId);
        labels.put(id, label);
        defaults.put(id, List.of(keys));
    }
    private static String normalizeKey(String value) { return value == null ? "" : value.strip().toUpperCase(Locale.ROOT); }
    private static List<String> parseKeys(String raw, List<String> fallback) {
        if (raw == null) return fallback;
        if (raw.isBlank()) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (String value : raw.split(",")) { String key = normalizeKey(value); if (!key.isBlank() && !out.contains(key)) out.add(key); }
        return List.copyOf(out);
    }
    private static String enumValue(List<String> supported, String value, String fallback) {
        String normalized = normalizeKey(value); return supported.contains(normalized) ? normalized : fallback;
    }
    private static int parseInt(String raw, int fallback, int min, int max) { try { return Math.clamp(Integer.parseInt(raw), min, max); } catch (RuntimeException ignored) { return fallback; } }
    private static double parseDouble(String raw, double fallback, double min, double max) { try { return Math.clamp(Double.parseDouble(raw), min, max); } catch (RuntimeException ignored) { return fallback; } }
}
