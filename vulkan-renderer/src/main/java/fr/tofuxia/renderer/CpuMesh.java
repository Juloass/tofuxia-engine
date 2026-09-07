package fr.tofuxia.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Packed interleaved vertex/index data on the CPU side, laid out per
 * {@link VertexLayout}. Upload through {@link MeshManager} to get a
 * {@link GpuMesh} usable in render submission.
 */
public record CpuMesh(String name, VertexLayout layout, byte[] vertices, int vertexCount, int[] indices) {
    public CpuMesh {
        if (layout == null) throw new IllegalArgumentException("Mesh '" + name + "': layout is required");
        if (vertices.length != vertexCount * layout.strideBytes()) {
            throw new IllegalArgumentException("Mesh '" + name + "': vertex byte length " + vertices.length
                    + " does not match " + vertexCount + " vertices at stride " + layout.strideBytes());
        }
        for (int index : indices) {
            if (index < 0 || index >= vertexCount) {
                throw new IllegalArgumentException("Mesh '" + name + "': index " + index
                        + " out of range for " + vertexCount + " vertices");
            }
        }
        vertices = Arrays.copyOf(vertices, vertices.length);
        indices = Arrays.copyOf(indices, indices.length);
    }

    public static CpuMesh staticMesh(String name, float[] components, int[] indices) {
        int vertexCount = checkedComponentCount(name, VertexLayout.STATIC, components);
        ByteBuffer out = allocate(VertexLayout.STATIC, vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            int src = i * VertexLayout.STATIC.debugFloatComponents;
            putStatic(out, components[src], components[src + 1], components[src + 2],
                    components[src + 3], components[src + 4], components[src + 5],
                    components[src + 6], components[src + 7],
                    components[src + 8], components[src + 9], components[src + 10], components[src + 11]);
        }
        return new CpuMesh(name, VertexLayout.STATIC, out.array(), vertexCount, indices);
    }

    public static CpuMesh voxelMesh(String name, float[] components, int[] indices) {
        int vertexCount = checkedComponentCount(name, VertexLayout.VOXEL, components);
        ByteBuffer out = allocate(VertexLayout.VOXEL, vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            int src = i * VertexLayout.VOXEL.debugFloatComponents;
            putVoxel(out, components[src], components[src + 1], components[src + 2],
                    components[src + 3], components[src + 4], components[src + 5],
                    components[src + 6], components[src + 7],
                    components[src + 8], components[src + 9], components[src + 10], components[src + 11],
                    components[src + 12], Math.round(components[src + 13]));
        }
        return new CpuMesh(name, VertexLayout.VOXEL, out.array(), vertexCount, indices);
    }

    public static CpuMesh skinnedMesh(String name, float[] components, int[] indices) {
        int vertexCount = checkedComponentCount(name, VertexLayout.SKINNED, components);
        ByteBuffer out = allocate(VertexLayout.SKINNED, vertexCount);
        for (int i = 0; i < vertexCount; i++) {
            int src = i * VertexLayout.SKINNED.debugFloatComponents;
            putSkinned(out, components[src], components[src + 1], components[src + 2],
                    components[src + 3], components[src + 4], components[src + 5],
                    components[src + 6], components[src + 7],
                    components[src + 8], components[src + 9], components[src + 10], components[src + 11],
                    Math.round(components[src + 12]), Math.round(components[src + 13]),
                    Math.round(components[src + 14]), Math.round(components[src + 15]),
                    components[src + 16], components[src + 17], components[src + 18], components[src + 19]);
        }
        return new CpuMesh(name, VertexLayout.SKINNED, out.array(), vertexCount, indices);
    }

    public static VertexWriter writer(String name, VertexLayout layout) {
        return new VertexWriter(name, layout);
    }

