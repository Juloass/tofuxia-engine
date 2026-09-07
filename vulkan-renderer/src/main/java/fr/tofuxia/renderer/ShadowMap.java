package fr.tofuxia.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Single orthographic directional shadow target, caster pipelines and depth preview. */
final class ShadowMap implements AutoCloseable {
    private record Key(VertexLayout layout, boolean cutout, CullMode cull) {}
    private final VulkanContext context;
    private final DescriptorManager descriptors;
    private final ShaderSources shaders;
    private final VulkanContext.DepthTarget target;
    private final long renderPass, framebuffer, sampler;
    private final Map<Key,Long> pipelines=new HashMap<>();
    private long previewPipeline;
    private final ByteBuffer push=BufferUtils.createByteBuffer(DescriptorManager.PUSH_CONSTANT_BYTES);
    private final Matrix4f lightViewProj=new Matrix4f();

    ShadowMap(VulkanContext context, DescriptorManager descriptors,
              Path shaders, int resolution) {
        this(context, descriptors, ShaderSources.directory(shaders), resolution);
    }

    ShadowMap(VulkanContext context, DescriptorManager descriptors,
              ShaderSources shaders, int resolution) {
        this.context=context;this.descriptors=descriptors;this.shaders=shaders;
        target=context.createSampledDepthTarget(resolution,resolution);
        renderPass=createRenderPass();framebuffer=createFramebuffer();sampler=context.createSampler(SamplerSettings.UI_CLAMP);
    }
    long view(){return target.view();} long sampler(){return sampler;} Matrix4f matrix(){return new Matrix4f(lightViewProj);}
    long renderPass(){return renderPass;} long framebuffer(){return framebuffer;}
    int width(){return target.width();} int height(){return target.height();}

    void updateMatrix(Vector3f direction,DirectionalShadowSettings s){
        updateFixedMatrix(direction,s);
    }

    void updateMatrix(Vector3f direction,DirectionalShadowSettings s,Matrix4f cameraView,Matrix4f cameraProj){
        if(s.fitMode()==ShadowFitMode.CAMERA_VIEW) updateCameraFitMatrix(direction,s,cameraView,cameraProj);
        else updateFixedMatrix(direction,s);
    }

    private void updateFixedMatrix(Vector3f direction,DirectionalShadowSettings s){
        Vector3f center=s.center();
        Vector3f eye=new Vector3f(direction).normalize().mul(-s.farPlane()*.5f).add(center);
        Matrix4f view=new Matrix4f().lookAt(eye,center,new Vector3f(0,1,0));
        Matrix4f proj=new Matrix4f().ortho(-s.halfExtent(),s.halfExtent(),-s.halfExtent(),s.halfExtent(),s.nearPlane(),s.farPlane(),true);
        proj.m11(proj.m11()*-1f);
        Matrix4f unsnapped=new Matrix4f(proj).mul(view);
        Vector4f origin=unsnapped.transform(new Vector4f(0,0,0,1));
        float clipX=origin.x/origin.w,clipY=origin.y/origin.w;
        proj.m30(proj.m30()+snapClipOffset(clipX,s.resolution()));
        proj.m31(proj.m31()+snapClipOffset(clipY,s.resolution()));
        lightViewProj.set(proj).mul(view);
    }

    private void updateCameraFitMatrix(Vector3f direction,DirectionalShadowSettings s,Matrix4f cameraView,Matrix4f cameraProj){
        lightViewProj.set(cameraFitMatrix(direction,s,cameraView,cameraProj));
    }

