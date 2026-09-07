package fr.tofuxia.app;

/** Window and framebuffer measurements sampled together for DPI-correct input and UI. */
public record DisplayMetrics(int windowWidth, int windowHeight,
                             int framebufferWidth, int framebufferHeight,
                             float contentScaleX, float contentScaleY) {
    public DisplayMetrics {
        windowWidth = Math.max(1, windowWidth);
        windowHeight = Math.max(1, windowHeight);
        framebufferWidth = Math.max(1, framebufferWidth);
        framebufferHeight = Math.max(1, framebufferHeight);
        contentScaleX = finitePositive(contentScaleX);
        contentScaleY = finitePositive(contentScaleY);
    }

    public static DisplayMetrics framebuffer(int width, int height) {
        return new DisplayMetrics(width, height, width, height, 1, 1);
    }

    public FramebufferSize framebuffer() { return new FramebufferSize(framebufferWidth, framebufferHeight); }
    public float framebufferScaleX() { return framebufferWidth / (float) windowWidth; }
    public float framebufferScaleY() { return framebufferHeight / (float) windowHeight; }

    private static float finitePositive(float value) {
        return Float.isFinite(value) && value > 0 ? value : 1;
    }
}
