package fr.tofuxia.renderer;

import org.joml.Vector3f;

import java.util.List;

/**
 * Per-frame lighting/sky state: one directional sun and a two-tone
 * hemisphere ambient (sky tint from above, ground bounce from below).
 * Uses neutral, standard lighting controls.
 */
public final class Environment {
    private final Vector3f sunDirection = new Vector3f(-0.45f, -0.8f, -0.35f).normalize();
    private final Vector3f sunColor = new Vector3f(1.0f, 1.0f, 1.0f);
    private float sunIntensity = 1.0f;
    private final Vector3f ambientSky = new Vector3f(0.36f, 0.37f, 0.39f);
    private final Vector3f ambientGround = new Vector3f(0.24f, 0.24f, 0.24f);
    private float ambientIntensity = 0.35f;
    private final Vector3f shadowTint = new Vector3f(0.24f, 0.24f, 0.24f);
    private float shadowStrength = 0.42f;
    private final Vector3f fogColor = new Vector3f(0.08f, 0.09f, 0.10f);
    private float fogDensity = 0.0f;
    private float emissiveMultiplier = 1.0f;
    private final Vector3f clearColor = new Vector3f(0.08f, 0.09f, 0.10f);
    private float toneExposure = 1.0f;
    private float toneContrast = 1.0f;
    private float toneSaturation = 1.0f;
    private final Vector3f gradeTint = new Vector3f(1.0f, 1.0f, 1.0f);
    private List<PointLight> pointLights = List.of();
    private DirectionalShadowSettings directionalShadows = DirectionalShadowSettings.validationLab();
    private final Vector3f windDirection = new Vector3f(.82f, 0, .57f).normalize();
    private float windStrength = 1.0f;
    private float windSpeed = .85f;

    public static Environment warmDay() {
        return new Environment();
    }

    public static Environment timeOfDay(float normalizedTime) {
        float t = normalizedTime - (float) Math.floor(normalizedTime);
        Environment out = new Environment();
        if (t < 0.25f) {
            float k = smooth(t / 0.25f);
            out.copy(night()).mix(sunrise(), k);
        } else if (t < 0.50f) {
            float k = smooth((t - 0.25f) / 0.25f);
            out.copy(sunrise()).mix(noon(), k);
        } else if (t < 0.75f) {
            float k = smooth((t - 0.50f) / 0.25f);
            out.copy(noon()).mix(sunset(), k);
        } else {
            float k = smooth((t - 0.75f) / 0.25f);
            out.copy(sunset()).mix(night(), k);
        }
        return out;
    }

    /**
     * Server-authoritative gameplay day/night lighting.
     * Phase convention: 0.00 sunrise, 0.25 noon, 0.50 sunset, 0.75 midnight.
     * The renderer has one active directional light/shadow map, so this blends
     * sun and moon into one effective directional light instead of hard-switching
     * at the horizon.
     */
    public static Environment worldDayCycle(float normalizedTime) {
        float phase = normalizedTime - (float) Math.floor(normalizedTime);
        float angle = phase * (float) (Math.PI * 2.0);
        float altitude = (float) Math.sin(angle);
        float dayWeight = smooth((altitude + 0.08f) / 0.55f);
        float moonWeight = 1.0f - dayWeight;
        float orbitWeight = lerp(0.02f, 0.98f, altitude * 0.5f + 0.5f);
        float twilight = smooth(1.0f - Math.min(1.0f, Math.abs(altitude) / 0.38f));
        Vector3f sunDirection = worldSunDirection(phase);
        Vector3f moonDirection = worldMoonDirection(phase);
        Vector3f blendedDirection = new Vector3f(sunDirection).mul(orbitWeight)
                .add(new Vector3f(moonDirection).mul(1.0f - orbitWeight))
                .normalize();
        LightingProfile profile = LightingProfile.mix(NIGHT_COZY_COLD, AFTERNOON_COZY, dayWeight)
                .mix(TWILIGHT_WARM, twilight * 0.35f);
        return new Environment()
                .sunDirection(blendedDirection.x, blendedDirection.y, blendedDirection.z)
                .sunColor(profile.directionalLightColor().x, profile.directionalLightColor().y, profile.directionalLightColor().z,
                        profile.directionalLightIntensity())
                .ambient(profile.ambientSkyColor(), profile.ambientGroundColor(), profile.ambientIntensity())
                .shadow(profile.shadowTint(), profile.shadowStrength())
                .fog(profile.fogColor(), profile.fogDensity())
                .clearColor(profile.clearColor().x, profile.clearColor().y, profile.clearColor().z)
                .colorGrade(profile.exposure(), profile.contrast(), profile.saturation(), profile.gradeTint())
                .directionalShadows(DirectionalShadowSettings.cameraViewSoft());
    }

