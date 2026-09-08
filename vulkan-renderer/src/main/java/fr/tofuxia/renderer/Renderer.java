package fr.tofuxia.renderer;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.MeshData;
import fr.tofuxia.render.ParticleMeshData;
import fr.tofuxia.render.ParticleTextureAtlas;
import fr.tofuxia.render.RendererStatus;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.render.Camera;
import fr.tofuxia.renderapi.GameRenderer;
import fr.tofuxia.renderapi.RenderViewport;
import fr.tofuxia.renderapi.SelectionOutlineData;
import fr.tofuxia.renderapi.TerrainOcclusionCutaway;
import fr.tofuxia.terrain.TerrainData;
import io.github.juloass.content.WorkProgress;
import io.github.juloass.resource.pack.ResourceSnapshot;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The central renderer. Owns the frame lifecycle and every rendering
 * subsystem (shader variants, pipeline cache, materials, textures, meshes,
 * render queue, debug overlay). Gameplay code interacts through:
 *
 * <pre>
 * renderer.beginFrame(view, proj, eye, environment, time);
 * renderer.submit(mesh, transform, material);          // or submitSkinned(...)
 * renderer.endFrame();
 * </pre>
 *
 * No caller ever picks a shader or binds a texture; the material decides,
 * the renderer resolves.
 */
public final class Renderer implements GameRenderer {
    private static final int MAX_ATLAS_FRAMES = 1024;
    private static final int MAX_ANIMATED_SPRITES = 1024;
    public static final int LIGHT_TILE_SIZE = ClusteredLighting.LIGHT_TILE_SIZE;
    public static final int LIGHT_CLUSTER_Z_SLICES = ClusteredLighting.LIGHT_CLUSTER_Z_SLICES;
    public static final int MAX_GPU_POINT_LIGHTS = ClusteredLighting.MAX_GPU_POINT_LIGHTS;
    public static final int MAX_LIGHTS_PER_CLUSTER = ClusteredLighting.MAX_LIGHTS_PER_CLUSTER;
    public static final int DIRECT_POINT_LIGHT_THRESHOLD = ClusteredLighting.DIRECT_LIGHT_THRESHOLD;
    private static final int CLUSTER_PARAMS_OFFSET = 432;
    private static final int CLUSTER_DEPTH_PARAMS_OFFSET = 448;
    private static final int CLUSTER_VIEWPORT_OFFSET = 464;
    private static final int RENDER_FRAME_OFFSET = 480;
    private static final int WIND_PARAMS_OFFSET = RENDER_FRAME_OFFSET + 16;
    private static final int OCCLUSION_PLAYER_OFFSET = WIND_PARAMS_OFFSET + 16;
    private static final int OCCLUSION_PARAMS_OFFSET = OCCLUSION_PLAYER_OFFSET + 16;
    private static final int OCCLUSION_PROJECTION_OFFSET = OCCLUSION_PARAMS_OFFSET + 16;
    private static final int OCCLUSION_RAY_OFFSET = OCCLUSION_PROJECTION_OFFSET + 16;
    private static final int ATLAS_FRAME_RECTS_OFFSET = OCCLUSION_RAY_OFFSET + 16;
    private static final int ANIMATED_SPRITE_META_OFFSET = ATLAS_FRAME_RECTS_OFFSET + MAX_ATLAS_FRAMES * 16;
    private static final long GLOBAL_UBO_BYTES = ANIMATED_SPRITE_META_OFFSET + MAX_ANIMATED_SPRITES * 16L;

    private final RenderThreadGuard renderThread = new RenderThreadGuard();
    private final VulkanContext context;
    private final DescriptorManager descriptors;
    private final ShaderLibrary shaderLibrary;
    private final PipelineCache pipelineCache;
    private final MaterialSystem materials;
    private final TextureManager textures;
    private final MeshManager meshes;
    private final ClusteredLighting clusteredLighting;
    private final UiPassRenderer uiPass;
    private final ShadowMap shadowMap;
    private final SelectionOutlineRenderer selectionOutline;
    private final ScreenSpaceOutlineRenderer screenSpaceOutline;
    private final RenderQueue queue = new RenderQueue();
    private final RendererStats stats = new RendererStats();
    private final DebugTextRenderer debugText;
    private final JointPalette identityPalette;
    private final SecondaryCommandWorkers commandWorkers;
    private final RenderPreparationWorker preparationWorker = new RenderPreparationWorker();
    private final List<JointPalette> palettes = new ArrayList<>();

    private final VulkanContext.GpuBuffer[] globalUbos = new VulkanContext.GpuBuffer[VulkanContext.FRAMES_IN_FLIGHT];
    private final long[] globalSets = new long[VulkanContext.FRAMES_IN_FLIGHT];
    private final ByteBuffer globalScratch = BufferUtils.createByteBuffer((int) GLOBAL_UBO_BYTES);
    private final ByteBuffer pushScratch = BufferUtils.createByteBuffer(DescriptorManager.PUSH_CONSTANT_BYTES);
    private final float[] atlasFrameRects = new float[MAX_ATLAS_FRAMES * 4];
    private final float[] animatedSpriteMeta = new float[MAX_ANIMATED_SPRITES * 4];

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f depthScratch = new Vector4f();
    private final Vector4f occlusionClipScratch = new Vector4f();
    private final Vector4f occlusionViewScratch = new Vector4f();
    private final Vector3f occlusionRayScratch = new Vector3f();
    private Environment environment = Environment.warmDay();
    private boolean showStats;
    private fr.tofuxia.renderapi.DebugViewMode debugViewMode = fr.tofuxia.renderapi.DebugViewMode.LIT;
    private boolean frameActive;
    private RenderViewport frameViewport;
    private VkCommandBuffer frameCommands;
    private long frameStartNanos;
    private double frameRetireMeshesMs;
    private double frameUpdateGlobalUniformsMs;
    private double frameBeginStateMs;
    private int lastCulledDraws;
    private int frameGameTick;
    private float frameTickAlpha;
    private TerrainOcclusionCutaway terrainOcclusionCutaway = TerrainOcclusionCutaway.DISABLED;

    public Renderer(long window, Path assetRoot, FontAtlas fontAtlas) {
        this(window, assetRoot, fontAtlas, ParticleTextureAtlas.white());
    }

    public Renderer(long window, Path assetRoot, FontAtlas fontAtlas, ParticleTextureAtlas particleAtlas) {
        this(new VulkanContext(window), assetRoot, fontAtlas, fontAtlas, fontAtlas, particleAtlas);
    }

    public Renderer(long window, Path assetRoot, FontAtlas uiFont, FontAtlas debugFont,
                    ParticleTextureAtlas particleAtlas) {
        this(new VulkanContext(window), assetRoot, uiFont, debugFont, uiFont, particleAtlas);
    }

    public Renderer(long window, Path assetRoot, FontAtlas uiFont, FontAtlas debugFont,
                    FontAtlas gameTitleFont, ParticleTextureAtlas particleAtlas) {
        this(new VulkanContext(window), assetRoot, uiFont, debugFont, gameTitleFont, particleAtlas);
    }

    Renderer(VulkanContext context, Path assetRoot, FontAtlas uiFont, FontAtlas debugFont,
             FontAtlas gameTitleFont, ParticleTextureAtlas particleAtlas) {
        this(context,assetRoot,uiFont,debugFont,gameTitleFont,particleAtlas,WorkProgress.none());
    }

    Renderer(VulkanContext context, Path assetRoot, FontAtlas uiFont,
             FontAtlas debugFont, FontAtlas gameTitleFont,
             ParticleTextureAtlas particleAtlas, WorkProgress progress) {
        this(context, RendererInputs.directory(assetRoot), uiFont, debugFont,
                gameTitleFont, particleAtlas, progress);
    }

    Renderer(VulkanContext context, ResourceSnapshot assets, FontAtlas uiFont,
             FontAtlas debugFont, FontAtlas gameTitleFont,
             ParticleTextureAtlas particleAtlas, WorkProgress progress) {
        this(context, RendererInputs.resources(assets), uiFont, debugFont,
                gameTitleFont, particleAtlas, progress);
    }

