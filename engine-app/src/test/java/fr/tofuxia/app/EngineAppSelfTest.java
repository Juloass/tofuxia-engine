package fr.tofuxia.app;

public final class EngineAppSelfTest {
    public static void main(String[] args) {
        GameBuilder builder = new GameBuilder()
                .windowTitle("Test")
                .connectionBackground("test/background.png")
                .defaultScene("a")
                .contentModule(context -> context.task(io.github.juloass.content.ContentBootstrapTask.of(
                        "test:freeze", io.github.juloass.content.BootstrapStage.CONTENT_PROCESSING,
                        "Freeze test content", 1, java.util.Set.of(),
                        java.util.Set.of(io.github.juloass.content.ExecutionProfile.TEST,
                                io.github.juloass.content.ExecutionProfile.CLIENT), ignored -> {})))
                .scene("a", context -> new StubScene("a"))
                .scene("b", context -> new StubScene("b"));
        GameConfig config = builder.build();
        require(config.windowTitle().equals("Test"), "window title");
        require(config.connectionBackground().equals("test/background.png"), "connection background");
        require(config.defaultScene().equals("a"), "default scene");
        require(config.scenes().size() == 2, "scene count");
        EngineArgs parsed = EngineArgs.parse(new String[]{"--scene=b", "--debug-view=normals", "--smoke-test"}, config.defaultScene());
        require(parsed.smokeTest(), "smoke arg");
        require(parsed.scene().equals("b"), "scene arg");
        require(parsed.debugView() == fr.tofuxia.renderapi.DebugViewMode.NORMALS, "debug arg");
        require(EngineArgs.parse(new String[]{"--debug-view=selection-exterior"}, "world").debugView()
                == fr.tofuxia.renderapi.DebugViewMode.SELECTION_EXTERIOR, "selection outline debug arg");
        require(parsed.connect().isBlank(), "connect defaults offline");
        EngineArgs connected = EngineArgs.parse(new String[]{"--connect=127.0.0.1:7777",
                "--client-uuid=00000000-0000-0000-0000-000000000001", "--display-name=Tester"}, config.defaultScene());
        require(connected.connect().equals("127.0.0.1:7777"), "connect arg");
        require(connected.clientUuid().equals("00000000-0000-0000-0000-000000000001"), "client uuid arg");
        require(connected.displayName().equals("Tester"), "display name arg");
        require(connected.accountToken().equals("dev:00000000-0000-0000-0000-000000000001"),
                "development account token defaults from the legacy client UUID for migration");
        require(EngineArgs.parse(new String[]{"--account-token=dev:explicit"}, config.defaultScene()).accountToken()
                .equals("dev:explicit"), "explicit opaque account token arg");
        validatesConnectionLifecycle();
        validatesConnectionScreen();
        validatesLoadingManager();
        validatesKeyedJobs();
        validatesExplorationCamera();
        validatesRenderViewport();
        validatesFixedTickSimulationRunner();
        validatesSessionProfilerFinalization();
        System.out.println("[engine-app] checks passed");
    }

    private static void validatesSessionProfilerFinalization() {
        try {
            java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("tofuxia-session-profiler-test");
            EngineLoop.SessionProfiler profiler = EngineLoop.SessionProfiler.start(directory, 42, System.nanoTime());
            require(profiler != null, "session profiler starts without a hotkey");
            java.nio.file.Path file = profiler.file();
            profiler.record(new EngineLoop.FrameDiagnostic(
                            42, "test-scene", 16.6f, 12.0f, .2f, 3.0f, .4f, .5f, 4.0f, .1f, 1.5f, 2.0f,
                            1.0f, 0.0f, 2, 1, 24, 36, 4096, 3, 8192, 2, 4, 1024, 1, 2),
                    fr.tofuxia.ui.ProfilerSnapshot.of(12.0f,
                            new fr.tofuxia.ui.ProfilerSnapshot.Entry("frame", 12.0f,
                                    new fr.tofuxia.ui.ProfilerSnapshot.Entry("update", 3.0f))),
                    fr.tofuxia.renderer.RendererStats.RenderTimings.EMPTY);
            profiler.close(System.nanoTime());
            String trace = java.nio.file.Files.readString(file);
            require(trace.contains("[frames]") && trace.contains("[summary]"),
                    "session profiler finalizes the same trace on shutdown");
            require(trace.contains("frames=1") && trace.contains("dropped_rows=0"),
                    "session profiler summary records the whole test session");
            require(trace.contains("42\t") && trace.contains("test-scene")
                            && trace.contains("frame=12.0;frame/update=3.0")
                            && trace.contains("tree/frame/update\t1\t3.0\t3.0\t3.0"),
                    "session profiler streams frame, renderer, and nested profiler timings");
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(directory);
        } catch (java.io.IOException e) {
            throw new AssertionError("session profiler finalization", e);
        }
    }

