package fr.tofuxia.gltf;

import fr.tofuxia.renderer.CpuMesh;
import fr.tofuxia.renderer.Material;
import fr.tofuxia.renderer.SamplerSettings;

import java.util.List;

/**
 * CPU-side result of a glTF import: everything converted into engine-owned
 * structures, no GPU resources yet. Upload with
 * {@link ModelAsset#upload(ModelData, fr.tofuxia.renderer.Renderer)}.
 * Keeping this stage separate lets the headless self-test exercise the whole
 * importer without a Vulkan device.
 */
public record ModelData(
        String name,
        List<ModelNode> nodes,
        int[] rootNodes,
        List<Mesh> meshes,
        List<Material> materials,
        List<EmbeddedImage> embeddedImages,
        List<ModelSkin> skins,
        List<AnimationClip> animations,
        List<String> diagnostics) {

    /** A glTF mesh: one or more primitives, each with its own material. */
    public record Mesh(String name, List<Primitive> primitives) {
    }

    public record Primitive(CpuMesh mesh, int materialIndex) {
    }

    /** Image embedded in the glTF (GLB buffer view or data URI), pre-registration. */
    public record EmbeddedImage(String memName, byte[] encodedBytes, SamplerSettings sampler, boolean srgb) {
    }

    public int totalPrimitives() {
        int total = 0;
        for (Mesh mesh : meshes) total += mesh.primitives().size();
        return total;
    }
}
