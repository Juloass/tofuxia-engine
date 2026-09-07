package fr.tofuxia.renderer;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

/**
 * GPU-side joint matrix palette for one skinned instance (descriptor set 2).
 * Double-buffered per frame-in-flight so a palette can be rewritten while the
 * previous frame still reads it. Create through
 * {@link Renderer#createJointPalette()}; the renderer owns cleanup.
 */
public final class JointPalette {
    static final long BYTES = (long) ShaderLibrary.MAX_JOINTS * 16 * Float.BYTES;

    private final VulkanContext context;
    private final VulkanContext.GpuBuffer[] buffers = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final long[] descriptorSets = new long[VulkanContext.FRAMES_IN_FLIGHT];
    private final ByteBuffer scratch = BufferUtils.createByteBuffer((int) BYTES);
    private int jointCount;

    JointPalette(VulkanContext context, DescriptorManager descriptors) {
        this.context = context;
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = context.createBuffer(BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            descriptorSets[i] = descriptors.allocateJointsSet();
            descriptors.writeUniformBuffer(descriptorSets[i], buffers[i], BYTES);
        }
        setIdentity();
    }

    /** Uploads joint matrices for the current frame slot. */
    public void update(int frameIndex, Matrix4f[] jointMatrices) {
        if (jointMatrices.length > ShaderLibrary.MAX_JOINTS) {
            throw new IllegalArgumentException("Joint palette overflow: " + jointMatrices.length
                    + " joints, shader limit is " + ShaderLibrary.MAX_JOINTS);
        }
        jointCount = jointMatrices.length;
        scratch.clear();
        for (Matrix4f matrix : jointMatrices) {
            matrix.get(scratch.position(), scratch);
            scratch.position(scratch.position() + 16 * Float.BYTES);
        }
        scratch.flip();
        context.uploadBytes(buffers[frameIndex], scratch, 0);
    }

    /** Fills every frame slot with identity matrices (bind-pose / fallback). */
    public void setIdentity() {
        Matrix4f identity = new Matrix4f();
        scratch.clear();
        for (int i = 0; i < ShaderLibrary.MAX_JOINTS; i++) {
            identity.get(i * 16 * Float.BYTES, scratch);
        }
        scratch.position(0).limit((int) BYTES);
        for (VulkanContext.GpuBuffer buffer : buffers) {
            context.uploadBytes(buffer, scratch.duplicate(), 0);
        }
        jointCount = 0;
    }

    public int jointCount() {
        return jointCount;
    }

    long descriptorSet(int frameIndex) {
        return descriptorSets[frameIndex];
    }

    void destroy() {
        for (VulkanContext.GpuBuffer buffer : buffers) {
            context.destroyBuffer(buffer);
        }
    }
}
