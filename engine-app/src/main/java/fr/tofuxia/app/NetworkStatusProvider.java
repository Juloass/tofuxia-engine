package fr.tofuxia.app;

public interface NetworkStatusProvider extends AutoCloseable {
    NetworkStatusProvider NONE = new NetworkStatusProvider() {
        public String statusLine() {
            return "Network: local/offline";
        }

        public NetworkWorldState worldState() {
            return NetworkWorldState.EMPTY;
        }

        public String[] chatLines() {
            return new String[0];
        }

        public void sendChat(String text) {
        }

        public void requestBlockEdit(BlockEditIntent intent) {
        }

        public void requestFluidEdit(FluidEditIntent intent) {
        }

        public boolean requestGameMode(GameModeRequest mode) {
            return true;
        }

        public long playerEntityId() {
            return 0;
        }

        public void sendMovementSnapshot(PlayerMovementSnapshot snapshot) {
        }

        public AuthoritativePlayerState consumeLatestCorrection() {
            return null;
        }

        public void close() {
        }
    };

    String statusLine();

    default NetworkWorldState worldState() {
        return NetworkWorldState.EMPTY;
    }

    default String[] chatLines() {
        return new String[0];
    }

    default void sendChat(String text) {
    }

    default void requestBlockEdit(BlockEditIntent intent) {
    }

    default void requestFluidEdit(FluidEditIntent intent) {
    }

    default boolean requestGameMode(GameModeRequest mode) {
        return true;
    }

    default long playerEntityId() {
        return 0;
    }

    default void sendMovementSnapshot(PlayerMovementSnapshot snapshot) {
    }

    default AuthoritativePlayerState consumeLatestCorrection() {
        return null;
    }

    default AccountRosterState accountRosterState() { return AccountRosterState.EMPTY; }

    default void requestCharacterCreate(String name, java.util.Map<String, String> appearance) {}

    default void requestCharacterSelect(String characterId) {}

    default void requestCharacterPlay(java.util.List<String> characterIds) {
        if (characterIds != null && !characterIds.isEmpty()) requestCharacterSelect(characterIds.getFirst());
    }

    default void requestCharacterDelete(String characterId) {}

    default void requestServerChange() {}

    default boolean serverChangeAvailable() { return false; }

    default SquadPartyState squadPartyState() { return SquadPartyState.EMPTY; }

    default void requestSquadCommand(String operation, String characterId) {}

    default void requestPartyCommand(String operation, String targetAccountId, String targetCharacterId, String partyId) {}

    default CombatClientState combatState() { return CombatClientState.EMPTY; }
    default WorldGameplayClientState worldGameplayState() { return WorldGameplayClientState.EMPTY; }
    default PlayerExperienceState playerExperienceState() { return PlayerExperienceState.EMPTY; }
    default SavePointClientState savePointState() { return SavePointClientState.EMPTY; }
    default ClientSettingsState clientSettingsState() { return ClientSettingsState.EMPTY; }
    default void requestCombatMapEdit(CombatMapEditIntent intent) {}
    default void requestSavePointEdit(SavePointEditIntent intent) {}
    default void requestHerdEngagement(String operation, long characterEntityId, String herdId) {}
    default void requestDungeonEntry(String npcId) {}
    default void requestSavePointActivation(String characterId, String savePointId) {}
    default void requestInventoryTransaction(long expectedRevision, String operation, String stackId,
                                             String otherStackId, String itemId, String slotId, int quantity) {}
    default void requestKeyRebind(String actionId, String key, boolean replaceConflict) {}
    default void requestKeyRebind(String actionId, int slot, String key, boolean replaceConflict) {
        requestKeyRebind(actionId, key, replaceConflict);
    }
    default void cancelKeyRebind() {}
    default void restoreDefaultBindings() {}
    default void requestGraphicsSettings(String displayMode, String resolution, boolean vsync,
                                         String quality, int renderDistance, double uiScale) {}
    default void confirmDisplaySettings(boolean keep) {}
    default void requestAudioBus(String busId, double volume, boolean muted) {}
    default void requestAccessibilitySettings(double textScale, boolean reducedUiAnimations, boolean highContrast) {}
    default void beginSettingsEdit() {}
    default void requestLocaleSetting(String locale) {}
    default void resetSettingsSection(String sectionId) {}
    default void cancelSettingsChanges() {}
    default void applySettingsChanges() {}
    default void requestCombatPlacementMove(long entityId, int x, int z) {}
    default void requestCombatReady(boolean ready) {}
    default void requestCombatMove(long entityId, int x, int z) {}
    default void requestCombatCast(long entityId, String spellId, int x, int z) {}
    default void requestCombatEndTurn(long entityId) {}
    default void requestCombatSurrender(long entityId) {}

    /** Starts a new connection/authentication attempt when the current attempt has failed. */
    default boolean requestReconnect() { return false; }

    void close();
}
