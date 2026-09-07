package fr.tofuxia.renderer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Feature flags a material can request. The renderer turns the feature set
 * into a shader variant (each feature maps to a preprocessor define), so
 * gameplay code never picks a shader file directly.
 */
public enum MaterialFeature {
    /** Sample a base color texture (otherwise base color factor only). */
    TEXTURED,
    /** Multiply in the per-vertex color attribute. */
    VERTEX_COLOR,
    /** Skip all lighting; output the surface color as-is. */
    UNLIT,
    /** Standard Lambert directional lighting with hemisphere ambient. */
    STANDARD_LIGHTING,
    /** Discard fragments below the material alpha cutoff. */
    ALPHA_CUTOUT,
    /** Add an emissive term (kept in a dedicated output hook for later bloom). */
    EMISSIVE,
    /** Orient the mesh's XY plane toward the camera (particles, impostors). */
    BILLBOARD,
    /** Expand back faces in screen space to form a solid silhouette border. */
    OUTLINE,
    /** Linear blend skinning driven by a joint matrix palette (glTF skins). */
    SKINNED,
    /** Surface samples the directional shadow map. */
    RECEIVE_SHADOWS,
    /** Voxel chunk vertices carry sprite metadata and foliage weights. */
    VOXEL_DATA,
    /** Terrain fragments between the camera and controlled character use a stable dithered cutaway. */
    TERRAIN_OCCLUSION_CUTAWAY,
    /** Accepted for authoring compatibility; fog hook is a placeholder. */
    FOG;

    /** Bitmask over the whole feature set, used in variant and pipeline keys. */
    public int bit() {
        return 1 << ordinal();
    }

    public static MaterialFeature parse(String value) {
        if ("PAINTERLY_LIGHTING".equals(value)) {
            return STANDARD_LIGHTING;
        }
        return MaterialFeature.valueOf(value);
    }

    public static int mask(Set<MaterialFeature> features) {
        int mask = 0;
        for (MaterialFeature feature : features) {
            mask |= feature.bit();
        }
        return mask;
    }

    public static Set<MaterialFeature> fromMask(int mask) {
        Set<MaterialFeature> features = EnumSet.noneOf(MaterialFeature.class);
        for (MaterialFeature feature : values()) {
            if ((mask & feature.bit()) != 0) {
                features.add(feature);
            }
        }
        return features;
    }
}
