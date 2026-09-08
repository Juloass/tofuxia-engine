#version 450

#include "common/global_ubo.glsl"
#include "common/surface_push.glsl"
#include "shadow/vertex_io.glsl"
#include "shadow/skinning.glsl"
#include "common/foliage_wind.glsl"

void main() {
    mat4 model = shadowModelMatrix();
    vUv = inUv;
    vec4 world = model * vec4(inPosition, 1.0);
#ifdef SHADOW_VOXEL
    vec3 deformationNormal = vec3(0.0, 1.0, 0.0);
    world = applyDeformation(world, deformationNormal, inFoliageWeight);
#endif
    gl_Position = globals.lightViewProj * world;
}
