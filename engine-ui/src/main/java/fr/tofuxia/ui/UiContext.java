package fr.tofuxia.ui;

import io.github.juloass.math.Easing;
import io.github.juloass.math.Easings;
import io.github.juloass.uianimation.AnimationClip;
import io.github.juloass.uianimation.AnimationController;
import io.github.juloass.uianimation.InterruptionPolicy;
import io.github.juloass.uianimation.UiMotionPolicy;
import io.github.juloass.uianimation.UiTransitionStore;
import io.github.juloass.uianimation.UiTransform;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiBuilder;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.render.UiSurfaceData;
import io.github.juloass.localization.Localization;
import io.github.juloass.localization.LocaleId;
import io.github.juloass.localization.TranslationCatalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.time.Duration;

public final class UiContext {
    private final FontAtlas font;
    private final Localization localization;
    private final UiTheme theme = new UiTheme();
    private final UiStateStore state = new UiStateStore();
    private final UiTransitionStore<String> animations = new UiTransitionStore<>();
    private final AnimationController<String> animator = new AnimationController<>();
    private final List<UiElement> roots = new ArrayList<>();
    private final Map<UiElement, UiRect> elementBounds = new IdentityHashMap<>();
    private final Map<String, ElementState> elementStates = new LinkedHashMap<>();
    private final Map<String, String> elementTooltips = new LinkedHashMap<>();
    private final Deque<String> scopes = new ArrayDeque<>();
    private final Deque<UiRect> clips = new ArrayDeque<>();
    private final Set<String> idsThisFrame = new HashSet<>();
    private UiBuilder draw;
    private UiInput input;
    private int width;
    private int height;
    private String hoveredId = "";
    private String activeId = "";
    private String focusedId = "";
    private String dragSourceId = "";
    private String dropTargetId = "";
    private String warning = "";
    private String tooltipText = "";
    private String tooltipTitle = "";
    private String tooltipSubtitle = "";
    private String[] tooltipRows = new String[0];
    private String[] tooltipWarnings = new String[0];
    private float tooltipX;
    private float tooltipY;
    private int layoutRects;
    private boolean captured;
    private boolean pointerPressConsumed;
    private final List<UiRect> pointerRegions = new ArrayList<>();
    private List<UiRect> previousPointerRegions = List.of();
    private float uiScale = 1.0f;
    private UiViewport viewport = UiViewport.framebuffer(1, 1, 1);
    private UiAccessibilityPreferences accessibility = UiAccessibilityPreferences.DEFAULT;
    private List<UiAccessibilityNode> accessibilityNodes = List.of();

    public UiContext(FontAtlas font) {
        this(font, literalOnlyLocalization());
    }

    public UiContext(FontAtlas font, Localization localization) {
        this.font = font;
        this.localization = java.util.Objects.requireNonNull(localization, "localization");
    }

    public Localization localization() { return localization; }

    private static Localization literalOnlyLocalization() {
        LocaleId locale = LocaleId.of("zxx");
        TranslationCatalog catalog = TranslationCatalog.fromBundles(locale, Map.of(locale, Map.of()));
        return new Localization(catalog, locale, List.of(), null);
    }

    public void begin(UiInput input, int width, int height) {
        begin(input, UiViewport.framebuffer(width, height, uiScale));
    }

    public void begin(UiInput input, UiViewport viewport) {
        this.viewport = java.util.Objects.requireNonNull(viewport, "viewport");
        this.uiScale = viewport.userScale();
        this.input = input.withPointer(viewport.toLogicalX(input.mouseX()), viewport.toLogicalY(input.mouseY()));
        this.width = Math.max(1, (int) Math.floor(viewport.logicalWidth()));
        this.height = Math.max(1, (int) Math.floor(viewport.logicalHeight()));
        this.draw = new UiBuilder(font, accessibility.textScale(), viewport.renderScaleX(), viewport.renderScaleY());
        hoveredId = "";
        warning = "";
        tooltipText = "";
        tooltipTitle = "";
        tooltipSubtitle = "";
        tooltipRows = new String[0];
        tooltipWarnings = new String[0];
        elementTooltips.clear();
        layoutRects = 0;
        captured = false;
        pointerPressConsumed = false;
        pointerRegions.clear();
        scopes.clear();
        clips.clear();
        idsThisFrame.clear();
        roots.clear();
        elementBounds.clear();
        clips.push(new UiRect(0, 0, width, height));
        animations.beginFrame();
        elementFrame++;
        animator.motionPolicy(accessibility.reducedUiAnimations() ? UiMotionPolicy.NONE : UiMotionPolicy.FULL);
        animator.advance(seconds(input.dt()));
        if (!input.mouseDown() && !input.mouseReleased()) {
            activeId = "";
        }
        if (input.unfocus()) {
            focusedId = "";
        }
    }

    public UiRenderData end() {
        renderElements();
        drawPendingTooltip();
        previousPointerRegions = List.copyOf(pointerRegions);
        animations.collectStale(360);
        elementStates.values().removeIf(state -> animationsFrame() - state.lastFrame > 360);
        return draw.toRenderData();
    }

    /** Adds a declarative element tree to this frame. Roots are painted in submission order. */
    public void submit(UiElement root) {
        roots.add(java.util.Objects.requireNonNull(root, "root"));
    }

    /** Imperative timeline runtime used by event-driven element animations. */
    public AnimationController<String> animator() {
        return animator;
    }

    public FontAtlas font() {
        return font;
    }

    public UiTheme theme() {
        return theme;
    }

