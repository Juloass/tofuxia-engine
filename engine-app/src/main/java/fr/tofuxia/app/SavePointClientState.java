package fr.tofuxia.app;

import io.github.juloass.localization.TextComponent;

import java.util.List;

/** Nearby server-owned save points and the selected character's authoritative active point. */
public record SavePointClientState(String characterId, String activeSavePointId, List<Discovery> discoveries,
                                   boolean lastActivationAccepted, String diagnostic) {
    public record Discovery(String id, TextComponent displayName, double distance, boolean active) {
        public Discovery(String id, String displayName, double distance, boolean active) {
            this(id, TextComponent.literal(displayName), distance, active);
        }
    }
    public static final SavePointClientState EMPTY = new SavePointClientState("", "", List.of(), false, "");
    public SavePointClientState { discoveries = List.copyOf(discoveries); diagnostic = diagnostic == null ? "" : diagnostic; }
    public SavePointClientState withResult(boolean accepted, String activeId, String reason) {
        return new SavePointClientState(characterId, activeId, discoveries, accepted, reason);
    }
}
