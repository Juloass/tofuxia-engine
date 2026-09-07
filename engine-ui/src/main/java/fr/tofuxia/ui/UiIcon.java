package fr.tofuxia.ui;

/** Named icon atlas region. */
public final class UiIcon extends UiElement {
    private final String atlas;
    private final UiRect uv;

    public UiIcon(String id, String atlas, UiRect uv) {
        super(id);
        this.atlas = atlas;
        this.uv = uv;
    }

    public String atlas() { return atlas; }
    public UiRect uv() { return uv; }
}
