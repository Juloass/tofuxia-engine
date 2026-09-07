package fr.tofuxia.renderer;

/**
 * Everything that makes two draws need different VkPipelines. The render
 * pass/output format is implicit because the whole frame renders into the
 * single swapchain render pass for now; add it here when offscreen passes
 * appear.
 */
public record PipelineKey(
        ShaderVariantKey variant,
        BlendMode blendMode,
        boolean depthTest,
        boolean depthWrite,
        CullMode cullMode,
        VertexLayout vertexLayout) {

    public static PipelineKey of(Material material, VertexLayout layout) {
        int mask = material.featureMask();
        mask &= ~MaterialFeature.FOG.bit();
        if (layout == VertexLayout.SKINNED) {
            mask |= MaterialFeature.SKINNED.bit();
        } else {
            mask &= ~MaterialFeature.SKINNED.bit();
        }
        if (layout == VertexLayout.VOXEL) {
            mask |= MaterialFeature.VOXEL_DATA.bit();
        } else {
            mask &= ~MaterialFeature.VOXEL_DATA.bit();
            mask &= ~MaterialFeature.TERRAIN_OCCLUSION_CUTAWAY.bit();
        }
        return new PipelineKey(
                new ShaderVariantKey(mask),
                material.blendMode(),
                material.depthTest(),
                material.depthWrite(),
                material.cullMode(),
                layout);
    }

    public String describe() {
        return variant.describe() + " blend=" + blendMode + " cull=" + cullMode
                + " depth=" + (depthTest ? "T" : "-") + (depthWrite ? "W" : "-")
                + " layout=" + vertexLayout;
    }
}
