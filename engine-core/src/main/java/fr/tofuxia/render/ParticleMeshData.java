package fr.tofuxia.render;

public record ParticleMeshData(float[] alphaVertices, int[] alphaIndices,
                               float[] blendVertices, int[] blendIndices,
                               float[] addVertices, int[] addIndices) {
    public static final int FLOATS_PER_VERTEX = 9;

    public ParticleMeshData(float[] vertices, int[] indices) {
        this(vertices, indices, new float[0], new int[0], new float[0], new int[0]);
    }

    public int vertexCount() {
        return alphaVertices.length / FLOATS_PER_VERTEX
                + blendVertices.length / FLOATS_PER_VERTEX
                + addVertices.length / FLOATS_PER_VERTEX;
    }

    public boolean isEmpty() {
        return alphaIndices.length == 0 && blendIndices.length == 0 && addIndices.length == 0;
    }
}
