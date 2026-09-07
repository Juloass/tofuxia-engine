package fr.tofuxia.app;

public record PlayerMovementSnapshot(
        long sequence,
        float x,
        float y,
        float z,
        float velocityX,
        float velocityY,
        float velocityZ,
        float yaw,
        float pitch,
        boolean grounded,
        long clientTimeMillis
) {
    public boolean finite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && Float.isFinite(velocityX) && Float.isFinite(velocityY) && Float.isFinite(velocityZ)
                && Float.isFinite(yaw) && Float.isFinite(pitch);
    }
}
