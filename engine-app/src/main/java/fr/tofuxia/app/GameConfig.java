package fr.tofuxia.app;

import io.github.juloass.content.ContentBootstrapPlan;
import fr.tofuxia.renderer.CpuMesh;
import io.github.juloass.resource.pack.ResourcePackCandidate;
import io.github.juloass.resource.ResourceType;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record GameConfig(
        String windowTitle,
        Path assetRoot,
        Path fontManifest,
        String loadingLogo,
        String connectionBackground,
        String defaultScene,
        Map<String, GameSceneFactory> scenes,
        Map<String, CpuMesh> meshes,
        ParticleSceneSupport particles,
        java.util.Optional<AudioSupport> audioSupport,
        ContentBootstrapPlan contentBootstrapPlan,
        List<ResourcePackCandidate> resourcePacks,
        List<ResourceType<?>> resourceTypes
) {
    public GameConfig {
        resourcePacks = List.copyOf(resourcePacks);
        resourceTypes = List.copyOf(resourceTypes);
        audioSupport = java.util.Objects.requireNonNull(audioSupport,
                "audioSupport");
    }
}
