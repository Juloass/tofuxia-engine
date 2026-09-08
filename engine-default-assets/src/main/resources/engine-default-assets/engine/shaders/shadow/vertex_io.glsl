#pragma once

layout(location = 0) in vec3 inPosition;
layout(location = 2) in vec2 inUv;
#ifdef SHADOW_VOXEL
layout(location = 4) in float inFoliageWeight;
#endif
#ifdef SHADOW_SKINNED
layout(location = 4) in uvec4 inJoints;
layout(location = 5) in vec4 inWeights;
#endif

layout(location = 0) out vec2 vUv;
