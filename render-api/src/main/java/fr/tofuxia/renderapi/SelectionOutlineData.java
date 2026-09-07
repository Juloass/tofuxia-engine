package fr.tofuxia.renderapi;

/** Renderer-neutral indexed line artifact for the dedicated selection overlay pass. */
public record SelectionOutlineData(
        int revision,
        DebugStage debugStage,
        float[] positions,
        int[] indices,
        float red, float green, float blue, float alpha,
        int blockX, int blockY, int blockZ,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ
) {
    public static final SelectionOutlineData EMPTY = new SelectionOutlineData(
            0, DebugStage.DISABLED, new float[0], new int[0],
            1, 1, 1, .65f, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    public SelectionOutlineData {
        debugStage = debugStage == null ? DebugStage.DISABLED : debugStage;
        positions = positions == null ? new float[0] : positions.clone();
        indices = indices == null ? new int[0] : indices.clone();
        if (positions.length % 3 != 0) throw new IllegalArgumentException("Outline positions must be XYZ triples");
        int vertexCount = positions.length / 3;
        if ((indices.length & 1) != 0) throw new IllegalArgumentException("LINE_LIST indices must be pairs");
        for (int index : indices) if (index < 0 || index >= vertexCount) {
            throw new IllegalArgumentException("Outline index outside vertex range: " + index);
        }
    }

    @Override public float[] positions() { return positions.clone(); }
    @Override public int[] indices() { return indices.clone(); }
    public int vertexCount() { return positions.length / 3; }
    public boolean empty() { return indices.length == 0 || debugStage == DebugStage.DISABLED; }

    public enum DebugStage {
        DISABLED,
        TEST_LINE,
        BLOCK_AABB,
        SELECTION_SHAPE,
        EXTRACTED_GEOMETRY;

        public static DebugStage fromInt(int value) {
            DebugStage[] values = values();
            return values[Math.max(0, Math.min(values.length - 1, value))];
        }
    }
}
