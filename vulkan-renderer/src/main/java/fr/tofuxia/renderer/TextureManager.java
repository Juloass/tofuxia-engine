package fr.tofuxia.renderer;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and caches textures by normalized asset path. Missing or broken
 * files resolve to a loud magenta/black checkerboard (and a warning that the
 * debug overlay surfaces), so a bad path never crashes a scene. Samplers are
 * cached per {@link SamplerSettings}; the cache key includes the sampler so
 * one image may legitimately exist with multiple sampler variants.
 */
public final class TextureManager implements AutoCloseable {
    private final VulkanContext context;
    private final DescriptorManager descriptors;
    private final Path assetRoot;
    private final RendererResources resources;
    private final Map<String, Texture2D> textures = new LinkedHashMap<>();
    private final Map<MemoryTextureKey, MemoryTexture> memoryTextures = new LinkedHashMap<>();
    private final Map<SamplerKey, Long> samplers = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean mipmapsEnabled;
    private final Texture2D white;
    private final Texture2D missing;
    private int fallbackHits;

    public TextureManager(VulkanContext context, DescriptorManager descriptors, Path assetRoot) {
        this.context = context;
        this.descriptors = descriptors;
        this.assetRoot = assetRoot;
        this.resources = null;
        this.mipmapsEnabled = Boolean.parseBoolean(System.getProperty("tofuxia.mipmaps", "false"));
        this.white = createFromRgba("<white>", 1, 1, new byte[]{-1, -1, -1, -1}, SamplerSettings.LINEAR_REPEAT, false, false);
        this.missing = createFromRgba("<missing>", 8, 8, checkerboard(8, 8), SamplerSettings.PIXEL_ART, false, true);
        System.out.println("[textures] mipmaps " + (mipmapsEnabled ? "enabled" : "disabled")
                + " (-Dtofuxia.mipmaps=true|false)");
    }

    TextureManager(
            VulkanContext context,
            DescriptorManager descriptors,
            RendererResources resources
    ) {
        this.context = context;
        this.descriptors = descriptors;
        this.assetRoot = null;
        this.resources = java.util.Objects.requireNonNull(
                resources, "resources");
        this.mipmapsEnabled = Boolean.parseBoolean(
                System.getProperty("tofuxia.mipmaps", "false"));
        this.white = createFromRgba(
                "<white>", 1, 1, new byte[]{-1, -1, -1, -1},
                SamplerSettings.LINEAR_REPEAT, false, false);
        this.missing = createFromRgba(
                "<missing>", 8, 8, checkerboard(8, 8),
                SamplerSettings.PIXEL_ART, false, true);
        System.out.println("[textures] mipmaps "
                + (mipmapsEnabled ? "enabled" : "disabled")
                + " (-Dtofuxia.mipmaps=true|false)");
    }

    /** 1x1 white texture bound for untextured draws (set 1 is always bound). */
    public Texture2D white() {
        return white;
    }

    /**
     * Loads a texture by asset path, e.g. "textures/terrain/grass.png".
     * Paths starting with "mem://" refer to textures registered via
     * {@link #fromEncodedBytes}/{@link #fromRgba} (glTF embedded images) and
     * are never read from disk.
     */
    public Texture2D load(String assetPath, SamplerSettings sampler, boolean srgb) {
        String key = key(assetPath, sampler);
        Texture2D cached = textures.get(key);
        if (cached != null) {
            if (cached.fallback()) fallbackHits++;
            return cached;
        }
        if (assetPath.startsWith("mem://")) {
            MemoryTexture source = memoryTextures.get(new MemoryTextureKey(assetPath, sampler, srgb));
            if (source != null) {
                Texture2D texture = createFromRgba(assetPath, source.width(), source.height(), source.rgba(), sampler, srgb, false);
                textures.put(key, texture);
                return texture;
            }
            warn("in-memory texture never registered: " + assetPath + " -> checkerboard");
            fallbackHits++;
            textures.put(key, missing);
            return missing;
        }
        Texture2D texture = loadFromDisk(normalize(assetPath), sampler, srgb);
        textures.put(key, texture);
        return texture;
    }

    /** Registers raw RGBA pixels under a "mem://" name (glTF embedded images). */
    public Texture2D fromRgba(String memName, int width, int height, byte[] rgba, SamplerSettings sampler, boolean srgb) {
        memoryTextures.put(new MemoryTextureKey(memName, sampler, srgb), new MemoryTexture(width, height, rgba.clone()));
        String key = key(memName, sampler);
        Texture2D cached = textures.get(key);
        if (cached != null) return cached;
        Texture2D texture = createFromRgba(memName, width, height, rgba, sampler, srgb, false);
        textures.put(key, texture);
        return texture;
    }

