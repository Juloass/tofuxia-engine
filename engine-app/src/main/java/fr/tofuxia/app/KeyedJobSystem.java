package fr.tofuxia.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Coalescing derived-work executor. A key has at most one running job and one
 * latest pending snapshot. Workers never receive mutable live state.
 */
public final class KeyedJobSystem<K, S, R> implements AutoCloseable {
    private final ExecutorService workers;
    private final Map<K, Slot<S, R>> slots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Completed<K, R>> completed = new ConcurrentLinkedQueue<>();
    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public KeyedJobSystem(String threadPrefix, int workerCount) {
        AtomicLong threadIds = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + "-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        workers = Executors.newFixedThreadPool(Math.max(1, workerCount), factory);
    }

    public long submit(K key, S immutableSnapshot, Function<S, R> work) {
        long generation = generations.incrementAndGet();
        submitted.incrementAndGet();
        Slot<S, R> slot = slots.computeIfAbsent(key, ignored -> new Slot<>());
        boolean launch;
        synchronized (slot) {
            if (slot.pending != null) coalesced.incrementAndGet();
            slot.latestGeneration = generation;
            slot.pending = new Pending<>(generation, immutableSnapshot, work);
            launch = !slot.running;
            if (launch) slot.running = true;
        }
        if (launch) workers.execute(() -> drain(key, slot));
        return generation;
    }

    public void cancel(K key) {
        Slot<S, R> slot = slots.get(key);
        if (slot == null) return;
        synchronized (slot) {
            slot.latestGeneration = generations.incrementAndGet();
            slot.pending = null;
        }
    }

    public List<Completed<K, R>> pollCompleted() {
        return pollCompleted(Integer.MAX_VALUE);
    }

    public List<Completed<K, R>> pollCompleted(int maxResults) {
        ArrayList<Completed<K, R>> out = new ArrayList<>();
        int limit = Math.max(0, maxResults);
        for (Completed<K, R> result; out.size() < limit && (result = completed.poll()) != null;) out.add(result);
        return List.copyOf(out);
    }

    public boolean isCurrent(K key, long generation) {
        Slot<S, R> slot = slots.get(key);
        if (slot == null) return false;
        synchronized (slot) {
            return slot.latestGeneration == generation;
        }
    }

    public int outstandingKeys() {
        int count = 0;
        for (Slot<S, R> slot : slots.values()) {
            synchronized (slot) {
                if (slot.running || slot.pending != null) count++;
            }
        }
        return count;
    }

    public Stats stats() {
        return new Stats(submitted.get(), coalesced.get(), finished.get(), failed.get(), outstandingKeys());
    }

    private void drain(K key, Slot<S, R> slot) {
        while (true) {
            Pending<S, R> pending;
            synchronized (slot) {
                pending = slot.pending;
                slot.pending = null;
                if (pending == null) {
                    slot.running = false;
                    return;
                }
            }
            long started = System.nanoTime();
            try {
                R value = pending.work.apply(pending.snapshot);
                completed.add(new Completed<>(key, pending.generation, value,
                        System.nanoTime() - started, null));
                finished.incrementAndGet();
            } catch (Throwable error) {
                completed.add(new Completed<>(key, pending.generation, null,
                        System.nanoTime() - started, error));
                failed.incrementAndGet();
            }
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
        slots.clear();
        completed.clear();
    }

    public record Completed<K, R>(K key, long generation, R value, long elapsedNanos, Throwable error) {}
    public record Stats(long submitted, long coalesced, long completed, long failed, int outstandingKeys) {}
    private record Pending<S, R>(long generation, S snapshot, Function<S, R> work) {}

    private static final class Slot<S, R> {
        boolean running;
        long latestGeneration;
        Pending<S, R> pending;
    }
}
