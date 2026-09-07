package fr.tofuxia.gltf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes small, valid glTF 2.0 sample models so the engine has deterministic
 * import/animation test assets without downloading anything:
 *
 * <ul>
 *   <li>{@code crate.glb} - static textured cube with a node hierarchy and an
 *       embedded PNG (GLB container path)</li>
 *   <li>{@code gem.gltf} + {@code gem.bin} - untextured transparent emissive
 *       octahedron without normals (external-buffer path, normal fallback)</li>
 *   <li>{@code tofu_walker.glb} - vertex-colored column character with a
 *       3-joint skin and two looping clips ("sway": LINEAR T/R + STEP scale,
 *       "spin": LINEAR rotation)</li>
 * </ul>
 *
 * Files are only written when missing.
 */
public final class SampleGltfWriter {
    private SampleGltfWriter() {
    }

    public static void ensureSampleModels(Path directory) {
        try {
            Files.createDirectories(directory);
            Path crate = directory.resolve("crate.glb");
            if (!Files.exists(crate)) {
                Files.write(crate, buildCrateGlb());
                System.out.println("[gltf] generated sample " + crate);
            }
            Path gem = directory.resolve("gem.gltf");
            if (!Files.exists(gem)) {
                GemFiles files = buildGemGltf();
                Files.writeString(gem, files.json);
                Files.write(directory.resolve("gem.bin"), files.bin);
                System.out.println("[gltf] generated sample " + gem + " (+ gem.bin)");
            }
            Path walker = directory.resolve("tofu_walker.glb");
            if (!Files.exists(walker)) {
                Files.write(walker, buildWalkerGlb());
                System.out.println("[gltf] generated sample " + walker);
            }
        } catch (IOException e) {
            System.err.println("[gltf] could not generate sample models: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- builders

    /** Accumulates a binary buffer plus bufferView/accessor JSON fragments. */
    private static final class Builder {
        final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        final List<String> bufferViews = new ArrayList<>();
        final List<String> accessors = new ArrayList<>();

        int addBufferView(byte[] bytes) {
            align(4);
            int offset = bin.size();
            bin.writeBytes(bytes);
            bufferViews.add(String.format(Locale.ROOT,
                    "{\"buffer\":0,\"byteOffset\":%d,\"byteLength\":%d}", offset, bytes.length));
            return bufferViews.size() - 1;
        }

        int addFloatAccessor(float[] data, String type, boolean minMax) {
            int components = components(type);
            ByteBuffer bytes = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : data) bytes.putFloat(value);
            int view = addBufferView(bytes.array());
            String extras = "";
            if (minMax) {
                float[] min = new float[components];
                float[] max = new float[components];
                java.util.Arrays.fill(min, Float.MAX_VALUE);
                java.util.Arrays.fill(max, -Float.MAX_VALUE);
                for (int i = 0; i < data.length; i++) {
                    int c = i % components;
                    min[c] = Math.min(min[c], data[i]);
                    max[c] = Math.max(max[c], data[i]);
                }
                extras = ",\"min\":" + floats(min) + ",\"max\":" + floats(max);
            }
            accessors.add(String.format(Locale.ROOT,
                    "{\"bufferView\":%d,\"componentType\":5126,\"count\":%d,\"type\":\"%s\"%s}",
                    view, data.length / components, type, extras));
            return accessors.size() - 1;
        }

        int addU16Accessor(int[] data, String type) {
            ByteBuffer bytes = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int value : data) bytes.putShort((short) value);
            int view = addBufferView(bytes.array());
            accessors.add(String.format(Locale.ROOT,
                    "{\"bufferView\":%d,\"componentType\":5123,\"count\":%d,\"type\":\"%s\"}",
                    view, data.length / components(type), type));
            return accessors.size() - 1;
        }

        private void align(int alignment) {
            while (bin.size() % alignment != 0) bin.write(0);
        }

        private static int components(String type) {
            return switch (type) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                case "MAT4" -> 16;
                default -> throw new IllegalArgumentException(type);
            };
        }

        String bufferViewsJson() {
            return "[" + String.join(",", bufferViews) + "]";
        }

        String accessorsJson() {
            return "[" + String.join(",", accessors) + "]";
        }
    }

