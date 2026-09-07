package fr.tofuxia.ui;

public record UiRect(float x, float y, float w, float h) {
    public float x1() {
        return x + w;
    }

    public float y1() {
        return y + h;
    }

    public boolean contains(float px, float py) {
        return px >= x && px <= x1() && py >= y && py <= y1();
    }

    public UiRect inset(float amount) {
        return new UiRect(x + amount, y + amount, Math.max(0.0f, w - amount * 2.0f), Math.max(0.0f, h - amount * 2.0f));
    }

    public UiRect intersect(UiRect other) {
        float nx = Math.max(x, other.x);
        float ny = Math.max(y, other.y);
        float nx1 = Math.min(x1(), other.x1());
        float ny1 = Math.min(y1(), other.y1());
        return new UiRect(nx, ny, Math.max(0.0f, nx1 - nx), Math.max(0.0f, ny1 - ny));
    }
}
