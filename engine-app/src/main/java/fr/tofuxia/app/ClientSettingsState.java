package fr.tofuxia.app;

import java.util.List;
import java.util.Map;

/** Versioned local presentation settings; gameplay never reads these values authoritatively. */
public record ClientSettingsState(int schemaVersion, List<ActionBinding> bindings, String pendingConflict,
                                  Graphics graphics, Map<String, AudioBus> audioBuses,
                                  Accessibility accessibility,
                                  boolean displayConfirmationPending, long displayRevertDeadlineMillis,
                                  String locale, Definitions definitions, boolean dirty,
                                  String diagnostic) {
    public record ActionBinding(String actionId, String categoryId, String displayName,
                                List<String> keys, List<String> defaultKeys, boolean enabled) {
        public ActionBinding {
            keys = List.copyOf(keys);
            defaultKeys = List.copyOf(defaultKeys);
        }
    }
    public record ControlCategory(String id, int order) {}
    public record NumericRange(double minimum, double maximum, double step) {
        public NumericRange {
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || !Double.isFinite(step)
                    || maximum < minimum || step <= 0) throw new IllegalArgumentException("Invalid settings range");
        }
    }
    public record Definitions(List<ControlCategory> controlCategories,
                              List<String> displayModes, List<String> resolutions, List<String> qualities,
                              NumericRange renderDistance, NumericRange uiScale, List<Double> textScales,
                              String defaultLocale) {
        public Definitions {
            controlCategories = List.copyOf(controlCategories);
            displayModes = List.copyOf(displayModes);
            resolutions = List.copyOf(resolutions);
            qualities = List.copyOf(qualities);
            textScales = List.copyOf(textScales);
        }
    }
    public record Graphics(String displayMode, String resolution, boolean vsync, String quality,
                           int renderDistance, double uiScale) {}
    public record AudioBus(double volume, boolean muted) {}
    public record Accessibility(double textScale, boolean reducedUiAnimations, boolean highContrast) {
        /** Compatibility alias for integrations using the former, broader setting name. */
        @Deprecated(forRemoval = false)
        public boolean reducedMotion() { return reducedUiAnimations; }
    }
    private static final Definitions EMPTY_DEFINITIONS = new Definitions(List.of(), List.of("WINDOWED"),
            List.of("1280x720"), List.of("HIGH"), new NumericRange(4, 16, 1),
            new NumericRange(.75, 2, .25), List.of(1d), "fr_fr");
    public static final ClientSettingsState EMPTY = new ClientSettingsState(3, List.of(), "",
            new Graphics("WINDOWED", "1280x720", true, "HIGH", 8, 1), Map.of(),
            new Accessibility(1, false, false), false, 0, "fr_fr", EMPTY_DEFINITIONS, false, "");
    public ClientSettingsState {
        bindings = List.copyOf(bindings);
        audioBuses = Map.copyOf(audioBuses);
        pendingConflict = pendingConflict == null ? "" : pendingConflict;
        locale = locale == null || locale.isBlank() ? "fr_fr" : locale;
        definitions = definitions == null ? EMPTY_DEFINITIONS : definitions;
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
}
