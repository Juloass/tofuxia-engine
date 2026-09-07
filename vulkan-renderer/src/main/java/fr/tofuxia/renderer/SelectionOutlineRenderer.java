package fr.tofuxia.renderer;

import fr.tofuxia.renderapi.RenderViewport;
import fr.tofuxia.renderapi.SelectionOutlineData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;

/** Dedicated depth-tested, non-depth-writing LINE_LIST selection overlay. */
final class SelectionOutlineRenderer implements AutoCloseable {
    private static final String VERT = """
            #version 450
            layout(location=0) in vec3 inPosition;
            layout(set=0,binding=0) uniform GlobalUbo {
                mat4 view; mat4 proj; mat4 viewProj; mat4 lightViewProj;
                vec4 remainder[13];
            } globals;
            layout(push_constant) uniform OutlinePush { vec4 color; int screenSpace; } outline;
            void main() {
                gl_Position = outline.screenSpace != 0
                    ? vec4(inPosition, 1.0)
                    : globals.viewProj * vec4(inPosition, 1.0);
            }
            """;
    private static final String FRAG = """
            #version 450
            layout(location=0) out vec4 outColor;
            layout(push_constant) uniform OutlinePush { vec4 color; int screenSpace; } outline;
            void main() { outColor = outline.color; }
            """;

    private final VulkanContext context;
    private final long pipelineLayout;
    private final long pipeline;
    private VulkanContext.GpuBuffer vertexBuffer;
    private VulkanContext.GpuBuffer indexBuffer;
    private long vertexCapacity;
    private long indexCapacity;
    private SelectionOutlineData data = SelectionOutlineData.EMPTY;
    private int indexCount;
    private boolean submitted;

    SelectionOutlineRenderer(VulkanContext context,long pipelineLayout){
        this.context=context;
        this.pipelineLayout=pipelineLayout;
        pipeline=createPipeline();
    }

    void upload(SelectionOutlineData next){
        next=next==null?SelectionOutlineData.EMPTY:next;
        if(next.revision()==data.revision()&&next.debugStage()==data.debugStage())return;
        data=next;
        submitted=false;
        if(next.empty()){indexCount=0;return;}
        float[] positions=next.positions();
        int[] indices=next.indices();
        ensureCapacity((long)positions.length*Float.BYTES,(long)indices.length*Integer.BYTES);
        context.uploadFloats(vertexBuffer,positions);
        context.uploadInts(indexBuffer,indices);
        indexCount=indices.length;
    }

    void draw(VkCommandBuffer cmd,long globalSet,RenderViewport viewport,RendererStats stats){
        submitted=false;
        if(indexCount==0)return;
        context.setViewportAndScissor(cmd,viewport);
        try(MemoryStack stack=MemoryStack.stackPush()){
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipelineLayout,0,
                    stack.longs(globalSet),null);
            ByteBuffer push=stack.malloc(32);
            push.putFloat(data.red()).putFloat(data.green()).putFloat(data.blue()).putFloat(data.alpha());
            push.putInt(data.debugStage()==SelectionOutlineData.DebugStage.TEST_LINE?1:0);
            while(push.position()<32)push.put((byte)0);
            push.flip();
            vkCmdPushConstants(cmd,pipelineLayout,VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,push);
            vkCmdBindVertexBuffers(cmd,0,stack.longs(vertexBuffer.buffer()),stack.longs(0));
            vkCmdBindIndexBuffer(cmd,indexBuffer.buffer(),0,VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(cmd,indexCount,1,0,0,0);
            submitted=true;
            stats.countPass(PassId.SELECTION_OUTLINE,1);
            stats.countDraw();
            stats.countPipelineBind();
        }
    }

    String diagnostics(){
        return "outlineSubmitted="+submitted+" stage="+data.debugStage()
                +" vertices="+data.vertexCount()+" indices="+indexCount
                +" topology=LINE_LIST depth=test/write-off cull=none pass=SELECTION_OUTLINE->UI";
    }

