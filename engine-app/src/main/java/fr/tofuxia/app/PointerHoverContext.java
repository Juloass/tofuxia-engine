package fr.tofuxia.app;

import fr.tofuxia.renderapi.RenderViewport;

/** Current render-frame pointer ray, calculated after camera following. */
public record PointerHoverContext(
        float originX, float originY, float originZ,
        float directionX, float directionY, float directionZ,
        boolean cursorInWorldViewport,
        boolean uiOwnsPointer,
        RenderViewport worldViewport
) {}
