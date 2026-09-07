package fr.tofuxia.renderer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Canonical packed GPU vertex formats.
 *
 * <pre>
 * STATIC  32 bytes: pos f32x3, normal snorm8x4, uv f16x2, color unorm8x4
 * VOXEL   32 bytes: pos f32x3, normal snorm8x4, localUv f32x2, color unorm8x4, foliage f16, sprite u16
 * SKINNED 40 bytes: pos f32x3, normal snorm8x4, uv f16x2, color unorm8x4, joints u16x4, weights unorm8x4
 * </pre>
 */
public enum VertexLayout {
    STATIC(32, 12),
    VOXEL(32, 14),
    SKINNED(40, 20);

    public final int debugFloatComponents;
    private final int strideBytes;

    VertexLayout(int strideBytes, int debugFloatComponents) {
        this.strideBytes = strideBytes;
        this.debugFloatComponents = debugFloatComponents;
    }

    public int strideBytes() {
        return strideBytes;
    }

    public VkVertexInputBindingDescription.Buffer bindingDescription(MemoryStack stack) {
        return VkVertexInputBindingDescription.calloc(1, stack)
                .binding(0)
                .stride(strideBytes)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
    }

    public VkVertexInputAttributeDescription.Buffer attributeDescriptions(MemoryStack stack) {
        int count = this == SKINNED ? 6 : this == VOXEL ? 6 : 4;
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(count, stack);
        attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attributes.get(1).binding(0).location(1).format(VK_FORMAT_R8G8B8A8_SNORM).offset(12);
        attributes.get(2).binding(0).location(2)
                .format(this == VOXEL ? VK_FORMAT_R32G32_SFLOAT : VK_FORMAT_R16G16_SFLOAT)
                .offset(16);
        attributes.get(3).binding(0).location(3).format(VK_FORMAT_R8G8B8A8_UNORM).offset(this == VOXEL ? 24 : 20);
        if (this == VOXEL) {
            attributes.get(4).binding(0).location(4).format(VK_FORMAT_R16_SFLOAT).offset(28);
            attributes.get(5).binding(0).location(5).format(VK_FORMAT_R16_UINT).offset(30);
        }
        if (this == SKINNED) {
            attributes.get(4).binding(0).location(4).format(VK_FORMAT_R16G16B16A16_UINT).offset(24);
            attributes.get(5).binding(0).location(5).format(VK_FORMAT_R8G8B8A8_UNORM).offset(32);
        }
        return attributes;
    }
}
