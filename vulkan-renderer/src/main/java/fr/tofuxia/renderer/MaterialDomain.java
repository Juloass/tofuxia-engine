package fr.tofuxia.renderer;

/** What kind of thing a material shades; decides the default render pass. */
public enum MaterialDomain {
    /** Regular world geometry. */
    SURFACE,
    /** Screen-space expanded entity silhouette, rendered after opaque geometry. */
    OUTLINE,
    /** Camera-facing quads (particles, impostors); always drawn late. */
    BILLBOARD;

    public static MaterialDomain parse(String value, MaterialDomain fallback) {
        if (value == null) return fallback;
        return switch (value.toLowerCase()) {
            case "surface" -> SURFACE;
            case "billboard", "particle" -> BILLBOARD;
            case "outline" -> OUTLINE;
            default -> fallback;
        };
    }
}
