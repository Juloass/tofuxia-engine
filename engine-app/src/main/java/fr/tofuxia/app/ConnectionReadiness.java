package fr.tofuxia.app;

/** Single lifecycle rule shared by the rendered client and connected smoke tests. */
public final class ConnectionReadiness {
    private ConnectionReadiness() {}

    public static AppState resolve(boolean remoteConnectionRequested, NetworkWorldState world) {
        if (!remoteConnectionRequested) return AppState.IN_GAME;
        if (world == null || !world.connected()) return AppState.CONNECTING;
        return world.chunkCache().isEmpty() ? AppState.WORLD_LOADING : AppState.IN_GAME;
    }

    /**
     * An authenticated account must be able to create or select a character before world initialization exists.
     * This state intentionally takes precedence over the generic connecting overlay in the UI router.
     */
    public static boolean awaitingCharacterChoice(boolean remoteConnectionRequested, AccountRosterState roster,
                                                   long playerEntityId) {
        return remoteConnectionRequested && roster != null && roster.authenticated() && playerEntityId <= 0;
    }
}
