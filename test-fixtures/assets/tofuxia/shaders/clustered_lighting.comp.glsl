#include "common/global_ubo.glsl"

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(std430, set = 0, binding = 2) readonly buffer PointLightBuffer {
    vec4 pointLightData[];
};

layout(std430, set = 0, binding = 3) buffer ClusterCountBuffer {
    uint clusterCounts[];
};

layout(std430, set = 0, binding = 4) buffer ClusterIndexBuffer {
    uint clusterLightIndices[];
};

layout(std430, set = 0, binding = 5) buffer ClusterStatsBuffer {
    uint clusterStats[];
};

int depthSlice(float depth) {
    float nearPlane = max(globals.clusterDepthParams.y, 0.0001);
    float farPlane = max(globals.clusterDepthParams.z, nearPlane + 0.0001);
    float slices = max(globals.clusterParams.w, 1.0);
    float normalized = log(clamp(depth, nearPlane, farPlane) / nearPlane) / log(farPlane / nearPlane);
    return int(clamp(floor(normalized * slices), 0.0, slices - 1.0));
}

void main() {
    uint lightIndex = gl_GlobalInvocationID.x;
    uint lightCount = uint(max(globals.clusterParams.x, 0.0));
    if (lightIndex >= lightCount) return;

    uint tilesX = uint(max(globals.clusterParams.y, 1.0));
    uint tilesY = uint(max(globals.clusterParams.z, 1.0));
    uint slices = uint(max(globals.clusterParams.w, 1.0));
    uint maxLightsPerCluster = uint(max(globals.clusterDepthParams.w, 1.0));
    float tileSize = max(globals.clusterDepthParams.x, 1.0);
    float viewportWidth = max(globals.clusterViewport.z, 1.0);
    float viewportHeight = max(globals.clusterViewport.w, 1.0);
    float nearPlane = globals.clusterDepthParams.y;
    float farPlane = globals.clusterDepthParams.z;

    vec4 posRadius = pointLightData[lightIndex * 2];
    vec4 colorIntensity = pointLightData[lightIndex * 2 + 1];
    float radius = posRadius.w;
    if (radius <= 0.0 || colorIntensity.w <= 0.0) return;

    vec4 viewCenter = globals.view * vec4(posRadius.xyz, 1.0);
    float depth = -viewCenter.z;
    if (depth + radius < nearPlane || depth - radius > farPlane || depth <= 0.0) return;

    vec2 minPx = vec2(1.0e20);
    vec2 maxPx = vec2(-1.0e20);
    for (int corner = 0; corner < 8; corner++) {
        vec3 offset = vec3((corner & 1) == 0 ? -radius : radius,
                (corner & 2) == 0 ? -radius : radius,
                (corner & 4) == 0 ? -radius : radius);
        vec4 clip = globals.proj * vec4(viewCenter.xyz + offset, 1.0);
        if (clip.w <= 0.0) continue;
        vec2 ndc = clip.xy / clip.w;
        vec2 px = (ndc * 0.5 + 0.5) * vec2(viewportWidth, viewportHeight);
        minPx = min(minPx, px);
        maxPx = max(maxPx, px);
    }
    if (maxPx.x < minPx.x || maxPx.y < minPx.y) return;
    if (maxPx.x < 0.0 || minPx.x > viewportWidth || maxPx.y < 0.0 || minPx.y > viewportHeight) {
        return;
    }

    int minTileX = int(clamp(floor(minPx.x / tileSize) - 1.0, 0.0, float(tilesX - 1u)));
    int maxTileX = int(clamp(floor(maxPx.x / tileSize) + 1.0, 0.0, float(tilesX - 1u)));
    int minTileY = int(clamp(floor(minPx.y / tileSize) - 1.0, 0.0, float(tilesY - 1u)));
    int maxTileY = int(clamp(floor(maxPx.y / tileSize) + 1.0, 0.0, float(tilesY - 1u)));
    int minSlice = depthSlice(max(depth - radius, nearPlane));
    int maxSlice = depthSlice(min(depth + radius, farPlane));

    for (int z = minSlice; z <= maxSlice; z++) {
        for (int y = minTileY; y <= maxTileY; y++) {
            for (int x = minTileX; x <= maxTileX; x++) {
                uint clusterIndex = (uint(z) * tilesY + uint(y)) * tilesX + uint(x);
                uint slot = atomicAdd(clusterCounts[clusterIndex], 1u);
                atomicMax(clusterStats[1], min(slot + 1u, maxLightsPerCluster));
                if (slot < maxLightsPerCluster) {
                    clusterLightIndices[clusterIndex * maxLightsPerCluster + slot] = lightIndex;
                } else {
                    atomicAdd(clusterStats[0], 1u);
                }
            }
        }
    }
}
