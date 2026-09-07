package fr.tofuxia.app;

/** Game-owned registration hook. The engine owns the loop; the game declares content. */
public interface GameModule {
    void configure(GameBuilder game);
}
