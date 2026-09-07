package fr.tofuxia.desktop;

import java.nio.file.Files;

public final class M07ClientSettingsSelfTest {
    public static void main(String[] args) throws Exception {
        var path = Files.createTempDirectory("m07-settings").resolve("client-settings.properties");
        var settings = new DesktopClientSettings(path);
        settings.displayResolutions(java.util.List.of("1024x768", "1920x1080", "1920x1080"));
        check(settings.state().schemaVersion() == 3 && settings.state().bindings().size() == DesktopClientSettings.Action.values().length,
                "settings use the actual desktop action registry");
        check(settings.state().definitions().controlCategories().size() == 5
                && settings.state().bindings().stream().allMatch(binding -> !binding.categoryId().isBlank()),
                "runtime settings definitions expose control categories");
        check(settings.state().definitions().resolutions().equals(java.util.List.of("1024x768", "1920x1080")),
                "runtime monitor modes define available resolutions");
        check(settings.locale().equals("fr_fr"), "French default locale");
        settings.beginEdit();
        settings.rebind("move_left", 0, "D", false);
        check(settings.state().pendingConflict().contains("move_right"), "conflict detected without mutation");
        settings.cancelRebind(); check(settings.state().pendingConflict().isBlank(), "conflict cancellation");
        settings.rebind("move_left", 0, "D", true);
        check(settings.keys("move_left").getFirst().equals("D") && settings.keys("move_right").isEmpty()
                && settings.state().dirty(), "explicit replacement is staged");
        check(settings.appliedKeys("move_left").contains("Q") && settings.appliedKeys("move_right").contains("D"),
                "runtime bindings remain applied while edits are staged");
        check(new DesktopClientSettings(path).keys("move_right").contains("D"), "staged bindings are not persisted");
        settings.applyChanges();
        check(settings.appliedKeys("move_left").contains("D") && settings.appliedKeys("move_right").isEmpty(),
                "runtime bindings update after apply");
        check(new DesktopClientSettings(path).keys("move_left").contains("D"), "apply persists staged bindings");
        settings.beginEdit();
        settings.restoreDefaults();
        check(settings.keys("move_left").contains("Q") && settings.state().dirty(), "section reset is staged");
        settings.cancelChanges();
        check(settings.keys("move_left").contains("D"), "cancel discards staged reset");
        var before = settings.state().graphics();
        settings.beginEdit();
        settings.graphics("FULLSCREEN", "1920x1080", false, "ULTRA", 16, 1.5);
        check(settings.state().dirty() && !settings.state().displayConfirmationPending()
                && settings.appliedGraphics().equals(before), "video changes remain staged before apply");
        settings.applyChanges();
        check(settings.state().displayConfirmationPending() && !settings.appliedGraphics().equals(before),
                "applied disruptive display change requires confirmation");
        settings.confirmDisplay(false); check(settings.state().graphics().equals(before), "safe display revert");
        settings.beginEdit();
        settings.audio("music", .35, true);
        settings.accessibility(1.3, true, true);
        settings.draftLocale("en-US");
        check(settings.state().accessibility().textScale() == 1.3 && settings.state().accessibility().reducedUiAnimations(),
                "accessibility settings staged");
        settings.applyChanges();
        check(settings.state().audioBuses().get("music").muted() && settings.locale().equals("en_us"),
                "audio and locale apply together");
        var reloaded = new DesktopClientSettings(path);
        check(reloaded.state().audioBuses().get("music").volume() == .35 && reloaded.keys("move_left").contains("D")
                && reloaded.state().accessibility().reducedUiAnimations()
                && reloaded.state().accessibility().highContrast() && reloaded.locale().equals("en_us"), "restart persistence");
        Files.writeString(path, "schema=2\naccessibility.reducedMotion=true\n");
        var legacyMotionName = new DesktopClientSettings(path);
        check(legacyMotionName.state().accessibility().reducedUiAnimations(),
                "legacy reduced-motion setting migrates to reduced UI animations");
        Files.writeString(path, "schema=1\ngraphics.displayMode=WINDOWED\n");
        var migrated = new DesktopClientSettings(path);
        check(migrated.state().schemaVersion() == 3 && migrated.state().diagnostic().contains("migrated")
                && migrated.locale().equals("fr_fr"),
                "schema-1 accessibility migration");
        Files.writeString(path, "schema=99\ngraphics.displayMode=INVALID\n");
        var obsolete = new DesktopClientSettings(path);
        check(obsolete.state().graphics().displayMode().equals("WINDOWED") && obsolete.state().diagnostic().contains("obsolete"),
                "obsolete schema defaults");
        System.out.println("[m07-settings] definitions=true drafts=true conflicts=true persistence=true graphics-revert=true audio-buses=4");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
