package fr.tofuxia.app;

public interface EnginePlatform {
    void pollEvents();
    boolean shouldClose();
    void requestClose();
    FramebufferSize framebufferSize();
    default DisplayMetrics displayMetrics() {
        FramebufferSize framebuffer = framebufferSize();
        return DisplayMetrics.framebuffer(framebuffer.width(), framebuffer.height());
    }
    InputSnapshot input();
    default void setCursorStyle(CursorStyle style) {}
    default void close() {}

    default PlatformFrameState frameState() {
        return PlatformFrameState.UNKNOWN;
    }
}