    private static String floats(float[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(String.format(Locale.ROOT, "%s", values[i]));
        }
        return out.append(']').toString();
    }

    private static byte[] glb(String json, byte[] bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPadded = (jsonBytes.length + 3) & ~3;
        int binPadded = (bin.length + 3) & ~3;
        int total = 12 + 8 + jsonPadded + 8 + binPadded;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(2).putInt(total);
        out.putInt(jsonPadded).putInt(0x4E4F534A).put(jsonBytes);
        for (int i = jsonBytes.length; i < jsonPadded; i++) out.put((byte) ' ');
        out.putInt(binPadded).putInt(0x004E4942).put(bin);
        for (int i = bin.length; i < binPadded; i++) out.put((byte) 0);
        return out.array();
    }

    // ----------------------------------------------------------------- crate

    private static byte[] buildCrateGlb() throws IOException {
        Builder b = new Builder();
        // 24-vertex cube (per-face normals/uvs), half extent 0.5
        float[][] faces = {
                {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
        };
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (float[] n : faces) {
            float[] u = Math.abs(n[1]) > 0.5f ? new float[]{1, 0, 0} : new float[]{n[2], 0, -n[0]};
            float[] v = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
            int base = positions.size() / 3;
            float[][] corners = {{-0.5f, -0.5f}, {0.5f, -0.5f}, {0.5f, 0.5f}, {-0.5f, 0.5f}};
            for (float[] corner : corners) {
                for (int axis = 0; axis < 3; axis++) {
                    positions.add(n[axis] * 0.5f + u[axis] * corner[0] + v[axis] * corner[1]);
                    normals.add(n[axis]);
                }
                uvs.add(corner[0] + 0.5f);
                uvs.add(0.5f - corner[1]);
            }
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }
        int positionAccessor = b.addFloatAccessor(toArray(positions), "VEC3", true);
        int normalAccessor = b.addFloatAccessor(toArray(normals), "VEC3", false);
        int uvAccessor = b.addFloatAccessor(toArray(uvs), "VEC2", false);
        int indexAccessor = b.addU16Accessor(indices.stream().mapToInt(Integer::intValue).toArray(), "SCALAR");
        int imageView = b.addBufferView(cratePng());

        String json = """
                {"asset":{"version":"2.0","generator":"tofuxia SampleGltfWriter"},
                "scene":0,"scenes":[{"nodes":[0]}],
                "nodes":[
                  {"name":"crate_root","rotation":[0,0.2588,0,0.9659],"children":[1]},
                  {"name":"crate_mesh","translation":[0,0.5,0],"mesh":0}
                ],
                "meshes":[{"name":"crate","primitives":[{"attributes":{"POSITION":%d,"NORMAL":%d,"TEXCOORD_0":%d},"indices":%d,"material":0}]}],
                "materials":[{"name":"crate_wood","pbrMetallicRoughness":{"baseColorTexture":{"index":0},"baseColorFactor":[1,1,1,1]},"alphaMode":"OPAQUE"}],
                "textures":[{"source":0,"sampler":0}],
                "samplers":[{"magFilter":9728,"minFilter":9728,"wrapS":10497,"wrapT":10497}],
                "images":[{"bufferView":%d,"mimeType":"image/png"}],
                "bufferViews":%s,"accessors":%s,
                "buffers":[{"byteLength":%d}]}
                """.formatted(positionAccessor, normalAccessor, uvAccessor, indexAccessor,
                imageView, b.bufferViewsJson(), b.accessorsJson(), b.bin.size());
        return glb(json, b.bin.toByteArray());
    }

    private static byte[] cratePng() throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                boolean border = x < 3 || y < 3 || x >= 29 || y >= 29;
                boolean stripe = ((x + y) / 4) % 2 == 0;
                int r = border ? 96 : stripe ? 176 : 148;
                int g = border ? 64 : stripe ? 120 : 96;
                int bl = border ? 40 : stripe ? 62 : 48;
                image.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | bl);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------- gem

