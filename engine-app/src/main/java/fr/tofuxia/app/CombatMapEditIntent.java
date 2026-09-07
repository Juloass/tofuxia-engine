package fr.tofuxia.app;

/** Typed client request for the server-owned M06 combat-map editor transaction stream. */
public record CombatMapEditIntent(Operation operation, String mapId, long expectedRevision, String dimensionId,
                                  Point anchor, Surface surface, Slot slot, Point entry, Policies policies) {
    public enum Operation { CREATE, SELECT, PAINT, ERASE, SLOT, ENTRY, POLICIES, VALIDATE, SAVE, DELETE }
    public record Point(float x, float y, float z) {}
    public record Surface(int worldX, int blockY, int worldZ, String sourceShape, String biomeId,
                          boolean occupied, boolean blocksLineOfSight, long sourceRevision) {}
    public record Slot(String side, int index, int x, int z) {}
    public record Policies(String victoryPolicyId, String defeatPolicyId, String abortPolicyId) {}
    public CombatMapEditIntent {
        if (operation == null) throw new IllegalArgumentException("operation");
        mapId = mapId == null ? "" : mapId; dimensionId = dimensionId == null ? "" : dimensionId;
    }
}
