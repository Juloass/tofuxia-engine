package fr.tofuxia.app;

public record ClientInputEvents(
        boolean combatToggle,
        boolean gameModeToggle,
        int hotbarSelection,
        boolean leftMouseClicked,
        boolean rightMouseClicked,
        float clickRayOriginX,
        float clickRayOriginY,
        float clickRayOriginZ,
        float clickRayDirectionX,
        float clickRayDirectionY,
        float clickRayDirectionZ,
        boolean clickInWorldViewport
) {
    public static final ClientInputEvents EMPTY = new ClientInputEvents(false, false, -1,
            false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, false);

    public boolean hasEvents() {
        return combatToggle || gameModeToggle || hotbarSelection >= 0 || leftMouseClicked || rightMouseClicked;
    }

}
