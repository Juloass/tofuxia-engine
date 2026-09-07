package fr.tofuxia.renderer;

import org.joml.Vector3f;

/** Configuration for the renderer's single stabilized directional shadow map. */
public record DirectionalShadowSettings(
        boolean enabled, int resolution, ShadowFitMode fitMode, Vector3f center, float halfExtent,
        float nearPlane, float farPlane, float constantBias, float slopeBias,
        float receiverBias, int pcfRadius, float fitDistance, float padding, float minHalfExtent) {
    public static final int SHADOW_MAP_RESOLUTION = 2048;

    public DirectionalShadowSettings {
        fitMode = fitMode == null ? ShadowFitMode.FIXED_VOLUME : fitMode;
        center = new Vector3f(center);
        if (resolution != SHADOW_MAP_RESOLUTION || halfExtent <= 0 || nearPlane <= 0 || farPlane <= nearPlane ||
                fitDistance <= nearPlane || padding < 0 || minHalfExtent <= 0)
            throw new IllegalArgumentException("Invalid directional shadow settings");
        pcfRadius = Math.max(0, Math.min(2, pcfRadius));
    }

    @Override public Vector3f center() { return new Vector3f(center); }

    public static DirectionalShadowSettings validationLab() {
        return fixedValidationLab();
    }

    public static DirectionalShadowSettings fixedValidationLab() {
        return new DirectionalShadowSettings(true, SHADOW_MAP_RESOLUTION, ShadowFitMode.FIXED_VOLUME, new Vector3f(0, 2.5f, 0), 9.0f,
                .1f, 40f, .35f, .75f, .00045f, 1, 40f, 4f, 9.0f);
    }

    public static DirectionalShadowSettings cameraView() {
        return new DirectionalShadowSettings(true, SHADOW_MAP_RESOLUTION, ShadowFitMode.CAMERA_VIEW, new Vector3f(0, 2.5f, 0), 7.5f,
                .1f, 100f, .35f, .75f, .00045f, 1, 42f, 4f, 7.5f);
    }

    public static DirectionalShadowSettings cameraViewSoft() {
        return new DirectionalShadowSettings(true, SHADOW_MAP_RESOLUTION, ShadowFitMode.CAMERA_VIEW, new Vector3f(0, 2.5f, 0), 7.5f,
                .1f, 100f, .45f, .85f, .00065f, 2, 42f, 4f, 7.5f);
    }

    public static DirectionalShadowSettings disabled() {
        return new DirectionalShadowSettings(false, SHADOW_MAP_RESOLUTION, ShadowFitMode.FIXED_VOLUME, new Vector3f(), 8f,
                .1f, 40f, .35f, .75f, .00045f, 1, 40f, 4f, 8f);
    }
}
