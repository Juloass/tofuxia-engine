#pragma once

vec3 applyEmissive(vec3 color) {
#ifdef FEATURE_EMISSIVE
    color += push.emissive.rgb * push.emissive.w * globals.timeParams.y;
#endif
    return color;
}
