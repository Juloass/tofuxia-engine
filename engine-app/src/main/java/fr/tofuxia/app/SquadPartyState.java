package fr.tofuxia.app;

import java.util.List;

/** Client read model for private squad control and cooperative party capacity. */
public record SquadPartyState(long squadRevision, String serverMode, int deploymentLimit,
                              String leaderCharacterId, long leaderEntityId, List<SquadMember> squad,
                              long partyRevision, String partyId, String partyLeaderCharacterId,
                              int partyDeployedCount, int partyCapacity, List<PartyMember> partyMembers,
                              List<Invitation> invitations, String diagnostic) {
    public record SquadMember(String characterId, long entityId, String name, int formationSlot, boolean leader) {}
    public record PartyMember(String accountId, List<String> deployedCharacterIds) {
        public PartyMember { deployedCharacterIds = List.copyOf(deployedCharacterIds); }
    }
    public record Invitation(String partyId, String invitingAccountId, boolean acceptEnabled, String reason) {}
    public SquadPartyState {
        squad = List.copyOf(squad); partyMembers = List.copyOf(partyMembers); invitations = List.copyOf(invitations);
        serverMode = serverMode == null ? "" : serverMode; leaderCharacterId = leaderCharacterId == null ? "" : leaderCharacterId;
        partyId = partyId == null ? "" : partyId; partyLeaderCharacterId = partyLeaderCharacterId == null ? "" : partyLeaderCharacterId;
        diagnostic = diagnostic == null ? "" : diagnostic;
    }
    public static final SquadPartyState EMPTY = new SquadPartyState(0, "", 1, "", 0, List.of(),
            0, "", "", 0, 8, List.of(), List.of(), "");
}
