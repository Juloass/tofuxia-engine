package fr.tofuxia.renderer;

import org.joml.Vector3f;

public record PointLight(float x, float y, float z,
                         float red, float green, float blue,
                         float radius, float intensity) {
    public PointLight(Vector3f position, Vector3f color, float radius, float intensity) {
        this(position.x, position.y, position.z, color.x, color.y, color.z, radius, intensity);
    }

    public PointLight {
        red = clamp01(red);
        green = clamp01(green);
        blue = clamp01(blue);
        radius = Math.max(0.0f, radius);
        intensity = Math.max(0.0f, intensity);
    }

    public boolean enabled() {
        return radius > 0.0f && intensity > 0.0f;
    }

    public Vector3f position() {
        return new Vector3f(x, y, z);
    }

    public Vector3f color() {
        return new Vector3f(red, green, blue);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
