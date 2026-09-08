package fr.tofuxia.desktop;

import fr.tofuxia.app.EngineArgs;
import fr.tofuxia.app.EngineLoop;
import fr.tofuxia.app.LoadingManager;
import fr.tofuxia.app.LoadingPhase;
import fr.tofuxia.app.StartupTaskSpec;
import fr.tofuxia.app.GameBuilder;
import fr.tofuxia.app.GameConfig;
import fr.tofuxia.app.GameModule;
import fr.tofuxia.app.NetworkStatusProvider;
import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.FontCatalog;
import fr.tofuxia.render.FontRole;
import fr.tofuxia.renderer.Renderer;
import fr.tofuxia.renderer.BootstrapRenderer;
import io.github.juloass.content.BootstrapStage;
import io.github.juloass.content.ContentBootstrapResult;
import io.github.juloass.content.ContentBootstrapSession;
import io.github.juloass.content.ExecutionProfile;
import io.github.juloass.localization.resource.ResourceTranslationProvider;
import io.github.juloass.localization.LocaleId;
import io.github.juloass.localization.Localization;
import io.github.juloass.resource.ResourceDomain;
import io.github.juloass.resource.ResourcePath;
import io.github.juloass.resource.pack.ResourceManager;
import io.github.juloass.resource.pack.ResourceSnapshot;
import io.github.juloass.audio.AudioEngine;
import io.github.juloass.audio.openal.OpenAlAudioEngine;
import io.github.juloass.audio.openal.OpenAlConfiguration;
import io.github.juloass.particle.ParticleEventSink;

import static org.lwjgl.glfw.GLFW.*;

public final class DesktopEngine {
    private DesktopEngine() {
    }

    public static void run(String[] args, GameModule module) {
        run(args, module, DesktopNetworkFactory.OFFLINE);
    }

