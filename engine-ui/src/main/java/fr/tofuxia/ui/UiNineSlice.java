package fr.tofuxia.ui;

/** Stretchable texture panel with fixed destination borders and normalized UV borders. */
public final class UiNineSlice extends UiElement {
    private final String texture;
    private final UiInsets border;
    private final UiInsets uvBorder;
    private final boolean pixelArt;

    public UiNineSlice(String id, String texture, UiInsets border, UiInsets uvBorder, boolean pixelArt) {
        super(id);
        this.texture = texture;
        this.border = border;
        this.uvBorder = uvBorder;
        this.pixelArt = pixelArt;
    }

    public String texture() { return texture; }
    public UiInsets border() { return border; }
    public UiInsets uvBorder() { return uvBorder; }
    public boolean pixelArt() { return pixelArt; }
}
