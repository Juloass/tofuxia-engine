package fr.tofuxia.render;

public record MeshData(float[] vertices, int[] indices) {
    public static final int FLOATS_PER_VERTEX = 6;

    public int vertexCount() {
        return vertices.length / FLOATS_PER_VERTEX;
    }

    public static MeshData merge(MeshData a, MeshData b) {
        if (a == null || a.indices.length == 0) return b;
        if (b == null || b.indices.length == 0) return a;
        float[] vertices = new float[a.vertices.length + b.vertices.length];
        System.arraycopy(a.vertices, 0, vertices, 0, a.vertices.length);
        System.arraycopy(b.vertices, 0, vertices, a.vertices.length, b.vertices.length);
        int[] indices = new int[a.indices.length + b.indices.length];
        System.arraycopy(a.indices, 0, indices, 0, a.indices.length);
        int offset = a.vertexCount();
        for (int i = 0; i < b.indices.length; i++) {
            indices[a.indices.length + i] = b.indices[i] + offset;
        }
        return new MeshData(vertices, indices);
    }
}
