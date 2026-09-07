package fr.tofuxia.renderer;

import fr.tofuxia.render.FontAtlas;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;

/**
 * The DEBUG_OVERLAY pass: renders stat/info text lines as screen-space quads
 * from the shared {@link FontAtlas}. Internal to the renderer (not a
 * material), so it keeps its own tiny pipeline; everything gameplay-visible
 * still goes through the material system.
 */
final class DebugTextRenderer implements AutoCloseable {
    private static final String VERT = """
            #version 450
            layout(push_constant) uniform Push { vec2 screenSize; } pushData;
            layout(location = 0) in vec2 inPosition;
            layout(location = 1) in vec2 inUv;
            layout(location = 2) in vec4 inColor;
            layout(location = 0) out vec2 uv;
            layout(location = 1) out vec4 color;
            void main() {
                vec2 ndc = inPosition / pushData.screenSize * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
                uv = inUv;
                color = inColor;
            }
            """;
    private static final String FRAG = """
            #version 450
            layout(binding = 0) uniform sampler2D fontAtlas;
            layout(location = 0) in vec2 uv;
            layout(location = 1) in vec4 color;
            layout(location = 0) out vec4 outColor;
            void main() {
                float alpha = uv.x < 0.0 ? 1.0 : texture(fontAtlas, uv).r;
                outColor = vec4(color.rgb, color.a * alpha);
            }
            """;
    private static final int FLOATS_PER_VERTEX = 8; // pos2 uv2 color4

    private final VulkanContext context;
    private final FontAtlas font;
    private final VulkanContext.GpuImage fontImage;
    private final long fontSampler;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final VulkanContext.GpuBuffer[] vertexBuffers = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final int[] vertexCounts = new int[VulkanContext.FRAMES_IN_FLIGHT];
    private static final int MAX_CHARS = 4096;

