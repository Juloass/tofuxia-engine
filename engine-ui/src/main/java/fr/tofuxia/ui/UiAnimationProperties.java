package fr.tofuxia.ui;

import io.github.juloass.uianimation.AnimationProperty;
import io.github.juloass.uianimation.UiTransform;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tofuxia property declarations for shared UI animation. */
public final class UiAnimationProperties {
    public static final AnimationProperty<Double> OPACITY = AnimationProperty.of("opacity", Double.class);
    public static final AnimationProperty<Double> FADE = AnimationProperty.of("fade", Double.class);
    public static final AnimationProperty<UiTransform> TRANSFORM = AnimationProperty.of("transform", UiTransform.class);
    private static final Map<String, AnimationProperty<Double>> SCALARS = new ConcurrentHashMap<>();
    private UiAnimationProperties() {}
    public static AnimationProperty<Double> scalar(String name) {
        return SCALARS.computeIfAbsent(name, value -> AnimationProperty.of(value, Double.class));
    }
}