    private static final LightingProfile AFTERNOON_COZY = new LightingProfile(
            new Vector3f(1.00f, 0.88f, 0.68f), 0.92f,
            new Vector3f(0.42f, 0.48f, 0.56f), new Vector3f(0.31f, 0.28f, 0.23f), 1.0f,
            new Vector3f(0.19f, 0.18f, 0.17f), 0.42f,
            new Vector3f(0.64f, 0.70f, 0.76f), 0.0018f,
            new Vector3f(0.42f, 0.56f, 0.74f),
            1.00f, 0.99f, 1.02f, new Vector3f(1.015f, 1.000f, 0.985f));

    private static final LightingProfile NIGHT_COZY_COLD = new LightingProfile(
            new Vector3f(0.54f, 0.64f, 0.88f), 0.34f,
            new Vector3f(0.20f, 0.25f, 0.34f), new Vector3f(0.10f, 0.12f, 0.18f), 1.0f,
            new Vector3f(0.15f, 0.18f, 0.24f), 0.28f,
            new Vector3f(0.22f, 0.28f, 0.38f), 0.0032f,
            new Vector3f(0.045f, 0.065f, 0.115f),
            0.94f, 0.94f, 0.92f, new Vector3f(0.955f, 0.980f, 1.035f));

    private static final LightingProfile TWILIGHT_WARM = new LightingProfile(
            new Vector3f(1.00f, 0.74f, 0.52f), 0.55f,
            new Vector3f(0.38f, 0.39f, 0.45f), new Vector3f(0.27f, 0.22f, 0.18f), 1.0f,
            new Vector3f(0.20f, 0.18f, 0.17f), 0.34f,
            new Vector3f(0.55f, 0.42f, 0.32f), 0.0028f,
            new Vector3f(0.32f, 0.20f, 0.14f),
            0.98f, 0.98f, 1.01f, new Vector3f(1.025f, 0.995f, 0.975f));

    public static Vector3f worldSunDirection(float normalizedTime) {
        return new Vector3f(worldCelestialLightingSourceDirection(normalizedTime)).negate().normalize();
    }

    public static Vector3f worldMoonDirection(float normalizedTime) {
        return new Vector3f(worldCelestialLightingSourceDirection(normalizedTime + 0.50f)).negate().normalize();
    }

    public static Vector3f worldSunSourceDirection(float normalizedTime) {
        return worldCelestialSourceDirection(normalizedTime);
    }

    public static Vector3f worldMoonSourceDirection(float normalizedTime) {
        return worldCelestialSourceDirection(normalizedTime + 0.50f);
    }

    public static float worldSunAltitude(float normalizedTime) {
        return worldSunSourceDirection(normalizedTime).y;
    }

    public static float worldMoonAltitude(float normalizedTime) {
        return worldMoonSourceDirection(normalizedTime).y;
    }

    private static Vector3f worldCelestialLightingSourceDirection(float normalizedTime) {
        float phase = normalizedTime - (float) Math.floor(normalizedTime);
        float angle = phase * (float) (Math.PI * 2.0);
        float sourceX = -(float) Math.cos(angle) * 2.80f;
        float sourceZ = (float) Math.sin(angle) * 0.30f;
        return new Vector3f(sourceX, 1.0f, sourceZ).normalize();
    }

    private static Vector3f worldCelestialSourceDirection(float normalizedTime) {
        float phase = normalizedTime - (float) Math.floor(normalizedTime);
        float angle = phase * (float) (Math.PI * 2.0);
        float sourceX = -(float) Math.cos(angle) * 2.80f;
        float sourceY = (float) Math.sin(angle);
        float sourceZ = sourceY * 0.30f;
        return new Vector3f(sourceX, sourceY, sourceZ).normalize();
    }

    public Environment sunDirection(float x, float y, float z) {
        sunDirection.set(x, y, z).normalize();
        return this;
    }

    public Environment sunColor(float r, float g, float b, float intensity) {
        sunColor.set(r, g, b);
        sunIntensity = intensity;
        return this;
    }

    public Environment ambient(Vector3f sky, Vector3f ground, float intensity) {
        ambientSky.set(sky);
        ambientGround.set(ground);
        ambientIntensity = intensity;
        return this;
    }

    public Environment clearColor(float r, float g, float b) {
        clearColor.set(r, g, b);
        return this;
    }

    public Environment shadow(Vector3f tint, float strength) {
        shadowTint.set(tint);
        shadowStrength = strength;
        return this;
    }

    public Environment directionalShadows(DirectionalShadowSettings settings) {
        directionalShadows = settings;
        return this;
    }

    public DirectionalShadowSettings directionalShadows() { return directionalShadows; }