    public long vertexByteSize() {
        return vertices.length;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public float positionX(int vertex) {
        return buffer().getFloat(base(vertex));
    }

    public float positionY(int vertex) {
        return buffer().getFloat(base(vertex) + 4);
    }

    public float positionZ(int vertex) {
        return buffer().getFloat(base(vertex) + 8);
    }

    public float normalX(int vertex) {
        return unpackSnorm8(vertices[base(vertex) + 12]);
    }

    public float normalY(int vertex) {
        return unpackSnorm8(vertices[base(vertex) + 13]);
    }

    public float normalZ(int vertex) {
        return unpackSnorm8(vertices[base(vertex) + 14]);
    }

    /**
     * Voxel-only geometry hint stored in the otherwise unused fourth packed
     * normal component. A value of one marks substantial terrain which may
     * participate in the camera/player visibility cutaway.
     */
    public float terrainOcclusionWeight(int vertex) {
        return layout == VertexLayout.VOXEL ? unpackSnorm8(vertices[base(vertex) + 15]) : 0.0f;
    }

    public float uvU(int vertex) {
        int offset = base(vertex) + 16;
        return layout == VertexLayout.VOXEL ? buffer().getFloat(offset) : halfToFloat(Short.toUnsignedInt(buffer().getShort(offset)));
    }

    public float uvV(int vertex) {
        int offset = base(vertex) + (layout == VertexLayout.VOXEL ? 20 : 18);
        return layout == VertexLayout.VOXEL ? buffer().getFloat(offset) : halfToFloat(Short.toUnsignedInt(buffer().getShort(offset)));
    }

    public float colorR(int vertex) {
        return unpackUnorm8(vertices[base(vertex) + (layout == VertexLayout.VOXEL ? 24 : 20)]);
    }

    public float colorG(int vertex) {
        return unpackUnorm8(vertices[base(vertex) + (layout == VertexLayout.VOXEL ? 25 : 21)]);
    }

    public float colorB(int vertex) {
        return unpackUnorm8(vertices[base(vertex) + (layout == VertexLayout.VOXEL ? 26 : 22)]);
    }

    public float colorA(int vertex) {
        return unpackUnorm8(vertices[base(vertex) + (layout == VertexLayout.VOXEL ? 27 : 23)]);
    }

    public float foliageWeight(int vertex) {
        if (layout != VertexLayout.VOXEL) return 0.0f;
        float encoded = halfToFloat(Short.toUnsignedInt(buffer().getShort(base(vertex) + 28)));
        return encoded - (float)Math.floor(encoded / 8.0f) * 8.0f;
    }

    public int deformationProfile(int vertex) {
        if (layout != VertexLayout.VOXEL) return 0;
        float encoded = halfToFloat(Short.toUnsignedInt(buffer().getShort(base(vertex) + 28)));
        return (int)Math.floor(encoded / 8.0f + .0001f);
    }

    public int spriteId(int vertex) {
        if (layout != VertexLayout.VOXEL) return 0;
        return Short.toUnsignedInt(buffer().getShort(base(vertex) + 30));
    }

    public int joint(int vertex, int component) {
        if (layout != VertexLayout.SKINNED) return 0;
        return Short.toUnsignedInt(buffer().getShort(base(vertex) + 24 + component * 2));
    }

    public float weight(int vertex, int component) {
        if (layout != VertexLayout.SKINNED) return component == 0 ? 1.0f : 0.0f;
        return unpackUnorm8(vertices[base(vertex) + 32 + component]);
    }

    public float component(int vertex, int component) {
        return switch (component) {
            case 0 -> positionX(vertex);
            case 1 -> positionY(vertex);
            case 2 -> positionZ(vertex);
            case 3 -> normalX(vertex);
            case 4 -> normalY(vertex);
            case 5 -> normalZ(vertex);
            case 6 -> uvU(vertex);
            case 7 -> uvV(vertex);
            case 8 -> colorR(vertex);
            case 9 -> colorG(vertex);
            case 10 -> colorB(vertex);
            case 11 -> colorA(vertex);
            case 12 -> layout == VertexLayout.VOXEL ? foliageWeight(vertex) : joint(vertex, 0);
            case 13 -> layout == VertexLayout.VOXEL ? spriteId(vertex) : joint(vertex, 1);
            case 14 -> joint(vertex, 2);
            case 15 -> joint(vertex, 3);
            case 16 -> weight(vertex, 0);
            case 17 -> weight(vertex, 1);
            case 18 -> weight(vertex, 2);
            case 19 -> weight(vertex, 3);
            default -> throw new IllegalArgumentException("Unknown component " + component + " for " + layout);
        };
    }

    public float[] debugFloatVertices() {
        float[] out = new float[vertexCount * layout.debugFloatComponents];
        for (int v = 0; v < vertexCount; v++) {
            int base = v * layout.debugFloatComponents;
            for (int c = 0; c < layout.debugFloatComponents; c++) out[base + c] = component(v, c);
        }
        return out;
    }

    private int base(int vertex) {
        if (vertex < 0 || vertex >= vertexCount) throw new IndexOutOfBoundsException(vertex);
        return vertex * layout.strideBytes();
    }

    private ByteBuffer buffer() {
        return ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int checkedComponentCount(String name, VertexLayout layout, float[] components) {
        if (components.length % layout.debugFloatComponents != 0) {
            throw new IllegalArgumentException("Mesh '" + name + "': component length " + components.length
                    + " is not a multiple of " + layout.debugFloatComponents);
        }
        return components.length / layout.debugFloatComponents;
    }

    private static ByteBuffer allocate(VertexLayout layout, int vertexCount) {
        return ByteBuffer.allocate(vertexCount * layout.strideBytes()).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void putStatic(ByteBuffer out, float px, float py, float pz, float nx, float ny, float nz,
                          float u, float v, float r, float g, float b, float a) {
        out.putFloat(px).putFloat(py).putFloat(pz);
        putSnorm4(out, nx, ny, nz, 0.0f);
        out.putShort(floatToHalf(u)).putShort(floatToHalf(v));
        putUnorm4(out, r, g, b, a);
        out.putLong(0L);
    }

    static void putVoxel(ByteBuffer out, float px, float py, float pz, float nx, float ny, float nz,
                         float u, float v, float r, float g, float b, float a, float foliageWeight, int spriteId) {
        putVoxel(out, px, py, pz, nx, ny, nz, u, v, r, g, b, a, foliageWeight, spriteId, 0.0f);
    }

    static void putVoxel(ByteBuffer out, float px, float py, float pz, float nx, float ny, float nz,
                         float u, float v, float r, float g, float b, float a, float foliageWeight, int spriteId,
                         float terrainOcclusionWeight) {
        out.putFloat(px).putFloat(py).putFloat(pz);
        putSnorm4(out, nx, ny, nz, terrainOcclusionWeight);
        out.putFloat(u).putFloat(v);
        putUnorm4(out, r, g, b, a);
        out.putShort(floatToHalf(foliageWeight));
        out.putShort((short) clampInt(spriteId, 0, 0xffff));
    }

    static void putSkinned(ByteBuffer out, float px, float py, float pz, float nx, float ny, float nz,
                           float u, float v, float r, float g, float b, float a,
                           int j0, int j1, int j2, int j3, float w0, float w1, float w2, float w3) {
        out.putFloat(px).putFloat(py).putFloat(pz);
        putSnorm4(out, nx, ny, nz, 0.0f);
        out.putShort(floatToHalf(u)).putShort(floatToHalf(v));
        putUnorm4(out, r, g, b, a);
        out.putShort((short) clampInt(j0, 0, 0xffff)).putShort((short) clampInt(j1, 0, 0xffff));
        out.putShort((short) clampInt(j2, 0, 0xffff)).putShort((short) clampInt(j3, 0, 0xffff));
        putUnorm4(out, w0, w1, w2, w3);
        out.putInt(0);
    }

    private static void putSnorm4(ByteBuffer out, float x, float y, float z, float w) {
        out.put(packSnorm8(x)).put(packSnorm8(y)).put(packSnorm8(z)).put(packSnorm8(w));
    }

    private static void putUnorm4(ByteBuffer out, float x, float y, float z, float w) {
        out.put(packUnorm8(x)).put(packUnorm8(y)).put(packUnorm8(z)).put(packUnorm8(w));
    }

    private static byte packSnorm8(float value) {
        return (byte) clampInt(Math.round(clamp(value, -1.0f, 1.0f) * 127.0f), -127, 127);
    }

    private static float unpackSnorm8(byte value) {
        return Math.max(-1.0f, value / 127.0f);
    }

    private static byte packUnorm8(float value) {
        return (byte) clampInt(Math.round(clamp(value, 0.0f, 1.0f) * 255.0f), 0, 255);
    }

    private static float unpackUnorm8(byte value) {
        return Byte.toUnsignedInt(value) / 255.0f;
    }

    private static short floatToHalf(float value) {
        int bits = Float.floatToIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int exp = ((bits >>> 23) & 0xff) - 127 + 15;
        int mantissa = bits & 0x7fffff;
        if (exp <= 0) {
            if (exp < -10) return (short) sign;
            mantissa = (mantissa | 0x800000) >> (1 - exp);
            return (short) (sign | ((mantissa + 0x1000) >> 13));
        }
        if (exp >= 31) return (short) (sign | 0x7c00);
        return (short) (sign | (exp << 10) | ((mantissa + 0x1000) >> 13));
    }

    private static float halfToFloat(int half) {
        int sign = (half & 0x8000) << 16;
        int exp = (half >>> 10) & 0x1f;
        int mantissa = half & 0x3ff;
        int bits;
        if (exp == 0) {
            if (mantissa == 0) {
                bits = sign;
            } else {
                exp = 1;
                while ((mantissa & 0x400) == 0) {
                    mantissa <<= 1;
                    exp--;
                }
                mantissa &= 0x3ff;
                bits = sign | ((exp + 127 - 15) << 23) | (mantissa << 13);
            }
        } else if (exp == 31) {
            bits = sign | 0x7f800000 | (mantissa << 13);
        } else {
            bits = sign | ((exp + 127 - 15) << 23) | (mantissa << 13);
        }
        return Float.intBitsToFloat(bits);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class VertexWriter {
        private final String name;
        private final VertexLayout layout;
        private byte[] vertices = new byte[1024];
        private int vertexCount;

        private VertexWriter(String name, VertexLayout layout) {
            this.name = name;
            this.layout = layout;
        }

        public int staticVertex(float px, float py, float pz, float nx, float ny, float nz,
                                float u, float v, float r, float g, float b, float a) {
            require(VertexLayout.STATIC);
            ByteBuffer out = slot();
            putStatic(out, px, py, pz, nx, ny, nz, u, v, r, g, b, a);
            return vertexCount++;
        }

        public int voxelVertex(float px, float py, float pz, float nx, float ny, float nz,
                               float u, float v, float r, float g, float b, float a,
                               float foliageWeight, int spriteId) {
            return voxelVertex(px, py, pz, nx, ny, nz, u, v, r, g, b, a,
                    foliageWeight, spriteId, 0.0f);
        }

        public int voxelVertex(float px, float py, float pz, float nx, float ny, float nz,
                               float u, float v, float r, float g, float b, float a,
                               float foliageWeight, int spriteId, float terrainOcclusionWeight) {
            require(VertexLayout.VOXEL);
            ByteBuffer out = slot();
            putVoxel(out, px, py, pz, nx, ny, nz, u, v, r, g, b, a,
                    foliageWeight, spriteId, terrainOcclusionWeight);
            return vertexCount++;
        }

        public int skinnedVertex(float px, float py, float pz, float nx, float ny, float nz,
                                 float u, float v, float r, float g, float b, float a,
                                 int j0, int j1, int j2, int j3, float w0, float w1, float w2, float w3) {
            require(VertexLayout.SKINNED);
            ByteBuffer out = slot();
            putSkinned(out, px, py, pz, nx, ny, nz, u, v, r, g, b, a, j0, j1, j2, j3, w0, w1, w2, w3);
            return vertexCount++;
        }

        public CpuMesh build(int[] indices) {
            return new CpuMesh(name, layout, Arrays.copyOf(vertices, vertexCount * layout.strideBytes()), vertexCount, indices);
        }

        private ByteBuffer slot() {
            int offset = vertexCount * layout.strideBytes();
            int needed = offset + layout.strideBytes();
            if (needed > vertices.length) vertices = Arrays.copyOf(vertices, Math.max(needed, vertices.length * 2));
            return ByteBuffer.wrap(vertices, offset, layout.strideBytes()).order(ByteOrder.LITTLE_ENDIAN);
        }

        private void require(VertexLayout expected) {
            if (layout != expected) throw new IllegalStateException("Writer is " + layout + ", not " + expected);
        }
    }
}
