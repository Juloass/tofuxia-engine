package fr.tofuxia.renderapi;

/** Pixel-space rectangle used for rendering and pointer-to-world mapping. */
public record RenderViewport(int x, int y, int width, int height) {
    public RenderViewport {
        width = Math.max(1, width);
        height = Math.max(1, height);
    }

    public static RenderViewport fullScreen(int width, int height) {
        return new RenderViewport(0, 0, width, height);
    }

    public boolean contains(float px, float py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    public float localX(float px) {
        return px - x;
    }

    public float localY(float py) {
        return py - y;
    }

    public static RenderViewport lerp(RenderViewport a, RenderViewport b, float t) {
        float x = clamp01(t);
        return new RenderViewport(
                Math.round(lerp(a.x, b.x, x)),
                Math.round(lerp(a.y, b.y, x)),
                Math.round(lerp(a.width, b.width, x)),
                Math.round(lerp(a.height, b.height, x)));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
