package fr.tofuxia.ui;

public record UiDebugSnapshot(String hoveredId, String activeId, String focusedId,
                              String dragSourceId, String dropTargetId,
                              int layoutRects, int clipRects, int animationChannels, int activeTimelines,
                              int retainedElements,
                              int drawVertices, int drawIndices, String warning) {
}