    public boolean updateRgba(String memName, int width, int height, byte[] rgba, SamplerSettings sampler) {
        Texture2D cached = textures.get(key(memName, sampler));
        if (cached == null || cached.width() != width || cached.height() != height || cached.fallback()) {
            return false;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(rgba.length);
        pixels.put(rgba).flip();
        context.updateTextureImage(cached.image(), pixels);
        return true;
    }

    /** Decodes an encoded image (PNG/JPEG bytes) from memory (GLB buffer views). */
    public Texture2D fromEncodedBytes(String memName, byte[] encoded, SamplerSettings sampler, boolean srgb) {
        String name = memName;
        String key = key(memName, sampler);
        Texture2D cached = textures.get(key);
        if (cached != null) return cached;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null) throw new IOException("unsupported image format");
            byte[] rgba = toRgbaBytes(image);
            memoryTextures.put(new MemoryTextureKey(memName, sampler, srgb),
                    new MemoryTexture(image.getWidth(), image.getHeight(), rgba.clone()));
            Texture2D texture = createFromRgba(name, image.getWidth(), image.getHeight(),
                    rgba, sampler, srgb, false);
            textures.put(key, texture);
            return texture;
        } catch (IOException e) {
            warn("embedded image '" + name + "' failed to decode (" + e.getMessage() + ") -> checkerboard");
            fallbackHits++;
            textures.put(key, missing);
            return missing;
        }
    }

    public int count() {
        return textures.size();
    }

    public int fallbackHits() {
        return fallbackHits;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public boolean mipmapsEnabled() {
        return mipmapsEnabled;
    }

    public boolean toggleMipmaps() {
        mipmapsEnabled = !mipmapsEnabled;
        System.out.println("[textures] mipmaps " + (mipmapsEnabled ? "enabled" : "disabled"));
        return mipmapsEnabled;
    }

    private Texture2D loadFromDisk(
            String assetPath,
            SamplerSettings sampler,
            boolean srgb
    ) {
        try {
            BufferedImage image;
            if (resources != null) {
                if (!resources.contains(assetPath)) {
                    warn("texture missing: " + assetPath
                            + " -> checkerboard");
                    fallbackHits++;
                    return missing;
                }
                image = ImageIO.read(new ByteArrayInputStream(
                        resources.readBytes(assetPath)));
            } else {
                Path file = assetRoot.resolve(assetPath);
                if (!Files.exists(file)) {
                    warn("texture missing: " + assetPath
                            + " -> checkerboard");
                    fallbackHits++;
                    return missing;
                }
                image = ImageIO.read(file.toFile());
            }
            if (image == null) throw new IOException("unsupported image format");
            Texture2D texture = createFromRgba(
                    assetPath,
                    image.getWidth(),
                    image.getHeight(),
                    toRgbaBytes(image),
                    sampler,
                    srgb,
                    false);
            System.out.println("[textures] loaded " + assetPath + " "
                    + image.getWidth() + "x" + image.getHeight()
                    + " " + sampler.filter() + "/" + sampler.wrap());
            return texture;
        } catch (IOException e) {
            warn("texture broken: " + assetPath + " ("
                    + e.getMessage() + ") -> checkerboard");
            fallbackHits++;
            return missing;
        }
    }
    private Texture2D createFromRgba(String name, int width, int height, byte[] rgba,
                                     SamplerSettings samplerSettings, boolean srgb, boolean fallback) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(rgba.length);
        pixels.put(rgba).flip();
        boolean requestMipmaps = mipmapsEnabled && !fallback && Math.max(width, height) > 1;
        VulkanContext.GpuImage image = context.createTextureImage(width, height, pixels, srgb, requestMipmaps);
        boolean actualMipmaps = image.mipLevels() > 1;
        long sampler = samplers.computeIfAbsent(new SamplerKey(samplerSettings, actualMipmaps),
                key -> context.createSampler(key.settings(), key.mipmaps()));
        long set = descriptors.allocateTextureSet();
        descriptors.writeCombinedImageSampler(set, image.view(), sampler);
        return new Texture2D(name, image, set, fallback);
    }

    private static byte[] toRgbaBytes(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            rgba[i * 4] = (byte) ((pixel >> 16) & 0xFF);
            rgba[i * 4 + 1] = (byte) ((pixel >> 8) & 0xFF);
            rgba[i * 4 + 2] = (byte) (pixel & 0xFF);
            rgba[i * 4 + 3] = (byte) ((pixel >> 24) & 0xFF);
        }
        return rgba;
    }

    private static byte[] checkerboard(int width, int height) {
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean magenta = ((x + y) & 1) == 0;
                int i = (y * width + x) * 4;
                rgba[i] = (byte) (magenta ? 0xFF : 0x00);
                rgba[i + 1] = 0;
                rgba[i + 2] = (byte) (magenta ? 0xFF : 0x00);
                rgba[i + 3] = (byte) 0xFF;
            }
        }
        return rgba;
    }

    private void warn(String message) {
        warnings.add(message);
        System.out.println("[textures] WARN " + message);
    }

    private static String normalize(String assetPath) {
        return assetPath.replace('\\', '/');
    }

    private String key(String path, SamplerSettings sampler) {
        return normalize(path) + "|" + sampler.filter() + "|" + sampler.wrap()
                + "|mips=" + mipmapsEnabled;
    }

    @Override
    public void close() {
        java.util.Set<VulkanContext.GpuImage> destroyed = new java.util.HashSet<>();
        for (Texture2D texture : textures.values()) {
            if (destroyed.add(texture.image())) {
                context.destroyImage(texture.image());
            }
        }
        textures.clear();
        context.destroyImage(white.image());
        if (destroyed.add(missing.image())) {
            context.destroyImage(missing.image());
        }
        for (long sampler : samplers.values()) {
            context.destroySampler(sampler);
        }
        samplers.clear();
    }

    private record SamplerKey(SamplerSettings settings, boolean mipmaps) {}
    private record MemoryTextureKey(String name, SamplerSettings sampler, boolean srgb) {}
    private record MemoryTexture(int width, int height, byte[] rgba) {}
}