    public static void run(String[] args, GameModule module, DesktopNetworkFactory networkFactory) {
        java.util.Objects.requireNonNull(networkFactory, "networkFactory");
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        DesktopClientSettings clientSettings = new DesktopClientSettings();
        clientSettings.displayResolutions(availableDisplayResolutions());
        fr.tofuxia.app.ClientSettingsState initialSettings = clientSettings.state();
        fr.tofuxia.app.ClientSettingsState.Graphics initialGraphics = initialSettings.graphics();
        System.setProperty("tofuxia.presentMode", initialGraphics.vsync() ? "fifo" : "mailbox-preferred");
        GameBuilder builder = new GameBuilder();
        module.configure(builder);
        GameConfig config = builder.build();
        long window = createConfiguredWindow(initialGraphics, config.windowTitle());
        if (window == 0) {
            glfwTerminate();
            throw new IllegalStateException("Window creation failed");
        }

        Renderer[] rendererRef = new Renderer[1];
        BootstrapRenderer bootstrap = null;
        DesktopPlatform platform = null;
        FontCatalog fonts = null;
        ResourceManager assetManager = null;
        ResourceSnapshot assetSnapshot = null;
        AudioEngine audioEngine = null;
        java.util.concurrent.ExecutorService bootstrapWorker = java.util.concurrent.Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("tofuxia-content-bootstrap").factory());
        try {
            ResourceManager.Builder assetManagerBuilder =
                    ResourceManager.builder(
                            ResourceDomain.ASSETS,
                            config.resourcePacks());
            config.resourceTypes().stream()
                    .filter(type -> type.domain() == ResourceDomain.ASSETS)
                    .forEach(assetManagerBuilder::registerType);
            assetManager = assetManagerBuilder.build();
            assetSnapshot = assetManager.acquireSnapshot();
            ParticleEventSink particleEvents = ParticleEventSink.NONE;
            if (config.audioSupport().isPresent()) {
                fr.tofuxia.app.AudioSupport audio =
                        config.audioSupport().orElseThrow();
                audioEngine = OpenAlAudioEngine.open(
                        OpenAlConfiguration.defaults(audio.mixerGraph()));
                if (!audioEngine.status().available()) {
                    audioEngine.status().problem().ifPresent(problem ->
                            System.err.println("[audio] " + problem.message()));
                }
                particleEvents = audio.particleSinkFactory().create(
                        assetManager, audioEngine);
            }
            ParticleEventSink activeParticleEvents = particleEvents;
            ResourceSnapshot activeAssets = assetSnapshot;
            ResourceTranslationProvider translations = new ResourceTranslationProvider(
                    config.resourcePacks(), LocaleId.of("en_us"),
                    message -> System.err.println("[localization-assets] " + message));
            LocaleId selectedLocale;
            try {
                selectedLocale = LocaleId.of(clientSettings.locale());
                if (!translations.availableLocales().contains(selectedLocale)) selectedLocale = LocaleId.of("fr_fr");
            } catch (RuntimeException invalid) {
                selectedLocale = LocaleId.of("fr_fr");
            }
            Localization localization = new Localization(translations, selectedLocale, LocaleId.of("en_us"),
                    diagnostic -> System.err.println("[localization] " + diagnostic.format()));
            if (!selectedLocale.value().equals(clientSettings.locale())) clientSettings.locale(selectedLocale.value());
            localization.addLocaleChangeListener(locale -> clientSettings.locale(locale.value()));
            FontCatalog activeFonts = FontCatalog.load(
                    activeAssets,
                    resourcePath(config.fontManifest()));
            fonts = activeFonts;
            FontAtlas uiFont = activeFonts.atlas(FontRole.UI, 15);
            FontAtlas debugFont = activeFonts.atlas(FontRole.DEBUG, 15);
            FontAtlas gameTitleFont = activeFonts.atlas(FontRole.GAME_TITLE, 52);
            LoadingScreen loadingScreen = new LoadingScreen(uiFont, gameTitleFont, (float) initialGraphics.uiScale(),
                    (float) initialSettings.accessibility().textScale(), localization, config.windowTitle());
            bootstrap = new BootstrapRenderer(window, uiFont, gameTitleFont);
            bootstrap.render(loadingScreen.build(displayMetrics(window),
                    new LoadingManager.Snapshot(fr.tofuxia.app.AppState.BOOT_LOADING,
                            LoadingPhase.CONFIGURATION,"engine:configuration","Reading game configuration",0,"",0,1)));
            glfwPollEvents();
            if (glfwGetWindowAttrib(window, GLFW_FOCUSED) != GLFW_TRUE) {
                glfwFocusWindow(window);
                if (glfwGetWindowAttrib(window, GLFW_FOCUSED) != GLFW_TRUE) glfwRequestWindowAttention(window);
            }

            glfwSetWindowTitle(window,config.windowTitle());
            EngineArgs parsed = EngineArgs.parse(args, config.defaultScene());
            if (!parsed.connect().isBlank() && !hasArg(args, "--scene=") && config.scenes().containsKey("world")) {
                parsed = new EngineArgs(parsed.smokeTest(), "world", parsed.debugView(), parsed.connect(),
                        parsed.clientUuid(), parsed.displayName(), parsed.accountToken());
            }

            DesktopInput input = new DesktopInput(window, clientSettings);
            platform = new DesktopPlatform(window, input, clientSettings, initialGraphics);
            DesktopPlatform activePlatform = platform;
            Renderer[] fullRenderer = rendererRef;
            NetworkStatusProvider[] network = new NetworkStatusProvider[]{NetworkStatusProvider.NONE};
            EngineLoop[] engineLoop = new EngineLoop[1];
            ContentBootstrapSession contentSession = config.contentBootstrapPlan().start(ExecutionProfile.CLIENT);
            ContentBootstrapResult[] content = new ContentBootstrapResult[1];
            BootstrapRenderer bootstrapRef=bootstrap;
            EngineArgs finalParsed=parsed;
            LoadingManager loading = new LoadingManager()
                    .add(new StartupTaskSpec("engine:configuration", LoadingPhase.CONFIGURATION,
                            "Loading configuration and input bindings",.6f,()->{}));
            for (var task : contentSession.tasks()) {
                loading.add(StartupTaskSpec.progressive(task.id().value(), loadingPhase(task.stage()), task.label(), task.weight(),
                        StartupTaskSpec.Execution.WORKER, progress -> {
                    contentSession.executeNext(progress);
                    if (contentSession.done()) content[0] = contentSession.result();
                }));
            }
            loading.add(StartupTaskSpec.progressive("engine:renderer_promotion", LoadingPhase.RENDERER,
                            "Promoting renderer and uploading immutable client assets",2.0f,
                            StartupTaskSpec.Execution.RENDER_THREAD, progress -> {
                                progress.indeterminate("Creating Vulkan pipelines and immutable GPU resources");
                                fullRenderer[0]=bootstrapRef.promote(activeAssets,uiFont,debugFont,
                                        gameTitleFont,config.particles().atlas(),progress);
                            }))
                    .add(new StartupTaskSpec("engine:network", LoadingPhase.NETWORK,
                            finalParsed.connect().isBlank()?"Preparing local session":"Starting network connection",.6f,
                            ()->network[0]=createNetworkProvider(finalParsed, content[0], clientSettings, localization,
                                    networkFactory)))
                    .add(StartupTaskSpec.progressive("engine:scenes", LoadingPhase.SCENES,"Creating game scenes",1.4f,
                            StartupTaskSpec.Execution.RENDER_THREAD,
                            progress->engineLoop[0]=new EngineLoop(config,finalParsed,activePlatform,fullRenderer[0],activeFonts,
                                    localization,network[0],content[0].registries(),progress,activeAssets,
                                    activeParticleEvents)));

            java.util.concurrent.Future<Boolean> workerStep=null;
            long[] lastNestedRender={0};
            while(!loading.done()&&!loading.failed()&&!glfwWindowShouldClose(window)){
                renderLoading(window,bootstrap,fullRenderer[0],loadingScreen,loading.snapshot());
                glfwPollEvents();
                if(workerStep!=null){
                    if(workerStep.isDone()){
                        try { workerStep.get(); }
                        catch (Exception error) { throw new IllegalStateException("Bootstrap worker failed",error); }
                        workerStep=null;
                    }
                }else if(loading.currentExecution()==StartupTaskSpec.Execution.WORKER){
                    workerStep=bootstrapWorker.submit((java.util.concurrent.Callable<Boolean>)loading::step);
                }else{
                    loading.step(()->{
                        long now=System.nanoTime();
                        if(now-lastNestedRender[0]<16_000_000L)return;
                        lastNestedRender[0]=now;
                        renderLoading(window,bootstrapRef,fullRenderer[0],loadingScreen,loading.snapshot());
                        glfwPollEvents();
                    });
                }
            }
            if(workerStep!=null&&!workerStep.isDone())workerStep.cancel(true);
            renderLoading(window,bootstrap,fullRenderer[0],loadingScreen,loading.snapshot());
            if(loading.failed()){
                int failureFrames=0;
                while(!glfwWindowShouldClose(window)&&(!finalParsed.smokeTest()||failureFrames++<120)){
                    glfwPollEvents();
                    renderLoading(window,bootstrap,fullRenderer[0],loadingScreen,loading.snapshot());
                }
                throw new IllegalStateException("Startup failed: "+loading.snapshot().error());
            }
            EngineLoop loop = engineLoop[0];
            int frames = loop.run();
            if (parsed.smokeTest()) System.out.println("[game-app] rendered " + parsed.scene() + " for " + frames + " frames");
        } finally {
            bootstrapWorker.shutdownNow();
            if (platform != null) platform.close();
            if (rendererRef[0] != null) rendererRef[0].close();
            else if (bootstrap != null) bootstrap.close();
            if (fonts != null) fonts.close();
            if (audioEngine != null) audioEngine.close();
            if (assetSnapshot != null) assetSnapshot.close();
            if (assetManager != null) assetManager.close();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static ResourcePath resourcePath(java.nio.file.Path path) {
        String value = path.toString().replace('\\', '/');
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException(
                    "Expected namespace-relative resource path: " + path);
        }
        return ResourcePath.of(
                ResourceDomain.ASSETS,
                value.substring(0, separator),
                value.substring(separator + 1));
    }

