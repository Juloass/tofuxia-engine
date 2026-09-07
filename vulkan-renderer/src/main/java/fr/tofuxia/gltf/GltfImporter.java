package fr.tofuxia.gltf;

import fr.tofuxia.renderer.BlendMode;
import fr.tofuxia.renderer.CpuMesh;
import fr.tofuxia.renderer.CullMode;
import fr.tofuxia.renderer.Material;
import fr.tofuxia.renderer.MaterialFeature;
import fr.tofuxia.renderer.SamplerSettings;
import fr.tofuxia.renderer.ShaderLibrary;
import fr.tofuxia.renderer.ShadingModel;
import fr.tofuxia.renderer.VertexLayout;
import fr.tofuxia.util.Json;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * glTF 2.0 importer. Reads {@code .gltf} (JSON + external/data-URI buffers)
 * and {@code .glb} (binary container) and converts everything into
 * engine-owned {@link ModelData}: CpuMeshes in the canonical vertex layouts,
 * engine {@link Material}s, skins, and animation clips. Never hands
 * third-party structures to the renderer.
 *
 * Supported: triangle primitives with POSITION / NORMAL / TEXCOORD_0 /
 * COLOR_0 / JOINTS_0 / WEIGHTS_0, node hierarchies (TRS and matrix), skins
 * with inverse bind matrices, LINEAR and STEP animation of T/R/S. Everything
 * else is skipped with a diagnostic instead of failing the import.
 */
public final class GltfImporter {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private static final int COMP_BYTE = 5120;
    private static final int COMP_UBYTE = 5121;
    private static final int COMP_SHORT = 5122;
    private static final int COMP_USHORT = 5123;
    private static final int COMP_UINT = 5125;
    private static final int COMP_FLOAT = 5126;

    private final String modelName;
    private final Path baseDir;
    private final Json.JsonObject gltf;
    private final List<byte[]> buffers = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();

