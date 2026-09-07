#pragma once

#ifdef SHADOW_SKINNED
layout(set = 2, binding = 0) uniform Joints {
    mat4 palette[MAX_JOINTS];
} skin;
#endif

mat4 shadowModelMatrix() {
    mat4 model = push.model;
#ifdef SHADOW_SKINNED
    mat4 skinMatrix =
        inWeights.x * skin.palette[inJoints.x] +
        inWeights.y * skin.palette[inJoints.y] +
        inWeights.z * skin.palette[inJoints.z] +
        inWeights.w * skin.palette[inJoints.w];
    model = model * skinMatrix;
#endif
    return model;
}
