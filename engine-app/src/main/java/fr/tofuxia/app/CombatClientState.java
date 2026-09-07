package fr.tofuxia.app;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable client projection; it can request actions but never predicts authoritative outcomes. */
public record CombatClientState(String combatId, int workerProtocolVersion, long eventSequence, String phase,
                                int serverTick, int round, int turnIndex, long activeEntityId,
                                int turnDeadlineTick, String mapId, long mapRevision,
                                TacticalMap tacticalMap,
                                List<Participant> participants, Placement placement,
                                long rngDrawCount, List<Event> events, Result result,
                                List<String> diagnostics) {
    public static final CombatClientState EMPTY = new CombatClientState("", 0, 0, "", 0, 0, -1, 0, 0,
            "", 0, TacticalMap.EMPTY, List.of(), Placement.EMPTY, 0, List.of(), Result.EMPTY, List.of());

    public record Cell(int x, int z) {}
    public record Participant(long entityId, String ownerSessionId, String side, String kind, long ownerEntityId,
                              Cell cell, double hitPoints, int actionPoints, int movementPoints,
                              boolean dead, boolean surrendered, boolean spectator, int remainingLifetimeTurns,
                              List<String> spellIds, double shieldPoints, Map<String, Double> effectiveStatistics,
                              List<String> equippedItemIds, String movementProfileId, String targetProfileId) {
        public Participant {
            spellIds = List.copyOf(spellIds); effectiveStatistics = Map.copyOf(effectiveStatistics);
            equippedItemIds = List.copyOf(equippedItemIds);
        }
    }
    public record SurfaceCell(int x, int z, double surfaceY, boolean walkable, boolean blocksLineOfSight,
                              String renderPolicy, boolean fluid, int checkerboardParity, String biomeId) {}
    public record TacticalMap(String dominantBiomeId, String cacheIdentity, List<SurfaceCell> cells) {
        public static final TacticalMap EMPTY = new TacticalMap("", "", List.of());
        public TacticalMap { cells = List.copyOf(cells); }
    }
    public record Assignment(long entityId, Cell cell, String reason, String ownerSessionId, boolean enemyFixed,
                             List<Cell> movementPath, int movementStartTick, int movementDeadlineTick, String speedCurve) {
        public Assignment { movementPath = List.copyOf(movementPath); }
    }
    public record Placement(String phase, List<Assignment> assignments, Set<String> readySessionIds,
                            int masterDeadlineTick, int graceDeadlineTick, int transitionDeadlineTick, boolean locked) {
        public static final Placement EMPTY = new Placement("", List.of(), Set.of(), 0, 0, 0, false);
        public Placement { assignments = List.copyOf(assignments); readySessionIds = Set.copyOf(readySessionIds); }
    }
    public record Event(long sequence, String type, long entityId, long targetEntityId, Map<String, String> values) {
        public Event { values = Map.copyOf(values); }
    }
    public record CharacterResult(long entityId, boolean livingVictor, boolean surrendered, float x, float y, float z,
                                  String outcomePolicyId) {}
    public record Result(String outcome, String reason, long finalEventSequence, List<CharacterResult> characters,
                         List<String> diagnostics) {
        public static final Result EMPTY = new Result("", "", 0, List.of(), List.of());
        public Result { characters = List.copyOf(characters); diagnostics = List.copyOf(diagnostics); }
    }

    public CombatClientState {
        combatId = combatId == null ? "" : combatId; phase = phase == null ? "" : phase;
        mapId = mapId == null ? "" : mapId; tacticalMap = tacticalMap == null ? TacticalMap.EMPTY : tacticalMap;
        participants = List.copyOf(participants);
        placement = placement == null ? Placement.EMPTY : placement; events = List.copyOf(events);
        result = result == null ? Result.EMPTY : result; diagnostics = List.copyOf(diagnostics);
    }
    public boolean active() { return !combatId.isBlank() && result.outcome().isBlank(); }
    public Participant activeParticipant() { return participants.stream().filter(value -> value.entityId() == activeEntityId).findFirst().orElse(null); }
}
