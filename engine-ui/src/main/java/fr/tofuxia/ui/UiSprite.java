package fr.tofuxia.ui;

/** Texture-backed UI image. Texture resolution is deferred to the renderer. */
public final class UiSprite extends UiElement {
    private final String texture;
    private final UiRect uv;
    private final boolean pixelArt;

    public UiSprite(String id, String texture) {
        this(id, texture, new UiRect(0, 0, 1, 1), true);
    }

    public UiSprite(String id, String texture, UiRect uv, boolean pixelArt) {
        super(id);
        this.texture = texture;
        this.uv = uv;
        this.pixelArt = pixelArt;
    }

    public String texture() { return texture; }
    public UiRect uv() { return uv; }
    public boolean pixelArt() { return pixelArt; }
}
