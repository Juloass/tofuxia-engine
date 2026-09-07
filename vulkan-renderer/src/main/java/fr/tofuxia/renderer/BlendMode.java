package fr.tofuxia.renderer;

/** How a material's fragments combine with the framebuffer. */
public enum BlendMode {
    /** No blending, depth write on. */
    OPAQUE,
    /** No blending, depth write on, fragments below alphaCutoff discarded. */
    CUTOUT,
    /** Classic src-alpha blending, depth write off, sorted back-to-front. */
    TRANSPARENT,
    /** Additive blending, depth write off (glows, particles). */
    ADDITIVE;

    public static BlendMode parse(String value, BlendMode fallback) {
        if (value == null) return fallback;
        return switch (value.toLowerCase()) {
            case "opaque" -> OPAQUE;
            case "cutout", "mask", "alpha_cutout" -> CUTOUT;
            case "transparent", "blend", "alpha" -> TRANSPARENT;
            case "additive", "add" -> ADDITIVE;
            default -> fallback;
        };
    }

    public boolean blendsWithBackground() {
        return this == TRANSPARENT || this == ADDITIVE;
    }
}