    public Environment fog(Vector3f color, float density) {
        fogColor.set(color);
        fogDensity = density;
        return this;
    }

    public Environment colorGrade(float exposure, float contrast, float saturation, Vector3f tint) {
        toneExposure = exposure;
        toneContrast = contrast;
        toneSaturation = saturation;
        gradeTint.set(tint);
        return this;
    }

    public Environment pointLights(List<PointLight> lights) {
        if (lights == null || lights.isEmpty()) {
            pointLights = List.of();
            return this;
        }
        pointLights = lights.stream().filter(PointLight::enabled).toList();
        return this;
    }

    public Environment wind(float x, float z, float strength, float speed) {
        windDirection.set(x, 0, z);
        if (windDirection.lengthSquared() < .0001f) windDirection.set(1, 0, 0);
        windDirection.normalize();
        windStrength = Math.max(0, strength);
        windSpeed = Math.max(0, speed);
        return this;
    }

    public Vector3f windDirection() { return new Vector3f(windDirection); }
    public float windStrength() { return windStrength; }
    public float windSpeed() { return windSpeed; }

    public Vector3f sunDirection() {
        return new Vector3f(sunDirection);
    }

    public Vector3f scaledSunColor() {
        return new Vector3f(sunColor).mul(sunIntensity);
    }

    public Vector3f scaledAmbientSky() {
        return new Vector3f(ambientSky).mul(ambientIntensity);
    }

    public Vector3f scaledAmbientGround() {
        return new Vector3f(ambientGround).mul(ambientIntensity);
    }

    public Vector3f shadowTint() {
        return new Vector3f(shadowTint);
    }

    public float shadowStrength() {
        return shadowStrength;
    }

    public Vector3f fogColor() {
        return new Vector3f(fogColor);
    }

    public float fogDensity() {
        return fogDensity;
    }

    public float emissiveMultiplier() {
        return emissiveMultiplier;
    }

    public float toneExposure() {
        return toneExposure;
    }

    public float toneContrast() {
        return toneContrast;
    }

    public float toneSaturation() {
        return toneSaturation;
    }

    public Vector3f gradeTint() {
        return new Vector3f(gradeTint);
    }

    public List<PointLight> pointLights() {
        return pointLights;
    }

    public float[] clearColorArray() {
        return new float[]{clearColor.x, clearColor.y, clearColor.z};
    }

    private Environment copy(Environment other) {
        sunDirection.set(other.sunDirection);
        sunColor.set(other.sunColor);
        sunIntensity = other.sunIntensity;
        ambientSky.set(other.ambientSky);
        ambientGround.set(other.ambientGround);
        ambientIntensity = other.ambientIntensity;
        shadowTint.set(other.shadowTint);
        shadowStrength = other.shadowStrength;
        fogColor.set(other.fogColor);
        fogDensity = other.fogDensity;
        emissiveMultiplier = other.emissiveMultiplier;
        clearColor.set(other.clearColor);
        toneExposure = other.toneExposure;
        toneContrast = other.toneContrast;
        toneSaturation = other.toneSaturation;
        gradeTint.set(other.gradeTint);
        pointLights = List.copyOf(other.pointLights);
        directionalShadows = other.directionalShadows;
        windDirection.set(other.windDirection);
        windStrength = other.windStrength;
        windSpeed = other.windSpeed;
        return this;
    }

    private Environment mix(Environment other, float k) {
        sunDirection.lerp(other.sunDirection, k).normalize();
        sunColor.lerp(other.sunColor, k);
        sunIntensity = lerp(sunIntensity, other.sunIntensity, k);
        ambientSky.lerp(other.ambientSky, k);
        ambientGround.lerp(other.ambientGround, k);
        ambientIntensity = lerp(ambientIntensity, other.ambientIntensity, k);
        shadowTint.lerp(other.shadowTint, k);
        shadowStrength = lerp(shadowStrength, other.shadowStrength, k);
        fogColor.lerp(other.fogColor, k);
        fogDensity = lerp(fogDensity, other.fogDensity, k);
        emissiveMultiplier = lerp(emissiveMultiplier, other.emissiveMultiplier, k);
        clearColor.lerp(other.clearColor, k);
        toneExposure = lerp(toneExposure, other.toneExposure, k);
        toneContrast = lerp(toneContrast, other.toneContrast, k);
        toneSaturation = lerp(toneSaturation, other.toneSaturation, k);
        gradeTint.lerp(other.gradeTint, k);
        pointLights = k >= 0.5f ? other.pointLights : pointLights;
        windDirection.lerp(other.windDirection, k).normalize();
        windStrength = lerp(windStrength, other.windStrength, k);
        windSpeed = lerp(windSpeed, other.windSpeed, k);
        return this;
    }