    public static ModelData load(Path file) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase();
        GltfImporter importer;
        if (fileName.endsWith(".glb")) {
            importer = fromGlb(file);
        } else {
            importer = new GltfImporter(file, Json.parseObject(Files.readString(file)), null);
        }
        ModelData model = importer.convert();
        System.out.println("[gltf] imported " + model.name() + ": " + model.nodes().size() + " nodes, "
                + model.meshes().size() + " meshes (" + model.totalPrimitives() + " primitives), "
                + model.materials().size() + " materials, " + model.skins().size() + " skins, "
                + model.animations().size() + " animations");
        for (String diagnostic : model.diagnostics()) {
            System.out.println("[gltf] WARN " + model.name() + ": " + diagnostic);
        }
        return model;
    }

    private static GltfImporter fromGlb(Path file) throws IOException {
        ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (data.remaining() < 12 || data.getInt() != GLB_MAGIC) {
            throw new IOException("Not a GLB file (bad magic): " + file);
        }
        int version = data.getInt();
        if (version != 2) throw new IOException("Unsupported GLB version " + version + ": " + file);
        data.getInt(); // total length
        Json.JsonObject json = null;
        byte[] bin = null;
        while (data.remaining() >= 8) {
            int chunkLength = data.getInt();
            int chunkType = data.getInt();
            byte[] chunk = new byte[chunkLength];
            data.get(chunk);
            if (chunkType == CHUNK_JSON) {
                json = Json.parseObject(new String(chunk, StandardCharsets.UTF_8));
            } else if (chunkType == CHUNK_BIN) {
                bin = chunk;
            }
        }
        if (json == null) throw new IOException("GLB has no JSON chunk: " + file);
        return new GltfImporter(file, json, bin);
    }

    private GltfImporter(Path file, Json.JsonObject gltf, byte[] glbBin) throws IOException {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        this.modelName = dot > 0 ? fileName.substring(0, dot) : fileName;
        this.baseDir = file.toAbsolutePath().getParent();
        this.gltf = gltf;
        loadBuffers(glbBin);
    }

    // -------------------------------------------------------------- buffers

    private void loadBuffers(byte[] glbBin) throws IOException {
        Json.JsonArray bufferDefs = gltf.getArray("buffers");
        if (bufferDefs == null) return;
        for (int i = 0; i < bufferDefs.size(); i++) {
            Json.JsonObject def = bufferDefs.getObject(i);
            String uri = def.getString("uri", null);
            if (uri == null) {
                if (glbBin == null) throw new IOException("buffer " + i + " has no uri and no GLB BIN chunk");
                buffers.add(glbBin);
            } else if (uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                buffers.add(Base64.getDecoder().decode(uri.substring(comma + 1)));
            } else {
                buffers.add(Files.readAllBytes(baseDir.resolve(uri)));
            }
        }
    }

    private byte[] resolveBufferView(Json.JsonObject view) {
        int bufferIndex = view.getInt("buffer", 0);
        int offset = view.getInt("byteOffset", 0);
        int length = view.getInt("byteLength", 0);
        byte[] buffer = buffers.get(bufferIndex);
        byte[] out = new byte[length];
        System.arraycopy(buffer, offset, out, 0, length);
        return out;
    }

    // ------------------------------------------------------------ accessors

    private record AccessorInfo(int componentType, int componentCount, int count, boolean normalized,
                                ByteBuffer data, int stride) {
    }

    private AccessorInfo accessor(int index) {
        Json.JsonObject accessor = gltf.getArray("accessors").getObject(index);
        if (accessor.getObject("sparse") != null) {
            throw new IllegalStateException("sparse accessors are not supported (accessor " + index + ")");
        }
        int componentType = accessor.getInt("componentType", COMP_FLOAT);
        int count = accessor.getInt("count", 0);
        boolean normalized = accessor.getBoolean("normalized", false);
        int componentCount = switch (accessor.getString("type", "SCALAR")) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IllegalStateException("unsupported accessor type "
                    + accessor.getString("type", "?") + " (accessor " + index + ")");
        };
        int componentSize = componentSize(componentType);
        int packed = componentSize * componentCount;
        int viewIndex = accessor.getInt("bufferView", -1);
        if (viewIndex < 0) {
            // all-zeros accessor per spec
            ByteBuffer zeros = ByteBuffer.allocate(packed * count).order(ByteOrder.LITTLE_ENDIAN);
            return new AccessorInfo(componentType, componentCount, count, normalized, zeros, packed);
        }
        Json.JsonObject view = gltf.getArray("bufferViews").getObject(viewIndex);
        int stride = view.getInt("byteStride", 0);
        if (stride == 0) stride = packed;
        int accessorOffset = accessor.getInt("byteOffset", 0);
        byte[] bufferBytes = buffers.get(view.getInt("buffer", 0));
        int start = view.getInt("byteOffset", 0) + accessorOffset;
        ByteBuffer data = ByteBuffer.wrap(bufferBytes, start, bufferBytes.length - start)
                .order(ByteOrder.LITTLE_ENDIAN).slice().order(ByteOrder.LITTLE_ENDIAN);
        return new AccessorInfo(componentType, componentCount, count, normalized, data, stride);
    }

    private static int componentSize(int componentType) {
        return switch (componentType) {
            case COMP_BYTE, COMP_UBYTE -> 1;
            case COMP_SHORT, COMP_USHORT -> 2;
            case COMP_UINT, COMP_FLOAT -> 4;
            default -> throw new IllegalStateException("unknown component type " + componentType);
        };
    }

    /** Reads an accessor as floats, denormalizing integer components per spec. */
    private float[] readFloats(int accessorIndex) {
        AccessorInfo info = accessor(accessorIndex);
        float[] out = new float[info.count * info.componentCount];
        int componentSize = componentSize(info.componentType);
        for (int element = 0; element < info.count; element++) {
            int base = element * info.stride;
            for (int component = 0; component < info.componentCount; component++) {
                int at = base + component * componentSize;
                out[element * info.componentCount + component] = switch (info.componentType) {
                    case COMP_FLOAT -> info.data.getFloat(at);
                    case COMP_UBYTE -> info.normalized
                            ? (info.data.get(at) & 0xFF) / 255.0f : (info.data.get(at) & 0xFF);
                    case COMP_USHORT -> info.normalized
                            ? (info.data.getShort(at) & 0xFFFF) / 65535.0f : (info.data.getShort(at) & 0xFFFF);
                    case COMP_BYTE -> info.normalized
                            ? Math.max(info.data.get(at) / 127.0f, -1.0f) : info.data.get(at);
                    case COMP_SHORT -> info.normalized
                            ? Math.max(info.data.getShort(at) / 32767.0f, -1.0f) : info.data.getShort(at);
                    case COMP_UINT -> (float) (info.data.getInt(at) & 0xFFFFFFFFL);
                    default -> 0.0f;
                };
            }
        }
        return out;
    }

    /** Reads an accessor as integers (indices, joints). */
    private int[] readInts(int accessorIndex) {
        AccessorInfo info = accessor(accessorIndex);
        int[] out = new int[info.count * info.componentCount];
        int componentSize = componentSize(info.componentType);
        for (int element = 0; element < info.count; element++) {
            int base = element * info.stride;
            for (int component = 0; component < info.componentCount; component++) {
                int at = base + component * componentSize;
                out[element * info.componentCount + component] = switch (info.componentType) {
                    case COMP_UBYTE -> info.data.get(at) & 0xFF;
                    case COMP_USHORT -> info.data.getShort(at) & 0xFFFF;
                    case COMP_UINT -> info.data.getInt(at);
                    case COMP_BYTE -> info.data.get(at);
                    case COMP_SHORT -> info.data.getShort(at);
                    case COMP_FLOAT -> (int) info.data.getFloat(at);
                    default -> 0;
                };
            }
        }
        return out;
    }

    // ------------------------------------------------------------ conversion

    private ModelData convert() {
        List<Material.Builder> materialBuilders = parseMaterialBuilders();
        List<ModelData.EmbeddedImage> images = new ArrayList<>();
        boolean[] needsVertexColor = new boolean[materialBuilders.size()];
        List<ModelData.Mesh> meshes = parseMeshes(materialBuilders.size(), needsVertexColor);
        List<Material> materials = resolveMaterials(materialBuilders, needsVertexColor, images);
        List<ModelNode> nodes = parseNodes();
        int[] roots = parseSceneRoots(nodes.size());
        List<ModelSkin> skins = parseSkins(nodes);
        List<AnimationClip> animations = parseAnimations(nodes.size());
        return new ModelData(modelName, nodes, roots, meshes, materials, images, skins, animations,
                List.copyOf(diagnostics));
    }

    private List<ModelNode> parseNodes() {
        List<ModelNode> nodes = new ArrayList<>();
        Json.JsonArray nodeDefs = gltf.getArray("nodes");
        if (nodeDefs == null) return nodes;
        for (int i = 0; i < nodeDefs.size(); i++) {
            Json.JsonObject def = nodeDefs.getObject(i);
            Vector3f translation = new Vector3f();
            Quaternionf rotation = new Quaternionf();
            Vector3f scale = new Vector3f(1, 1, 1);
            float[] matrix = def.getFloats("matrix", null);
            if (matrix != null && matrix.length == 16) {
                Matrix4f m = new Matrix4f().set(matrix);
                m.getTranslation(translation);
                m.getUnnormalizedRotation(rotation).normalize();
                m.getScale(scale);
            } else {
                float[] t = def.getFloats("translation", null);
                if (t != null && t.length == 3) translation.set(t[0], t[1], t[2]);
                float[] r = def.getFloats("rotation", null);
                if (r != null && r.length == 4) rotation.set(r[0], r[1], r[2], r[3]);
                float[] s = def.getFloats("scale", null);
                if (s != null && s.length == 3) scale.set(s[0], s[1], s[2]);
            }
            Json.JsonArray childArray = def.getArray("children");
            int[] children = childArray == null ? new int[0] : childArray.toInts();
            nodes.add(new ModelNode(
                    def.getString("name", "node" + i),
                    def.getInt("mesh", -1),
                    def.getInt("skin", -1),
                    children, translation, rotation, scale));
        }
        return nodes;
    }

    private int[] parseSceneRoots(int nodeCount) {
        Json.JsonArray scenes = gltf.getArray("scenes");
        if (scenes != null && !scenes.isEmpty()) {
            int sceneIndex = gltf.getInt("scene", 0);
            if (sceneIndex >= scenes.size()) {
                diagnostics.add("default scene index " + sceneIndex + " out of range; using scene 0");
                sceneIndex = 0;
            }
            Json.JsonArray rootArray = scenes.getObject(sceneIndex).getArray("nodes");
            if (rootArray != null) return rootArray.toInts();
        }
        // no scene: treat all nodes without parents as roots
        boolean[] isChild = new boolean[nodeCount];
        Json.JsonArray nodeDefs = gltf.getArray("nodes");
        if (nodeDefs != null) {
            for (int i = 0; i < nodeDefs.size(); i++) {
                Json.JsonArray children = nodeDefs.getObject(i).getArray("children");
                if (children != null) {
                    for (int child : children.toInts()) isChild[child] = true;
                }
            }
        }
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            if (!isChild[i]) roots.add(i);
        }
        return roots.stream().mapToInt(Integer::intValue).toArray();
    }

    // ------------------------------------------------------------- materials

    private List<Material.Builder> parseMaterialBuilders() {
        List<Material.Builder> builders = new ArrayList<>();
        Json.JsonArray materialDefs = gltf.getArray("materials");
        if (materialDefs == null) return builders;
        for (int i = 0; i < materialDefs.size(); i++) {
            Json.JsonObject def = materialDefs.getObject(i);
            Material.Builder builder = Material.builder(modelName + "/" + def.getString("name", "material" + i))
                    .shadingModel(ShadingModel.STANDARD_LIT);
            switch (def.getString("alphaMode", "OPAQUE")) {
                case "MASK" -> builder.blendMode(BlendMode.CUTOUT)
                        .alphaCutoff((float) def.getDouble("alphaCutoff", 0.5));
                case "BLEND" -> builder.blendMode(BlendMode.TRANSPARENT);
                default -> builder.blendMode(BlendMode.OPAQUE);
            }
            builder.cullMode(def.getBoolean("doubleSided", false) ? CullMode.NONE : CullMode.BACK);
            Json.JsonObject pbr = def.getObject("pbrMetallicRoughness");
            if (pbr != null) {
                float[] baseColor = pbr.getFloats("baseColorFactor", new float[]{1, 1, 1, 1});
                if (baseColor.length == 4) {
                    builder.baseColorFactor(baseColor[0], baseColor[1], baseColor[2], baseColor[3]);
                }
                Json.JsonObject baseColorTexture = pbr.getObject("baseColorTexture");
                if (baseColorTexture != null) {
                    applyBaseColorTexture(builder, baseColorTexture.getInt("index", -1));
                    if (baseColorTexture.getInt("texCoord", 0) != 0) {
                        diagnostics.add("material " + i + " uses TEXCOORD_" + baseColorTexture.getInt("texCoord", 0)
                                + "; only TEXCOORD_0 is supported");
                    }
                }
            }
            float[] emissive = def.getFloats("emissiveFactor", new float[]{0, 0, 0});
            float emissiveMax = Math.max(emissive[0], Math.max(emissive[1], emissive[2]));
            if (emissiveMax > 0.0f) {
                builder.emissive(emissive[0] / emissiveMax, emissive[1] / emissiveMax, emissive[2] / emissiveMax,
                        emissiveMax);
            }
            if (def.getObject("emissiveTexture") != null) {
                diagnostics.add("material " + i + " has an emissiveTexture; only emissiveFactor is supported");
            }
            builders.add(builder);
        }
        return builders;
    }

    private void applyBaseColorTexture(Material.Builder builder, int textureIndex) {
        if (textureIndex < 0) return;
        Json.JsonObject texture = gltf.getArray("textures").getObject(textureIndex);
        int imageIndex = texture.getInt("source", -1);
        if (imageIndex < 0) {
            diagnostics.add("texture " + textureIndex + " has no image source");
            return;
        }
        builder.baseColorTexture("mem://" + modelName + "/image" + imageIndex);
        builder.sampler(parseSampler(texture.getInt("sampler", -1)));
    }

    private SamplerSettings parseSampler(int samplerIndex) {
        if (samplerIndex < 0) return SamplerSettings.LINEAR_REPEAT;
        Json.JsonObject sampler = gltf.getArray("samplers").getObject(samplerIndex);
        int magFilter = sampler.getInt("magFilter", 9729);
        int wrapS = sampler.getInt("wrapS", 10497);
        SamplerSettings.Filter filter = magFilter == 9728
                ? SamplerSettings.Filter.NEAREST : SamplerSettings.Filter.LINEAR;
        SamplerSettings.Wrap wrap = wrapS == 33071
                ? SamplerSettings.Wrap.CLAMP : SamplerSettings.Wrap.REPEAT;
        return new SamplerSettings(filter, wrap);
    }

    private List<Material> resolveMaterials(List<Material.Builder> builders, boolean[] needsVertexColor,
                                            List<ModelData.EmbeddedImage> images) {
        List<Material> materials = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            Material.Builder builder = builders.get(i);
            if (needsVertexColor[i]) {
                builder.feature(MaterialFeature.VERTEX_COLOR);
            }
            Material material = builder.resolve(diagnostics::add);
            materials.add(material);
            collectEmbeddedImage(material, images);
        }
        // shared default for primitives without a material
        materials.add(Material.builder(modelName + "/default")
                .shadingModel(ShadingModel.STANDARD_LIT)
                .baseColorFactor(0.8f, 0.8f, 0.8f, 1.0f)
                .resolve(diagnostics::add));
        return materials;
    }

    /** Extracts the encoded bytes for a material's mem:// texture reference. */
    private void collectEmbeddedImage(Material material, List<ModelData.EmbeddedImage> images) {
        String texturePath = material.baseColorTexture();
        if (texturePath == null || !texturePath.startsWith("mem://")) return;
        for (ModelData.EmbeddedImage existing : images) {
            if (existing.memName().equals(texturePath)) return;
        }
        int imageIndex = Integer.parseInt(texturePath.substring(texturePath.lastIndexOf("image") + 5));
        Json.JsonObject image = gltf.getArray("images").getObject(imageIndex);
        try {
            byte[] bytes;
            String uri = image.getString("uri", null);
            if (uri != null && uri.startsWith("data:")) {
                bytes = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
            } else if (uri != null) {
                bytes = Files.readAllBytes(baseDir.resolve(uri));
            } else {
                int viewIndex = image.getInt("bufferView", -1);
                if (viewIndex < 0) throw new IOException("image has neither uri nor bufferView");
                bytes = resolveBufferView(gltf.getArray("bufferViews").getObject(viewIndex));
            }
            images.add(new ModelData.EmbeddedImage(texturePath, bytes, material.sampler(), true));
        } catch (IOException e) {
            diagnostics.add("image " + imageIndex + " unreadable (" + e.getMessage() + "); texture will fall back");
        }
    }

    // ---------------------------------------------------------------- meshes

    private List<ModelData.Mesh> parseMeshes(int materialCount, boolean[] needsVertexColor) {
        List<ModelData.Mesh> meshes = new ArrayList<>();
        Json.JsonArray meshDefs = gltf.getArray("meshes");
        if (meshDefs == null) return meshes;
        for (int meshIndex = 0; meshIndex < meshDefs.size(); meshIndex++) {
            Json.JsonObject meshDef = meshDefs.getObject(meshIndex);
            String meshName = meshDef.getString("name", "mesh" + meshIndex);
            List<ModelData.Primitive> primitives = new ArrayList<>();
            Json.JsonArray primitiveDefs = meshDef.getArray("primitives");
            for (int primIndex = 0; primIndex < primitiveDefs.size(); primIndex++) {
                Json.JsonObject primitive = primitiveDefs.getObject(primIndex);
                int mode = primitive.getInt("mode", 4);
                if (mode != 4) {
                    diagnostics.add("mesh '" + meshName + "' primitive " + primIndex
                            + " uses unsupported mode " + mode + " (only TRIANGLES); skipped");
                    continue;
                }
                try {
                    CpuMesh mesh = convertPrimitive(modelName + "/" + meshName + "#" + primIndex, primitive,
                            materialCount, needsVertexColor);
                    int materialIndex = primitive.getInt("material", materialCount); // last = default material
                    primitives.add(new ModelData.Primitive(mesh, materialIndex));
                } catch (RuntimeException e) {
                    diagnostics.add("mesh '" + meshName + "' primitive " + primIndex
                            + " failed to convert: " + e.getMessage() + "; skipped");
                }
            }
            meshes.add(new ModelData.Mesh(meshName, primitives));
        }
        return meshes;
    }

    private CpuMesh convertPrimitive(String name, Json.JsonObject primitive,
                                     int materialCount, boolean[] needsVertexColor) {
        Json.JsonObject attributes = primitive.getObject("attributes");
        Integer positionAccessor = attributeIndex(attributes, "POSITION");
        if (positionAccessor == null) throw new IllegalStateException("no POSITION attribute");
        float[] positions = readFloats(positionAccessor);
        int vertexCount = positions.length / 3;

        Integer indexAccessor = primitive.getInt("indices", -1) >= 0 ? primitive.getInt("indices", -1) : null;
        int[] indices;
        if (indexAccessor != null) {
            indices = readInts(indexAccessor);
        } else {
            indices = new int[vertexCount];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
        }

        float[] normals;
        Integer normalAccessor = attributeIndex(attributes, "NORMAL");
        if (normalAccessor != null) {
            normals = readFloats(normalAccessor);
        } else {
            diagnostics.add(name + ": no NORMAL attribute; computed smooth normals");
            normals = computeSmoothNormals(positions, indices);
        }

        float[] uvs = null;
        Integer uvAccessor = attributeIndex(attributes, "TEXCOORD_0");
        if (uvAccessor != null) uvs = readFloats(uvAccessor);

        float[] colors = null;
        int colorComponents = 4;
        Integer colorAccessor = attributeIndex(attributes, "COLOR_0");
        if (colorAccessor != null) {
            colorComponents = accessor(colorAccessor).componentCount;
            colors = readFloats(colorAccessor);
            int materialIndex = primitive.getInt("material", -1);
            if (materialIndex >= 0 && materialIndex < materialCount) {
                needsVertexColor[materialIndex] = true;
            }
        }

        Integer jointsAccessor = attributeIndex(attributes, "JOINTS_0");
        Integer weightsAccessor = attributeIndex(attributes, "WEIGHTS_0");
        boolean skinned = jointsAccessor != null && weightsAccessor != null;
        if ((jointsAccessor != null) != (weightsAccessor != null)) {
            diagnostics.add(name + ": JOINTS_0/WEIGHTS_0 must appear together; imported as static");
        }
        int[] joints = skinned ? readInts(jointsAccessor) : null;
        float[] weights = skinned ? readFloats(weightsAccessor) : null;

        VertexLayout layout = skinned ? VertexLayout.SKINNED : VertexLayout.STATIC;
        float[] vertices = new float[vertexCount * layout.debugFloatComponents];
        for (int v = 0; v < vertexCount; v++) {
            int out = v * layout.debugFloatComponents;
            vertices[out] = positions[v * 3];
            vertices[out + 1] = positions[v * 3 + 1];
            vertices[out + 2] = positions[v * 3 + 2];
            vertices[out + 3] = normals[v * 3];
            vertices[out + 4] = normals[v * 3 + 1];
            vertices[out + 5] = normals[v * 3 + 2];
            vertices[out + 6] = uvs != null ? uvs[v * 2] : 0.0f;
            vertices[out + 7] = uvs != null ? uvs[v * 2 + 1] : 0.0f;
            if (colors != null) {
                vertices[out + 8] = colors[v * colorComponents];
                vertices[out + 9] = colors[v * colorComponents + 1];
                vertices[out + 10] = colors[v * colorComponents + 2];
                vertices[out + 11] = colorComponents == 4 ? colors[v * colorComponents + 3] : 1.0f;
            } else {
                vertices[out + 8] = 1.0f;
                vertices[out + 9] = 1.0f;
                vertices[out + 10] = 1.0f;
                vertices[out + 11] = 1.0f;
            }
            if (skinned) {
                float w0 = weights[v * 4];
                float w1 = weights[v * 4 + 1];
                float w2 = weights[v * 4 + 2];
                float w3 = weights[v * 4 + 3];
                float sum = w0 + w1 + w2 + w3;
                if (sum <= 0.0f) {
                    w0 = 1.0f;
                    sum = 1.0f;
                }
                vertices[out + 12] = joints[v * 4];
                vertices[out + 13] = joints[v * 4 + 1];
                vertices[out + 14] = joints[v * 4 + 2];
                vertices[out + 15] = joints[v * 4 + 3];
                vertices[out + 16] = w0 / sum;
                vertices[out + 17] = w1 / sum;
                vertices[out + 18] = w2 / sum;
                vertices[out + 19] = w3 / sum;
            }
        }
        return layout == VertexLayout.SKINNED
                ? CpuMesh.skinnedMesh(name, vertices, indices)
                : CpuMesh.staticMesh(name, vertices, indices);
    }

    private static Integer attributeIndex(Json.JsonObject attributes, String name) {
        if (attributes == null) return null;
        Object value = attributes.get(name);
        return value instanceof Double d ? (int) (double) d : null;
    }

    private static float[] computeSmoothNormals(float[] positions, int[] indices) {
        float[] normals = new float[positions.length];
        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int t = 0; t + 2 < indices.length; t += 3) {
            int a = indices[t] * 3;
            int b = indices[t + 1] * 3;
            int c = indices[t + 2] * 3;
            edge1.set(positions[b] - positions[a], positions[b + 1] - positions[a + 1], positions[b + 2] - positions[a + 2]);
            edge2.set(positions[c] - positions[a], positions[c + 1] - positions[a + 1], positions[c + 2] - positions[a + 2]);
            edge1.cross(edge2, normal);
            for (int base : new int[]{a, b, c}) {
                normals[base] += normal.x;
                normals[base + 1] += normal.y;
                normals[base + 2] += normal.z;
            }
        }
        for (int v = 0; v < normals.length; v += 3) {
            float length = (float) Math.sqrt(normals[v] * normals[v]
                    + normals[v + 1] * normals[v + 1] + normals[v + 2] * normals[v + 2]);
            if (length > 1e-6f) {
                normals[v] /= length;
                normals[v + 1] /= length;
                normals[v + 2] /= length;
            } else {
                normals[v + 1] = 1.0f;
            }
        }
        return normals;
    }

    // ----------------------------------------------------------------- skins

    private List<ModelSkin> parseSkins(List<ModelNode> nodes) {
        List<ModelSkin> skins = new ArrayList<>();
        Json.JsonArray skinDefs = gltf.getArray("skins");
        if (skinDefs == null) return skins;
        for (int i = 0; i < skinDefs.size(); i++) {
            Json.JsonObject def = skinDefs.getObject(i);
            int[] joints = def.getArray("joints").toInts();
            if (joints.length > ShaderLibrary.MAX_JOINTS) {
                diagnostics.add("skin " + i + " has " + joints.length + " joints; shader limit is "
                        + ShaderLibrary.MAX_JOINTS + " - extra joints will misbehave");
            }
            Matrix4f[] inverseBind = new Matrix4f[joints.length];
            int ibmAccessor = def.getInt("inverseBindMatrices", -1);
            if (ibmAccessor >= 0) {
                float[] raw = readFloats(ibmAccessor);
                for (int j = 0; j < joints.length; j++) {
                    inverseBind[j] = new Matrix4f().set(raw, j * 16);
                }
            } else {
                diagnostics.add("skin " + i + " has no inverseBindMatrices; using identity");
                for (int j = 0; j < joints.length; j++) {
                    inverseBind[j] = new Matrix4f();
                }
            }
            skins.add(new ModelSkin(def.getString("name", "skin" + i), joints, inverseBind,
                    def.getInt("skeleton", -1)));
        }
        return skins;
    }

    // ------------------------------------------------------------ animations

    private List<AnimationClip> parseAnimations(int nodeCount) {
        List<AnimationClip> clips = new ArrayList<>();
        Json.JsonArray animationDefs = gltf.getArray("animations");
        if (animationDefs == null) return clips;
        for (int a = 0; a < animationDefs.size(); a++) {
            Json.JsonObject def = animationDefs.getObject(a);
            String clipName = def.getString("name", "clip" + a);
            Json.JsonArray samplers = def.getArray("samplers");
            List<AnimationClip.Channel> channels = new ArrayList<>();
            float duration = 0.0f;
            Json.JsonArray channelDefs = def.getArray("channels");
            for (int c = 0; c < channelDefs.size(); c++) {
                Json.JsonObject channel = channelDefs.getObject(c);
                Json.JsonObject target = channel.getObject("target");
                int nodeIndex = target.getInt("node", -1);
                if (nodeIndex < 0 || nodeIndex >= nodeCount) {
                    diagnostics.add("animation '" + clipName + "' channel " + c + " has no valid target node; skipped");
                    continue;
                }
                String pathName = target.getString("path", "");
                AnimationClip.Path path = switch (pathName) {
                    case "translation" -> AnimationClip.Path.TRANSLATION;
                    case "rotation" -> AnimationClip.Path.ROTATION;
                    case "scale" -> AnimationClip.Path.SCALE;
                    default -> null;
                };
                if (path == null) {
                    diagnostics.add("animation '" + clipName + "' channel " + c
                            + " targets unsupported path '" + pathName + "'; skipped");
                    continue;
                }
                Json.JsonObject sampler = samplers.getObject(channel.getInt("sampler", 0));
                String interpolationName = sampler.getString("interpolation", "LINEAR");
                AnimationClip.Interpolation interpolation = switch (interpolationName) {
                    case "STEP" -> AnimationClip.Interpolation.STEP;
                    case "LINEAR" -> AnimationClip.Interpolation.LINEAR;
                    default -> null;
                };
                if (interpolation == null) {
                    diagnostics.add("animation '" + clipName + "' channel " + c + " uses unsupported interpolation '"
                            + interpolationName + "'; skipped");
                    continue;
                }
                float[] times = readFloats(sampler.getInt("input", 0));
                float[] values = readFloats(sampler.getInt("output", 0));
                int expected = times.length * (path == AnimationClip.Path.ROTATION ? 4 : 3);
                if (values.length != expected) {
                    diagnostics.add("animation '" + clipName + "' channel " + c + " has " + values.length
                            + " output floats, expected " + expected + "; skipped");
                    continue;
                }
                if (times.length == 0) continue;
                duration = Math.max(duration, times[times.length - 1]);
                channels.add(new AnimationClip.Channel(nodeIndex, path, interpolation, times, values));
            }
            clips.add(new AnimationClip(clipName, duration, channels));
        }
        return clips;
    }
}
