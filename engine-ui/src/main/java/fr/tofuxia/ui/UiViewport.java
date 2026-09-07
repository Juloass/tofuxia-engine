package fr.tofuxia.ui;

/**
 * Maps framebuffer pixels to stable UI design units. Display density and the
 * player's UI preference are applied exactly once at the root of the UI pass.
 */
public record UiViewport(int framebufferWidth, int framebufferHeight,
                         float pixelRatioX, float pixelRatioY, float userScale,
                         UiInsets safeInsets) {
    public UiViewport {
        if (framebufferWidth <= 0 || framebufferHeight <= 0) {
            throw new IllegalArgumentException("Framebuffer dimensions must be positive");
        }
        pixelRatioX = finitePositive(pixelRatioX, 1);
        pixelRatioY = finitePositive(pixelRatioY, 1);
        userScale = Math.max(.60f, Math.min(2.00f, finitePositive(userScale, 1)));
        safeInsets = safeInsets == null ? new UiInsets(0, 0, 0, 0) : safeInsets;
    }

    public static UiViewport framebuffer(int width, int height, float userScale) {
        return new UiViewport(width, height, 1, 1, userScale, new UiInsets(0, 0, 0, 0));
    }

    public float renderScaleX() { return pixelRatioX * userScale; }
    public float renderScaleY() { return pixelRatioY * userScale; }
    public float logicalWidth() { return framebufferWidth / renderScaleX(); }
    public float logicalHeight() { return framebufferHeight / renderScaleY(); }
    public float toLogicalX(float framebufferX) { return framebufferX / renderScaleX(); }
    public float toLogicalY(float framebufferY) { return framebufferY / renderScaleY(); }

    public UiRect safeBounds() {
        float left = Math.max(0, safeInsets.left());
        float top = Math.max(0, safeInsets.top());
        return new UiRect(left, top,
                Math.max(0, logicalWidth() - left - Math.max(0, safeInsets.right())),
                Math.max(0, logicalHeight() - top - Math.max(0, safeInsets.bottom())));
    }

    public UiDisplayClass displayClass() {
        float width = safeBounds().w();
        float aspect = safeBounds().h() <= 0 ? 1 : width / safeBounds().h();
        if (width < 720) return UiDisplayClass.COMPACT;
        if (width < 1200) return UiDisplayClass.STANDARD;
        if (aspect >= 2.15f) return UiDisplayClass.ULTRAWIDE;
        return UiDisplayClass.WIDE;
    }

    public UiRect fit(float sourceWidth, float sourceHeight, UiRect destination, UiAspectPolicy policy) {
        if (policy == UiAspectPolicy.STRETCH || sourceWidth <= 0 || sourceHeight <= 0) return destination;
        float factor = policy == UiAspectPolicy.COVER
                ? Math.max(destination.w() / sourceWidth, destination.h() / sourceHeight)
                : Math.min(destination.w() / sourceWidth, destination.h() / sourceHeight);
        float width = sourceWidth * factor;
        float height = sourceHeight * factor;
        return new UiRect(destination.x() + (destination.w() - width) * .5f,
                destination.y() + (destination.h() - height) * .5f, width, height);
    }

    private static float finitePositive(float value, float fallback) {
        return Float.isFinite(value) && value > 0 ? value : fallback;
    }
}
