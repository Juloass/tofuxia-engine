package fr.tofuxia.ui;

import io.github.juloass.localization.TextComponent;

public final class UiText extends UiElement {
    private final TextComponent component;
    private final float[] color;
    private boolean wrap;
    private int maxLines = Integer.MAX_VALUE;
    private float lineSpacing = 1;
    private UiTextOverflow overflow = UiTextOverflow.CLIP;

    public UiText(String id, String text, float[] color) {
        this(id, TextComponent.literal(text), color);
    }

    public UiText(String id, TextComponent component, float[] color) {
        super(id);
        this.component = component == null ? TextComponent.literal("") : component;
        this.color = color.clone();
        semantics(UiRole.LABEL, this.component);
        focusable(false);
    }

    public TextComponent component() { return component; }
    public float[] color() { return color.clone(); }
    public boolean wrap() { return wrap; }
    public int maxLines() { return maxLines; }
    public float lineSpacing() { return lineSpacing; }
    public UiTextOverflow overflow() { return overflow; }

    public UiText wrap(boolean value) { wrap = value; return this; }
    public UiText maxLines(int value) { maxLines = Math.max(1, value); return this; }
    public UiText lineSpacing(float value) { lineSpacing = Math.max(.75f, Math.min(3, value)); return this; }
    public UiText overflow(UiTextOverflow value) {
        overflow = value == null ? UiTextOverflow.CLIP : value;
        return this;
    }
}
