package fr.tofuxia.renderer;

import static org.lwjgl.vulkan.VK10.*;

/** Triangle face culling for a material. */
public enum CullMode {
    BACK(VK_CULL_MODE_BACK_BIT),
    FRONT(VK_CULL_MODE_FRONT_BIT),
    NONE(VK_CULL_MODE_NONE);

    public final int vkCullMode;

    CullMode(int vkCullMode) {
        this.vkCullMode = vkCullMode;
    }

    public static CullMode parse(String value, CullMode fallback) {
        if (value == null) return fallback;
        return switch (value.toLowerCase()) {
            case "back" -> BACK;
            case "front" -> FRONT;
            case "none", "off", "double_sided" -> NONE;
            default -> fallback;
        };
    }
}
