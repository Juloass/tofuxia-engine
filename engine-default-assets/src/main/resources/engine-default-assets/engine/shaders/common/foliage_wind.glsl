#pragma once

vec4 applyDeformation(vec4 world, inout vec3 normal, float encoded) {
    if (encoded <= 0.0) return world;
    int profile = int(floor(encoded / 8.0 + 0.0001));
    float amplitude = encoded - float(profile) * 8.0;
    if (profile <= 0 || amplitude <= 0.0) return world;

    vec2 windDirection = normalize(globals.windParams.xy);
    float time = globals.timeParams.x * globals.windParams.w;
    float broad = sin(dot(world.xz, vec2(0.73, 0.91)) + time);
    float detail = sin(dot(world.xz, vec2(1.37, -0.61)) + time * 0.55);
    float gust = (broad * 0.72 + detail * 0.28) * globals.windParams.z;

    if (profile == 3) {
        float alongCloth = sin(time * 1.35 - world.y * 5.2 + dot(world.xz, windDirection) * 0.42);
        float pressure = 0.62 + gust * 0.30 + alongCloth * 0.18;
        world.xz += windDirection * pressure * amplitude;
        world.y += alongCloth * amplitude * 0.075;
        normal = normalize(normal + vec3(-windDirection.x, 0.0, -windDirection.y)
                * alongCloth * amplitude);
    } else if (profile == 2) {
        world.xz += windDirection * gust * amplitude;
    } else if (profile == 4) {
        world.xz += windDirection * gust * amplitude;
    } else if (profile == 5) {
        float current = sin(dot(world.xz, vec2(.42, .58)) + time * .35);
        world.xz += windDirection * current * amplitude;
    } else {
        world.xz += windDirection * gust * amplitude;
    }
    return world;
}
