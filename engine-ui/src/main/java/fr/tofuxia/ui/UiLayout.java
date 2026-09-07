package fr.tofuxia.ui;

/** Layout constraints shared by every UI element. */
public final class UiLayout {
    public enum Flow { NONE, ROW, COLUMN, STACK }
    public enum Align { START, CENTER, END, STRETCH }

    float x;
    float y;
    float width = -1;
    float height = -1;
    float minWidth;
    float minHeight;
    float maxWidth = Float.POSITIVE_INFINITY;
    float maxHeight = Float.POSITIVE_INFINITY;
    float padding;
    float gap;
    float flex;
    int zIndex;
    Flow flow = Flow.NONE;
    Align mainAlign = Align.START;
    Align crossAlign = Align.START;
    boolean absolute;
    boolean clip;

    public UiLayout position(float x, float y) { this.x = x; this.y = y; return this; }
    public UiLayout size(float width, float height) { this.width = width; this.height = height; return this; }
    public UiLayout minSize(float width, float height) { minWidth = width; minHeight = height; return this; }
    public UiLayout maxSize(float width, float height) { maxWidth = width; maxHeight = height; return this; }
    public UiLayout padding(float value) { padding = Math.max(0, value); return this; }
    public UiLayout gap(float value) { gap = Math.max(0, value); return this; }
    public UiLayout flex(float value) { flex = Math.max(0, value); return this; }
    public UiLayout zIndex(int value) { zIndex = value; return this; }
    public UiLayout flow(Flow value) { flow = value == null ? Flow.NONE : value; return this; }
    public UiLayout align(Align main, Align cross) {
        mainAlign = main == null ? Align.START : main;
        crossAlign = cross == null ? Align.START : cross;
        return this;
    }
    public UiLayout absolute(boolean value) { absolute = value; return this; }
    public UiLayout clip(boolean value) { clip = value; return this; }
}
