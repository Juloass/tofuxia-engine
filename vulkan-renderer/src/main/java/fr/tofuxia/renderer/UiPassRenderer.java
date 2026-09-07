package fr.tofuxia.renderer;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiRenderData;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;

/** Indexed pixel-space UI pass for renderer-neutral UiRenderData batches. */
final class UiPassRenderer implements AutoCloseable {
    static final String VERT = """
            #version 450
            layout(push_constant) uniform Push { vec2 screenSize; vec2 logicalScale; int textureKind; } pushData;
            layout(location = 0) in vec2 inPosition;
            layout(location = 1) in vec2 inUv;
            layout(location = 2) in vec4 inColor;
            layout(location = 3) in vec2 inLocal;
            layout(location = 4) in vec2 inSize;
            layout(location = 5) in vec4 inSecondary;
            layout(location = 6) in vec4 inBorder;
            layout(location = 7) in vec4 inParams0;
            layout(location = 8) in vec4 inParams1;
            layout(location = 9) in vec4 inParams2;
            layout(location = 10) in vec4 inShadowColor;
            layout(location = 11) in vec4 inShadowParams;
            layout(location = 12) in vec4 inExtension;
            layout(location = 13) in vec4 inExtra;
            layout(location = 0) out vec2 uv;
            layout(location = 1) out vec4 color;
            layout(location = 2) out vec2 localPosition;
            layout(location = 3) flat out vec2 logicalSize;
            layout(location = 4) flat out vec4 secondary;
            layout(location = 5) flat out vec4 borderColor;
            layout(location = 6) flat out vec4 params0;
            layout(location = 7) flat out vec4 params1;
            layout(location = 8) flat out vec4 params2;
            layout(location = 9) flat out vec4 shadowColor;
            layout(location = 10) flat out vec4 shadowParams;
            layout(location = 11) flat out vec4 extensionParams;
            layout(location = 12) flat out vec4 extra;
            void main() {
                vec2 ndc = inPosition / pushData.screenSize * 2.0 - 1.0;
                gl_Position = vec4(ndc, 0.0, 1.0);
                uv=inUv; color=inColor; localPosition=inLocal; logicalSize=inSize;
                secondary=inSecondary; borderColor=inBorder; params0=inParams0; params1=inParams1;
                params2=inParams2; shadowColor=inShadowColor; shadowParams=inShadowParams;
                extensionParams=inExtension; extra=inExtra;
            }
            """;
    static final String FRAG = """
            #version 450
            layout(push_constant) uniform Push { vec2 screenSize; vec2 logicalScale; int textureKind; } pushData;
            layout(binding = 0) uniform sampler2D uiTexture;
            layout(binding = 1) uniform sampler2D surfaceNoiseTexture;
            layout(location = 0) in vec2 uv;
            layout(location = 1) in vec4 color;
            layout(location = 2) in vec2 localPosition;
            layout(location = 3) flat in vec2 logicalSize;
            layout(location = 4) flat in vec4 secondary;
            layout(location = 5) flat in vec4 borderColor;
            layout(location = 6) flat in vec4 params0;
            layout(location = 7) flat in vec4 params1;
            layout(location = 8) flat in vec4 params2;
            layout(location = 9) flat in vec4 shadowColor;
            layout(location = 10) flat in vec4 shadowParams;
            layout(location = 11) flat in vec4 extensionParams;
            layout(location = 12) flat in vec4 extra;
            layout(location = 0) out vec4 outColor;

            float boxDistance(vec2 point, vec2 center, vec2 size, float radius) {
                float validRadius=clamp(radius,0.0,max(0.0,min(size.x,size.y)*0.5));
                vec2 q=abs(point-center)-(size*0.5-vec2(validRadius));
                return length(max(q,vec2(0.0)))+min(max(q.x,q.y),0.0)-validRadius;
            }
            float shapeDistance(vec2 point) {
                int shape=int(params0.x+0.5);
                float mainRadius=shape==0?0.0:params0.y;
                float distance=boxDistance(point,logicalSize*0.5,logicalSize,mainRadius);
                if(shape==2){
                    float width=max(0.001,extensionParams.x);
                    float height=max(0.001,extensionParams.y);
                    vec2 center=vec2(logicalSize.x*0.5+extensionParams.z,-height*0.5+extensionParams.w);
                    distance=min(distance,boxDistance(point,center,vec2(width,height),extra.x));
                }
                return distance;
            }
            float coverage(float distance,float antialiasWidth){return 1.0-smoothstep(-antialiasWidth,antialiasWidth,distance);}
            float proceduralNoise(vec2 p){
                vec2 cell=floor(p); return fract(sin(dot(cell,vec2(127.1,311.7)))*43758.5453123);
            }
            vec4 over(vec4 front,vec4 back){
                float alpha=front.a+back.a*(1.0-front.a);
                if(alpha<=0.00001)return vec4(0.0);
                return vec4((front.rgb*front.a+back.rgb*back.a*(1.0-front.a))/alpha,alpha);
            }
            vec4 analyticSurface(){
                float distance=shapeDistance(localPosition);
                float scaleFloor=0.35/max(0.01,min(pushData.logicalScale.x,pushData.logicalScale.y));
                float aa=max(fwidth(distance),scaleFloor);
                float outerCoverage=coverage(distance,aa);
                float innerCoverage=coverage(distance+max(0.0,params0.z),aa);
                float borderCoverage=max(outerCoverage-innerCoverage,0.0);
                vec2 normalized=localPosition/max(logicalSize,vec2(0.001))-0.5;
                float ramp=clamp(0.5+dot(normalized,normalize(params1.xy)),0.0,1.0);
                vec3 fill=mix(color.rgb,secondary.rgb,ramp*clamp(params0.w,0.0,1.0));
                int noiseMode=int(params1.z+0.5);
                if(noiseMode!=0&&params1.w>0.0){
                    vec2 noiseCoord=int(params2.x+0.5)==0?localPosition:gl_FragCoord.xy/pushData.logicalScale;
                    float grain=noiseMode==1?texture(surfaceNoiseTexture,noiseCoord/64.0).r:proceduralNoise(noiseCoord);
                    fill+=vec3((grain-0.5)*params1.w);
                }
                float innerEdge=exp(-max(-distance,0.0)/3.5);
                fill*=1.0-innerEdge*params2.y;
                vec4 fillLayer=vec4(clamp(fill,0.0,1.0),color.a*innerCoverage);
                vec4 borderLayer=vec4(borderColor.rgb,borderColor.a*borderCoverage);
                vec4 surface=over(borderLayer,fillLayer);
                float shadowDistance=shapeDistance(localPosition-shadowParams.xy)-max(0.0,shadowParams.z);
                float shadowAa=max(aa,max(0.01,shadowParams.w));
                vec4 shadow=vec4(shadowColor.rgb,shadowColor.a*coverage(shadowDistance,shadowAa));
                int debugMode=int(params2.z+0.5);
                if(debugMode==1)return vec4(distance<0.0?vec3(0.12,0.45,1.0):vec3(1.0,0.20,0.08),clamp(0.3+abs(distance)*0.06,0.3,1.0));
                if(debugMode==2)return vec4(vec3(clamp(aa*0.5,0.0,1.0)),outerCoverage);
                if(debugMode==3)return vec4(vec3(borderCoverage),max(borderCoverage,.15));
                return over(surface,shadow);
            }
            void main() {
                if(extra.w>0.5){
                    outColor=analyticSurface();
                } else if (uv.x < 0.0) {
                    outColor = color;
                } else if (pushData.textureKind == 0) {
                    float alpha = texture(uiTexture, uv).r;
                    outColor = vec4(color.rgb, color.a * alpha);
                } else {
                    outColor = texture(uiTexture, uv) * color;
                }
            }
            """;

