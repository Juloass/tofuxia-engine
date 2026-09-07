package fr.tofuxia.terrain;

import java.util.Arrays;
import java.util.Random;

public final class TerrainData {
    public static final int FORMAT_VERSION = 1;

    private final int width;
    private final int height;
    private final long seed;
    private final float worldSize;
    public final float[] heightField;
    public final float[] grassMask;
    public final float[] dirtMask;
    public final float[] rockMask;
    public final float[] sandMask;
    public final float[] humidityField;
    public final float[] temperatureField;
    public final float[] waterDepthField;
    public final float[] waterSurfaceHeightField;
    public final float[] waterFlowDirectionField;
    public final float[] waterFlowSpeedField;

    public TerrainData(int width, int height, long seed, float worldSize) {
        this.width = width;
        this.height = height;
        this.seed = seed;
        this.worldSize = worldSize;
        int size = width * height;
        heightField = new float[size];
        grassMask = new float[size];
        dirtMask = new float[size];
        rockMask = new float[size];
        sandMask = new float[size];
        humidityField = new float[size];
        temperatureField = new float[size];
        waterDepthField = new float[size];
        waterSurfaceHeightField = new float[size];
        waterFlowDirectionField = new float[size];
        waterFlowSpeedField = new float[size];
        reset();
    }

    public static TerrainData createDefault() {
        TerrainData terrain = new TerrainData(128, 128, 42L, 10.0f);
        terrain.randomize();
        return terrain;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long seed() {
        return seed;
    }

    public float worldSize() {
        return worldSize;
    }

    public int index(int x, int y) {
        return y * width + x;
    }

    public float worldX(int x) {
        return ((x / (float) (width - 1)) - 0.5f) * worldSize;
    }

    public float worldZ(int y) {
        return ((y / (float) (height - 1)) - 0.5f) * worldSize;
    }

    public int sampleX(float worldX) {
        return clamp(Math.round(((worldX / worldSize) + 0.5f) * (width - 1)), 0, width - 1);
    }

    public int sampleY(float worldZ) {
        return clamp(Math.round(((worldZ / worldSize) + 0.5f) * (height - 1)), 0, height - 1);
    }

    public float sampleHeight(float worldX, float worldZ) {
        float sx = ((worldX / worldSize) + 0.5f) * (width - 1);
        float sy = ((worldZ / worldSize) + 0.5f) * (height - 1);
        int x0 = clamp((int) Math.floor(sx), 0, width - 1);
        int y0 = clamp((int) Math.floor(sy), 0, height - 1);
        int x1 = clamp(x0 + 1, 0, width - 1);
        int y1 = clamp(y0 + 1, 0, height - 1);
        float tx = sx - x0;
        float ty = sy - y0;
        float a = lerp(heightField[index(x0, y0)], heightField[index(x1, y0)], tx);
        float b = lerp(heightField[index(x0, y1)], heightField[index(x1, y1)], tx);
        return lerp(a, b, ty);
    }

    public void reset() {
        Arrays.fill(heightField, 0.0f);
        Arrays.fill(grassMask, 1.0f);
        Arrays.fill(dirtMask, 0.0f);
        Arrays.fill(rockMask, 0.0f);
        Arrays.fill(sandMask, 0.0f);
        Arrays.fill(humidityField, 0.35f);
        Arrays.fill(temperatureField, 0.45f);
        Arrays.fill(waterDepthField, 0.0f);
        Arrays.fill(waterSurfaceHeightField, 0.02f);
        Arrays.fill(waterFlowDirectionField, 0.0f);
        Arrays.fill(waterFlowSpeedField, 0.0f);
    }

    public void randomize() {
        reset();
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = index(x, y);
                float nx = x / (float) (width - 1);
                float ny = y / (float) (height - 1);
                float ridge = (float) Math.sin(nx * Math.PI * 3.0 + 0.35) * 0.10f
                        + (float) Math.cos(ny * Math.PI * 2.0) * 0.08f;
                float river = Math.abs(nx - 0.56f - 0.08f * (float) Math.sin(ny * Math.PI * 2.0));
                heightField[i] = ridge + (random.nextFloat() - 0.5f) * 0.025f;
                dirtMask[i] = smoothStep(0.14f, 0.00f, river);
                sandMask[i] = smoothStep(0.10f, 0.00f, river) * 0.65f;
                rockMask[i] = smoothStep(0.55f, 0.85f, heightField[i] + 0.55f);
                grassMask[i] = Math.max(0.0f, 1.0f - dirtMask[i] - sandMask[i] * 0.7f - rockMask[i] * 0.8f);
                humidityField[i] = clamp01(0.35f + smoothStep(0.18f, 0.00f, river) * 0.45f);
                temperatureField[i] = clamp01(0.48f + (ny - 0.5f) * 0.25f);
                if (river < 0.045f) {
                    waterDepthField[i] = (0.045f - river) * 3.0f;
                    waterSurfaceHeightField[i] = 0.065f;
                    waterFlowDirectionField[i] = 1.57f;
                    waterFlowSpeedField[i] = 0.65f;
                }
            }
        }
        normalizeMaterials();
    }

    public void normalizeMaterials() {
        for (int i = 0; i < heightField.length; i++) {
            grassMask[i] = clamp01(grassMask[i]);
            dirtMask[i] = clamp01(dirtMask[i]);
            rockMask[i] = clamp01(rockMask[i]);
            sandMask[i] = clamp01(sandMask[i]);
            float total = grassMask[i] + dirtMask[i] + rockMask[i] + sandMask[i];
            if (total <= 0.0001f) {
                grassMask[i] = 1.0f;
                dirtMask[i] = rockMask[i] = sandMask[i] = 0.0f;
            } else {
                grassMask[i] /= total;
                dirtMask[i] /= total;
                rockMask[i] /= total;
                sandMask[i] /= total;
            }
        }
    }

    public static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothStep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }
}
