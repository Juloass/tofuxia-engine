package fr.tofuxia.desktop;

import fr.tofuxia.app.EnginePlatform;
import fr.tofuxia.app.FramebufferSize;
import fr.tofuxia.app.InputSnapshot;
import fr.tofuxia.app.PlatformFrameState;

import static org.lwjgl.glfw.GLFW.*;

final class DesktopPlatform implements EnginePlatform {
    private final long window;
    private final DesktopInput input;
    private final DesktopClientSettings settings;
    private final int[] width = new int[1];
    private final int[] height = new int[1];
    private final int[] windowWidth = new int[1];
    private final int[] windowHeight = new int[1];
    private final float[] contentScaleX = new float[1];
    private final float[] contentScaleY = new float[1];
    private final long arrowCursor;
    private final long pointingHandCursor;
    private fr.tofuxia.app.CursorStyle cursorStyle = fr.tofuxia.app.CursorStyle.ARROW;
    private boolean closed;
    private fr.tofuxia.app.ClientSettingsState.Graphics appliedGraphics;

    DesktopPlatform(long window, DesktopInput input, DesktopClientSettings settings,
                    fr.tofuxia.app.ClientSettingsState.Graphics initialGraphics) {
        this.window = window;
        this.input = input;
        this.settings = settings;
        this.arrowCursor = glfwCreateStandardCursor(GLFW_ARROW_CURSOR);
        this.pointingHandCursor = glfwCreateStandardCursor(GLFW_POINTING_HAND_CURSOR);
        this.appliedGraphics = initialGraphics;
    }

    public void pollEvents() {
        glfwPollEvents();
        applyGraphics();
    }

    private void applyGraphics() {
        var graphics = settings.appliedGraphics();
        if (graphics.equals(appliedGraphics)) return;
        String[] dimensions = graphics.resolution().split("x", 2);
        int requestedWidth = Integer.parseInt(dimensions[0]);
        int requestedHeight = Integer.parseInt(dimensions[1]);
        long monitor = glfwGetPrimaryMonitor();
        var mode = monitor == 0 ? null : glfwGetVideoMode(monitor);
        glfwRestoreWindow(window);
        switch (graphics.displayMode()) {
            case "FULLSCREEN" -> {
                glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE);
                glfwSetWindowMonitor(window, monitor, 0, 0, requestedWidth, requestedHeight,
                        mode == null ? GLFW_DONT_CARE : mode.refreshRate());
            }
            case "BORDERLESS" -> {
                glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
                int[] monitorX = new int[1], monitorY = new int[1];
                if (monitor != 0) glfwGetMonitorPos(monitor, monitorX, monitorY);
                glfwSetWindowMonitor(window, 0, monitorX[0], monitorY[0], mode == null ? requestedWidth : mode.width(),
                        mode == null ? requestedHeight : mode.height(), GLFW_DONT_CARE);
            }
            default -> {
                glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE);
                int[] workX = new int[1], workY = new int[1], workWidth = new int[1], workHeight = new int[1];
                if (monitor != 0) glfwGetMonitorWorkarea(monitor, workX, workY, workWidth, workHeight);
                int x = monitor == 0 ? 80 : workX[0] + Math.max(0, (workWidth[0] - requestedWidth) / 2);
                int y = monitor == 0 ? 80 : workY[0] + Math.max(0, (workHeight[0] - requestedHeight) / 2);
                glfwSetWindowMonitor(window, 0, x, y, requestedWidth, requestedHeight, GLFW_DONT_CARE);
            }
        }
        glfwFocusWindow(window);
        appliedGraphics = graphics;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }

    public void requestClose() {
        glfwSetWindowShouldClose(window, true);
    }

    public FramebufferSize framebufferSize() {
        glfwGetFramebufferSize(window, width, height);
        return new FramebufferSize(width[0], height[0]);
    }

    @Override
    public fr.tofuxia.app.DisplayMetrics displayMetrics() {
        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, width, height);
        glfwGetWindowContentScale(window, contentScaleX, contentScaleY);
        return new fr.tofuxia.app.DisplayMetrics(windowWidth[0], windowHeight[0], width[0], height[0],
                contentScaleX[0], contentScaleY[0]);
    }

    public InputSnapshot input() {
        return input.snapshot();
    }

    @Override
    public void setCursorStyle(fr.tofuxia.app.CursorStyle style) {
        fr.tofuxia.app.CursorStyle next = style == null ? fr.tofuxia.app.CursorStyle.ARROW : style;
        if (next == cursorStyle) return;
        cursorStyle = next;
        glfwSetCursor(window, next == fr.tofuxia.app.CursorStyle.POINTING_HAND ? pointingHandCursor : arrowCursor);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        input.close();
        if (arrowCursor != 0) glfwDestroyCursor(arrowCursor);
        if (pointingHandCursor != 0) glfwDestroyCursor(pointingHandCursor);
    }

    @Override
    public PlatformFrameState frameState() {
        return new PlatformFrameState(
                glfwGetWindowAttrib(window, GLFW_FOCUSED) == GLFW_TRUE,
                glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE);
    }
}
