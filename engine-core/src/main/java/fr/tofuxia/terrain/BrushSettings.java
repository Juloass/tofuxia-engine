package fr.tofuxia.terrain;

public final class BrushSettings {
    private TerrainChannel channel = TerrainChannel.HEIGHT;
    private float radius = 0.65f;
    private float strength = 0.55f;
    private float falloff = 0.65f;
    private boolean subtract;
    private DebugVisualization debugVisualization = DebugVisualization.MATERIALS;

    public TerrainChannel channel() {
        return channel;
    }

    public void setChannel(TerrainChannel channel) {
        this.channel = channel;
    }

    public float radius() {
        return radius;
    }

    public void adjustRadius(float delta) {
        radius = Math.max(0.08f, Math.min(2.5f, radius + delta));
    }

    public void setRadius(float value) {
        radius = Math.max(0.08f, Math.min(2.5f, value));
    }

    public float strength() {
        return strength;
    }

    public void adjustStrength(float delta) {
        strength = Math.max(0.02f, Math.min(2.0f, strength + delta));
    }

    public void setStrength(float value) {
        strength = Math.max(0.02f, Math.min(2.0f, value));
    }

    public float falloff() {
        return falloff;
    }

    public void adjustFalloff(float delta) {
        falloff = Math.max(0.05f, Math.min(1.0f, falloff + delta));
    }

    public void setFalloff(float value) {
        falloff = Math.max(0.05f, Math.min(1.0f, value));
    }

    public boolean subtract() {
        return subtract;
    }

    public void setSubtract(boolean subtract) {
        this.subtract = subtract;
    }

    public DebugVisualization debugVisualization() {
        return debugVisualization;
    }

    public void cycleDebugVisualization() {
        DebugVisualization[] values = DebugVisualization.values();
        debugVisualization = values[(debugVisualization.ordinal() + 1) % values.length];
    }

    public String summary() {
        return "%s r=%.2f str=%.2f fall=%.2f debug=%s".formatted(
                channel.label(), radius, strength, falloff, debugVisualization.label()
        );
    }
}
