package fr.tofuxia.ui;

public record UiEvent(Type type, String targetId, float localX, float localY, float wheel,
                      String text, EditKey editKey) {
    public enum Type { ENTER, LEAVE, PRESS, DRAG, RELEASE, CLICK, FOCUS, BLUR, SCROLL, TEXT_INPUT, EDIT }
    public enum EditKey { NONE, BACKSPACE, DELETE, LEFT, RIGHT, HOME, END }
}
