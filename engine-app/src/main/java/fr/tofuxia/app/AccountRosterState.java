package fr.tofuxia.app;

import java.util.List;
import java.util.Map;

public record AccountRosterState(boolean authenticated, String accountId, int maximumCharacters,
                                 List<CharacterSummary> characters, String selectedCharacterId,
                                 String diagnostic) {
    public record CharacterSummary(String characterId, String name, int level, long entityId,
                                   Map<String, String> appearance) {
        public CharacterSummary { appearance = Map.copyOf(appearance); }
    }
    public AccountRosterState { characters = List.copyOf(characters); diagnostic = diagnostic == null ? "" : diagnostic; }
    public static final AccountRosterState EMPTY = new AccountRosterState(false, "", 4, List.of(), "", "");
}
