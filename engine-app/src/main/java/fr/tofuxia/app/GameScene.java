package fr.tofuxia.app;

import fr.tofuxia.renderer.Environment;
import fr.tofuxia.renderer.Renderer;
import fr.tofuxia.renderapi.RenderViewport;
import fr.tofuxia.renderapi.SelectionOutlineData;
import fr.tofuxia.renderapi.TerrainOcclusionCutaway;
import fr.tofuxia.ui.ProfilerSnapshot;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

public interface GameScene {
    String id();
    String title();
    default List<SceneMesh> dynamicMeshes() { return List.of(); }
    default SelectionOutlineData selectionOutline() { return SelectionOutlineData.EMPTY; }
    default TerrainOcclusionCutaway terrainOcclusionCutaway() { return TerrainOcclusionCutaway.DISABLED; }
    List<ScenePlacement> placements();
    Environment environment(float timeSeconds);

    default void load(SceneLoadContext context) {}
    default void unload(SceneUnloadContext context) {}
    default RenderViewport prepareFrameLayout(SceneFrameLayoutContext context) {
        return RenderViewport.fullScreen(context.framebufferWidth(), context.framebufferHeight());
    }
    default boolean usesFixedTickSimulation() { return false; }
    default void prepareRenderFrame(float fixedTickAlpha) {}
    default void beginRenderFrame() {}
    default void endRenderFrame() {}
    /** Submit pre-uploaded compound or animated assets after ordinary placements. */
    default void submitModels(Renderer renderer) {}
    default void updatePointerHover(PointerHoverContext context) {}
    default CursorStyle cursorStyle() { return CursorStyle.ARROW; }
    default void tick(SceneTickContext context) { update(context.asLegacyUpdateContext()); }
    default void update(SceneUpdateContext context) {}
    default List<ProfilerSnapshot.Entry> updateProfilerEntries() { return List.of(); }
    default List<ProfilerSnapshot.Entry> rendererInputProfilerEntries() { return List.of(); }
    /** Short, lock-free description used by the slow-tick watchdog. */
    default String diagnosticActivity() { return ""; }
    default void ui(SceneUiContext context) {}
    default boolean suppressWorldTimeHud() { return false; }
    default boolean particles() { return false; }
    default Vector3f cameraTarget() { return null; }
    default Matrix4f transform(ScenePlacement placement, float timeSeconds) {
        return new Matrix4f(placement.transform());
    }
    default String[] diagnostics(SceneDiagnosticsContext context) {
        return new String[0];
    }
}
