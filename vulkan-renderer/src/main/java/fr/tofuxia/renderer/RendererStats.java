package fr.tofuxia.renderer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Per-frame renderer counters plus persistent cache stats, formatted for the
 * debug overlay.
 */
public final class RendererStats {
    private final Map<PassId, Integer> itemsPerPass = new EnumMap<>(PassId.class);
    private int drawCalls;
    private int pipelineBinds;
    private int uiDrawCalls;
    private int uiSurfaceCount;
    private int uiCompositeSurfaceCount;
    private int uiClippedBatchCount;
    private int uiDescriptorSwitches;
    private int uiPipelineSwitches;
    private double cpuFrameMillis;
    private RenderTimings timings = RenderTimings.EMPTY;

    private int pipelineCount;
    private int variantCount;
    private int textureCount;
    private int textureFallbacks;
    private int meshCount;
    private int materialCount;

    void beginFrame() {
        itemsPerPass.clear();
        drawCalls = 0;
        pipelineBinds = 0;
        uiDrawCalls=0; uiSurfaceCount=0; uiCompositeSurfaceCount=0; uiClippedBatchCount=0;
        uiDescriptorSwitches=0; uiPipelineSwitches=0;
        timings = RenderTimings.EMPTY;
    }

    void countPass(PassId pass, int items) {
        itemsPerPass.put(pass, items);
    }

    void countDraw() {
        drawCalls++;
    }

    void countDraws(int count) {
        drawCalls += count;
    }

    void countPipelineBind() {
        pipelineBinds++;
    }

    void countPipelineBinds(int count) {
        pipelineBinds += count;
    }

    void countUi(int draws,int surfaces,int composites,int clippedBatches,int descriptorSwitches,int pipelineSwitches){
        uiDrawCalls+=Math.max(0,draws); uiSurfaceCount=Math.max(0,surfaces);
        uiCompositeSurfaceCount=Math.max(0,composites); uiClippedBatchCount=Math.max(0,clippedBatches);
        uiDescriptorSwitches+=Math.max(0,descriptorSwitches); uiPipelineSwitches+=Math.max(0,pipelineSwitches);
    }

    void finishFrame(double cpuMillis, int pipelines, int variants, int textures, int fallbacks,
                     int meshes, int materials) {
        this.cpuFrameMillis = cpuMillis;
        this.pipelineCount = pipelines;
        this.variantCount = variants;
        this.textureCount = textures;
        this.textureFallbacks = fallbacks;
        this.meshCount = meshes;
        this.materialCount = materials;
    }

    void finishTimings(RenderTimings timings) {
        this.timings = timings == null ? RenderTimings.EMPTY : timings;
    }

    public RenderTimings timings() {
        return timings;
    }

    public int drawCalls() {
        return drawCalls;
    }

    public int pipelineBinds() {
        return pipelineBinds;
    }

    public int uiDrawCalls(){return uiDrawCalls;} public int uiSurfaceCount(){return uiSurfaceCount;}
    public int uiCompositeSurfaceCount(){return uiCompositeSurfaceCount;}
    public int uiClippedBatchCount(){return uiClippedBatchCount;}
    public int uiDescriptorSwitches(){return uiDescriptorSwitches;}
    public int uiPipelineSwitches(){return uiPipelineSwitches;}

    public int items(PassId pass) {
        return itemsPerPass.getOrDefault(pass, 0);
    }

    /** Lines for the debug overlay. */
    public List<String> overlayLines() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("frame %.2f ms | draws %d | pipeline binds %d", cpuFrameMillis, drawCalls, pipelineBinds));
        StringBuilder passes = new StringBuilder("passes ");
        for (PassId pass : PassId.values()) {
            if (!pass.acceptsSubmissions()) continue;
            passes.append(pass.name().toLowerCase()).append('=').append(items(pass)).append(' ');
        }
        lines.add(passes.toString().strip());
        lines.add("pipelines " + pipelineCount + " | shader variants " + variantCount
                + " | materials " + materialCount);
        lines.add("textures " + textureCount + " (fallback hits " + textureFallbacks + ") | meshes " + meshCount);
        lines.add("ui draws "+uiDrawCalls+" | surfaces "+uiSurfaceCount+" (composite "+uiCompositeSurfaceCount+")"
                +" | clipped batches "+uiClippedBatchCount+" | descriptors "+uiDescriptorSwitches+" | pipelines "+uiPipelineSwitches);
        return lines;
    }

    public record RenderTimings(
            double recreateSwapchainMs,
            double waitPreviousFrameFenceMs,
            double acquireSwapchainImageMs,
            double resetFrameFenceMs,
            double resetFrameCommandBufferMs,
            double beginFrameCommandBufferMs,
            double rendererBeginStateMs,
            double retireMeshesMs,
            double updateGlobalUniformsMs,
            double prepareQueueMs,
            double renderPrepWorkerCpuMs,
            double prepareAppDrawsMs,
            double waitPrepareWorkerMs,
            double prepareMainDrawsMs,
            double prepareShadowDrawsMs,
            double recordSecondariesMs,
            double submitSecondaryJobsMs,
            double waitSecondaryWorkersMs,
            double secondaryWorkerCpuMs,
            double shadowPassMs,
            double beginMainPassMs,
            double executeMainSecondariesMs,
            double statsMs,
            double overlayRecordMs,
            double executeOverlayMs,
            double endRenderPassMs,
            double endCommandBufferMs,
            double queueSubmitMs,
            double presentMs,
            double frameIndexAdvanceMs,
            int mainDraws,
            int shadowDraws,
            int pipelineBinds
    ) {
        public static final RenderTimings EMPTY = new RenderTimings(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
