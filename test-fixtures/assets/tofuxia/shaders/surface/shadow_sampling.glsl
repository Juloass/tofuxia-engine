#pragma once

layout(set = 0, binding = 1) uniform sampler2D shadowMap;

float directionalShadow(vec3 normal) {
    if (globals.shadowParams.w < 0.5) return 1.0;
    vec3 projected = vLightPos.xyz / vLightPos.w;
    vec2 uv = projected.xy * 0.5 + 0.5;
    if (projected.z < 0.0 || projected.z > 1.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return 1.0;
    float ndl = max(dot(normal, normalize(-globals.sunDirection.xyz)), 0.0);
    float bias = globals.shadowParams.x * (1.0 + (1.0 - ndl));
    float lit = 0.0;
    int radius = int(globals.shadowParams.z + 0.5);
    for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
        float depth = texture(shadowMap, uv + vec2(x, y) * globals.shadowParams.y).r;
        lit += projected.z - bias <= depth ? 1.0 : 0.0;
    }
    float width = float(radius * 2 + 1);
    return lit / (width * width);
}