    private void ensureCapacity(long vertices,long indices){
        if(vertexBuffer==null||vertexCapacity<vertices){
            if(vertexBuffer!=null){context.waitIdle();context.destroyBuffer(vertexBuffer);}
            vertexCapacity=nextCapacity(vertices);
            vertexBuffer=context.createBuffer(vertexCapacity,VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        }
        if(indexBuffer==null||indexCapacity<indices){
            if(indexBuffer!=null){context.waitIdle();context.destroyBuffer(indexBuffer);}
            indexCapacity=nextCapacity(indices);
            indexBuffer=context.createBuffer(indexCapacity,VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        }
    }

    private static long nextCapacity(long required){
        long value=64*1024;
        while(value<required)value<<=1;
        return value;
    }

    private long createPipeline(){
        ByteBuffer vertSpirv=ShaderLibrary.compile("selection_outline.vert",VERT,shaderc_glsl_vertex_shader);
        ByteBuffer fragSpirv=ShaderLibrary.compile("selection_outline.frag",FRAG,shaderc_glsl_fragment_shader);
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkDevice device=context.device();
            LongBuffer handle=stack.mallocLong(1);
            VkShaderModuleCreateInfo moduleInfo=VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(vertSpirv);
            VulkanContext.check(vkCreateShaderModule(device,moduleInfo,null,handle),"create selection vertex module");
            long vert=handle.get(0);
            moduleInfo.pCode(fragSpirv);
            VulkanContext.check(vkCreateShaderModule(device,moduleInfo,null,handle),"create selection fragment module");
            long frag=handle.get(0);
            VkPipelineShaderStageCreateInfo.Buffer stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
            VkVertexInputBindingDescription.Buffer binding=VkVertexInputBindingDescription.calloc(1,stack)
                    .binding(0).stride(3*Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes=VkVertexInputAttributeDescription.calloc(1,stack);
            attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            VkPipelineVertexInputStateCreateInfo vertexInput=VkPipelineVertexInputStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attributes);
            VkPipelineInputAssemblyStateCreateInfo assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_LINE_LIST);
            VkPipelineViewportStateCreateInfo viewport=VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineDynamicStateCreateInfo dynamic=VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRasterizationStateCreateInfo raster=VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1f).cullMode(VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineMultisampleStateCreateInfo multisample=VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment=VkPipelineColorBlendAttachmentState.calloc(1,stack)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT|VK_COLOR_COMPONENT_G_BIT|VK_COLOR_COMPONENT_B_BIT|VK_COLOR_COMPONENT_A_BIT)
                    .blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .alphaBlendOp(VK_BLEND_OP_ADD);
            VkPipelineColorBlendStateCreateInfo blend=VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO).pAttachments(blendAttachment);
            VkPipelineDepthStencilStateCreateInfo depth=VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(true).depthWriteEnable(false).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo=VkGraphicsPipelineCreateInfo.calloc(1,stack);
            pipelineInfo.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pStages(stages)
                    .pVertexInputState(vertexInput).pInputAssemblyState(assembly).pViewportState(viewport)
                    .pDynamicState(dynamic).pRasterizationState(raster).pMultisampleState(multisample)
                    .pDepthStencilState(depth).pColorBlendState(blend).layout(pipelineLayout)
                    .renderPass(context.overlayRenderPassHandle()).subpass(0);
            VulkanContext.check(vkCreateGraphicsPipelines(device,VK_NULL_HANDLE,pipelineInfo,null,handle),
                    "create selection outline pipeline");
            vkDestroyShaderModule(device,frag,null);
            vkDestroyShaderModule(device,vert,null);
            return handle.get(0);
        }
    }

    @Override public void close(){
        if(vertexBuffer!=null)context.destroyBuffer(vertexBuffer);
        if(indexBuffer!=null)context.destroyBuffer(indexBuffer);
        vkDestroyPipeline(context.device(),pipeline,null);
    }
}
