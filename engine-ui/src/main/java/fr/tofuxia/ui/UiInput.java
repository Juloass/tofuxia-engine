package fr.tofuxia.ui;

public record UiInput(float mouseX, float mouseY, boolean mouseDown, boolean mouseClicked,
                      boolean mouseReleased, float wheel, String typed,
                      boolean backspace, boolean delete, boolean left, boolean right,
                      boolean home, boolean end, boolean unfocus, float dt,
                      boolean navNext, boolean navPrevious, boolean navActivate, boolean navCancel,
                      int navX, int navY, boolean controller) {
    public UiInput(float mouseX, float mouseY, boolean mouseDown, boolean mouseClicked,
                   boolean mouseReleased, float wheel, String typed,
                   boolean backspace, boolean delete, boolean left, boolean right,
                   boolean home, boolean end, boolean unfocus, float dt) {
        this(mouseX, mouseY, mouseDown, mouseClicked, mouseReleased, wheel, typed,
                backspace, delete, left, right, home, end, unfocus, dt,
                false, false, false, false, 0, 0, false);
    }

    public static UiInput mouseOnly(float mouseX, float mouseY, boolean clicked, float dt) {
        return new UiInput(mouseX, mouseY, false, clicked, false, 0.0f, "",
                false, false, false, false, false, false, false, dt);
    }

    public UiInput withPointer(float x, float y) {
        return new UiInput(x, y, mouseDown, mouseClicked, mouseReleased, wheel, typed,
                backspace, delete, left, right, home, end, unfocus, dt,
                navNext, navPrevious, navActivate, navCancel, navX, navY, controller);
    }
}