    private record GemFiles(String json, byte[] bin) {
    }

    private static GemFiles buildGemGltf() {
        Builder b = new Builder();
        float[] positions = {
                0, 0.7f, 0,   0, -0.7f, 0,
                0.45f, 0, 0,  -0.45f, 0, 0,
                0, 0, 0.45f,  0, 0, -0.45f,
        };
        int[] indices = {
                0, 4, 2, 0, 2, 5, 0, 5, 3, 0, 3, 4,
                1, 2, 4, 1, 5, 2, 1, 3, 5, 1, 4, 3,
        };
        // deliberately no NORMAL attribute: exercises the importer fallback
        int positionAccessor = b.addFloatAccessor(positions, "VEC3", true);
        int indexAccessor = b.addU16Accessor(indices, "SCALAR");
        String json = """
                {"asset":{"version":"2.0","generator":"tofuxia SampleGltfWriter"},
                "scene":0,"scenes":[{"nodes":[0]}],
                "nodes":[{"name":"gem","mesh":0,"translation":[0,0.75,0],"scale":[1.2,1.2,1.2]}],
                "meshes":[{"name":"gem","primitives":[{"attributes":{"POSITION":%d},"indices":%d,"material":0}]}],
                "materials":[{"name":"gem_glass","pbrMetallicRoughness":{"baseColorFactor":[0.4,0.7,1.0,0.5]},"alphaMode":"BLEND","doubleSided":true,"emissiveFactor":[0.1,0.3,0.6]}],
                "bufferViews":%s,"accessors":%s,
                "buffers":[{"uri":"gem.bin","byteLength":%d}]}
                """.formatted(positionAccessor, indexAccessor,
                b.bufferViewsJson(), b.accessorsJson(), b.bin.size());
        return new GemFiles(json, b.bin.toByteArray());
    }

    // ---------------------------------------------------------------- walker

