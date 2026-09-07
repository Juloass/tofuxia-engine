package fr.tofuxia.ui;

/** Player-facing presentation preferences independent of monitor density. */
public record UiAccessibilityPreferences(float textScale, boolean reducedUiAnimations,
                                         boolean highContrast) {
    public static final UiAccessibilityPreferences DEFAULT = new UiAccessibilityPreferences(1, false, false);

    public UiAccessibilityPreferences {
        textScale = Math.max(1, Math.min(1.5f, Float.isFinite(textScale) ? textScale : 1));
    }

    /** Compatibility alias for integrations using the former, broader setting name. */
    @Deprecated(forRemoval = false)
    public boolean reducedMotion() {
        return reducedUiAnimations;
    }
}
