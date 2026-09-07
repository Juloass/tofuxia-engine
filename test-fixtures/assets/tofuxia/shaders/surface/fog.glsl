#pragma once

float fogFactor() {
    float fogAmount = 1.0 - exp(-globals.fogParams.w * length(vWorldPos - globals.eye.xyz));
    return clamp(fogAmount, 0.0, 1.0);
}

vec3 applyFog(vec3 color) {
#ifdef FEATURE_FOG
    color = mix(color, globals.fogParams.rgb, fogFactor());
#endif
    return color;
}
