package fr.tofuxia.renderer;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** CPU-only visibility, sorting, and immutable pass-batch preparation. */
final class RenderPreparationWorker implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tofuxia-render-prepare");
        thread.setDaemon(true);
        return thread;
    });

    CompletableFuture<Plan> prepare(Map<PassId, List<RenderItem>> snapshot, Matrix4f viewProjection) {
        Matrix4f matrix = new Matrix4f(viewProjection);
        return CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime();
            Plan plan = build(snapshot, matrix);
            return plan.withWorkerMillis((System.nanoTime() - started) / 1_000_000.0);
        }, worker);
    }

    static Plan build(Map<PassId, List<RenderItem>> snapshot, Matrix4f viewProjection) {
        FrustumIntersection frustum = new FrustumIntersection(viewProjection);
        EnumMap<PassId, List<RenderItem>> passes = new EnumMap<>(PassId.class);
        int submitted = 0;
        int culled = 0;
        Comparator<RenderItem> byState = Comparator
                .comparingInt((RenderItem item) -> PipelineKey.of(item.material, item.mesh.layout()).variant().featureMask())
                .thenComparing(item -> item.material.blendMode())
                .thenComparing(item -> item.material.cullMode())
                .thenComparing(item -> item.mesh.layout())
                .thenComparing(item -> item.material.baseColorTexture(), Comparator.nullsFirst(String::compareTo))
                .thenComparingLong(item -> item.mesh.vertexBuffer() == null ? 0L : item.mesh.vertexBuffer().buffer())
                .thenComparingLong(item -> item.mesh.vertexBufferOffset())
                .thenComparingLong(item -> item.mesh.indexBufferOffset());
        Comparator<RenderItem> backToFront = Comparator.comparingDouble((RenderItem item) -> -item.viewDepth);
        for (PassId pass : PassId.values()) {
            ArrayList<RenderItem> visible = new ArrayList<>();
            for (RenderItem item : snapshot.getOrDefault(pass, List.of())) {
                submitted++;
                if (visible(item, frustum)) visible.add(item);
                else culled++;
            }
            if (pass == PassId.OPAQUE || pass == PassId.ALPHA_CUTOUT) visible.sort(byState);
            if (pass == PassId.TRANSPARENT || pass == PassId.BILLBOARDS) visible.sort(backToFront);
            passes.put(pass, List.copyOf(visible));
        }
        return new Plan(Map.copyOf(passes), submitted, culled, 0.0);
    }

    private static boolean visible(RenderItem item, FrustumIntersection frustum) {
        GpuMesh.BoundingSphere bounds = item.mesh.bounds();
        Vector3f center = item.transform.transformPosition(
                new Vector3f(bounds.x(), bounds.y(), bounds.z()));
        Vector3f scale = item.transform.getScale(new Vector3f());
        float animationMargin = switch (item.mesh.layout()) {
            case SKINNED -> 2.0f;
            case VOXEL -> 1.35f;
            default -> 1.0f;
        };
        float radius = bounds.radius() * animationMargin * Math.max(scale.x, Math.max(scale.y, scale.z));
        return frustum.testSphere(center.x, center.y, center.z, radius);
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    record Plan(Map<PassId, List<RenderItem>> passes, int submitted, int culled, double workerMs) {
        List<RenderItem> items(PassId pass) {
            return passes.getOrDefault(pass, List.of());
        }

        Plan withWorkerMillis(double workerMs) {
            return new Plan(passes, submitted, culled, workerMs);
        }
    }
}
