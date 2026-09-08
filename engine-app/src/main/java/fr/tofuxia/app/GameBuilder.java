package fr.tofuxia.app;

import io.github.juloass.content.ContentBootstrapPlan;
import io.github.juloass.content.ContentModule;
import fr.tofuxia.render.ParticleTextureAtlas;
import fr.tofuxia.renderer.CpuMesh;
import io.github.juloass.resource.pack.ResourcePackCandidate;
import io.github.juloass.resource.ResourceType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GameBuilder {
    private String windowTitle = "Java Game";
    private Path assetRoot = Path.of("assets");
    private Path fontManifest = Path.of("engine/fonts/fonts.properties");
    private String connectionBackground = "";
    private String defaultScene = "render";
    private final Map<String, GameSceneFactory> scenes = new LinkedHashMap<>();
    private final Map<String, CpuMesh> meshes = new LinkedHashMap<>();
    private ParticleSceneSupport particles = ParticleSceneSupport.none();
    private AudioSupport audioSupport;
    private final List<ContentModule> contentModules = new ArrayList<>();
    private final List<ResourcePackCandidate> resourcePacks = new ArrayList<>(
            List.of(fr.tofuxia.assets.EngineDefaultAssets.resourcePack()));
    private final List<ResourceType<?>> resourceTypes = new ArrayList<>();

    public GameBuilder windowTitle(String value) {
        windowTitle = value;
        return this;
    }

    public GameBuilder assetRoot(Path value) {
        assetRoot = value;
        return this;
    }

    /** Selects the role-to-font manifest relative to {@link #assetRoot}. */
    public GameBuilder fontManifest(Path value) {
        fontManifest = java.util.Objects.requireNonNull(value, "value");
        return this;
    }

    /** Selects a full-screen connection background relative to the asset root. */
    public GameBuilder connectionBackground(String value) {
        connectionBackground = java.util.Objects.requireNonNull(value, "value")
                .replace('\\', '/');
        return this;
    }

    public GameBuilder defaultScene(String id) {
        defaultScene = id;
        return this;
    }

    public GameBuilder scene(String id, GameSceneFactory factory) {
        scenes.put(id, factory);
        return this;
    }

    public GameBuilder particleSupport(ParticleSceneSupport value) {
        particles = value == null ? ParticleSceneSupport.none() : value;
        return this;
    }

    public GameBuilder audioSupport(AudioSupport value) {
        audioSupport = java.util.Objects.requireNonNull(value, "value");
        return this;
    }

    public GameBuilder mesh(String id, CpuMesh mesh) {
        meshes.put(id, mesh);
        return this;
    }

    public GameBuilder contentModule(ContentModule module) {
        contentModules.add(java.util.Objects.requireNonNull(module, "module"));
        return this;
    }

    public GameBuilder resourceTypes(
            java.util.Collection<? extends ResourceType<?>> types
    ) {
        types.forEach(type -> resourceTypes.add(
                java.util.Objects.requireNonNull(type, "type")));
        return this;
    }

    public GameBuilder resourcePack(ResourcePackCandidate pack) {
        resourcePacks.add(java.util.Objects.requireNonNull(pack, "pack"));
        return this;
    }

    public GameConfig build() {
        if (scenes.isEmpty()) {
            throw new IllegalStateException("At least one scene must be registered");
        }
        if (!scenes.containsKey(defaultScene)) {
            throw new IllegalStateException(
                    "Default scene is not registered: " + defaultScene);
        }
        if (contentModules.isEmpty()) {
            throw new IllegalStateException(
                    "At least one content module must be registered");
        }
        ContentBootstrapPlan.Builder content = ContentBootstrapPlan.builder();
        resourcePacks.forEach(content::resourcePack);
        resourceTypes.forEach(content::resourceType);
        contentModules.forEach(content::module);
        return new GameConfig(
                windowTitle,
                assetRoot,
                fontManifest,
                connectionBackground,
                defaultScene,
                Map.copyOf(scenes),
                Map.copyOf(meshes),
                particles,
                java.util.Optional.ofNullable(audioSupport),
                content.build(),
                List.copyOf(resourcePacks),
                List.copyOf(resourceTypes));
    }
}