    private static void validatesConnectionLifecycle() {
        require(ConnectionReadiness.resolve(true, NetworkWorldState.EMPTY) == AppState.CONNECTING,
                "remote client waits for authoritative world initialization");
        AccountRosterState emptyRoster = new AccountRosterState(true, "account", 4,
                java.util.List.of(), "", "");
        require(ConnectionReadiness.awaitingCharacterChoice(true, emptyRoster, 0),
                "authenticated empty roster is routed to character creation while connecting");
        AccountRosterState populatedRoster = new AccountRosterState(true, "account", 4,
                java.util.List.of(new AccountRosterState.CharacterSummary(
                        "character", "Hero", 1, 42, java.util.Map.of())), "", "");
        require(ConnectionReadiness.awaitingCharacterChoice(true, populatedRoster, 0),
                "authenticated unselected roster is routed to character selection while connecting");
        require(!ConnectionReadiness.awaitingCharacterChoice(true, populatedRoster, 42),
                "selected character resumes the world-loading lifecycle");
        require(!ConnectionReadiness.awaitingCharacterChoice(false, emptyRoster, 0),
                "offline clients do not enter the account roster flow");
    }

    private static void validatesConnectionScreen() {
        require(ConnectionScreen.retryable("Network: disconnected (ConnectException)"),
                "disconnected status enables retry");
        require(ConnectionScreen.retryable("Network: rejected invalid token"),
                "rejected status enables retry");
        require(!ConnectionScreen.retryable("Network: connecting localhost:7777"),
                "active connection attempt disables retry");
        var refused = ConnectionScreen.presentStatus("Network: disconnected (AnnotatedConnectException)");
        require(refused.title().equals("Failed to connect")
                        && refused.detail().equals("Connection refused · AnnotatedConnectException"),
                "transport failures use a friendly title and separate diagnostic line");
        var rejected = ConnectionScreen.presentStatus("Network: rejected invalid token");
        require(rejected.title().equals("Connection rejected") && rejected.detail().equals("Invalid token"),
                "server rejections preserve their explanation below a friendly title");
        var connecting = ConnectionScreen.presentStatus("Network: connecting localhost:7777");
        require(connecting.title().equals("Connecting…") && connecting.detail().equals("localhost:7777"),
                "pending connections separate the action from the endpoint");

        int[] attempts = {0};
        NetworkStatusProvider provider = new NetworkStatusProvider() {
            public String statusLine() { return "Network: disconnected"; }
            public boolean requestReconnect() { attempts[0]++; return true; }
            public void close() {}
        };
        ConnectionScreen screen = new ConnectionScreen("test/background.png");
        require(screen.attemptRetry(provider) && attempts[0] == 1, "retry invokes the network provider");
        require(Math.abs(screen.retryCooldown() - ConnectionScreen.RETRY_COOLDOWN_SECONDS) < .001f,
                "successful retry starts cooldown");
        require(!screen.attemptRetry(provider) && attempts[0] == 1, "cooldown blocks repeated retries");
        screen.advanceCooldown(ConnectionScreen.RETRY_COOLDOWN_SECONDS);
        require(screen.attemptRetry(provider) && attempts[0] == 2, "retry re-enables after cooldown");

        fr.tofuxia.ui.UiContext animatedUi = new fr.tofuxia.ui.UiContext(null);
        animatedUi.begin(fr.tofuxia.ui.UiInput.mouseOnly(0, 0, false, 0), 1280, 720);
        screen.draw(animatedUi, provider, 0);
        require(animatedUi.animator().activeCount() == 1, "countdown starts a keyed text pop");
        animatedUi.begin(fr.tofuxia.ui.UiInput.mouseOnly(0, 0, false, .04f), 1280, 720);
        screen.draw(animatedUi, provider, .04f);
        var pulse = animatedUi.animator().valueOrElse(
                "connection-screen/retry/label", fr.tofuxia.ui.UiAnimationProperties.TRANSFORM,
                io.github.juloass.uianimation.UiTransform.IDENTITY);
        require(pulse.scaleX() > 1, "countdown text visibly expands during the pop");
        animatedUi.begin(fr.tofuxia.ui.UiInput.mouseOnly(0, 0, false, .25f), 1280, 720);
        screen.draw(animatedUi, provider, .97f);
        require(animatedUi.animator().activeCount() == 1, "each changed countdown second starts one pop");
        animatedUi.setAccessibility(new fr.tofuxia.ui.UiAccessibilityPreferences(1, true, false));
        animatedUi.begin(fr.tofuxia.ui.UiInput.mouseOnly(0, 0, false, .25f), 1280, 720);
        screen.draw(animatedUi, provider, 1.01f);
        require(animatedUi.animator().activeCount() == 0,
                "reduced UI animations suppresses the countdown pop");

        fr.tofuxia.ui.UiRect wide = ConnectionScreen.coverUv(2560, 1080);
        require(wide.x() == 0 && wide.w() == 1 && wide.h() < 1,
                "wide screens crop the background vertically without stretching");
        fr.tofuxia.ui.UiRect tall = ConnectionScreen.coverUv(1024, 1024);
        require(tall.y() == 0 && tall.h() == 1 && tall.w() < 1,
                "tall screens crop the background horizontally without stretching");
    }

