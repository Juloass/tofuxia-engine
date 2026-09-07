package fr.tofuxia.renderer;

import fr.tofuxia.renderapi.RenderViewport;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandBufferInheritanceInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Fixed secondary-command workers. Every logical worker has a dedicated
 * single-thread executor and one command pool per frame-in-flight slot.
 */
final class SecondaryCommandWorkers implements AutoCloseable {
    private final VulkanContext context;
    private final long pipelineLayout;
    private final List<Worker> workers;

    SecondaryCommandWorkers(VulkanContext context, long pipelineLayout, int workerCount) {
        this.context = context;
        this.pipelineLayout = pipelineLayout;
        int count = Math.max(1, workerCount);
        ArrayList<Worker> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) created.add(new Worker(i));
        workers = List.copyOf(created);
    }

    int workerCount() {
        return workers.size();
    }

    FrameCommands record(int frameIndex, long globalSet,
                         List<SecondaryDraw> mainDraws, MainPass main,
                         List<SecondaryDraw> shadowDraws, ShadowPass shadow) {
        List<Range> mainRanges = partition(mainDraws.size(), workers.size());
        List<Range> shadowRanges = partition(shadowDraws.size(), workers.size());
        ArrayList<CompletableFuture<WorkerCommands>> futures = new ArrayList<>(workers.size());
        long submitStart = System.nanoTime();
        for (int i = 0; i < workers.size(); i++) {
            Worker worker = workers.get(i);
            List<SecondaryDraw> mainSlice = copyRange(mainDraws, mainRanges.get(i));
            List<SecondaryDraw> shadowSlice = copyRange(shadowDraws, shadowRanges.get(i));
            futures.add(CompletableFuture.supplyAsync(
                    () -> worker.record(frameIndex, globalSet, mainSlice, main, shadowSlice, shadow),
                    worker.executor));
        }
        long submitEnd = System.nanoTime();
        ArrayList<VkCommandBuffer> mainBuffers = new ArrayList<>(workers.size());
        ArrayList<VkCommandBuffer> shadowBuffers = new ArrayList<>(workers.size());
        int mainDrawCount = 0;
        int shadowDrawCount = 0;
        int pipelineBinds = 0;
        double workerCpuMs = 0.0;
        long waitStart = System.nanoTime();
        try {
            for (CompletableFuture<WorkerCommands> future : futures) {
                WorkerCommands commands = future.join();
                mainBuffers.add(commands.main());
                shadowBuffers.add(commands.shadow());
                mainDrawCount += commands.mainDraws();
                shadowDrawCount += commands.shadowDraws();
                pipelineBinds += commands.pipelineBinds();
                workerCpuMs += commands.workerMs();
            }
        } catch (CompletionException error) {
            throw new IllegalStateException("Secondary command recording failed", error.getCause());
        }
        long waitEnd = System.nanoTime();
        return new FrameCommands(List.copyOf(mainBuffers), List.copyOf(shadowBuffers),
                mainDrawCount, shadowDrawCount, pipelineBinds,
                (submitEnd - submitStart) / 1_000_000.0,
                (waitEnd - waitStart) / 1_000_000.0,
                workerCpuMs);
    }

    static List<Range> partition(int itemCount, int partitions) {
        if (itemCount < 0 || partitions <= 0) throw new IllegalArgumentException();
        ArrayList<Range> ranges = new ArrayList<>(partitions);
        int base = itemCount / partitions;
        int extra = itemCount % partitions;
        int start = 0;
        for (int i = 0; i < partitions; i++) {
            int size = base + (i < extra ? 1 : 0);
            ranges.add(new Range(start, start + size));
            start += size;
        }
        return List.copyOf(ranges);
    }

    private static <T> List<T> copyRange(List<T> values, Range range) {
        return List.copyOf(values.subList(range.start(), range.end()));
    }

    @Override
    public void close() {
        for (Worker worker : workers) worker.executor.shutdown();
        for (Worker worker : workers) {
            try {
                if (!worker.executor.awaitTermination(5, TimeUnit.SECONDS)) worker.executor.shutdownNow();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                worker.executor.shutdownNow();
            }
        }
        for (Worker worker : workers) {
            for (long pool : worker.pools) vkDestroyCommandPool(context.device(), pool, null);
        }
    }

    record Range(int start, int end) {
        Range {
            if (start < 0 || end < start) throw new IllegalArgumentException();
        }
    }

    record MainPass(long renderPass, long framebuffer, int width, int height, RenderViewport viewport) {}
    record ShadowPass(long renderPass, long framebuffer, int width, int height,
                      float constantBias, float slopeBias, boolean enabled) {}
    record FrameCommands(List<VkCommandBuffer> main, List<VkCommandBuffer> shadow,
                         int mainDraws, int shadowDraws, int pipelineBinds,
                         double submitJobsMs, double waitWorkersMs, double workerCpuMs) {}
    private record WorkerCommands(VkCommandBuffer main, VkCommandBuffer shadow,
                                  int mainDraws, int shadowDraws, int pipelineBinds, double workerMs) {}

    private final class Worker {
        private final long[] pools = new long[VulkanContext.FRAMES_IN_FLIGHT];
        private final VkCommandBuffer[] main = new VkCommandBuffer[VulkanContext.FRAMES_IN_FLIGHT];
        private final VkCommandBuffer[] shadow = new VkCommandBuffer[VulkanContext.FRAMES_IN_FLIGHT];
        private final AtomicReference<Thread> owner = new AtomicReference<>();
        private final ExecutorService executor;

        private Worker(int index) {
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tofuxia-vk-record-" + index);
                thread.setDaemon(true);
                owner.set(thread);
                return thread;
            });
            for (int frame = 0; frame < VulkanContext.FRAMES_IN_FLIGHT; frame++) createFrameResources(frame);
        }

        private void createFrameResources(int frame) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                        .queueFamilyIndex(context.graphicsFamilyIndex());
                LongBuffer pPool = stack.mallocLong(1);
                VulkanContext.check(vkCreateCommandPool(context.device(), poolInfo, null, pPool),
                        "create secondary worker command pool");
                pools[frame] = pPool.get(0);
                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(pools[frame])
                        .level(VK_COMMAND_BUFFER_LEVEL_SECONDARY)
                        .commandBufferCount(2);
                PointerBuffer buffers = stack.mallocPointer(2);
                VulkanContext.check(vkAllocateCommandBuffers(context.device(), allocInfo, buffers),
                        "allocate secondary worker command buffers");
                main[frame] = new VkCommandBuffer(buffers.get(0), context.device());
                shadow[frame] = new VkCommandBuffer(buffers.get(1), context.device());
            }
        }

        private WorkerCommands record(int frame, long globalSet,
                                      List<SecondaryDraw> mainDraws, MainPass mainPass,
                                      List<SecondaryDraw> shadowDraws, ShadowPass shadowPass) {
            long started = System.nanoTime();
            if (Thread.currentThread() != owner.get()) {
                throw new IllegalStateException("Worker command pool accessed by a non-owner thread");
            }
            VulkanContext.check(vkResetCommandPool(context.device(), pools[frame], 0),
                    "reset secondary worker command pool");
            int mainBinds = recordMain(main[frame], globalSet, mainDraws, mainPass);
            int shadowBinds = recordShadow(shadow[frame], globalSet, shadowDraws, shadowPass);
            return new WorkerCommands(main[frame], shadow[frame], mainDraws.size(),
                    shadowPass.enabled() ? shadowDraws.size() : 0, mainBinds + shadowBinds,
                    (System.nanoTime() - started) / 1_000_000.0);
        }

        private int recordMain(VkCommandBuffer cmd, long globalSet, List<SecondaryDraw> draws, MainPass pass) {
            begin(cmd, pass.renderPass(), pass.framebuffer());
            setViewport(cmd, pass.viewport());
            int pipelineBinds = recordDraws(cmd, globalSet, draws);
            VulkanContext.check(vkEndCommandBuffer(cmd), "end main secondary command buffer");
            return pipelineBinds;
        }

        private int recordShadow(VkCommandBuffer cmd, long globalSet, List<SecondaryDraw> draws, ShadowPass pass) {
            begin(cmd, pass.renderPass(), pass.framebuffer());
            int pipelineBinds = 0;
            if (pass.enabled()) {
                setViewport(cmd, pass.width(), pass.height());
                vkCmdSetDepthBias(cmd, pass.constantBias(), 0.0f, pass.slopeBias());
                pipelineBinds = recordDraws(cmd, globalSet, draws);
            }
            VulkanContext.check(vkEndCommandBuffer(cmd), "end shadow secondary command buffer");
            return pipelineBinds;
        }

        private void begin(VkCommandBuffer cmd, long renderPass, long framebuffer) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBufferInheritanceInfo inheritance = VkCommandBufferInheritanceInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO)
                        .renderPass(renderPass)
                        .subpass(0)
                        .framebuffer(framebuffer);
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                                | VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT)
                        .pInheritanceInfo(inheritance);
                VulkanContext.check(vkBeginCommandBuffer(cmd, begin), "begin secondary command buffer");
            }
        }

        private int recordDraws(VkCommandBuffer cmd, long globalSet, List<SecondaryDraw> draws) {
            int pipelineBinds = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                        stack.longs(globalSet), null);
                long boundPipeline = 0;
                long boundTexture = 0;
                long boundJoints = 0;
                for (SecondaryDraw draw : draws) {
                    if (draw.pipeline() != boundPipeline) {
                        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, draw.pipeline());
                        boundPipeline = draw.pipeline();
                        pipelineBinds++;
                    }
                    if (draw.textureSet() != boundTexture) {
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 1,
                                stack.longs(draw.textureSet()), null);
                        boundTexture = draw.textureSet();
                    }
                    if (draw.jointsSet() != boundJoints) {
                        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 2,
                                stack.longs(draw.jointsSet()), null);
                        boundJoints = draw.jointsSet();
                    }
                    byte[] bytes = draw.pushConstants();
                    vkCmdPushConstants(cmd, pipelineLayout,
                            VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                            0, stack.bytes(bytes));
                    vkCmdBindVertexBuffers(cmd, 0, stack.longs(draw.vertexBuffer()),
                            stack.longs(draw.vertexBufferOffset()));
                    vkCmdBindIndexBuffer(cmd, draw.indexBuffer(), draw.indexBufferOffset(), VK_INDEX_TYPE_UINT32);
                    vkCmdDrawIndexed(cmd, draw.indexCount(), 1, 0, 0, 0);
                }
            }
            return pipelineBinds;
        }

        private void setViewport(VkCommandBuffer cmd, int width, int height) {
            setViewport(cmd, RenderViewport.fullScreen(width, height));
        }

        private void setViewport(VkCommandBuffer cmd, RenderViewport renderViewport) {
            RenderViewport vp = renderViewport == null ? RenderViewport.fullScreen(1, 1) : renderViewport;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkViewport.Buffer viewport = VkViewport.calloc(1, stack)
                        .x(vp.x()).y(vp.y()).width(vp.width()).height(vp.height()).minDepth(0.0f).maxDepth(1.0f);
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.offset().set(vp.x(), vp.y());
                scissor.extent().set(vp.width(), vp.height());
                vkCmdSetViewport(cmd, 0, viewport);
                vkCmdSetScissor(cmd, 0, scissor);
            }
        }
    }
}
