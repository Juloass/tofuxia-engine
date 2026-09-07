#pragma once

vec3 surfaceWorldNormal(mat4 model, vec3 objectNormal) {
    return normalize(transpose(inverse(mat3(model))) * objectNormal);
}