    private static void validatesExplorationCamera(){
        fr.tofuxia.render.Camera camera=fr.tofuxia.render.Camera.isometricRpg();
        require(Math.abs(camera.yaw()-(float)Math.toRadians(45))<.0001f,
                "exploration camera is diagonal to the block grid");
        org.joml.Vector3f offset=camera.position().sub(camera.target());
        require(Math.abs(offset.x-offset.z)<.001f&&offset.y>offset.x,
                "exploration camera has a strong downward pitch");
        require(Math.abs(camera.projection(1600,900).m23())>.5f,
                "exploration camera uses perspective projection");
        var cutaway = fr.tofuxia.renderapi.TerrainOcclusionCutaway.aroundPlayer(4.5f, 8.0f, -2.5f);
        require(cutaway.enabled() && cutaway.playerX() == 4.5f && cutaway.playerFeetY() == 8.0f
                        && cutaway.outerRadius() > cutaway.innerRadius(),
                "terrain cutaway carries a valid controlled-player visibility window");
        require(!fr.tofuxia.renderapi.TerrainOcclusionCutaway.DISABLED.enabled(),
                "terrain cutaway defaults to disabled outside a controlled world view");
    }

    private static void validatesRenderViewport() {
        fr.tofuxia.renderapi.RenderViewport full = fr.tofuxia.renderapi.RenderViewport.fullScreen(1280, 720);
        require(full.x() == 0 && full.y() == 0 && full.width() == 1280 && full.height() == 720,
                "full-screen viewport matches framebuffer");
        require(full.contains(0, 0) && full.contains(1279, 719), "viewport contains interior pixels");
        require(!full.contains(1280, 719) && !full.contains(1279, 720), "viewport rejects outside pixels");
        fr.tofuxia.renderapi.RenderViewport editor = new fr.tofuxia.renderapi.RenderViewport(72, 44, 948, 564);
        require(editor.localX(100) == 28 && editor.localY(80) == 36, "viewport local coordinates subtract origin");
        require(fr.tofuxia.renderapi.RenderViewport.lerp(full, editor, 0).equals(full), "viewport lerp starts at full");
        require(fr.tofuxia.renderapi.RenderViewport.lerp(full, editor, 1).equals(editor), "viewport lerp ends at editor");
    }

    private static void validatesLoadingManager() {
        java.util.ArrayList<String> order=new java.util.ArrayList<>();
        LoadingManager loading=new LoadingManager()
                .add(new StartupTaskSpec(LoadingPhase.CONFIGURATION,"config",1,()->order.add("config")))
                .add(new StartupTaskSpec(LoadingPhase.CONTENT_PROCESSING,"atlas",3,()->order.add("atlas")));
        require(loading.snapshot().task().equals("config")&&loading.progress()==0,"loading exposes next task");
        require(loading.step()&&Math.abs(loading.progress()-.25f)<.0001f,"loading uses task weights");
        require(loading.snapshot().task().equals("atlas"),"loading advances phase label");
        require(loading.step()&&loading.done()&&loading.progress()==1,"loading completes");
        require(order.equals(java.util.List.of("config","atlas")),"loading tasks stay ordered");

        java.util.concurrent.CountDownLatch reported=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        LoadingManager progressive=new LoadingManager().add(StartupTaskSpec.progressive(
                "test:bake",LoadingPhase.CONTENT_PROCESSING,"bake",1,
                StartupTaskSpec.Execution.WORKER,progress->{
                    progress.report(37,100,"states","Baking legal block states");
                    reported.countDown();
                    release.await();
                    progress.report(100,100,"states","Baking legal block states");
                }));
        Thread worker=Thread.startVirtualThread(progressive::step);
        try {
            require(reported.await(2,java.util.concurrent.TimeUnit.SECONDS),"progressive task reports while running");
            LoadingManager.Snapshot live=progressive.snapshot();
            require(live.taskDeterminate()&&live.taskCompleted()==37&&live.taskTotal()==100,
                    "loading snapshot exposes exact current-task counts");
            require(live.taskUnit().equals("states")&&live.taskDetail().contains("legal block states"),
                    "loading snapshot exposes current-task unit and detail");
            release.countDown();
            worker.join(2_000);
            require(progressive.done()&&!worker.isAlive(),"progressive task completes after live reporting");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } finally {
            release.countDown();
        }

        LoadingManager failed=new LoadingManager().add(new StartupTaskSpec(
                LoadingPhase.CONTENT_PROCESSING,"broken",1,()->{throw new java.io.IOException("bad model");}));
        require(!failed.step()&&failed.failed()&&failed.snapshot().error().contains("bad model"),
                "loading failure remains readable");
    }

