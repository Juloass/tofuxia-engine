package fr.tofuxia.renderer;

/**
 * Explicit frame pipeline. Passes execute in declaration order inside the
 * frame's render pass; the queue buckets items per pass. POST_PROCESS and UI
 * are placeholders so the frame shape is stable when they become real.
 */
public enum PassId {
    /** Opaque world + entity geometry, sorted front-to-back-ish by pipeline. */
    OPAQUE,
    /** Alpha-tested foliage/props; depth-written like opaque. */
    ALPHA_CUTOUT,
    /** Depth-tested selected-object mask, composited as a screen-space silhouette. */
    ENTITY_OUTLINE,
    /** Blended geometry, sorted back-to-front. */
    TRANSPARENT,
    /** Camera-facing quads: particles, impostors; drawn after transparents. */
    BILLBOARDS,
    /** Placeholder hook: tonemap/bloom will live here. */
    POST_PROCESS,
    /** Dedicated unlit depth-tested line overlay, after world draws and before UI. */
    SELECTION_OUTLINE,
    /** Placeholder hook: game UI will live here. */
    UI,
    /** Renderer stats text and debug visualizations. */
    DEBUG_OVERLAY;

    public boolean acceptsSubmissions() {
        return this == OPAQUE || this == ALPHA_CUTOUT || this == ENTITY_OUTLINE
                || this == TRANSPARENT || this == BILLBOARDS;
    }
}