    private static byte[] buildWalkerGlb() {
        Builder b = new Builder();
        // segmented column: square rings from y=0 to y=1.5, tapered toward the top
        int rings = 7;
        float height = 1.5f;
        float[] jointHeights = {0.0f, 0.55f, 1.10f};
        List<Float> positions = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        List<Float> uvs = new ArrayList<>();
        List<Float> colors = new ArrayList<>();
        List<Integer> joints = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        float[][] corners = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
        for (int ring = 0; ring <= rings; ring++) {
            float t = ring / (float) rings;
            float y = t * height;
            float halfWidth = 0.28f * (1.0f - t * 0.35f);
            for (int c = 0; c < 4; c++) {
                positions.add(corners[c][0] * halfWidth);
                positions.add(y);
                positions.add(corners[c][1] * halfWidth);
                float inv = (float) (1.0 / Math.sqrt(2.0));
                normals.add(corners[c][0] * inv);
                normals.add(0.0f);
                normals.add(corners[c][1] * inv);
                uvs.add(c / 4.0f);
                uvs.add(1.0f - t);
                // soft beige body with a warm top
                colors.add(0.95f - t * 0.1f);
                colors.add(0.9f);
                colors.add(0.82f + t * 0.12f);
                colors.add(1.0f);
                addSkinWeights(y, jointHeights, joints, weights);
            }
        }
        for (int ring = 0; ring < rings; ring++) {
            for (int c = 0; c < 4; c++) {
                int i0 = ring * 4 + c;
                int i1 = ring * 4 + (c + 1) % 4;
                int i2 = i0 + 4;
                int i3 = i1 + 4;
                indices.add(i0);
                indices.add(i1);
                indices.add(i2);
                indices.add(i1);
                indices.add(i3);
                indices.add(i2);
            }
        }
        // top cap
        int topCenter = positions.size() / 3;
        positions.add(0.0f);
        positions.add(height);
        positions.add(0.0f);
        normals.add(0.0f);
        normals.add(1.0f);
        normals.add(0.0f);
        uvs.add(0.5f);
        uvs.add(0.0f);
        colors.add(0.9f);
        colors.add(0.85f);
        colors.add(0.95f);
        colors.add(1.0f);
        addSkinWeights(height, jointHeights, joints, weights);
        int lastRing = rings * 4;
        for (int c = 0; c < 4; c++) {
            indices.add(lastRing + c);
            indices.add(lastRing + (c + 1) % 4);
            indices.add(topCenter);
        }

        int positionAccessor = b.addFloatAccessor(toArray(positions), "VEC3", true);
        int normalAccessor = b.addFloatAccessor(toArray(normals), "VEC3", false);
        int uvAccessor = b.addFloatAccessor(toArray(uvs), "VEC2", false);
        int colorAccessor = b.addFloatAccessor(toArray(colors), "VEC4", false);
        int jointsAccessor = b.addU16Accessor(joints.stream().mapToInt(Integer::intValue).toArray(), "VEC4");
        int weightsAccessor = b.addFloatAccessor(toArray(weights), "VEC4", false);
        int indexAccessor = b.addU16Accessor(indices.stream().mapToInt(Integer::intValue).toArray(), "SCALAR");

        float[] inverseBind = new float[16 * 3];
        for (int j = 0; j < 3; j++) {
            // column-major identity with -jointHeight y translation
            inverseBind[j * 16] = 1;
            inverseBind[j * 16 + 5] = 1;
            inverseBind[j * 16 + 10] = 1;
            inverseBind[j * 16 + 15] = 1;
            inverseBind[j * 16 + 13] = -jointHeights[j];
        }
        int ibmAccessor = b.addFloatAccessor(inverseBind, "MAT4", false);

        // clip "sway": LINEAR rotations on mid/top joints, LINEAR root bob, STEP top scale
        float[] times5 = {0.0f, 0.5f, 1.0f, 1.5f, 2.0f};
        int timeAccessor5 = b.addFloatAccessor(times5, "SCALAR", true);
        float lean = 0.30f;
        int midRotation = b.addFloatAccessor(zRotationKeys(new float[]{-lean, lean, -lean, lean, -lean}), "VEC4", false);
        int topRotation = b.addFloatAccessor(zRotationKeys(new float[]{lean, -lean, lean, -lean, lean}), "VEC4", false);
        int rootBob = b.addFloatAccessor(new float[]{
                0, 0, 0, 0, 0.08f, 0, 0, 0, 0, 0, 0.08f, 0, 0, 0, 0}, "VEC3", false);
        float[] stepTimes = {0.0f, 0.9f, 1.1f, 2.0f};
        int stepTimeAccessor = b.addFloatAccessor(stepTimes, "SCALAR", true);
        int topScale = b.addFloatAccessor(new float[]{
                1, 1, 1, 1.18f, 0.85f, 1.18f, 1, 1, 1, 1, 1, 1}, "VEC3", false);

        // clip "spin": LINEAR full-turn rotation of the root joint
        float[] spinTimes = {0.0f, 0.75f, 1.5f, 2.25f, 3.0f};
        int spinTimeAccessor = b.addFloatAccessor(spinTimes, "SCALAR", true);
        int spinRotation = b.addFloatAccessor(yRotationKeys(new float[]{
                0.0f, (float) (Math.PI * 0.5), (float) Math.PI, (float) (Math.PI * 1.5), (float) (Math.PI * 2.0)}), "VEC4", false);

        String json = """
                {"asset":{"version":"2.0","generator":"tofuxia SampleGltfWriter"},
                "scene":0,"scenes":[{"nodes":[0,3]}],
                "nodes":[
                  {"name":"root_joint","children":[1]},
                  {"name":"mid_joint","translation":[0,0.55,0],"children":[2]},
                  {"name":"top_joint","translation":[0,0.55,0]},
                  {"name":"body","mesh":0,"skin":0}
                ],
                "meshes":[{"name":"body","primitives":[{"attributes":{"POSITION":%d,"NORMAL":%d,"TEXCOORD_0":%d,"COLOR_0":%d,"JOINTS_0":%d,"WEIGHTS_0":%d},"indices":%d,"material":0}]}],
                "materials":[{"name":"tofu_skin","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1]},"alphaMode":"OPAQUE"}],
                "skins":[{"name":"tofu_skin","joints":[0,1,2],"inverseBindMatrices":%d,"skeleton":0}],
                "animations":[
                  {"name":"sway","samplers":[
                     {"input":%d,"output":%d,"interpolation":"LINEAR"},
                     {"input":%d,"output":%d,"interpolation":"LINEAR"},
                     {"input":%d,"output":%d,"interpolation":"LINEAR"},
                     {"input":%d,"output":%d,"interpolation":"STEP"}],
                   "channels":[
                     {"sampler":0,"target":{"node":1,"path":"rotation"}},
                     {"sampler":1,"target":{"node":2,"path":"rotation"}},
                     {"sampler":2,"target":{"node":0,"path":"translation"}},
                     {"sampler":3,"target":{"node":2,"path":"scale"}}]},
                  {"name":"spin","samplers":[
                     {"input":%d,"output":%d,"interpolation":"LINEAR"}],
                   "channels":[
                     {"sampler":0,"target":{"node":0,"path":"rotation"}}]}
                ],
                "bufferViews":%s,"accessors":%s,
                "buffers":[{"byteLength":%d}]}
                """.formatted(
                positionAccessor, normalAccessor, uvAccessor, colorAccessor, jointsAccessor, weightsAccessor,
                indexAccessor, ibmAccessor,
                timeAccessor5, midRotation, timeAccessor5, topRotation, timeAccessor5, rootBob,
                stepTimeAccessor, topScale,
                spinTimeAccessor, spinRotation,
                b.bufferViewsJson(), b.accessorsJson(), b.bin.size());
        return glb(json, b.bin.toByteArray());
    }

