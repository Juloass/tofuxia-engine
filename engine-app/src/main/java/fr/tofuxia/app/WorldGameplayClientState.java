package fr.tofuxia.app;

import java.util.List;

/** Client-visible M06 state; outcomes remain server-authored. */
public record WorldGameplayClientState(String engagementState, String herdId, float approachX, float approachY,
                                       float approachZ, String dungeonRunId, String dungeonTransition,
                                       int dungeonRoomIndex, String authoringMapId, long authoringRevision,
                                       boolean authoringAccepted, String dominantBiomeId, List<String> diagnostics,
                                       String rewardTransactionId, long rewardedExperience, List<Loot> loot) {
    public record Loot(String itemId, int rolled, int inserted, int overflow, String failure) {}
    public static final WorldGameplayClientState EMPTY = new WorldGameplayClientState("", "", 0, 0, 0,
            "", "", -1, "", 0, false, "", List.of(), "", 0, List.of());
    public WorldGameplayClientState { diagnostics = List.copyOf(diagnostics); loot = List.copyOf(loot); }
    public WorldGameplayClientState withAuthoring(String mapId, long revision, boolean accepted, String biome, List<String> nextDiagnostics) {
        return new WorldGameplayClientState(engagementState, herdId, approachX, approachY, approachZ, dungeonRunId,
                dungeonTransition, dungeonRoomIndex, mapId, revision, accepted, biome, nextDiagnostics,
                rewardTransactionId, rewardedExperience, loot);
    }
    public WorldGameplayClientState withEngagement(String state, String nextHerd, float x, float y, float z, String diagnostic) {
        return new WorldGameplayClientState(state, nextHerd, x, y, z, dungeonRunId, dungeonTransition, dungeonRoomIndex,
                authoringMapId, authoringRevision, authoringAccepted, dominantBiomeId,
                diagnostic == null || diagnostic.isBlank() ? diagnostics : List.of(diagnostic), rewardTransactionId, rewardedExperience, loot);
    }
    public WorldGameplayClientState withDungeon(String runId, String transition, int room, String diagnostic) {
        return new WorldGameplayClientState(engagementState, herdId, approachX, approachY, approachZ, runId, transition, room,
                authoringMapId, authoringRevision, authoringAccepted, dominantBiomeId,
                diagnostic == null || diagnostic.isBlank() ? diagnostics : List.of(diagnostic), rewardTransactionId, rewardedExperience, loot);
    }
    public WorldGameplayClientState withReward(String transaction, long xp, List<Loot> nextLoot) {
        return new WorldGameplayClientState(engagementState, herdId, approachX, approachY, approachZ, dungeonRunId,
                dungeonTransition, dungeonRoomIndex, authoringMapId, authoringRevision, authoringAccepted, dominantBiomeId,
                diagnostics, transaction, xp, nextLoot);
    }
}