    private final VulkanContext context;
    private final VulkanContext.GpuImage fontImage;
    private final VulkanContext.GpuImage noiseImage;
    private final long fontSampler;
    private final long noiseSampler;
    private final long pixelSampler;
    private final long linearSampler;
    private final TextureManager textures;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private VulkanContext.GpuBuffer vertexBuffer;
    private VulkanContext.GpuBuffer indexBuffer;
    private long vertexBytes;
    private long indexBytes;
    private int indexCount;
    private List<UiRenderData.Batch> batches = List.of();
    private float logicalScaleX=1, logicalScaleY=1;
    private int surfaceCount, compositeSurfaceCount, clippedBatchCount;
    private final Map<String, Long> textureSets = new HashMap<>();
    private final Map<String, Long> fontSets = new HashMap<>();
    private final List<VulkanContext.GpuImage> alternateFontImages = new ArrayList<>();

    UiPassRenderer(VulkanContext context, FontAtlas font, TextureManager textures) {
        this(context, font, List.of(), textures);
    }

    UiPassRenderer(VulkanContext context, FontAtlas font, List<FontAtlas> alternateFonts,
                   TextureManager textures) {
        this.context = context;
        this.textures = textures;
        ByteBuffer pixels = BufferUtils.createByteBuffer(font.pixels().length);
        pixels.put(font.pixels()).flip();
        this.fontImage = context.createTextureImageR8(font.width(), font.height(), pixels);
        ByteBuffer noisePixels=BufferUtils.createByteBuffer(64*64);
        int state=0x6d2b79f5;
        for(int i=0;i<64*64;i++){state^=state<<13;state^=state>>>17;state^=state<<5;noisePixels.put((byte)state);}
        noisePixels.flip();
        this.noiseImage=context.createTextureImageR8(64,64,noisePixels);
        this.fontSampler = context.createSampler(SamplerSettings.UI_CLAMP);
        this.noiseSampler=context.createSampler(SamplerSettings.LINEAR_REPEAT);
        this.pixelSampler = context.createSampler(SamplerSettings.PIXEL_CLAMP);
        this.linearSampler = context.createSampler(SamplerSettings.UI_CLAMP);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.device();
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(2, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            binding.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);
            LongBuffer pLayout = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout),
                    "create ui descriptor set layout");
            descriptorSetLayout = pLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1024);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSize).maxSets(512);
            LongBuffer pPool = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorPool(device, poolInfo, null, pPool),
                    "create ui descriptor pool");
            descriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            VulkanContext.check(vkAllocateDescriptorSets(device, allocInfo, pSet), "allocate ui descriptor set");
            descriptorSet = pSet.get(0);
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(5 * Float.BYTES);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pushRange);
            VulkanContext.check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pLayout),
                    "create ui pipeline layout");
            pipelineLayout = pLayout.get(0);
        }
        updateDescriptorSet(descriptorSet,fontImage.view(),fontSampler);
        for (FontAtlas alternateFont : alternateFonts) {
            if (!alternateFont.atlasId().equals(font.atlasId())) registerFont(alternateFont);
        }
        pipeline = createPipeline();
    }

    void upload(UiRenderData data) {
        if (data == null || data.vertices().length == 0 || data.indices().length == 0) {
            indexCount = 0;
            batches = List.of();
            surfaceCount=0; compositeSurfaceCount=0; clippedBatchCount=0;
            return;
        }
        long neededVertexBytes = (long) data.vertices().length * Float.BYTES;
        long neededIndexBytes = (long) data.indices().length * Integer.BYTES;
        ensureCapacity(neededVertexBytes, neededIndexBytes);
        context.uploadFloats(vertexBuffer, data.vertices());
        context.uploadInts(indexBuffer, data.indices());
        indexCount = data.indices().length;
        batches = data.batches();
        logicalScaleX=data.logicalScaleX(); logicalScaleY=data.logicalScaleY();
        surfaceCount=data.surfaceCount(); compositeSurfaceCount=data.compositeSurfaceCount();
        clippedBatchCount=data.clippedBatchCount();
    }

    int indexCount() {
        return indexCount;
    }

    void countStats(RendererStats stats){
        if(indexCount==0){stats.countUi(0,0,0,0,0,0);return;}
        int draws=batches.isEmpty()?1:batches.size(),switches=0;
        String previous=null;
        List<UiRenderData.Batch> values=batches.isEmpty()?List.of(new UiRenderData.Batch(0,indexCount,UiRenderData.TextureKind.FONT,null,true,UiRenderData.Blend.TRANSPARENT,0,0,0,0)):batches;
        for(UiRenderData.Batch batch:values){String key=batch.textureKind()+"|"+batch.texture()+"|"+batch.pixelArt();if(!key.equals(previous)){switches++;previous=key;}}
        stats.countUi(draws,surfaceCount,compositeSurfaceCount,clippedBatchCount,switches,1);
    }

    void draw(VkCommandBuffer cmd, RendererStats stats) {
        if (indexCount == 0) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            stats.countPipelineBind();
            ByteBuffer screenSize = stack.malloc(5 * Float.BYTES);
            screenSize.putFloat(0, (float) context.width());
            screenSize.putFloat(Float.BYTES, (float) context.height());
            screenSize.putFloat(2*Float.BYTES,logicalScaleX);
            screenSize.putFloat(3*Float.BYTES,logicalScaleY);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffer.buffer()), stack.longs(0));
            vkCmdBindIndexBuffer(cmd, indexBuffer.buffer(), 0, VK_INDEX_TYPE_UINT32);
            List<UiRenderData.Batch> draws = batches.isEmpty()
                    ? List.of(new UiRenderData.Batch(0, indexCount, UiRenderData.TextureKind.FONT, null, true,
                    UiRenderData.Blend.TRANSPARENT, 0, 0, context.width(), context.height()))
                    : batches;
            for (UiRenderData.Batch batch : draws) {
                long set = descriptorFor(batch, stack);
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                        stack.longs(set), null);
                screenSize.putInt(4 * Float.BYTES, batch.textureKind() == UiRenderData.TextureKind.FONT ? 0 : 1);
                vkCmdPushConstants(cmd, pipelineLayout,
                        VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, screenSize);
                int x = Math.max(0, (int) batch.clipX());
                int y = Math.max(0, (int) batch.clipY());
                int w = Float.isFinite(batch.clipWidth()) ? Math.max(0, Math.min(context.width() - x, (int) Math.ceil(batch.clipWidth()))) : context.width() - x;
                int h = Float.isFinite(batch.clipHeight()) ? Math.max(0, Math.min(context.height() - y, (int) Math.ceil(batch.clipHeight()))) : context.height() - y;
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(x, y);
                scissor.extent().set(w, h);
                vkCmdSetScissor(cmd, 0, scissor);
                vkCmdDrawIndexed(cmd, batch.indexCount(), 1, batch.firstIndex(), 0, 0);
                stats.countDraw();
            }
        }
    }

    private long descriptorFor(UiRenderData.Batch batch, MemoryStack stack) {
        if (batch.textureKind() == UiRenderData.TextureKind.FONT) {
            if (batch.texture() == null) return descriptorSet;
            Long fontSet = fontSets.get(batch.texture());
            if (fontSet == null) throw new IllegalStateException("UI requested unregistered font atlas " + batch.texture());
            return fontSet;
        }
        if (batch.texture() == null) return descriptorSet;
        String key = batch.texture() + "#" + batch.pixelArt();
        Long cached = textureSets.get(key);
        if (cached != null) return cached;
        Texture2D texture = textures.load(batch.texture(),
                batch.pixelArt() ? SamplerSettings.PIXEL_ART : SamplerSettings.UI_CLAMP, false);
        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(descriptorPool)
                .pSetLayouts(stack.longs(descriptorSetLayout));
        LongBuffer pSet = stack.mallocLong(1);
        VulkanContext.check(vkAllocateDescriptorSets(context.device(), allocInfo, pSet), "allocate UI texture descriptor");
        long set = pSet.get(0);
        updateDescriptorSet(set,texture.image().view(),batch.pixelArt()?pixelSampler:linearSampler);
        textureSets.put(key, set);
        return set;
    }

    private void registerFont(FontAtlas font) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(font.pixels().length);
        pixels.put(font.pixels()).flip();
        VulkanContext.GpuImage image = context.createTextureImageR8(font.width(), font.height(), pixels);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(stack.longs(descriptorSetLayout));
            LongBuffer pSet = stack.mallocLong(1);
            VulkanContext.check(vkAllocateDescriptorSets(context.device(), allocInfo, pSet),
                    "allocate alternate UI font descriptor");
            long set = pSet.get(0);
            updateDescriptorSet(set,image.view(),fontSampler);
            fontSets.put(font.atlasId(), set);
            alternateFontImages.add(image);
        }
    }

    private void updateDescriptorSet(long set,long imageView,long sampler){
        try(MemoryStack stack=MemoryStack.stackPush()){
            VkDescriptorImageInfo.Buffer image=VkDescriptorImageInfo.calloc(1,stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(imageView).sampler(sampler);
            VkDescriptorImageInfo.Buffer noise=VkDescriptorImageInfo.calloc(1,stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).imageView(noiseImage.view()).sampler(noiseSampler);
            VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(2,stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(image);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(noise);
            vkUpdateDescriptorSets(context.device(),writes,null);
        }
    }

    private void ensureCapacity(long neededVertexBytes, long neededIndexBytes) {
        if (vertexBuffer == null || vertexBytes < neededVertexBytes) {
            context.waitIdle();
            if (vertexBuffer != null) context.destroyBuffer(vertexBuffer);
            vertexBytes = nextCapacity(neededVertexBytes);
            vertexBuffer = context.createBuffer(vertexBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        }
        if (indexBuffer == null || indexBytes < neededIndexBytes) {
            context.waitIdle();
            if (indexBuffer != null) context.destroyBuffer(indexBuffer);
            indexBytes = nextCapacity(neededIndexBytes);
            indexBuffer = context.createBuffer(indexBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        }
    }

    private long nextCapacity(long required) {
        long capacity = 4096;
        while (capacity < required) capacity <<= 1;
        return capacity;
    }

    private long createPipeline() {
        ByteBuffer vertSpirv = ShaderLibrary.compile("ui_pass.vert", VERT, shaderc_glsl_vertex_shader);
        ByteBuffer fragSpirv = ShaderLibrary.compile("ui_pass.frag", FRAG, shaderc_glsl_fragment_shader);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = context.device();
            LongBuffer pModule = stack.mallocLong(1);
            VkShaderModuleCreateInfo vertInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(vertSpirv);
            VulkanContext.check(vkCreateShaderModule(device, vertInfo, null, pModule), "create ui vert module");
            long vertModule = pModule.get(0);
            VkShaderModuleCreateInfo fragInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO).pCode(fragSpirv);
            VulkanContext.check(vkCreateShaderModule(device, fragInfo, null, pModule), "create ui frag module");
            long fragModule = pModule.get(0);

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));
            VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack)
                    .binding(0).stride(UiRenderData.FLOATS_PER_VERTEX * Float.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(14, stack);
            attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
            attributes.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(2 * Float.BYTES);
            attributes.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(4 * Float.BYTES);
            attributes.get(3).binding(0).location(3).format(VK_FORMAT_R32G32_SFLOAT).offset(8*Float.BYTES);
            attributes.get(4).binding(0).location(4).format(VK_FORMAT_R32G32_SFLOAT).offset(10*Float.BYTES);
            attributes.get(5).binding(0).location(5).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(12*Float.BYTES);
            attributes.get(6).binding(0).location(6).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16*Float.BYTES);
            attributes.get(7).binding(0).location(7).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(20*Float.BYTES);
            attributes.get(8).binding(0).location(8).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(24*Float.BYTES);
            attributes.get(9).binding(0).location(9).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(28*Float.BYTES);
            attributes.get(10).binding(0).location(10).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(32*Float.BYTES);
            attributes.get(11).binding(0).location(11).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(36*Float.BYTES);
            attributes.get(12).binding(0).location(12).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(40*Float.BYTES);
            attributes.get(13).binding(0).location(13).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(44*Float.BYTES);
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
                    "create ui pipeline");
            vkDestroyShaderModule(device, fragModule, null);
            vkDestroyShaderModule(device, vertModule, null);
            return pPipeline.get(0);
        }
    }

    @Override
    public void close() {
        VkDevice device = context.device();
        if (vertexBuffer != null) context.destroyBuffer(vertexBuffer);
        if (indexBuffer != null) context.destroyBuffer(indexBuffer);
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        context.destroySampler(fontSampler);
        context.destroySampler(noiseSampler);
        context.destroySampler(pixelSampler);
        context.destroySampler(linearSampler);
        alternateFontImages.forEach(context::destroyImage);
        context.destroyImage(fontImage);
        context.destroyImage(noiseImage);
    }

    static void verifyShadersCompile(){
        ShaderLibrary.compile("ui_pass_surface.vert",VERT,shaderc_glsl_vertex_shader);
        ShaderLibrary.compile("ui_pass_surface.frag",FRAG,shaderc_glsl_fragment_shader);
    }
}
