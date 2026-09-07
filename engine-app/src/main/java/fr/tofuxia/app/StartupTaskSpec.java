package fr.tofuxia.app;

import io.github.juloass.content.WorkProgress;

public record StartupTaskSpec(String id, LoadingPhase phase, String label, float weight,
                              Execution execution, ProgressTask action) {
    public StartupTaskSpec {
        if (id == null || id.isBlank() || phase == null || label == null || label.isBlank()
                || weight <= 0 || execution == null || action == null) {
            throw new IllegalArgumentException("Invalid startup task");
        }
    }

    public StartupTaskSpec(String id, LoadingPhase phase, String label, float weight, CheckedTask action) {
        this(id, phase, label, weight, Execution.WORKER, ignored -> action.run());
    }

    public StartupTaskSpec(LoadingPhase phase, String label, float weight, CheckedTask action) {
        this("engine:" + label.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_"),
                phase, label, weight, Execution.WORKER, ignored -> action.run());
    }

    public static StartupTaskSpec progressive(String id, LoadingPhase phase, String label, float weight,
                                              Execution execution, ProgressTask action) {
        return new StartupTaskSpec(id, phase, label, weight, execution, action);
    }

    public enum Execution { WORKER, RENDER_THREAD }

    @FunctionalInterface
    public interface CheckedTask {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface ProgressTask {
        void run(WorkProgress progress) throws Exception;
    }
}
