package fr.tofuxia.renderapi;

import fr.tofuxia.render.MeshData;
import fr.tofuxia.render.ParticleMeshData;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.terrain.TerrainData;

/** Immutable frame payload for renderer implementations that prefer batch submission. */
public record RenderScene(
        TerrainData terrainData,
        MeshData terrain,
        MeshData water,
        MeshData scene,
        MeshData overlay,
        MeshData dynamicShadowCasters,
        ParticleMeshData particles,
        UiRenderData ui
) {
}
