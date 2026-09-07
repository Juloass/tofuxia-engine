package fr.tofuxia.app;

public record PlatformFrameState(boolean focused, boolean iconified) {
    public static final PlatformFrameState UNKNOWN = new PlatformFrameState(false, false);
}
