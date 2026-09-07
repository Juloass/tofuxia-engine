package fr.tofuxia.app;

import java.util.Map;

public record BlockEditIntent(
        String dimensionId,
        int clickedX,
        int clickedY,
        int clickedZ,
        String blockId,
        Map<String, String> properties,
        boolean place,
        String clickedFace,
        float hitX,
        float hitY,
        float hitZ
) {
    public BlockEditIntent {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        blockId = blockId == null ? "" : blockId;
        clickedFace = clickedFace == null ? "" : clickedFace;
    }
}
