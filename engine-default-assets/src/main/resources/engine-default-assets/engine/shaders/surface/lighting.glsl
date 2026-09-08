#pragma once

vec3 ambientLighting(vec3 normal) {
    float hemisphere = clamp(normal.y * 0.5 + 0.5, 0.0, 1.0);
    return mix(globals.ambientGround.rgb, globals.ambientSky.rgb, hemisphere);
}

vec3 directLighting(vec3 normal) {
    float lambert = max(dot(normal, normalize(-globals.sunDirection.xyz)), 0.0);
    return globals.sunColor.rgb * lambert;
}

layout(std430, set = 0, binding = 2) readonly buffer PointLightBuffer {
    vec4 pointLightData[];
};

layout(std430, set = 0, binding = 3) readonly buffer ClusterCountBuffer {
    uint clusterCounts[];
};

layout(std430, set = 0, binding = 4) readonly buffer ClusterIndexBuffer {
    uint clusterLightIndices[];
};

layout(std430, set = 0, binding = 5) readonly buffer ClusterStatsBuffer {
    uint clusterStats[];
};

int clusteredDepthSlice(float depth) {
    float nearPlane = max(globals.clusterDepthParams.y, 0.0001);
    float farPlane = max(globals.clusterDepthParams.z, nearPlane + 0.0001);
    float slices = max(globals.clusterParams.w, 1.0);
    float normalized = log(clamp(depth, nearPlane, farPlane) / nearPlane) / log(farPlane / nearPlane);
    return int(clamp(floor(normalized * slices), 0.0, slices - 1.0));
}

int clusteredLightListBase(vec3 worldPos, out int maxLightsPerCluster) {
    int tilesX = max(int(globals.clusterParams.y + 0.5), 1);
    int tilesY = max(int(globals.clusterParams.z + 0.5), 1);
    maxLightsPerCluster = max(int(globals.clusterDepthParams.w + 0.5), 1);
    int tileSize = max(int(globals.clusterDepthParams.x + 0.5), 1);
    vec2 localFrag = gl_FragCoord.xy - globals.clusterViewport.xy;
    int tileX = clamp(int(floor(localFrag.x)) / tileSize, 0, tilesX - 1);
    int tileY = clamp(int(floor(localFrag.y)) / tileSize, 0, tilesY - 1);
    float depth = max(-(globals.view * vec4(worldPos, 1.0)).z, globals.clusterDepthParams.y);
    int slice = clusteredDepthSlice(depth);
    int clusterIndex = (slice * tilesY + tileY) * tilesX + tileX;
    return clusterIndex * maxLightsPerCluster;
}

float clusterOccupancyDebug(vec3 worldPos) {
    if (int(globals.clusterParams.x + 0.5) <= 8) return 0.0;
    int maxLightsPerCluster;
    int listBase = clusteredLightListBase(worldPos, maxLightsPerCluster);
    int clusterIndex = listBase / maxLightsPerCluster;
    return clamp(float(min(int(clusterCounts[clusterIndex]), maxLightsPerCluster))
            / float(maxLightsPerCluster), 0.0, 1.0);
}

vec3 localLighting(vec3 worldPos, vec3 normal) {
    vec3 result = vec3(0.0);
    int count = max(int(globals.clusterParams.x + 0.5), 0);
    if (count == 0) return result;
    if (count <= 8) {
        for (int lightIndex = 0; lightIndex < count; lightIndex++) {
            vec4 posRadius = pointLightData[lightIndex * 2];
            vec4 colorIntensity = pointLightData[lightIndex * 2 + 1];
            if (colorIntensity.w <= 0.0 || posRadius.w <= 0.0) {
                continue;
            }
            vec3 toLight = posRadius.xyz - worldPos;
            float distanceToLight = length(toLight);
            float range = max(posRadius.w, 0.001);
            float attenuation = clamp(1.0 - distanceToLight / range, 0.0, 1.0);
            attenuation *= attenuation;
            vec3 lightDirection = toLight / max(distanceToLight, 0.0001);
            float ndl = max(dot(normal, lightDirection), 0.0);
            result += colorIntensity.rgb * colorIntensity.w * attenuation * (0.35 + 0.65 * ndl);
        }
        return result;
    }
    int maxLightsPerCluster;
    int listBase = clusteredLightListBase(worldPos, maxLightsPerCluster);
    int clusterIndex = listBase / maxLightsPerCluster;
    int clusterLightCount = min(int(clusterCounts[clusterIndex]), maxLightsPerCluster);
    for (int i = 0; i < clusterLightCount; i++) {
        int lightIndex = int(clusterLightIndices[listBase + i]);
        if (lightIndex < 0 || lightIndex >= count) continue;
        vec4 posRadius = pointLightData[lightIndex * 2];
        vec4 colorIntensity = pointLightData[lightIndex * 2 + 1];
        if (colorIntensity.w <= 0.0 || posRadius.w <= 0.0) {
            continue;
        }
        vec3 toLight = posRadius.xyz - worldPos;
        float distanceToLight = length(toLight);
        float range = max(posRadius.w, 0.001);
        float attenuation = clamp(1.0 - distanceToLight / range, 0.0, 1.0);
        attenuation *= attenuation;
        vec3 lightDirection = toLight / max(distanceToLight, 0.0001);
        float ndl = max(dot(normal, lightDirection), 0.0);
        result += colorIntensity.rgb * colorIntensity.w * attenuation * (0.35 + 0.65 * ndl);
    }
    return result;
}

float softenedShadow(float visibility) {
    float strength = clamp(globals.debugParams.y, 0.0, 1.0);
    return mix(1.0, visibility, strength);
}

vec3 standardLighting(vec3 baseColor, vec3 normal, vec3 worldPos, float shadow) {
    vec3 ambient = ambientLighting(normal);
    vec3 direct = directLighting(normal) * softenedShadow(shadow) + localLighting(worldPos, normal);
    return baseColor * (ambient + direct);
}
