package fr.tofuxia.render;

import fr.tofuxia.terrain.BrushSettings;
import fr.tofuxia.terrain.DebugVisualization;
import fr.tofuxia.terrain.TerrainData;

import java.util.ArrayList;
import java.util.List;

public final class TerrainMeshBuilder {
    private TerrainMeshBuilder() {
    }

    public static MeshData buildTerrain(TerrainData terrain, DebugVisualization debug) {
        int width = terrain.width();
        int height = terrain.height();
        float[] vertices = new float[width * height * MeshData.FLOATS_PER_VERTEX];
        int cursor = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = terrain.index(x, y);
                float[] color = materialColor(terrain, i, debug);
                vertices[cursor++] = terrain.worldX(x);
                vertices[cursor++] = terrain.heightField[i];
                vertices[cursor++] = terrain.worldZ(y);
                vertices[cursor++] = color[0];
                vertices[cursor++] = color[1];
                vertices[cursor++] = color[2];
            }
        }

        int[] indices = new int[(width - 1) * (height - 1) * 6];
        cursor = 0;
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int a = terrain.index(x, y);
                int b = terrain.index(x + 1, y);
                int c = terrain.index(x + 1, y + 1);
                int d = terrain.index(x, y + 1);
                indices[cursor++] = a;
                indices[cursor++] = b;
                indices[cursor++] = c;
                indices[cursor++] = a;
                indices[cursor++] = c;
                indices[cursor++] = d;
            }
        }
        return new MeshData(vertices, indices);
    }

    public static MeshData buildWater(TerrainData terrain, float timeSeconds) {
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int y = 0; y < terrain.height() - 1; y++) {
            for (int x = 0; x < terrain.width() - 1; x++) {
                int a = terrain.index(x, y);
                int b = terrain.index(x + 1, y);
                int c = terrain.index(x + 1, y + 1);
                int d = terrain.index(x, y + 1);
                float depth = (terrain.waterDepthField[a] + terrain.waterDepthField[b]
                        + terrain.waterDepthField[c] + terrain.waterDepthField[d]) * 0.25f;
                if (depth <= 0.01f) {
                    continue;
                }
                int base = vertices.size() / MeshData.FLOATS_PER_VERTEX;
                addWaterVertex(vertices, terrain, x, y, a, timeSeconds);
                addWaterVertex(vertices, terrain, x + 1, y, b, timeSeconds);
                addWaterVertex(vertices, terrain, x + 1, y + 1, c, timeSeconds);
                addWaterVertex(vertices, terrain, x, y + 1, d, timeSeconds);
                indices.add(base);
                indices.add(base + 1);
                indices.add(base + 2);
                indices.add(base);
                indices.add(base + 2);
                indices.add(base + 3);
            }
        }
        return new MeshData(toFloatArray(vertices), toIntArray(indices));
    }

    public static MeshData buildBrushPreview(TerrainData terrain, BrushSettings brush, float worldX, float worldZ) {
        return buildBrushPreview(terrain, brush.radius(), brush.subtract(), worldX, worldZ);
    }

    public static MeshData buildBrushPreview(TerrainData terrain, float radius, boolean subtract, float worldX, float worldZ) {
        int segments = 48;
        float[] vertices = new float[(segments + 1) * MeshData.FLOATS_PER_VERTEX];
        int cursor = 0;
        float centerY = terrain.sampleHeight(worldX, worldZ) + 0.045f;
        vertices[cursor++] = worldX;
        vertices[cursor++] = centerY;
        vertices[cursor++] = worldZ;
        vertices[cursor++] = 1.0f;
        vertices[cursor++] = subtract ? 0.25f : 0.95f;
        vertices[cursor++] = 0.15f;
        for (int i = 0; i < segments; i++) {
            float angle = (float) (Math.PI * 2.0 * i / segments);
            float x = worldX + (float) Math.cos(angle) * radius;
            float z = worldZ + (float) Math.sin(angle) * radius;
            vertices[cursor++] = x;
            vertices[cursor++] = terrain.sampleHeight(x, z) + 0.05f;
            vertices[cursor++] = z;
            vertices[cursor++] = 1.0f;
            vertices[cursor++] = subtract ? 0.20f : 0.85f;
            vertices[cursor++] = 0.10f;
        }
        int[] indices = new int[segments * 3];
        cursor = 0;
        for (int i = 0; i < segments; i++) {
            indices[cursor++] = 0;
            indices[cursor++] = i + 1;
            indices[cursor++] = i == segments - 1 ? 1 : i + 2;
        }
        return new MeshData(vertices, indices);
    }

    private static void addWaterVertex(List<Float> vertices, TerrainData terrain, int x, int y, int sample, float timeSeconds) {
        float flow = terrain.waterFlowSpeedField[sample];
        float shimmer = (float) Math.sin(timeSeconds * (2.0f + flow * 4.0f) + terrain.waterFlowDirectionField[sample]) * 0.012f;
        vertices.add(terrain.worldX(x));
        vertices.add(terrain.waterSurfaceHeightField[sample] + shimmer);
        vertices.add(terrain.worldZ(y));
        vertices.add(0.16f);
        vertices.add(0.48f + flow * 0.10f);
        vertices.add(0.82f);
    }

    private static float[] materialColor(TerrainData terrain, int i, DebugVisualization debug) {
        return switch (debug) {
            case HEIGHT -> gray((terrain.heightField[i] + 0.6f) / 1.2f);
            case HUMIDITY -> new float[]{0.12f, 0.20f + terrain.humidityField[i] * 0.65f, 0.85f};
            case TEMPERATURE -> new float[]{0.20f + terrain.temperatureField[i] * 0.75f, 0.20f, 0.85f - terrain.temperatureField[i] * 0.65f};
            case WATER -> new float[]{0.06f, 0.16f + terrain.waterDepthField[i] * 2.0f, 0.20f + terrain.waterDepthField[i] * 2.0f};
            case MATERIALS -> materialBlend(terrain, i);
        };
    }

    private static float[] materialBlend(TerrainData terrain, int i) {
        float humidity = terrain.humidityField[i];
        float temperature = terrain.temperatureField[i];
        float dryHeat = Math.max(0.0f, temperature - humidity);
        float[] grass = {0.18f + humidity * 0.05f + dryHeat * 0.32f, 0.46f + humidity * 0.18f - dryHeat * 0.20f, 0.20f - dryHeat * 0.05f};
        float[] dirt = {0.46f - humidity * 0.16f, 0.30f - humidity * 0.10f, 0.18f - humidity * 0.08f};
        float[] rock = {0.42f - humidity * 0.10f, 0.42f + humidity * 0.08f, 0.40f - humidity * 0.08f};
        float[] sand = {0.76f - humidity * 0.18f, 0.65f - humidity * 0.16f, 0.36f - humidity * 0.10f};
        return new float[]{
                grass[0] * terrain.grassMask[i] + dirt[0] * terrain.dirtMask[i] + rock[0] * terrain.rockMask[i] + sand[0] * terrain.sandMask[i],
                grass[1] * terrain.grassMask[i] + dirt[1] * terrain.dirtMask[i] + rock[1] * terrain.rockMask[i] + sand[1] * terrain.sandMask[i],
                grass[2] * terrain.grassMask[i] + dirt[2] * terrain.dirtMask[i] + rock[2] * terrain.rockMask[i] + sand[2] * terrain.sandMask[i]
        };
    }

    private static float[] gray(float value) {
        float v = TerrainData.clamp01(value);
        return new float[]{v, v, v};
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }
}
