package fr.tofuxia.ui;

import java.util.List;

import io.github.juloass.math.Easing;
import io.github.juloass.math.Easings;
import io.github.juloass.uianimation.*;
import java.time.Duration;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiRenderData;

import java.nio.file.Path;

public final class UiContextSelfTest {
    public static void main(String[] args) {
        FontAtlas font = new FontAtlas(Path.of("test-fixtures/assets/tofuxia/fonts/inter-variable.ttf"), 15);
        UiContext ui = new UiContext(font);

        ui.setUiScale(1.25f);
        assertNear(1.25f, ui.uiScale(), 0.001f, "ui scale preset");

        ui.theme().setWarmAccent();
        assertTrue(ui.theme().accent[0] > ui.theme().accent[1] && ui.theme().accent[1] > ui.theme().accent[2],
                "canonical accent is warm orange");
        assertTrue(java.util.Arrays.equals(ui.theme().titleText, UiTheme.hex("F7F6DE")), "title text is ivory");

        String[] rows = {
                "Glyph QA: : ? j g p q y A",
                "UI scale: 125%",
                "Dynamic panel sizing"
        };
        UiRect measured = ui.measurePanel(10, 12, "Tofuxia Lab", rows, 180.0f);
        assertTrue(measured.w() >= 180.0f, "panel keeps logical minimum width");
        assertTrue(measured.h() > 24.0f, "panel height includes title and rows");

        ui.begin(UiInput.mouseOnly(0, 0, false, 1.0f / 60.0f), 1024, 768);
        UiRect drawn = ui.dynamicTextPanel(10, 12, "Tofuxia Lab", rows, 180.0f);
        UiRect graph = ui.timeSeriesPanel(new UiRect(10, drawn.y1() + 8, 260, 100),
                "Frame time",
                new float[]{12.0f, 14.0f, 18.0f, 28.0f, 42.0f},
                5,
                0,
                16.67f,
                50.0f,
                "ms");
        UiRect stats = ui.statsPanel(10, graph.y1() + 8, "Renderer stats", new String[]{"draws 4", "pipelines 2"});
        UiRect profiler = ui.profilerPanel(10, stats.y1() + 8,
                ProfilerSnapshot.of(16.67f,
                        new ProfilerSnapshot.Entry("renderer", 9.0f,
                                new ProfilerSnapshot.Entry("shadow", 3.2f),
                                new ProfilerSnapshot.Entry("ui", 1.0f)),
                        new ProfilerSnapshot.Entry("particles", 2.5f))
                        .withMemory(new ProfilerSnapshot.Memory(
                                128L * 1024 * 1024,
                                256L * 1024 * 1024,
                                1024L * 1024 * 1024,
                                48L * 1024 * 1024,
                                2L * 1024 * 1024,
                                1,
                                4)),
                360.0f);
        UiRect memory = ui.memoryPanel(10, profiler.y1() + 8,
                new ProfilerSnapshot.Memory(
                        128L * 1024 * 1024,
                        256L * 1024 * 1024,
                        1024L * 1024 * 1024,
                        48L * 1024 * 1024,
                        2L * 1024 * 1024,
                        1,
                        4),
                360.0f);
        UiRenderData mesh = ui.end();
        assertNear(measured.w(), drawn.w(), 0.001f, "drawn panel width matches measurement");
        assertNear(260.0f, graph.w(), 0.001f, "graph panel keeps requested width");
        assertTrue(stats.w() >= 320.0f, "stats panel applies standard logical minimum width");
        assertTrue(profiler.w() >= 360.0f, "profiler panel applies requested logical minimum width");
        assertTrue(memory.w() >= 360.0f, "memory panel applies requested logical minimum width");
        assertTrue(mesh.vertexCount() > 0, "dynamic panel emits vertices");
        assertTrue(mesh.indices().length > 0, "dynamic panel emits indices");

        ui.setUiScale(0.01f);
        assertNear(0.60f, ui.uiScale(), 0.001f, "ui scale lower clamp");
        ui.setUiScale(99.0f);
        assertNear(2.00f, ui.uiScale(), 0.001f, "ui scale upper clamp");

        testAnimationRuntime();
        testElementTree(font);
        testDeclarativeTooltip(font);
        testPointerConsumption(font);
        testDisplayMatrix(font);
        testNavigationAndAccessibility(font);
        testTextLayout(font);
        testLiveLocalization(font);
        System.out.println("[engine-ui] UiContextSelfTest passed");
    }