    private static Environment noon() {
        return new Environment()
                .sunDirection(-0.38f, -0.82f, -0.30f)
                .sunColor(1.0f, 1.0f, 1.0f, 1.0f)
                .ambient(new Vector3f(0.36f, 0.37f, 0.39f), new Vector3f(0.24f, 0.24f, 0.24f), 0.35f)
                .shadow(new Vector3f(0.24f, 0.24f, 0.24f), 0.42f)
                .fog(new Vector3f(0.08f, 0.09f, 0.10f), 0.0f)
                .clearColor(0.08f, 0.09f, 0.10f);
    }

    private static Environment sunrise() {
        return new Environment()
                .sunDirection(-0.70f, -0.36f, -0.34f)
                .sunColor(1.0f, 0.96f, 0.90f, 0.75f)
                .ambient(new Vector3f(0.34f, 0.35f, 0.38f), new Vector3f(0.22f, 0.22f, 0.22f), 0.30f)
                .shadow(new Vector3f(0.24f, 0.24f, 0.24f), 0.50f)
                .fog(new Vector3f(0.08f, 0.09f, 0.10f), 0.0f)
                .clearColor(0.07f, 0.08f, 0.10f);
    }

    private static Environment sunset() {
        return new Environment()
                .sunDirection(0.62f, -0.34f, 0.28f)
                .sunColor(1.0f, 0.96f, 0.90f, 0.70f)
                .ambient(new Vector3f(0.34f, 0.35f, 0.38f), new Vector3f(0.22f, 0.22f, 0.22f), 0.30f)
                .shadow(new Vector3f(0.24f, 0.24f, 0.24f), 0.54f)
                .fog(new Vector3f(0.08f, 0.09f, 0.10f), 0.0f)
                .clearColor(0.07f, 0.08f, 0.10f);
    }

    private static Environment night() {
        return new Environment()
                .sunDirection(0.30f, -0.52f, 0.58f)
                .sunColor(0.80f, 0.84f, 0.90f, 0.25f)
                .ambient(new Vector3f(0.22f, 0.23f, 0.25f), new Vector3f(0.12f, 0.12f, 0.12f), 0.22f)
                .shadow(new Vector3f(0.20f, 0.20f, 0.20f), 0.32f)
                .fog(new Vector3f(0.04f, 0.05f, 0.06f), 0.0f)
                .clearColor(0.03f, 0.035f, 0.04f);
    }

    private record LightingProfile(
            Vector3f directionalLightColor,
            float directionalLightIntensity,
            Vector3f ambientSkyColor,
            Vector3f ambientGroundColor,
            float ambientIntensity,
            Vector3f shadowTint,
            float shadowStrength,
            Vector3f fogColor,
            float fogDensity,
            Vector3f clearColor,
            float exposure,
            float contrast,
            float saturation,
            Vector3f gradeTint
    ) {
        LightingProfile {
            directionalLightColor = new Vector3f(directionalLightColor);
            ambientSkyColor = new Vector3f(ambientSkyColor);
            ambientGroundColor = new Vector3f(ambientGroundColor);
            shadowTint = new Vector3f(shadowTint);
            fogColor = new Vector3f(fogColor);
            clearColor = new Vector3f(clearColor);
            gradeTint = new Vector3f(gradeTint);
        }

        static LightingProfile mix(LightingProfile a, LightingProfile b, float k) {
            return new LightingProfile(
                    Environment.mix(a.directionalLightColor, b.directionalLightColor, k),
                    lerp(a.directionalLightIntensity, b.directionalLightIntensity, k),
                    Environment.mix(a.ambientSkyColor, b.ambientSkyColor, k),
                    Environment.mix(a.ambientGroundColor, b.ambientGroundColor, k),
                    lerp(a.ambientIntensity, b.ambientIntensity, k),
                    Environment.mix(a.shadowTint, b.shadowTint, k),
                    lerp(a.shadowStrength, b.shadowStrength, k),
                    Environment.mix(a.fogColor, b.fogColor, k),
                    lerp(a.fogDensity, b.fogDensity, k),
                    Environment.mix(a.clearColor, b.clearColor, k),
                    lerp(a.exposure, b.exposure, k),
                    lerp(a.contrast, b.contrast, k),
                    lerp(a.saturation, b.saturation, k),
                    Environment.mix(a.gradeTint, b.gradeTint, k));
        }

        LightingProfile mix(LightingProfile other, float k) {
            return mix(this, other, k);
        }
    }

    private static Vector3f mix(Vector3f a, Vector3f b, float k) {
        return new Vector3f(a).lerp(b, k);
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    private static float smooth(float x) {
        float t = Math.max(0.0f, Math.min(1.0f, x));
        return t * t * (3.0f - 2.0f * t);
    }
}
