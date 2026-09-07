package fr.tofuxia.renderer;

import fr.tofuxia.renderapi.RenderViewport;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memCopy;
import static org.lwjgl.system.MemoryUtil.memIntBuffer;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the Vulkan device, swapchain and per-frame synchronization, and
 * provides the resource helpers (buffers, images, one-shot commands) the
 * rest of the renderer builds on. Contains no material/pipeline logic;
 * that lives in {@link Renderer}, {@link PipelineCache} and friends.
 */
public final class VulkanContext implements AutoCloseable {
    public static final int FRAMES_IN_FLIGHT = 2;

    private final RenderThreadGuard renderThread = new RenderThreadGuard();
    private final long window;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;
    private int graphicsFamily;
    private int presentFamily;

    private long swapchain;
    private int swapchainFormat;
    private final VkExtent2D swapchainExtent = VkExtent2D.create();
    private final List<Long> swapchainImageViews = new ArrayList<>();
    private final List<Long> framebuffers = new ArrayList<>();
    private final List<Long> overlayFramebuffers = new ArrayList<>();
    private long depthImage;
    private long depthImageMemory;
    private long depthImageView;
    private long selectionMaskImage;
    private long selectionMaskMemory;
    private long selectionMaskView;
    private long renderPass;
    private long overlayRenderPass;