    private static java.util.List<String> availableDisplayResolutions() {
        long monitor = glfwGetPrimaryMonitor();
        org.lwjgl.glfw.GLFWVidMode.Buffer modes = monitor == 0 ? null : glfwGetVideoModes(monitor);
        if (modes == null) return java.util.List.of();
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (int index = 0; index < modes.limit(); index++) {
            org.lwjgl.glfw.GLFWVidMode mode = modes.get(index);
            values.add(mode.width() + "x" + mode.height());
        }
        return values.stream().sorted(java.util.Comparator.comparingInt(value -> {
            String[] dimensions = value.split("x", 2);
            return Integer.parseInt(dimensions[0]) * Integer.parseInt(dimensions[1]);
        })).toList();
    }

    private static long createConfiguredWindow(fr.tofuxia.app.ClientSettingsState.Graphics graphics, String windowTitle) {
        int[] requested = resolution(graphics.resolution());
        long primaryMonitor = glfwGetPrimaryMonitor();
        var videoMode = primaryMonitor == 0 ? null : glfwGetVideoMode(primaryMonitor);
        String mode = graphics.displayMode();
        boolean fullscreen = mode.equals("FULLSCREEN") && primaryMonitor != 0;
        boolean borderless = mode.equals("BORDERLESS");
        int width = borderless && videoMode != null ? videoMode.width() : requested[0];
        int height = borderless && videoMode != null ? videoMode.height() : requested[1];

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, borderless ? GLFW_FALSE : GLFW_TRUE);
        glfwWindowHint(GLFW_FOCUSED, GLFW_TRUE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_TRUE);
        glfwWindowHint(GLFW_SCALE_TO_MONITOR, GLFW_TRUE);
        glfwWindowHint(GLFW_SCALE_FRAMEBUFFER, GLFW_TRUE);
        long window = glfwCreateWindow(width, height, windowTitle, fullscreen ? primaryMonitor : 0, 0);
        if (window == 0) return 0;

