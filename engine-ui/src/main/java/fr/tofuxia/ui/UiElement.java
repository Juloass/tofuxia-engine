package fr.tofuxia.ui;

import io.github.juloass.uianimation.AnimationClip;
import io.github.juloass.uianimation.UiTransform;

import io.github.juloass.localization.TextComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.EnumMap;
import java.util.Map;

/**
 * Lightweight declarative node. Instances are expected to be rebuilt each frame;
 * runtime state is retained by {@link UiContext} using {@link #id()}.
 */
public abstract class UiElement {
    private final String id;
    private final UiLayout layout = new UiLayout();
    private final List<UiElement> children = new ArrayList<>();
    private UiTransform transform = UiTransform.IDENTITY;
    private float opacity = 1;
    private float[] tint = UiTheme.rgba(1, 1, 1, 1);
    private boolean visible = true;
    private boolean enabled = true;
    private boolean selected;
    private boolean interactive;
    private boolean focusable;
    private int tabIndex;
    private boolean focusTrap;
    private UiRole role = UiRole.CUSTOM;
    private TextComponent accessibleLabel = TextComponent.literal("");
    private TextComponent accessibleValue = TextComponent.literal("");
    private boolean interactionMotion = true;
    private Consumer<UiEvent> eventHandler;
    private AnimationClip enterAnimation;
    private AnimationClip exitAnimation;
    private final Map<UiVisualState, AnimationClip> stateAnimations = new EnumMap<>(UiVisualState.class);

    protected UiElement(String id) {
        this.id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("UI element id must not be blank");
    }

    public final String id() { return id; }
    public final UiLayout layout() { return layout; }
    public final List<UiElement> children() { return List.copyOf(children); }
    public final UiTransform transform() { return transform; }
    public final float opacity() { return opacity; }
    public final float[] tint() { return tint.clone(); }
    public final boolean visible() { return visible; }
    public final boolean enabled() { return enabled; }
    public final boolean selected() { return selected; }
    public final boolean interactive() { return interactive; }
    public final boolean focusable() { return focusable; }
    public final int tabIndex() { return tabIndex; }
    public final boolean focusTrap() { return focusTrap; }
    public final UiRole role() { return role; }
    public final TextComponent accessibleLabelComponent() { return accessibleLabel; }
    public final TextComponent accessibleValueComponent() { return accessibleValue; }
    public final boolean interactionMotion() { return interactionMotion; }
    final Consumer<UiEvent> eventHandler() { return eventHandler; }
    final AnimationClip enterAnimation() { return enterAnimation; }
    final AnimationClip exitAnimation() { return exitAnimation; }
    final Map<UiVisualState, AnimationClip> stateAnimations() { return stateAnimations; }

    public UiElement child(UiElement child) { children.add(Objects.requireNonNull(child)); return this; }
    public UiElement children(UiElement... values) {
        for (UiElement value : values) child(value);
        return this;
    }
    public UiElement transform(UiTransform value) { transform = value == null ? UiTransform.IDENTITY : value; return this; }
    public UiElement opacity(float value) { opacity = Math.max(0, Math.min(1, value)); return this; }
    public UiElement tint(float[] value) { tint = Objects.requireNonNull(value).clone(); return this; }
    public UiElement visible(boolean value) { visible = value; return this; }
    public UiElement enabled(boolean value) { enabled = value; return this; }
    public UiElement selected(boolean value) { selected = value; return this; }
    public UiElement interactionMotion(boolean value) { interactionMotion = value; return this; }
    public UiElement onEvent(Consumer<UiEvent> handler) {
        interactive = handler != null;
        if (handler != null) focusable = true;
        eventHandler = handler;
        return this;
    }
    public UiElement focusable(boolean value) { focusable = value; return this; }
    public UiElement tabIndex(int value) { tabIndex = value; return this; }
    public UiElement focusTrap(boolean value) { focusTrap = value; return this; }
    public UiElement semantics(UiRole role, String label) {
        return semantics(role, TextComponent.literal(label));
    }
    public UiElement semantics(UiRole role, TextComponent label) {
        this.role = role == null ? UiRole.CUSTOM : role;
        this.accessibleLabel = label == null ? TextComponent.literal("") : label;
        return this;
    }
    public UiElement accessibleValue(String value) {
        return accessibleValue(TextComponent.literal(value));
    }
    public UiElement accessibleValue(TextComponent value) {
        accessibleValue = value == null ? TextComponent.literal("") : value;
        return this;
    }
    public UiElement enter(AnimationClip clip) { enterAnimation = clip; return this; }
    public UiElement exit(AnimationClip clip) { exitAnimation = clip; return this; }
    public UiElement transition(UiVisualState state, AnimationClip clip) {
        stateAnimations.put(Objects.requireNonNull(state), Objects.requireNonNull(clip));
        return this;
    }
}