    private long commandPool;
    private final VkCommandBuffer[] frameCommandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final VkCommandBuffer[] overlayCommandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] imageAvailable = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlightFences = new long[FRAMES_IN_FLIGHT];
    private long[] renderFinished = new long[0]; // one per swapchain image

    private int frameIndex;
    private int currentImageIndex = -1;
    private boolean recreatePending;
    private FrameBeginTimings lastBeginTimings = FrameBeginTimings.EMPTY;

    public VulkanContext(long window) {
        this.window = window;
        createInstance();
        createSurface();
        pickPhysicalDevice();
        createLogicalDevice();
        createCommandPool();
        createFrameCommandBuffers();
        createSwapchainResources();
        createFrameSync();
        System.out.println("[vulkan] context ready, swapchain " + swapchainExtent.width() + "x"
                + swapchainExtent.height() + " format=" + swapchainFormat);
    }

    // ------------------------------------------------------------------ frame

    /**
     * Acquires the next swapchain image and begins the frame's command buffer.
     * Render passes are recorded by the renderer. Returns null when the swapchain had to be recreated
     * this frame (caller should skip rendering once).
     */
    public VkCommandBuffer beginFrame(float[] clearColor) {
        renderThread.check("beginFrame");
        long recreateStart = System.nanoTime();
        if (recreatePending) {
            recreateSwapchain();
            recreatePending = false;
        }
        long recreateEnd = System.nanoTime();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long waitFenceStart = System.nanoTime();
            vkWaitForFences(device, inFlightFences[frameIndex], true, Long.MAX_VALUE);
            long waitFenceEnd = System.nanoTime();
            IntBuffer pImageIndex = stack.mallocInt(1);
            long acquireStart = System.nanoTime();
            int acquire = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE,
                    imageAvailable[frameIndex], VK_NULL_HANDLE, pImageIndex);
            long acquireEnd = System.nanoTime();
            if (acquire == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain();
                lastBeginTimings = new FrameBeginTimings(
                        nanosToMillis(recreateEnd - recreateStart),
                        nanosToMillis(waitFenceEnd - waitFenceStart),
                        nanosToMillis(acquireEnd - acquireStart),
                        0, 0, 0, 0);
                return null;
            }
            if (acquire == VK_SUBOPTIMAL_KHR) {
                recreatePending = true;
            } else {
                check(acquire, "acquire swapchain image");
            }
            long resetFenceStart = System.nanoTime();
            vkResetFences(device, inFlightFences[frameIndex]);
            long resetFenceEnd = System.nanoTime();
            currentImageIndex = pImageIndex.get(0);

            VkCommandBuffer cmd = frameCommandBuffers[frameIndex];
            long resetCommandStart = System.nanoTime();
            vkResetCommandBuffer(cmd, 0);
            long resetCommandEnd = System.nanoTime();
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            long beginCommandStart = System.nanoTime();
            check(vkBeginCommandBuffer(cmd, beginInfo), "begin frame command buffer");
            long beginCommandEnd = System.nanoTime();
            lastBeginTimings = new FrameBeginTimings(
                    nanosToMillis(recreateEnd - recreateStart),
                    nanosToMillis(waitFenceEnd - waitFenceStart),
                    nanosToMillis(acquireEnd - acquireStart),
                    nanosToMillis(resetFenceEnd - resetFenceStart),
                    nanosToMillis(resetCommandEnd - resetCommandStart),
                    nanosToMillis(beginCommandEnd - beginCommandStart),
                    0);

            return cmd;
        }
    }

    public void beginMainRenderPass(VkCommandBuffer cmd, float[] clearColor) {
        renderThread.check("beginMainRenderPass");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clear = VkClearValue.calloc(3, stack);
            clear.get(0).color()
                    .float32(0, clearColor[0]).float32(1, clearColor[1])
                    .float32(2, clearColor[2]).float32(3, 1.0f);
            clear.get(1).depthStencil().depth(1.0f).stencil(0);
            clear.get(2).color().float32(0, 0.0f);
            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffers.get(currentImageIndex))
                    .pClearValues(clear);
            renderPassInfo.renderArea().offset().set(0, 0);
            renderPassInfo.renderArea().extent().set(swapchainExtent);
            vkCmdBeginRenderPass(cmd, renderPassInfo, VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS);
        }
    }

    public VkCommandBuffer beginOverlaySecondary() {
        renderThread.check("beginOverlaySecondary");
        VkCommandBuffer cmd = overlayCommandBuffers[frameIndex];
        check(vkResetCommandBuffer(cmd, 0), "reset overlay secondary command buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferInheritanceInfo inheritance = VkCommandBufferInheritanceInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO)
                    .renderPass(overlayRenderPass)
                    .subpass(0)
                    .framebuffer(currentOverlayFramebufferHandle());
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            | VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT)
                    .pInheritanceInfo(inheritance);
            check(vkBeginCommandBuffer(cmd, beginInfo), "begin overlay secondary command buffer");
            setFullViewport(cmd, width(), height());
        }
        return cmd;
    }

    public void endOverlaySecondary(VkCommandBuffer cmd) {
        renderThread.check("endOverlaySecondary");
        check(vkEndCommandBuffer(cmd), "end overlay secondary command buffer");
    }

    public void beginOverlayRenderPass(VkCommandBuffer cmd) {
        renderThread.check("beginOverlayRenderPass");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(overlayRenderPass)
                    .framebuffer(currentOverlayFramebufferHandle());
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(swapchainExtent);
            vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS);
        }
    }

    public void setFullViewport(VkCommandBuffer cmd, int width, int height) {
        setViewportAndScissor(cmd, RenderViewport.fullScreen(width, height));
    }

    public void setViewportAndScissor(VkCommandBuffer cmd, RenderViewport renderViewport) {
        RenderViewport vp = renderViewport == null ? RenderViewport.fullScreen(width(), height()) : renderViewport;
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

    public void endRenderPass(VkCommandBuffer cmd) {
        renderThread.check("endRenderPass");
        vkCmdEndRenderPass(cmd);
    }

    /** Submits and presents the fully recorded frame begun by beginFrame. */
    public FrameSubmitTimings endFrame() {
        renderThread.check("endFrame");
        VkCommandBuffer cmd = frameCommandBuffers[frameIndex];
        long endCommandStart = System.nanoTime();
        check(vkEndCommandBuffer(cmd), "end frame command buffer");
        long endCommandEnd = System.nanoTime();
        long submitStart;
        long submitEnd;
        long presentStart;
        long presentEnd;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long signalSemaphore = renderFinished[currentImageIndex];
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(stack.longs(imageAvailable[frameIndex]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(signalSemaphore));
            submitStart = System.nanoTime();
            check(vkQueueSubmit(graphicsQueue, submitInfo, inFlightFences[frameIndex]), "submit frame");
            submitEnd = System.nanoTime();

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(signalSemaphore))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain))
                    .pImageIndices(stack.ints(currentImageIndex));
            presentStart = System.nanoTime();
            int present = vkQueuePresentKHR(presentQueue, presentInfo);
            presentEnd = System.nanoTime();
            if (present == VK_ERROR_OUT_OF_DATE_KHR || present == VK_SUBOPTIMAL_KHR) {
                recreatePending = true;
            } else {
                check(present, "present swapchain image");
            }
        }
        long advanceStart = System.nanoTime();
        frameIndex = (frameIndex + 1) % FRAMES_IN_FLIGHT;
        long advanceEnd = System.nanoTime();
        return new FrameSubmitTimings(
                nanosToMillis(endCommandEnd - endCommandStart),
                nanosToMillis(submitEnd - submitStart),
                nanosToMillis(presentEnd - presentStart),
                nanosToMillis(advanceEnd - advanceStart));
    }

    public FrameBeginTimings lastBeginTimings() {
        return lastBeginTimings;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    public record FrameBeginTimings(double recreateSwapchainMs, double waitPreviousFrameFenceMs,
                                     double acquireSwapchainImageMs, double resetFrameFenceMs,
                                     double resetCommandBufferMs, double beginCommandBufferMs,
                                     double skippedFrameMs) {
        public static final FrameBeginTimings EMPTY = new FrameBeginTimings(0, 0, 0, 0, 0, 0, 0);
    }

    public record FrameSubmitTimings(double endCommandBufferMs, double queueSubmitMs,
                                     double presentMs, double frameIndexAdvanceMs) {}

    public void requestSwapchainRecreation() {
        renderThread.check("requestSwapchainRecreation");
        recreatePending = true;
    }

    public void waitIdle() {
        renderThread.check("waitIdle");
        if (device != null) {
            vkDeviceWaitIdle(device);
        }
    }

    // ------------------------------------------------------------- accessors

    public VkDevice device() {
        return device;
    }

    public long renderPassHandle() {
        return renderPass;
    }

    public long overlayRenderPassHandle() { return overlayRenderPass; }
    public long selectionMaskView() { return selectionMaskView; }
    public long sceneDepthView() { return depthImageView; }

    long currentFramebufferHandle() {
        if (currentImageIndex < 0) throw new IllegalStateException("No acquired swapchain image");
        return framebuffers.get(currentImageIndex);
    }

    long currentOverlayFramebufferHandle() {
        if (currentImageIndex < 0) throw new IllegalStateException("No acquired swapchain image");
        return overlayFramebuffers.get(currentImageIndex);
    }

    int graphicsFamilyIndex() {
        return graphicsFamily;
    }

    public int frameIndex() {
        return frameIndex;
    }

    public int width() {
        return swapchainExtent.width();
    }

    public int height() {
        return swapchainExtent.height();
    }

    // -------------------------------------------------------------- buffers

    public record GpuBuffer(long buffer, long memory, long size) {
    }

    public GpuBuffer createBuffer(long size, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            check(vkCreateBuffer(device, bufferInfo, null, pBuffer), "create buffer");
            long buffer = pBuffer.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate buffer memory");
            long memory = pMemory.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "bind buffer memory");
            return new GpuBuffer(buffer, memory, size);
        }
    }

    public void destroyBuffer(GpuBuffer buffer) {
        if (buffer == null) return;
        if (buffer.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer.buffer, null);
        if (buffer.memory != VK_NULL_HANDLE) vkFreeMemory(device, buffer.memory, null);
    }

    public void uploadFloats(GpuBuffer buffer, float[] values) {
        FloatBuffer src = BufferUtils.createFloatBuffer(values.length);
        src.put(values).flip();
        uploadRaw(buffer, memAddress(src), (long) values.length * Float.BYTES, 0);
    }

    public void uploadInts(GpuBuffer buffer, int[] values) {
        uploadInts(buffer, values, 0);
    }

    public void uploadInts(GpuBuffer buffer, int[] values, long offset) {
        if (values == null || values.length == 0) return;
        IntBuffer src = BufferUtils.createIntBuffer(values.length);
        src.put(values).flip();
        uploadRaw(buffer, memAddress(src), (long) values.length * Integer.BYTES, offset);
    }

    public void uploadBytes(GpuBuffer buffer, ByteBuffer src, long offset) {
        if (src == null || !src.hasRemaining()) return;
        ByteBuffer upload = src;
        if (!src.isDirect()) {
            upload = BufferUtils.createByteBuffer(src.remaining());
            upload.put(src.duplicate()).flip();
        }
        uploadRaw(buffer, memAddress(upload), upload.remaining(), offset);
    }

    public int[] readInts(GpuBuffer buffer, int count, long offset) {
        if (buffer == null || count <= 0) return new int[0];
        int[] out = new int[count];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, buffer.memory, offset, (long) count * Integer.BYTES, 0, mapped), "map buffer read");
            IntBuffer src = memIntBuffer(mapped.get(0), count);
            src.get(out);
            vkUnmapMemory(device, buffer.memory);
        }
        return out;
    }

    private void uploadRaw(GpuBuffer buffer, long srcAddress, long bytes, long offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, buffer.memory, offset, bytes, 0, mapped), "map buffer");
            memCopy(srcAddress, mapped.get(0), bytes);
            vkUnmapMemory(device, buffer.memory);
        }
    }

    // --------------------------------------------------------------- images

    public record GpuImage(long image, long memory, long view, int width, int height, int mipLevels) {
    }

    public record DepthTarget(long image, long memory, long view, int width, int height) {}

    public DepthTarget createSampledDepthTarget(int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_D32_SFLOAT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageInfo.extent().width(width).height(height).depth(1);
            imageInfo.mipLevels(1).arrayLayers(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateImage(device, imageInfo, null, p), "create sampled depth image");
            long image = p.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO).allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(device, alloc, null, p), "allocate sampled depth memory");
            long memory = p.get(0);
            check(vkBindImageMemory(device, image, memory, 0), "bind sampled depth memory");
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO).image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_D32_SFLOAT);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            check(vkCreateImageView(device, viewInfo, null, p), "create sampled depth view");
            return new DepthTarget(image, memory, p.get(0), width, height);
        }
    }

    public void destroyDepthTarget(DepthTarget target) {
        if (target == null) return;
        vkDestroyImageView(device, target.view(), null);
        vkDestroyImage(device, target.image(), null);
        vkFreeMemory(device, target.memory(), null);
    }

    /** Creates a sampled 2D image and uploads RGBA8 pixels through a staging buffer. */
    public GpuImage createTextureImage(int width, int height, ByteBuffer rgbaPixels, boolean srgb) {
        return createTextureImage(width, height, rgbaPixels, srgb, false);
    }

    /** Creates a sampled 2D image and uploads RGBA8 pixels through a staging buffer. */
    public GpuImage createTextureImage(int width, int height, ByteBuffer rgbaPixels, boolean srgb, boolean mipmaps) {
        int format = srgb ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
        return createSampledImage(width, height, format, rgbaPixels, 4, mipmaps);
    }

    public void updateTextureImage(GpuImage target, ByteBuffer rgbaPixels) {
        if (target == null) return;
        long imageSize = (long) target.width * target.height * 4;
        GpuBuffer staging = createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try {
            uploadBytes(staging, rgbaPixels, 0);
            withOneShotCommands(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    transitionImage(stack, cmd, target.image,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            target.mipLevels);
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1);
                    region.get(0).imageOffset().set(0, 0, 0);
                    region.get(0).imageExtent().set(target.width, target.height, 1);
                    vkCmdCopyBufferToImage(cmd, staging.buffer, target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                    if (target.mipLevels > 1) {
                        generateMipmaps(stack, cmd, target.image, target.width, target.height, target.mipLevels);
                    } else {
                        transitionImage(stack, cmd, target.image,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                    }
                }
            });
        } finally {
            destroyBuffer(staging);
        }
    }

    /** Creates a sampled single-channel image (font atlases). */
    public GpuImage createTextureImageR8(int width, int height, ByteBuffer pixels) {
        return createSampledImage(width, height, VK_FORMAT_R8_UNORM, pixels, 1, false);
    }

    private GpuImage createSampledImage(int width, int height, int format, ByteBuffer pixels, int bytesPerPixel,
                                        boolean mipmaps) {
        long imageSize = (long) width * height * bytesPerPixel;
        GpuBuffer staging = createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            uploadBytes(staging, pixels, 0);
            int mipLevels = mipmaps && supportsLinearMipBlit(format) ? mipLevels(width, height) : 1;

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                            | (mipLevels > 1 ? VK_IMAGE_USAGE_TRANSFER_SRC_BIT : 0))
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageInfo.extent().width(width).height(height).depth(1);
            imageInfo.mipLevels(mipLevels).arrayLayers(1);
            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device, imageInfo, null, pImage), "create texture image");
            long image = pImage.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate texture memory");
            long memory = pMemory.get(0);
            check(vkBindImageMemory(device, image, memory, 0), "bind texture memory");

            withOneShotCommands(cmd -> {
                try (MemoryStack barrierStack = MemoryStack.stackPush()) {
                    transitionImage(barrierStack, cmd, image,
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            0, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            mipLevels);
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, barrierStack);
                    region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                    region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0).baseArrayLayer(0).layerCount(1);
                    region.get(0).imageOffset().set(0, 0, 0);
                    region.get(0).imageExtent().set(width, height, 1);
                    vkCmdCopyBufferToImage(cmd, staging.buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                    if (mipLevels > 1) {
                        generateMipmaps(barrierStack, cmd, image, width, height, mipLevels);
                    } else {
                        transitionImage(barrierStack, cmd, image,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                    }
                }
            });

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device, viewInfo, null, pView), "create texture image view");
            return new GpuImage(image, memory, pView.get(0), width, height, mipLevels);
        } finally {
            destroyBuffer(staging);
        }
    }

    public void destroyImage(GpuImage image) {
        if (image == null) return;
        if (image.view != VK_NULL_HANDLE) vkDestroyImageView(device, image.view, null);
        if (image.image != VK_NULL_HANDLE) vkDestroyImage(device, image.image, null);
        if (image.memory != VK_NULL_HANDLE) vkFreeMemory(device, image.memory, null);
    }

    public long createSampler(SamplerSettings settings) {
        return createSampler(settings, false);
    }

    public long createSampler(SamplerSettings settings, boolean mipmaps) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int filter = settings.filter() == SamplerSettings.Filter.NEAREST ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
            int wrap = settings.wrap() == SamplerSettings.Wrap.CLAMP
                    ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(filter).minFilter(filter)
                    .addressModeU(wrap).addressModeV(wrap).addressModeW(wrap)
                    .mipmapMode(mipmaps && settings.filter() == SamplerSettings.Filter.LINEAR
                            ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .minLod(0.0f)
                    .maxLod(mipmaps ? VK_LOD_CLAMP_NONE : 0.0f)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
            LongBuffer pSampler = stack.mallocLong(1);
            check(vkCreateSampler(device, samplerInfo, null, pSampler), "create sampler");
            return pSampler.get(0);
        }
    }

    public void destroySampler(long sampler) {
        if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, null);
    }

    private static void transitionImage(MemoryStack stack, VkCommandBuffer cmd, long image,
                                        int oldLayout, int newLayout, int srcAccess, int dstAccess,
                                        int srcStage, int dstStage) {
        transitionImage(stack, cmd, image, oldLayout, newLayout, srcAccess, dstAccess, srcStage, dstStage, 1);
    }

    private static void transitionImage(MemoryStack stack, VkCommandBuffer cmd, long image,
                                        int oldLayout, int newLayout, int srcAccess, int dstAccess,
                                        int srcStage, int dstStage, int mipLevels) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void transitionMipLevel(MemoryStack stack, VkCommandBuffer cmd, long image, int level,
                                           int oldLayout, int newLayout, int srcAccess, int dstAccess,
                                           int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(level).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void generateMipmaps(MemoryStack stack, VkCommandBuffer cmd, long image,
                                        int width, int height, int mipLevels) {
        int mipWidth = width;
        int mipHeight = height;
        for (int level = 1; level < mipLevels; level++) {
            transitionMipLevel(stack, cmd, image, level - 1,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcOffsets(0).set(0, 0, 0);
            blit.get(0).srcOffsets(1).set(mipWidth, mipHeight, 1);
            blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level - 1).baseArrayLayer(0).layerCount(1);

            int nextWidth = Math.max(1, mipWidth / 2);
            int nextHeight = Math.max(1, mipHeight / 2);
            blit.get(0).dstOffsets(0).set(0, 0, 0);
            blit.get(0).dstOffsets(1).set(nextWidth, nextHeight, 1);
            blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level).baseArrayLayer(0).layerCount(1);

            vkCmdBlitImage(cmd,
                    image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit, VK_FILTER_LINEAR);

            transitionMipLevel(stack, cmd, image, level - 1,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

            mipWidth = nextWidth;
            mipHeight = nextHeight;
        }
        transitionMipLevel(stack, cmd, image, mipLevels - 1,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    private boolean supportsLinearMipBlit(int format) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, properties);
            return (properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
        }
    }

    private static int mipLevels(int width, int height) {
        return 1 + (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2.0));
    }

    /** Records and synchronously submits a one-off command buffer (uploads). */
    public void withOneShotCommands(java.util.function.Consumer<VkCommandBuffer> recorder) {
        renderThread.check("withOneShotCommands");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo cmdAlloc = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, cmdAlloc, pCmd), "allocate one-shot command buffer");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, beginInfo), "begin one-shot command buffer");
            recorder.accept(cmd);
            check(vkEndCommandBuffer(cmd), "end one-shot command buffer");
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO).pCommandBuffers(pCmd);
            check(vkQueueSubmit(graphicsQueue, submit, VK_NULL_HANDLE), "submit one-shot command buffer");
            vkQueueWaitIdle(graphicsQueue);
            vkFreeCommandBuffers(device, commandPool, pCmd);
        }
    }

    // ---------------------------------------------------------------- setup

    private void createInstance() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Tofuxia"))
                    .applicationVersion(VK_MAKE_VERSION(0, 3, 0))
                    .pEngineName(stack.UTF8("Tofuxia Renderer"))
                    .engineVersion(VK_MAKE_VERSION(0, 3, 0))
                    .apiVersion(VK_API_VERSION_1_0);
            PointerBuffer required = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (required == null) {
                throw new IllegalStateException("GLFW did not report required Vulkan instance extensions.");
            }
            PointerBuffer extensions = stack.mallocPointer(required.remaining());
            extensions.put(required).flip();
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(appInfo)
                    .ppEnabledExtensionNames(extensions);
            PointerBuffer pInstance = stack.mallocPointer(1);
            check(vkCreateInstance(createInfo, null, pInstance), "create Vulkan instance");
            instance = new VkInstance(pInstance.get(0), createInfo);
        }
    }

    private void createSurface() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, pSurface), "create window surface");
            surface = pSurface.get(0);
        }
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            vkEnumeratePhysicalDevices(instance, count, null);
            if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical device found.");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devices);
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                int graphics = -1;
                int present = -1;
                IntBuffer familyCount = stack.ints(0);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, familyCount, null);
                VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(familyCount.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, familyCount, properties);
                IntBuffer presentSupport = stack.ints(VK_FALSE);
                for (int family = 0; family < properties.capacity(); family++) {
                    if ((properties.get(family).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) graphics = family;
                    vkGetPhysicalDeviceSurfaceSupportKHR(candidate, family, surface, presentSupport);
                    if (presentSupport.get(0) == VK_TRUE) present = family;
                }
                if (graphics >= 0 && present >= 0) {
                    physicalDevice = candidate;
                    graphicsFamily = graphics;
                    presentFamily = present;
                    return;
                }
            }
            throw new IllegalStateException("No Vulkan device with graphics and present queues found.");
        }
    }

    private void createLogicalDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int queueFamilyCount = graphicsFamily == presentFamily ? 1 : 2;
            VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(queueFamilyCount, stack);
            queueInfos.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsFamily).pQueuePriorities(stack.floats(1.0f));
            if (queueFamilyCount == 2) {
                queueInfos.get(1).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(presentFamily).pQueuePriorities(stack.floats(1.0f));
            }
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueInfos)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
            PointerBuffer pDevice = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, createInfo, null, pDevice), "create logical device");
            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);
            vkGetDeviceQueue(device, presentFamily, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);
        }
    }

    private void createCommandPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsFamily);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, createInfo, null, pPool), "create command pool");
            commandPool = pPool.get(0);
        }
    }

    private void createFrameCommandBuffers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(device, allocInfo, pBuffers), "allocate frame command buffers");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                frameCommandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device);
            }
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_SECONDARY);
            pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(device, allocInfo, pBuffers), "allocate overlay command buffers");
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                overlayCommandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), device);
            }
        }
    }

    private void createSwapchainResources() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, capabilities);
            IntBuffer count = stack.ints(0);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, formats);
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, null);
            IntBuffer presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, count, presentModes);

            VkSurfaceFormatKHR surfaceFormat = formats.get(0);
            for (int i = 0; i < formats.capacity(); i++) {
                if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_SRGB
                        && formats.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    surfaceFormat = formats.get(i);
                    break;
                }
            }
            List<Integer> supportedPresentModes = new ArrayList<>();
            for (int i = 0; i < presentModes.capacity(); i++) supportedPresentModes.add(presentModes.get(i));
            String requestedPresentMode = System.getProperty("tofuxia.presentMode", "mailbox-preferred");
            int presentMode = selectPresentMode(supportedPresentModes, requestedPresentMode);
            System.out.println("[vulkan] present modes requested=" + requestedPresentMode
                    + " supported=" + presentModeNames(supportedPresentModes)
                    + " selected=" + presentModeName(presentMode)
                    + " vsync=" + (presentMode == VK_PRESENT_MODE_FIFO_KHR
                    || presentMode == VK_PRESENT_MODE_FIFO_RELAXED_KHR));
            VkExtent2D extent;
            if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
                extent = capabilities.currentExtent();
            } else {
                IntBuffer width = stack.ints(0);
                IntBuffer height = stack.ints(0);
                glfwGetFramebufferSize(window, width, height);
                extent = VkExtent2D.malloc(stack);
                extent.width(Math.max(capabilities.minImageExtent().width(),
                        Math.min(capabilities.maxImageExtent().width(), width.get(0))));
                extent.height(Math.max(capabilities.minImageExtent().height(),
                        Math.min(capabilities.maxImageExtent().height(), height.get(0))));
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0 && imageCount > capabilities.maxImageCount()) {
                imageCount = capabilities.maxImageCount();
            }
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(surfaceFormat.format())
                    .imageColorSpace(surfaceFormat.colorSpace())
                    .imageExtent(extent)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .preTransform(capabilities.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true);
            if (graphicsFamily != presentFamily) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(graphicsFamily, presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }
            LongBuffer pSwapchain = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, createInfo, null, pSwapchain), "create swapchain");
            swapchain = pSwapchain.get(0);

            vkGetSwapchainImagesKHR(device, swapchain, count, null);
            LongBuffer images = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, count, images);
            int previousFormat = swapchainFormat;
            swapchainFormat = surfaceFormat.format();
            swapchainExtent.set(extent);

            for (int i = 0; i < images.capacity(); i++) {
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(images.get(i))
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(swapchainFormat);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
                LongBuffer pView = stack.mallocLong(1);
                check(vkCreateImageView(device, viewInfo, null, pView), "create swapchain image view");
                swapchainImageViews.add(pView.get(0));
            }

            createDepthResources();
            if (renderPass == VK_NULL_HANDLE) {
                createRenderPass();
            } else if (previousFormat != swapchainFormat) {
                throw new IllegalStateException("Swapchain format changed on recreation ("
                        + previousFormat + " -> " + swapchainFormat + "); pipeline cache would be invalid.");
            }
            for (long imageView : swapchainImageViews) {
                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(stack.longs(imageView, depthImageView, selectionMaskView))
                        .width(swapchainExtent.width())
                        .height(swapchainExtent.height())
                        .layers(1);
                LongBuffer pFramebuffer = stack.mallocLong(1);
                check(vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer), "create framebuffer");
                framebuffers.add(pFramebuffer.get(0));
                framebufferInfo.renderPass(overlayRenderPass)
                        .pAttachments(stack.longs(imageView, depthImageView));
                check(vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer), "create overlay framebuffer");
                overlayFramebuffers.add(pFramebuffer.get(0));
            }

            if (renderFinished.length != images.capacity()) {
                for (long semaphore : renderFinished) {
                    vkDestroySemaphore(device, semaphore, null);
                }
                renderFinished = new long[images.capacity()];
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                LongBuffer pSemaphore = stack.mallocLong(1);
                for (int i = 0; i < renderFinished.length; i++) {
                    check(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "create render-finished semaphore");
                    renderFinished[i] = pSemaphore.get(0);
                }
            }
        }
    }

    private void createDepthResources() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_D32_SFLOAT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageInfo.extent().width(swapchainExtent.width()).height(swapchainExtent.height()).depth(1);
            imageInfo.mipLevels(1).arrayLayers(1);
            LongBuffer pImage = stack.mallocLong(1);
            check(vkCreateImage(device, imageInfo, null, pImage), "create depth image");
            depthImage = pImage.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(device, depthImage, requirements);
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate depth image memory");
            depthImageMemory = pMemory.get(0);
            check(vkBindImageMemory(device, depthImage, depthImageMemory, 0), "bind depth image memory");
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(depthImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_D32_SFLOAT);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            check(vkCreateImageView(device, viewInfo, null, pView), "create depth image view");
            depthImageView = pView.get(0);

            VkImageCreateInfo maskInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO).imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8_UNORM).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .samples(VK_SAMPLE_COUNT_1_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            maskInfo.extent().width(swapchainExtent.width()).height(swapchainExtent.height()).depth(1);
            maskInfo.mipLevels(1).arrayLayers(1);
            check(vkCreateImage(device, maskInfo, null, pImage), "create selection mask image");
            selectionMaskImage = pImage.get(0);
            vkGetImageMemoryRequirements(device, selectionMaskImage, requirements);
            allocInfo.allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(device, allocInfo, null, pMemory), "allocate selection mask memory");
            selectionMaskMemory = pMemory.get(0);
            check(vkBindImageMemory(device, selectionMaskImage, selectionMaskMemory, 0), "bind selection mask memory");
            viewInfo.image(selectionMaskImage).format(VK_FORMAT_R8_UNORM);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            check(vkCreateImageView(device, viewInfo, null, pView), "create selection mask view");
            selectionMaskView = pView.get(0);
        }
    }

    private void createRenderPass() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(3, stack);
            attachments.get(0).format(swapchainFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachments.get(1).format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            attachments.get(2).format(VK_FORMAT_R8_UNORM).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(2, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            colorRef.get(1).attachment(2).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef)
                    .pDepthStencilAttachment(depthRef);
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(2, stack);
            dependency.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            dependency.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT);
            VkRenderPassCreateInfo createInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments).pSubpasses(subpass).pDependencies(dependency);
            LongBuffer pRenderPass = stack.mallocLong(1);
            check(vkCreateRenderPass(device, createInfo, null, pRenderPass), "create render pass");
            renderPass = pRenderPass.get(0);

            VkAttachmentDescription.Buffer overlayAttachments = VkAttachmentDescription.calloc(2, stack);
            overlayAttachments.get(0).format(swapchainFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            overlayAttachments.get(1).format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            VkAttachmentReference.Buffer overlayColor = VkAttachmentReference.calloc(1, stack)
                    .attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference overlayDepth = VkAttachmentReference.calloc(stack)
                    .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL);
            VkSubpassDescription.Buffer overlaySubpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                    .pColorAttachments(overlayColor).pDepthStencilAttachment(overlayDepth);
            createInfo.pAttachments(overlayAttachments).pSubpasses(overlaySubpass);
            check(vkCreateRenderPass(device, createInfo, null, pRenderPass), "create overlay render pass");
            overlayRenderPass = pRenderPass.get(0);
        }
    }

    private void createFrameSync() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                check(vkCreateSemaphore(device, semaphoreInfo, null, p), "create image-available semaphore");
                imageAvailable[i] = p.get(0);
                check(vkCreateFence(device, fenceInfo, null, p), "create frame fence");
                inFlightFences[i] = p.get(0);
            }
        }
    }

    private void recreateSwapchain() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.ints(0);
            IntBuffer height = stack.ints(0);
            glfwGetFramebufferSize(window, width, height);
            while (width.get(0) == 0 || height.get(0) == 0) {
                org.lwjgl.glfw.GLFW.glfwWaitEvents();
                glfwGetFramebufferSize(window, width, height);
            }
        }
        vkDeviceWaitIdle(device);
        destroySwapchainOnly();
        createSwapchainResources();
        System.out.println("[vulkan] swapchain recreated " + swapchainExtent.width() + "x" + swapchainExtent.height());
    }

    private static int selectPresentMode(List<Integer> supported, String requested) {
        String value = requested == null ? "" : requested.trim().toLowerCase();
        if (value.equals("immediate") && supported.contains(VK_PRESENT_MODE_IMMEDIATE_KHR)) {
            return VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        if (value.equals("mailbox") && supported.contains(VK_PRESENT_MODE_MAILBOX_KHR)) {
            return VK_PRESENT_MODE_MAILBOX_KHR;
        }
        if ((value.equals("fifo-relaxed") || value.equals("fifo_relaxed"))
                && supported.contains(VK_PRESENT_MODE_FIFO_RELAXED_KHR)) {
            return VK_PRESENT_MODE_FIFO_RELAXED_KHR;
        }
        if (value.equals("fifo") && supported.contains(VK_PRESENT_MODE_FIFO_KHR)) {
            return VK_PRESENT_MODE_FIFO_KHR;
        }
        if (supported.contains(VK_PRESENT_MODE_MAILBOX_KHR)) {
            return VK_PRESENT_MODE_MAILBOX_KHR;
        }
        if (supported.contains(VK_PRESENT_MODE_IMMEDIATE_KHR) && value.equals("uncapped")) {
            return VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private static String presentModeNames(List<Integer> modes) {
        ArrayList<String> names = new ArrayList<>(modes.size());
        for (int mode : modes) names.add(presentModeName(mode));
        return names.toString();
    }

    private static String presentModeName(int mode) {
        return switch (mode) {
            case VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE";
            case VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX";
            case VK_PRESENT_MODE_FIFO_KHR -> "FIFO";
            case VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED";
            default -> "UNKNOWN(" + mode + ")";
        };
    }

    private void destroySwapchainOnly() {
        for (long framebuffer : overlayFramebuffers) vkDestroyFramebuffer(device, framebuffer, null);
        overlayFramebuffers.clear();
        for (long framebuffer : framebuffers) vkDestroyFramebuffer(device, framebuffer, null);
        framebuffers.clear();
        for (long imageView : swapchainImageViews) vkDestroyImageView(device, imageView, null);
        swapchainImageViews.clear();
        if (depthImageView != VK_NULL_HANDLE) vkDestroyImageView(device, depthImageView, null);
        depthImageView = VK_NULL_HANDLE;
        if (depthImage != VK_NULL_HANDLE) vkDestroyImage(device, depthImage, null);
        depthImage = VK_NULL_HANDLE;
        if (depthImageMemory != VK_NULL_HANDLE) vkFreeMemory(device, depthImageMemory, null);
        depthImageMemory = VK_NULL_HANDLE;
        if (selectionMaskView != VK_NULL_HANDLE) vkDestroyImageView(device, selectionMaskView, null);
        selectionMaskView = VK_NULL_HANDLE;
        if (selectionMaskImage != VK_NULL_HANDLE) vkDestroyImage(device, selectionMaskImage, null);
        selectionMaskImage = VK_NULL_HANDLE;
        if (selectionMaskMemory != VK_NULL_HANDLE) vkFreeMemory(device, selectionMaskMemory, null);
        selectionMaskMemory = VK_NULL_HANDLE;
        if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(device, swapchain, null);
        swapchain = VK_NULL_HANDLE;
    }

    private int findMemoryType(int typeFilter, int properties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                if ((typeFilter & (1 << i)) != 0
                        && (memoryProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    return i;
                }
            }
            throw new IllegalStateException("No suitable Vulkan memory type found.");
        }
    }

    static void check(int result, String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan failed to " + action + " (VkResult " + result + ").");
        }
    }

    @Override
    public void close() {
        waitIdle();
        destroySwapchainOnly();
        if (renderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, renderPass, null);
        renderPass = VK_NULL_HANDLE;
        if (overlayRenderPass != VK_NULL_HANDLE) vkDestroyRenderPass(device, overlayRenderPass, null);
        overlayRenderPass = VK_NULL_HANDLE;
        for (long semaphore : renderFinished) vkDestroySemaphore(device, semaphore, null);
        renderFinished = new long[0];
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (imageAvailable[i] != VK_NULL_HANDLE) vkDestroySemaphore(device, imageAvailable[i], null);
            if (inFlightFences[i] != VK_NULL_HANDLE) vkDestroyFence(device, inFlightFences[i], null);
        }
        if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, null);
        if (device != null) vkDestroyDevice(device, null);
        if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, null);
        if (instance != null) vkDestroyInstance(instance, null);
    }
}
