package fr.tofuxia.render;

import java.util.Objects;

/**
 * Renderer-neutral parameters for one analytic UI surface. Values are expressed
 * in logical UI pixels; the root {@link UiBuilder} transform applies display
 * density and user scale exactly once.
 */
public record UiSurfaceData(
        Shape shape,
        float[] primaryColor,
        float[] secondaryColor,
        float[] borderColor,
        float borderWidth,
        float cornerRadius,
        float gradientDirectionX,
        float gradientDirectionY,
        float gradientStrength,
        NoiseMode noiseMode,
        float noiseStrength,
        NoiseAnchor noiseAnchor,
        float edgeDarkening,
        float[] shadowColor,
        float shadowOffsetX,
        float shadowOffsetY,
        float shadowExpansion,
        float shadowSoftness,
        float extensionWidth,
        float extensionHeight,
        float extensionOffset,
        float extensionOverlap,
        float extensionRadius,
        DebugMode debugMode
) {
    public enum Shape {
        RECT(0), ROUNDED_RECT(1), TOP_CENTER_EXTENSION_PANEL(2);
        private final int shaderId;
        Shape(int shaderId) { this.shaderId = shaderId; }
        public int shaderId() { return shaderId; }
        public boolean composite() { return this == TOP_CENTER_EXTENSION_PANEL; }
    }

    public enum NoiseMode {
        NONE(0), SHARED_TEXTURE(1), PROCEDURAL(2);
        private final int shaderId;
        NoiseMode(int shaderId) { this.shaderId = shaderId; }
        public int shaderId() { return shaderId; }
    }

    public enum NoiseAnchor {
        ELEMENT_LOCAL(0), SCREEN_SPACE(1);
        private final int shaderId;
        NoiseAnchor(int shaderId) { this.shaderId = shaderId; }
        public int shaderId() { return shaderId; }
    }

    public enum DebugMode {
        NONE(0), SIGNED_DISTANCE(1), ANTIALIASING(2), BORDER_COVERAGE(3);
        private final int shaderId;
        DebugMode(int shaderId) { this.shaderId = shaderId; }
        public int shaderId() { return shaderId; }
    }

    public UiSurfaceData {
        shape = Objects.requireNonNull(shape, "shape");
        primaryColor = color(primaryColor, "primaryColor");
        secondaryColor = color(secondaryColor, "secondaryColor");
        borderColor = color(borderColor, "borderColor");
        shadowColor = color(shadowColor, "shadowColor");
        noiseMode = Objects.requireNonNull(noiseMode, "noiseMode");
        noiseAnchor = Objects.requireNonNull(noiseAnchor, "noiseAnchor");
        debugMode = Objects.requireNonNull(debugMode, "debugMode");
        borderWidth = Math.max(0, borderWidth);
        cornerRadius = Math.max(0, cornerRadius);
        gradientStrength = clamp01(gradientStrength);
        noiseStrength = clamp01(noiseStrength);
        edgeDarkening = clamp01(edgeDarkening);
        shadowExpansion = Math.max(0, shadowExpansion);
        shadowSoftness = Math.max(0, shadowSoftness);
        extensionWidth = Math.max(0, extensionWidth);
        extensionHeight = Math.max(0, extensionHeight);
        extensionOverlap = Math.min(extensionHeight, Math.max(0, extensionOverlap));
        extensionRadius = Math.max(0, extensionRadius);
        float directionLength = (float) Math.hypot(gradientDirectionX, gradientDirectionY);
        if (directionLength < .0001f) {
            gradientDirectionX = 0;
            gradientDirectionY = 1;
        } else {
            gradientDirectionX /= directionLength;
            gradientDirectionY /= directionLength;
        }
    }

    @Override public float[] primaryColor() { return primaryColor.clone(); }
    @Override public float[] secondaryColor() { return secondaryColor.clone(); }
    @Override public float[] borderColor() { return borderColor.clone(); }
    @Override public float[] shadowColor() { return shadowColor.clone(); }

    private static float[] color(float[] value, String name) {
        if (value == null || value.length < 4) throw new IllegalArgumentException(name + " needs RGBA");
        return new float[]{clamp01(value[0]), clamp01(value[1]), clamp01(value[2]), clamp01(value[3])};
    }

    private static float clamp01(float value) { return Math.max(0, Math.min(1, value)); }
}
