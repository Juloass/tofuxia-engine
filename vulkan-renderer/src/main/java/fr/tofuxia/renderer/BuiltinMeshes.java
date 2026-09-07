package fr.tofuxia.renderer;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural test meshes for Milestone 1: cube, plane grid, billboard quad
 * and a low-poly crystal. All are STATIC layout with white vertex colors
 * unless stated otherwise.
 */
public final class BuiltinMeshes {
    private BuiltinMeshes() {
    }

    /** Axis-aligned cube centered at origin; per-face normals and 0..1 UVs. */
    public static CpuMesh cube(float size) {
        float h = size * 0.5f;
        MeshBuilder b = new MeshBuilder("cube");
        // +Y top, -Y bottom, +Z front, -Z back, +X right, -X left
        b.quad(new float[]{-h, h, -h}, new float[]{-h, h, h}, new float[]{h, h, h}, new float[]{h, h, -h}, new float[]{0, 1, 0});
        b.quad(new float[]{-h, -h, h}, new float[]{-h, -h, -h}, new float[]{h, -h, -h}, new float[]{h, -h, h}, new float[]{0, -1, 0});
        b.quad(new float[]{-h, -h, h}, new float[]{h, -h, h}, new float[]{h, h, h}, new float[]{-h, h, h}, new float[]{0, 0, 1});
        b.quad(new float[]{h, -h, -h}, new float[]{-h, -h, -h}, new float[]{-h, h, -h}, new float[]{h, h, -h}, new float[]{0, 0, -1});
        b.quad(new float[]{h, -h, h}, new float[]{h, -h, -h}, new float[]{h, h, -h}, new float[]{h, h, h}, new float[]{1, 0, 0});
        b.quad(new float[]{-h, -h, -h}, new float[]{-h, -h, h}, new float[]{-h, h, h}, new float[]{-h, h, -h}, new float[]{-1, 0, 0});
        return b.build();
    }

    /** Flat XZ plane centered at origin with tiled UVs and up normals. */
    public static CpuMesh plane(float halfExtent, int segments, float uvTiles) {
        MeshBuilder b = new MeshBuilder("plane");
        float step = (halfExtent * 2.0f) / segments;
        for (int z = 0; z <= segments; z++) {
            for (int x = 0; x <= segments; x++) {
                float px = -halfExtent + x * step;
                float pz = -halfExtent + z * step;
                float u = (x / (float) segments) * uvTiles;
                float v = (z / (float) segments) * uvTiles;
                b.vertex(px, 0, pz, 0, 1, 0, u, v, 1, 1, 1, 1);
            }
        }
        int stride = segments + 1;
        for (int z = 0; z < segments; z++) {
            for (int x = 0; x < segments; x++) {
                int i0 = z * stride + x;
                int i1 = i0 + 1;
                int i2 = i0 + stride;
                int i3 = i2 + 1;
                b.triangle(i0, i2, i1);
                b.triangle(i1, i2, i3);
            }
        }
        return b.build();
    }

    /**
     * Unit quad in the XY plane (corners at +-0.5) for billboards and foliage
     * cards. The BILLBOARD shader feature treats XY as camera-space offsets.
     */
    public static CpuMesh quad(String name) {
        MeshBuilder b = new MeshBuilder(name);
        b.vertex(-0.5f, -0.5f, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1);
        b.vertex(0.5f, -0.5f, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1);
        b.vertex(0.5f, 0.5f, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1);
        b.vertex(-0.5f, 0.5f, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1);
        b.triangle(0, 1, 2);
        b.triangle(0, 2, 3);
        return b.build();
    }

    /**
     * Low-poly bipyramid crystal with faceted normals and a vertical vertex
     * color gradient; a good emissive/vertex-color test subject.
     */
    public static CpuMesh crystal(float radius, float height) {
        MeshBuilder b = new MeshBuilder("crystal");
        int sides = 6;
        Vector3f top = new Vector3f(0, height * 0.62f, 0);
        Vector3f bottom = new Vector3f(0, -height * 0.38f, 0);
        for (int i = 0; i < sides; i++) {
            double a0 = (i / (double) sides) * Math.PI * 2.0;
            double a1 = ((i + 1) / (double) sides) * Math.PI * 2.0;
            Vector3f p0 = new Vector3f((float) (Math.cos(a0) * radius), 0, (float) (Math.sin(a0) * radius));
            Vector3f p1 = new Vector3f((float) (Math.cos(a1) * radius), 0, (float) (Math.sin(a1) * radius));
            b.facet(top, p1, p0, 1.0f, 0.75f);
            b.facet(bottom, p0, p1, 0.35f, 0.75f);
        }
        return b.build();
    }

    /** Interleaved STATIC-layout mesh assembly helper. */
    public static final class MeshBuilder {
        private final String name;
        private final CpuMesh.VertexWriter vertices;
        private final List<Integer> indices = new ArrayList<>();

        public MeshBuilder(String name) {
            this.name = name;
            this.vertices = CpuMesh.writer(name, VertexLayout.STATIC);
        }

        public int vertex(float px, float py, float pz, float nx, float ny, float nz,
                          float u, float v, float r, float g, float bl, float a) {
            return vertices.staticVertex(px, py, pz, nx, ny, nz, u, v, r, g, bl, a);
        }

        public void triangle(int a, int b, int c) {
            indices.add(a);
            indices.add(b);
            indices.add(c);
        }

        /** Quad from 4 corners (counter-clockwise seen from the normal side). */
        public void quad(float[] p0, float[] p1, float[] p2, float[] p3, float[] n) {
            int i0 = vertex(p0[0], p0[1], p0[2], n[0], n[1], n[2], 0, 1, 1, 1, 1, 1);
            int i1 = vertex(p1[0], p1[1], p1[2], n[0], n[1], n[2], 0, 0, 1, 1, 1, 1);
            int i2 = vertex(p2[0], p2[1], p2[2], n[0], n[1], n[2], 1, 0, 1, 1, 1, 1);
            int i3 = vertex(p3[0], p3[1], p3[2], n[0], n[1], n[2], 1, 1, 1, 1, 1, 1);
            triangle(i0, i1, i2);
            triangle(i0, i2, i3);
        }

        /** Faceted triangle with a flat computed normal and grayscale tint. */
        public void facet(Vector3f a, Vector3f b, Vector3f c, float tintA, float tintBc) {
            Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).normalize();
            int i0 = vertex(a.x, a.y, a.z, normal.x, normal.y, normal.z, 0.5f, 0, tintA, tintA, tintA, 1);
            int i1 = vertex(b.x, b.y, b.z, normal.x, normal.y, normal.z, 1, 1, tintBc, tintBc, tintBc, 1);
            int i2 = vertex(c.x, c.y, c.z, normal.x, normal.y, normal.z, 0, 1, tintBc, tintBc, tintBc, 1);
            triangle(i0, i1, i2);
        }

        public CpuMesh build() {
            int[] indexArray = indices.stream().mapToInt(Integer::intValue).toArray();
            return vertices.build(indexArray);
        }
    }
}