    private static void testDeclarativeTooltip(FontAtlas font) {
        UiContext ui = new UiContext(font);
        UiBox target = new UiBox("tooltip-target", UiTheme.rgba(1, 1, 1, 1));
        target.layout().position(10, 20).size(60, 30);
        target.onEvent(event -> {});

        ui.begin(UiInput.mouseOnly(20, 30, false, 1f / 60f), 320, 180);
        ui.tooltipFor(target.id(), "Oak Planks · tofuxia:oak_planks");
        ui.submit(target);
        UiRenderData withTooltip = ui.end();

        ui.begin(UiInput.mouseOnly(20, 30, false, 1f / 60f), 320, 180);
        ui.submit(target);
        UiRenderData withoutTooltip = ui.end();
        assertTrue(withTooltip.vertexCount() > withoutTooltip.vertexCount(),
                "declarative hovered elements render their registered tooltip every frame");
    }

    private static void testLiveLocalization(FontAtlas font) {
        var french = io.github.juloass.localization.LocaleId.of("fr_fr");
        var english = io.github.juloass.localization.LocaleId.of("en_us");
        var catalog = io.github.juloass.localization.TranslationCatalog.fromBundles(english, java.util.Map.of(
                french, java.util.Map.of("screen.test.label", "Court"),
                english, java.util.Map.of("screen.test.label", "A much wider label")));
        var localization = new io.github.juloass.localization.Localization(catalog,
                io.github.juloass.localization.LocaleId.of("fr_fr"), io.github.juloass.localization.LocaleId.of("en_us"), null);
        UiContext ui = new UiContext(font, localization);
        UiText sameNode = new UiText("localized", io.github.juloass.localization.TextComponent.translatable("screen.test.label"),
                ui.theme().text);
        float frenchWidth = renderWidth(ui, sameNode);
        assertTrue(ui.accessibilityNodes().stream().anyMatch(node -> node.label().equals("Court")),
                "accessibility resolves French component");
        localization.setLocale(io.github.juloass.localization.LocaleId.of("en_us"));
        float englishWidth = renderWidth(ui, sameNode);
        assertTrue(englishWidth > frenchWidth, "same text node is remeasured after locale switch");
        assertTrue(ui.accessibilityNodes().stream().anyMatch(node -> node.label().equals("A much wider label")),
                "accessibility switches with rendered text");
    }

