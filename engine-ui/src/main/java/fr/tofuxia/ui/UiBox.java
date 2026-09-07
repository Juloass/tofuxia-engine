package fr.tofuxia.ui;

public final class UiBox extends UiElement {
    private final float[] color;
    private float[] border;
    private float borderWidth = 1;

    public UiBox(String id, float[] color) {
        super(id);
        this.color = color.clone();
    }

    public float[] color() { return color.clone(); }
    public float[] border() { return border == null ? null : border.clone(); }
    public float borderWidth() { return borderWidth; }
    public UiBox border(float[] color, float width) {
        border = color == null ? null : color.clone();
        borderWidth = Math.max(0, width);
        return this;
    }
}
