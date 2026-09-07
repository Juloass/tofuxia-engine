package fr.tofuxia.app;

public record FramebufferSize(int width, int height) {
    public boolean visible() {
        return width > 0 && height > 0;
    }
}