    public float uiScale() {
        return uiScale;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public void setUiScale(float uiScale) {
        this.uiScale = Math.max(0.60f, Math.min(2.00f, uiScale));
    }

    public float sx(float value) {
        return value;
    }

    public float pad() {
        return sx(theme.pad);
    }

    public float gap() {
        return sx(theme.gap);
    }

    public float row() {
        return sx(theme.row);
    }

    public float textWidth(String text) {
        return font.textWidth(text) * accessibility.textScale();
    }

    public UiViewport viewport() { return viewport; }
    public UiRect safeBounds() { return viewport.safeBounds(); }
    public UiDisplayClass displayClass() { return viewport.displayClass(); }

    public float responsive(float compact, float standard, float wide) {
        return switch (displayClass()) {
            case COMPACT -> compact;
            case STANDARD -> standard;
            case WIDE, ULTRAWIDE -> wide;
        };
    }

    public void setAccessibility(UiAccessibilityPreferences preferences) {
        accessibility = preferences == null ? UiAccessibilityPreferences.DEFAULT : preferences;
    }

    public UiAccessibilityPreferences accessibility() { return accessibility; }
    public List<UiAccessibilityNode> accessibilityNodes() { return accessibilityNodes; }

    public UiStateStore state() {
        return state;
    }

    public UiInput input() {
        return input;
    }

    public boolean captured() {
        return captured || !activeId.isEmpty();
    }

    public void capture() {
        captured = true;
    }

    /** Previous reconciled hit tree, available before this frame's scene update. */
    public boolean capturesPointerAt(float x,float y) {
        float logicalX = viewport.toLogicalX(x);
        float logicalY = viewport.toLogicalY(y);
        if(!activeId.isEmpty())return true;
        for(UiRect rect:previousPointerRegions)if(rect.contains(logicalX,logicalY))return true;
        List<Hit> hits=new ArrayList<>();
        int[] order={0};
        for(UiElement root:roots)collectHits(root,Affine.IDENTITY,new UiRect(0,0,width,height),hits,order);
        return hits.stream().anyMatch(hit->hit.contains(logicalX,logicalY));
    }

    public void pushScope(String scope) {
        scopes.push(scope);
    }

    public void popScope() {
        scopes.pop();
    }

    public String id(String local) {
        StringBuilder out = new StringBuilder();
        Object[] path = scopes.toArray();
        for (int i = path.length - 1; i >= 0; i--) {
            out.append(path[i]).append("/");
        }
        out.append(local);
        String id = out.toString();
        if (!idsThisFrame.add(id)) {
            warning = "ID collision: " + id;
        }
        return id;
    }

    public boolean hit(String id, UiRect rect) {
        layoutRects++;
        pointerRegions.add(rect);
        boolean inside = clips.peek().contains(input.mouseX(), input.mouseY()) && rect.contains(input.mouseX(), input.mouseY());
        if (inside) {
            hoveredId = id;
            captured = true;
        }
        return inside;
    }

    public boolean clicked(String id, UiRect rect) {
        boolean hover = hit(id, rect);
        if (hover && input.mouseClicked() && !pointerPressConsumed) {
            pointerPressConsumed = true;
            activeId = id;
            focusedId = id;
            return true;
        }
        return false;
    }

    public boolean pressed(String id) {
        return activeId.equals(id) && input.mouseDown();
    }

    public boolean focused(String id) {
        return focusedId.equals(id);
    }

    /** The id of the keyboard-focused widget, or an empty string. */
    public String focusedId() {
        return focusedId;
    }

    public boolean hovered(String id) {
        return hoveredId.equals(id);
    }

    public void focus(String id) {
        focusedId = id;
    }

    public void clearFocus(String id) {
        if (focusedId.equals(id)) focusedId = "";
    }

    public float animFloat(String id, String property, float target, float speed) {
        return (float) animations.approach(id, UiAnimationProperties.scalar(property), target, speed, seconds(input.dt()));
    }

    public float transition(String id, String property, float target, float duration, Easing easing) {
        return (float) animations.transition(id, UiAnimationProperties.scalar(property), target,
                seconds(duration), easing, seconds(input.dt()));
    }

    public float spring(String id, String property, float target, float stiffness, float damping) {
        return (float) animations.spring(id, UiAnimationProperties.scalar(property), target,
                stiffness, damping, seconds(input.dt()));
    }

    public float smoothValue(String id, String property, float target, float speed) {
        return animFloat(id, property, target, speed);
    }

    public void pushClip(UiRect rect) {
        clips.push(clips.peek().intersect(rect));
    }

    public void popClip() {
        clips.pop();
    }

    public boolean visible(UiRect rect) {
        UiRect clip = clips.peek();
        return rect.x1() >= clip.x() && rect.x() <= clip.x1() && rect.y1() >= clip.y() && rect.y() <= clip.y1();
    }

    public void panel(UiRect rect) {
        draw.shadow(rect.x(), rect.y(), rect.w(), rect.h(), 5, theme.shadow);
        draw.quad(rect.x(), rect.y(), rect.x1(), rect.y1(), theme.canvas);
        draw.border(rect.x(), rect.y(), rect.w(), rect.h(), theme.borderDim);
    }

    /** Draws a panel with an accent title bar and returns the content area. */
    public UiRect titledPanel(UiRect rect, String title) {
        panel(rect);
        float titleH = sx(24.0f);
        draw.quad(rect.x(), rect.y(), rect.x1(), Math.min(rect.y1(), rect.y() + titleH), theme.accent);
        draw.border(rect.x(), rect.y(), rect.w(), rect.h(), theme.border);
        drawBoldText(rect.x() + sx(8.0f), rect.y() + sx(5.0f), title, theme.titleText);
        return new UiRect(rect.x() + pad(), rect.y() + titleH + gap(),
                Math.max(0.0f, rect.w() - pad() * 2.0f),
                Math.max(0.0f, rect.h() - titleH - gap() - pad()));
    }

    /** Measures a compact panel around title + rows using the current UI scale. */
    public UiRect measurePanel(float x, float y, String title, String[] rows, float minWidth) {
        float width = Math.max(sx(minWidth), textWidth(title) + sx(28.0f));
        for (String row : rows) width = Math.max(width, textWidth(row) + sx(24.0f));
        float height = sx(24.0f) + gap() + pad() + Math.max(0, rows.length) * sx(17.0f);
        return new UiRect(x, y, width, height);
    }

    /** Draws a self-sized information panel and returns its outer rectangle. */
    public UiRect dynamicTextPanel(float x, float y, String title, String[] rows, float minWidth) {
        UiRect rect = measurePanel(x, y, title, rows, minWidth);
        String panelId = "panel:" + title + ":" + Math.round(x) + ":" + Math.round(y);
        UiBox panel = new UiBox(panelId, theme.canvas).border(theme.border, 1);
        panel.layout().position(x, y).size(rect.w(), rect.h()).flow(UiLayout.Flow.STACK);
        float titleH = sx(24);
        UiBox titleBar = new UiBox(panelId + "/title-bar", theme.accent);
        titleBar.layout().position(0, 0).size(rect.w(), titleH).absolute(true);
        UiText titleText = new UiText(panelId + "/title", title, theme.titleText);
        titleText.layout().position(sx(8), sx(5)).absolute(true);
        panel.children(titleBar, titleText);
        float ty = titleH + gap();
        for (int i = 0; i < rows.length; i++) {
            UiText row = new UiText(panelId + "/row-" + i, clip(rows[i], rect.w()-pad()*2), theme.text);
            row.layout().position(pad(), ty).absolute(true);
            panel.child(row);
            ty += sx(17);
        }
        submit(panel);
        return rect;
    }

    /** Draws a compact stats/readout panel using the standard titled-panel style. */
    public UiRect statsPanel(float x, float y, String title, String[] rows) {
        return dynamicTextPanel(x, y, title, rows, 320.0f);
    }

    /** Draws a hierarchical frame profiler with percentage bars relative to total frame time. */
    public UiRect profilerPanel(float x, float y, ProfilerSnapshot snapshot, float minWidth) {
        ProfilerSnapshot safe = snapshot == null ? ProfilerSnapshot.EMPTY : snapshot;
        int rows = profilerVisibleRows(safe.entries());
        float width = Math.max(sx(minWidth), sx(360.0f));
        float height = sx(24.0f) + gap() + pad() + sx(20.0f) + rows * sx(18.0f);
        UiRect rect = new UiRect(x, y, width, height);
        UiRect content = titledPanel(rect, "Frame profiler");
        draw.text(content.x(), content.y(), "Total frame %.2f ms".formatted(safe.frameMs()), theme.text);
        float rowY = content.y() + sx(20.0f);
        List<ProfilerSnapshot.Entry> sorted = sortedEntries(safe.entries());
        for (ProfilerSnapshot.Entry entry : sorted) {
            rowY = profilerRow(content.x(), rowY, content.w(), safe.frameMs(), entry, 0, theme.text);
            for (ProfilerSnapshot.Entry child : sortedEntries(entry.children())) {
                if (entry.millis() > 0.0f && child.millis() / entry.millis() >= 0.30f) {
                    rowY = profilerRow(content.x(), rowY, content.w(), safe.frameMs(), child, 1, theme.textDim);
                }
            }
        }
        return rect;
    }

    /** Draws heap/non-heap memory and GC deltas as a standalone diagnostics panel. */
    public UiRect memoryPanel(float x, float y, ProfilerSnapshot.Memory memory, float minWidth) {
        float width = Math.max(sx(minWidth), sx(360.0f));
        float height = sx(24.0f) + gap() + pad() + sx(86.0f);
        UiRect rect = new UiRect(x, y, width, height);
        UiRect content = titledPanel(rect, "RAM / GC");
        drawMemoryProfiler(content.x(), content.y(), content.w(), memory == null ? ProfilerSnapshot.Memory.EMPTY : memory);
        return rect;
    }

    /**
     * Draws a fixed-size time-series bar graph.
     * Samples are expected in circular-buffer order controlled by {@code cursor};
     * newer samples appear on the right. Values are milliseconds.
     */
    public UiRect timeSeriesPanel(UiRect rect, String title, float[] samples, int count, int cursor,
                                  float targetMs, float spikeMs, String unitLabel) {
        UiRect content = titledPanel(rect, title);
        float labelH = sx(17.0f);
        float graphX = content.x();
        float graphY = content.y() + labelH;
        float graphW = Math.max(1.0f, content.w());
        float graphH = Math.max(1.0f, content.h() - labelH);
        int sampleCount = Math.max(0, Math.min(count, samples.length));

        draw.text(content.x(), content.y(), graphLabel(samples, sampleCount, cursor, targetMs, unitLabel), theme.textDim);
        draw.quad(graphX, graphY, graphX + graphW, graphY + graphH, UiTheme.alpha(theme.panelPressed, 0.72f));
        draw.border(graphX, graphY, graphW, graphH, theme.borderDim);

        float targetY = graphY + graphH - graphH * Math.min(1.0f, targetMs / spikeMs);
        draw.quad(graphX, targetY, graphX + graphW, targetY + Math.max(1.0f, sx(1.0f)), UiTheme.alpha(theme.accentBlue, 0.55f));

        if (sampleCount == 0) return rect;
        float barGap = Math.max(0.0f, sx(1.0f));
        float barW = Math.max(1.0f, graphW / sampleCount - barGap);
        int oldest = Math.floorMod(cursor - sampleCount, samples.length);
        for (int i = 0; i < sampleCount; i++) {
            float value = samples[(oldest + i) % samples.length];
            float normalized = Math.max(0.0f, Math.min(1.0f, value / spikeMs));
            float x0 = graphX + i * graphW / sampleCount;
            float x1 = Math.min(graphX + graphW, x0 + barW);
            float y1 = graphY + graphH;
            float y0 = y1 - Math.max(1.0f, graphH * normalized);
            draw.quad(x0, y0, x1, y1, frameTimeColor(value, targetMs, spikeMs));
        }
        return rect;
    }

    public void tooltip(String text, float x, float y) {
        tooltipText = text;
        tooltipX = x;
        tooltipY = y;
    }

    /** Registers a tooltip resolved after this frame's declarative hit testing. */
    public void tooltipFor(String targetId, String text) {
        if (targetId == null || targetId.isBlank() || text == null || text.isBlank()) return;
        elementTooltips.put(targetId, text);
    }

    public void tooltip(String title, String subtitle, String[] rows, String[] warnings, float x, float y) {
        tooltipTitle = title == null ? "" : title;
        tooltipSubtitle = subtitle == null ? "" : subtitle;
        tooltipRows = rows == null ? new String[0] : rows;
        tooltipWarnings = warnings == null ? new String[0] : warnings;
        tooltipX = x;
        tooltipY = y;
    }

    private void drawPendingTooltip() {
        if (tooltipTitle.isEmpty() && tooltipText.isEmpty()) {
            String hoveredTooltip = elementTooltips.get(hoveredId);
            if (hoveredTooltip != null) {
                tooltipText = hoveredTooltip;
                tooltipX = input.mouseX() + sx(12.0f);
                tooltipY = Math.max(4.0f, input.mouseY() - font.lineHeight() - sx(18.0f));
            }
        }
        if (!tooltipTitle.isEmpty()) {
            drawRichTooltip();
            return;
        }
        if (tooltipText.isEmpty()) return;
        String id = "tooltip:" + tooltipText;
        float appear = transition(id, "appear", 1.0f, 0.10f, Easings.QUADRATIC_OUT);
        float tw = textWidth(tooltipText) + sx(14.0f);
        float th = font.lineHeight() + 8;
        float tx = Math.min(tooltipX, width - tw - 4);
        float ty = Math.min(tooltipY, height - th - 4);
        draw.shadow(tx, ty, tw, th, 4, UiTheme.alpha(theme.shadow, 0.45f * appear));
        draw.quad(tx, ty, tx + tw, ty + th, UiTheme.alpha(theme.canvas, 0.98f * appear));
        draw.border(tx, ty, tw, th, theme.border);
        draw.text(tx + 7, ty + 4, tooltipText, UiTheme.alpha(theme.text, appear));
    }

    private void drawRichTooltip() {
        String id = "tooltip:" + tooltipTitle + tooltipSubtitle;
        float appear = transition(id, "appear", 1.0f, 0.10f, Easings.QUADRATIC_OUT);
        float tw = Math.max(textWidth(tooltipTitle), textWidth(tooltipSubtitle));
        for (String row : tooltipRows) tw = Math.max(tw, textWidth(row));
        for (String row : tooltipWarnings) tw = Math.max(tw, textWidth(row));
        tw = Math.max(220.0f, Math.min(360.0f, tw + 24.0f));
        float th = 34.0f + tooltipRows.length * 17.0f + tooltipWarnings.length * 17.0f + (tooltipSubtitle.isEmpty() ? 0.0f : 17.0f);
        float tx = Math.min(tooltipX, width - tw - 4);
        float ty = Math.min(tooltipY, height - th - 4);
        draw.shadow(tx, ty, tw, th, 5, UiTheme.alpha(theme.shadow, 0.50f * appear));
        draw.quad(tx, ty, tx + tw, ty + th, UiTheme.alpha(theme.canvas, 0.98f * appear));
        draw.quad(tx, ty, tx + tw, ty + 24, UiTheme.alpha(theme.panelRaised, 0.98f * appear));
        draw.border(tx, ty, tw, th, theme.accent);
        draw.text(tx + 10, ty + 5, clip(tooltipTitle, tw - 20), UiTheme.alpha(theme.text, appear));
        float y = ty + 27;
        if (!tooltipSubtitle.isEmpty()) {
            draw.text(tx + 10, y, clip(tooltipSubtitle, tw - 20), UiTheme.alpha(theme.textDim, appear));
            y += 17;
        }
        for (String row : tooltipRows) {
            draw.text(tx + 10, y, clip(row, tw - 20), UiTheme.alpha(theme.text, appear));
            y += 17;
        }
        for (String row : tooltipWarnings) {
            draw.text(tx + 10, y, clip(row, tw - 20), UiTheme.alpha(theme.warning, appear));
            y += 17;
        }
    }

    public String clip(String text, float maxWidth) {
        if (textWidth(text) <= maxWidth) return text;
        StringBuilder out = new StringBuilder();
        float width = textWidth("..");
        for (int i = 0; i < text.length(); i++) {
            width += font.glyph(text.charAt(i)).advance() * uiScale;
            if (width > maxWidth) break;
            out.append(text.charAt(i));
        }
        return out + "..";
    }

    public UiDebugSnapshot debugSnapshot() {
        return new UiDebugSnapshot(hoveredId, activeId, focusedId, dragSourceId, dropTargetId,
                layoutRects, clips.size(), animations.channelCount(), animator.activeCount(), elementStates.size(),
                draw.vertexCount(), draw.indexCount(), warning);
    }

    public void drawDebugOverlay(UiRect rect) {
        UiDebugSnapshot d = debugSnapshot();
        draw.quad(rect.x(), rect.y(), rect.x1(), rect.y1(), UiTheme.alpha(theme.canvas, 0.88f));
        draw.border(rect.x(), rect.y(), rect.w(), rect.h(), theme.warning);
        float y = rect.y() + 6;
        String[] lines = {
                "UI DEBUG",
                "hover: " + empty(d.hoveredId()),
                "active: " + empty(d.activeId()),
                "focus: " + empty(d.focusedId()),
                "drag: " + empty(d.dragSourceId()) + " -> " + empty(d.dropTargetId()),
                "layout " + d.layoutRects() + " clip " + d.clipRects() + " anim "
                        + d.animationChannels() + "/" + d.activeTimelines() + " nodes " + d.retainedElements(),
                "draw v" + d.drawVertices() + " i" + d.drawIndices(),
                d.warning().isEmpty() ? "" : d.warning()
        };
        for (String line : lines) {
            if (!line.isEmpty()) draw.text(rect.x() + 7, y, clip(line, rect.w() - 14), line.startsWith("ID ") ? theme.danger : theme.textDim);
            y += 17;
        }
    }

    private String empty(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private void drawBoldText(float x, float y, String text, float[] color) {
        draw.text(x, y, text, color);
        draw.text(x + 1.0f, y, text, color);
    }

    private String graphLabel(float[] samples, int count, int cursor, float targetMs, String unitLabel) {
        if (count <= 0) return "frame -- " + unitLabel + " target %.1f".formatted(targetMs);
        float latest = samples[Math.floorMod(cursor - 1, samples.length)];
        float max = 0.0f;
        int oldest = Math.floorMod(cursor - count, samples.length);
        for (int i = 0; i < count; i++) max = Math.max(max, samples[(oldest + i) % samples.length]);
        return "%.2f %s  max %.2f  target %.1f".formatted(latest, unitLabel, max, targetMs);
    }

    private float[] frameTimeColor(float value, float targetMs, float spikeMs) {
        float warningStart = Math.max(0.001f, targetMs);
        float t = (value - warningStart) / Math.max(0.001f, spikeMs - warningStart);
        t = Math.max(0.0f, Math.min(1.0f, t));
        float[] green = UiTheme.rgba(0.130f, 0.880f, 0.380f, 0.92f);
        float[] yellow = UiTheme.rgba(0.950f, 0.780f, 0.180f, 0.94f);
        return t < 0.5f
                ? UiTheme.mix(green, yellow, t * 2.0f)
                : UiTheme.mix(yellow, theme.danger, (t - 0.5f) * 2.0f);
    }

    private int profilerVisibleRows(List<ProfilerSnapshot.Entry> entries) {
        int rows = 0;
        for (ProfilerSnapshot.Entry entry : entries) {
            rows++;
            for (ProfilerSnapshot.Entry child : entry.children()) {
                if (entry.millis() > 0.0f && child.millis() / entry.millis() >= 0.30f) rows++;
            }
        }
        return rows;
    }

    private float profilerRow(float x, float y, float width, float frameMs, ProfilerSnapshot.Entry entry, int depth, float[] textColor) {
        float rowH = sx(16.0f);
        float labelW = Math.min(sx(165.0f), width * 0.44f);
        float barX = x + labelW;
        float valueW = sx(92.0f);
        float barW = Math.max(sx(40.0f), width - labelW - valueW - sx(8.0f));
        float percent = frameMs <= 0.0f ? 0.0f : Math.max(0.0f, Math.min(1.0f, entry.millis() / frameMs));
        float indent = sx(12.0f * depth);
        draw.text(x + indent, y + sx(1.0f), clip((depth > 0 ? "- " : "") + entry.name(), labelW - indent - sx(4.0f)), textColor);
        draw.quad(barX, y + sx(3.0f), barX + barW, y + rowH - sx(3.0f), UiTheme.alpha(theme.panelPressed, 0.82f));
        draw.quad(barX, y + sx(3.0f), barX + Math.max(sx(1.0f), barW * percent), y + rowH - sx(3.0f), profilerColor(percent));
        draw.border(barX, y + sx(3.0f), barW, rowH - sx(6.0f), theme.borderDim);
        draw.text(barX + barW + sx(6.0f), y + sx(1.0f), "%.2f ms / %.0f%%".formatted(entry.millis(), percent * 100.0f), textColor);
        return y + sx(18.0f);
    }

    private List<ProfilerSnapshot.Entry> sortedEntries(List<ProfilerSnapshot.Entry> entries) {
        List<ProfilerSnapshot.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(ProfilerSnapshot.Entry::millis).reversed());
        return sorted;
    }

    private float[] profilerColor(float percent) {
        float[] green = UiTheme.rgba(0.130f, 0.880f, 0.380f, 0.94f);
        float[] yellow = UiTheme.rgba(0.950f, 0.780f, 0.180f, 0.96f);
        float t = Math.max(0.0f, Math.min(1.0f, percent / 0.50f));
        return t < 0.5f
                ? UiTheme.mix(green, yellow, t * 2.0f)
                : UiTheme.mix(yellow, theme.danger, (t - 0.5f) * 2.0f);
    }

    private void drawMemoryProfiler(float x, float y, float width, ProfilerSnapshot.Memory memory) {
        float heapMax = memory.heapMaxBytes() <= 0 ? Math.max(1.0f, memory.heapCommittedBytes()) : memory.heapMaxBytes();
        float heapPct = memory.heapUsedBytes() / heapMax;
        float barY = y;
        float labelW = Math.min(sx(120.0f), width * 0.34f);
        float barX = x + labelW;
        float valueW = sx(132.0f);
        float barW = Math.max(sx(40.0f), width - labelW - valueW - sx(8.0f));
        draw.text(x, barY + sx(1.0f), "heap", theme.text);
        draw.quad(barX, barY + sx(3.0f), barX + barW, barY + sx(13.0f), UiTheme.alpha(theme.panelPressed, 0.82f));
        draw.quad(barX, barY + sx(3.0f), barX + Math.max(sx(1.0f), barW * Math.min(1.0f, heapPct)), barY + sx(13.0f), profilerColor(heapPct));
        draw.border(barX, barY + sx(3.0f), barW, sx(10.0f), theme.borderDim);
        draw.text(barX + barW + sx(6.0f), barY + sx(1.0f), "%s / %s / %s".formatted(
                mib(memory.heapUsedBytes()), mib(memory.heapCommittedBytes()), memory.heapMaxBytes() <= 0 ? "unlimited" : mib(memory.heapMaxBytes())), theme.textDim);
        draw.text(x, barY + sx(19.0f), "non-heap " + mib(memory.nonHeapUsedBytes()), theme.textDim);
        draw.text(x, barY + sx(37.0f), "alloc delta " + signedMib(memory.usedDeltaBytes()), memory.usedDeltaBytes() > 0 ? theme.warning : theme.success);
        draw.text(x, barY + sx(55.0f), "GC +" + memory.gcCountDelta() + " collections / +" + memory.gcTimeDeltaMs() + " ms", memory.gcCountDelta() > 0 ? theme.warning : theme.textDim);
    }

    private String mib(long bytes) {
        return "%.1f MiB".formatted(bytes / 1048576.0);
    }

    private String signedMib(long bytes) {
        return "%s%.2f MiB".formatted(bytes >= 0 ? "+" : "-", Math.abs(bytes) / 1048576.0);
    }

    private void renderElements() {
        if (roots.isEmpty()) {
            accessibilityNodes = List.of();
            return;
        }
        Set<String> elementIds = new HashSet<>();
        for (UiElement root : roots) {
            validateElementIds(root, elementIds);
            measureAndLayout(root, new UiRect(0, 0, width, height), true);
        }
        startExitAnimations(elementIds);
        List<Hit> focusables = focusableHits();
        updateNavigation(focusables);
        Hit hit = findTopHit();
        updateElementInteraction(hit);
        accessibilityNodes = buildAccessibilityNodes();
        for (UiElement root : roots) paintElement(root);
        for (ElementState state : new ArrayList<>(elementStates.values())) {
            if (state.exiting && state.bounds != null) paintRetained(state);
        }
    }

    private List<Hit> focusableHits() {
        List<Hit> hits = new ArrayList<>();
        int[] order = {0};
        UiElement trap = activeRootFocusTrap();
        if (trap != null) collectHits(trap, Affine.IDENTITY, new UiRect(0, 0, width, height), hits, order);
        else for (UiElement root : roots) collectHits(root, Affine.IDENTITY,
                    new UiRect(0, 0, width, height), hits, order);
        return hits.stream().filter(hit -> hit.element.focusable())
                .sorted(Comparator.comparingInt((Hit hit) -> hit.element.tabIndex()).thenComparingInt(Hit::order))
                .toList();
    }

    private UiElement activeRootFocusTrap() {
        UiElement selected = null;
        int selectedZ = Integer.MIN_VALUE;
        for (UiElement root : roots) {
            if (root.visible() && root.enabled() && root.focusTrap() && root.layout().zIndex >= selectedZ) {
                selected = root;
                selectedZ = root.layout().zIndex;
            }
        }
        return selected;
    }

    private void updateNavigation(List<Hit> focusables) {
        if (input.navCancel() || input.unfocus()) setFocused("");
        if (focusables.isEmpty()) return;
        if (input.navNext() || input.navPrevious()) {
            int current = indexOfFocus(focusables);
            int step = input.navPrevious() ? -1 : 1;
            int next = current < 0 ? (step > 0 ? 0 : focusables.size() - 1)
                    : Math.floorMod(current + step, focusables.size());
            setFocused(focusables.get(next).element.id());
        } else if (input.navX() != 0 || input.navY() != 0) {
            Hit next = spatialFocus(focusables, input.navX(), input.navY());
            if (next != null) setFocused(next.element.id());
        }
        if (input.navActivate() && !focusedId.isEmpty()) {
            ElementState focused = elementStates.get(focusedId);
            if (focused != null && focused.element.enabled()) {
                Hit hit = hitFor(focused.element);
                dispatch(focused.element, UiEvent.Type.PRESS, hit);
                dispatch(focused.element, UiEvent.Type.RELEASE, hit);
                dispatch(focused.element, UiEvent.Type.CLICK, hit);
            }
        }
    }

    private int indexOfFocus(List<Hit> focusables) {
        for (int i = 0; i < focusables.size(); i++) if (focusables.get(i).element.id().equals(focusedId)) return i;
        return -1;
    }

    private Hit spatialFocus(List<Hit> focusables, int dx, int dy) {
        if (focusedId.isEmpty()) return focusables.getFirst();
        Hit current = focusables.stream().filter(hit -> hit.element.id().equals(focusedId)).findFirst().orElse(null);
        if (current == null) return focusables.getFirst();
        float cx = current.bounds.x() + current.bounds.w() * .5f;
        float cy = current.bounds.y() + current.bounds.h() * .5f;
        Hit best = null;
        float bestScore = Float.POSITIVE_INFINITY;
        for (Hit candidate : focusables) {
            if (candidate == current) continue;
            float ox = candidate.bounds.x() + candidate.bounds.w() * .5f - cx;
            float oy = candidate.bounds.y() + candidate.bounds.h() * .5f - cy;
            float forward = ox * dx + oy * dy;
            if (forward <= 0) continue;
            float sideways = Math.abs(ox * dy - oy * dx);
            float score = forward + sideways * 2.5f;
            if (score < bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private void setFocused(String next) {
        if (java.util.Objects.equals(focusedId, next)) return;
        ElementState previous = elementStates.get(focusedId);
        if (previous != null) dispatch(previous.element, UiEvent.Type.BLUR, hitFor(previous.element));
        focusedId = next == null ? "" : next;
        ElementState focused = elementStates.get(focusedId);
        if (focused != null) dispatch(focused.element, UiEvent.Type.FOCUS, hitFor(focused.element));
    }

    private List<UiAccessibilityNode> buildAccessibilityNodes() {
        List<UiAccessibilityNode> nodes = new ArrayList<>();
        for (UiElement root : roots) collectAccessibilityNodes(root, nodes);
        return List.copyOf(nodes);
    }

    private void collectAccessibilityNodes(UiElement element, List<UiAccessibilityNode> nodes) {
        if (!element.visible()) return;
        UiRect bounds = elementBounds.get(element);
        String label = localization.resolvePlainText(element.accessibleLabelComponent());
        String value = localization.resolvePlainText(element.accessibleValueComponent());
        if (bounds != null && (element.role() != UiRole.CUSTOM || !label.isBlank())) {
            nodes.add(new UiAccessibilityNode(element.id(), element.role(),
                    label.isBlank() ? element.id() : label,
                    value, bounds, element.enabled(), element.id().equals(focusedId),
                    element.selected()));
        }
        for (UiElement child : element.children()) collectAccessibilityNodes(child, nodes);
    }

    private void validateElementIds(UiElement element, Set<String> ids) {
        if (!ids.add(element.id())) warning = "ID collision: " + element.id();
        for (UiElement child : element.children()) validateElementIds(child, ids);
    }

    private UiRect measureAndLayout(UiElement element, UiRect parent, boolean root) {
        UiLayout l = element.layout();
        float[] measured = measure(element);
        float w = clamp(l.width >= 0 ? l.width : measured[0], l.minWidth, l.maxWidth);
        float h = clamp(l.height >= 0 ? l.height : measured[1], l.minHeight, l.maxHeight);
        if (root && l.width < 0) w = Math.min(parent.w(), w);
        if (root && l.height < 0) h = Math.min(parent.h(), h);
        UiRect bounds = new UiRect(parent.x() + l.x, parent.y() + l.y, Math.max(0, w), Math.max(0, h));
        elementBounds.put(element, bounds);
        layoutChildren(element, bounds);
        return bounds;
    }

    private void layoutChildren(UiElement parent, UiRect bounds) {
        List<UiElement> children = new ArrayList<>(parent.children());
        children.sort(Comparator.comparingInt(child -> child.layout().zIndex));
        UiLayout layout = parent.layout();
        UiRect content = new UiRect(bounds.x() + layout.padding, bounds.y() + layout.padding,
                Math.max(0, bounds.w() - layout.padding * 2), Math.max(0, bounds.h() - layout.padding * 2));
        float cursor = layout.flow == UiLayout.Flow.ROW ? content.x() : content.y();
        float availableMain = layout.flow == UiLayout.Flow.ROW ? content.w() : content.h();
        float fixed = Math.max(0, children.size() - 1) * layout.gap;
        float totalFlex = 0;
        for (UiElement child : children) {
            float[] size = measure(child);
            if (child.layout().absolute) continue;
            if (child.layout().flex > 0) totalFlex += child.layout().flex;
            else fixed += layout.flow == UiLayout.Flow.ROW ? size[0] : layout.flow == UiLayout.Flow.COLUMN ? size[1] : 0;
        }
        float flexSpace = Math.max(0, availableMain - fixed);
        if (totalFlex == 0) {
            float spare = Math.max(0, availableMain - fixed);
            if (layout.mainAlign == UiLayout.Align.CENTER) cursor += spare * .5f;
            else if (layout.mainAlign == UiLayout.Align.END) cursor += spare;
        }
        for (UiElement child : children) {
            UiLayout cl = child.layout();
            float[] measured = measure(child);
            float cw = clamp(cl.width >= 0 ? cl.width : measured[0], cl.minWidth, cl.maxWidth);
            float ch = clamp(cl.height >= 0 ? cl.height : measured[1], cl.minHeight, cl.maxHeight);
            float cx = content.x() + cl.x;
            float cy = content.y() + cl.y;
            if (!cl.absolute && layout.flow == UiLayout.Flow.ROW) {
                cw = cl.flex > 0 ? flexSpace * cl.flex / totalFlex : cw;
                cx = cursor;
                cy = alignCross(content.y(), content.h(), ch, layout.crossAlign);
                if (layout.crossAlign == UiLayout.Align.STRETCH && cl.height < 0) ch = content.h();
                cursor += cw + layout.gap;
            } else if (!cl.absolute && layout.flow == UiLayout.Flow.COLUMN) {
                ch = cl.flex > 0 ? flexSpace * cl.flex / totalFlex : ch;
                cy = cursor;
                cx = alignCross(content.x(), content.w(), cw, layout.crossAlign);
                if (layout.crossAlign == UiLayout.Align.STRETCH && cl.width < 0) cw = content.w();
                cursor += ch + layout.gap;
            } else if (!cl.absolute && layout.flow == UiLayout.Flow.STACK) {
                if (cl.width < 0) cw = content.w();
                if (cl.height < 0) ch = content.h();
            }
            UiRect childBounds = new UiRect(cx, cy, Math.max(0, cw), Math.max(0, ch));
            elementBounds.put(child, childBounds);
            layoutChildren(child, childBounds);
        }
    }

    private float[] measure(UiElement element) {
        UiLayout l = element.layout();
        if (element instanceof UiText text) {
            List<String> lines = textLines(text, l.width >= 0 ? l.width : Float.POSITIVE_INFINITY);
            float measuredWidth = 0;
            for (String line : lines) measuredWidth = Math.max(measuredWidth, textWidth(line));
            float measuredHeight = lines.size() * font.lineHeight() * accessibility.textScale() * text.lineSpacing();
            return new float[]{l.width >= 0 ? l.width : measuredWidth,
                    l.height >= 0 ? l.height : measuredHeight};
        }
        if (element instanceof UiSpacer) return new float[]{Math.max(0, l.width), Math.max(0, l.height)};
        float w = 0;
        float h = 0;
        int count = 0;
        for (UiElement child : element.children()) {
            if (child.layout().absolute) continue;
            float[] size = measure(child);
            if (l.flow == UiLayout.Flow.ROW) { w += size[0]; h = Math.max(h, size[1]); }
            else if (l.flow == UiLayout.Flow.COLUMN) { w = Math.max(w, size[0]); h += size[1]; }
            else { w = Math.max(w, size[0]); h = Math.max(h, size[1]); }
            count++;
        }
        if (l.flow == UiLayout.Flow.ROW) w += Math.max(0, count - 1) * l.gap;
        if (l.flow == UiLayout.Flow.COLUMN) h += Math.max(0, count - 1) * l.gap;
        return new float[]{l.width >= 0 ? l.width : w + l.padding * 2,
                l.height >= 0 ? l.height : h + l.padding * 2};
    }

    private Hit findTopHit() {
        List<Hit> hits = new ArrayList<>();
        int[] order = {0};
        for (UiElement root : roots) collectHits(root, Affine.IDENTITY, new UiRect(0, 0, width, height), hits, order);
        hits.sort(Comparator.comparingInt(Hit::z).thenComparingInt(Hit::order).reversed());
        for (Hit hit : hits) if (hit.contains(input.mouseX(), input.mouseY())) return hit;
        return null;
    }

    private void collectHits(UiElement element, Affine parent, UiRect inheritedClip, List<Hit> hits, int[] order) {
        collectHits(element, parent, inheritedClip, hits, order, 0);
    }

    private void collectHits(UiElement element, Affine parent, UiRect inheritedClip,
                             List<Hit> hits, int[] order, int parentZ) {
        if (!element.visible()) return;
        UiRect bounds = elementBounds.get(element);
        Affine world = parent.multiply(localTransform(element, bounds));
        UiRect clip = element.layout().clip ? inheritedClip.intersect(world.aabb(bounds)) : inheritedClip;
        int effectiveZ = parentZ + element.layout().zIndex;
        if (element.interactive() && element.enabled()) hits.add(new Hit(element, bounds, world, clip,
                effectiveZ, order[0]++));
        List<UiElement> children = new ArrayList<>(element.children());
        children.sort(Comparator.comparingInt(child -> child.layout().zIndex));
        for (UiElement child : children) collectHits(child, world, clip, hits, order, effectiveZ);
    }

    private void updateElementInteraction(Hit hit) {
        String next = hit == null ? "" : hit.element.id();
        if (!next.equals(hoveredId)) {
            ElementState previous = elementStates.get(hoveredId);
            if (previous != null) dispatch(previous.element, UiEvent.Type.LEAVE, null);
            if (hit != null) dispatch(hit.element, UiEvent.Type.ENTER, hit);
        }
        hoveredId = next;
        if (hit != null) captured = true;
        if (input.mouseClicked() && hit != null && !pointerPressConsumed) {
            pointerPressConsumed = true;
            activeId = hit.element.id();
            setFocused(activeId);
            dispatch(hit.element, UiEvent.Type.PRESS, hit);
            dispatch(hit.element, UiEvent.Type.FOCUS, hit);
        }
        if (input.mouseDown() && !activeId.isEmpty()) {
            ElementState active = elementStates.get(activeId);
            if (active != null) dispatch(active.element, UiEvent.Type.DRAG,
                    hitFor(active.element));
        }
        if (input.mouseReleased() && !activeId.isEmpty()) {
            ElementState active = elementStates.get(activeId);
            if (active != null) {
                dispatch(active.element, UiEvent.Type.RELEASE, hit);
                if (activeId.equals(next)) dispatch(active.element, UiEvent.Type.CLICK, hit);
            }
            activeId = "";
        }
        if (hit != null && input.wheel() != 0) dispatch(hit.element, UiEvent.Type.SCROLL, hit);
        ElementState focused = elementStates.get(focusedId);
        if (focused != null) {
            if (!input.typed().isEmpty()) dispatchText(focused.element, input.typed());
            if (input.backspace()) dispatchEdit(focused.element, UiEvent.EditKey.BACKSPACE);
            if (input.delete()) dispatchEdit(focused.element, UiEvent.EditKey.DELETE);
            if (input.left()) dispatchEdit(focused.element, UiEvent.EditKey.LEFT);
            if (input.right()) dispatchEdit(focused.element, UiEvent.EditKey.RIGHT);
            if (input.home()) dispatchEdit(focused.element, UiEvent.EditKey.HOME);
            if (input.end()) dispatchEdit(focused.element, UiEvent.EditKey.END);
        }
    }

    private void dispatch(UiElement element, UiEvent.Type type, Hit hit) {
        if (element == null || element.eventHandler() == null) return;
        float localX = 0;
        float localY = 0;
        if (hit != null) {
            float[] local = hit.world.inverse(input.mouseX(), input.mouseY());
            localX = local[0] - hit.bounds.x();
            localY = local[1] - hit.bounds.y();
        }
        element.eventHandler().accept(new UiEvent(type, element.id(), localX, localY, input.wheel(),
                "", UiEvent.EditKey.NONE));
    }

    private void dispatchText(UiElement element, String text) {
        if (element.eventHandler() != null) element.eventHandler().accept(
                new UiEvent(UiEvent.Type.TEXT_INPUT, element.id(), 0, 0, 0, text, UiEvent.EditKey.NONE));
    }

    private void dispatchEdit(UiElement element, UiEvent.EditKey key) {
        if (element.eventHandler() != null) element.eventHandler().accept(
                new UiEvent(UiEvent.Type.EDIT, element.id(), 0, 0, 0, "", key));
    }

    private Hit hitFor(UiElement element) {
        List<Hit> hits = new ArrayList<>();
        int[] order = {0};
        for (UiElement root : roots) collectHits(root, Affine.IDENTITY,
                new UiRect(0, 0, width, height), hits, order);
        return hits.stream().filter(hit -> hit.element.id().equals(element.id())).findFirst().orElse(null);
    }

    private void paintElement(UiElement element) {
        if (!element.visible()) return;
        UiRect bounds = elementBounds.get(element);
        ElementState state = elementStates.computeIfAbsent(element.id(), ignored -> new ElementState());
        boolean typeChanged = state.element != null && state.element.getClass() != element.getClass();
        if (typeChanged) {
            warning = "ID type changed: " + element.id() + " "
                    + state.element.getClass().getSimpleName() + " -> " + element.getClass().getSimpleName();
            state.activeStates.clear();
        }
        boolean firstFrame = state.element == null || typeChanged || state.lastFrame < elementFrame - 1;
        state.element = element;
        state.bounds = bounds;
        state.lastFrame = animationsFrame();
        state.exiting = false;
        if (firstFrame && element.enterAnimation() != null) {
            animator.play(element.id(), element.enterAnimation(), InterruptionPolicy.REPLACE);
        }
        updateStateAnimations(element, state);
        UiTransform declared = element.transform();
        UiTransform transform = animator.valueOrElse(element.id(), UiAnimationProperties.TRANSFORM, declared);
        float opacity = animator.valueOrElse(element.id(), UiAnimationProperties.OPACITY, (double) element.opacity()).floatValue();
        float hoverScale = element.interactionMotion() && !accessibility.reducedUiAnimations() ? (float) animations.approach(element.id(), UiAnimationProperties.scalar("__hoverScale"),
                hoveredId.equals(element.id()) ? 1.025 : 1, 18, seconds(input.dt())) : 1.0f;
        float selectedScale = element.interactionMotion() && !accessibility.reducedUiAnimations() ? (float) animations.spring(element.id(), UiAnimationProperties.scalar("__selectedScale"),
                element.selected() ? 1.08 : 1, 220, 20, seconds(input.dt())) : 1.0f;
        draw.pushTransform(transform.translateX(), transform.translateY(),
                transform.scaleX() * hoverScale * selectedScale, transform.scaleY() * hoverScale * selectedScale,
                transform.rotationRadians(),
                bounds.x() + bounds.w() * transform.pivotX(), bounds.y() + bounds.h() * transform.pivotY());
        draw.pushOpacity(opacity);
        if (element.layout().clip) draw.pushClip(bounds.x(), bounds.y(), bounds.w(), bounds.h());
        paintPrimitive(element, bounds, state.activeStates);
        List<UiElement> children = new ArrayList<>(element.children());
        children.sort(Comparator.comparingInt(child -> child.layout().zIndex));
        for (UiElement child : children) paintElement(child);
        if (element.layout().clip) draw.popClip();
        draw.popOpacity();
        draw.popTransform();
    }

    private void paintPrimitive(UiElement element, UiRect bounds, Set<UiVisualState> activeStates) {
        if (element instanceof UiSurface surface) {
            java.util.EnumSet<UiVisualState> states=java.util.EnumSet.noneOf(UiVisualState.class);
            states.addAll(activeStates); states.addAll(surface.presentation());
            UiSurfaceStyle style=surface.resolveStyle(states,theme);
            draw.surface(bounds.x(),bounds.y(),bounds.w(),bounds.h(),surfaceData(style,element.tint()));
        } else if (element instanceof UiBox box) {
            draw.quad(bounds.x(), bounds.y(), bounds.x1(), bounds.y1(), multiply(box.color(), element.tint()));
            if (box.border() != null && box.borderWidth() > 0) {
                float bw = box.borderWidth();
                float[] c = multiply(box.border(), element.tint());
                draw.quad(bounds.x(), bounds.y(), bounds.x1(), bounds.y() + bw, c);
                draw.quad(bounds.x(), bounds.y1() - bw, bounds.x1(), bounds.y1(), c);
                draw.quad(bounds.x(), bounds.y(), bounds.x() + bw, bounds.y1(), c);
                draw.quad(bounds.x1() - bw, bounds.y(), bounds.x1(), bounds.y1(), c);
            }
        } else if (element instanceof UiText text) {
            List<String> lines = textLines(text, bounds.w());
            float lineHeight = font.lineHeight() * accessibility.textScale() * text.lineSpacing();
            for (int i = 0; i < lines.size(); i++) {
                draw.text(bounds.x(), bounds.y() + i * lineHeight, lines.get(i),
                        multiply(text.color(), element.tint()));
            }
        } else if (element instanceof UiSprite sprite) {
            UiRect uv = sprite.uv();
            draw.sprite(bounds.x(), bounds.y(), bounds.x1(), bounds.y1(),
                    uv.x(), uv.y(), uv.x1(), uv.y1(), sprite.texture(), sprite.pixelArt(), element.tint());
        } else if (element instanceof UiIcon icon) {
            UiRect uv = icon.uv();
            draw.sprite(bounds.x(), bounds.y(), bounds.x1(), bounds.y1(),
                    uv.x(), uv.y(), uv.x1(), uv.y1(), icon.atlas(), true, element.tint());
        } else if (element instanceof UiNineSlice slice) {
            paintNineSlice(slice, bounds);
        } else if (element instanceof UiCustom custom) {
            custom.painter().paint(draw, bounds, element.opacity());
        }
    }

    private UiSurfaceData surfaceData(UiSurfaceStyle style,float[] tint) {
        return new UiSurfaceData(
                UiSurfaceData.Shape.valueOf(style.shape().name()),
                multiply(style.primary(),tint),multiply(style.secondary(),tint),multiply(style.border(),tint),
                style.borderWidth(),style.cornerRadius(),style.gradientX(),style.gradientY(),style.gradientStrength(),
                UiSurfaceData.NoiseMode.valueOf(style.noiseMode().name()),style.noiseStrength(),
                UiSurfaceData.NoiseAnchor.valueOf(style.noiseAnchor().name()),style.edgeDarkening(),
                multiply(style.shadow(),tint),style.shadowOffsetX(),style.shadowOffsetY(),style.shadowExpansion(),
                style.shadowSoftness(),style.extensionWidth(),style.extensionHeight(),style.extensionOffset(),
                style.extensionOverlap(),style.extensionRadius(),
                UiSurfaceData.DebugMode.valueOf(style.debugMode().name()));
    }

    private List<String> textLines(UiText text, float availableWidth) {
        boolean shouldWrap = text.wrap() && Float.isFinite(availableWidth);
        float fontWidth = shouldWrap ? availableWidth / accessibility.textScale() : Float.MAX_VALUE;
        return font.wrap(localization.resolvePlainText(text.component()), fontWidth, text.maxLines(),
                text.overflow() == UiTextOverflow.ELLIPSIS);
    }

    private Affine localTransform(UiElement element, UiRect bounds) {
        UiTransform t = animator.valueOrElse(element.id(), UiAnimationProperties.TRANSFORM, element.transform());
        float px = bounds.x() + bounds.w() * t.pivotX();
        float py = bounds.y() + bounds.h() * t.pivotY();
        float cos = (float) Math.cos(t.rotationRadians());
        float sin = (float) Math.sin(t.rotationRadians());
        return new Affine(cos*t.scaleX(), sin*t.scaleX(), -sin*t.scaleY(), cos*t.scaleY(),
                t.translateX()+px-cos*t.scaleX()*px+sin*t.scaleY()*py,
                t.translateY()+py-sin*t.scaleX()*px-cos*t.scaleY()*py);
    }

    private float[] multiply(float[] a, float[] b) {
        float[] color = new float[]{a[0]*b[0], a[1]*b[1], a[2]*b[2],
                (a.length > 3 ? a[3] : 1) * (b.length > 3 ? b[3] : 1)};
        if (accessibility.highContrast()) {
            for (int i = 0; i < 3; i++) color[i] = Math.max(0, Math.min(1, .5f + (color[i] - .5f) * 1.55f));
        }
        return color;
    }

    private void paintNineSlice(UiNineSlice slice, UiRect bounds) {
        UiInsets b = slice.border();
        UiInsets uv = slice.uvBorder();
        float[] xs = {bounds.x(), Math.min(bounds.x1(), bounds.x()+b.left()),
                Math.max(bounds.x(), bounds.x1()-b.right()), bounds.x1()};
        float[] ys = {bounds.y(), Math.min(bounds.y1(), bounds.y()+b.top()),
                Math.max(bounds.y(), bounds.y1()-b.bottom()), bounds.y1()};
        float[] us = {0, uv.left(), 1-uv.right(), 1};
        float[] vs = {0, uv.top(), 1-uv.bottom(), 1};
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            draw.sprite(xs[x], ys[y], xs[x+1], ys[y+1], us[x], vs[y], us[x+1], vs[y+1],
                    slice.texture(), slice.pixelArt(), slice.tint());
        }
    }

    private float alignCross(float start, float available, float size, UiLayout.Align align) {
        return switch (align) {
            case CENTER -> start + (available - size) * 0.5f;
            case END -> start + available - size;
            default -> start;
        };
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int animationsFrame() {
        return elementFrame;
    }

    private int elementFrame;

    private static final class ElementState {
        UiElement element;
        UiRect bounds;
        int lastFrame;
        boolean exiting;
        final java.util.EnumSet<UiVisualState> activeStates = java.util.EnumSet.noneOf(UiVisualState.class);
    }

    private void updateStateAnimations(UiElement element, ElementState state) {
        java.util.EnumSet<UiVisualState> now = java.util.EnumSet.noneOf(UiVisualState.class);
        if (hoveredId.equals(element.id())) now.add(UiVisualState.HOVERED);
        if (activeId.equals(element.id())) now.add(UiVisualState.PRESSED);
        if (focusedId.equals(element.id())) now.add(UiVisualState.FOCUSED);
        if (element.selected()) now.add(UiVisualState.SELECTED);
        if (!element.enabled()) now.add(UiVisualState.DISABLED);
        for (UiVisualState visualState : now) {
            if (!state.activeStates.contains(visualState)) {
                AnimationClip clip = element.stateAnimations().get(visualState);
                if (clip != null) animator.play(element.id(), clip, InterruptionPolicy.RETARGET);
            }
        }
        state.activeStates.clear();
        state.activeStates.addAll(now);
    }

    private void startExitAnimations(Set<String> currentIds) {
        for (Map.Entry<String, ElementState> entry : new ArrayList<>(elementStates.entrySet())) {
            ElementState state = entry.getValue();
            if (currentIds.contains(entry.getKey()) || state.exiting || state.element == null) continue;
            AnimationClip exit = state.element.exitAnimation();
            if (exit == null) continue;
            state.exiting = true;
            animator.play(entry.getKey(), exit, InterruptionPolicy.REPLACE, .5,
                    (owner, handle) -> elementStates.remove(entry.getKey(), state));
        }
    }

    private void paintRetained(ElementState state) {
        UiElement element = state.element;
        UiRect bounds = state.bounds;
        UiTransform transform = animator.valueOrElse(element.id(), UiAnimationProperties.TRANSFORM, element.transform());
        float opacity = animator.valueOrElse(element.id(), UiAnimationProperties.OPACITY, (double) element.opacity()).floatValue();
        draw.pushTransform(transform.translateX(), transform.translateY(), transform.scaleX(), transform.scaleY(),
                transform.rotationRadians(), bounds.x()+bounds.w()*transform.pivotX(), bounds.y()+bounds.h()*transform.pivotY());
        draw.pushOpacity(opacity);
        paintPrimitive(element, bounds, state.activeStates);
        draw.popOpacity();
        draw.popTransform();
    }

    private record Hit(UiElement element, UiRect bounds, Affine world, UiRect clip, int z, int order) {
        boolean contains(float x, float y) {
            if (!clip.contains(x, y)) return false;
            float[] p = world.inverse(x, y);
            return bounds.contains(p[0], p[1]);
        }
    }

    private static Duration seconds(double value) {
        return Duration.ofNanos(Math.max(0L, Math.round(value * 1_000_000_000.0)));
    }

    private record Affine(float a, float b, float c, float d, float tx, float ty) {
        static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);
        Affine multiply(Affine o) {
            return new Affine(a*o.a+c*o.b, b*o.a+d*o.b, a*o.c+c*o.d, b*o.c+d*o.d,
                    a*o.tx+c*o.ty+tx, b*o.tx+d*o.ty+ty);
        }
        float[] apply(float x, float y) { return new float[]{a*x+c*y+tx, b*x+d*y+ty}; }
        float[] inverse(float x, float y) {
            float determinant = a*d-b*c;
            if (Math.abs(determinant) < 0.000001f) return new float[]{Float.NaN, Float.NaN};
            float px = x-tx;
            float py = y-ty;
            return new float[]{(d*px-c*py)/determinant, (-b*px+a*py)/determinant};
        }
        UiRect aabb(UiRect r) {
            float[] p0=apply(r.x(),r.y()), p1=apply(r.x1(),r.y()), p2=apply(r.x1(),r.y1()), p3=apply(r.x(),r.y1());
            float x0=Math.min(Math.min(p0[0],p1[0]),Math.min(p2[0],p3[0]));
            float y0=Math.min(Math.min(p0[1],p1[1]),Math.min(p2[1],p3[1]));
            float x1=Math.max(Math.max(p0[0],p1[0]),Math.max(p2[0],p3[0]));
            float y1=Math.max(Math.max(p0[1],p1[1]),Math.max(p2[1],p3[1]));
            return new UiRect(x0,y0,x1-x0,y1-y0);
        }
    }
}
