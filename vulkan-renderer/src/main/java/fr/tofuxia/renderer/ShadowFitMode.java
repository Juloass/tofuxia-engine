package fr.tofuxia.renderer;

/** How the single directional shadow map chooses its orthographic coverage. */
public enum ShadowFitMode {
    /** Uses the explicit center and half extent from DirectionalShadowSettings. */
    FIXED_VOLUME,
    /** Fits the map to the current camera view out to a bounded distance. */
    CAMERA_VIEW
}
