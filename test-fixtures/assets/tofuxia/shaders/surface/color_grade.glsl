#pragma once

vec3 gentleToneMap(vec3 color) {
    color = max(color, vec3(0.0));
    return clamp((color * 1.08) / (vec3(1.0) + color * 0.08), 0.0, 1.0);
}

vec3 applyColorGrade(vec3 color) {
    color *= globals.toneParams.x;
    color = gentleToneMap(color);
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, globals.toneParams.z);
    color = (color - 0.5) * globals.toneParams.y + 0.5;
    color = mix(color, color * globals.gradeTint.rgb, 0.25);
    return clamp(color, 0.0, 1.0);
}