    DebugTextRenderer(VulkanContext context, FontAtlas font) {
        this.context = context;
        this.font = font;
        ByteBuffer pixels = BufferUtils.createByteBuffer(font.pixels().length);
        pixels.put(font.pixels()).flip();
        this.fontImage = context.createTextureImageR8(font.width(), font.height(), pixels);
        this.fontSampler = context.createSampler(SamplerSettings.UI_CLAMP);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.device();
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);
            LongBuffer pLayout = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout),
                    "create debug text descriptor set layout");
            descriptorSetLayout = pLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSize).maxSets(1);
            LongBuffer pPool = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorPool(device, poolInfo, null, pPool),
                    "create debug text descriptor pool");
            descriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            VulkanContext.check(vkAllocateDescriptorSets(device, allocInfo, pSet), "allocate debug text descriptor set");
            descriptorSet = pSet.get(0);
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(fontImage.view())
                    .sampler(fontSampler);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(device, write, null);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(2 * Float.BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            VulkanContext.check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pLayout),
                    "create debug text pipeline layout");
            pipelineLayout = pLayout.get(0);
        }
        this.pipeline = createPipeline();
        long bufferBytes = (long) MAX_CHARS * 6 * FLOATS_PER_VERTEX * Float.BYTES;
        for (int i = 0; i < vertexBuffers.length; i++) {
            vertexBuffers[i] = context.createBuffer(bufferBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        }
    }

    /** Builds and uploads text quads for this frame, then records the draw. */
    void draw(VkCommandBuffer cmd, int frameIndex, List<String> lines, RendererStats stats) {
        if (lines.isEmpty()) return;
        float[] vertices = buildVertices(lines);
        int vertexCount = vertices.length / FLOATS_PER_VERTEX;
        if (vertexCount == 0) return;
        context.uploadFloats(vertexBuffers[frameIndex], vertices);
        vertexCounts[frameIndex] = vertexCount;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            stats.countPipelineBind();
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            ByteBuffer screenSize = stack.malloc(2 * Float.BYTES);
            screenSize.putFloat(0, (float) context.width());
            screenSize.putFloat(Float.BYTES, (float) context.height());
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, screenSize);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffers[frameIndex].buffer()), stack.longs(0));
            vkCmdDraw(cmd, vertexCount, 1, 0, 0);
            stats.countDraw();
        }
    }

    private float[] buildVertices(List<String> lines) {
        return buildVertices(font, lines, MAX_CHARS);
    }

    static float[] buildVertices(FontAtlas font, List<String> lines, int maxChars) {
        int totalChars = 0;
        FontAtlas.PositionedGlyph[][] layouts = new FontAtlas.PositionedGlyph[lines.size()][];
        for (int i = 0; i < lines.size(); i++) {
            layouts[i] = font.layout(lines.get(i));
            totalChars += layouts[i].length;
        }
        totalChars = Math.max(0, Math.min(totalChars, maxChars - lines.size() * 2));
        // background quads (uv.x < 0 renders solid) + glyph quads
        float[] vertices = new float[(totalChars + lines.size()) * 6 * FLOATS_PER_VERTEX];
        int cursor = 0;
        float x0 = 10.0f;
        float y = 10.0f;
        float lineHeight = font.lineHeight() + 2.0f;
        int written = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            FontAtlas.PositionedGlyph[] layout = layouts[lineIndex];
            float width = font.textWidth(line);
            cursor = putQuad(vertices, cursor, x0 - 4, y - 1, x0 + width + 4, y + lineHeight - 1,
                    -1, -1, -1, -1, 0.06f, 0.07f, 0.09f, 0.72f);
            for (FontAtlas.GlyphQuad quad : font.layoutQuads(line, x0, y)) {
                if (written >= totalChars) break;
                cursor = putQuad(vertices, cursor, quad.x0(), quad.y0(), quad.x1(), quad.y1(),
                        quad.u0(), quad.v0(), quad.u1(), quad.v1(), 0.92f, 0.95f, 1.0f, 1.0f);
                written++;
            }
            y += lineHeight;
        }
        return cursor == vertices.length ? vertices : java.util.Arrays.copyOf(vertices, cursor);
    }

    private static int putQuad(float[] out, int cursor, float x0, float y0, float x1, float y1,
                               float u0, float v0, float u1, float v1,
                               float r, float g, float b, float a) {
        float[][] corners = {
                {x0, y0, u0, v0}, {x1, y0, u1, v0}, {x1, y1, u1, v1},
                {x0, y0, u0, v0}, {x1, y1, u1, v1}, {x0, y1, u0, v1},
        };
        for (float[] corner : corners) {
            out[cursor++] = corner[0];
            out[cursor++] = corner[1];
            out[cursor++] = corner[2];
            out[cursor++] = corner[3];
            out[cursor++] = r;
            out[cursor++] = g;
            out[cursor++] = b;
            out[cursor++] = a;
        }
        return cursor;
    }

    private long createPipeline() {
        ByteBuffer vertSpirv = ShaderLibrary.compile("debug_text.vert", VERT, shaderc_glsl_vertex_shader);
        ByteBuffer fragSpirv = ShaderLibrary.compile("debug_text.frag", FRAG, shaderc_glsl_fragment_shader);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.device();
            LongBuffer pModule = stack.mallocLong(1);
            VkShaderModuleCreateInfo vertInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(vertSpirv);
            VulkanContext.check(vkCreateShaderModule(device, vertInfo, null, pModule), "create debug text vert module");
            long vertModule = pModule.get(0);
            VkShaderModuleCreateInfo fragInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(fragSpirv);
            VulkanContext.check(vkCreateShaderModule(device, fragInfo, null, pModule), "create debug text frag module");
            long fragModule = pModule.get(0);

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(FLOATS_PER_VERTEX * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributes.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(2 * Float.BYTES);
            attributes.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(4 * Float.BYTES);
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(binding)
                    .pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1);
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1.0f)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer colorAttachment = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                    .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(colorAttachment);
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(false).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_ALWAYS);

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
                    .layout(pipelineLayout)
                    .renderPass(context.overlayRenderPassHandle())
                    .subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            VulkanContext.check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                    "create debug text pipeline");
            vkDestroyShaderModule(device, fragModule, null);
            vkDestroyShaderModule(device, vertModule, null);
            return pPipeline.get(0);
        }
    }

    @Override
    public void close() {
        VkDevice device = context.device();
        for (VulkanContext.GpuBuffer buffer : vertexBuffers) {
            context.destroyBuffer(buffer);
        }
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        context.destroySampler(fontSampler);
        context.destroyImage(fontImage);
    }
}
