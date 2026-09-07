package fr.tofuxia.gltf;

import fr.tofuxia.renderer.GpuMesh;
import fr.tofuxia.renderer.Material;
import fr.tofuxia.renderer.Renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, GPU-ready model: uploaded mesh primitives with resolved engine
 * materials, node hierarchy, skins and animation clips. Many
 * {@link ModelInstance}s can reference one asset and animate independently.
 */
public final class ModelAsset {
    /** One drawable primitive: an uploaded mesh with its engine material. */
    public record Primitive(GpuMesh mesh, Material material) {
    }

    public record Mesh(String name, List<Primitive> primitives) {
    }

    private final String name;
    private final List<ModelNode> nodes;
    private final int[] rootNodes;
    private final List<Mesh> meshes;
    private final List<ModelSkin> skins;
    private final List<AnimationClip> animations;
    private final List<String> diagnostics;

    private ModelAsset(String name, List<ModelNode> nodes, int[] rootNodes, List<Mesh> meshes,
                       List<ModelSkin> skins, List<AnimationClip> animations, List<String> diagnostics) {
        this.name = name;
        this.nodes = nodes;
        this.rootNodes = rootNodes;
        this.meshes = meshes;
        this.skins = skins;
        this.animations = animations;
        this.diagnostics = diagnostics;
    }

    /**
     * Turns imported CPU data into a renderable asset: registers embedded
     * textures, registers materials, uploads mesh primitives. From here on
     * the model renders through the normal submit / material / pipeline path.
     */
    public static ModelAsset upload(ModelData data, Renderer renderer) {
        for (ModelData.EmbeddedImage image : data.embeddedImages()) {
            renderer.textures().fromEncodedBytes(image.memName(), image.encodedBytes(),
                    image.sampler(), image.srgb());
        }
        for (Material material : data.materials()) {
            renderer.materials().register(material);
        }
        List<Mesh> meshes = new ArrayList<>();
        for (ModelData.Mesh mesh : data.meshes()) {
            List<Primitive> primitives = new ArrayList<>();
            for (ModelData.Primitive primitive : mesh.primitives()) {
                Material material = data.materials().get(
                        Math.min(primitive.materialIndex(), data.materials().size() - 1));
                primitives.add(new Primitive(renderer.uploadMesh(primitive.mesh()), material));
            }
            meshes.add(new Mesh(mesh.name(), primitives));
        }
        return new ModelAsset(data.name(), data.nodes(), data.rootNodes(), meshes,
                data.skins(), data.animations(), data.diagnostics());
    }

    public String name() {
        return name;
    }

    public List<ModelNode> nodes() {
        return nodes;
    }

    public int[] rootNodes() {
        return rootNodes;
    }

    public List<Mesh> meshes() {
        return meshes;
    }

    public List<ModelSkin> skins() {
        return skins;
    }

    public List<AnimationClip> animations() {
        return animations;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }
}
