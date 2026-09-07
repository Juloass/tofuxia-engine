package fr.tofuxia.ui;

public final class UiSpacer extends UiElement {
    public UiSpacer(String id, float width, float height) {
        super(id);
        layout().size(width, height);
    }
}
