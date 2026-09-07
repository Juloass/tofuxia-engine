package fr.tofuxia.app;

import fr.tofuxia.ui.ProfilerSnapshot;

import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientSimulationRunner implements AutoCloseable {
    public static final float FIXED_DELTA_SECONDS = 0.05f;
    private static final long FIXED_DELTA_NANOS = 50_000_000L;
    public static final float MAX_RENDER_ALPHA = 1.5f;
    private static final int MAX_CATCH_UP_TICKS = 3;

    private final NetworkStatusProvider network;
    private final AtomicReference<ClientInputSample> latestInput =
            new AtomicReference<>(ClientInputSample.empty());
    private final Object eventsLock = new Object();
    private static final int MAX_PENDING_EVENT_FRAMES = 256;
    private final ArrayDeque<ClientInputEvents> pendingEvents = new ArrayDeque<>();
    private final AtomicReference<GameScene> scene = new AtomicReference<>();
    private final Thread thread;
    private final ScheduledExecutorService watchdog;
    private volatile boolean running = true;
    private volatile Stats stats = Stats.EMPTY;
    private volatile GameScene activeTickScene;
    private volatile long activeTickStartedNanos;
    private volatile long lastWatchdogReportNanos;
    private long tickIndex;
    private long nextTickNanos;

    public ClientSimulationRunner(NetworkStatusProvider network) {
        this.network = network == null ? NetworkStatusProvider.NONE : network;
        this.nextTickNanos = System.nanoTime() + FIXED_DELTA_NANOS;
        this.thread = new Thread(this::runLoop, "tofuxia-client-simulation");
        this.thread.setDaemon(true);
        this.thread.start();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread watcher = new Thread(runnable, "tofuxia-client-simulation-watchdog");
            watcher.setDaemon(true);
            return watcher;
        });
        this.watchdog.scheduleAtFixedRate(this::reportSlowTick, 1L, 1L, TimeUnit.SECONDS);
    }

    public void setScene(GameScene next) {
        scene.set(next != null && next.usesFixedTickSimulation() ? next : null);
    }

    public void submitInput(ClientInputSample input) {
        if (input != null) latestInput.set(input);
    }

    public void submitEvents(ClientInputEvents events) {
        if (events == null || !events.hasEvents()) return;
        synchronized (eventsLock) {
            if (pendingEvents.size() == MAX_PENDING_EVENT_FRAMES) {
                throw new IllegalStateException("client input event capacity was exceeded");
            }
            pendingEvents.addLast(events);
        }
    }

    public Stats stats() {
        return stats;
    }

    public List<ProfilerSnapshot.Entry> profilerEntries() {
        Stats snapshot = stats;
        float snapshotAgeMillis = snapshot.snapshotAgeMillis();
        return List.of(new ProfilerSnapshot.Entry("client simulation runner",
                snapshot.lastTickMillis(),
                new ProfilerSnapshot.Entry("tick index " + snapshot.tickIndex(), 0.0f),
                new ProfilerSnapshot.Entry("snapshot age %.2fms".formatted(snapshotAgeMillis), snapshotAgeMillis),
                new ProfilerSnapshot.Entry("late ticks +" + snapshot.lateTicks(), 0.0f),
                new ProfilerSnapshot.Entry("dropped catch-up +" + snapshot.droppedCatchUpTicks(), 0.0f)));
    }

    private void runLoop() {
        while (running) {
            long now = System.nanoTime();
            long sleepNanos = nextTickNanos - now;
            if (sleepNanos > 0L) {
                sleepBriefly(sleepNanos);
                continue;
            }
            int ticks = 0;
            int late = 0;
            while (running && now >= nextTickNanos && ticks < MAX_CATCH_UP_TICKS) {
                if (now - nextTickNanos > FIXED_DELTA_NANOS) late++;
                runTick(nextTickNanos, late);
                ticks++;
                nextTickNanos += FIXED_DELTA_NANOS;
                now = System.nanoTime();
            }
            int dropped = 0;
            while (running && now >= nextTickNanos) {
                nextTickNanos += FIXED_DELTA_NANOS;
                dropped++;
            }
            if (dropped > 0) {
                Stats previous = stats;
                stats = previous.withDropped(previous.droppedCatchUpTicks() + dropped);
            }
        }
    }

    private void runTick(long scheduledTickNanos, int lateTicks) {
        GameScene active = scene.get();
        if (active == null) return;
        ClientInputSample input = latestInput.get();
        ClientInputEvents events = drainEvents();
        boolean clickEvent = events.leftMouseClicked() || events.rightMouseClicked();
        AuthoritativePlayerState correction = network.consumeLatestCorrection();
        SceneTickContext context = new SceneTickContext(++tickIndex, FIXED_DELTA_SECONDS,
                input.renderTimeSeconds(), input.animate(), input.lightPreset(), input.networkWorld(),
                input.moveX(), input.moveZ(), events.combatToggle(), input.diagnosticsVisible(),
                input.cameraYawRadians(),
                clickEvent ? events.clickRayOriginX() : input.cursorRayOriginX(),
                clickEvent ? events.clickRayOriginY() : input.cursorRayOriginY(),
                clickEvent ? events.clickRayOriginZ() : input.cursorRayOriginZ(),
                clickEvent ? events.clickRayDirectionX() : input.cursorRayDirectionX(),
                clickEvent ? events.clickRayDirectionY() : input.cursorRayDirectionY(),
                clickEvent ? events.clickRayDirectionZ() : input.cursorRayDirectionZ(),
                events.leftMouseClicked(), events.rightMouseClicked(), events.gameModeToggle(), events.hotbarSelection(),
                network, correction, network.playerEntityId(),
                clickEvent ? events.clickInWorldViewport() : input.cursorInWorldViewport(),
                input.worldViewport());
        long started = System.nanoTime();
        activeTickScene = active;
        activeTickStartedNanos = started;
        lastWatchdogReportNanos = 0L;
        try {
            active.tick(context);
        } finally {
            long ended = System.nanoTime();
            activeTickStartedNanos = 0L;
            activeTickScene = null;
            Stats previous = stats;
            stats = new Stats(tickIndex, (ended - started) / 1_000_000.0f, ended, scheduledTickNanos,
                    lateTicks, previous.totalLateTicks() + lateTicks, previous.droppedCatchUpTicks());
            float elapsedMillis = (ended - started) / 1_000_000.0f;
            if (elapsedMillis >= 250.0f) {
                System.out.printf("[game-client][simulation] slow tick=%d scene=%s duration=%.1fms activity=%s%n",
                        tickIndex, active.id(), elapsedMillis, active.diagnosticActivity());
            }
        }
    }

    private void reportSlowTick() {
        long started = activeTickStartedNanos;
        GameScene active = activeTickScene;
        if (!running || started == 0L || active == null) return;
        long now = System.nanoTime();
        long elapsed = now - started;
        if (elapsed < 1_000_000_000L || now - lastWatchdogReportNanos < 5_000_000_000L) return;
        lastWatchdogReportNanos = now;
        System.out.printf("[game-client][watchdog] simulation tick=%d scene=%s running=%.1fs activity=%s%n",
                tickIndex, active.id(), elapsed / 1_000_000_000.0, active.diagnosticActivity());
    }

    private ClientInputEvents drainEvents() {
        synchronized (eventsLock) {
            ClientInputEvents events = pendingEvents.pollFirst();
            return events == null ? ClientInputEvents.EMPTY : events;
        }
    }

    private static void sleepBriefly(long sleepNanos) {
        try {
            long millis = Math.max(0L, sleepNanos / 1_000_000L);
            int nanos = (int) Math.max(0L, sleepNanos - millis * 1_000_000L);
            Thread.sleep(millis, nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        watchdog.shutdownNow();
        thread.interrupt();
        try {
            thread.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record Stats(long tickIndex, float lastTickMillis, long lastTickEndedNanos, long lastTickScheduledNanos,
                        int lateTicks, long totalLateTicks, int droppedCatchUpTicks) {
        private static final Stats EMPTY = new Stats(0L, 0.0f, 0L, 0L, 0, 0, 0);

        public float snapshotAgeMillis() {
            if (lastTickEndedNanos == 0L) return 0.0f;
            return Math.max(0.0f, (System.nanoTime() - lastTickEndedNanos) / 1_000_000.0f);
        }

        public float renderAlpha() {
            return renderAlpha(System.nanoTime());
        }

        float renderAlpha(long renderNanos) {
            if (lastTickScheduledNanos == 0L) return 0.0f;
            float alpha = (renderNanos - lastTickScheduledNanos) / (float) FIXED_DELTA_NANOS;
            return Math.max(0.0f, Math.min(MAX_RENDER_ALPHA, alpha));
        }

        private Stats withDropped(int dropped) {
            return new Stats(tickIndex, lastTickMillis, lastTickEndedNanos, lastTickScheduledNanos,
                    lateTicks, totalLateTicks, dropped);
        }
    }
}
