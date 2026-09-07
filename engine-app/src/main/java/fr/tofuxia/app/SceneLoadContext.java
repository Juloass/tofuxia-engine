package fr.tofuxia.app;

import fr.tofuxia.renderer.MaterialSystem;
import fr.tofuxia.renderer.Renderer;
import fr.tofuxia.render.FontCatalog;

import java.nio.file.Path;
import io.github.juloass.registry.RegistrySet;
import io.github.juloass.content.WorkProgress;

public record SceneLoadContext(Path assetRoot, Renderer renderer, MaterialSystem materials, RegistrySet registries,
                               FontCatalog fonts, WorkProgress startupProgress) {
    public SceneLoadContext {
        startupProgress = startupProgress == null ? WorkProgress.none() : startupProgress;
    }

    public SceneLoadContext(Path assetRoot, Renderer renderer, MaterialSystem materials, RegistrySet registries,
                            FontCatalog fonts) {
        this(assetRoot, renderer, materials, registries, fonts, WorkProgress.none());
    }
}
