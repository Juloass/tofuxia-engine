#pragma once

layout(push_constant) uniform Push {
    mat4 model;
    vec4 baseColorFactor;
    vec4 emissive;        // rgb color, w strength
    vec4 params;          // x = alpha cutoff, y = outline width in pixels
} push;
