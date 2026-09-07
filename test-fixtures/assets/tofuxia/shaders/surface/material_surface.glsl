#pragma once

#ifdef FEATURE_TEXTURED
layout(set = 1, binding = 0) uniform sampler2D baseColorTexture;
#endif

const int ANIM_PHASE_WORLD_DIAGONAL = 1;
const int ANIM_PHASE_WORLD_HASH = 2;

int spritePhaseOffset(int flags, int frameCount) {
    if (frameCount <= 1) return 0;
#ifdef FEATURE_VOXEL_DATA
    int mode = flags & 15;
    if (mode == ANIM_PHASE_WORLD_DIAGONAL) {
        return int(floor(vWorldPos.x + vWorldPos.z)) % frameCount;
    }
    if (mode == ANIM_PHASE_WORLD_HASH) {
        int x = int(floor(vWorldPos.x));
        int y = int(floor(vWorldPos.y));
        int z = int(floor(vWorldPos.z));
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        return int(uint(h) % uint(frameCount));
    }
#endif
    return 0;
}

int spriteId() {
#ifdef FEATURE_VOXEL_DATA
    return clamp(vSpriteId, 0, 1023);
#else
    return 0;
#endif
}

vec4 spriteMeta() {
#ifdef FEATURE_VOXEL_DATA
    return globals.animatedSpriteMeta[spriteId()];
#else
    return vec4(0.0, 1.0, 1.0, 0.0);
#endif
}

int spriteCurrentFrame() {
#ifdef FEATURE_VOXEL_DATA
    vec4 meta = spriteMeta();
    int frameCount = max(1, int(meta.y + 0.5));
    int duration = max(1, int(meta.z + 0.5));
    int flags = int(meta.w + 0.5);
    return (int(globals.renderFrame.y + 0.5) / duration + spritePhaseOffset(flags, frameCount)) % frameCount;
#else
    return 0;
#endif
}

vec4 spriteFrameRect() {
#ifdef FEATURE_VOXEL_DATA
    vec4 meta = spriteMeta();
    int baseFrame = int(meta.x + 0.5);
    return globals.atlasFrameRects[baseFrame + spriteCurrentFrame()];
#else
    return vec4(0.0, 0.0, 1.0, 1.0);
#endif
}

vec2 spriteAtlasUv(vec2 localUv) {
#ifdef FEATURE_VOXEL_DATA
    vec4 rect = spriteFrameRect();
    vec2 uv = max(localUv.x, localUv.y) > 1.0001 ? fract(localUv) : clamp(localUv, vec2(0.0), vec2(1.0));
    return mix(rect.xy, rect.zw, uv);
#else
    return localUv;
#endif
}

float spriteFrameDebugValue() {
#ifdef FEATURE_VOXEL_DATA
    vec4 meta = spriteMeta();
    int frameCount = max(1, int(meta.y + 0.5));
    if (frameCount <= 1) return 0.0;
    return float(spriteCurrentFrame()) / float(frameCount - 1);
#else
    return 0.0;
#endif
}

float spriteIdDebugValue() {
#ifdef FEATURE_VOXEL_DATA
    return fract(float(spriteId()) * 0.073);
#else
    return 0.0;
#endif
}

vec2 spriteAtlasUvDebugValue() {
#ifdef FEATURE_VOXEL_DATA
    return spriteAtlasUv(vUv);
#else
    return vUv;
#endif
}

vec2 spriteLocalUvDebugValue() {
#ifdef FEATURE_VOXEL_DATA
    return clamp(vUv, vec2(0.0), vec2(1.0));
#else
    return vUv;
#endif
}

vec4 surfaceBaseColor() {
    vec4 surface = push.baseColorFactor;
#ifdef FEATURE_TEXTURED
    surface *= texture(baseColorTexture, spriteAtlasUv(vUv));
#endif
#ifdef FEATURE_VERTEX_COLOR
    surface *= vColor;
#endif
#ifdef FEATURE_ALPHA_CUTOUT
    if (surface.a < push.params.x) {
        discard;
    }
#endif
    return surface;
}