        if (!fullscreen && primaryMonitor != 0) {
            int[] monitorX = new int[1], monitorY = new int[1];
            if (borderless) {
                glfwGetMonitorPos(primaryMonitor, monitorX, monitorY);
                glfwSetWindowPos(window, monitorX[0], monitorY[0]);
            } else {
                int[] workWidth = new int[1], workHeight = new int[1];
                glfwGetMonitorWorkarea(primaryMonitor, monitorX, monitorY, workWidth, workHeight);
                glfwSetWindowPos(window, monitorX[0] + Math.max(0, (workWidth[0] - width) / 2),
                        monitorY[0] + Math.max(0, (workHeight[0] - height) / 2));
            }
        }
        glfwShowWindow(window);
        glfwFocusWindow(window);
        return window;
    }

    private static int[] resolution(String value) {
        try {
            String[] dimensions = value.split("x", 2);
            return new int[]{Math.max(640, Integer.parseInt(dimensions[0])),
                    Math.max(360, Integer.parseInt(dimensions[1]))};
        } catch (RuntimeException ignored) {
            return new int[]{1280, 720};
        }
    }

    private static void renderLoading(long window,BootstrapRenderer bootstrap,Renderer renderer,LoadingScreen screen,
                                      LoadingManager.Snapshot snapshot){
        fr.tofuxia.app.DisplayMetrics display = displayMetrics(window);
        if(renderer!=null)renderer.renderLoadingUi(screen.build(display,snapshot));
        else bootstrap.render(screen.build(display,snapshot));
    }

    private static fr.tofuxia.app.DisplayMetrics displayMetrics(long window) {
        int[] windowWidth = new int[1], windowHeight = new int[1], framebufferWidth = new int[1], framebufferHeight = new int[1];
        float[] scaleX = new float[1], scaleY = new float[1];
        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
        glfwGetWindowContentScale(window, scaleX, scaleY);
        return new fr.tofuxia.app.DisplayMetrics(windowWidth[0], windowHeight[0], framebufferWidth[0], framebufferHeight[0],
                scaleX[0], scaleY[0]);
    }

    private static NetworkStatusProvider createNetworkProvider(EngineArgs args, ContentBootstrapResult content,
                                                               DesktopClientSettings clientSettings, Localization localization,
                                                               DesktopNetworkFactory networkFactory) {
        String connect = args.connect();
        if (connect == null || connect.isBlank()) return NetworkStatusProvider.NONE;
        return networkFactory.create(args, content, clientSettings, localization);
    }

    private static LoadingPhase loadingPhase(BootstrapStage stage) {
        return switch (stage) {
            case CONFIGURATION -> LoadingPhase.CONFIGURATION;
            case CONTENT_REGISTRATION -> LoadingPhase.CONTENT_REGISTRATION;
            case CONTENT_PROCESSING -> LoadingPhase.CONTENT_PROCESSING;
            case RENDERER -> LoadingPhase.RENDERER;
            case NETWORK -> LoadingPhase.NETWORK;
            case SCENES -> LoadingPhase.SCENES;
        };
    }

    private static boolean hasArg(String[] args, String prefix) {
        for (String arg : args) if (arg.startsWith(prefix)) return true;
        return false;
    }
}
