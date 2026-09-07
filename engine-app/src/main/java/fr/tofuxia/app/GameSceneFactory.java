package fr.tofuxia.app;

@FunctionalInterface
public interface GameSceneFactory {
    GameScene create(SceneLoadContext context);
}
