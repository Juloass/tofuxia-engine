package fr.tofuxia.app;

import fr.tofuxia.renderapi.RenderViewport;

public record SceneTickContext(
        long tickIndex,
        float fixedDeltaSeconds,
        float renderTimeSeconds,
        boolean animate,
        int lightPreset,
        NetworkWorldState networkWorld,
        float moveX,
        float moveZ,
        boolean combatToggle,
        boolean diagnosticsVisible,
        float cameraYawRadians,
        float cursorRayOriginX,
        float cursorRayOriginY,
        float cursorRayOriginZ,
        float cursorRayDirectionX,
        float cursorRayDirectionY,
        float cursorRayDirectionZ,
        boolean leftMouseClicked,
        boolean rightMouseClicked,
        boolean gameModeToggle,
        int hotbarSelection,
        NetworkStatusProvider network,
        AuthoritativePlayerState authoritativeCorrection,
        long localPlayerEntityId,
        boolean cursorInWorldViewport,
        RenderViewport worldViewport
) {
    public SceneTickContext {
        networkWorld = networkWorld == null ? NetworkWorldState.EMPTY : networkWorld;
        network = network == null ? NetworkStatusProvider.NONE : network;
        worldViewport = worldViewport == null ? RenderViewport.fullScreen(1, 1) : worldViewport;
    }

    public SceneUpdateContext asLegacyUpdateContext() {
        return new SceneUpdateContext(fixedDeltaSeconds, renderTimeSeconds, animate, lightPreset, networkWorld,
                moveX, moveZ, combatToggle, diagnosticsVisible, cameraYawRadians,
                cursorRayOriginX, cursorRayOriginY, cursorRayOriginZ,
                cursorRayDirectionX, cursorRayDirectionY, cursorRayDirectionZ,
                leftMouseClicked, rightMouseClicked, gameModeToggle, hotbarSelection, network,
                cursorInWorldViewport, worldViewport);
    }
}
