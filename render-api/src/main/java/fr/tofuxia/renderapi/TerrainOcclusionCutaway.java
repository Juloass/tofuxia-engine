package fr.tofuxia.renderapi;

/**
 * Per-frame terrain visibility window centered on the controlled character.
 * Radii are authored in world units and projected by the terrain shader so
 * the window tracks camera zoom without gameplay code knowing the viewport.
 */
public record TerrainOcclusionCutaway(
        float playerX,
        float playerFeetY,
        float playerZ,
        float strength,
        float focusHeight,
        float innerRadius,
        float outerRadius,
        float depthBias,
        float floorProtectionHeight
) {
    public static final float DEFAULT_FOCUS_HEIGHT = .85f;
    public static final float DEFAULT_INNER_RADIUS = 1.15f;
    public static final float DEFAULT_OUTER_RADIUS = 3.0f;
    public static final float DEFAULT_DEPTH_BIAS = .30f;
    public static final float DEFAULT_FLOOR_PROTECTION_HEIGHT = .18f;

    public static final TerrainOcclusionCutaway DISABLED =
            new TerrainOcclusionCutaway(0, 0, 0, 0,
                    DEFAULT_FOCUS_HEIGHT, DEFAULT_INNER_RADIUS, DEFAULT_OUTER_RADIUS,
                    DEFAULT_DEPTH_BIAS, DEFAULT_FLOOR_PROTECTION_HEIGHT);

    public TerrainOcclusionCutaway {
        requireFinite(playerX, "playerX");
        requireFinite(playerFeetY, "playerFeetY");
        requireFinite(playerZ, "playerZ");
        requireFinite(strength, "strength");
        requireFinite(focusHeight, "focusHeight");
        requireFinite(innerRadius, "innerRadius");
        requireFinite(outerRadius, "outerRadius");
        requireFinite(depthBias, "depthBias");
        requireFinite(floorProtectionHeight, "floorProtectionHeight");
        strength = Math.clamp(strength, 0.0f, 1.0f);
        focusHeight = Math.max(0.0f, focusHeight);
        innerRadius = Math.max(0.0f, innerRadius);
        outerRadius = Math.max(innerRadius + .01f, outerRadius);
        depthBias = Math.max(0.0f, depthBias);
        floorProtectionHeight = Math.max(0.0f, floorProtectionHeight);
    }

    public static TerrainOcclusionCutaway aroundPlayer(float x, float feetY, float z) {
        return new TerrainOcclusionCutaway(x, feetY, z, 1.0f,
                DEFAULT_FOCUS_HEIGHT, DEFAULT_INNER_RADIUS, DEFAULT_OUTER_RADIUS,
                DEFAULT_DEPTH_BIAS, DEFAULT_FLOOR_PROTECTION_HEIGHT);
    }

    public boolean enabled() {
        return strength > .001f;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
