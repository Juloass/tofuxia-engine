package fr.tofuxia.desktop;

import fr.tofuxia.app.InputCommand;
import fr.tofuxia.app.InputSnapshot;
import fr.tofuxia.renderapi.DebugViewMode;
import io.github.juloass.identifier.Identifier;
import io.github.juloass.input.*;
import io.github.juloass.input.glfw.GlfwInputSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/** Adapts shared GLFW input into Tofuxia frame input. */
final class DesktopInput implements AutoCloseable {
    private static final int MAX_CHAT_LENGTH = 256;
    private static final Identifier CONTEXT_ID = Identifier.parse("tofuxia:desktop_input");
    private static final EnumMap<DesktopClientSettings.Action, DigitalAction> ACTIONS = actions();
    private static final DigitalAction POINTER_LEFT = DigitalAction.of("tofuxia:pointer_left");
    private static final DigitalAction POINTER_RIGHT = DigitalAction.of("tofuxia:pointer_right");
    private static final DigitalAction POINTER_MIDDLE = DigitalAction.of("tofuxia:pointer_middle");
    private static final DigitalAction GAMEPAD_ACTIVATE = DigitalAction.of("tofuxia:gamepad_activate");
    private static final DigitalAction GAMEPAD_CANCEL = DigitalAction.of("tofuxia:gamepad_cancel");
    private static final DigitalAction GAMEPAD_PREVIOUS = DigitalAction.of("tofuxia:gamepad_previous");
    private static final DigitalAction GAMEPAD_NEXT = DigitalAction.of("tofuxia:gamepad_next");
    private static final DigitalAction GAMEPAD_LEFT = DigitalAction.of("tofuxia:gamepad_left");
    private static final DigitalAction GAMEPAD_RIGHT = DigitalAction.of("tofuxia:gamepad_right");
    private static final DigitalAction GAMEPAD_UP = DigitalAction.of("tofuxia:gamepad_up");
    private static final DigitalAction GAMEPAD_DOWN = DigitalAction.of("tofuxia:gamepad_down");

    private final long window;
    private final DesktopClientSettings settings;
    private final GlfwInputSource source;
    private final InputEvaluator evaluator = new InputEvaluator();
    private final StringBuilder chatDraft = new StringBuilder();
    private boolean chatActive;
    private String submittedChat;
    private double lastX;
    private double lastY;
    private boolean first = true;
    private boolean shiftDown;
    private InputVector2 eventPointer = InputVector2.ZERO;
    private final int[] windowWidth = new int[1], windowHeight = new int[1];
    private final int[] framebufferWidth = new int[1], framebufferHeight = new int[1];

    DesktopInput(long window, DesktopClientSettings settings) {
        this.window = window;
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        source = new GlfwInputSource(window);
    }

