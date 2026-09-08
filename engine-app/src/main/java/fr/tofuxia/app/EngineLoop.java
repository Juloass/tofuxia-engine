package fr.tofuxia.app;

import fr.tofuxia.particles.ParticleEngine;
import fr.tofuxia.particles.ParticleEffectDefinition;
import io.github.juloass.resource.pack.ResourceSnapshot;
import fr.tofuxia.particles.ParticleRenderPacket;
import fr.tofuxia.particles.ParticleSpawnContext;
import fr.tofuxia.particles.ParticleUpdateContext;
import fr.tofuxia.particles.ParticleEventSink;
import fr.tofuxia.particles.Vec3;
import fr.tofuxia.render.Camera;
import fr.tofuxia.render.FontCatalog;
import fr.tofuxia.render.FontRole;
import fr.tofuxia.render.ParticleMeshData;
import fr.tofuxia.render.ParticleTextureAtlas;
import fr.tofuxia.renderapi.DebugViewMode;
import fr.tofuxia.renderapi.RenderViewport;
import fr.tofuxia.renderer.CpuMesh;
import fr.tofuxia.renderer.Environment;
import fr.tofuxia.renderer.GpuMesh;
import fr.tofuxia.renderer.Renderer;
import fr.tofuxia.renderer.RendererStats;
import fr.tofuxia.ui.ProfilerSnapshot;
import fr.tofuxia.ui.UiContext;
import fr.tofuxia.ui.UiInput;
import fr.tofuxia.ui.UiRect;
import io.github.juloass.registry.RegistrySet;
import io.github.juloass.content.WorkProgress;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class EngineLoop {
    private static final String[] LIGHT_NAMES = {"Sunrise", "Noon", "Sunset", "Night"};
    private static final float[] UI_SCALES = {.75f, 1.00f, 1.25f, 1.50f, 2.00f};
    private static final int FRAME_TIME_SAMPLES = 180;
    private static final float FRAME_SPIKE_MS = 50.0f;
    private static final int SMOKE_TEST_FRAMES = 5;
    private static final int MAX_MESH_UPLOADS_PER_FRAME = 2;
    private static final long MAX_MESH_UPLOAD_BYTES_PER_FRAME = 4L * 1024L * 1024L;
    private static final long MAX_MESH_UPLOAD_NANOS_PER_FRAME = 1_000_000L;
    private static final int MAX_PENDING_MESH_UPLOADS = 512;
    private static final DateTimeFormatter PROFILER_SESSION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final GameConfig config;
    private final EngineArgs args;
    private final EnginePlatform platform;
    private final Renderer renderer;
    private final UiContext ui;
    private final NetworkStatusProvider networkStatus;
    private final ClientSimulationRunner simulationRunner;
    private final SceneManager scenes;
    private final ConnectionScreen connectionScreen;
    private final Map<String, GpuMesh> meshes = new HashMap<>();
    private final Map<String, Integer> meshRevisions = new HashMap<>();
    private final Map<String, Integer> latestSceneMeshRevisions = new HashMap<>();
    private final Map<String, PendingMeshUpload> pendingMeshUploads = new HashMap<>();
    private final Set<String> dynamicMeshIds = new HashSet<>();
    private final Camera camera = Camera.isometricRpg();
    private final MemorySampler memorySampler = new MemorySampler();
    private final ParticleEngine particles = new ParticleEngine();
    private final KeyedJobSystem<String, ParticleBuildSnapshot, ParticleMeshData> particleJobs =
            new KeyedJobSystem<>("tofuxia-particle-prep", 1);
    private final ParticleTextureAtlas particleAtlas;
    private final ParticleEffectDefinition particleDefinition;
    private final ParticleEventSink particleEventSink;
    private final float[] frameTimesMs = new float[FRAME_TIME_SAMPLES];

    private ProfilerSnapshot profiler = ProfilerSnapshot.EMPTY;
    private SessionProfiler sessionProfiler;
    private DebugViewMode activeDebug;
    private int frameTimeCursor;
    private int frameTimeCount;
    private long frameNumber;
    private int lightPreset = 1;
    private int uiScalePreset = 1;
    private boolean animate = true;
    private boolean showDiagnostics = false;
    private boolean panning;
    private boolean cameraTargetLocked;
    private AppState appState;

    public EngineLoop(GameConfig config, EngineArgs args, EnginePlatform platform, Renderer renderer, FontCatalog fonts,
                      io.github.juloass.localization.Localization localization,
                      NetworkStatusProvider networkStatus, RegistrySet registries) {
        this(config,args,platform,renderer,fonts,localization,networkStatus,registries,WorkProgress.none());
    }

    public EngineLoop(GameConfig config, EngineArgs args, EnginePlatform platform,
                      Renderer renderer, FontCatalog fonts,
                      io.github.juloass.localization.Localization localization,
                      NetworkStatusProvider networkStatus,
                      RegistrySet registries,
                      WorkProgress startupProgress) {
        this(config, args, platform, renderer, fonts, localization,
                networkStatus, registries, startupProgress, null,
                ParticleEventSink.NONE);
    }

    public EngineLoop(GameConfig config, EngineArgs args, EnginePlatform platform,
                      Renderer renderer, FontCatalog fonts,
                      io.github.juloass.localization.Localization localization,
                      NetworkStatusProvider networkStatus,
                      RegistrySet registries,
                      WorkProgress startupProgress,
                      ResourceSnapshot resources) {
        this(config, args, platform, renderer, fonts, localization,
                networkStatus, registries, startupProgress, resources,
                ParticleEventSink.NONE);
    }

    public EngineLoop(GameConfig config, EngineArgs args, EnginePlatform platform,
                      Renderer renderer, FontCatalog fonts,
                      io.github.juloass.localization.Localization localization,
                      NetworkStatusProvider networkStatus,
                      RegistrySet registries,
                      WorkProgress startupProgress,
                      ResourceSnapshot resources,
                      ParticleEventSink particleEventSink) {
        this.config = config;
        this.args = args;
        this.platform = platform;
        this.renderer = renderer;
        this.networkStatus = networkStatus == null ? NetworkStatusProvider.NONE : networkStatus;
        this.simulationRunner = new ClientSimulationRunner(this.networkStatus);
        this.connectionScreen = new ConnectionScreen(config.connectionBackground());
        this.ui = new UiContext(fonts.atlas(FontRole.UI, 15), localization);
        this.ui.theme().setWarmAccent();
        this.particleAtlas = config.particles().atlas();
        this.particleDefinition = config.particles().definition(resources);
        this.particleEventSink = particleEventSink == null
                ? ParticleEventSink.NONE : particleEventSink;
        int uploadedMeshes=0,totalMeshes=config.meshes().size();
        for (Map.Entry<String, fr.tofuxia.renderer.CpuMesh> entry : config.meshes().entrySet()) {
            startupProgress.report(uploadedMeshes,totalMeshes,"meshes","Uploading static meshes");
            meshes.put(entry.getKey(), renderer.uploadMesh(entry.getValue()));
            startupProgress.report(++uploadedMeshes,totalMeshes,"meshes","Uploading static meshes");
        }
        this.scenes = new SceneManager(config, renderer, registries, fonts, startupProgress);
        scenes.switchTo(args.scene());
        this.activeDebug = args.debugView();
        this.appState = args.connect().isBlank() ? AppState.IN_GAME : AppState.CONNECTING;
        renderer.setStatsOverlayEnabled(false);
        renderer.setDebugViewMode(activeDebug);
        if (particleDefinition != null) {
            particles.spawn(particles.register(particleDefinition),
                    ParticleSpawnContext.at(new Vec3(0, .15f, 1.75f), 0x5EEDL));
        }
    }

    public int run() {
        long previous = System.nanoTime();
        float time = 0;
        int frames = 0;
        int smokeFrames = Integer.getInteger("tofuxia.smokeFrames", SMOKE_TEST_FRAMES);
        double smokeSeconds = Double.parseDouble(System.getProperty("tofuxia.smokeSeconds",
                args.connect().isBlank() ? "0" : "15"));
        long smokeCaptureNanos = Math.max(0L, (long) (smokeSeconds * 1_000_000_000.0));
        long smokeLoadTimeoutNanos = (long) (Double.parseDouble(System.getProperty(
                "tofuxia.smokeLoadTimeoutSeconds", "15")) * 1_000_000_000.0);
        long smokeCaptureStart = 0L;
        long smokeWaitStart = System.nanoTime();
        int smokeCapturedFrames = 0;
        SmokeSummary smokeSummary = new SmokeSummary();
        sessionProfiler = args.smokeTest() ? null : SessionProfiler.start(frameNumber, System.nanoTime());
        try {
            while (!platform.shouldClose()) {
            long frameIndex = frameNumber++;
            long frameStart = System.nanoTime();
            long pollStart = System.nanoTime();
            platform.pollEvents();
            long pollEnd = System.nanoTime();
            long deltaStart = System.nanoTime();
            long now = System.nanoTime();
            float dt = Math.min(.1f, (now - previous) / 1_000_000_000f);
            previous = now;
            float dtMs = dt * 1000.0f;
            frameTimesMs[frameTimeCursor] = dtMs;
            frameTimeCursor = (frameTimeCursor + 1) % frameTimesMs.length;
            frameTimeCount = Math.min(frameTimesMs.length, frameTimeCount + 1);
            if (animate) time += dt;
            long deltaEnd = System.nanoTime();

            long framebufferStart = System.nanoTime();
            DisplayMetrics displayMetrics = platform.displayMetrics();
            FramebufferSize framebuffer = displayMetrics.framebuffer();
            long framebufferEnd = System.nanoTime();
            long platformStateStart = System.nanoTime();
            PlatformFrameState platformState = platform.frameState();
            long platformStateEnd = System.nanoTime();
            if (!framebuffer.visible()) {
                if (args.smokeTest() && args.connect().isBlank() && ++frames >= smokeFrames) break;
                continue;
            }

            long inputStart = System.nanoTime();
            InputSnapshot input = platform.input();
            handleGlobalInput(input);
            applyClientUiScale();
            long inputEnd = System.nanoTime();

            long updateStart = System.nanoTime();
            long sceneLookupStart = System.nanoTime();
            GameScene scene = scenes.active();
            long sceneLookupEnd = System.nanoTime();
            long appStateStart = System.nanoTime();
            updateAppState();
            long appStateEnd = System.nanoTime();
            long frameLayoutStart = System.nanoTime();
            RenderViewport worldViewport = scene.prepareFrameLayout(new SceneFrameLayoutContext(
                    dt, framebuffer.width(), framebuffer.height(),
                    UI_SCALES[uiScalePreset] * Math.max(displayMetrics.framebufferScaleX(), displayMetrics.framebufferScaleY()),
                    scene.usesFixedTickSimulation() ? false : input.gameModeToggle()));
            if (worldViewport == null) {
                worldViewport = RenderViewport.fullScreen(framebuffer.width(), framebuffer.height());
            }
            long frameLayoutEnd = System.nanoTime();
            long pointerOwnershipStart = System.nanoTime();
            boolean uiOwnsPointer=ui.capturesPointerAt(input.mouseX(),input.mouseY());
            boolean cursorInWorldViewport = worldViewport.contains(input.mouseX(), input.mouseY());
            long pointerOwnershipEnd = System.nanoTime();
            long pointerCameraStart = System.nanoTime();
            handlePointerCameraInput(input, worldViewport, uiOwnsPointer, cursorInWorldViewport, scene);
            long pointerCameraEnd = System.nanoTime();
            long cameraUpdateStart = System.nanoTime();
            camera.update(dt);
            long cameraUpdateEnd = System.nanoTime();
            long cursorRayStart = System.nanoTime();
            fr.tofuxia.render.Camera.Ray cursorRay = cursorInWorldViewport
                    ? camera.screenRay(worldViewport.localX(input.mouseX()), worldViewport.localY(input.mouseY()),
                    worldViewport.width(), worldViewport.height())
                    : new fr.tofuxia.render.Camera.Ray(new Vector3f(), new Vector3f());
            long cursorRayEnd = System.nanoTime();
            ClientInputSample inputSample = new ClientInputSample(dt, time, animate, lightPreset, networkStatus.worldState(),
                    input.moveX(), input.moveZ(), false, showDiagnostics,
                    camera.yaw(),
                    cursorRay.origin().x, cursorRay.origin().y, cursorRay.origin().z,
                    cursorRay.direction().x, cursorRay.direction().y, cursorRay.direction().z,
                    false,
                    false,
                    false,
                    -1,
                    cursorInWorldViewport, worldViewport);
            ClientInputEvents inputEvents = new ClientInputEvents(
                    input.combatToggle(),
                    input.gameModeToggle(),
                    input.hotbarSelection(),
                    input.leftMouseClicked() && !uiOwnsPointer && cursorInWorldViewport,
                    input.rightMouseClicked() && !uiOwnsPointer && cursorInWorldViewport,
                    cursorRay.origin().x, cursorRay.origin().y, cursorRay.origin().z,
                    cursorRay.direction().x, cursorRay.direction().y, cursorRay.direction().z,
                    cursorInWorldViewport);
            simulationRunner.setScene(scene);
            simulationRunner.submitInput(inputSample);
            if (scene.usesFixedTickSimulation()) {
                if (input.commands().isEmpty()) {
                    simulationRunner.submitEvents(inputEvents);
                } else {
                    for (InputCommand command : input.commands()) {
                        fr.tofuxia.render.Camera.Ray commandRay = cursorRay;
                        boolean commandInWorld = !uiOwnsPointer && cursorInWorldViewport;
                        if (command.pointerPosition().isPresent()) {
                            io.github.juloass.input.InputVector2 pointer = command.pointerPosition().orElseThrow();
                            float pointerX = (float) (pointer.x() * displayMetrics.framebufferScaleX());
                            float pointerY = (float) (pointer.y() * displayMetrics.framebufferScaleY());
                            commandInWorld = !ui.capturesPointerAt(pointerX, pointerY)
                                    && worldViewport.contains(pointerX, pointerY);
                            commandRay = commandInWorld
                                    ? camera.screenRay(worldViewport.localX(pointerX), worldViewport.localY(pointerY),
                                    worldViewport.width(), worldViewport.height())
                                    : new fr.tofuxia.render.Camera.Ray(new Vector3f(), new Vector3f());
                        }
                        simulationRunner.submitEvents(commandEvents(command,
                                commandRay.origin().x, commandRay.origin().y, commandRay.origin().z,
                                commandRay.direction().x, commandRay.direction().y, commandRay.direction().z,
                                commandInWorld));
                    }
                }
            }
            long sceneUpdateStart = System.nanoTime();
            if (!scene.usesFixedTickSimulation()) {
                scene.update(new SceneUpdateContext(dt, time, animate, lightPreset, networkStatus.worldState(),
                        input.moveX(), input.moveZ(), input.combatToggle(), showDiagnostics,
                        camera.yaw(),
                        cursorRay.origin().x, cursorRay.origin().y, cursorRay.origin().z,
                        cursorRay.direction().x, cursorRay.direction().y, cursorRay.direction().z,
                        input.leftMouseClicked()&&!uiOwnsPointer&&cursorInWorldViewport,
                        input.rightMouseClicked()&&!uiOwnsPointer&&cursorInWorldViewport,
                        input.gameModeToggle(),
                        input.hotbarSelection(), networkStatus,
                        cursorInWorldViewport, worldViewport));
            }
            long sceneUpdateEnd = System.nanoTime();
            List<ProfilerSnapshot.Entry> sceneUpdateProfilerEntries = scene.usesFixedTickSimulation()
                    ? combinedSceneProfiler(scene)
                    : scene.updateProfilerEntries();
            float fixedTickAlpha = scene.usesFixedTickSimulation() ? simulationRunner.stats().renderAlpha() : 0.0f;
            scene.prepareRenderFrame(fixedTickAlpha);
            long cameraTargetStart = System.nanoTime();
            Vector3f cameraTarget = scene.cameraTarget();
            if (cameraTarget != null) {
                if (cameraTargetLocked) {
                    camera.lockIsometricExploration(cameraTarget.x, cameraTarget.y, cameraTarget.z, dt);
                } else {
                    camera.snapIsometricExploration(cameraTarget.x, cameraTarget.y, cameraTarget.z);
                }
                cameraTargetLocked = true;
            } else {
                cameraTargetLocked = false;
            }
            fr.tofuxia.render.Camera.Ray hoverRay = cursorInWorldViewport
                    ? camera.screenRay(worldViewport.localX(input.mouseX()), worldViewport.localY(input.mouseY()),
                    worldViewport.width(), worldViewport.height())
                    : new fr.tofuxia.render.Camera.Ray(new Vector3f(), new Vector3f());
            scene.updatePointerHover(new PointerHoverContext(
                    hoverRay.origin().x, hoverRay.origin().y, hoverRay.origin().z,
                    hoverRay.direction().x, hoverRay.direction().y, hoverRay.direction().z,
                    cursorInWorldViewport, uiOwnsPointer, worldViewport));
            platform.setCursorStyle(uiOwnsPointer ? CursorStyle.ARROW : scene.cursorStyle());
            scene.beginRenderFrame();
            renderer.uploadTerrainOcclusionCutaway(scene.terrainOcclusionCutaway());
            long cameraTargetEnd = System.nanoTime();
            long updateEnd = System.nanoTime();

            long particleStart = System.nanoTime();
            ParticleTimings particleTimings = updateParticles(scene, animate ? dt : 0);
            long particleEnd = System.nanoTime();

            long sceneStart = System.nanoTime();
            long eyeStart = System.nanoTime();
            Vector3f eye = camera.position();
            long eyeEnd = System.nanoTime();
            long viewStart = System.nanoTime();
            Matrix4f view = camera.view();
            long viewEnd = System.nanoTime();
            long projectionStart = System.nanoTime();
            Matrix4f projection = camera.projection(worldViewport.width(), worldViewport.height());
            long projectionEnd = System.nanoTime();
            long environmentStart = System.nanoTime();
            Environment environment = scene.environment(time);
            long environmentEnd = System.nanoTime();
            long renderTickStart = System.nanoTime();
            int renderTick = Math.max(networkStatus.worldState().serverTick(), (int) Math.floor(time * 20.0f));
            long renderTickEnd = System.nanoTime();
            long renderBeginStart = System.nanoTime();
            renderer.beginFrame(view, projection, eye, environment, time,
                    renderTick, fixedTickAlpha, worldViewport);
            long renderBeginEnd = System.nanoTime();
            long submitStart = System.nanoTime();
            int dynamicMeshCount = 0;
            Set<String> currentDynamicMeshIds = new HashSet<>();
            for (SceneMesh mesh : scene.dynamicMeshes()) {
                currentDynamicMeshIds.add(mesh.id());
                dynamicMeshCount++;
                Integer previousRevision = meshRevisions.get(mesh.id());
                if (previousRevision == null || previousRevision != mesh.revision()) {
                    enqueueMeshUpload(mesh, eye, true);
                }
            }
            for (String removed : new HashSet<>(dynamicMeshIds)) {
                if (currentDynamicMeshIds.contains(removed)) continue;
                pendingMeshUploads.remove(removed);
                latestSceneMeshRevisions.remove(removed);
                renderer.retireMesh(meshes.remove(removed));
                meshRevisions.remove(removed);
            }
            dynamicMeshIds.clear();
            dynamicMeshIds.addAll(currentDynamicMeshIds);
            MeshUploadFrameStats meshUploads = processPendingMeshUploads(currentDynamicMeshIds);
            for (ScenePlacement placement : scene.placements()) {
                GpuMesh mesh = meshes.get(placement.mesh());
                if (mesh == null) {
                    if (currentDynamicMeshIds.contains(placement.mesh()) || pendingMeshUploads.containsKey(placement.mesh())) {
                        continue;
                    }
                    throw new IllegalStateException("Scene '" + scene.id() + "' requested unknown mesh: " + placement.mesh());
                }
                renderer.submit(mesh, scene.transform(placement, time), placement.material());
            }
            scene.submitModels(renderer);
            renderer.uploadSelectionOutline(scene.selectionOutline());
            long submitEnd = System.nanoTime();
            long sceneEnd = submitEnd;

            long uiStart = System.nanoTime();
            buildUi(input, displayMetrics, scene, particleTimings.packetCount(), dt);
            long uiEnd = System.nanoTime();

            long renderEndStart = System.nanoTime();
            renderer.endFrame();
            scene.endRenderFrame();
            long renderEndEnd = System.nanoTime();
            long frameEnd = renderEndEnd;
            float frameWorkMs = ms(frameStart, frameEnd);
            ProfilerSnapshot.Memory memory = memorySampler.sample();
            RendererStats.RenderTimings renderTimings = renderer.stats().timings();
            float rendererMs = ms(sceneStart, sceneEnd) + ms(renderEndStart, renderEndEnd);
            float frameSyncMs = (float) (renderTimings.recreateSwapchainMs()
                    + renderTimings.waitPreviousFrameFenceMs()
                    + renderTimings.acquireSwapchainImageMs()
                    + renderTimings.resetFrameFenceMs()
                    + renderTimings.resetFrameCommandBufferMs()
                    + renderTimings.beginFrameCommandBufferMs());
            float beginStateMs = Math.max(0.0f, ms(renderBeginStart, renderBeginEnd) - frameSyncMs);
            float prepareSceneInputMs = ms(sceneStart, renderBeginStart);
            float rendererInputChildrenSum = ms(eyeStart, eyeEnd) + ms(viewStart, viewEnd)
                    + ms(projectionStart, projectionEnd) + ms(environmentStart, environmentEnd)
                    + ms(renderTickStart, renderTickEnd);
            float rendererInputUnaccountedMs = Math.max(0.0f, prepareSceneInputMs - rendererInputChildrenSum);
            float submitSceneMs = ms(submitStart, submitEnd);
            float recordSubmitPresentMs = ms(renderEndStart, renderEndEnd);
            float rendererChildrenSum = prepareSceneInputMs + frameSyncMs + beginStateMs
                    + submitSceneMs + recordSubmitPresentMs;
            float rendererUnaccountedMs = Math.max(0.0f, rendererMs - rendererChildrenSum);
            float rendererAccountedPct = rendererMs <= 0.0001f ? 100.0f
                    : Math.min(100.0f, rendererChildrenSum / rendererMs * 100.0f);

            profiler = ProfilerSnapshot.of(frameWorkMs,
                    new ProfilerSnapshot.Entry("renderer",
                            rendererMs,
                            new ProfilerSnapshot.Entry("prepare renderer inputs", prepareSceneInputMs,
                                    new ProfilerSnapshot.Entry("camera position", ms(eyeStart, eyeEnd)),
                                    new ProfilerSnapshot.Entry("camera view matrix", ms(viewStart, viewEnd)),
                                    new ProfilerSnapshot.Entry("camera projection matrix", ms(projectionStart, projectionEnd)),
                                    new ProfilerSnapshot.Entry("scene environment", ms(environmentStart, environmentEnd),
                                            scene.rendererInputProfilerEntries()),
                                    new ProfilerSnapshot.Entry("render tick sample", ms(renderTickStart, renderTickEnd)),
                                    new ProfilerSnapshot.Entry("renderer input unaccounted", rendererInputUnaccountedMs)),
                            new ProfilerSnapshot.Entry("frame synchronization", frameSyncMs,
                                    new ProfilerSnapshot.Entry("recreate swapchain", (float) renderTimings.recreateSwapchainMs()),
                                    new ProfilerSnapshot.Entry("wait previous frame fence", (float) renderTimings.waitPreviousFrameFenceMs()),
                                    new ProfilerSnapshot.Entry("acquire swapchain image", (float) renderTimings.acquireSwapchainImageMs()),
                                    new ProfilerSnapshot.Entry("reset frame fence", (float) renderTimings.resetFrameFenceMs()),
                                    new ProfilerSnapshot.Entry("reset frame command buffer", (float) renderTimings.resetFrameCommandBufferMs()),
                                    new ProfilerSnapshot.Entry("begin frame command buffer", (float) renderTimings.beginFrameCommandBufferMs())),
                            new ProfilerSnapshot.Entry("begin frame state", beginStateMs,
                                    new ProfilerSnapshot.Entry("retire mesh resources", (float) renderTimings.retireMeshesMs()),
                                    new ProfilerSnapshot.Entry("update global uniforms", (float) renderTimings.updateGlobalUniformsMs()),
                                    new ProfilerSnapshot.Entry("queue/stats/frame state", (float) renderTimings.rendererBeginStateMs())),
                            new ProfilerSnapshot.Entry("submit scene", submitSceneMs,
                                    new ProfilerSnapshot.Entry("mesh uploads", meshUploads.uploadMs()),
                                    new ProfilerSnapshot.Entry("mesh upload queue " + meshUploads.pendingCount()
                                            + "/" + "%.1fMB".formatted(meshUploads.pendingBytes() / 1048576.0),
                                            meshUploads.pendingCount() == 0 ? 0.0f : 0.001f),
                                    new ProfilerSnapshot.Entry("mesh buffer creates +" + meshUploads.bufferCreations(),
                                            meshUploads.bufferCreations() == 0 ? 0.0f : 0.001f)),
                            new ProfilerSnapshot.Entry("record/submit/present", recordSubmitPresentMs,
                                    new ProfilerSnapshot.Entry("prepare queue snapshot", (float) renderTimings.prepareQueueMs()),
                                    new ProfilerSnapshot.Entry("render prep worker cpu", (float) renderTimings.renderPrepWorkerCpuMs()),
                                    new ProfilerSnapshot.Entry("prepare app draws", (float) renderTimings.prepareAppDrawsMs()),
                                    new ProfilerSnapshot.Entry("wait render prep worker", (float) renderTimings.waitPrepareWorkerMs()),
                                    new ProfilerSnapshot.Entry("prepare main draws", (float) renderTimings.prepareMainDrawsMs()),
                                    new ProfilerSnapshot.Entry("prepare shadow draws", (float) renderTimings.prepareShadowDrawsMs()),
                                    new ProfilerSnapshot.Entry("record secondaries", (float) renderTimings.recordSecondariesMs(),
                                            new ProfilerSnapshot.Entry("submit secondary record jobs", (float) renderTimings.submitSecondaryJobsMs()),
                                            new ProfilerSnapshot.Entry("wait secondary record workers", (float) renderTimings.waitSecondaryWorkersMs()),
                                            new ProfilerSnapshot.Entry("secondary worker cpu", (float) renderTimings.secondaryWorkerCpuMs())),
                                    new ProfilerSnapshot.Entry("shadow pass commands", (float) renderTimings.shadowPassMs()),
                                    new ProfilerSnapshot.Entry("begin main pass", (float) renderTimings.beginMainPassMs()),
                                    new ProfilerSnapshot.Entry("execute main secondaries", (float) renderTimings.executeMainSecondariesMs()),
                                    new ProfilerSnapshot.Entry("renderer stats", (float) renderTimings.statsMs()),
                                    new ProfilerSnapshot.Entry("record overlay", (float) renderTimings.overlayRecordMs()),
                                    new ProfilerSnapshot.Entry("execute overlay", (float) renderTimings.executeOverlayMs()),
                                    new ProfilerSnapshot.Entry("end render pass", (float) renderTimings.endRenderPassMs()),
                                    new ProfilerSnapshot.Entry("end command buffer", (float) renderTimings.endCommandBufferMs()),
                                    new ProfilerSnapshot.Entry("queue submit", (float) renderTimings.queueSubmitMs()),
                                    new ProfilerSnapshot.Entry("present/vsync wait", (float) renderTimings.presentMs()),
                                    new ProfilerSnapshot.Entry("advance frame index", (float) renderTimings.frameIndexAdvanceMs())),
                            new ProfilerSnapshot.Entry("renderer accounting", 0.0f,
                                    new ProfilerSnapshot.Entry("renderer children sum", rendererChildrenSum),
                                    new ProfilerSnapshot.Entry("renderer unaccounted", rendererUnaccountedMs),
                                    new ProfilerSnapshot.Entry("renderer accounted %.1f%%".formatted(rendererAccountedPct), 0.0f))),
                    new ProfilerSnapshot.Entry("platform/window",
                            ms(pollStart, pollEnd) + ms(deltaStart, deltaEnd) + ms(framebufferStart, framebufferEnd)
                                    + ms(platformStateStart, platformStateEnd),
                            new ProfilerSnapshot.Entry("poll window events", ms(pollStart, pollEnd)),
                            new ProfilerSnapshot.Entry("calculate delta/frame clock", ms(deltaStart, deltaEnd)),
                            new ProfilerSnapshot.Entry("query framebuffer size", ms(framebufferStart, framebufferEnd)),
                            new ProfilerSnapshot.Entry("query focus/iconified state", ms(platformStateStart, platformStateEnd)),
                            new ProfilerSnapshot.Entry("window focused " + platformState.focused(), 0.0f),
                            new ProfilerSnapshot.Entry("window iconified " + platformState.iconified(), 0.0f),
                            new ProfilerSnapshot.Entry("frame limiter active false", 0.0f),
                            new ProfilerSnapshot.Entry("background throttle active false", 0.0f),
                            new ProfilerSnapshot.Entry("idle throttle active false", 0.0f)),
                    new ProfilerSnapshot.Entry("ui", ms(uiStart, uiEnd),
                            new ProfilerSnapshot.Entry("build ui", ms(uiStart, uiEnd))),
                    new ProfilerSnapshot.Entry("particles", ms(particleStart, particleEnd),
                            new ProfilerSnapshot.Entry("simulate", ms(particleTimings.updateStart(), particleTimings.updateEnd())),
                            new ProfilerSnapshot.Entry("build/upload mesh", ms(particleTimings.buildStart(), particleTimings.buildEnd()))),
                    new ProfilerSnapshot.Entry("input", ms(inputStart, inputEnd)),
                    new ProfilerSnapshot.Entry("update", ms(updateStart, updateEnd),
                            new ProfilerSnapshot.Entry("scene lookup", ms(sceneLookupStart, sceneLookupEnd)),
                            new ProfilerSnapshot.Entry("app state", ms(appStateStart, appStateEnd)),
                            new ProfilerSnapshot.Entry("frame layout", ms(frameLayoutStart, frameLayoutEnd)),
                            new ProfilerSnapshot.Entry("ui pointer ownership", ms(pointerOwnershipStart, pointerOwnershipEnd)),
                            new ProfilerSnapshot.Entry("pointer camera input", ms(pointerCameraStart, pointerCameraEnd)),
                            new ProfilerSnapshot.Entry("camera update", ms(cameraUpdateStart, cameraUpdateEnd)),
                            new ProfilerSnapshot.Entry("cursor ray", ms(cursorRayStart, cursorRayEnd)),
                            new ProfilerSnapshot.Entry("scene update", ms(sceneUpdateStart, sceneUpdateEnd), sceneUpdateProfilerEntries),
                            new ProfilerSnapshot.Entry("camera target lock", ms(cameraTargetStart, cameraTargetEnd))))
                    .withMemory(memory);
            boolean smokeReady = !args.smokeTest() || smokeCaptureReady();
            if (args.smokeTest() && smokeReady && smokeCaptureStart == 0L) {
                smokeCaptureStart = frameEnd;
                System.out.println("[profiler] smoke capture started after world load at frame " + frameIndex
                        + " appState=" + appState + " connected=" + networkStatus.worldState().connected()
                        + " chunks=" + networkStatus.worldState().chunkCache().size());
            }
            if (args.smokeTest() && smokeReady) {
                smokeSummary.add(frameWorkMs, rendererMs, ms(updateStart, updateEnd), ms(uiStart, uiEnd),
                        ms(particleStart, particleEnd), ms(pollStart, pollEnd), ms(environmentStart, environmentEnd),
                        (float) renderTimings.presentMs());
                smokeCapturedFrames++;
            }
            FrameDiagnostic diagnostic = new FrameDiagnostic(frameIndex, scene.id(), dtMs, frameWorkMs,
                    ms(inputStart, inputEnd), ms(updateStart, updateEnd), ms(particleStart, particleEnd),
                    ms(renderBeginStart, renderBeginEnd), ms(submitStart, submitEnd),
                    meshUploads.uploadMs(),
                    ms(uiStart, uiEnd), ms(renderEndStart, renderEndEnd),
                    input.moveX(), input.moveZ(), dynamicMeshCount, meshUploads.uploadedCount(),
                    meshUploads.uploadedVertices(), meshUploads.uploadedIndices(), meshUploads.uploadedBytes(),
                    meshUploads.pendingCount(), meshUploads.pendingBytes(), meshUploads.bufferCreations(),
                    particleTimings.packetCount(),
                    memory.usedDeltaBytes(), memory.gcCountDelta(), memory.gcTimeDeltaMs());
            if (sessionProfiler != null) {
                sessionProfiler.record(diagnostic, profiler, renderTimings);
            }

            frames++;
            if (args.smokeTest()) {
                if (smokeReady) {
                    if (smokeCaptureNanos > 0 && frameEnd - smokeCaptureStart >= smokeCaptureNanos) break;
                    if (smokeCaptureNanos == 0 && smokeCapturedFrames >= smokeFrames) break;
                } else if (!args.connect().isBlank() && frameEnd - smokeWaitStart >= smokeLoadTimeoutNanos) {
                    System.out.println("[profiler] smoke capture timed out waiting for server world load"
                            + " appState=" + appState
                            + " connected=" + networkStatus.worldState().connected()
                            + " chunks=" + networkStatus.worldState().chunkCache().size());
                    break;
                } else if (args.connect().isBlank() && frames >= smokeFrames) {
                    break;
                }
            }
            }
            if (args.smokeTest()) {
                printSmokeProfilerExcerpt(smokeSummary);
            }
            return frames;
        } finally {
            SessionProfiler capture = sessionProfiler;
            sessionProfiler = null;
            if (capture != null) capture.close(System.nanoTime());
            simulationRunner.close();
            particleJobs.close();
            renderer.waitIdle();
            networkStatus.close();
        }
    }

    private static ClientInputEvents commandEvents(InputCommand command,
                                                   float originX, float originY, float originZ,
                                                   float directionX, float directionY, float directionZ,
                                                   boolean inWorldViewport) {
        if (command.kind() != io.github.juloass.input.ButtonTransitionKind.PRESS) return ClientInputEvents.EMPTY;
        String action = command.action().value();
        boolean combat = action.equals("tofuxia:combat_toggle");
        boolean gameMode = action.equals("tofuxia:game_mode_toggle");
        boolean left = action.equals("tofuxia:pointer_left");
        boolean right = action.equals("tofuxia:pointer_right");
        if ((left || right) && !inWorldViewport) return ClientInputEvents.EMPTY;
        int hotbar = -1;
        if (action.startsWith("tofuxia:hotbar_")) {
            try { hotbar = Integer.parseInt(action.substring("tofuxia:hotbar_".length())) - 1; }
            catch (NumberFormatException ignored) { hotbar = -1; }
        }
        return new ClientInputEvents(combat, gameMode, hotbar, left, right,
                originX, originY, originZ, directionX, directionY, directionZ, inWorldViewport);
    }

    private void handleGlobalInput(InputSnapshot input) {
        if (input.quit()) platform.requestClose();
        if (input.stats()) showDiagnostics = !showDiagnostics;
        if (input.reload()) renderer.reloadShadersAndMaterials();
        if (input.mipmapToggle()) renderer.toggleMipmaps();
        if (input.pause()) animate = !animate;
        if (input.uiScale()) uiScalePreset = (uiScalePreset + 1) % UI_SCALES.length;
        if (input.chatSubmit() != null) networkStatus.sendChat(input.chatSubmit());
        if (input.sceneSlot() >= 0) {
            simulationRunner.setScene(null);
            if (scenes.switchSlot(input.sceneSlot())) {
                simulationRunner.setScene(scenes.active());
                renderer.uploadParticles(null);
            }
        }
        if (input.debugView() != null) {
            activeDebug = input.debugView();
            renderer.setDebugViewMode(activeDebug);
        }
        if (input.lightPreset() >= 0) lightPreset = input.lightPreset();
    }

    private List<ProfilerSnapshot.Entry> combinedSceneProfiler(GameScene scene) {
        ArrayList<ProfilerSnapshot.Entry> entries = new ArrayList<>(scene.updateProfilerEntries());
        entries.addAll(simulationRunner.profilerEntries());
        return List.copyOf(entries);
    }

    private boolean smokeCaptureReady() {
        if (appState != AppState.IN_GAME) return false;
        if (args.connect().isBlank()) return true;
        NetworkWorldState worldState = networkStatus.worldState();
        return worldState.connected() && !worldState.chunkCache().isEmpty();
    }

    private void printSmokeProfilerExcerpt(SmokeSummary summary) {
        if (summary.count() > 0) {
            double averageFrameMs = summary.frameMs.average();
            double averageFps = averageFrameMs <= 0.0001 ? 0.0 : 1000.0 / averageFrameMs;
            System.out.println("[profiler] smoke averages frames=" + summary.count()
                    + " fps=" + "%.1f".formatted(averageFps)
                    + " frame=" + "%.3fms".formatted(averageFrameMs)
                    + " renderer=" + "%.3fms".formatted(summary.rendererMs.average())
                    + " update=" + "%.3fms".formatted(summary.updateMs.average())
                    + " ui=" + "%.3fms".formatted(summary.uiMs.average())
                    + " particles=" + "%.3fms".formatted(summary.particlesMs.average())
                    + " pollEvents=" + "%.3fms".formatted(summary.pollEventsMs.average())
                    + " sceneEnvironment=" + "%.3fms".formatted(summary.sceneEnvironmentMs.average())
                    + " present=" + "%.3fms".formatted(summary.presentMs.average()));
        }
        System.out.println("[profiler] smoke final-frame tree");
        for (ProfilerSnapshot.Entry entry : profiler.entries()) {
            printProfilerEntry(entry, 0, 3);
        }
    }

    private static void printProfilerEntry(ProfilerSnapshot.Entry entry, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        System.out.println("[profiler] " + "  ".repeat(depth)
                + entry.name() + " " + "%.3fms".formatted(entry.millis()));
        if (depth == maxDepth) return;
        for (ProfilerSnapshot.Entry child : entry.children()) {
            printProfilerEntry(child, depth + 1, maxDepth);
        }
    }

    private void handlePointerCameraInput(InputSnapshot input, RenderViewport viewport,
                                          boolean uiOwnsPointer, boolean cursorInWorldViewport, GameScene scene) {
        boolean pointerCanControlWorld = cursorInWorldViewport && !uiOwnsPointer;
        boolean fixedSceneCamera = scene.cameraTarget() != null;
        if (!fixedSceneCamera && input.rightMouseDown() && pointerCanControlWorld) {
            camera.orbitByMouse(input.mouseDx(), input.mouseDy());
        }
        if (!fixedSceneCamera && input.middleMouseDown() && pointerCanControlWorld) {
            if (!panning) camera.panBegin(viewport.localX(input.mouseX()), viewport.localY(input.mouseY()),
                    viewport.width(), viewport.height());
            camera.panUpdate(viewport.localX(input.mouseX()), viewport.localY(input.mouseY()),
                    viewport.width(), viewport.height());
            panning = true;
        } else if (panning) {
            camera.panEnd();
            panning = false;
        }
        if (input.wheel() != 0 && pointerCanControlWorld) {
            camera.zoomByWheel(input.wheel(), viewport.localX(input.mouseX()), viewport.localY(input.mouseY()),
                    viewport.width(), viewport.height());
        }
    }

    private void enqueueMeshUpload(SceneMesh sceneMesh, Vector3f eye, boolean visible) {
        latestSceneMeshRevisions.put(sceneMesh.id(), sceneMesh.revision());
        long bytes = meshBytes(sceneMesh.mesh());
        float priority = uploadPriority(sceneMesh.mesh(), eye, visible);
        PendingMeshUpload existing = pendingMeshUploads.get(sceneMesh.id());
        if (existing == null || sceneMesh.revision() >= existing.revision()) {
            pendingMeshUploads.put(sceneMesh.id(), new PendingMeshUpload(
                    sceneMesh.id(), sceneMesh.revision(), sceneMesh.mesh(), bytes, priority, visible));
        }
        prunePendingMeshUploads();
    }

    private MeshUploadFrameStats processPendingMeshUploads(Set<String> currentDynamicMeshIds) {
        if (pendingMeshUploads.isEmpty()) {
            return new MeshUploadFrameStats(0, 0, 0, 0, pendingMeshUploads.size(), 0, 0, 0.0f);
        }
        long started = System.nanoTime();
        long elapsed = 0;
        long uploadedBytes = 0;
        int uploadedCount = 0;
        int uploadedVertices = 0;
        int uploadedIndices = 0;
        int bufferCreationsBefore = renderer.meshBufferCreationsThisFrame();
        ArrayList<PendingMeshUpload> ordered = new ArrayList<>(pendingMeshUploads.values());
        ordered.sort(Comparator.comparing(PendingMeshUpload::priority));
        for (PendingMeshUpload pending : ordered) {
            if (uploadedCount >= MAX_MESH_UPLOADS_PER_FRAME) break;
            if (uploadedCount > 0 && uploadedBytes + pending.bytes() > MAX_MESH_UPLOAD_BYTES_PER_FRAME) break;
            if (uploadedCount > 0 && elapsed >= MAX_MESH_UPLOAD_NANOS_PER_FRAME) break;
            Integer latest = latestSceneMeshRevisions.get(pending.id());
            if (latest == null || latest != pending.revision() || !currentDynamicMeshIds.contains(pending.id())) {
                pendingMeshUploads.remove(pending.id());
                continue;
            }
            long uploadStart = System.nanoTime();
            GpuMesh replacement = renderer.replaceMesh(meshes.get(pending.id()), pending.mesh());
            meshes.put(pending.id(), replacement);
            meshRevisions.put(pending.id(), pending.revision());
            pendingMeshUploads.remove(pending.id());
            uploadedCount++;
            uploadedBytes += pending.bytes();
            uploadedVertices += pending.mesh().vertexCount();
            uploadedIndices += pending.mesh().indices().length;
            elapsed = System.nanoTime() - started;
            if (System.nanoTime() - uploadStart >= MAX_MESH_UPLOAD_NANOS_PER_FRAME && uploadedCount > 0) {
                break;
            }
        }
        long pendingBytes = pendingUploadBytes();
        int bufferCreations = Math.max(0, renderer.meshBufferCreationsThisFrame() - bufferCreationsBefore);
        return new MeshUploadFrameStats(uploadedCount, uploadedVertices, uploadedIndices, uploadedBytes,
                pendingMeshUploads.size(), pendingBytes, bufferCreations, elapsed / 1_000_000.0f);
    }

    private void prunePendingMeshUploads() {
        while (pendingMeshUploads.size() > MAX_PENDING_MESH_UPLOADS) {
            PendingMeshUpload worst = pendingMeshUploads.values().stream()
                    .max(Comparator.comparing(PendingMeshUpload::priority))
                    .orElse(null);
            if (worst == null) return;
            pendingMeshUploads.remove(worst.id());
        }
    }

    private long pendingUploadBytes() {
        long bytes = 0;
        for (PendingMeshUpload pending : pendingMeshUploads.values()) bytes += pending.bytes();
        return bytes;
    }

    private static long meshBytes(CpuMesh mesh) {
        return mesh.vertexByteSize() + (long) mesh.indices().length * Integer.BYTES;
    }

    private static float uploadPriority(CpuMesh mesh, Vector3f eye, boolean visible) {
        float distance = meshDistanceSquared(mesh, eye);
        return (visible ? 0.0f : 1_000_000_000.0f) + distance;
    }

    private static float meshDistanceSquared(CpuMesh mesh, Vector3f eye) {
        if (mesh.vertexCount() == 0) return Float.MAX_VALUE;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float x = mesh.positionX(i), y = mesh.positionY(i), z = mesh.positionZ(i);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private ParticleTimings updateParticles(GameScene scene, float dt) {
        long particleUpdateStart = System.nanoTime();
        long particleUpdateEnd = particleUpdateStart;
        long particleBuildStart = particleUpdateStart;
        long particleBuildEnd = particleBuildStart;
        int particlePacketCount = 0;
        if (scene.particles() && config.particles().definition() != null) {
            Vector3f eye = camera.position();
            particleUpdateStart = System.nanoTime();
            particles.update(dt, new ParticleUpdateContext(
                    new Vec3(eye.x, eye.y, eye.z),
                    fr.tofuxia.particles.ParticleCollisionProvider.NONE,
                    Map.of(), particleEventSink));
            particleUpdateEnd = System.nanoTime();
            particleBuildStart = System.nanoTime();
            ParticleMeshData newest = null;
            long newestGeneration = Long.MIN_VALUE;
            for (KeyedJobSystem.Completed<String, ParticleMeshData> completed : particleJobs.pollCompleted()) {
                if (completed.error() != null) {
                    System.err.println("[particle-jobs] preparation failed: " + completed.error());
                } else if (completed.generation() > newestGeneration) {
                    newest = completed.value();
                    newestGeneration = completed.generation();
                }
            }
            if (newest != null) renderer.uploadParticles(newest);
            List<ParticleRenderPacket> particlePackets = particles.collectRenderPackets();
            particlePacketCount = particlePackets.size();
            ParticleBuildSnapshot snapshot = new ParticleBuildSnapshot(
                    List.copyOf(particlePackets), new Vector3f(eye), new Vector3f(camera.target()));
            particleJobs.submit("frame", snapshot,
                    value -> config.particles().mesh(value.packets(), particleAtlas, value.eye(), value.target()));
            particleBuildEnd = System.nanoTime();
        } else {
            particleJobs.cancel("frame");
            particleJobs.pollCompleted();
            renderer.uploadParticles(null);
        }
        return new ParticleTimings(particleUpdateStart, particleUpdateEnd, particleBuildStart, particleBuildEnd, particlePacketCount);
    }

    private void buildUi(InputSnapshot input, DisplayMetrics display, GameScene scene, int particleCount, float dt) {
        FramebufferSize framebuffer = display.framebuffer();
        ui.setUiScale(UI_SCALES[uiScalePreset]);
        ClientSettingsState.Accessibility accessibility = networkStatus.clientSettingsState().accessibility();
        ui.setAccessibility(new fr.tofuxia.ui.UiAccessibilityPreferences((float) accessibility.textScale(),
                accessibility.reducedUiAnimations(), accessibility.highContrast()));
        ui.begin(new UiInput(input.mouseX(), input.mouseY(), input.leftMouseDown(), input.leftMouseClicked(),
                        input.leftMouseReleased(), input.wheel(), input.uiTyped(),
                        input.uiBackspace(), input.uiDelete(), input.uiLeft(), input.uiRight(),
                        input.uiHome(), input.uiEnd(), input.uiUnfocus(), dt,
                        input.uiNavNext(), input.uiNavPrevious(), input.uiNavActivate(), input.uiNavCancel(),
                        input.uiNavX(), input.uiNavY(), input.uiController()),
                new fr.tofuxia.ui.UiViewport(framebuffer.width(), framebuffer.height(),
                        display.framebufferScaleX(), display.framebufferScaleY(),
                        UI_SCALES[uiScalePreset], new fr.tofuxia.ui.UiInsets(0, 0, 0, 0)));
        if (ConnectionReadiness.awaitingCharacterChoice(!args.connect().isBlank(),
                networkStatus.accountRosterState(), networkStatus.playerEntityId())) {
            scene.ui(new SceneUiContext(ui, dt));
            renderer.uploadUi(ui.end());
            return;
        }
        if(appState==AppState.CONNECTING){
            connectionScreen.draw(ui,networkStatus,dt);
            renderer.uploadUi(ui.end());
            return;
        }
        if(appState==AppState.WORLD_LOADING||appState==AppState.MAIN_MENU){
            String stateKey=appState==AppState.WORLD_LOADING?"world_loading":"main_menu";
            String title=ui.localization().resolvePlainText(io.github.juloass.localization.TextComponent.translatable(
                    "screen.engine."+stateKey+".title"));
            String detail=ui.localization().resolvePlainText(io.github.juloass.localization.TextComponent.translatable(
                    "screen.engine."+stateKey+".detail"));
            float panelWidth=430;
            float x=Math.max(12,(ui.width()-panelWidth)*.5f);
            float y=Math.max(12,ui.height()*.42f);
            ui.dynamicTextPanel(x,y,title,new String[]{detail,"State: "+appState},430);
            renderer.uploadUi(ui.end());
            return;
        }
        UiRect worldTimeHud = scene.suppressWorldTimeHud() ? null : buildWorldTimeHud(framebuffer);
        buildChatUi(input, framebuffer);
        if (!showDiagnostics) {
            buildFrameTimeGraph(12.0f, 42.0f, framebuffer, 0.28f, 82.0f);
            scene.ui(new SceneUiContext(ui, dt));
            renderer.uploadUi(ui.end());
            return;
        }
        UiRect infoPanel = ui.dynamicTextPanel(12, 12, scene.title(), debugRows(scene, particleCount), 315.0f);
        UiRect frameGraph = buildFrameTimeGraph(12.0f, infoPanel.y1() + 8.0f,
                framebuffer, 0.34f, 116.0f);
        if (showDiagnostics) {
            UiRect profilerPanel = ui.profilerPanel(12, frameGraph.y1() + 8.0f, profiler, 390.0f);
            UiRect memoryPanel = ui.memoryPanel(12, profilerPanel.y1() + 8.0f, profiler.memory(), 390.0f);
            buildSessionProfilerPanel(12, memoryPanel.y1() + 8.0f);
        }
        float panelX = Math.max(12.0f, ui.width() - 12.0f - 280.0f);
        float panelY = worldTimeHud == null ? 12.0f : worldTimeHud.y1() + 8.0f;
        ui.dynamicTextPanel(panelX, panelY, "Tofuxia Lab",
                new String[]{
                        "Scene: " + scene.title().replace("TOFUXIA ", ""),
                        "UI scale: %.0f%% (F4)".formatted(UI_SCALES[uiScalePreset] * 100.0f),
                        "Debug: " + debugName(activeDebug),
                        networkStatus.statusLine(),
                        scene.particles() ? "Particles: enabled" : "Particles: none",
                },
                230.0f);
        scene.ui(new SceneUiContext(ui, dt));
        renderer.uploadUi(ui.end());
    }

    private UiRect buildFrameTimeGraph(float x, float y, FramebufferSize framebuffer, float widthFraction, float height) {
        float graphW = Math.min(420.0f, Math.max(220.0f, ui.width() * widthFraction));
        return ui.timeSeriesPanel(new UiRect(x, y, graphW, height),
                "Frame time", frameTimesMs, frameTimeCount, frameTimeCursor, 16.67f, FRAME_SPIKE_MS, "ms");
    }

    private void updateAppState(){
        appState = ConnectionReadiness.resolve(!args.connect().isBlank(), networkStatus.worldState());
    }

    private void applyClientUiScale() {
        ClientSettingsState state = networkStatus.clientSettingsState();
        if (state.bindings().isEmpty()) return;
        double requested = state.graphics().uiScale();
        int nearest = 0;
        for (int i = 1; i < UI_SCALES.length; i++) {
            if (Math.abs(UI_SCALES[i] - requested) < Math.abs(UI_SCALES[nearest] - requested)) nearest = i;
        }
        uiScalePreset = nearest;
    }

    public AppState appState(){return appState;}

    private UiRect buildWorldTimeHud(FramebufferSize framebuffer) {
        NetworkWorldState world = networkStatus.worldState();
        if (!world.connected()) return null;
        long now = System.currentTimeMillis();
        float width = 156.0f;
        float x = Math.max(12.0f, ui.width() - 12.0f - width);
        return ui.dynamicTextPanel(x, 12.0f, "World Time",
                new String[]{world.celestialLabel(now) + "  " + world.worldClock(now)},
                width);
    }

    private void buildChatUi(InputSnapshot input, FramebufferSize framebuffer) {
        String[] history = networkStatus.chatLines();
        if (!input.chatActive() && history.length == 0) return;
        int historyRows = Math.min(6, history.length);
        String[] rows = new String[historyRows + 1];
        int start = Math.max(0, history.length - historyRows);
        for (int i = 0; i < historyRows; i++) rows[i] = history[start + i];
        rows[rows.length - 1] = input.chatActive() ? "> " + input.chatDraft() + "_" : "Enter: chat | /ping | /time get | /time set 12:00";
        ui.dynamicTextPanel(12, Math.max(12, ui.height() - (44 + rows.length * 18)),
                "Chat", rows, 520.0f);
    }

    private String[] debugRows(GameScene scene, int particleCount) {
        String[] sceneRows = scene.diagnostics(new SceneDiagnosticsContext(lightPreset, particleCount));
        if (sceneRows.length > 0) return sceneRows;
        return new String[]{
                "Glyph QA: : ? j g p q y A",
                "F1 Render Lab | F2 Shadow Lab | F3 debug UI | F4 UI scale",
                "Session profiler active | F8/F9/F11/F12 debug views | F10 mipmaps | F5 reload",
                "Lighting [1-4]: " + LIGHT_NAMES[lightPreset],
                "RMB orbit | MMB pan | wheel zoom | Space animation",
                scene.particles() ? "Particles: " + particleCount : "Particles: none",
                "Esc quit"
        };
    }

    private void buildSessionProfilerPanel(float x, float y) {
        SessionProfiler capture = sessionProfiler;
        String[] rows = capture == null
                ? new String[]{"Session profiler unavailable (or disabled for smoke tests)."}
                : new String[]{
                "Recording every rendered frame for this session.",
                "Frames: " + capture.frameCount(),
                "Output: " + capture.file().toAbsolutePath(),
                "Final summary is appended when the game exits."
        };
        ui.dynamicTextPanel(x, y, "Session profiler", rows, 510.0f);
    }

    private static String debugName(DebugViewMode mode) {
        return switch (mode) {
            case LIT -> "lit";
            case ALBEDO -> "albedo";
            case DIRECT_LIGHT -> "direct light";
            case AMBIENT -> "ambient";
            case SHADOW_FACTOR -> "shadow factor";
            case FOG_FACTOR -> "fog factor";
            case TONE_MAPPED -> "tone mapped";
            case SHADOW_MAP_DEPTH -> "shadow depth";
            case NORMALS -> "normals";
            case SPRITE_LOCAL_UV -> "sprite local uv";
            case SPRITE_ID -> "sprite id";
            case SPRITE_ATLAS_UV -> "sprite atlas uv";
            case SPRITE_FRAME -> "sprite frame";
            case CLUSTER_OCCUPANCY -> "cluster occupancy";
            case LOCAL_LIGHT -> "local light";
            case SELECTION_MASK -> "selection mask";
            case SELECTION_DILATED_MASK -> "selection dilated mask";
            case SELECTION_EXTERIOR -> "selection exterior";
            case SELECTION_DEPTH_OCCLUSION -> "selection depth occlusion";
        };
    }

    private static float ms(long start, long end) {
        return (end - start) / 1_000_000.0f;
    }

    private record ParticleTimings(long updateStart, long updateEnd, long buildStart, long buildEnd, int packetCount) {
    }

    private record ParticleBuildSnapshot(List<ParticleRenderPacket> packets, Vector3f eye, Vector3f target) {
    }

    static final class SessionProfiler {
        private static final String END = new String("profiler-session-end");
        private static final int QUEUE_CAPACITY = 8192;
        private static final int FLUSH_EVERY_ROWS = 120;
        private static final String FRAME_HEADER = String.join("\t",
                "frame", "elapsed_ms", "scene", "dt_ms", "frame_work_ms", "input_ms", "update_ms",
                "particles_ms", "begin_frame_ms", "submit_ms", "mesh_upload_ms", "ui_ms", "present_ms",
                "move_x", "move_z", "dynamic_meshes", "uploaded_meshes", "uploaded_vertices",
                "uploaded_indices", "uploaded_bytes", "pending_uploads", "pending_upload_bytes",
                "mesh_buffer_creations", "particle_packets", "memory_delta_bytes", "gc_count_delta",
                "gc_time_delta_ms", "recreate_swapchain_ms", "wait_previous_frame_fence_ms",
                "acquire_swapchain_image_ms", "reset_frame_fence_ms", "reset_frame_command_buffer_ms",
                "begin_frame_command_buffer_ms", "renderer_begin_state_ms", "retire_meshes_ms",
                "update_global_uniforms_ms", "prepare_queue_ms", "render_prep_worker_cpu_ms",
                "prepare_app_draws_ms", "wait_prepare_worker_ms", "prepare_main_draws_ms",
                "prepare_shadow_draws_ms", "record_secondaries_ms", "submit_secondary_jobs_ms",
                "wait_secondary_workers_ms", "secondary_worker_cpu_ms", "shadow_pass_ms",
                "begin_main_pass_ms", "execute_main_secondaries_ms", "renderer_stats_ms",
                "overlay_record_ms", "execute_overlay_ms", "end_render_pass_ms", "end_command_buffer_ms",
                "queue_submit_ms", "present_vsync_wait_ms", "frame_index_advance_ms", "main_draws",
                "shadow_draws", "pipeline_binds", "profiler_tree");

        private final Path file;
        private final long startFrameIndex;
        private final long startNanos;
        private final BlockingQueue<String> rows = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private final LinkedHashMap<String, ProfilerAccumulator> aggregates = new LinkedHashMap<>();
        private final Thread writerThread;
        private volatile IOException writerFailure;
        private long frameCount;
        private long lastFrameIndex;
        private long droppedRows;
        private boolean closed;

        static SessionProfiler start(long startFrameIndex, long startNanos) {
            return start(Path.of("build", "profiler-dumps"), startFrameIndex, startNanos);
        }

        static SessionProfiler start(Path directory, long startFrameIndex, long startNanos) {
            Path file = directory.resolve("profiler-session-" +
                    LocalDateTime.now().format(PROFILER_SESSION_TIMESTAMP) + ".tsv");
            try {
                Files.createDirectories(directory);
                BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                writer.write("# Tofuxia full-session profiler\n");
                writer.write("# started=" + LocalDateTime.now() + " start_frame=" + startFrameIndex + "\n");
                writer.write("# Every rendered frame is recorded below; the final min/avg/max summary is appended on exit.\n");
                writer.write("[frames]\n");
                writer.write(FRAME_HEADER);
                writer.newLine();
                writer.flush();
                SessionProfiler capture = new SessionProfiler(file, startFrameIndex, startNanos, writer);
                System.out.println("[profiler] recording full session to " + file.toAbsolutePath());
                return capture;
            } catch (IOException e) {
                System.err.println("[profiler] failed to start full-session capture at "
                        + file.toAbsolutePath() + ": " + e.getMessage());
                return null;
            }
        }

        private SessionProfiler(Path file, long startFrameIndex, long startNanos, BufferedWriter writer) {
            this.file = file;
            this.startFrameIndex = startFrameIndex;
            this.startNanos = startNanos;
            this.lastFrameIndex = startFrameIndex;
            this.writerThread = new Thread(() -> writeRows(writer), "tofuxia-session-profiler-writer");
            this.writerThread.setDaemon(false);
            this.writerThread.start();
        }

        Path file() {
            return file;
        }

        long frameCount() {
            return frameCount;
        }

        void record(FrameDiagnostic d, ProfilerSnapshot snapshot, RendererStats.RenderTimings r) {
            if (closed || writerFailure != null) return;
            long recordStart = System.nanoTime();
            frameCount++;
            lastFrameIndex = d.frameIndex();
            addDiagnosticAggregates(d);
            addRenderAggregates(r);
            for (ProfilerSnapshot.Entry entry : snapshot.entries()) {
                collectProfilerEntry("tree", entry);
            }
            if (!rows.offer(frameRow(d, snapshot, r))) droppedRows++;
            add("profiler/session_record_overhead_ms", (System.nanoTime() - recordStart) / 1_000_000.0);
        }

        void close(long endNanos) {
            if (closed) return;
            closed = true;
            try {
                if (writerFailure == null) {
                    rows.put(END);
                }
                writerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[profiler] interrupted while finalizing " + file.toAbsolutePath());
            }
            appendSummary(endNanos);
        }

        private void writeRows(BufferedWriter writer) {
            try (writer) {
                int sinceFlush = 0;
                while (true) {
                    String row = rows.take();
                    if (row == END) break;
                    writer.write(row);
                    writer.newLine();
                    if (++sinceFlush >= FLUSH_EVERY_ROWS) {
                        writer.flush();
                        sinceFlush = 0;
                    }
                }
                writer.flush();
            } catch (IOException e) {
                writerFailure = e;
                System.err.println("[profiler] session writer failed for " + file.toAbsolutePath() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                writerFailure = new IOException("writer thread interrupted", e);
            }
        }

        private void appendSummary(long endNanos) {
            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.newLine();
                writer.write("[summary]");
                writer.newLine();
                writer.write("ended=" + LocalDateTime.now()
                        + " duration_seconds=" + ((endNanos - startNanos) / 1_000_000_000.0)
                        + " frames=" + frameCount
                        + " range=" + startFrameIndex + ".." + lastFrameIndex
                        + " dropped_rows=" + droppedRows);
                writer.newLine();
                if (writerFailure != null) {
                    writer.write("writer_error=" + sanitize(writerFailure.getMessage()));
                    writer.newLine();
                }
                writer.write("metric\tcount\taverage\tmin\tmax");
                writer.newLine();
                for (Map.Entry<String, ProfilerAccumulator> entry : aggregates.entrySet()) {
                    ProfilerAccumulator stats = entry.getValue();
                    writer.write(sanitize(entry.getKey()) + "\t" + stats.count + "\t" + stats.average()
                            + "\t" + stats.min() + "\t" + stats.max());
                    writer.newLine();
                }
                System.out.println("[profiler] finalized " + frameCount + " frames to " + file.toAbsolutePath());
            } catch (IOException e) {
                System.err.println("[profiler] failed to finalize " + file.toAbsolutePath() + ": " + e.getMessage());
            }
        }

        private void addDiagnosticAggregates(FrameDiagnostic d) {
            add("dt_ms", d.dtMs());
            add("frame_work_ms", d.frameMs());
            add("input_ms", d.inputMs());
            add("update_ms", d.updateMs());
            add("particles_ms", d.particlesMs());
            add("begin_frame_ms", d.beginFrameMs());
            add("submit_ms", d.submitMs());
            add("mesh_upload_ms", d.meshUploadMs());
            add("ui_ms", d.uiMs());
            add("present_ms", d.presentMs());
            add("dynamic_meshes", d.dynamicMeshCount());
            add("uploaded_meshes", d.uploadedMeshCount());
            add("pending_uploads", d.pendingUploadCount());
            add("particle_packets", d.particlePackets());
            add("memory_delta_kb", d.memoryDeltaBytes() / 1024.0);
            add("gc_count_delta", d.gcCountDelta());
            add("gc_time_delta_ms", d.gcTimeDeltaMs());
        }

        private void addRenderAggregates(RendererStats.RenderTimings r) {
            add("render/recreate_swapchain_ms", r.recreateSwapchainMs());
            add("render/wait_previous_frame_fence_ms", r.waitPreviousFrameFenceMs());
            add("render/acquire_swapchain_image_ms", r.acquireSwapchainImageMs());
            add("render/reset_frame_fence_ms", r.resetFrameFenceMs());
            add("render/reset_frame_command_buffer_ms", r.resetFrameCommandBufferMs());
            add("render/begin_frame_command_buffer_ms", r.beginFrameCommandBufferMs());
            add("render/renderer_begin_state_ms", r.rendererBeginStateMs());
            add("render/retire_meshes_ms", r.retireMeshesMs());
            add("render/update_global_uniforms_ms", r.updateGlobalUniformsMs());
            add("render/prepare_queue_ms", r.prepareQueueMs());
            add("render/render_prep_worker_cpu_ms", r.renderPrepWorkerCpuMs());
            add("render/prepare_app_draws_ms", r.prepareAppDrawsMs());
            add("render/wait_prepare_worker_ms", r.waitPrepareWorkerMs());
            add("render/prepare_main_draws_ms", r.prepareMainDrawsMs());
            add("render/prepare_shadow_draws_ms", r.prepareShadowDrawsMs());
            add("render/record_secondaries_ms", r.recordSecondariesMs());
            add("render/submit_secondary_jobs_ms", r.submitSecondaryJobsMs());
            add("render/wait_secondary_workers_ms", r.waitSecondaryWorkersMs());
            add("render/secondary_worker_cpu_ms", r.secondaryWorkerCpuMs());
            add("render/shadow_pass_ms", r.shadowPassMs());
            add("render/begin_main_pass_ms", r.beginMainPassMs());
            add("render/execute_main_secondaries_ms", r.executeMainSecondariesMs());
            add("render/renderer_stats_ms", r.statsMs());
            add("render/overlay_record_ms", r.overlayRecordMs());
            add("render/execute_overlay_ms", r.executeOverlayMs());
            add("render/end_render_pass_ms", r.endRenderPassMs());
            add("render/end_command_buffer_ms", r.endCommandBufferMs());
            add("render/queue_submit_ms", r.queueSubmitMs());
            add("render/present_vsync_wait_ms", r.presentMs());
            add("render/frame_index_advance_ms", r.frameIndexAdvanceMs());
            add("render/main_draws", r.mainDraws());
            add("render/shadow_draws", r.shadowDraws());
            add("render/pipeline_binds", r.pipelineBinds());
        }

        private void collectProfilerEntry(String parent, ProfilerSnapshot.Entry entry) {
            String path = parent + "/" + entry.name();
            add(path, entry.millis());
            for (ProfilerSnapshot.Entry child : entry.children()) collectProfilerEntry(path, child);
        }

        private void add(String metric, double value) {
            aggregates.computeIfAbsent(metric, ignored -> new ProfilerAccumulator()).add(value);
        }

        private String frameRow(FrameDiagnostic d, ProfilerSnapshot snapshot, RendererStats.RenderTimings r) {
            StringBuilder row = new StringBuilder(2048);
            value(row, d.frameIndex());
            value(row, (System.nanoTime() - startNanos) / 1_000_000.0);
            value(row, sanitize(d.sceneId()));
            value(row, d.dtMs()); value(row, d.frameMs()); value(row, d.inputMs()); value(row, d.updateMs());
            value(row, d.particlesMs()); value(row, d.beginFrameMs()); value(row, d.submitMs());
            value(row, d.meshUploadMs()); value(row, d.uiMs()); value(row, d.presentMs());
            value(row, d.moveX()); value(row, d.moveZ()); value(row, d.dynamicMeshCount());
            value(row, d.uploadedMeshCount()); value(row, d.uploadedVertices()); value(row, d.uploadedIndices());
            value(row, d.uploadedBytes()); value(row, d.pendingUploadCount()); value(row, d.pendingUploadBytes());
            value(row, d.meshBufferCreations()); value(row, d.particlePackets()); value(row, d.memoryDeltaBytes());
            value(row, d.gcCountDelta()); value(row, d.gcTimeDeltaMs());
            value(row, r.recreateSwapchainMs()); value(row, r.waitPreviousFrameFenceMs());
            value(row, r.acquireSwapchainImageMs()); value(row, r.resetFrameFenceMs());
            value(row, r.resetFrameCommandBufferMs()); value(row, r.beginFrameCommandBufferMs());
            value(row, r.rendererBeginStateMs()); value(row, r.retireMeshesMs());
            value(row, r.updateGlobalUniformsMs()); value(row, r.prepareQueueMs());
            value(row, r.renderPrepWorkerCpuMs()); value(row, r.prepareAppDrawsMs());
            value(row, r.waitPrepareWorkerMs()); value(row, r.prepareMainDrawsMs());
            value(row, r.prepareShadowDrawsMs()); value(row, r.recordSecondariesMs());
            value(row, r.submitSecondaryJobsMs()); value(row, r.waitSecondaryWorkersMs());
            value(row, r.secondaryWorkerCpuMs()); value(row, r.shadowPassMs());
            value(row, r.beginMainPassMs()); value(row, r.executeMainSecondariesMs()); value(row, r.statsMs());
            value(row, r.overlayRecordMs()); value(row, r.executeOverlayMs()); value(row, r.endRenderPassMs());
            value(row, r.endCommandBufferMs()); value(row, r.queueSubmitMs()); value(row, r.presentMs());
            value(row, r.frameIndexAdvanceMs()); value(row, r.mainDraws()); value(row, r.shadowDraws());
            value(row, r.pipelineBinds());
            StringBuilder tree = new StringBuilder(1024);
            for (ProfilerSnapshot.Entry entry : snapshot.entries()) appendProfilerTree(tree, "", entry);
            row.append(tree);
            return row.toString();
        }

        private static void appendProfilerTree(StringBuilder out, String parent, ProfilerSnapshot.Entry entry) {
            String path = parent.isEmpty() ? entry.name() : parent + "/" + entry.name();
            if (!out.isEmpty()) out.append(';');
            out.append(sanitizeToken(path)).append('=').append(entry.millis());
            for (ProfilerSnapshot.Entry child : entry.children()) appendProfilerTree(out, path, child);
        }

        private static void value(StringBuilder row, Object value) {
            row.append(value).append('\t');
        }

        private static String sanitize(String value) {
            return value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        }

        private static String sanitizeToken(String value) {
            return sanitize(value).replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=");
        }
    }

    private static final class SmokeSummary {
        private final ProfilerAccumulator frameMs = new ProfilerAccumulator();
        private final ProfilerAccumulator rendererMs = new ProfilerAccumulator();
        private final ProfilerAccumulator updateMs = new ProfilerAccumulator();
        private final ProfilerAccumulator uiMs = new ProfilerAccumulator();
        private final ProfilerAccumulator particlesMs = new ProfilerAccumulator();
        private final ProfilerAccumulator pollEventsMs = new ProfilerAccumulator();
        private final ProfilerAccumulator sceneEnvironmentMs = new ProfilerAccumulator();
        private final ProfilerAccumulator presentMs = new ProfilerAccumulator();

        void add(double frame, double renderer, double update, double ui, double particles,
                 double pollEvents, double sceneEnvironment, double present) {
            frameMs.add(frame);
            rendererMs.add(renderer);
            updateMs.add(update);
            uiMs.add(ui);
            particlesMs.add(particles);
            pollEventsMs.add(pollEvents);
            sceneEnvironmentMs.add(sceneEnvironment);
            presentMs.add(present);
        }

        int count() {
            return frameMs.count;
        }
    }

    private static final class ProfilerAccumulator {
        private int count;
        private double total;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        void add(double value) {
            count++;
            total += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double average() {
            return count == 0 ? 0.0 : total / count;
        }

        double min() {
            return count == 0 ? 0.0 : min;
        }

        double max() {
            return count == 0 ? 0.0 : max;
        }
    }

    private record PendingMeshUpload(String id, int revision, CpuMesh mesh, long bytes, float priority, boolean visible) {
    }

    private record MeshUploadFrameStats(
            int uploadedCount,
            int uploadedVertices,
            int uploadedIndices,
            long uploadedBytes,
            int pendingCount,
            long pendingBytes,
            int bufferCreations,
            float uploadMs
    ) {
    }

    record FrameDiagnostic(
            long frameIndex,
            String sceneId,
            float dtMs,
            float frameMs,
            float inputMs,
            float updateMs,
            float particlesMs,
            float beginFrameMs,
            float submitMs,
            float meshUploadMs,
            float uiMs,
            float presentMs,
            float moveX,
            float moveZ,
            int dynamicMeshCount,
            int uploadedMeshCount,
            int uploadedVertices,
            int uploadedIndices,
            long uploadedBytes,
            int pendingUploadCount,
            long pendingUploadBytes,
            int meshBufferCreations,
            int particlePackets,
            long memoryDeltaBytes,
            long gcCountDelta,
            long gcTimeDeltaMs
    ) {
        String summary() {
            return "dt %.1f work %.1f submit %.1f present %.1f upload %d %.1fMB/%.1fms q %d gc +%d/%dms".formatted(
                    dtMs, frameMs, submitMs, presentMs, uploadedMeshCount, uploadedBytes / 1048576.0,
                    meshUploadMs, pendingUploadCount, gcCountDelta, gcTimeDeltaMs);
        }

        String row(long offset) {
            String marker = offset == 0 ? "*" : offset < 0 ? "-" : "+";
            String moving = Math.abs(moveX) + Math.abs(moveZ) > 0.001f ? " move" : "";
            String upload = uploadedMeshCount > 0
                    ? " upload %d mesh %dv %.1fMB %.1fms".formatted(
                    uploadedMeshCount, uploadedVertices, uploadedBytes / 1048576.0, meshUploadMs)
                    : " upload none";
            String queue = pendingUploadCount > 0
                    ? " q %d/%.1fMB".formatted(pendingUploadCount, pendingUploadBytes / 1048576.0)
                    : "";
            String buffers = meshBufferCreations > 0 ? " buffers +" + meshBufferCreations : "";
            String gc = gcCountDelta > 0 ? " GC +" + gcCountDelta + "/" + gcTimeDeltaMs + "ms" : "";
            return "%s%+d dt %.1f work %.1f upd %.1f sub %.1f pres %.1f%s dyn %d%s%s%s%s".formatted(
                    marker, offset, dtMs, frameMs, updateMs, submitMs, presentMs, moving, dynamicMeshCount,
                    upload, queue, buffers, gc);
        }
    }
}
