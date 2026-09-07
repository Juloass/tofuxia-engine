package fr.tofuxia.renderapi;

/** Stable renderer diagnostics exposed to applications and validation scenes. */
public enum DebugViewMode {
    LIT(0),
    ALBEDO(1),
    DIRECT_LIGHT(2),
    AMBIENT(3),
    SHADOW_FACTOR(4),
    FOG_FACTOR(5),
    TONE_MAPPED(6),
    SHADOW_MAP_DEPTH(7),
    NORMALS(8),
    SPRITE_LOCAL_UV(9),
    SPRITE_ID(10),
    SPRITE_ATLAS_UV(11),
    SPRITE_FRAME(12),
    CLUSTER_OCCUPANCY(13),
    LOCAL_LIGHT(14),
    SELECTION_MASK(15),
    SELECTION_DILATED_MASK(16),
    SELECTION_EXTERIOR(17),
    SELECTION_DEPTH_OCCLUSION(18);

    private final int shaderValue;

    DebugViewMode(int shaderValue) { this.shaderValue = shaderValue; }
    public int shaderValue() { return shaderValue; }

    public static DebugViewMode fromInt(int value) {
        for (DebugViewMode mode : values()) if (mode.shaderValue == value) return mode;
        return LIT;
    }
}