    private Renderer(VulkanContext context, RendererInputs inputs,
                     FontAtlas uiFont, FontAtlas debugFont,
                     FontAtlas gameTitleFont,
                     ParticleTextureAtlas particleAtlas,
                     WorkProgress progress) {
        final int totalSteps=16;
        int completed=0;
        this.context = context;
        progress.report(completed,totalSteps,"components","Creating descriptor manager");
        this.descriptors = new DescriptorManager(context);
        progress.report(++completed,totalSteps,"components","Loading shader library");
        ShaderSources shaderSources = inputs.shaders();
        this.shaderLibrary = new ShaderLibrary(shaderSources);
        progress.report(++completed,totalSteps,"components","Creating graphics pipeline cache");
        this.pipelineCache = new PipelineCache(context, descriptors, shaderLibrary);
        progress.report(++completed,totalSteps,"components","Creating shadow renderer");
        this.shadowMap = new ShadowMap(context, descriptors, shaderSources,
                DirectionalShadowSettings.SHADOW_MAP_RESOLUTION);
        progress.report(++completed,totalSteps,"components","Creating selection outline renderer");
        this.selectionOutline = new SelectionOutlineRenderer(context, descriptors.pipelineLayout());
        progress.report(++completed,totalSteps,"components","Creating screen-space outline renderer");
        this.screenSpaceOutline = new ScreenSpaceOutlineRenderer(context);
        progress.report(++completed,totalSteps,"components","Loading material definitions");
        this.materials = inputs.resources() == null
                ? new MaterialSystem(inputs.assetRoot())
                : new MaterialSystem(inputs.resources());
        progress.report(++completed,totalSteps,"components","Creating texture manager");
        this.textures = inputs.resources() == null
                ? new TextureManager(
                        context, descriptors, inputs.assetRoot())
                : new TextureManager(
                        context, descriptors, inputs.resources());
        progress.report(++completed,totalSteps,"components","Creating mesh manager");
        this.meshes = new MeshManager(context);
        progress.report(++completed,totalSteps,"components","Creating clustered-lighting resources");
        this.clusteredLighting = new ClusteredLighting(context, descriptors, shaderSources);
        progress.report(++completed,totalSteps,"components","Creating UI renderer");
        this.uiPass = new UiPassRenderer(context, uiFont, List.of(gameTitleFont), textures);
        progress.report(++completed,totalSteps,"components","Creating debug-text renderer");
        this.debugText = new DebugTextRenderer(context, debugFont);
        progress.report(++completed,totalSteps,"components","Creating animation palette");
        this.identityPalette = new JointPalette(context, descriptors);
        int recordingWorkers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        progress.report(++completed,totalSteps,"components","Starting command recording workers");
        this.commandWorkers = new SecondaryCommandWorkers(context, descriptors.pipelineLayout(), recordingWorkers);
        progress.report(++completed,totalSteps,"components","Preparing application compatibility resources");
        this.app = new AppCompatibility(particleAtlas);
        progress.report(++completed,totalSteps,"components","Allocating frame uniform buffers");
        for (int i = 0; i < VulkanContext.FRAMES_IN_FLIGHT; i++) {
            globalUbos[i] = context.createBuffer(GLOBAL_UBO_BYTES, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            globalSets[i] = descriptors.allocateGlobalSet();
            descriptors.writeUniformBuffer(globalSets[i], globalUbos[i], GLOBAL_UBO_BYTES);
            descriptors.writeCombinedImageSampler(globalSets[i], 1, shadowMap.view(), shadowMap.sampler());
            clusteredLighting.writeDescriptors(globalSets[i], i);
        }
        progress.report(++completed,totalSteps,"components","Renderer ready");
        System.out.println("[renderer] frame pass order: " + java.util.Arrays.toString(PassId.values()));
    }

    private final AppCompatibility app;

    private record RendererInputs(
            Path assetRoot,
            RendererResources resources,
            ShaderSources shaders
    ) {
        static RendererInputs directory(Path assetRoot) {
            return new RendererInputs(
                    assetRoot,
                    null,
                    ShaderSources.directory(shaderRoot(assetRoot)));
        }

        static RendererInputs resources(ResourceSnapshot snapshot) {
            RendererResources resources = new RendererResources(
                    snapshot, "engine");
            return new RendererInputs(
                    null,
                    resources,
                    ShaderSources.resources(
                            snapshot, "engine", "shaders"));
        }

        private static Path shaderRoot(Path assetRoot) {
            Path namespaced = assetRoot.resolve("engine")
                    .resolve("shaders");
            return java.nio.file.Files.isDirectory(namespaced)
                    ? namespaced
                    : assetRoot.resolve("shaders");
        }
    }
    // ------------------------------------------------------------ subsystems

    public MaterialSystem materials() {
        return materials;
    }

    /** Compiles the main and shadow variants during a loading phase instead of the first visible frame. */
    public void prewarm(Material material, VertexLayout layout) {
        pipelineCache.pipeline(PipelineKey.of(material, layout));
        shadowMap.prewarm(layout, material);
    }

    public TextureManager textures() {
        return textures;
    }

    public void uploadSpriteAnimationTables(float[] frameRects, float[] spriteMeta) {
        renderThread.check("Renderer.uploadSpriteAnimationTables");
        java.util.Arrays.fill(atlasFrameRects, 0.0f);
        java.util.Arrays.fill(animatedSpriteMeta, 0.0f);
        int frameFloats = Math.min(atlasFrameRects.length, frameRects == null ? 0 : frameRects.length);
        if (frameFloats > 0) System.arraycopy(frameRects, 0, atlasFrameRects, 0, frameFloats);
        int metaFloats = Math.min(animatedSpriteMeta.length, spriteMeta == null ? 0 : spriteMeta.length);
        if (metaFloats > 0) System.arraycopy(spriteMeta, 0, animatedSpriteMeta, 0, metaFloats);
        for (int i = 0; i < MAX_ANIMATED_SPRITES; i++) {
            int offset = i * 4;
            if (animatedSpriteMeta[offset + 1] <= 0.0f) {
                animatedSpriteMeta[offset] = 0.0f;
                animatedSpriteMeta[offset + 1] = 1.0f;
                animatedSpriteMeta[offset + 2] = 1.0f;
                animatedSpriteMeta[offset + 3] = 0.0f;
            }
        }
    }

    public GpuMesh uploadMesh(CpuMesh mesh) {
        return meshes.upload(mesh);
    }

    public GpuMesh replaceMesh(GpuMesh previous, CpuMesh mesh) {
        GpuMesh replacement = meshes.upload(mesh);
        meshes.retire(previous);
        return replacement;
    }

    public void retireMesh(GpuMesh mesh) {
        meshes.retire(mesh);
    }

    public int meshBufferCreationsThisFrame() {
        return meshes.frameBufferCreations();
    }

    public JointPalette createJointPalette() {
        JointPalette palette = new JointPalette(context, descriptors);
        palettes.add(palette);
        return palette;
    }

    public RendererStats stats() {
        return stats;
    }

    public int frameIndex() {
        return context.frameIndex();
    }

    public int width() {
        return context.width();
    }

    public int height() {
        return context.height();
    }

    /** Human-readable shader variant a material resolves to (debug UI). */
    public String variantName(Material material, VertexLayout layout) {
        return PipelineKey.of(material, layout).variant().describe();
    }

    public void requestSwapchainRecreation() {
        context.requestSwapchainRecreation();
    }

    public RendererStatus status() {
        return new RendererStatus("terrain=%d shadow=%d water=%d scene=%d overlay=%d particles=%d ui=%d draws=%d culled=%d pipelines=%d textures=%d fallback=%d swapchain=%dx%d".formatted(
                app.terrain.indexCount, app.sceneShadow.indexCount + app.dynamicShadow.indexCount,
                app.water.indexCount, app.scene.indexCount, app.overlay.indexCount,
                app.particleAlpha.indexCount + app.particleBlend.indexCount + app.particleAdd.indexCount,
                uiPass.indexCount(), stats.drawCalls(), lastCulledDraws,
                pipelineCache.count(), textures.count(), textures.fallbackHits(),
                width(), height()) + " " + selectionOutline.diagnostics());
    }

    public void toggleStatsOverlay() {
        showStats = !showStats;
    }

    public void setStatsOverlayEnabled(boolean enabled) {
        showStats = enabled;
    }

    public boolean statsOverlayEnabled() {
        return showStats;
    }

    public void setDebugViewMode(int mode) {
        debugViewMode = fr.tofuxia.renderapi.DebugViewMode.fromInt(mode);
    }

    public void setDebugViewMode(fr.tofuxia.renderapi.DebugViewMode mode) { debugViewMode = mode == null ? fr.tofuxia.renderapi.DebugViewMode.LIT : mode; }

    /** Recompiles shaders and re-reads material files; pipelines rebuild lazily. */
    public void reloadShadersAndMaterials() {
        context.waitIdle();
        shaderLibrary.reload();
        pipelineCache.clear();
        materials.reload();
    }

    public void toggleMipmaps() {
        context.waitIdle();
        boolean enabled = textures.toggleMipmaps();
        System.out.println("[renderer] mipmaps " + (enabled ? "enabled" : "disabled"));
    }

    // ------------------------------------------------------------- frame API

    public void beginFrame(Matrix4f viewMatrix, Matrix4f projMatrix, Vector3f eye,
                           Environment environment, float timeSeconds) {
        beginFrame(viewMatrix, projMatrix, eye, environment, timeSeconds,
                RenderViewport.fullScreen(width(), height()));
    }

    public void beginFrame(Matrix4f viewMatrix, Matrix4f projMatrix, Vector3f eye,
                           Environment environment, float timeSeconds, RenderViewport viewport) {
        beginFrame(viewMatrix, projMatrix, eye, environment, timeSeconds, 0, 0.0f, viewport);
    }

    public void beginFrame(Matrix4f viewMatrix, Matrix4f projMatrix, Vector3f eye,
                           Environment environment, float timeSeconds, int gameTick, float tickAlpha,
                           RenderViewport viewport) {
        renderThread.check("Renderer.beginFrame");
        this.environment = environment;
        this.frameGameTick = gameTick;
        this.frameTickAlpha = Math.max(0.0f, Math.min(1.0f, tickAlpha));
        frameViewport = viewport == null ? RenderViewport.fullScreen(width(), height()) : viewport;
        frameStartNanos = System.nanoTime();
        long beginStateStart = frameStartNanos;
        frameCommands = context.beginFrame(environment.clearColorArray());
        frameActive = frameCommands != null;
        queue.clear();
        stats.beginFrame();
        if (!frameActive) return;
        long retireStart = System.nanoTime();
        meshes.beginFrame();
        long retireEnd = System.nanoTime();
        view.set(viewMatrix);
        long uniformsStart = System.nanoTime();
        updateGlobalUniforms(viewMatrix, projMatrix, eye, timeSeconds);
        long uniformsEnd = System.nanoTime();
        frameRetireMeshesMs = ms(retireStart, retireEnd);
        frameUpdateGlobalUniformsMs = ms(uniformsStart, uniformsEnd);
        frameBeginStateMs = ms(beginStateStart, uniformsEnd)
                - context.lastBeginTimings().recreateSwapchainMs()
                - context.lastBeginTimings().waitPreviousFrameFenceMs()
                - context.lastBeginTimings().acquireSwapchainImageMs()
                - context.lastBeginTimings().resetFrameFenceMs()
                - context.lastBeginTimings().resetCommandBufferMs()
                - context.lastBeginTimings().beginCommandBufferMs()
                - frameRetireMeshesMs
                - frameUpdateGlobalUniformsMs;
    }

    public void uploadTerrain(MeshData terrain, MeshData water, MeshData scene) {
        app.terrain.upload(convertMeshDataVertices("app-terrain", terrain));
        app.water.upload(convertMeshDataVertices("app-water", water));
        app.scene.upload(convertMeshDataVertices("app-scene", scene));
        app.sceneShadowSource = scene;
        app.sceneShadowDirty = true;
    }

    public void uploadTerrain(TerrainData terrainData, MeshData terrain, MeshData water, MeshData scene) {
        app.shadowReceiver = ShadowReceiver.fromTerrain(terrainData);
        app.dynamicShadowDirty = true;
        uploadTerrain(terrain, water, scene);
    }

    public void uploadOverlay(MeshData overlay) {
        app.overlay.upload(convertMeshDataVertices("app-overlay", overlay));
    }

    public void uploadDynamicShadowCasters(MeshData casters) {
        app.dynamicShadowSource = casters;
        app.dynamicShadow.upload(convertMeshDataVertices("app-dynamic-shadow", casters));
        app.dynamicShadowDirty = false;
    }

    public void uploadDiagnosticScene(TerrainData receiver, MeshData visibleScene, MeshData shadowCasters) {
        app.shadowReceiver = ShadowReceiver.fromTerrain(receiver);
        app.terrain.clear();
        app.water.clear();
        app.scene.upload(convertMeshDataVertices("app-diagnostic-scene", visibleScene, false));
        app.overlay.clear();
        app.sceneShadowSource = shadowCasters;
        app.sceneShadowDirty = true;
        app.dynamicShadowSource = shadowCasters;
        app.dynamicShadow.upload(convertMeshDataVertices("app-diagnostic-shadow", shadowCasters));
        app.dynamicShadowDirty = false;
    }

    public void uploadParticles(ParticleMeshData particles) {
        if (particles == null || particles.isEmpty()) {
            app.particleAlpha.clear();
            app.particleBlend.clear();
            app.particleAdd.clear();
            return;
        }
        app.particleAlpha.upload(convertParticleVertices("app-particle-alpha", particles.alphaVertices(), particles.alphaIndices()));
        app.particleBlend.upload(convertParticleVertices("app-particle-blend", particles.blendVertices(), particles.blendIndices()));
        app.particleAdd.upload(convertParticleVertices("app-particle-add", particles.addVertices(), particles.addIndices()));
    }

    public void uploadUi(UiRenderData ui) {
        uiPass.upload(ui);
    }

    @Override
    public void uploadSelectionOutline(SelectionOutlineData outline) {
        selectionOutline.upload(outline);
    }

    @Override
    public void uploadTerrainOcclusionCutaway(TerrainOcclusionCutaway cutaway) {
        terrainOcclusionCutaway = cutaway == null
                ? TerrainOcclusionCutaway.DISABLED
                : cutaway;
    }

    /** Draws an intentional loading frame without scene/world submissions. */
    public void renderLoadingUi(UiRenderData data) {
        Matrix4f identity = new Matrix4f();
        beginFrame(identity, identity, new Vector3f(), Environment.warmDay(), 0);
        uploadUi(data);
        endFrame();
    }

    public void draw(Camera camera) {
        float timeSeconds = (float) (System.nanoTime() / 1_000_000_000.0);
        draw(camera, Environment.timeOfDay((timeSeconds / 240.0f) % 1.0f), timeSeconds);
    }

    public void draw(Camera camera, Environment frameEnvironment) {
        float timeSeconds = (float) (System.nanoTime() / 1_000_000_000.0);
        draw(camera, frameEnvironment, timeSeconds);
    }

    private void draw(Camera camera, Environment frameEnvironment, float timeSeconds) {
        Vector3f eye = camera.position();
        Matrix4f viewMatrix = camera.view();
        Matrix4f projMatrix = camera.projection(width(), height());
        beginFrame(viewMatrix, projMatrix, eye, frameEnvironment, timeSeconds);
        endFrame();
    }

    public void submit(GpuMesh mesh, Matrix4f transform, Material material) {
        submitInternal(mesh, transform, material, null);
    }

    public void submitSkinned(GpuMesh mesh, Matrix4f transform, Material material, JointPalette palette) {
        if (mesh.layout() != VertexLayout.SKINNED) {
            throw new IllegalArgumentException("submitSkinned needs a SKINNED-layout mesh, got " + mesh.name());
        }
        submitInternal(mesh, transform, material, palette);
    }

    private void submitInternal(GpuMesh mesh, Matrix4f transform, Material material, JointPalette palette) {
        if (!frameActive) return;
        float depth = viewDepth(transform);
        queue.add(material.pass(), new RenderItem(mesh, transform, material, palette, depth));
    }

    public void endFrame() {
        renderThread.check("Renderer.endFrame");
        if (!frameActive) {
            return;
        }
        VkCommandBuffer cmd = frameCommands;
        DirectionalShadowSettings shadowSettings = environment.directionalShadows();
        long prepareQueueStart = System.nanoTime();
        java.util.concurrent.CompletableFuture<RenderPreparationWorker.Plan> planFuture =
                preparationWorker.prepare(queue.snapshot(), viewProjection);
        long prepareQueueEnd = System.nanoTime();
        long appDrawsStart = System.nanoTime();
        Map<PassId, List<SecondaryDraw>> appDraws = prepareAppDraws();
        long appDrawsEnd = System.nanoTime();
        long waitWorkerStart = System.nanoTime();
        RenderPreparationWorker.Plan plan = planFuture.join();
        long waitWorkerEnd = System.nanoTime();
        lastCulledDraws = plan.culled();
        long mainDrawsStart = System.nanoTime();
        List<SecondaryDraw> mainDraws = prepareMainDraws(plan, appDraws);
        long mainDrawsEnd = System.nanoTime();
        long shadowDrawsStart = System.nanoTime();
        List<SecondaryDraw> shadowDraws = shadowSettings.enabled() ? prepareShadowDraws(plan) : List.of();
        long shadowDrawsEnd = System.nanoTime();
        long clusteredStart = System.nanoTime();
        clusteredLighting.writeDescriptors(globalSets[context.frameIndex()], context.frameIndex(), frameViewport);
        boolean collectClusterStats = showStats
                || debugViewMode == fr.tofuxia.renderapi.DebugViewMode.CLUSTER_OCCUPANCY;
        clusteredLighting.prepareAndDispatch(cmd, globalSets[context.frameIndex()], context.frameIndex(), frameViewport,
                environment.pointLights(), collectClusterStats);
        long clusteredEnd = System.nanoTime();
        long recordStart = System.nanoTime();
        SecondaryCommandWorkers.FrameCommands recorded = commandWorkers.record(
                context.frameIndex(), globalSets[context.frameIndex()],
                mainDraws,
                new SecondaryCommandWorkers.MainPass(context.renderPassHandle(),
                        context.currentFramebufferHandle(), context.width(), context.height(), frameViewport),
                shadowDraws,
                new SecondaryCommandWorkers.ShadowPass(shadowMap.renderPass(), shadowMap.framebuffer(),
                        shadowMap.width(), shadowMap.height(), shadowSettings.constantBias(),
                        shadowSettings.slopeBias(), shadowSettings.enabled()));
        long recordEnd = System.nanoTime();
        long shadowPassStart = System.nanoTime();
        if (shadowSettings.enabled()) {
            shadowMap.begin(cmd, shadowSettings, globalSets[context.frameIndex()]);
            executeSecondaries(cmd, recorded.shadow());
            shadowMap.end(cmd);
        }
        long shadowPassEnd = System.nanoTime();
        long mainPassStart = System.nanoTime();
        context.beginMainRenderPass(cmd, environment.clearColorArray());
        long mainPassEnd = System.nanoTime();
        long executeMainStart = System.nanoTime();
        executeSecondaries(cmd, recorded.main());
        long executeMainEnd = System.nanoTime();
        long statsStart = System.nanoTime();
        for (PassId pass : PassId.values()) {
            if (!pass.acceptsSubmissions()) continue;
            List<RenderItem> items = plan.items(pass);
            stats.countPass(pass, items.size() + app.count(pass));
        }
        stats.countDraws(recorded.mainDraws() + recorded.shadowDraws());
        stats.countPipelineBinds(recorded.pipelineBinds());
        double cpuMillis = (System.nanoTime() - frameStartNanos) / 1_000_000.0;
        stats.finishFrame(cpuMillis, pipelineCache.count(), shaderLibrary.variantCount(),
                textures.count(), textures.fallbackHits(), meshes.count(), materials.count());
        uiPass.countStats(stats);
        long statsEnd = System.nanoTime();
        List<String> overlay = new ArrayList<>();
        if (showStats) {
            overlay.addAll(stats.overlayLines());
            overlay.add("clustered lights " + clusteredLighting.activeLights()
                    + " | clusters " + clusteredLighting.tilesX() + "x" + clusteredLighting.tilesY()
                    + "x" + LIGHT_CLUSTER_Z_SLICES
                    + " | max/overflow " + clusteredLighting.lastMaxLightsInCluster()
                    + "/" + clusteredLighting.lastOverflowCount()
                    + " | setup " + String.format(java.util.Locale.ROOT, "%.2f ms", clusteredLighting.frameSetupMs()));
            List<String> textureWarnings = textures.warnings();
            for (int i = Math.max(0, textureWarnings.size() - 3); i < textureWarnings.size(); i++) {
                overlay.add("WARN " + textureWarnings.get(i));
            }
        }
        long overlayStart = System.nanoTime();
        context.endRenderPass(cmd);
        context.beginOverlayRenderPass(cmd);
        VkCommandBuffer overlayCommands = context.beginOverlaySecondary();
        screenSpaceOutline.draw(overlayCommands, outlineDebugMode(debugViewMode), System.nanoTime()/1_000_000_000.0f, stats);
        if (debugViewMode == fr.tofuxia.renderapi.DebugViewMode.SHADOW_MAP_DEPTH) {
            shadowMap.drawPreview(overlayCommands, globalSets[context.frameIndex()]);
        }
        selectionOutline.draw(overlayCommands, globalSets[context.frameIndex()], frameViewport, stats);
        context.setFullViewport(overlayCommands, context.width(), context.height());
        uiPass.draw(overlayCommands, stats);
        if (!overlay.isEmpty()) {
            context.setFullViewport(overlayCommands, context.width(), context.height());
            debugText.draw(overlayCommands, context.frameIndex(), overlay, stats);
        }
        context.endOverlaySecondary(overlayCommands);
        long overlayEnd = System.nanoTime();
        long executeOverlayStart = System.nanoTime();
        executeSecondaries(cmd, List.of(overlayCommands));
        long executeOverlayEnd = System.nanoTime();
        long endRenderPassStart = System.nanoTime();
        context.endRenderPass(cmd);
        long endRenderPassEnd = System.nanoTime();
        VulkanContext.FrameSubmitTimings submitTimings = context.endFrame();
        stats.finishTimings(new RendererStats.RenderTimings(
                context.lastBeginTimings().recreateSwapchainMs(),
                context.lastBeginTimings().waitPreviousFrameFenceMs(),
                context.lastBeginTimings().acquireSwapchainImageMs(),
                context.lastBeginTimings().resetFrameFenceMs(),
                context.lastBeginTimings().resetCommandBufferMs(),
                context.lastBeginTimings().beginCommandBufferMs(),
                Math.max(0.0, frameBeginStateMs),
                frameRetireMeshesMs,
                frameUpdateGlobalUniformsMs,
                ms(prepareQueueStart, prepareQueueEnd),
                plan.workerMs(),
                ms(appDrawsStart, appDrawsEnd),
                ms(waitWorkerStart, waitWorkerEnd),
                ms(mainDrawsStart, mainDrawsEnd),
                ms(shadowDrawsStart, shadowDrawsEnd),
                ms(recordStart, recordEnd) + ms(clusteredStart, clusteredEnd),
                recorded.submitJobsMs(),
                recorded.waitWorkersMs(),
                recorded.workerCpuMs(),
                ms(shadowPassStart, shadowPassEnd),
                ms(mainPassStart, mainPassEnd),
                ms(executeMainStart, executeMainEnd),
                ms(statsStart, statsEnd),
                ms(overlayStart, overlayEnd),
                ms(executeOverlayStart, executeOverlayEnd),
                ms(endRenderPassStart, endRenderPassEnd),
                submitTimings.endCommandBufferMs(),
                submitTimings.queueSubmitMs(),
                submitTimings.presentMs(),
                submitTimings.frameIndexAdvanceMs(),
                recorded.mainDraws(),
                recorded.shadowDraws(),
                recorded.pipelineBinds()));
        frameActive = false;
    }

    private static int outlineDebugMode(fr.tofuxia.renderapi.DebugViewMode mode) {
        int selected = switch (mode) {
            case SELECTION_MASK -> 1;
            case SELECTION_DILATED_MASK -> 2;
            case SELECTION_EXTERIOR -> 3;
            case SELECTION_DEPTH_OCCLUSION -> 4;
            default -> 0;
        };
        if (selected != 0) return selected;
        return switch (System.getProperty("tofuxia.outlineDebug", "").trim().toLowerCase(java.util.Locale.ROOT)) {
            case "mask", "original" -> 1;
            case "dilated", "dilation" -> 2;
            case "outline", "exterior" -> 3;
            case "depth", "occlusion" -> 4;
            default -> 0;
        };
    }

    private static double ms(long start, long end) {
        return (end - start) / 1_000_000.0;
    }

    public void waitIdle() {
        context.waitIdle();
    }

    // ------------------------------------------------------------- internals

    private List<SecondaryDraw> prepareMainDraws(RenderPreparationWorker.Plan plan,
                                                  Map<PassId, List<SecondaryDraw>> appDraws) {
        ArrayList<SecondaryDraw> draws = new ArrayList<>();
        for (PassId pass : PassId.values()) {
            if (!pass.acceptsSubmissions()) continue;
            draws.addAll(appDraws.getOrDefault(pass, List.of()));
            for (RenderItem item : plan.items(pass)) draws.add(prepareDraw(item));
        }
        return List.copyOf(draws);
    }

    private Map<PassId, List<SecondaryDraw>> prepareAppDraws() {
        EnumMap<PassId, List<SecondaryDraw>> byPass = new EnumMap<>(PassId.class);
        for (PassId pass : PassId.values()) {
            if (!pass.acceptsSubmissions()) continue;
            ArrayList<SecondaryDraw> draws = new ArrayList<>();
            addPreparedBatch(draws, app.terrain, app.terrainMaterial, pass);
            addPreparedBatch(draws, app.scene, app.sceneMaterial, pass);
            addPreparedBatch(draws, app.water, app.waterMaterial, pass);
            addPreparedBatch(draws, app.overlay, app.overlayMaterial, pass);
            addPreparedBatch(draws, app.particleAlpha, app.particleAlphaMaterial, pass);
            addPreparedBatch(draws, app.particleBlend, app.particleBlendMaterial, pass);
            addPreparedBatch(draws, app.particleAdd, app.particleAddMaterial, pass);
            byPass.put(pass, List.copyOf(draws));
        }
        return Map.copyOf(byPass);
    }

    private List<SecondaryDraw> prepareShadowDraws(RenderPreparationWorker.Plan plan) {
        ArrayList<SecondaryDraw> draws = new ArrayList<>();
        for (PassId pass : List.of(PassId.OPAQUE, PassId.ALPHA_CUTOUT)) {
            for (RenderItem item : plan.items(pass)) {
                SecondaryDraw draw = shadowMap.prepare(item, resolveTexture(item.material),
                        identityPalette, context.frameIndex());
                if (draw != null) draws.add(draw);
            }
        }
        return List.copyOf(draws);
    }

    private SecondaryDraw prepareDraw(RenderItem item) {
        long pipeline = pipelineCache.pipeline(PipelineKey.of(item.material, item.mesh.layout()));
        Texture2D texture = resolveTexture(item.material);
        JointPalette palette = item.palette != null ? item.palette : identityPalette;
        writePushConstants(item);
        return new SecondaryDraw(pipeline, texture.descriptorSet(), palette.descriptorSet(context.frameIndex()),
                item.mesh.vertexBuffer().buffer(), item.mesh.indexBuffer().buffer(),
                item.mesh.vertexBufferOffset(), item.mesh.indexBufferOffset(), item.mesh.indexCount(),
                copyPushConstants());
    }

    private void addPreparedBatch(List<SecondaryDraw> draws, DynamicBatch batch, Material material, PassId pass) {
        if (batch.indexCount == 0 || material.pass() != pass) return;
        long pipeline = pipelineCache.pipeline(PipelineKey.of(material, VertexLayout.STATIC));
        Texture2D texture = resolveTexture(material);
        pushIdentity(material);
        draws.add(new SecondaryDraw(pipeline, texture.descriptorSet(),
                identityPalette.descriptorSet(context.frameIndex()),
                batch.vertexBuffer.buffer(), batch.indexBuffer.buffer(), 0, 0, batch.indexCount,
                copyPushConstants()));
    }

    private byte[] copyPushConstants() {
        byte[] bytes = new byte[DescriptorManager.PUSH_CONSTANT_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = pushScratch.get(i);
        return bytes;
    }

    private static void executeSecondaries(VkCommandBuffer primary, List<VkCommandBuffer> secondaries) {
        if (secondaries.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointers = stack.mallocPointer(secondaries.size());
            for (VkCommandBuffer secondary : secondaries) pointers.put(secondary.address());
            pointers.flip();
            vkCmdExecuteCommands(primary, pointers);
        }
    }

    private Texture2D resolveTexture(Material material) {
        if (material.has(MaterialFeature.TEXTURED) && material.baseColorTexture() != null) {
            return textures.load(material.baseColorTexture(), material.sampler(), true);
        }
        return textures.white();
    }

    private long drawAppBatches(VkCommandBuffer cmd, PassId pass, long boundPipeline) {
        boundPipeline = drawBatch(cmd, app.terrain, app.terrainMaterial, pass, boundPipeline);
        boundPipeline = drawBatch(cmd, app.scene, app.sceneMaterial, pass, boundPipeline);
        boundPipeline = drawBatch(cmd, app.water, app.waterMaterial, pass, boundPipeline);
        boundPipeline = drawBatch(cmd, app.overlay, app.overlayMaterial, pass, boundPipeline);
        boundPipeline = drawBatch(cmd, app.particleAlpha, app.particleAlphaMaterial, pass, boundPipeline);
        boundPipeline = drawBatch(cmd, app.particleBlend, app.particleBlendMaterial, pass, boundPipeline);
        return drawBatch(cmd, app.particleAdd, app.particleAddMaterial, pass, boundPipeline);
    }

    private long drawBatch(VkCommandBuffer cmd, DynamicBatch batch, Material material, PassId pass, long boundPipeline) {
        if (batch.indexCount == 0 || material.pass() != pass) return boundPipeline;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PipelineKey key = PipelineKey.of(material, VertexLayout.STATIC);
            long pipeline = pipelineCache.pipeline(key);
            if (pipeline != boundPipeline) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
                boundPipeline = pipeline;
                stats.countPipelineBind();
            }
            Texture2D texture = resolveTexture(material);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, descriptors.pipelineLayout(), 1,
                    stack.longs(texture.descriptorSet()), null);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, descriptors.pipelineLayout(), 2,
                    stack.longs(identityPalette.descriptorSet(context.frameIndex())), null);
            pushIdentity(material);
            vkCmdPushConstants(cmd, descriptors.pipelineLayout(),
                    VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, pushScratch);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(batch.vertexBuffer.buffer()), stack.longs(0));
            vkCmdBindIndexBuffer(cmd, batch.indexBuffer.buffer(), 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(cmd, batch.indexCount, 1, 0, 0, 0);
            stats.countDraw();
        }
        return boundPipeline;
    }

    private void pushIdentity(Material material) {
        pushScratch.clear();
        new Matrix4f().get(0, pushScratch);
        Vector4f baseColor = material.baseColorFactor();
        pushScratch.putFloat(64, baseColor.x).putFloat(68, baseColor.y)
                .putFloat(72, baseColor.z).putFloat(76, baseColor.w);
        Vector3f emissive = material.emissiveColor();
        pushScratch.putFloat(80, emissive.x).putFloat(84, emissive.y)
                .putFloat(88, emissive.z).putFloat(92, material.emissiveStrength());
        pushScratch.putFloat(96, material.alphaCutoff());
        pushScratch.putFloat(100, material.outlineWidthPixels());
        pushScratch.position(0).limit(DescriptorManager.PUSH_CONSTANT_BYTES);
    }

    private void writePushConstants(RenderItem item) {
        pushScratch.clear();
        item.transform.get(0, pushScratch);
        Vector4f baseColor = item.material.baseColorFactor();
        pushScratch.putFloat(64, baseColor.x).putFloat(68, baseColor.y)
                .putFloat(72, baseColor.z).putFloat(76, baseColor.w);
        Vector3f emissive = item.material.emissiveColor();
        pushScratch.putFloat(80, emissive.x).putFloat(84, emissive.y)
                .putFloat(88, emissive.z).putFloat(92, item.material.emissiveStrength());
        pushScratch.putFloat(96, item.material.alphaCutoff());
        pushScratch.putFloat(100, item.material.outlineWidthPixels());
        pushScratch.position(0).limit(DescriptorManager.PUSH_CONSTANT_BYTES);
    }

    private void updateGlobalUniforms(Matrix4f viewMatrix, Matrix4f projMatrix, Vector3f eye, float timeSeconds) {
        Matrix4f viewProj = new Matrix4f(projMatrix).mul(viewMatrix);
        viewProjection.set(viewProj);
        DirectionalShadowSettings shadows = environment.directionalShadows();
        shadowMap.updateMatrix(environment.sunDirection(), shadows, viewMatrix, projMatrix);
        globalScratch.clear();
        viewMatrix.get(0, globalScratch);
        projMatrix.get(64, globalScratch);
        viewProj.get(128, globalScratch);
        shadowMap.matrix().get(192, globalScratch);
        putVec4(256, eye.x, eye.y, eye.z, 1.0f);
        Vector3f sunDirection = environment.sunDirection();
        putVec4(272, sunDirection.x, sunDirection.y, sunDirection.z, 0.0f);
        Vector3f sunColor = environment.scaledSunColor();
        putVec4(288, sunColor.x, sunColor.y, sunColor.z, 1.0f);
        Vector3f ambientSky = environment.scaledAmbientSky();
        putVec4(304, ambientSky.x, ambientSky.y, ambientSky.z, 1.0f);
        Vector3f ambientGround = environment.scaledAmbientGround();
        putVec4(320, ambientGround.x, ambientGround.y, ambientGround.z, 1.0f);
        putVec4(336, timeSeconds, environment.emissiveMultiplier(), 0.0f, 0.0f);
        putVec4(352, shadows.receiverBias(), 1f / shadows.resolution(), shadows.pcfRadius(), shadows.enabled()?1f:0f);
        Vector3f fogColor = environment.fogColor();
        putVec4(368, fogColor.x, fogColor.y, fogColor.z, environment.fogDensity());
        putVec4(384, debugViewMode.shaderValue(), environment.shadowStrength(), 0.0f, 0.0f);
        putVec4(400, environment.toneExposure(), environment.toneContrast(), environment.toneSaturation(), 0.0f);
        Vector3f gradeTint = environment.gradeTint();
        putVec4(416, gradeTint.x, gradeTint.y, gradeTint.z, 1.0f);
        List<PointLight> lights = environment.pointLights();
        int lightCount = Math.min(MAX_GPU_POINT_LIGHTS, lights.size());
        int tilesX = ClusteredLighting.tilesForPixels(frameViewport.width());
        int tilesY = ClusteredLighting.tilesForPixels(frameViewport.height());
        putVec4(CLUSTER_PARAMS_OFFSET, lightCount, tilesX, tilesY, LIGHT_CLUSTER_Z_SLICES);
        putVec4(CLUSTER_DEPTH_PARAMS_OFFSET, LIGHT_TILE_SIZE, ClusteredLighting.CLUSTER_NEAR,
                ClusteredLighting.CLUSTER_FAR, MAX_LIGHTS_PER_CLUSTER);
        putVec4(CLUSTER_VIEWPORT_OFFSET, frameViewport.x(), frameViewport.y(),
                frameViewport.width(), frameViewport.height());
        putVec4(RENDER_FRAME_OFFSET, timeSeconds, frameGameTick, frameTickAlpha, 0.0f);
        Vector3f wind = environment.windDirection();
        putVec4(WIND_PARAMS_OFFSET, wind.x, wind.z, environment.windStrength(), environment.windSpeed());
        TerrainOcclusionCutaway cutaway = terrainOcclusionCutaway;
        putVec4(OCCLUSION_PLAYER_OFFSET, cutaway.playerX(), cutaway.playerFeetY(),
                cutaway.playerZ(), cutaway.strength());
        putVec4(OCCLUSION_PARAMS_OFFSET, cutaway.innerRadius(), cutaway.outerRadius(),
                cutaway.depthBias(), cutaway.floorProtectionHeight());
        float focusY = cutaway.playerFeetY() + cutaway.focusHeight();
        occlusionRayScratch.set(cutaway.playerX() - eye.x, focusY - eye.y, cutaway.playerZ() - eye.z);
        float playerDistance = occlusionRayScratch.length();
        if (cutaway.enabled() && playerDistance > .001f) {
            occlusionRayScratch.div(playerDistance);
            viewProj.transform(occlusionClipScratch.set(
                    cutaway.playerX(), focusY, cutaway.playerZ(), 1.0f));
            viewMatrix.transform(occlusionViewScratch.set(
                    cutaway.playerX(), focusY, cutaway.playerZ(), 1.0f));
            float inverseW = Math.abs(occlusionClipScratch.w) > .001f
                    ? 1.0f / occlusionClipScratch.w : 0.0f;
            float playerPixelX = (occlusionClipScratch.x * inverseW * .5f + .5f)
                    * frameViewport.width();
            float playerPixelY = (occlusionClipScratch.y * inverseW * .5f + .5f)
                    * frameViewport.height();
            float pixelsPerWorldUnit = Math.abs(projMatrix.m11()) * frameViewport.height()
                    / (2.0f * Math.max(.001f, -occlusionViewScratch.z));
            putVec4(OCCLUSION_PROJECTION_OFFSET, playerPixelX, playerPixelY,
                    pixelsPerWorldUnit, playerDistance);
            putVec4(OCCLUSION_RAY_OFFSET, occlusionRayScratch.x, occlusionRayScratch.y,
                    occlusionRayScratch.z, 0);
        } else {
            putVec4(OCCLUSION_PROJECTION_OFFSET, 0, 0, 0, 0);
            putVec4(OCCLUSION_RAY_OFFSET, 0, 0, 0, 0);
        }
        putFloatArray(ATLAS_FRAME_RECTS_OFFSET, atlasFrameRects);
        putFloatArray(ANIMATED_SPRITE_META_OFFSET, animatedSpriteMeta);
        globalScratch.position(0).limit((int) GLOBAL_UBO_BYTES);
        context.uploadBytes(globalUbos[context.frameIndex()], globalScratch, 0);
    }

    private void putVec4(int offset, float x, float y, float z, float w) {
        globalScratch.putFloat(offset, x).putFloat(offset + 4, y)
                .putFloat(offset + 8, z).putFloat(offset + 12, w);
    }

    private void putFloatArray(int offset, float[] values) {
        for (int i = 0; i < values.length; i++) {
            globalScratch.putFloat(offset + i * Float.BYTES, values[i]);
        }
    }

    private float viewDepth(Matrix4f transform) {
        depthScratch.set(transform.m30(), transform.m31(), transform.m32(), 1.0f);
        view.transform(depthScratch);
        return -depthScratch.z;
    }

    @Override
    public void close() {
        renderThread.check("Renderer.close");
        context.waitIdle();
        commandWorkers.close();
        preparationWorker.close();
        debugText.close();
        shadowMap.close();
        selectionOutline.close();
        screenSpaceOutline.close();
        uiPass.close();
        clusteredLighting.close();
        app.close();
        identityPalette.destroy();
        for (JointPalette palette : palettes) {
            palette.destroy();
        }
        for (VulkanContext.GpuBuffer ubo : globalUbos) {
            context.destroyBuffer(ubo);
        }
        meshes.close();
        textures.close();
        pipelineCache.close();
        shaderLibrary.close();
        descriptors.close();
        context.close();
    }

    static CpuMesh convertMeshDataVertices(String name, MeshData mesh) {
        return convertMeshDataVertices(name, mesh, true);
    }

    static CpuMesh convertMeshDataVertices(String name, MeshData mesh, boolean forceUpwardNormals) {
        if (mesh == null || mesh.vertices().length == 0 || mesh.indices().length == 0) return null;
        int vertexCount = mesh.vertices().length / MeshData.FLOATS_PER_VERTEX;
        float[] normals = computeMeshDataNormals(mesh, vertexCount, forceUpwardNormals);
        float[] out = new float[vertexCount * VertexLayout.STATIC.debugFloatComponents];
        for (int i = 0; i < vertexCount; i++) {
            int src = i * MeshData.FLOATS_PER_VERTEX;
            int dst = i * VertexLayout.STATIC.debugFloatComponents;
            out[dst] = mesh.vertices()[src];
            out[dst + 1] = mesh.vertices()[src + 1];
            out[dst + 2] = mesh.vertices()[src + 2];
            out[dst + 3] = normals[i * 3];
            out[dst + 4] = normals[i * 3 + 1];
            out[dst + 5] = normals[i * 3 + 2];
            out[dst + 6] = 0.0f;
            out[dst + 7] = 0.0f;
            out[dst + 8] = mesh.vertices()[src + 3];
            out[dst + 9] = mesh.vertices()[src + 4];
            out[dst + 10] = mesh.vertices()[src + 5];
            out[dst + 11] = 1.0f;
        }
        return CpuMesh.staticMesh(name, out, mesh.indices());
    }

    private static float[] computeMeshDataNormals(MeshData mesh, int vertexCount, boolean forceUpwardNormals) {
        float[] normals = new float[vertexCount * 3];
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int ia = indices[i];
            int ib = indices[i + 1];
            int ic = indices[i + 2];
            if (ia < 0 || ib < 0 || ic < 0 || ia >= vertexCount || ib >= vertexCount || ic >= vertexCount) continue;
            readPosition(vertices, ia, a);
            readPosition(vertices, ib, b);
            readPosition(vertices, ic, c);
            b.sub(a, edge1);
            c.sub(a, edge2);
            edge1.cross(edge2, normal);
            if (normal.lengthSquared() < 1e-10f) continue;
            addNormal(normals, ia, normal);
            addNormal(normals, ib, normal);
            addNormal(normals, ic, normal);
        }
        for (int i = 0; i < vertexCount; i++) {
            int n = i * 3;
            normal.set(normals[n], normals[n + 1], normals[n + 2]);
            if (normal.lengthSquared() < 1e-10f) {
                normal.set(0, 1, 0);
            } else {
                normal.normalize();
                if (forceUpwardNormals && normal.y < 0.0f) normal.negate();
            }
            normals[n] = normal.x;
            normals[n + 1] = normal.y;
            normals[n + 2] = normal.z;
        }
        return normals;
    }

    private static void readPosition(float[] vertices, int vertex, Vector3f out) {
        int base = vertex * MeshData.FLOATS_PER_VERTEX;
        out.set(vertices[base], vertices[base + 1], vertices[base + 2]);
    }

    private static void addNormal(float[] normals, int vertex, Vector3f normal) {
        int base = vertex * 3;
        normals[base] += normal.x;
        normals[base + 1] += normal.y;
        normals[base + 2] += normal.z;
    }

    static CpuMesh convertParticleVertices(String name, float[] vertices, int[] indices) {
        if (vertices == null || vertices.length == 0 || indices == null || indices.length == 0) return null;
        int vertexCount = vertices.length / ParticleMeshData.FLOATS_PER_VERTEX;
        float[] out = new float[vertexCount * VertexLayout.STATIC.debugFloatComponents];
        for (int i = 0; i < vertexCount; i++) {
            int src = i * ParticleMeshData.FLOATS_PER_VERTEX;
            int dst = i * VertexLayout.STATIC.debugFloatComponents;
            out[dst] = vertices[src];
            out[dst + 1] = vertices[src + 1];
            out[dst + 2] = vertices[src + 2];
            out[dst + 3] = 0.0f;
            out[dst + 4] = 1.0f;
            out[dst + 5] = 0.0f;
            out[dst + 6] = vertices[src + 3];
            out[dst + 7] = vertices[src + 4];
            out[dst + 8] = vertices[src + 5];
            out[dst + 9] = vertices[src + 6];
            out[dst + 10] = vertices[src + 7];
            out[dst + 11] = vertices[src + 8];
        }
        return CpuMesh.staticMesh(name, out, indices);
    }

    static ProjectedShadowMesh buildProjectedShadowMesh(MeshData source, Vector3f sunDirection, Vector3f shadowTint,
                                                        float shadowStrength) {
        return buildProjectedShadowMesh(source, sunDirection, shadowTint, shadowStrength, ShadowReceiver.flat());
    }

    static ProjectedShadowMesh buildProjectedShadowMesh(MeshData source, Vector3f sunDirection, Vector3f shadowTint,
                                                        float shadowStrength, ShadowReceiver receiver) {
        if (source == null || source.vertices().length == 0 || source.indices().length == 0
                || Math.abs(sunDirection.y) < 0.05f) {
            return ProjectedShadowMesh.empty();
        }
        if (receiver == null) {
            receiver = ShadowReceiver.flat();
        }
        int[] indices = source.indices();
        float[] vertices = source.vertices();
        int vertexCount = source.vertexCount();
        List<Integer>[] adjacency = shadowAdjacency(source, receiver);
        boolean[] visited = new boolean[vertexCount];
        List<Float> outVertices = new ArrayList<>();
        List<Integer> outIndices = new ArrayList<>();
        for (int start = 0; start < vertexCount; start++) {
            if (visited[start] || adjacency[start].isEmpty()) {
                continue;
            }
            List<ShadowPoint> points = projectedComponentPoints(vertices, adjacency, visited, start,
                    sunDirection, receiver);
            List<ShadowPoint> hull = convexHull(points);
            if (hull.size() < 3) {
                continue;
            }
            appendShadowHull(outVertices, outIndices, hull, shadowTint, shadowStrength);
        }
        float[] vertexArray = new float[outVertices.size()];
        for (int i = 0; i < vertexArray.length; i++) vertexArray[i] = outVertices.get(i);
        int[] indexArray = outIndices.stream().mapToInt(Integer::intValue).toArray();
        return new ProjectedShadowMesh(vertexArray, indexArray);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer>[] shadowAdjacency(MeshData source, ShadowReceiver receiver) {
        int vertexCount = source.vertexCount();
        List<Integer>[] adjacency = new ArrayList[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            adjacency[i] = new ArrayList<>();
        }
        int[] indices = source.indices();
        float[] vertices = source.vertices();
        Vector3f p0 = new Vector3f();
        Vector3f p1 = new Vector3f();
        Vector3f p2 = new Vector3f();
        Vector3f normal = new Vector3f();
        Vector3f e1 = new Vector3f();
        Vector3f e2 = new Vector3f();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];
            if (!validMeshDataIndex(source, i0) || !validMeshDataIndex(source, i1) || !validMeshDataIndex(source, i2)) {
                continue;
            }
            readPosition(vertices, i0, p0);
            readPosition(vertices, i1, p1);
            readPosition(vertices, i2, p2);
            if (averageHeightAboveReceiver(receiver, p0, p1, p2) < 0.045f) {
                continue;
            }
            p1.sub(p0, e1);
            p2.sub(p0, e2);
            e1.cross(e2, normal);
            if (normal.lengthSquared() < 1e-8f) {
                continue;
            }
            connect(adjacency, i0, i1);
            connect(adjacency, i1, i2);
            connect(adjacency, i2, i0);
        }
        return adjacency;
    }

    private static void connect(List<Integer>[] adjacency, int a, int b) {
        adjacency[a].add(b);
        adjacency[b].add(a);
    }

    private static boolean validMeshDataIndex(MeshData mesh, int index) {
        return index >= 0 && index < mesh.vertexCount();
    }

    private static float averageHeightAboveReceiver(ShadowReceiver receiver, Vector3f p0, Vector3f p1, Vector3f p2) {
        float h0 = p0.y - receiver.sample(p0.x, p0.z);
        float h1 = p1.y - receiver.sample(p1.x, p1.z);
        float h2 = p2.y - receiver.sample(p2.x, p2.z);
        return (h0 + h1 + h2) / 3.0f;
    }

    private static List<ShadowPoint> projectedComponentPoints(float[] vertices, List<Integer>[] adjacency,
                                                             boolean[] visited, int start, Vector3f sunDirection,
                                                             ShadowReceiver receiver) {
        List<ShadowPoint> points = new ArrayList<>();
        int[] queue = new int[visited.length];
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        visited[start] = true;
        Vector3f source = new Vector3f();
        while (head < tail) {
            int index = queue[head++];
            readPosition(vertices, index, source);
            ShadowPoint point = projectShadowPoint(source, sunDirection, receiver);
            points.add(point);
            for (int next : adjacency[index]) {
                if (!visited[next]) {
                    visited[next] = true;
                    queue[tail++] = next;
                }
            }
        }
        return points;
    }

    private static ShadowPoint projectShadowPoint(Vector3f source, Vector3f sunDirection, ShadowReceiver receiver) {
        float firstY = receiver.sample(source.x, source.z);
        float t = (firstY - source.y) / sunDirection.y;
        float x = source.x + sunDirection.x * t;
        float z = source.z + sunDirection.z * t;
        float groundY = receiver.sample(x, z);
        t = (groundY - source.y) / sunDirection.y;
        x = source.x + sunDirection.x * t;
        z = source.z + sunDirection.z * t;
        groundY = receiver.sample(x, z);
        float receiverY = groundY + 0.018f;
        float height = Math.max(0.0f, source.y - groundY);
        return new ShadowPoint(x, receiverY, z, height);
    }

    private static List<ShadowPoint> convexHull(List<ShadowPoint> points) {
        if (points.size() <= 3) {
            return points;
        }
        points.sort((a, b) -> {
            int x = Float.compare(a.x, b.x);
            return x != 0 ? x : Float.compare(a.z, b.z);
        });
        List<ShadowPoint> hull = new ArrayList<>();
        for (ShadowPoint point : points) {
            while (hull.size() >= 2 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= 0.0001f) {
                hull.remove(hull.size() - 1);
            }
            hull.add(point);
        }
        int lowerSize = hull.size();
        for (int i = points.size() - 2; i >= 0; i--) {
            ShadowPoint point = points.get(i);
            while (hull.size() > lowerSize && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= 0.0001f) {
                hull.remove(hull.size() - 1);
            }
            hull.add(point);
        }
        if (!hull.isEmpty()) {
            hull.remove(hull.size() - 1);
        }
        return hull;
    }

    private static float cross(ShadowPoint a, ShadowPoint b, ShadowPoint c) {
        return (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x);
    }

    private static void appendShadowHull(List<Float> outVertices, List<Integer> outIndices, List<ShadowPoint> hull,
                                         Vector3f tint, float strength) {
        int base = outVertices.size() / VertexLayout.STATIC.debugFloatComponents;
        for (ShadowPoint point : hull) {
            appendShadowPoint(outVertices, point, tint, strength);
        }
        for (int i = 1; i + 1 < hull.size(); i++) {
            outIndices.add(base);
            outIndices.add(base + i);
            outIndices.add(base + i + 1);
        }
    }

    private static void appendShadowPoint(List<Float> out, ShadowPoint point, Vector3f tint, float strength) {
        float alpha = Math.min(0.50f, Math.max(0.11f, strength * (0.28f + point.height * 0.24f)));
        float[] data = {
                point.x, point.y, point.z,
                0.0f, 1.0f, 0.0f,
                0.0f, 0.0f,
                tint.x * 0.16f, tint.y * 0.16f, tint.z * 0.16f, alpha
        };
        for (float value : data) out.add(value);
    }

    private record ShadowPoint(float x, float y, float z, float height) {
    }

    record ProjectedShadowMesh(float[] vertices, int[] indices) {
        static ProjectedShadowMesh empty() {
            return new ProjectedShadowMesh(new float[0], new int[0]);
        }
    }

    static final class ShadowReceiver {
        private static final ShadowReceiver FLAT = new ShadowReceiver(0, 0, 1.0f, null);

        private final int width;
        private final int height;
        private final float worldSize;
        private final float[] heights;

        private ShadowReceiver(int width, int height, float worldSize, float[] heights) {
            this.width = width;
            this.height = height;
            this.worldSize = worldSize;
            this.heights = heights;
        }

        static ShadowReceiver flat() {
            return FLAT;
        }

        static ShadowReceiver fromTerrain(TerrainData terrain) {
            if (terrain == null || terrain.width() < 2 || terrain.height() < 2) {
                return flat();
            }
            return new ShadowReceiver(terrain.width(), terrain.height(), terrain.worldSize(),
                    terrain.heightField.clone());
        }

        float sample(float worldX, float worldZ) {
            if (heights == null) {
                return 0.0f;
            }
            float sx = ((worldX / worldSize) + 0.5f) * (width - 1);
            float sy = ((worldZ / worldSize) + 0.5f) * (height - 1);
            int x0 = clamp((int) Math.floor(sx), 0, width - 1);
            int y0 = clamp((int) Math.floor(sy), 0, height - 1);
            int x1 = clamp(x0 + 1, 0, width - 1);
            int y1 = clamp(y0 + 1, 0, height - 1);
            float tx = sx - x0;
            float ty = sy - y0;
            float a = lerp(heights[y0 * width + x0], heights[y0 * width + x1], tx);
            float b = lerp(heights[y1 * width + x0], heights[y1 * width + x1], tx);
            return lerp(a, b, ty);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }

    private final class AppCompatibility implements AutoCloseable {
        final DynamicBatch terrain = new DynamicBatch();
        final DynamicBatch sceneShadow = new DynamicBatch();
        final DynamicBatch dynamicShadow = new DynamicBatch();
        final DynamicBatch water = new DynamicBatch();
        final DynamicBatch scene = new DynamicBatch();
        final DynamicBatch overlay = new DynamicBatch();
        final DynamicBatch particleAlpha = new DynamicBatch();
        final DynamicBatch particleBlend = new DynamicBatch();
        final DynamicBatch particleAdd = new DynamicBatch();

        final Material terrainMaterial = Material.builder("app_terrain_vertex_color")
                .shadingModel(ShadingModel.STANDARD_LIT)
                .cullMode(CullMode.NONE)
                .feature(MaterialFeature.VERTEX_COLOR)
                .resolve(message -> System.out.println("[renderer] WARN " + message));
        final Material sceneMaterial = Material.builder("app_scene_vertex_color")
                .shadingModel(ShadingModel.STANDARD_LIT)
                .cullMode(CullMode.NONE)
                .feature(MaterialFeature.VERTEX_COLOR)
                .resolve(message -> System.out.println("[renderer] WARN " + message));
        final Material waterMaterial = Material.builder("app_water_vertex_color")
                .blendMode(BlendMode.TRANSPARENT)
                .cullMode(CullMode.NONE)
                .feature(MaterialFeature.VERTEX_COLOR)
                .resolve(message -> System.out.println("[renderer] WARN " + message));
        final Material shadowMaterial = Material.builder("app_projected_sun_shadow")
                .blendMode(BlendMode.TRANSPARENT)
                .shadingModel(ShadingModel.UNLIT)
                .cullMode(CullMode.NONE)
                .feature(MaterialFeature.VERTEX_COLOR)
                .resolve(message -> System.out.println("[renderer] WARN " + message));
        final Material overlayMaterial = Material.builder("app_overlay_vertex_color")
                .blendMode(BlendMode.TRANSPARENT)
                .shadingModel(ShadingModel.UNLIT)
                .cullMode(CullMode.NONE)
                .feature(MaterialFeature.VERTEX_COLOR)
                .resolve(message -> System.out.println("[renderer] WARN " + message));
        final Material particleAlphaMaterial;
        final Material particleBlendMaterial;
        final Material particleAddMaterial;
        MeshData sceneShadowSource;
        boolean sceneShadowDirty = true;
        MeshData dynamicShadowSource;
        boolean dynamicShadowDirty = true;
        ShadowReceiver shadowReceiver = ShadowReceiver.flat();
        private final Vector3f lastShadowSun = new Vector3f(Float.NaN);
        private final Vector3f lastShadowTint = new Vector3f(Float.NaN);
        private float lastShadowStrength = Float.NaN;

        AppCompatibility(ParticleTextureAtlas atlas) {
            textures.fromRgba("mem://app/particle_atlas", atlas.width(), atlas.height(),
                    atlas.rgba(), SamplerSettings.PIXEL_ART, false);
            particleAlphaMaterial = particleMaterial("app_particle_alpha", BlendMode.CUTOUT);
            particleBlendMaterial = particleMaterial("app_particle_blend", BlendMode.TRANSPARENT);
            particleAddMaterial = particleMaterial("app_particle_add", BlendMode.ADDITIVE);
            materials.register(terrainMaterial);
            materials.register(sceneMaterial);
            materials.register(waterMaterial);
            materials.register(shadowMaterial);
            materials.register(overlayMaterial);
            materials.register(particleAlphaMaterial);
            materials.register(particleBlendMaterial);
            materials.register(particleAddMaterial);
        }

        int count(PassId pass) {
            int count = 0;
            if (terrain.indexCount > 0 && terrainMaterial.pass() == pass) count++;
            if (sceneShadow.indexCount > 0 && shadowMaterial.pass() == pass) count++;
            if (dynamicShadow.indexCount > 0 && shadowMaterial.pass() == pass) count++;
            if (scene.indexCount > 0 && sceneMaterial.pass() == pass) count++;
            if (water.indexCount > 0 && waterMaterial.pass() == pass) count++;
            if (overlay.indexCount > 0 && overlayMaterial.pass() == pass) count++;
            if (particleAlpha.indexCount > 0 && particleAlphaMaterial.pass() == pass) count++;
            if (particleBlend.indexCount > 0 && particleBlendMaterial.pass() == pass) count++;
            if (particleAdd.indexCount > 0 && particleAddMaterial.pass() == pass) count++;
            return count;
        }

        void updateProjectedShadows(Environment environment) {
            Vector3f sun = environment.sunDirection();
            Vector3f tint = environment.shadowTint();
            float strength = environment.shadowStrength();
            boolean lightChanged = !sun.equals(lastShadowSun, 0.015f)
                    || !tint.equals(lastShadowTint, 0.02f)
                    || Math.abs(strength - lastShadowStrength) > 0.02f;
            boolean changed = sceneShadowDirty || dynamicShadowDirty || lightChanged;
            if (!changed) return;
            if (sceneShadowDirty || lightChanged) {
                ProjectedShadowMesh shadow = buildProjectedShadowMesh(sceneShadowSource, sun, tint, strength, shadowReceiver);
                sceneShadow.upload(shadow.vertices(), shadow.indices());
            }
            if (dynamicShadowDirty || lightChanged) {
                ProjectedShadowMesh shadow = buildProjectedShadowMesh(dynamicShadowSource, sun, tint, strength, shadowReceiver);
                dynamicShadow.upload(shadow.vertices(), shadow.indices());
            }
            lastShadowSun.set(sun);
            lastShadowTint.set(tint);
            lastShadowStrength = strength;
            sceneShadowDirty = false;
            dynamicShadowDirty = false;
        }

        private Material particleMaterial(String name, BlendMode blendMode) {
            return Material.builder(name)
                    .blendMode(blendMode)
                    .shadingModel(ShadingModel.UNLIT)
                    .cullMode(CullMode.NONE)
                    .baseColorTexture("mem://app/particle_atlas")
                    .sampler(SamplerSettings.PIXEL_ART)
                    .feature(MaterialFeature.VERTEX_COLOR)
                    .alphaCutoff(0.02f)
                    .resolve(message -> System.out.println("[renderer] WARN " + message));
        }

        @Override
        public void close() {
            terrain.close();
            sceneShadow.close();
            dynamicShadow.close();
            water.close();
            scene.close();
            overlay.close();
            particleAlpha.close();
            particleBlend.close();
            particleAdd.close();
        }
    }

    private final class DynamicBatch implements AutoCloseable {
        private VulkanContext.GpuBuffer vertexBuffer;
        private VulkanContext.GpuBuffer indexBuffer;
        private long vertexBytes;
        private long indexBytes;
        private int indexCount;

        void upload(CpuMesh mesh) {
            if (mesh == null || mesh.vertexCount() == 0 || mesh.indices().length == 0) {
                clear();
                return;
            }
            long neededVertexBytes = mesh.vertexByteSize();
            long neededIndexBytes = (long) mesh.indices().length * Integer.BYTES;
            ensureCapacity(neededVertexBytes, neededIndexBytes);
            context.uploadBytes(vertexBuffer, java.nio.ByteBuffer.wrap(mesh.vertices()), 0);
            context.uploadInts(indexBuffer, mesh.indices());
            indexCount = mesh.indices().length;
        }

        void upload(float[] vertices, int[] indices) {
            upload(vertices == null || indices == null ? null : CpuMesh.staticMesh("dynamic-shadow", vertices, indices));
        }

        void clear() {
            indexCount = 0;
        }

        private void ensureCapacity(long neededVertexBytes, long neededIndexBytes) {
            if (vertexBuffer == null || vertexBytes < neededVertexBytes) {
                context.waitIdle();
                if (vertexBuffer != null) context.destroyBuffer(vertexBuffer);
                vertexBytes = nextCapacity(neededVertexBytes);
                vertexBuffer = context.createBuffer(vertexBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            }
            if (indexBuffer == null || indexBytes < neededIndexBytes) {
                context.waitIdle();
                if (indexBuffer != null) context.destroyBuffer(indexBuffer);
                indexBytes = nextCapacity(neededIndexBytes);
                indexBuffer = context.createBuffer(indexBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            }
        }

        private long nextCapacity(long required) {
            long capacity = 4096;
            while (capacity < required) capacity <<= 1;
            return capacity;
        }

        @Override
        public void close() {
            if (vertexBuffer != null) context.destroyBuffer(vertexBuffer);
            if (indexBuffer != null) context.destroyBuffer(indexBuffer);
            vertexBuffer = null;
            indexBuffer = null;
            indexCount = 0;
        }
    }
}