    InputSnapshot snapshot() {
        InputPollResult poll = source.poll();
        if (poll.failure().isPresent()) {
            evaluator.reset(source.snapshot());
            throw new IllegalStateException("desktop input collection failed: " + poll.failure().orElseThrow());
        }
        RawInputBatch raw = poll.batch().orElseThrow();
        LogicalInputBatch logical = evaluator.evaluate(raw, bindingSet(), contextStack());
        Map<Long, List<DigitalActionTransition>> logicalBySource = new HashMap<>();
        List<DigitalActionTransition> mappingChanges = new ArrayList<>();
        for (LogicalInputTransition transition : logical.transitions()) {
            if (!(transition instanceof DigitalActionTransition digital)) continue;
            if (digital.sourceSequence().isPresent()) {
                logicalBySource.computeIfAbsent(digital.sourceSequence().getAsLong(), ignored -> new ArrayList<>()).add(digital);
            } else mappingChanges.add(digital);
        }

        Frame frame = new Frame();
        frame.pointer = eventPointer;
        for (RawInputTransition transition : raw.transitions()) {
            if (transition instanceof ButtonTransition button) processRawButton(button, frame);
            else if (transition instanceof TextInputTransition text) processText(text, frame);
            else if (transition instanceof ScrollTransition scroll) frame.wheel += (float) scroll.amount().y();
            else if (transition instanceof PointerMoveTransition pointer) {
                frame.pointer = pointer.position();
                eventPointer = pointer.position();
            }
            List<DigitalActionTransition> changes = logicalBySource.get(transition.sequence());
            if (changes != null) for (DigitalActionTransition change : changes) processAction(change, frame);
        }
        for (DigitalActionTransition change : mappingChanges) processAction(change, frame);

        RawInputSnapshot state = raw.snapshot();
        InputVector2 pointer = state.pointer(source.pointerId());
        glfwGetWindowSize(window, windowWidth, windowHeight);
        glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
        double scaleX = framebufferWidth[0] / (double) Math.max(1, windowWidth[0]);
        double scaleY = framebufferHeight[0] / (double) Math.max(1, windowHeight[0]);
        double x = pointer.x() * scaleX;
        double y = pointer.y() * scaleY;
        if (first) { lastX = x; lastY = y; first = false; }
        float dx = (float) (x - lastX);
        float dy = (float) (y - lastY);
        lastX = x; lastY = y;

        boolean leftDown = logical.snapshot().value(POINTER_LEFT);
        boolean rightDown = logical.snapshot().value(POINTER_RIGHT);
        boolean middleDown = logical.snapshot().value(POINTER_MIDDLE);
        float moveX = chatActive ? 0.0f : digitalAxis(logical.snapshot(), DesktopClientSettings.Action.MOVE_LEFT,
                DesktopClientSettings.Action.MOVE_RIGHT);
        float moveZ = chatActive ? 0.0f : digitalAxis(logical.snapshot(), DesktopClientSettings.Action.MOVE_FORWARD,
                DesktopClientSettings.Action.MOVE_BACK);

        String submitted = submittedChat;
        submittedChat = null;
        return new InputSnapshot(frame.quit, frame.stats, frame.reload, frame.pause, frame.uiScale,
                frame.scene, frame.lightPreset, frame.debugView, moveX, moveZ, (float) x, (float) y, dx, dy,
                frame.wheel, frame.leftClicked, leftDown, frame.leftReleased, frame.rightClicked,
                rightDown, middleDown, frame.uiTyped.toString(), frame.uiBackspace, frame.uiDelete,
                frame.uiLeft, frame.uiRight, frame.uiHome, frame.uiEnd, frame.uiUnfocus,
                frame.uiNavNext, frame.uiNavPrevious, frame.uiNavActivate, frame.uiNavCancel,
                frame.uiNavX, frame.uiNavY, frame.uiController, frame.combatToggle,
                frame.gameModeToggle, frame.mipmapToggle, frame.hotbarSelection, chatActive,
                chatDraft.toString(), submitted, frame.commands);
    }

    private void processRawButton(ButtonTransition button, Frame frame) {
        if (!(button.control().control() instanceof KeyboardKey key)) return;
        if (chatActive) {
            frame.suppressedSources.add(button.sequence());
            if (button.kind() == ButtonTransitionKind.RELEASE) return;
            if (key == KeyboardKey.ESCAPE && button.kind() == ButtonTransitionKind.PRESS) {
                chatActive = false; chatDraft.setLength(0);
            } else if (key == KeyboardKey.BACKSPACE && !chatDraft.isEmpty()) {
                chatDraft.deleteCharAt(chatDraft.length() - 1);
            } else if ((key == KeyboardKey.ENTER || key == KeyboardKey.KP_ENTER)
                    && button.kind() == ButtonTransitionKind.PRESS) {
                String text = chatDraft.toString().trim();
                if (!text.isEmpty()) submittedChat = text;
                chatDraft.setLength(0); chatActive = false;
            }
            return;
        }
        if (key == KeyboardKey.LEFT_SHIFT || key == KeyboardKey.RIGHT_SHIFT) {
            shiftDown = button.kind() != ButtonTransitionKind.RELEASE;
        }
        if (button.kind() != ButtonTransitionKind.PRESS) return;
        if (key == KeyboardKey.TAB) {
            if (shiftDown) frame.uiNavPrevious = true;
            else frame.uiNavNext = true;
        } else if (key == KeyboardKey.ENTER || key == KeyboardKey.KP_ENTER || key == KeyboardKey.SPACE) {
            frame.uiNavActivate = true;
        }
        else if (key == KeyboardKey.ESCAPE) frame.uiNavCancel = true;
        else if (key == KeyboardKey.UP) frame.uiNavY = -1;
        else if (key == KeyboardKey.DOWN) frame.uiNavY = 1;
        else if (key == KeyboardKey.LEFT) frame.uiNavX = -1;
        else if (key == KeyboardKey.RIGHT) frame.uiNavX = 1;
    }

    private void processText(TextInputTransition text, Frame frame) {
        if (text.codePoint() < 32 || text.codePoint() == 127) return;
        if (chatActive) {
            if (chatDraft.length() < MAX_CHAT_LENGTH) chatDraft.appendCodePoint(text.codePoint());
        } else frame.uiTyped.appendCodePoint(text.codePoint());
    }

