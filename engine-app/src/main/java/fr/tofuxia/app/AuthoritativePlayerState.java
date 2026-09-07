package fr.tofuxia.app;

public record AuthoritativePlayerState(
        long acknowledgedSequence,
        float x,
        float y,
        float z,
        float velocityX,
        float velocityY,
        float velocityZ,
        float yaw,
        float pitch,
        boolean grounded,
        long serverTimeMillis,
        String reason
) {
    public boolean finite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && Float.isFinite(velocityX) && Float.isFinite(velocityY) && Float.isFinite(velocityZ)
                && Float.isFinite(yaw) && Float.isFinite(pitch);
    }
}
