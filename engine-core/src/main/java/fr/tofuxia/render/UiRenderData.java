package fr.tofuxia.render;

import java.util.List;

/** Renderer-neutral ordered UI geometry, analytic-surface metadata, and texture batches. */
public record UiRenderData(float[] vertices, int[] indices, List<Batch> batches,
                           float logicalScaleX, float logicalScaleY,
                           int surfaceCount, int compositeSurfaceCount, int clippedBatchCount) {
    public static final int LEGACY_FLOATS_PER_VERTEX = 8;
    public static final int FLOATS_PER_VERTEX = 48;
    public enum TextureKind { FONT, RGBA }
    public enum Blend { TRANSPARENT, ADDITIVE }

    public record Batch(
            int firstIndex,
            int indexCount,
            TextureKind textureKind,
            String texture,
            boolean pixelArt,
            Blend blend,
            float clipX,
            float clipY,
            float clipWidth,
            float clipHeight
    ) {}

    public UiRenderData {
        vertices = expandLegacy(vertices == null ? new float[0] : vertices);
        indices = indices == null ? new int[0] : indices;
        batches = batches == null ? List.of() : List.copyOf(batches);
        logicalScaleX = Math.max(.01f, logicalScaleX);
        logicalScaleY = Math.max(.01f, logicalScaleY);
        surfaceCount = Math.max(0, surfaceCount);
        compositeSurfaceCount = Math.max(0, compositeSurfaceCount);
        clippedBatchCount = Math.max(0, clippedBatchCount);
    }

    /** Compatibility constructor for existing renderer-neutral callers. */
    public UiRenderData(float[] vertices, int[] indices, List<Batch> batches) {
        this(vertices, indices, batches, 1, 1, 0, 0, 0);
    }

    public int vertexCount() { return vertices.length / FLOATS_PER_VERTEX; }

    private static float[] expandLegacy(float[] source) {
        if (source.length == 0 || source.length % FLOATS_PER_VERTEX == 0) return source;
        if (source.length % LEGACY_FLOATS_PER_VERTEX != 0) {
            throw new IllegalArgumentException("UI vertex array has an unsupported stride");
        }
        int count = source.length / LEGACY_FLOATS_PER_VERTEX;
        float[] expanded = new float[count * FLOATS_PER_VERTEX];
        for (int vertex = 0; vertex < count; vertex++) {
            System.arraycopy(source, vertex * LEGACY_FLOATS_PER_VERTEX,
                    expanded, vertex * FLOATS_PER_VERTEX, LEGACY_FLOATS_PER_VERTEX);
        }
        return expanded;
    }
}
