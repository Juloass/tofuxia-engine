package fr.tofuxia.renderer;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.render.ParticleTextureAtlas;
import io.github.juloass.resource.pack.ResourceSnapshot;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.vkCmdExecuteCommands;

/**
 * Dependency-light startup renderer: swapchain, one UI pipeline, configured UI font,
 * and solid rectangles. It never constructs world materials, textures, shadows,
 * block atlases, model registries, or game shader variants.
 */
public final class BootstrapRenderer implements AutoCloseable {
    private final VulkanContext context;
    private final UiPassRenderer ui;
    private final RendererStats stats = new RendererStats();
    private boolean promoted;

    public BootstrapRenderer(long window, FontAtlas font) {
        this(window, font, font);
    }

    public BootstrapRenderer(long window, FontAtlas uiFont, FontAtlas gameTitleFont) {
        context = new VulkanContext(window);
        ui = new UiPassRenderer(context, uiFont, java.util.List.of(gameTitleFont), null);
    }

    public void render(UiRenderData data) {
        VkCommandBuffer primary = context.beginFrame(new float[]{.018f,.024f,.032f,1});
        if (primary == null) return;
        stats.beginFrame();
        ui.upload(data);
        context.beginMainRenderPass(primary, new float[]{.018f,.024f,.032f,1});
        VkCommandBuffer secondary = context.beginOverlaySecondary();
        ui.draw(secondary, stats);
        context.endOverlaySecondary(secondary);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer commands = stack.mallocPointer(1).put(0, secondary.address());
            vkCmdExecuteCommands(primary, commands);
        }
        context.endRenderPass(primary);
        context.endFrame();
    }

    public int width() { return context.width(); }
    public int height() { return context.height(); }

    /**
     * Builds the full renderer on the existing Vulkan context while this UI
     * remains valid. If construction fails, the bootstrap screen can still
     * render the error.
     */
    public Renderer promote(java.nio.file.Path assetRoot, FontAtlas uiFont, FontAtlas debugFont,
                            ParticleTextureAtlas particles) {
        return promote(assetRoot, uiFont, debugFont, uiFont, particles);
    }

    public Renderer promote(java.nio.file.Path assetRoot, FontAtlas uiFont, FontAtlas debugFont,
                            FontAtlas gameTitleFont, ParticleTextureAtlas particles) {
        return promote(assetRoot,uiFont,debugFont,gameTitleFont,particles,io.github.juloass.content.WorkProgress.none());
    }

    public Renderer promote(java.nio.file.Path assetRoot,FontAtlas uiFont,FontAtlas debugFont,
                            FontAtlas gameTitleFont,ParticleTextureAtlas particles,
                            io.github.juloass.content.WorkProgress progress){
        Renderer renderer = new Renderer(context, assetRoot, uiFont, debugFont, gameTitleFont, particles,progress);
        ui.close();
        promoted = true;
        return renderer;
    }

    public Renderer promote(
            ResourceSnapshot assets,
            FontAtlas uiFont,
            FontAtlas debugFont,
            FontAtlas gameTitleFont,
            ParticleTextureAtlas particles,
            io.github.juloass.content.WorkProgress progress
    ) {
        Renderer renderer = new Renderer(
                context,
                assets,
                uiFont,
                debugFont,
                gameTitleFont,
                particles,
                progress);
        ui.close();
        promoted = true;
        return renderer;
    }

    @Override public void close() {
        if (promoted) return;
        context.waitIdle();
        ui.close();
        context.close();
    }
}
