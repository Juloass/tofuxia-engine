package fr.tofuxia.app;

import fr.tofuxia.renderapi.DebugViewMode;
import java.util.List;

public record InputSnapshot(
        boolean quit,
        boolean stats,
        boolean reload,
        boolean pause,
        boolean uiScale,
        int sceneSlot,
        int lightPreset,
        DebugViewMode debugView,
        float moveX,
        float moveZ,
        float mouseX,
        float mouseY,
        float mouseDx,
        float mouseDy,
        float wheel,
        boolean leftMouseClicked,
        boolean leftMouseDown,
        boolean leftMouseReleased,
        boolean rightMouseClicked,
        boolean rightMouseDown,
        boolean middleMouseDown,
        String uiTyped,
        boolean uiBackspace,
        boolean uiDelete,
        boolean uiLeft,
        boolean uiRight,
        boolean uiHome,
        boolean uiEnd,
        boolean uiUnfocus,
        boolean uiNavNext,
        boolean uiNavPrevious,
        boolean uiNavActivate,
        boolean uiNavCancel,
        int uiNavX,
        int uiNavY,
        boolean uiController,
        boolean combatToggle,
        boolean gameModeToggle,
        boolean mipmapToggle,
        int hotbarSelection,
        boolean chatActive,
        String chatDraft,
        String chatSubmit,
        List<InputCommand> commands) {
    public InputSnapshot {
        commands = List.copyOf(commands == null ? List.of() : commands);
    }

    public InputSnapshot(
            boolean quit, boolean stats, boolean reload, boolean pause, boolean uiScale,
            int sceneSlot, int lightPreset, DebugViewMode debugView, float moveX, float moveZ,
            float mouseX, float mouseY, float mouseDx, float mouseDy, float wheel,
            boolean leftMouseClicked, boolean leftMouseDown, boolean leftMouseReleased,
            boolean rightMouseClicked, boolean rightMouseDown, boolean middleMouseDown,
            String uiTyped, boolean uiBackspace, boolean uiDelete, boolean uiLeft, boolean uiRight,
            boolean uiHome, boolean uiEnd, boolean uiUnfocus, boolean uiNavNext,
            boolean uiNavPrevious, boolean uiNavActivate, boolean uiNavCancel, int uiNavX, int uiNavY,
            boolean uiController, boolean combatToggle, boolean gameModeToggle, boolean mipmapToggle,
            int hotbarSelection, boolean chatActive, String chatDraft, String chatSubmit) {
        this(quit, stats, reload, pause, uiScale, sceneSlot, lightPreset, debugView, moveX, moveZ,
                mouseX, mouseY, mouseDx, mouseDy, wheel, leftMouseClicked, leftMouseDown,
                leftMouseReleased, rightMouseClicked, rightMouseDown, middleMouseDown, uiTyped,
                uiBackspace, uiDelete, uiLeft, uiRight, uiHome, uiEnd, uiUnfocus, uiNavNext,
                uiNavPrevious, uiNavActivate, uiNavCancel, uiNavX, uiNavY, uiController,
                combatToggle, gameModeToggle, mipmapToggle, hotbarSelection, chatActive,
                chatDraft, chatSubmit, List.of());
    }
    public static InputSnapshot empty() {
        return new InputSnapshot(false, false, false, false, false, -1, -1, null,
                0, 0, 0, 0, 0, 0, 0,
                false, false, false, false, false, false, "", false, false, false, false, false, false, false,
                false, false, false, false, 0, 0, false,
                false, false, false, -1, false, "", null, List.of());
    }
}
