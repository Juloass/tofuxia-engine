#pragma once

vec4 surfaceBillboardWorld(vec4 world) {
#ifdef FEATURE_BILLBOARD
    // Spherical billboard: keep the instance origin, span the mesh XY plane
    // along the camera's right/up vectors.
    vec3 center = (push.model * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
    vec3 camRight = vec3(globals.view[0][0], globals.view[1][0], globals.view[2][0]);
    vec3 camUp = vec3(globals.view[0][1], globals.view[1][1], globals.view[2][1]);
    world.xyz = center + camRight * inPosition.x + camUp * inPosition.y;
#endif
    return world;
}
