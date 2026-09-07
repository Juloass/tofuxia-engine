package fr.tofuxia.renderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Central binding model of the renderer. Every material pipeline shares one
 * pipeline layout with three descriptor sets, so pipelines only differ by
 * shader variant and fixed-function state:
 *
 * <pre>
 * set 0: global uniforms, shadow map, clustered light buffers - bound once per frame
 * set 1: material base color texture                        - bound per material
 * set 2: joint matrix palette (SKINNED)                     - bound per skinned draw
 * push : model matrix + material factors (112 bytes)
 * </pre>
 *
 * Sets 1 and 2 are always part of the layout; untextured or unskinned draws
 * bind shared fallback sets (white texture, identity palette).
 */
public final class DescriptorManager implements AutoCloseable {
    public static final int PUSH_CONSTANT_BYTES = 112;

    private final VulkanContext context;
    private final long globalSetLayout;
    private final long textureSetLayout;
    private final long jointsSetLayout;
    private final long pipelineLayout;
    private final List<Long> pools = new ArrayList<>();

    public DescriptorManager(VulkanContext context) {
        this.context = context;
        this.globalSetLayout = createGlobalSetLayout();
        this.textureSetLayout = createSetLayout(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                VK_SHADER_STAGE_FRAGMENT_BIT);
        this.jointsSetLayout = createSetLayout(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                VK_SHADER_STAGE_VERTEX_BIT);
        this.pipelineLayout = createPipelineLayout();
        addPool();
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    long globalSetLayout() {
        return globalSetLayout;
    }

    public long allocateGlobalSet() {
        return allocate(globalSetLayout);
    }

    public long allocateTextureSet() {
        return allocate(textureSetLayout);
    }

    public long allocateJointsSet() {
        return allocate(jointsSetLayout);
    }

    public void writeUniformBuffer(long set, VulkanContext.GpuBuffer buffer, long range) {
        writeStorageOrUniformBuffer(set, 0, buffer, range, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    }

    public void writeStorageBuffer(long set, int binding, VulkanContext.GpuBuffer buffer, long range) {
        writeStorageOrUniformBuffer(set, binding, buffer, range, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
    }

    private void writeStorageOrUniformBuffer(long set, int binding, VulkanContext.GpuBuffer buffer, long range,
                                             int descriptorType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer.buffer()).offset(0).range(range);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(binding)
                    .descriptorType(descriptorType)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo);
            vkUpdateDescriptorSets(context.device(), write, null);
        }
    }

    public void writeCombinedImageSampler(long set, long imageView, long sampler) {
        writeCombinedImageSampler(set, 0, imageView, sampler);
    }

    public void writeCombinedImageSampler(long set, int binding, long imageView, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    .imageView(imageView)
                    .sampler(sampler);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set).dstBinding(binding)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(context.device(), write, null);
        }
    }

    private long allocate(long setLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pools.get(pools.size() - 1))
                    .pSetLayouts(stack.longs(setLayout));
            LongBuffer pSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(context.device(), allocInfo, pSet);
            if (result == org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY || result == VK_ERROR_FRAGMENTED_POOL) {
                addPool();
                allocInfo.descriptorPool(pools.get(pools.size() - 1));
                result = vkAllocateDescriptorSets(context.device(), allocInfo, pSet);
            }
            VulkanContext.check(result, "allocate descriptor set");
            return pSet.get(0);
        }
    }

    private void addPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(256);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(256);
            poolSizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1024);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .pPoolSizes(poolSizes)
                    .maxSets(512);
            LongBuffer pPool = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorPool(context.device(), poolInfo, null, pPool),
                    "create descriptor pool");
            pools.add(pPool.get(0));
        }
    }

    private long createSetLayout(int descriptorType, int stageFlags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                    .binding(0)
                    .descriptorType(descriptorType)
                    .descriptorCount(1)
                    .stageFlags(stageFlags);
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(binding);
            LongBuffer pLayout = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorSetLayout(context.device(), layoutInfo, null, pLayout),
                    "create descriptor set layout");
            return pLayout.get(0);
        }
    }

    private long createGlobalSetLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT
                            | VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            int storageStages = VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT;
            bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(storageStages);
            bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(storageStages);
            bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(storageStages);
            bindings.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(storageStages);
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(bindings);
            LongBuffer p = stack.mallocLong(1);
            VulkanContext.check(vkCreateDescriptorSetLayout(context.device(), info, null, p), "create global descriptor layout");
            return p.get(0);
        }
    }

    private long createPipelineLayout() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(PUSH_CONSTANT_BYTES);
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(globalSetLayout, textureSetLayout, jointsSetLayout))
                    .pPushConstantRanges(pushRange);
            LongBuffer pLayout = stack.mallocLong(1);
            VulkanContext.check(vkCreatePipelineLayout(context.device(), layoutInfo, null, pLayout),
                    "create shared pipeline layout");
            return pLayout.get(0);
        }
    }

    @Override
    public void close() {
        VkDevice device = context.device();
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        for (long pool : pools) vkDestroyDescriptorPool(device, pool, null);
        pools.clear();
        vkDestroyDescriptorSetLayout(device, globalSetLayout, null);
        vkDestroyDescriptorSetLayout(device, textureSetLayout, null);
        vkDestroyDescriptorSetLayout(device, jointsSetLayout, null);
    }
}