    private static float renderWidth(UiContext ui, UiText text) {
        ui.begin(UiInput.mouseOnly(0, 0, false, 1f / 60f), 500, 100);
        ui.submit(text);
        UiRenderData data = ui.end();
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < data.vertices().length; i += UiRenderData.FLOATS_PER_VERTEX) {
            min = Math.min(min, data.vertices()[i]); max = Math.max(max, data.vertices()[i]);
        }
        return max - min;
    }

    private static void testDisplayMatrix(FontAtlas font) {
        UiViewport oneX = new UiViewport(1280, 720, 1, 1, 1, new UiInsets(12, 8, 12, 8));
        UiViewport twoX = new UiViewport(2560, 1440, 2, 2, 1, new UiInsets(12, 8, 12, 8));
        assertNear(oneX.logicalWidth(), twoX.logicalWidth(), .001f, "DPI preserves logical width");
        assertNear(oneX.logicalHeight(), twoX.logicalHeight(), .001f, "DPI preserves logical height");
        assertTrue(oneX.safeBounds().x() == 12 && oneX.safeBounds().w() == 1256, "safe area is logical");
        assertTrue(oneX.displayClass() == UiDisplayClass.WIDE, "720p canvas is wide");
        assertTrue(new UiViewport(640, 360, 1, 1, 1, UiInsets.all(0)).displayClass() == UiDisplayClass.COMPACT,
                "narrow canvas uses compact breakpoint");
        UiRect covered = oneX.fit(16, 9, new UiRect(0, 0, 100, 100), UiAspectPolicy.COVER);
        assertTrue(covered.w() > 100 && covered.h() == 100, "cover preserves aspect and crops width");

        UiContext ui = new UiContext(font);
        ui.begin(UiInput.mouseOnly(40, 40, false, 1f/60f), twoX);
        UiBox box = new UiBox("dpi-target", UiTheme.rgba(1, 1, 1, 1));
        box.interactionMotion(false);
        box.layout().position(10, 10).size(30, 20);
        box.onEvent(event -> {}).semantics(UiRole.BUTTON, "DPI target");
        ui.submit(box);
        UiRenderData data = ui.end();
        assertTrue(ui.capturesPointerAt(40, 40), "framebuffer pointer maps to logical hit target");
        float maxX = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < data.vertices().length; i += UiRenderData.FLOATS_PER_VERTEX) maxX = Math.max(maxX, data.vertices()[i]);
        assertNear(80, maxX, .001f, "logical geometry is transformed to 2x framebuffer pixels once");

        for (float userScale : new float[]{.75f, 1, 1.25f, 1.5f, 2}) {
            UiViewport viewport = new UiViewport(3840, 2160, 2, 2, userScale, UiInsets.all(0));
            assertNear(1920 / userScale, viewport.logicalWidth(), .001f, "4K logical width at scale " + userScale);
        }
    }

    private static void testNavigationAndAccessibility(FontAtlas font) {
        UiContext ui = new UiContext(font);
        final int[] clicks = {0};
        UiInput next = new UiInput(0, 0, false, false, false, 0, "",
                false, false, false, false, false, false, false, 1f/60f,
                true, false, false, false, 0, 0, false);
        ui.begin(next, 400, 200);
        ui.submit(navigationTree(ui, clicks));
        ui.end();
        assertTrue(ui.accessibilityNodes().stream().anyMatch(node -> node.id().equals("nav/a") && node.focused()),
                "tab navigation focuses first control");
        assertTrue(ui.accessibilityNodes().stream().anyMatch(node -> node.role() == UiRole.LABEL),
                "semantic snapshot includes noninteractive labels");

        UiInput activate = new UiInput(0, 0, false, false, false, 0, "",
                false, false, false, false, false, false, false, 1f/60f,
                false, false, true, false, 0, 0, true);
        ui.begin(activate, 400, 200);
        ui.submit(navigationTree(ui, clicks));
        ui.end();
        assertTrue(clicks[0] == 1, "controller activation dispatches one click");

        UiInput right = new UiInput(0, 0, false, false, false, 0, "",
                false, false, false, false, false, false, false, 1f/60f,
                false, false, false, false, 1, 0, true);
        ui.begin(right, 400, 200);
        ui.submit(navigationTree(ui, clicks));
        ui.end();
        assertTrue(ui.accessibilityNodes().stream().anyMatch(node -> node.id().equals("nav/b") && node.focused()),
                "spatial navigation moves right");

        ui.setAccessibility(new UiAccessibilityPreferences(1.4f, true, true));
        assertNear(1.4f, ui.accessibility().textScale(), .001f, "accessibility text scale retained");
        assertTrue(ui.accessibility().reducedUiAnimations() && ui.accessibility().highContrast(),
                "UI animation and contrast preferences retained");

        UiContext trapped = new UiContext(font);
        UiSurface outside = UiWidgets.button(trapped, "outside", "Outside", 80, 30, () -> {});
        UiGroup modal = new UiGroup("modal");
        modal.focusTrap(true);
        modal.layout().position(100, 20).size(100, 80).zIndex(10).flow(UiLayout.Flow.COLUMN);
        modal.child(UiWidgets.button(trapped, "modal/inside", "Inside", 80, 30, () -> {}));
        trapped.begin(next, 400, 200);
        trapped.submit(outside);
        trapped.submit(modal);
        trapped.end();
        assertTrue(trapped.accessibilityNodes().stream().anyMatch(node -> node.id().equals("modal/inside") && node.focused()),
                "modal focus trap excludes background controls");
    }

    private static UiElement navigationTree(UiContext ui, int[] clicks) {
        UiGroup root = new UiGroup("nav");
        root.layout().position(10, 10).size(220, 80).flow(UiLayout.Flow.ROW).gap(10);
        root.child(UiWidgets.button(ui, "nav/a", "Alpha", 100, 40, () -> clicks[0]++));
        root.child(UiWidgets.button(ui, "nav/b", "Beta", 100, 40, () -> clicks[0]++));
        return root;
    }

    private static void testTextLayout(FontAtlas font) {
        assertTrue(font.supports('é') && font.supports('Ω') && font.supports('Ж'),
                "Unicode atlas includes Latin, Greek and Cyrillic glyphs");
        assertTrue(font.layout("Été Ω Ж").length > 0, "Unicode text shapes");
        var wrapped = font.wrap("A deterministic sentence that must wrap safely.", 90, 2, true);
        assertTrue(wrapped.size() == 2, "text respects maximum line count");
        assertTrue(wrapped.getLast().endsWith("…") || wrapped.getLast().endsWith("..."),
                "overflow uses a codepoint-safe ellipsis");

        UiContext ui = new UiContext(font);
        ui.begin(UiInput.mouseOnly(0, 0, false, 1f/60f), 320, 180);
        UiText text = new UiText("wrapped", "Long localized interface text wraps over multiple lines", ui.theme().text)
                .wrap(true).maxLines(3).overflow(UiTextOverflow.ELLIPSIS);
        text.layout().position(10, 10).size(100, -1);
        ui.submit(text);
        UiRenderData data = ui.end();
        assertTrue(data.vertexCount() > 0, "wrapped text emits render geometry");
    }

    private static void testPointerConsumption(FontAtlas font) {
        UiContext ui=new UiContext(font);
        UiInput click=new UiInput(15,15,true,true,false,0,"",
                false,false,false,false,false,false,false,1f/60f);
        ui.begin(click,200,100);
        UiRect overlap=new UiRect(10,10,40,30);
        assertTrue(ui.clicked("first",overlap),"first overlapping imperative widget consumes click");
        assertTrue(!ui.clicked("second",overlap),"consumed click cannot reach another widget");
        ui.end();
        assertTrue(ui.capturesPointerAt(15,15),"previous UI hit region gates next scene update");

        final int[] presses={0};
        UiBox lower=new UiBox("lower",UiTheme.rgba(1,1,1,1));
        lower.layout().position(10,10).size(40,30).zIndex(0);
        lower.onEvent(event->{if(event.type()==UiEvent.Type.PRESS)presses[0]++;});
        UiBox upper=new UiBox("upper",UiTheme.rgba(1,1,1,1));
        upper.layout().position(10,10).size(40,30).zIndex(1);
        upper.onEvent(event->{if(event.type()==UiEvent.Type.PRESS)presses[0]++;});
        ui.begin(click,200,100);
        ui.submit(lower);
        ui.submit(upper);
        ui.end();
        assertTrue(presses[0]==1,"only topmost declarative element receives overlapping click");
        assertTrue(ui.capturesPointerAt(15,15),"declarative hit tree captures scene pointer");

        final int[] overlayPresses={0};
        UiGroup overlay=new UiGroup("stacking-overlay");
        overlay.layout().position(0,0).size(200,100).zIndex(500).flow(UiLayout.Flow.STACK);
        UiBox scrim=new UiBox("stacking-overlay/scrim",UiTheme.rgba(0,0,0,.6f));
        scrim.layout().size(200,100).zIndex(590).absolute(true);
        scrim.onEvent(event->{if(event.type()==UiEvent.Type.PRESS)overlayPresses[0]--;} );
        UiGroup modal=new UiGroup("stacking-overlay/modal");
        modal.layout().position(10,10).size(80,50).zIndex(600).absolute(true).flow(UiLayout.Flow.STACK);
        UiBox modalButton=new UiBox("stacking-overlay/modal/button",UiTheme.rgba(1,1,1,1));
        modalButton.layout().position(0,0).size(40,30).absolute(true);
        modalButton.onEvent(event->{if(event.type()==UiEvent.Type.PRESS)overlayPresses[0]++;});
        modal.child(modalButton); overlay.children(scrim,modal);
        ui.begin(click,200,100); ui.submit(overlay); ui.end();
        assertTrue(overlayPresses[0]==1,"nested modal controls inherit ancestor z-order above the scrim");
    }

    private static void testAnimationRuntime() {
        List<Easing> easings = List.of(
                Easings.LINEAR,
                Easings.QUADRATIC_IN,
                Easings.QUADRATIC_OUT,
                Easings.QUADRATIC_IN_OUT,
                Easings.CUBIC_IN,
                Easings.CUBIC_OUT,
                Easings.CUBIC_IN_OUT,
                Easings.SINE_IN,
                Easings.SINE_OUT,
                Easings.SINE_IN_OUT,
                Easings.CIRCULAR_IN,
                Easings.CIRCULAR_OUT,
                Easings.EXPONENTIAL_IN,
                Easings.EXPONENTIAL_OUT,
                Easings.BACK_IN,
                Easings.BACK_OUT,
                Easings.ELASTIC_OUT,
                Easings.BOUNCE_OUT);
        for (Easing easing : easings) {
            assertNear(0, (float) easing.apply(0), 0.001f, "easing starts at zero");
            assertNear(1, (float) easing.apply(1), 0.001f, "easing ends at one");
        }
        Easing bezier = Easings.cubicBezier(.25f, .1f, .25f, 1);
        assertNear(0, (float) bezier.apply(0), .001f, "bezier starts at zero");
        assertNear(1, (float) bezier.apply(1), .001f, "bezier ends at one");

        AnimationController<String> animator = new AnimationController<>();
        AnimationProperty<Double> xProperty = UiAnimationProperties.scalar("x");
        AnimationClip clip = AnimationClip.builder()
                .track(xProperty, 0.0, 10.0, Interpolators.DOUBLE, UiMotionCategory.TRANSLATION)
                .then(Duration.ofSeconds(1), Easings.LINEAR)
                .repeat(1, true)
                .build();
        AnimationHandle handle = animator.play("node", clip, InterruptionPolicy.REPLACE);
        animator.advance(Duration.ofMillis(500));
        assertNear(5, animator.valueOrElse("node", xProperty, -1.0).floatValue(), .001f, "timeline interpolates");
        handle.seek(Duration.ofMillis(1500));
        animator.advance(Duration.ZERO);
        assertNear(5, animator.valueOrElse("node", xProperty, -1.0).floatValue(), .001f, "timeline yoyo samples deterministically");
        handle.pause();
        animator.advance(Duration.ofMillis(200));
        assertNear(1.5f, (float) handle.elapsed().toNanos() / 1_000_000_000.0f, .001f, "paused timeline does not advance");
        handle.resume().reverse();
        animator.advance(Duration.ofMillis(250));
        assertNear(1.25f, (float) handle.elapsed().toNanos() / 1_000_000_000.0f, .001f, "timeline reverse");

        AnimationController<String> overshoot = new AnimationController<>();
        AnimationHandle overshootHandle = overshoot.play("drawer", AnimationClip.builder()
                .track(UiAnimationProperties.OPACITY, 0.0, 1.0, Interpolators.DOUBLE, UiMotionCategory.OPACITY)
                .track(UiAnimationProperties.TRANSFORM, UiTransform.translated(0, 60),
                        UiTransform.IDENTITY, Interpolators.TRANSFORM, UiMotionCategory.TRANSLATION)
                .then(Duration.ofMillis(320), Easings.BACK_OUT)
                .build(), InterruptionPolicy.REPLACE);
        overshoot.advance(Duration.ofMillis(500));
        assertTrue(overshootHandle.complete(), "overshot finite timeline completes");
        assertNear(1, overshoot.valueOrElse("drawer", UiAnimationProperties.OPACITY, -1.0).floatValue(), .001f,
                "overshot timeline retains final opacity");
        UiTransform finalTransform = overshoot.valueOrElse("drawer", UiAnimationProperties.TRANSFORM,
                UiTransform.translated(0, -1));
        assertNear(0, finalTransform.translateY(), .001f,
                "overshot timeline retains final transform");

        UiTransitionStore<String> springs = new UiTransitionStore<>();
        springs.beginFrame();
        float value = (float) springs.spring("node", UiAnimationProperties.scalar("scale"), 1, 240, 22, Duration.ZERO);
        assertTrue(Float.isFinite(value), "zero-dt spring is finite");
        value = (float) springs.spring("node", UiAnimationProperties.scalar("scale"), 2, 240, 22, Duration.ofMillis(250));
        assertTrue(Float.isFinite(value) && value > 1 && value < 2.5f, "large-dt spring remains bounded");
    }

    private static void testElementTree(FontAtlas font) {
        UiContext ui = new UiContext(font);
        final boolean[] pressed = {false};
        UiGroup root = new UiGroup("root");
        root.layout().position(10, 10).size(20, 20).clip(true).flow(UiLayout.Flow.STACK);
        root.transform(UiTransform.translated(20, 0)).opacity(.5f);
        UiBox child = new UiBox("root/child", UiTheme.rgba(1, 1, 1, 1));
        child.layout().size(40, 20);
        child.onEvent(event -> pressed[0] |= event.type() == UiEvent.Type.PRESS);
        root.child(child);

        ui.begin(new UiInput(35, 15, true, true, false, 0, "",
                false, false, false, false, false, false, false, 1f/60f), 200, 100);
        ui.submit(root);
        UiRenderData data = ui.end();
        assertTrue(pressed[0], "inverse-transformed element hit test");
        for (int i = 0; i < data.vertices().length; i += UiRenderData.FLOATS_PER_VERTEX) {
            assertTrue(data.vertices()[i] >= 30-.001f && data.vertices()[i] <= 50+.001f,
                    "parent clip constrains transformed child geometry");
            assertNear(.5f, data.vertices()[i+7], .001f, "parent opacity cascades");
        }

        UiContext sprites = new UiContext(font);
        sprites.begin(UiInput.mouseOnly(0, 0, false, 1f/60f), 100, 100);
        UiSprite sprite = new UiSprite("sprite", "textures/ui/test.png");
        sprite.layout().position(2, 3).size(16, 16);
        sprites.submit(sprite);
        UiRenderData spriteData = sprites.end();
        assertTrue(spriteData.batches().stream().anyMatch(
                batch -> batch.textureKind() == UiRenderData.TextureKind.RGBA
                        && "textures/ui/test.png".equals(batch.texture())), "sprite produces texture batch");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertNear(float expected, float actual, float epsilon, String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + ": expected " + expected + " got " + actual);
        }
    }
}
