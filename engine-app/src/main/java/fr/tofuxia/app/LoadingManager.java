package fr.tofuxia.app;

import java.util.ArrayList;
import java.util.List;
import io.github.juloass.content.WorkProgress;

/** Deterministic weighted startup task runner; callers render between {@link #step()} calls. */
public final class LoadingManager {
    private final List<StartupTaskSpec> tasks = new ArrayList<>();
    private float totalWeight;
    private float completedWeight;
    private int cursor;
    private LoadingPhase phase = LoadingPhase.CONFIGURATION;
    private String currentTaskId = "engine:opening";
    private String currentTask = "Opening";
    private Throwable failure;
    private boolean started;
    private boolean active;
    private volatile long taskCompleted;
    private volatile long taskTotal;
    private volatile String taskUnit = "";
    private volatile String taskDetail = "";

    public synchronized LoadingManager add(StartupTaskSpec task) {
        if (started) throw new IllegalStateException("Cannot add loading tasks after execution starts");
        tasks.add(task);
        totalWeight += task.weight();
        return this;
    }

    public LoadingManager addAll(List<StartupTaskSpec> values) {
        values.forEach(this::add);
        return this;
    }

    public boolean step() { return step(() -> {}); }

    /** Executes one task. The pulse runs on the calling thread after real progress checkpoints. */
    public boolean step(Runnable pulse) {
        StartupTaskSpec task;
        synchronized (this) {
            if (done() || failure != null) return false;
            if (active) throw new IllegalStateException("A startup task is already executing");
            started = true;
            active = true;
            task = tasks.get(cursor);
            phase = task.phase();
            currentTaskId = task.id();
            currentTask = task.label();
            clearTaskProgress();
        }
        WorkProgress reporter = (completed, total, unit, detail) -> {
            taskTotal = Math.max(0, total);
            taskCompleted = taskTotal == 0 ? 0 : Math.clamp(completed, 0, taskTotal);
            taskUnit = unit == null ? "" : unit;
            taskDetail = detail == null ? "" : detail;
            pulse.run();
        };
        try {
            task.action().run(reporter);
            synchronized (this) {
                completedWeight += task.weight();
                cursor++;
                active = false;
                clearTaskProgress();
            }
            return true;
        } catch (Throwable error) {
            synchronized (this) {
                failure = error;
                active = false;
            }
            return false;
        }
    }

    public synchronized boolean done() { return cursor >= tasks.size(); }
    public synchronized boolean failed() { return failure != null; }
    public synchronized StartupTaskSpec.Execution currentExecution() {
        return done() ? StartupTaskSpec.Execution.WORKER : tasks.get(cursor).execution();
    }
    public synchronized float progress() { return totalWeight <= 0 ? 1 : Math.min(1, completedWeight / totalWeight); }
    public synchronized Snapshot snapshot() {
        LoadingPhase shownPhase = !failed() && !done() ? tasks.get(cursor).phase() : phase;
        String shownTaskId = !failed() && !done() ? tasks.get(cursor).id() : currentTaskId;
        String shownTask = !failed() && !done() ? tasks.get(cursor).label() : currentTask;
        long completed = taskCompleted, total = taskTotal;
        return new Snapshot(AppState.BOOT_LOADING, shownPhase, shownTaskId, shownTask, progress(),
                failure == null ? "" : readable(failure), cursor, tasks.size(), total > 0,
                completed, total, taskUnit, taskDetail);
    }

    private void clearTaskProgress() {
        taskCompleted = 0;
        taskTotal = 0;
        taskUnit = "";
        taskDetail = "";
    }

    private static String readable(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public record Snapshot(AppState state, LoadingPhase phase, String taskId, String task, float progress,
                           String error, int completedTasks, int totalTasks, boolean taskDeterminate,
                           long taskCompleted, long taskTotal, String taskUnit, String taskDetail) {
        public Snapshot(AppState state, LoadingPhase phase, String taskId, String task, float progress,
                        String error, int completedTasks, int totalTasks) {
            this(state, phase, taskId, task, progress, error, completedTasks, totalTasks,
                    false, 0, 0, "", "");
        }
    }
}
