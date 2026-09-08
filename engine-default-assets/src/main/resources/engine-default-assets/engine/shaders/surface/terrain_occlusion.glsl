#pragma once

#ifdef FEATURE_TERRAIN_OCCLUSION_CUTAWAY

const float BAYER_8X8[64] = float[](
     0.5, 48.5, 12.5, 60.5,  3.5, 51.5, 15.5, 63.5,
    32.5, 16.5, 44.5, 28.5, 35.5, 19.5, 47.5, 31.5,
     8.5, 56.5,  4.5, 52.5, 11.5, 59.5,  7.5, 55.5,
    40.5, 24.5, 36.5, 20.5, 43.5, 27.5, 39.5, 23.5,
     2.5, 50.5, 14.5, 62.5,  1.5, 49.5, 13.5, 61.5,
    34.5, 18.5, 46.5, 30.5, 33.5, 17.5, 45.5, 29.5,
    10.5, 58.5,  6.5, 54.5,  9.5, 57.5,  5.5, 53.5,
    42.5, 26.5, 38.5, 22.5, 41.5, 25.5, 37.5, 21.5
);

float terrainCutawayThreshold(ivec2 framebufferPixel) {
    // A fixed screen-door pattern remains stable without requiring TAA.
    // Quantizing to 2x2 pixels keeps the edge readable at pixel-art scale.
    ivec2 cell = (framebufferPixel / 2) & 7;
    return BAYER_8X8[cell.y * 8 + cell.x] / 64.0;
}

void applyTerrainOcclusionCutaway() {
    float strength = globals.occlusionPlayer.w;
    if (strength <= 0.001 || vTerrainOcclusionWeight < 0.5) {
        return;
    }

    vec3 playerFeet = globals.occlusionPlayer.xyz;
    float playerDistance = globals.occlusionProjection.w;
    if (playerDistance <= 0.001) {
        return;
    }

    vec3 viewRay = globals.occlusionRay.xyz;
    float fragmentDistance = dot(vWorldPos - globals.eye.xyz, viewRay);
    float depthBias = globals.occlusionParams.z;
    if (fragmentDistance <= 0.0 || fragmentDistance >= playerDistance - depthBias) {
        return;
    }

    vec3 worldNormal = normalize(vNormal);
    bool protectedFloor = worldNormal.y > 0.55
            && vWorldPos.y <= playerFeet.y + globals.occlusionParams.w;
    if (protectedFloor) {
        return;
    }

    vec2 playerPixel = globals.occlusionProjection.xy;
    vec2 fragmentPixel = gl_FragCoord.xy - globals.clusterViewport.xy;
    float pixelsPerWorldUnit = globals.occlusionProjection.z;
    float innerPixels = max(6.0, globals.occlusionParams.x * pixelsPerWorldUnit);
    float outerPixels = max(innerPixels + 8.0,
            globals.occlusionParams.y * pixelsPerWorldUnit);
    float radial = 1.0 - smoothstep(innerPixels, outerPixels,
            distance(fragmentPixel, playerPixel));
    float dissolve = clamp(strength * radial, 0.0, 1.0);

    if (terrainCutawayThreshold(ivec2(gl_FragCoord.xy)) < dissolve) {
        discard;
    }
}

#else

void applyTerrainOcclusionCutaway() {}

#endif
