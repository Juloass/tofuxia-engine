package fr.tofuxia.app;

public record FluidEditIntent(
        String dimensionId,
        int clickedX,
        int clickedY,
        int clickedZ,
        String fluidId,
        boolean place,
        int level,
        boolean falling,
        boolean source,
        String clickedFace,
        float hitX,
        float hitY,
        float hitZ
) {
    public FluidEditIntent {
        fluidId = fluidId == null ? "" : fluidId;
        clickedFace = clickedFace == null ? "" : clickedFace;
        if (level < 0) level = 0;
        if (level > 8) level = 8;
    }
}
