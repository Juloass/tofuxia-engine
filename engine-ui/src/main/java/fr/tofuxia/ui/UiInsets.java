package fr.tofuxia.ui;

public record UiInsets(float left, float top, float right, float bottom) {
    public static UiInsets all(float value) { return new UiInsets(value, value, value, value); }
}