    /** Hat-function weights of a height against the three joint heights. */
    private static void addSkinWeights(float y, float[] jointHeights,
                                       List<Integer> joints, List<Float> weights) {
        float spacing = jointHeights[1] - jointHeights[0];
        float[] w = new float[3];
        float sum = 0;
        for (int j = 0; j < 3; j++) {
            w[j] = Math.max(0.0f, 1.0f - Math.abs(y - jointHeights[j]) / spacing);
            sum += w[j];
        }
        if (sum <= 0) {
            // above the last joint: fully bound to it
            w[2] = 1;
            sum = 1;
        }
        joints.add(0);
        joints.add(1);
        joints.add(2);
        joints.add(0);
        weights.add(w[0] / sum);
        weights.add(w[1] / sum);
        weights.add(w[2] / sum);
        weights.add(0.0f);
    }

    private static float[] zRotationKeys(float[] angles) {
        float[] out = new float[angles.length * 4];
        for (int i = 0; i < angles.length; i++) {
            out[i * 4 + 2] = (float) Math.sin(angles[i] * 0.5);
            out[i * 4 + 3] = (float) Math.cos(angles[i] * 0.5);
        }
        return out;
    }

    private static float[] yRotationKeys(float[] angles) {
        float[] out = new float[angles.length * 4];
        for (int i = 0; i < angles.length; i++) {
            out[i * 4 + 1] = (float) Math.sin(angles[i] * 0.5);
            out[i * 4 + 3] = (float) Math.cos(angles[i] * 0.5);
        }
        return out;
    }

    private static float[] toArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }
}
