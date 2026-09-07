#pragma once

layout(set = 0, binding = 0) uniform GlobalUbo {
    mat4 view;
    mat4 proj;
    mat4 viewProj;
    mat4 lightViewProj;
    vec4 eye;
    vec4 sunDirection;    // xyz = direction the light travels (downward)
    vec4 sunColor;
    vec4 ambientSky;
    vec4 ambientGround;
    vec4 timeParams;      // x = time seconds, y = emissive multiplier
    vec4 shadowParams;    // x receiver bias, y inverse map size, z PCF radius, w enabled
    vec4 fogParams;       // rgb color, w density
    vec4 debugParams;     // x debug mode, y surface shadow strength
    vec4 toneParams;      // x exposure, y contrast, z saturation
    vec4 gradeTint;       // rgb color grade tint
    vec4 clusterParams;    // x light count, y tiles x, z tiles y, w z slices
    vec4 clusterDepthParams; // x tile size, y near, z far, w max lights per cluster
    vec4 clusterViewport; // xy framebuffer origin, zw viewport size
    vec4 renderFrame;     // x = time seconds, y = game tick, z = tick alpha
    vec4 windParams;      // xy = horizontal direction, z = strength, w = speed
    vec4 occlusionPlayer; // xyz = controlled character feet, w = cutaway strength
    vec4 occlusionParams; // x inner radius, y outer radius, z depth bias, w floor protection
    vec4 occlusionProjection; // xy player pixels, z pixels/world unit, w camera distance
    vec4 occlusionRay;    // xyz = normalized camera-to-player-focus direction
    vec4 atlasFrameRects[1024];   // xy = uv min, zw = uv max
    vec4 animatedSpriteMeta[1024]; // x base frame, y count, z duration ticks, w flags
} globals;
