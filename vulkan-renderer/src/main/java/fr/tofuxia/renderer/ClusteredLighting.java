package fr.tofuxia.renderer;

import fr.tofuxia.renderapi.RenderViewport;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.List;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader;
import static org.lwjgl.vulkan.VK10.*;

final class ClusteredLighting implements AutoCloseable {
    static final int LIGHT_TILE_SIZE = 64;
    static final int LIGHT_CLUSTER_Z_SLICES = 4;
    static final int MAX_GPU_POINT_LIGHTS = 2048;
    static final int MAX_LIGHTS_PER_CLUSTER = 32;
    static final int DIRECT_LIGHT_THRESHOLD = 8;
    static final float CLUSTER_NEAR = 0.05f;
    static final float CLUSTER_FAR = 1000.0f;
    private static final int LIGHT_BYTES = 32;
    private static final int STATS_BYTES = 16;

    private final VulkanContext context;
    private final DescriptorManager descriptors;
    private final ShaderSources shaderSources;
    private final VulkanContext.GpuBuffer[] lightBuffers = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final VulkanContext.GpuBuffer[] countBuffers = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final VulkanContext.GpuBuffer[] indexBuffers = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final VulkanContext.GpuBuffer[] statsBuffers = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final ByteBuffer lightScratch = BufferUtils.createByteBuffer(MAX_GPU_POINT_LIGHTS * LIGHT_BYTES);
    private final ByteBuffer zeroStats = BufferUtils.createByteBuffer(STATS_BYTES);
    private long pipelineLayout;
    private long pipeline;
    private int tilesX;
    private int tilesY;
    private int clusterCount;
    private int activeLights;
    private int lastOverflowCount;
    private int lastMaxLightsInCluster;
    private double frameSetupMs;
    private long bufferGeneration;
    private final long[] descriptorGenerations = new long[VulkanContext.FRAMES_IN_FLIGHT];
    private int diagnosticReadbackCountdown;

    ClusteredLighting(
            VulkanContext context,
            DescriptorManager descriptors,
            Path shaderRoot
    ) {
        this(context, descriptors, ShaderSources.directory(shaderRoot));
    }

    ClusteredLighting(
            VulkanContext context,
            DescriptorManager descriptors,
            ShaderSources shaderSources
    ) {
        this.context = context;
        this.descriptors = descriptors;
        this.shaderSources = shaderSources;
        createPipeline();
    }

    void writeDescriptors(long globalSet, int frameIndex) {
        writeDescriptors(globalSet, frameIndex, RenderViewport.fullScreen(context.width(), context.height()));
    }

    void writeDescriptors(long globalSet, int frameIndex, RenderViewport viewport) {
        ensureBuffers(Math.max(1, viewport.width()), Math.max(1, viewport.height()));
        if (descriptorGenerations[frameIndex] == bufferGeneration) return;
        descriptors.writeStorageBuffer(globalSet, 2, lightBuffers[frameIndex], lightBuffers[frameIndex].size());
        descriptors.writeStorageBuffer(globalSet, 3, countBuffers[frameIndex], countBuffers[frameIndex].size());
        descriptors.writeStorageBuffer(globalSet, 4, indexBuffers[frameIndex], indexBuffers[frameIndex].size());
        descriptors.writeStorageBuffer(globalSet, 5, statsBuffers[frameIndex], statsBuffers[frameIndex].size());
        descriptorGenerations[frameIndex] = bufferGeneration;
    }

