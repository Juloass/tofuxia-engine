package fr.tofuxia.renderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Creates and reuses VkPipelines keyed by {@link PipelineKey}. Pipelines use
 * dynamic viewport/scissor so they survive window resizes; a shader reload
 * clears the cache and rebuilds lazily.
 */
public final class PipelineCache implements AutoCloseable {
    private final VulkanContext context;
    private final DescriptorManager descriptors;
    private final ShaderLibrary shaders;
    private final Map<PipelineKey, Long> pipelines = new LinkedHashMap<>();
    private int creationCount;

    public PipelineCache(VulkanContext context, DescriptorManager descriptors, ShaderLibrary shaders) {
        this.context = context;
        this.descriptors = descriptors;
        this.shaders = shaders;
    }

    public long pipeline(PipelineKey key) {
        Long cached = pipelines.get(key);
        if (cached != null) return cached;
        long start = System.nanoTime();
        long pipeline = createPipeline(key);
        pipelines.put(key, pipeline);
        creationCount++;
        System.out.printf("[pipelines] created #%d %s (%.1f ms)%n",
                creationCount, key.describe(), (System.nanoTime() - start) / 1_000_000.0);
        return pipeline;
    }

    public int count() {
        return pipelines.size();
    }

    public int creationCount() {
        return creationCount;
    }

    /** Destroys all cached pipelines (call after waitIdle, e.g. shader reload). */
    public void clear() {
        for (long pipeline : pipelines.values()) {
            vkDestroyPipeline(context.device(), pipeline, null);
        }
        System.out.println("[pipelines] cleared " + pipelines.size() + " cached pipeline(s)");
        pipelines.clear();
    }

    private long createPipeline(PipelineKey key) {
        ShaderVariant variant = shaders.variant(key.variant());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vertModule = createShaderModule(variant.vertexSpirv(), stack);
            long fragModule = createShaderModule(variant.fragmentSpirv(), stack);
            try {
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
                stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

                VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                        .pVertexBindingDescriptions(key.vertexLayout().bindingDescription(stack))
                        .pVertexAttributeDescriptions(key.vertexLayout().attributeDescriptions(stack));
                VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1)
                        .scissorCount(1);
                VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
                VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .lineWidth(1.0f)
                        .cullMode(key.cullMode().vkCullMode)
                        .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
                VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                VkPipelineColorBlendAttachmentState.Buffer colorAttachment = blendAttachments(key, stack);
                VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .pAttachments(colorAttachment);
                VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(key.depthTest())
                        .depthWriteEnable(key.depthWrite())
                        .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewportState)
                        .pDynamicState(dynamicState)
                        .pRasterizationState(rasterizer)
                        .pMultisampleState(multisample)
                        .pDepthStencilState(depthStencil)
                        .pColorBlendState(colorBlend)
                        .layout(descriptors.pipelineLayout())
                        .renderPass(context.renderPassHandle())
                        .subpass(0);
                LongBuffer pPipeline = stack.mallocLong(1);
                VulkanContext.check(vkCreateGraphicsPipelines(context.device(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                        "create graphics pipeline " + key.describe());
                return pPipeline.get(0);
            } finally {
                vkDestroyShaderModule(context.device(), fragModule, null);
                vkDestroyShaderModule(context.device(), vertModule, null);
            }
        }
    }

    private static VkPipelineColorBlendAttachmentState.Buffer blendAttachments(PipelineKey key, MemoryStack stack) {
        VkPipelineColorBlendAttachmentState.Buffer attachments = VkPipelineColorBlendAttachmentState.calloc(2, stack);
        VkPipelineColorBlendAttachmentState scene = attachments.get(0);
        VkPipelineColorBlendAttachmentState mask = attachments.get(1);
        boolean outline = key.variant().features().contains(MaterialFeature.OUTLINE);
        scene.colorWriteMask(outline ? 0 : VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        mask.colorWriteMask(outline ? VK_COLOR_COMPONENT_R_BIT : 0).blendEnable(false);
        switch (key.blendMode()) {
            case OPAQUE, CUTOUT -> scene.blendEnable(false);
            case TRANSPARENT -> scene.blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            case ADDITIVE -> scene.blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
        }
        return attachments;
    }

    private long createShaderModule(ByteBuffer spirv, MemoryStack stack) {
        VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(spirv);
        LongBuffer pModule = stack.mallocLong(1);
        VulkanContext.check(vkCreateShaderModule(context.device(), createInfo, null, pModule), "create shader module");
        return pModule.get(0);
    }

    @Override
    public void close() {
        clear();
    }
}
