// Surface fragment stage. ShaderLibrary prepends #version, MAX_JOINTS and
// FEATURE_* defines per material variant before include expansion.

#include "common/global_ubo.glsl"
#include "common/surface_push.glsl"
#include "surface/fragment_io.glsl"
#include "surface/material_surface.glsl"
#include "surface/normals.glsl"
#include "surface/shadow_sampling.glsl"
#include "surface/lighting.glsl"
#include "surface/emissive.glsl"
#include "surface/fog.glsl"
#include "surface/color_grade.glsl"
#include "surface/terrain_occlusion.glsl"

layout(location = 1) out float outSelectionMask;

void main() {
#ifdef FEATURE_OUTLINE
    outSelectionMask = 1.0;
#else
    outSelectionMask = 0.0;
#endif
    int debugMode = int(globals.debugParams.x + 0.5);
    if (debugMode == 9) {
        vec2 uv = spriteLocalUvDebugValue();
        outColor = vec4(uv.x, uv.y, 0.0, 1.0);
        return;
    }
    if (debugMode == 10) {
        float id = spriteIdDebugValue();
        outColor = vec4(id, fract(id * 3.7), fract(id * 11.3), 1.0);
        return;
    }
    if (debugMode == 11) {
        vec2 uv = spriteAtlasUvDebugValue();
        outColor = vec4(uv.x, uv.y, 0.0, 1.0);
        return;
    }
    if (debugMode == 12) {
        float frame = spriteFrameDebugValue();
        outColor = vec4(frame, 1.0 - frame, 0.0, 1.0);
        return;
    }
    applyTerrainOcclusionCutaway();
    vec4 surface = surfaceBaseColor();
    if (debugMode == 1) {
        outColor = vec4(surface.rgb, surface.a);
        return;
    }

    vec3 color = surface.rgb;
    float shadow = 1.0;

#ifdef FEATURE_STANDARD_LIGHTING
    vec3 normal = normalize(vNormal);
    if (debugMode == 8) {
        outColor = vec4(normal * 0.5 + 0.5, 1.0);
        return;
    }
    if (debugMode == 13) {
        float occupancy = clusterOccupancyDebug(vWorldPos);
        outColor = vec4(occupancy, occupancy * 0.35, 1.0 - occupancy, 1.0);
        return;
    }
    if (debugMode == 14) {
        outColor = vec4(localLighting(vWorldPos, normal), 1.0);
        return;
    }

#ifdef FEATURE_RECEIVE_SHADOWS
    shadow = directionalShadow(normal);
#endif
    if (debugMode == 4) {
        outColor = vec4(vec3(shadow), 1.0);
        return;
    }
    vec3 ambient = ambientLighting(normal);
    vec3 direct = directLighting(normal) * softenedShadow(shadow) + localLighting(vWorldPos, normal);
    if (debugMode == 2) {
        outColor = vec4(direct, 1.0);
        return;
    }
    if (debugMode == 3) {
        outColor = vec4(ambient, 1.0);
        return;
    }

    color = surface.rgb * (ambient + direct);
#endif

    color = applyEmissive(color);
    if (debugMode == 5) {
        outColor = vec4(vec3(fogFactor()), 1.0);
        return;
    }
    color = applyFog(color);
    color = applyColorGrade(color);
    if (debugMode == 6) {
        outColor = vec4(color, surface.a);
        return;
    }
    outColor = vec4(color, surface.a);
}