    private void processAction(DigitalActionTransition transition, Frame frame) {
        if (transition.sourceSequence().isPresent()
                && frame.suppressedSources.contains(transition.sourceSequence().getAsLong())) return;
        boolean pointerAction = transition.action().equals(POINTER_LEFT) || transition.action().equals(POINTER_RIGHT)
                || transition.action().equals(POINTER_MIDDLE);
        frame.commands.add(new InputCommand(transition.sequence(), transition.action().id(), transition.kind(),
                pointerAction ? java.util.Optional.of(frame.pointer) : java.util.Optional.empty()));
        if (transition.action().equals(POINTER_LEFT) && transition.kind() == ButtonTransitionKind.RELEASE) {
            frame.leftReleased = true;
        }
        if (transition.kind() != ButtonTransitionKind.PRESS) return;
        DesktopClientSettings.Action action = action(transition.action());
        if (action != null) switch (action) {
            case QUIT -> { frame.quit = true; frame.uiUnfocus = true; }
            case CHAT -> chatActive = true;
            case STATS -> frame.stats = true; case RELOAD -> frame.reload = true;
            case PAUSE -> frame.pause = true; case UI_SCALE -> frame.uiScale = true;
            case COMBAT_TOGGLE -> frame.combatToggle = true; case SCENE_1 -> frame.scene = 0;
            case SCENE_2 -> frame.scene = 1; case GAME_MODE_TOGGLE -> frame.gameModeToggle = true;
            case DEBUG_ALBEDO -> frame.debugView = DebugViewMode.ALBEDO;
            case DEBUG_SHADOW_FACTOR -> frame.debugView = DebugViewMode.SHADOW_FACTOR;
            case MIPMAP_TOGGLE -> frame.mipmapToggle = true;
            case DEBUG_TONE_MAPPED -> frame.debugView = DebugViewMode.TONE_MAPPED;
            case UI_BACKSPACE -> frame.uiBackspace = true; case UI_DELETE -> frame.uiDelete = true;
            case UI_LEFT -> frame.uiLeft = true; case UI_RIGHT -> frame.uiRight = true;
            case UI_HOME -> frame.uiHome = true; case UI_END -> frame.uiEnd = true;
            default -> {
                int hotbar = action.ordinal() - DesktopClientSettings.Action.HOTBAR_1.ordinal();
                if (hotbar >= 0 && hotbar < 9) {
                    frame.hotbarSelection = hotbar;
                    if (hotbar <= 3) frame.lightPreset = hotbar;
                }
            }
        }
        if (transition.action().equals(POINTER_LEFT)) frame.leftClicked = true;
        else if (transition.action().equals(POINTER_RIGHT)) frame.rightClicked = true;
        else if (transition.action().equals(GAMEPAD_ACTIVATE)) { frame.uiNavActivate = true; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_CANCEL)) { frame.uiNavCancel = true; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_PREVIOUS)) { frame.uiNavPrevious = true; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_NEXT)) { frame.uiNavNext = true; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_LEFT)) { frame.uiNavX = -1; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_RIGHT)) { frame.uiNavX = 1; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_UP)) { frame.uiNavY = -1; frame.uiController = true; }
        else if (transition.action().equals(GAMEPAD_DOWN)) { frame.uiNavY = 1; frame.uiController = true; }
    }

    private InputBindingSet bindingSet() {
        InputBindingSet.Builder bindings = InputBindingSet.builder();
        DeviceSelector keyboards = DeviceSelector.deviceClass(InputDeviceClass.KEYBOARD);
        Map<String, List<String>> applied = settings.appliedBindings();
        for (DesktopClientSettings.Action action : DesktopClientSettings.Action.values()) {
            for (String name : applied.getOrDefault(action.id, List.of())) {
                KeyboardKey key = keyboardKey(name);
                if (key != null) bindings.bind(CONTEXT_ID, ACTIONS.get(action), new DirectButtonBinding(keyboards, key));
            }
        }
        DeviceSelector pointers = DeviceSelector.deviceClass(InputDeviceClass.POINTER);
        bindings.bind(CONTEXT_ID, POINTER_LEFT, new DirectButtonBinding(pointers, PointerButton.PRIMARY));
        bindings.bind(CONTEXT_ID, POINTER_RIGHT, new DirectButtonBinding(pointers, PointerButton.SECONDARY));
        bindings.bind(CONTEXT_ID, POINTER_MIDDLE, new DirectButtonBinding(pointers, PointerButton.MIDDLE));
        DeviceSelector gamepads = DeviceSelector.deviceClass(InputDeviceClass.GAMEPAD);
        bind(bindings, GAMEPAD_ACTIVATE, gamepads, GamepadButton.A); bind(bindings, GAMEPAD_CANCEL, gamepads, GamepadButton.B);
        bind(bindings, GAMEPAD_PREVIOUS, gamepads, GamepadButton.LEFT_BUMPER); bind(bindings, GAMEPAD_NEXT, gamepads, GamepadButton.RIGHT_BUMPER);
        bind(bindings, GAMEPAD_LEFT, gamepads, GamepadButton.DPAD_LEFT); bind(bindings, GAMEPAD_RIGHT, gamepads, GamepadButton.DPAD_RIGHT);
        bind(bindings, GAMEPAD_UP, gamepads, GamepadButton.DPAD_UP); bind(bindings, GAMEPAD_DOWN, gamepads, GamepadButton.DPAD_DOWN);
        return bindings.build();
    }

    private InputContextStack contextStack() {
        InputContext.Builder context = InputContext.builder(CONTEXT_ID, 0);
        for (DigitalAction action : ACTIONS.values()) context.action(action, ConsumptionMode.OBSERVE);
        context.action(POINTER_LEFT, ConsumptionMode.OBSERVE).action(POINTER_RIGHT, ConsumptionMode.OBSERVE)
                .action(POINTER_MIDDLE, ConsumptionMode.OBSERVE).action(GAMEPAD_ACTIVATE, ConsumptionMode.OBSERVE)
                .action(GAMEPAD_CANCEL, ConsumptionMode.OBSERVE).action(GAMEPAD_PREVIOUS, ConsumptionMode.OBSERVE)
                .action(GAMEPAD_NEXT, ConsumptionMode.OBSERVE).action(GAMEPAD_LEFT, ConsumptionMode.OBSERVE)
                .action(GAMEPAD_RIGHT, ConsumptionMode.OBSERVE).action(GAMEPAD_UP, ConsumptionMode.OBSERVE)
                .action(GAMEPAD_DOWN, ConsumptionMode.OBSERVE);
        return InputContextStack.of(context.build());
    }

    private static float digitalAxis(LogicalInputSnapshot state, DesktopClientSettings.Action negative,
                                     DesktopClientSettings.Action positive) {
        return (state.value(ACTIONS.get(positive)) ? 1.0f : 0.0f) - (state.value(ACTIONS.get(negative)) ? 1.0f : 0.0f);
    }
    private static void bind(InputBindingSet.Builder bindings, DigitalAction action, DeviceSelector devices, ButtonControl control) {
        bindings.bind(CONTEXT_ID, action, new DirectButtonBinding(devices, control));
    }
    private static DesktopClientSettings.Action action(DigitalAction value) {
        for (var entry : ACTIONS.entrySet()) if (entry.getValue().equals(value)) return entry.getKey();
        return null;
    }
    private static EnumMap<DesktopClientSettings.Action, DigitalAction> actions() {
        EnumMap<DesktopClientSettings.Action, DigitalAction> result = new EnumMap<>(DesktopClientSettings.Action.class);
        for (DesktopClientSettings.Action action : DesktopClientSettings.Action.values()) {
            result.put(action, DigitalAction.of("tofuxia:" + action.id));
        }
        return result;
    }
    private static KeyboardKey keyboardKey(String value) {
        if (value == null) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace("GLFW_KEY_", "");
        if (normalized.equals("ESC")) normalized = "ESCAPE";
        if (normalized.length() == 1 && Character.isDigit(normalized.charAt(0))) normalized = "DIGIT_" + normalized;
        try { return KeyboardKey.valueOf(normalized); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    @Override public void close() { source.close(); }

    private static final class Frame {
        boolean quit, stats, reload, pause, uiScale, uiBackspace, uiDelete, uiLeft, uiRight, uiHome, uiEnd, uiUnfocus;
        boolean uiNavNext, uiNavPrevious, uiNavActivate, uiNavCancel, uiController;
        boolean combatToggle, gameModeToggle, mipmapToggle, leftClicked, leftReleased, rightClicked;
        int scene = -1, lightPreset = -1, hotbarSelection = -1, uiNavX, uiNavY;
        DebugViewMode debugView;
        float wheel;
        final StringBuilder uiTyped = new StringBuilder();
        final List<InputCommand> commands = new ArrayList<>();
        final java.util.Set<Long> suppressedSources = new java.util.HashSet<>();
        InputVector2 pointer = InputVector2.ZERO;
    }
}
