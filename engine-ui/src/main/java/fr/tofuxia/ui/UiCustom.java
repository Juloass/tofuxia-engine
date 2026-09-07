package fr.tofuxia.ui;

import fr.tofuxia.render.UiBuilder;

public final class UiCustom extends UiElement {
    @FunctionalInterface
    public interface Painter {
        void paint(UiBuilder draw, UiRect bounds, float opacity);
    }

    private final Painter painter;

    public UiCustom(String id, Painter painter) {
        super(id);
        this.painter = painter;
    }

    public Painter painter() { return painter; }
}
