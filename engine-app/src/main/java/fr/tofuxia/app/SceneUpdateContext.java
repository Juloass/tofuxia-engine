package fr.tofuxia.app;

import fr.tofuxia.renderapi.RenderViewport;

public record SceneUpdateContext(float deltaSeconds, float timeSeconds, boolean animate, int lightPreset,
                                 NetworkWorldState networkWorld, float moveX, float moveZ, boolean combatToggle,
                                 boolean diagnosticsVisible,
                                 float cameraYawRadians,
                                 float cursorRayOriginX, float cursorRayOriginY, float cursorRayOriginZ,
                                 float cursorRayDirectionX, float cursorRayDirectionY, float cursorRayDirectionZ,
                                 boolean leftMouseClicked, boolean rightMouseClicked, boolean gameModeToggle,
                                 int hotbarSelection, NetworkStatusProvider network,
                                 boolean cursorInWorldViewport, RenderViewport worldViewport) {
    public SceneUpdateContext {
        network = network == null ? NetworkStatusProvider.NONE : network;
        worldViewport = worldViewport == null ? RenderViewport.fullScreen(1, 1) : worldViewport;
    }

    public SceneUpdateContext(float deltaSeconds, float timeSeconds, boolean animate, int lightPreset,
                              NetworkWorldState networkWorld, float moveX, float moveZ, boolean combatToggle,
                              boolean diagnosticsVisible) {
        this(deltaSeconds, timeSeconds, animate, lightPreset, networkWorld, moveX, moveZ, combatToggle,
                diagnosticsVisible, 0, 0, 0, 0, 0, 0, 0, false, false, false, -1,
                NetworkStatusProvider.NONE, true, RenderViewport.fullScreen(1, 1));
    }

    public SceneUpdateContext(float deltaSeconds, float timeSeconds, boolean animate, int lightPreset,
                              NetworkWorldState networkWorld, float moveX, float moveZ, boolean combatToggle,
                              boolean diagnosticsVisible,
                              float cursorRayOriginX, float cursorRayOriginY, float cursorRayOriginZ,
                              float cursorRayDirectionX, float cursorRayDirectionY, float cursorRayDirectionZ) {
        this(deltaSeconds, timeSeconds, animate, lightPreset, networkWorld, moveX, moveZ, combatToggle,
                diagnosticsVisible, 0, cursorRayOriginX, cursorRayOriginY, cursorRayOriginZ,
                cursorRayDirectionX, cursorRayDirectionY, cursorRayDirectionZ, false, false, false, -1,
                NetworkStatusProvider.NONE, true, RenderViewport.fullScreen(1, 1));
    }

    public SceneUpdateContext(float deltaSeconds, float timeSeconds, boolean animate, int lightPreset,
                              NetworkWorldState networkWorld, float moveX, float moveZ, boolean combatToggle,
                              boolean diagnosticsVisible,
                              float cursorRayOriginX, float cursorRayOriginY, float cursorRayOriginZ,
                              float cursorRayDirectionX, float cursorRayDirectionY, float cursorRayDirectionZ,
                              boolean leftMouseClicked, boolean rightMouseClicked, boolean gameModeToggle,
                              int hotbarSelection, NetworkStatusProvider network) {
        this(deltaSeconds, timeSeconds, animate, lightPreset, networkWorld, moveX, moveZ, combatToggle,
                diagnosticsVisible, 0, cursorRayOriginX, cursorRayOriginY, cursorRayOriginZ,
                cursorRayDirectionX, cursorRayDirectionY, cursorRayDirectionZ,
                leftMouseClicked, rightMouseClicked, gameModeToggle, hotbarSelection, network,
                true, RenderViewport.fullScreen(1, 1));
    }

    public SceneUpdateContext(float deltaSeconds, float timeSeconds, boolean animate, int lightPreset,
                              NetworkWorldState networkWorld, float moveX, float moveZ, boolean combatToggle,
                              boolean diagnosticsVisible,
                              float cameraYawRadians,
                              float cursorRayOriginX, float cursorRayOriginY, float cursorRayOriginZ,
                              float cursorRayDirectionX, float cursorRayDirectionY, float cursorRayDirectionZ,
                              boolean leftMouseClicked, boolean rightMouseClicked, boolean gameModeToggle,
                              int hotbarSelection, NetworkStatusProvider network) {
        this(deltaSeconds, timeSeconds, animate, lightPreset, networkWorld, moveX, moveZ, combatToggle,
                diagnosticsVisible, cameraYawRadians, cursorRayOriginX, cursorRayOriginY, cursorRayOriginZ,
                cursorRayDirectionX, cursorRayDirectionY, cursorRayDirectionZ,
                leftMouseClicked, rightMouseClicked, gameModeToggle, hotbarSelection, network,
                true, RenderViewport.fullScreen(1, 1));
    }

}