    private static void validatesKeyedJobs() {
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (KeyedJobSystem<String, Integer, Integer> jobs = new KeyedJobSystem<>("job-test", 2)) {
            jobs.submit("section", 1, value -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return value;
            });
            try {
                require(running.await(2, java.util.concurrent.TimeUnit.SECONDS), "first keyed job starts");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            jobs.submit("section", 2, value -> value);
            long latest = jobs.submit("section", 3, value -> value);
            release.countDown();
            java.util.ArrayList<KeyedJobSystem.Completed<String, Integer>> completed = new java.util.ArrayList<>();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (completed.size() < 2 && System.nanoTime() < deadline) {
                completed.addAll(jobs.pollCompleted());
                Thread.onSpinWait();
            }
            require(completed.stream().noneMatch(result -> Integer.valueOf(2).equals(result.value())),
                    "pending keyed work is coalesced to latest snapshot");
            require(completed.stream().anyMatch(result -> result.generation() == latest && result.value() == 3),
                    "latest keyed job completes");
            require(jobs.stats().coalesced() == 1, "keyed job metrics count coalescing");
        }
    }

    private static void validatesFixedTickSimulationRunner() {
        FixedTickScene scene = new FixedTickScene();
        try (ClientSimulationRunner runner = new ClientSimulationRunner(NetworkStatusProvider.NONE)) {
            runner.setScene(scene);
            runner.submitInput(ClientInputSample.empty());
            sleepMillis(260);
            long ticks = scene.ticks();
            require(ticks >= 4 && ticks <= 7, "simulation runner advances near 20hz without render frames");
            runner.submitInput(new ClientInputSample(0.001f, 1.0f, true, 1, NetworkWorldState.EMPTY,
                    1.0f, 0.0f, false, false, 0.0f,
                    0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
                    false, false, false, -1, true, fr.tofuxia.renderapi.RenderViewport.fullScreen(64, 64)));
            runner.submitEvents(new ClientInputEvents(false, true, 3,
                    true, false, 1.0f, 2.0f, 3.0f, 0.0f, -1.0f, 0.0f, true));
            runner.submitInput(ClientInputSample.empty());
            long eventDeadline = System.nanoTime() + 2_000_000_000L;
            while (scene.lastHotbarSelection() != 3 && System.nanoTime() < eventDeadline) {
                Thread.onSpinWait();
            }
            require(Math.abs(scene.lastDeltaSeconds() - ClientSimulationRunner.FIXED_DELTA_SECONDS) < 0.0001f,
                    "simulation runner uses fixed tick dt");
            require(scene.lastHotbarSelection() == 3, "simulation runner drains queued hotbar input");
            require(scene.gameModeToggles() == 1, "simulation runner drains queued keybind input");
            require(scene.leftClicks() == 1, "simulation runner drains queued world click input");
            require(scene.lastClickRayOriginX() == 1.0f, "simulation runner preserves click ray from event frame");
            runner.submitEvents(new ClientInputEvents(false, true, -1,
                    false, false, 0, 0, 0, 0, 0, 0, false));
            runner.submitEvents(new ClientInputEvents(false, true, -1,
                    false, false, 0, 0, 0, 0, 0, 0, false));
            long repeatedEventDeadline = System.nanoTime() + 2_000_000_000L;
            while (scene.gameModeToggles() != 3 && System.nanoTime() < repeatedEventDeadline) {
                Thread.onSpinWait();
            }
            require(scene.gameModeToggles() == 3, "simulation runner preserves repeated edge events across ticks");
            require(runner.stats().totalLateTicks() >= runner.stats().lateTicks(),
                    "simulation runner separates cumulative and current lateness");
            require(runner.stats().snapshotAgeMillis() >= 0.0f, "simulation snapshot age is readable");
            ClientSimulationRunner.Stats alphaStart = runner.stats();
            float firstAlpha = alphaStart.renderAlpha();
            sleepMillis(12);
            float secondAlpha = alphaStart.renderAlpha();
            require(firstAlpha >= 0.0f && firstAlpha <= ClientSimulationRunner.MAX_RENDER_ALPHA
                            && secondAlpha >= firstAlpha && secondAlpha <= ClientSimulationRunner.MAX_RENDER_ALPHA,
                    "simulation render alpha advances monotonically between fixed ticks");
            long scheduled = 1_000_000_000L;
            ClientSimulationRunner.Stats earlyFinish = new ClientSimulationRunner.Stats(
                    1L, 1.0f, scheduled + 1_000_000L, scheduled, 0, 0L, 0);
            ClientSimulationRunner.Stats lateFinish = new ClientSimulationRunner.Stats(
                    1L, 9.0f, scheduled + 9_000_000L, scheduled, 0, 0L, 0);
            float expectedHalfTick = 0.5f;
            require(Math.abs(earlyFinish.renderAlpha(scheduled + 25_000_000L) - expectedHalfTick) < 0.0001f
                            && Math.abs(lateFinish.renderAlpha(scheduled + 25_000_000L) - expectedHalfTick) < 0.0001f,
                    "simulation render alpha follows the scheduled cadence, independent of tick duration");
            require(Math.abs(earlyFinish.renderAlpha(scheduled + 62_500_000L) - 1.25f) < 0.0001f,
                    "simulation render alpha extrapolates through a short late-tick gap");
            require(Math.abs(earlyFinish.renderAlpha(scheduled + 100_000_000L)
                            - ClientSimulationRunner.MAX_RENDER_ALPHA) < 0.0001f,
                    "simulation render extrapolation remains bounded");
        }
        try (ClientSimulationRunner bounded = new ClientSimulationRunner(NetworkStatusProvider.NONE)) {
            ClientInputEvents event = new ClientInputEvents(false, true, -1,
                    false, false, 0, 0, 0, 0, 0, 0, false);
            for (int index = 0; index < 256; index++) bounded.submitEvents(event);
            boolean overflow = false;
            try { bounded.submitEvents(event); }
            catch (IllegalStateException expected) { overflow = true; }
            require(overflow, "simulation input reports capacity overflow without eviction");
        }
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private record StubScene(String id) implements GameScene {
        public String title() { return id; }
        public java.util.List<ScenePlacement> placements() { return java.util.List.of(); }
        public fr.tofuxia.renderer.Environment environment(float timeSeconds) { return fr.tofuxia.renderer.Environment.warmDay(); }
    }

    private static final class FixedTickScene implements GameScene {
        private final java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();
        private volatile float lastDeltaSeconds;
        private volatile int lastHotbarSelection = -1;
        private volatile int gameModeToggles;
        private volatile int leftClicks;
        private volatile float lastClickRayOriginX;

        public String id() { return "fixed"; }
        public String title() { return "fixed"; }
        public java.util.List<ScenePlacement> placements() { return java.util.List.of(); }
        public fr.tofuxia.renderer.Environment environment(float timeSeconds) { return fr.tofuxia.renderer.Environment.warmDay(); }
        public boolean usesFixedTickSimulation() { return true; }

        public void tick(SceneTickContext context) {
            ticks.incrementAndGet();
            lastDeltaSeconds = context.fixedDeltaSeconds();
            if (context.hotbarSelection() >= 0) lastHotbarSelection = context.hotbarSelection();
            if (context.gameModeToggle()) gameModeToggles++;
            if (context.leftMouseClicked()) {
                leftClicks++;
                lastClickRayOriginX = context.cursorRayOriginX();
            }
        }

        long ticks() {
            return ticks.get();
        }

        float lastDeltaSeconds() {
            return lastDeltaSeconds;
        }

        int lastHotbarSelection() {
            return lastHotbarSelection;
        }

        int gameModeToggles() {
            return gameModeToggles;
        }

        int leftClicks() {
            return leftClicks;
        }

        float lastClickRayOriginX() {
            return lastClickRayOriginX;
        }
    }
}
