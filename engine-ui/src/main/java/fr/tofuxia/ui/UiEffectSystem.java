package fr.tofuxia.ui;

import io.github.juloass.math.Easing;
import io.github.juloass.math.Easings;
import io.github.juloass.uianimation.AnimationClip;
import io.github.juloass.uianimation.AnimationController;
import io.github.juloass.uianimation.Interpolators;
import io.github.juloass.uianimation.InterruptionPolicy;
import io.github.juloass.uianimation.UiMotionCategory;
import io.github.juloass.uianimation.UiTransform;
import java.time.Duration;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiBuilder;
import fr.tofuxia.render.UiRenderData;

/** Reusable full-screen UI effects: fades and a screen-shake state hook. */
public final class UiEffectSystem {
    private final AnimationController<String> animator = new AnimationController<>();
    private float fadeAlpha;
    private float fadeStart;
    private float fadeTarget;
    private float fadeElapsed;
    private float fadeDuration = 0.001f;
    private Easing fadeEasing = Easings.CUBIC_OUT;
    private float shakeElapsed;
    private float shakeDuration;
    private float shakeStrength;
    private float shakeX;
    private float shakeY;
    private int shakeTick;

    public void fadeToBlack(float duration) {
        startFade(1.0f, duration, Easings.CUBIC_OUT);
    }

    public void fadeInFromBlack(float duration) {
        fadeAlpha = 1.0f;
        startFade(0.0f, duration, Easings.CUBIC_OUT);
    }

    public void fadeTo(float targetAlpha, float duration, Easing easing) {
        startFade(Math.max(0.0f, Math.min(1.0f, targetAlpha)), duration, easing);
    }

    public void shake(float duration, float strength) {
        shakeElapsed = 0.0f;
        shakeDuration = Math.max(0.0f, duration);
        shakeStrength = Math.max(0.0f, strength);
    }

    public void update(float dt) {
        animator.advance(seconds(dt));
        fadeAlpha = animator.valueOrElse("screen-effects", UiAnimationProperties.FADE, (double) fadeAlpha).floatValue();
        if (shakeElapsed < shakeDuration) {
            shakeElapsed = Math.min(shakeDuration, shakeElapsed + Math.max(0.0f, dt));
            float remaining = 1.0f - shakeElapsed / Math.max(0.001f, shakeDuration);
            shakeTick++;
            shakeX = noise(shakeTick * 17) * shakeStrength * remaining;
            shakeY = noise(shakeTick * 31) * shakeStrength * remaining;
        } else {
            shakeX = 0.0f;
            shakeY = 0.0f;
        }
    }

    public float shakeX() {
        return shakeX;
    }

    public float shakeY() {
        return shakeY;
    }

    public float fadeAlpha() {
        return fadeAlpha;
    }

    /** Wraps a tree so screen shake composes as a parent transform. */
    public UiGroup wrap(String id, UiElement content) {
        UiGroup root = new UiGroup(id);
        root.transform(UiTransform.translated(shakeX, shakeY));
        root.child(content);
        return root;
    }

    public UiRenderData buildOverlay(FontAtlas font, int width, int height) {
        UiBuilder b = new UiBuilder(font);
        if (fadeAlpha > 0.001f) {
            b.quad(0, 0, width, height, new float[]{0.0f, 0.0f, 0.0f, Math.min(1.0f, fadeAlpha)});
        }
        return b.toRenderData();
    }

    private void startFade(float target, float duration, Easing easing) {
        fadeStart = fadeAlpha;
        fadeTarget = target;
        fadeElapsed = 0.0f;
        fadeDuration = Math.max(0.001f, duration);
        fadeEasing = easing == null ? Easings.CUBIC_OUT : easing;
        animator.play("screen-effects", AnimationClip.builder()
                        .track(UiAnimationProperties.FADE, (double) fadeStart, (double) fadeTarget,
                                Interpolators.DOUBLE, UiMotionCategory.OPACITY)
                        .then(seconds(fadeDuration), fadeEasing)
                        .build(),
                InterruptionPolicy.RETARGET);
    }

    private float noise(int seed) {
        int n = seed * 1103515245 + 12345;
        n ^= n >>> 16;
        return ((n & 0xFFFF) / 32767.5f) - 1.0f;
    }
    private static Duration seconds(double value) {
        return Duration.ofNanos(Math.max(0L, Math.round(value * 1_000_000_000.0)));
    }
}
