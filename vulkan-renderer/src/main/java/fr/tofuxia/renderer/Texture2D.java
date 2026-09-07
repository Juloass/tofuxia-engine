package fr.tofuxia.renderer;

/**
 * A loaded texture plus its ready-to-bind descriptor set (set 1). Owned by
 * {@link TextureManager}.
 */
public final class Texture2D {
    private final String path;
    private final VulkanContext.GpuImage image;
    private final long descriptorSet;
    private final boolean fallback;

    Texture2D(String path, VulkanContext.GpuImage image, long descriptorSet, boolean fallback) {
        this.path = path;
        this.image = image;
        this.descriptorSet = descriptorSet;
        this.fallback = fallback;
    }

    public String path() {
        return path;
    }

    public int width() {
        return image.width();
    }

    public int height() {
        return image.height();
    }

    /** True when this is the missing-texture checkerboard standing in for a broken path. */
    public boolean fallback() {
        return fallback;
    }

    long descriptorSet() {
        return descriptorSet;
    }

    VulkanContext.GpuImage image() {
        return image;
    }
}
