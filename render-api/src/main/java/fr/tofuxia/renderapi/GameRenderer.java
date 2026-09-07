package fr.tofuxia.renderapi;

import fr.tofuxia.render.Camera;
import fr.tofuxia.render.MeshData;
import fr.tofuxia.render.ParticleMeshData;
import fr.tofuxia.render.RendererStatus;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.terrain.TerrainData;

/**
 * App-facing renderer contract. Runtime and application code should target this API
 * instead of the Vulkan implementation class.
 */
public interface GameRenderer extends AutoCloseable {
    void uploadTerrain(MeshData terrain, MeshData water, MeshData scene);

    void uploadTerrain(TerrainData terrainData, MeshData terrain, MeshData water, MeshData scene);

    void uploadOverlay(MeshData overlay);

    void uploadDynamicShadowCasters(MeshData casters);

    void uploadParticles(ParticleMeshData particles);

    void uploadUi(UiRenderData ui);

    default void uploadSelectionOutline(SelectionOutlineData outline) {
    }

    default void uploadTerrainOcclusionCutaway(TerrainOcclusionCutaway cutaway) {
    }

    void draw(Camera camera);

    RendererStatus status();

    void requestSwapchainRecreation();

    void toggleStatsOverlay();

    void setDebugViewMode(int mode);

    default void setDebugViewMode(DebugViewMode mode) {
        setDebugViewMode(mode == null ? 0 : mode.shaderValue());
    }
}
