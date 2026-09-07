package fr.tofuxia.app;

import fr.tofuxia.renderer.Renderer;
import io.github.juloass.registry.RegistrySet;
import fr.tofuxia.render.FontCatalog;
import io.github.juloass.content.WorkProgress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SceneManager {
    private final List<String> orderedIds;
    private final Map<String, GameScene> scenes;
    private GameScene active;

    public SceneManager(GameConfig config, Renderer renderer, RegistrySet registries, FontCatalog fonts) {
        this(config, renderer, registries, fonts, WorkProgress.none());
    }

    public SceneManager(GameConfig config, Renderer renderer, RegistrySet registries, FontCatalog fonts,
                        WorkProgress progress) {
        SceneLoadContext context = new SceneLoadContext(config.assetRoot(), renderer, renderer.materials(), registries, fonts, progress);
        orderedIds = new ArrayList<>(config.scenes().keySet());
        java.util.LinkedHashMap<String, GameScene> loaded = new java.util.LinkedHashMap<>();
        int completed=0,total=config.scenes().size();
        for (Map.Entry<String, GameSceneFactory> entry : config.scenes().entrySet()) {
            progress.report(completed,total,"scenes","Creating scene "+entry.getKey());
            GameScene scene = entry.getValue().create(context);
            scene.load(context);
            loaded.put(entry.getKey(), scene);
            progress.report(++completed,total,"scenes","Creating scene "+entry.getKey());
        }
        scenes = Map.copyOf(loaded);
        switchTo(config.defaultScene());
    }

    public GameScene active() {
        return active;
    }

    public boolean switchTo(String id) {
        GameScene next = scenes.get(id);
        if (next == null) return false;
        if (active != null && active != next) active.unload(new SceneUnloadContext());
        active = next;
        return true;
    }

    public boolean switchSlot(int slot) {
        if (slot < 0 || slot >= orderedIds.size()) return false;
        return switchTo(orderedIds.get(slot));
    }

    public String activeId() {
        for (Map.Entry<String, GameScene> entry : scenes.entrySet()) {
            if (entry.getValue() == active) return entry.getKey();
        }
        return "";
    }
}
