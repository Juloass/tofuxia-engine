// Surface vertex stage. ShaderLibrary prepends #version, MAX_JOINTS and
// FEATURE_* defines per material variant before include expansion.

#include "common/global_ubo.glsl"
#include "common/surface_push.glsl"
#include "surface/vertex_io.glsl"
#include "surface/skinning.glsl"
#include "surface/billboard.glsl"
#include "surface/normals.glsl"
#include "common/foliage_wind.glsl"

void main() {
    mat4 model = surfaceModelMatrix();
    vec4 world = model * vec4(inPosition, 1.0);
    world = surfaceBillboardWorld(world);
    vec3 worldNormal = surfaceWorldNormal(model, inNormal.xyz);
#ifdef FEATURE_VOXEL_DATA
    world = applyDeformation(world, worldNormal, inFoliageWeight);
#endif

    vNormal = worldNormal;
    vUv = inUv;
    vColor = inColor;
    vWorldPos = world.xyz;
    vLightPos = globals.lightViewProj * world;
#ifdef FEATURE_VOXEL_DATA
    vSpriteId = int(inSpriteId);
    vTerrainOcclusionWeight = inNormal.w;
#endif
    gl_Position = globals.viewProj * world;
}
