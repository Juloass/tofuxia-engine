#pragma once

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inNormal;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec4 inColor;
#ifdef FEATURE_VOXEL_DATA
layout(location = 4) in float inFoliageWeight;
layout(location = 5) in uint inSpriteId;
#endif
#ifdef FEATURE_SKINNED
layout(location = 4) in uvec4 inJoints;
layout(location = 5) in vec4 inWeights;
#endif

layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec4 vColor;
layout(location = 3) out vec3 vWorldPos;
layout(location = 4) out vec4 vLightPos;
#ifdef FEATURE_VOXEL_DATA
layout(location = 5) flat out int vSpriteId;
layout(location = 6) flat out float vTerrainOcclusionWeight;
#endif
