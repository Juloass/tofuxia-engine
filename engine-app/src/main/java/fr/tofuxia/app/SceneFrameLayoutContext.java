package fr.tofuxia.app;

public record SceneFrameLayoutContext(float deltaSeconds, int framebufferWidth, int framebufferHeight,
                                      float uiScale, boolean gameModeToggle) {
}
