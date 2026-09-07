package fr.tofuxia.renderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Composites a crisp exterior-only dilation of the depth-tested R8 selection mask. */
final class ScreenSpaceOutlineRenderer implements AutoCloseable {
    private static final String VERT = """
            #version 450
            layout(location=0) out vec2 uv;
            void main() {
                vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
                uv = p * 0.5;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String FRAG = """
            #version 450
            layout(binding=0) uniform sampler2D selectionMask;
            layout(binding=1) uniform sampler2D sceneDepth;
            layout(push_constant) uniform Push { int radius; int debugMode; float opacity; float pulse; } push;
            layout(location=0) in vec2 uv;
            layout(location=0) out vec4 outColor;
            float maskAt(ivec2 p) {
                ivec2 size = textureSize(selectionMask, 0);
                return texelFetch(selectionMask, clamp(p, ivec2(0), size - 1), 0).r;
            }
            float depthAt(ivec2 p) {
                ivec2 size = textureSize(sceneDepth, 0);
                return texelFetch(sceneDepth, clamp(p, ivec2(0), size - 1), 0).r;
            }
            void main() {
                ivec2 pixel = ivec2(gl_FragCoord.xy);
                float original = maskAt(pixel);
                float dilated = original;
                float selectedDepth = 1.0;
                int r = clamp(push.radius, 1, 2);
                for (int y=-2; y<=2; ++y) for (int x=-2; x<=2; ++x) {
                    if (abs(x) <= r && abs(y) <= r) {
                        ivec2 samplePixel = pixel + ivec2(x,y);
                        float selected = maskAt(samplePixel);
                        dilated = max(dilated, selected);
                        if (selected > 0.5) selectedDepth = min(selectedDepth, depthAt(samplePixel));
                    }
                }
                float exterior = dilated * (1.0 - original);
                bool occluded = exterior > 0.5 && depthAt(pixel) + 0.0005 < selectedDepth;
                if (occluded) exterior = 0.0;
                if (push.debugMode == 1) { outColor = vec4(original.xxx, 1.0); return; }
                if (push.debugMode == 2) { outColor = vec4(dilated.xxx, 1.0); return; }
                if (push.debugMode == 3) { outColor = vec4(exterior.xxx, 1.0); return; }
                if (push.debugMode == 4) { outColor = occluded ? vec4(1,0,0,1) : vec4(0,exterior,0,1); return; }
                if (exterior < 0.5) discard;
                vec3 cream = vec3(1.0, 0.91, 0.70);
                outColor = vec4(cream, clamp(push.opacity + push.pulse, 0.0, 0.85));
            }
            """;

    private final VulkanContext context;
    private final long sampler, setLayout, pool, set, layout, pipeline;
    private long boundView;

    ScreenSpaceOutlineRenderer(VulkanContext context) {
        this.context = context;
        sampler = context.createSampler(SamplerSettings.PIXEL_CLAMP);
        try (MemoryStack s = MemoryStack.stackPush()) {
            LongBuffer p = s.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(2,s);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            binding.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(s)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(binding);
            VulkanContext.check(vkCreateDescriptorSetLayout(context.device(),li,null,p),"create outline set layout");
            setLayout=p.get(0);
            VkDescriptorPoolSize.Buffer ps=VkDescriptorPoolSize.calloc(1,s)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
            VkDescriptorPoolCreateInfo pi=VkDescriptorPoolCreateInfo.calloc(s)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).pPoolSizes(ps).maxSets(1);
            VulkanContext.check(vkCreateDescriptorPool(context.device(),pi,null,p),"create outline descriptor pool");
            pool=p.get(0);
            VkDescriptorSetAllocateInfo ai=VkDescriptorSetAllocateInfo.calloc(s)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(pool)
                    .pSetLayouts(s.longs(setLayout));
            VulkanContext.check(vkAllocateDescriptorSets(context.device(),ai,p),"allocate outline descriptor set");
            set=p.get(0);
            VkPushConstantRange.Buffer push=VkPushConstantRange.calloc(1,s)
                    .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo pli=VkPipelineLayoutCreateInfo.calloc(s)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(s.longs(setLayout))
                    .pPushConstantRanges(push);
            VulkanContext.check(vkCreatePipelineLayout(context.device(),pli,null,p),"create outline pipeline layout");
            layout=p.get(0);
        }
        pipeline=createPipeline();
    }

    /** CPU reference for tests/debug tooling; mirrors the shader's square native-pixel dilation. */
    static boolean[][] exteriorOnly(boolean[][] mask, int requestedRadius) {
        int h=mask.length,w=h==0?0:mask[0].length,r=Math.max(1,Math.min(2,requestedRadius));
        boolean[][] out=new boolean[h][w];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            if(mask[y][x])continue;
            boolean neighbor=false;
            for(int oy=-r;oy<=r&&!neighbor;oy++)for(int ox=-r;ox<=r;ox++){
                int sx=x+ox,sy=y+oy;
                if(sx>=0&&sx<w&&sy>=0&&sy<h&&mask[sy][sx]){neighbor=true;break;}
            }
            out[y][x]=neighbor;
        }
        return out;
    }

    void draw(VkCommandBuffer cmd, int debugMode, float timeSeconds, RendererStats stats) {
        updateDescriptor();
        context.setFullViewport(cmd,context.width(),context.height());
        try(MemoryStack s=MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,s.longs(set),null);
            int radius=Math.max(1,Math.min(2,Integer.getInteger("tofuxia.outlinePixels",1)));
            ByteBuffer push=s.malloc(16).putInt(radius).putInt(debugMode).putFloat(0.78f)
                    .putFloat((float)Math.sin(timeSeconds*2.0)*0.015f).flip();
            vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_FRAGMENT_BIT,0,push);
            vkCmdDraw(cmd,3,1,0,0);
            stats.countPass(PassId.ENTITY_OUTLINE,1); stats.countDraw(); stats.countPipelineBind();
        }
    }

    private void updateDescriptor() {
        long view=context.selectionMaskView();
        if(view==boundView)return;
        try(MemoryStack s=MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer image=VkDescriptorImageInfo.calloc(2,s);
            image.get(0).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(view).sampler(sampler);
            image.get(1).imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                    .imageView(context.sceneDepthView()).sampler(sampler);
            VkWriteDescriptorSet.Buffer write=VkWriteDescriptorSet.calloc(2,s);
            write.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(image.address(),1));
            write.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(image.address()+VkDescriptorImageInfo.SIZEOF,1));
            vkUpdateDescriptorSets(context.device(),write,null);
        }
        boundView=view;
    }

    private long createPipeline() {
        ByteBuffer vs=ShaderLibrary.compile("screen_outline.vert",VERT,shaderc_glsl_vertex_shader);
        ByteBuffer fs=ShaderLibrary.compile("screen_outline.frag",FRAG,shaderc_glsl_fragment_shader);
        try(MemoryStack s=MemoryStack.stackPush()) {
            LongBuffer p=s.mallocLong(1); VkDevice d=context.device();
            VkShaderModuleCreateInfo mi=VkShaderModuleCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            mi.pCode(vs); VulkanContext.check(vkCreateShaderModule(d,mi,null,p),"create outline vertex shader"); long vm=p.get(0);
            mi.pCode(fs); VulkanContext.check(vkCreateShaderModule(d,mi,null,p),"create outline fragment shader"); long fm=p.get(0);
            VkPipelineShaderStageCreateInfo.Buffer stages=VkPipelineShaderStageCreateInfo.calloc(2,s);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(s.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(s.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vi=VkPipelineVertexInputStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            VkPipelineInputAssemblyStateCreateInfo ia=VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo vp=VkPipelineViewportStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineDynamicStateCreateInfo dyn=VkPipelineDynamicStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(s.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRasterizationStateCreateInfo rs=VkPipelineRasterizationStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineMultisampleStateCreateInfo ms=VkPipelineMultisampleStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer ba=VkPipelineColorBlendAttachmentState.calloc(1,s).colorWriteMask(15).blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo bs=VkPipelineColorBlendStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(ba);
            VkPipelineDepthStencilStateCreateInfo ds=VkPipelineDepthStencilStateCreateInfo.calloc(s).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(false).depthWriteEnable(false);
            VkGraphicsPipelineCreateInfo.Buffer gi=VkGraphicsPipelineCreateInfo.calloc(1,s);
            gi.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia).pViewportState(vp).pDynamicState(dyn).pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(bs).pDepthStencilState(ds).layout(layout).renderPass(context.overlayRenderPassHandle()).subpass(0);
            VulkanContext.check(vkCreateGraphicsPipelines(d,0,gi,null,p),"create screen outline pipeline");
            vkDestroyShaderModule(d,fm,null);vkDestroyShaderModule(d,vm,null);return p.get(0);
        }
    }

    @Override public void close(){VkDevice d=context.device();vkDestroyPipeline(d,pipeline,null);vkDestroyPipelineLayout(d,layout,null);vkDestroyDescriptorPool(d,pool,null);vkDestroyDescriptorSetLayout(d,setLayout,null);context.destroySampler(sampler);}
}
