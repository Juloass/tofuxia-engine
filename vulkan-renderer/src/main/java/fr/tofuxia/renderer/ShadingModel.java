package fr.tofuxia.renderer;

/** High-level lighting response of a material; expands into shader features. */
public enum ShadingModel {
    /** No lighting at all. */
    UNLIT,
    /** Standard Lambert directional light + hemisphere ambient. */
    STANDARD_LIT;

    public static ShadingModel parse(String value, ShadingModel fallback) {
        if (value == null) return fallback;
        return switch (value.toLowerCase()) {
            case "unlit" -> UNLIT;
            case "standard_lit", "standard", "lambert" -> STANDARD_LIT;
            case "painterly_lit", "painterly", "lit", "toon" -> STANDARD_LIT;
            default -> fallback;
        };
    }
}
