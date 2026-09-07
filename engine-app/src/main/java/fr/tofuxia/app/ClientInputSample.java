package fr.tofuxia.app;

import fr.tofuxia.renderapi.RenderViewport;

public record ClientInputSample(
        float renderDeltaSeconds,
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
        boolean cursorInWorldViewport,
        RenderViewport worldViewport
) {
    public ClientInputSample {
        networkWorld = networkWorld == null ? NetworkWorldState.EMPTY : networkWorld;
        worldViewport = worldViewport == null ? RenderViewport.fullScreen(1, 1) : worldViewport;
    }

    public static ClientInputSample empty() {
        return new ClientInputSample(0.0f, 0.0f, true, 1, NetworkWorldState.EMPTY,
                0.0f, 0.0f, false, false, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                false, false, false, -1, false, RenderViewport.fullScreen(1, 1));
    }
}
