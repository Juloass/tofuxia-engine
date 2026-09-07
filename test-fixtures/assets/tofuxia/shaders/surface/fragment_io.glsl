#pragma once

layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec4 vColor;
layout(location = 3) in vec3 vWorldPos;
layout(location = 4) in vec4 vLightPos;
#ifdef FEATURE_VOXEL_DATA
layout(location = 5) flat in int vSpriteId;
layout(location = 6) flat in float vTerrainOcclusionWeight;
#endif

layout(location = 0) out vec4 outColor;