    void prepareAndDispatch(VkCommandBuffer cmd, long globalSet, int frameIndex, RenderViewport viewport,
                            List<PointLight> lights, boolean collectStats) {
        long start = System.nanoTime();
        ensureBuffers(Math.max(1, viewport.width()), Math.max(1, viewport.height()));
        if (collectStats && diagnosticReadbackCountdown-- <= 0) {
            int[] previousStats = context.readInts(statsBuffers[frameIndex], 2, 0);
            lastOverflowCount = previousStats.length > 0 ? previousStats[0] : 0;
            lastMaxLightsInCluster = previousStats.length > 1 ? previousStats[1] : 0;
            diagnosticReadbackCountdown = 30;
        } else if (!collectStats) {
            diagnosticReadbackCountdown = 0;
        }
        activeLights = Math.min(MAX_GPU_POINT_LIGHTS, lights == null ? 0 : lights.size());
        validateBufferCapacities(frameIndex);
        if (activeLights == 0) {
            frameSetupMs = (System.nanoTime() - start) / 1_000_000.0;
            return;
        }
        lightScratch.clear();
        for (int i = 0; i < activeLights; i++) {
            PointLight light = lights.get(i);
            lightScratch.putFloat(light.x()).putFloat(light.y()).putFloat(light.z()).putFloat(light.radius());
            lightScratch.putFloat(light.red()).putFloat(light.green()).putFloat(light.blue()).putFloat(light.intensity());
        }
        lightScratch.flip();
        context.uploadBytes(lightBuffers[frameIndex], lightScratch, 0);
        if (activeLights <= DIRECT_LIGHT_THRESHOLD || clusterCount == 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferMemoryBarrier.Buffer uploadBarrier = VkBufferMemoryBarrier.calloc(1, stack);
                uploadBarrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(lightBuffers[frameIndex].buffer()).offset(0).size((long) activeLights * LIGHT_BYTES);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        0, null, uploadBarrier, null);
            }
            frameSetupMs = (System.nanoTime() - start) / 1_000_000.0;
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdFillBuffer(cmd, countBuffers[frameIndex].buffer(), 0, countBuffers[frameIndex].size(), 0);
            vkCmdFillBuffer(cmd, statsBuffers[frameIndex].buffer(), 0, statsBuffers[frameIndex].size(), 0);
            VkBufferMemoryBarrier.Buffer clearBarrier = VkBufferMemoryBarrier.calloc(2, stack);
            clearBarrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(countBuffers[frameIndex].buffer()).offset(0).size(countBuffers[frameIndex].size());
            clearBarrier.get(1).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(statsBuffers[frameIndex].buffer()).offset(0).size(statsBuffers[frameIndex].size());
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, clearBarrier, null);

            VkBufferMemoryBarrier.Buffer uploadBarrier = VkBufferMemoryBarrier.calloc(1, stack);
            uploadBarrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(lightBuffers[frameIndex].buffer()).offset(0).size((long) activeLights * LIGHT_BYTES);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, uploadBarrier, null);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(globalSet), null);
            vkCmdDispatch(cmd, (activeLights + 63) / 64, 1, 1);
            VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(3, stack);
            barrier.get(0).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(countBuffers[frameIndex].buffer()).offset(0).size(countBuffers[frameIndex].size());
            barrier.get(1).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(indexBuffers[frameIndex].buffer()).offset(0).size(indexBuffers[frameIndex].size());
            barrier.get(2).sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(statsBuffers[frameIndex].buffer()).offset(0).size(statsBuffers[frameIndex].size());
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0, null, barrier, null);
        }
        frameSetupMs = (System.nanoTime() - start) / 1_000_000.0;
    }

    private void validateBufferCapacities(int frameIndex) {
        long requiredLights = (long) MAX_GPU_POINT_LIGHTS * LIGHT_BYTES;
        long requiredCounts = (long) clusterCount * Integer.BYTES;
        long requiredIndices = (long) clusterCount * MAX_LIGHTS_PER_CLUSTER * Integer.BYTES;
        if (lightBuffers[frameIndex].size() < requiredLights
                || countBuffers[frameIndex].size() < requiredCounts
                || indexBuffers[frameIndex].size() < requiredIndices
                || statsBuffers[frameIndex].size() < STATS_BYTES) {
            throw new IllegalStateException("Clustered-light buffer capacity does not match viewport layout");
        }
    }

    int activeLights() {
        return activeLights;
    }

    int tilesX() {
        return tilesX;
    }

    int tilesY() {
        return tilesY;
    }

    int clusterCount() {
        return clusterCount;
    }

    double frameSetupMs() {
        return frameSetupMs;
    }

    int lastOverflowCount() {
        return lastOverflowCount;
    }

    int lastMaxLightsInCluster() {
        return lastMaxLightsInCluster;
    }

    static int tilesForPixels(int pixels) {
        return Math.max(1, (pixels + LIGHT_TILE_SIZE - 1) / LIGHT_TILE_SIZE);
    }

    static int clusterCountFor(int width, int height) {
        return tilesForPixels(width) * tilesForPixels(height) * LIGHT_CLUSTER_Z_SLICES;
    }

    private void ensureBuffers(int width, int height) {
        int neededTilesX = tilesForPixels(width);
        int neededTilesY = tilesForPixels(height);
        int neededClusters = neededTilesX * neededTilesY * LIGHT_CLUSTER_Z_SLICES;
        if (neededClusters == clusterCount && lightBuffers[0] != null) return;
        context.waitIdle();
        destroyBuffers();
        tilesX = neededTilesX;
        tilesY = neededTilesY;
        clusterCount = neededClusters;
        bufferGeneration++;
        long lightBytes = (long) MAX_GPU_POINT_LIGHTS * LIGHT_BYTES;
        long countBytes = (long) clusterCount * Integer.BYTES;
        long indexBytes = (long) clusterCount * MAX_LIGHTS_PER_CLUSTER * Integer.BYTES;
        long statsBytes = STATS_BYTES;
        for (int i = 0; i < VulkanContext.FRAMES_IN_FLIGHT; i++) {
            lightBuffers[i] = context.createBuffer(lightBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            countBuffers[i] = context.createBuffer(countBytes,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            indexBuffers[i] = context.createBuffer(indexBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            statsBuffers[i] = context.createBuffer(statsBytes,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            zeroStats.position(0).limit(STATS_BYTES);
            context.uploadBytes(statsBuffers[i], zeroStats, 0);
        }
    }

    private void createPipeline() {
        String source = ShaderLibrary.preprocess(shaderSources, "clustered_lighting.comp.glsl", "#version 450\n");
        ByteBuffer spirv = ShaderLibrary.compile("clustered_lighting.comp", source, shaderc_glsl_compute_shader);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pModule = stack.mallocLong(1);
            VulkanContext.check(vkCreateShaderModule(context.device(), moduleInfo, null, pModule),
                    "create clustered lighting shader");
            long module = pModule.get(0);
            try {
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pSetLayouts(stack.longs(descriptors.globalSetLayout()));
                LongBuffer pLayout = stack.mallocLong(1);
                VulkanContext.check(vkCreatePipelineLayout(context.device(), layoutInfo, null, pLayout),
                        "create clustered lighting pipeline layout");
                pipelineLayout = pLayout.get(0);
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(module)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(stage)
                        .layout(pipelineLayout);
                LongBuffer pPipeline = stack.mallocLong(1);
                VulkanContext.check(vkCreateComputePipelines(context.device(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline),
                        "create clustered lighting pipeline");
                pipeline = pPipeline.get(0);
            } finally {
                vkDestroyShaderModule(context.device(), module, null);
            }
        }
    }

    private void destroyBuffers() {
        for (int i = 0; i < VulkanContext.FRAMES_IN_FLIGHT; i++) {
            context.destroyBuffer(lightBuffers[i]);
            context.destroyBuffer(countBuffers[i]);
            context.destroyBuffer(indexBuffers[i]);
            context.destroyBuffer(statsBuffers[i]);
            lightBuffers[i] = null;
            countBuffers[i] = null;
            indexBuffers[i] = null;
            statsBuffers[i] = null;
        }
    }

    @Override
    public void close() {
        destroyBuffers();
        if (pipeline != 0) vkDestroyPipeline(context.device(), pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(context.device(), pipelineLayout, null);
    }
}
