#version 450

#include "common/surface_push.glsl"

layout(set = 1, binding = 0) uniform sampler2D baseColorTexture;
layout(location = 0) in vec2 vUv;

void main() {
    if (texture(baseColorTexture, vUv).a * push.baseColorFactor.a < push.params.x) {
        discard;
    }
}