    static Matrix4f cameraFitMatrix(Vector3f direction,DirectionalShadowSettings s,Matrix4f cameraView,Matrix4f cameraProj){
        Vector3f[] corners=cameraFrustumCorners(cameraView,cameraProj,s.nearPlane(),s.fitDistance());
        Vector3f center=new Vector3f();
        for(Vector3f c:corners)center.add(c);
        center.div(corners.length);
        Vector3f lightDir=new Vector3f(direction).normalize();
        Vector3f up=Math.abs(lightDir.dot(0,1,0))>.95f?new Vector3f(0,0,1):new Vector3f(0,1,0);
        Vector3f eye=new Vector3f(lightDir).mul(-s.farPlane()*.5f).add(center);
        Matrix4f view=new Matrix4f().lookAt(eye,center,up);
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        Vector4f p=new Vector4f();
        for(Vector3f c:corners){
            view.transform(new Vector4f(c,1),p);
            minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);
            minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);
        }
        float halfExtent=Math.max(s.minHalfExtent(),Math.max(maxX-minX,maxY-minY)*.5f+s.padding());
        float texelWorld=(halfExtent*2f)/s.resolution();
        float centerX=snapToTexel((minX+maxX)*.5f,texelWorld);
        float centerY=snapToTexel((minY+maxY)*.5f,texelWorld);
        Matrix4f proj=new Matrix4f().ortho(centerX-halfExtent,centerX+halfExtent,centerY-halfExtent,centerY+halfExtent,s.nearPlane(),s.farPlane(),true);
        proj.m11(proj.m11()*-1f);
        return proj.mul(view);
    }

    static float snapToTexel(float value,float texelWorld){
        return Math.round(value/texelWorld)*texelWorld;
    }

    static Vector3f[] cameraFrustumCorners(Matrix4f cameraView,Matrix4f cameraProj,float nearDistance,float farDistance){
        Matrix4f invProj=new Matrix4f(cameraProj).invert();
        Matrix4f invView=new Matrix4f(cameraView).invert();
        Vector3f[] corners=new Vector3f[8];
        int i=0;
        for(float distance:new float[]{nearDistance,farDistance}){
            for(float y:new float[]{-1f,1f})for(float x:new float[]{-1f,1f}){
                Vector4f v=invProj.transform(new Vector4f(x,y,1f,1f));
                v.div(v.w);
                Vector3f dir=new Vector3f(v.x,v.y,v.z);
                float scale=distance/Math.max(.0001f,-dir.z);
                Vector4f world=invView.transform(new Vector4f(dir.mul(scale),1f));
                corners[i++]=new Vector3f(world.x/world.w,world.y/world.w,world.z/world.w);
            }
        }
        return corners;
    }

    static float snapClipOffset(float clipCoordinate,int resolution){
        float texels=clipCoordinate*resolution*.5f;
        return (Math.round(texels)-texels)*2f/resolution;
    }

    void begin(VkCommandBuffer cmd,DirectionalShadowSettings s,long globalSet){
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkClearValue.Buffer clear=VkClearValue.calloc(1,stack);clear.depthStencil().depth(1f).stencil(0);
            VkRenderPassBeginInfo info=VkRenderPassBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass).framebuffer(framebuffer).pClearValues(clear);
            info.renderArea().offset().set(0,0);info.renderArea().extent().set(target.width(),target.height());
            vkCmdBeginRenderPass(cmd,info,VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS);
        }
    }
    void end(VkCommandBuffer cmd){vkCmdEndRenderPass(cmd);}

    void draw(VkCommandBuffer cmd,RenderItem item,Texture2D texture,JointPalette identity,int frame){
        if(!item.material.castShadows())return;
        draw(cmd,item.mesh.vertexBuffer().buffer(),item.mesh.indexBuffer().buffer(),
                item.mesh.vertexBufferOffset(),item.mesh.indexBufferOffset(),item.mesh.indexCount(),
                item.mesh.layout(),item.transform,item.material,texture,item.palette==null?identity:item.palette,frame);
    }

    SecondaryDraw prepare(RenderItem item,Texture2D texture,JointPalette identity,int frame){
        if(!item.material.castShadows()||item.mesh.indexCount()==0)return null;
        boolean cutout=item.material.blendMode()==BlendMode.CUTOUT;
        long pipeline=pipelines.computeIfAbsent(new Key(item.mesh.layout(),cutout,item.material.cullMode()),this::createPipeline);
        JointPalette palette=item.palette==null?identity:item.palette;
        writePush(item.transform,item.material);
        byte[] constants=new byte[DescriptorManager.PUSH_CONSTANT_BYTES];
        for(int i=0;i<constants.length;i++)constants[i]=push.get(i);
        return new SecondaryDraw(pipeline,texture.descriptorSet(),palette.descriptorSet(frame),
                item.mesh.vertexBuffer().buffer(),item.mesh.indexBuffer().buffer(),
                item.mesh.vertexBufferOffset(),item.mesh.indexBufferOffset(),item.mesh.indexCount(),constants);
    }
    void prewarm(VertexLayout layout,Material material){
        if(!material.castShadows())return;
        boolean cutout=material.blendMode()==BlendMode.CUTOUT;
        pipelines.computeIfAbsent(new Key(layout,cutout,material.cullMode()),this::createPipeline);
    }
    void draw(VkCommandBuffer cmd,long vb,long ib,long vertexOffset,long indexOffset,int count,VertexLayout layout,Matrix4f model,Material material,Texture2D texture,JointPalette palette,int frame){
        if(count==0||!material.castShadows())return;
        boolean cutout=material.blendMode()==BlendMode.CUTOUT;
        long pipeline=pipelines.computeIfAbsent(new Key(layout,cutout,material.cullMode()),this::createPipeline);
        try(MemoryStack stack=MemoryStack.stackPush()){
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,descriptors.pipelineLayout(),1,stack.longs(texture.descriptorSet()),null);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,descriptors.pipelineLayout(),2,stack.longs(palette.descriptorSet(frame)),null);
            writePush(model,material);vkCmdPushConstants(cmd,descriptors.pipelineLayout(),VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT,0,push);
            vkCmdBindVertexBuffers(cmd,0,stack.longs(vb),stack.longs(vertexOffset));vkCmdBindIndexBuffer(cmd,ib,indexOffset,VK_INDEX_TYPE_UINT32);vkCmdDrawIndexed(cmd,count,1,0,0,0);
        }
    }
    void drawPreview(VkCommandBuffer cmd,long globalSet){
        if(previewPipeline==0)previewPipeline=createPreviewPipeline();
        try(MemoryStack stack=MemoryStack.stackPush()){
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,previewPipeline);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,descriptors.pipelineLayout(),0,stack.longs(globalSet),null);
            vkCmdDraw(cmd,3,1,0,0);
        }
    }
    private void writePush(Matrix4f model,Material m){push.clear();model.get(0,push);push.putFloat(64,m.baseColorFactor().x).putFloat(68,m.baseColorFactor().y).putFloat(72,m.baseColorFactor().z).putFloat(76,m.baseColorFactor().w);push.putFloat(96,m.alphaCutoff());push.position(0).limit(DescriptorManager.PUSH_CONSTANT_BYTES);}

    private long createRenderPass(){try(MemoryStack st=MemoryStack.stackPush()){
        VkAttachmentDescription.Buffer a=VkAttachmentDescription.calloc(1,st);a.format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkAttachmentReference d=VkAttachmentReference.calloc(st).attachment(0).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer sub=VkSubpassDescription.calloc(1,st).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pDepthStencilAttachment(d);
        VkSubpassDependency.Buffer deps=VkSubpassDependency.calloc(2,st);
        deps.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT).srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT).dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);
        deps.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT).dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT).dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);
        VkRenderPassCreateInfo i=VkRenderPassCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO).pAttachments(a).pSubpasses(sub).pDependencies(deps);LongBuffer p=st.mallocLong(1);VulkanContext.check(vkCreateRenderPass(context.device(),i,null,p),"create shadow render pass");return p.get(0);
    }}
    private long createFramebuffer(){try(MemoryStack st=MemoryStack.stackPush()){VkFramebufferCreateInfo i=VkFramebufferCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO).renderPass(renderPass).pAttachments(st.longs(target.view())).width(target.width()).height(target.height()).layers(1);LongBuffer p=st.mallocLong(1);VulkanContext.check(vkCreateFramebuffer(context.device(),i,null,p),"create shadow framebuffer");return p.get(0);}}
    private long module(ByteBuffer code,MemoryStack st){VkShaderModuleCreateInfo i=VkShaderModuleCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(code);LongBuffer p=st.mallocLong(1);VulkanContext.check(vkCreateShaderModule(context.device(),i,null,p),"create shadow shader");return p.get(0);}
    private ByteBuffer compile(String file,int kind,String prefix){try{return ShaderLibrary.compile(file,ShaderLibrary.preprocess(shaders,file,prefix),kind);}catch(Exception e){throw new IllegalStateException("Cannot compile "+file,e);}}
    private long createPipeline(Key k){String defs="#define MAX_JOINTS "+ShaderLibrary.MAX_JOINTS+"\n"
            +(k.layout()==VertexLayout.SKINNED?"#define SHADOW_SKINNED\n":"")
            +(k.layout()==VertexLayout.VOXEL?"#define SHADOW_VOXEL\n":"");
        ByteBuffer vs=compile("shadow.vert.glsl",shaderc_glsl_vertex_shader,defs);ByteBuffer fs=k.cutout()?compile("shadow_cutout.frag.glsl",shaderc_glsl_fragment_shader,""):null;return graphicsPipeline(vs,fs,k.layout(),k.cull(),renderPass,true);}
    private long createPreviewPipeline(){return graphicsPipeline(compile("shadow_preview.vert.glsl",shaderc_glsl_vertex_shader,""),compile("shadow_preview.frag.glsl",shaderc_glsl_fragment_shader,""),null,CullMode.NONE,context.overlayRenderPassHandle(),false);}
    private long graphicsPipeline(ByteBuffer vs,ByteBuffer fs,VertexLayout layout,CullMode cull,long pass,boolean depth){try(MemoryStack st=MemoryStack.stackPush()){
        long vm=module(vs,st),fm=fs==null?0:module(fs,st);try{
            int n=fs==null?1:2;VkPipelineShaderStageCreateInfo.Buffer stages=VkPipelineShaderStageCreateInfo.calloc(n,st);stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vm).pName(st.UTF8("main"));if(fs!=null)stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fm).pName(st.UTF8("main"));
            VkPipelineVertexInputStateCreateInfo vi=VkPipelineVertexInputStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);if(layout!=null)vi.pVertexBindingDescriptions(layout.bindingDescription(st)).pVertexAttributeDescriptions(layout.attributeDescriptions(st));
            VkPipelineInputAssemblyStateCreateInfo ia=VkPipelineInputAssemblyStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO).topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo vp=VkPipelineViewportStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO).viewportCount(1).scissorCount(1);
            VkPipelineDynamicStateCreateInfo dy=VkPipelineDynamicStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO).pDynamicStates(depth?st.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR,VK_DYNAMIC_STATE_DEPTH_BIAS):st.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineRasterizationStateCreateInfo rs=VkPipelineRasterizationStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO).polygonMode(VK_POLYGON_MODE_FILL).lineWidth(1).cullMode(cull.vkCullMode).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).depthBiasEnable(depth);
            VkPipelineMultisampleStateCreateInfo ms=VkPipelineMultisampleStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO).rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo ds=VkPipelineDepthStencilStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO).depthTestEnable(depth).depthWriteEnable(depth).depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);
            VkPipelineColorBlendStateCreateInfo cb=VkPipelineColorBlendStateCreateInfo.calloc(st).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);if(!depth){VkPipelineColorBlendAttachmentState.Buffer ca=VkPipelineColorBlendAttachmentState.calloc(1,st).colorWriteMask(15);cb.pAttachments(ca);}
            VkGraphicsPipelineCreateInfo.Buffer pi=VkGraphicsPipelineCreateInfo.calloc(1,st);pi.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO).pStages(stages).pVertexInputState(vi).pInputAssemblyState(ia).pViewportState(vp).pDynamicState(dy).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds).pColorBlendState(cb).layout(descriptors.pipelineLayout()).renderPass(pass).subpass(0);LongBuffer p=st.mallocLong(1);VulkanContext.check(vkCreateGraphicsPipelines(context.device(),0,pi,null,p),"create shadow pipeline");return p.get(0);
        }finally{if(fm!=0)vkDestroyShaderModule(context.device(),fm,null);vkDestroyShaderModule(context.device(),vm,null);}}
    }
    @Override public void close(){for(long p:pipelines.values())vkDestroyPipeline(context.device(),p,null);if(previewPipeline!=0)vkDestroyPipeline(context.device(),previewPipeline,null);vkDestroyFramebuffer(context.device(),framebuffer,null);vkDestroyRenderPass(context.device(),renderPass,null);context.destroySampler(sampler);context.destroyDepthTarget(target);}
}
