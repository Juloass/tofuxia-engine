package fr.tofuxia.app;

import io.github.juloass.math.Easings;

import fr.tofuxia.ui.UiContext;
import fr.tofuxia.ui.UiAnimationProperties;
import fr.tofuxia.ui.UiGroup;
import fr.tofuxia.ui.UiLayout;
import fr.tofuxia.ui.UiRect;
import fr.tofuxia.ui.UiRole;
import fr.tofuxia.ui.UiSprite;
import fr.tofuxia.ui.UiSurface;
import fr.tofuxia.ui.UiSurfaceNoiseAnchor;
import fr.tofuxia.ui.UiSurfaceNoiseMode;
import fr.tofuxia.ui.UiSurfaceShape;
import fr.tofuxia.ui.UiSurfaceStyle;
import fr.tofuxia.ui.UiText;
import fr.tofuxia.ui.UiTheme;
import io.github.juloass.uianimation.AnimationClip;
import io.github.juloass.uianimation.Interpolators;
import io.github.juloass.uianimation.InterruptionPolicy;
import io.github.juloass.uianimation.UiMotionCategory;
import io.github.juloass.uianimation.UiTransform;
import java.time.Duration;
import io.github.juloass.localization.TextComponent;

import java.util.Locale;

/** Full-screen connection presentation with a throttled, real reconnect action. */
final class ConnectionScreen {
    static final float RETRY_COOLDOWN_SECONDS = 5.0f;
    private static final float BACKGROUND_ASPECT = 1672.0f / 941.0f;
    private static final String RETRY_LABEL_ID = "connection-screen/retry/label";
    private static final AnimationClip RETRY_COUNTDOWN_POP = AnimationClip.builder()
            .track(UiAnimationProperties.TRANSFORM, transform(1), transform(1.08f),
                    Interpolators.TRANSFORM, UiMotionCategory.SCALE)
            .then(Duration.ofMillis(80), Easings.BACK_OUT)
            .track(UiAnimationProperties.TRANSFORM, transform(1.08f), transform(1),
                    Interpolators.TRANSFORM, UiMotionCategory.SCALE)
            .then(Duration.ofMillis(160), Easings.QUADRATIC_OUT)
            .build();

    private final String backgroundTexture;
    private float retryCooldown;
    private int displayedCountdownSecond;

    ConnectionScreen(String backgroundTexture) {
        this.backgroundTexture = backgroundTexture == null ? "" : backgroundTexture;
    }

    void draw(UiContext ui, NetworkStatusProvider network, float dt) {
        advanceCooldown(dt);
        float width = ui.width();
        float height = ui.height();

        UiGroup root = new UiGroup("connection-screen");
        root.focusTrap(true);
        root.layout().position(0, 0).size(width, height).absolute(true).zIndex(490)
                .flow(UiLayout.Flow.STACK);
        if (backgroundTexture.isBlank()) {
            UiSurface fallback = new UiSurface("connection-screen/background-fallback",
                    UiSurfaceStyle.builder()
                            .primary(ui.theme().charcoal).secondary(ui.theme().charcoal)
                            .borderWidth(0)
                            .shape(UiSurfaceShape.RECT).cornerRadius(0).gradient(0, 1, 0)
                            .noise(UiSurfaceNoiseMode.NONE, 0, UiSurfaceNoiseAnchor.ELEMENT_LOCAL)
                            .edgeDarkening(0).shadow(UiTheme.rgba(0, 0, 0, 0), 0, 0, 0, 0)
                            .build());
            fallback.layout().size(width, height);
            root.child(fallback);
        } else {
            UiSprite background = new UiSprite("connection-screen/background", backgroundTexture,
                    coverUv(width, height), false);
            background.layout().size(width, height);
            root.child(background);
        }

        float panelWidth = Math.min(ui.sx(620), Math.max(ui.sx(280), width - ui.sx(48)));
        float panelHeight = ui.sx(154);
        UiSurfaceStyle panelStyle = UiSurfaceStyle.panel(ui.theme()).toBuilder()
                .primary(ui.theme().charcoal).secondary(ui.theme().charcoal)
                .borderWidth(1)
                .cornerRadius(ui.sx(12)).gradient(0, 1, 0)
                .noise(UiSurfaceNoiseMode.NONE, 0, UiSurfaceNoiseAnchor.ELEMENT_LOCAL)
                .edgeDarkening(0)
                .shadow(UiTheme.rgba(0, 0, 0, .48f), 0, ui.sx(3), ui.sx(2), ui.sx(6))
                .build();
        UiSurface panel = new UiSurface("connection-screen/panel", panelStyle);
        panel.layout().position((width - panelWidth) * .5f, (height - panelHeight) * .5f)
                .size(panelWidth, panelHeight).absolute(true).padding(ui.sx(22)).gap(ui.sx(18))
                .flow(UiLayout.Flow.COLUMN).align(UiLayout.Align.CENTER, UiLayout.Align.CENTER);

        String status = displayStatus(network.statusLine());
        StatusPresentation presentation = presentStatus(status);
        TextComponent title = localizedStatus(presentation.title());
        panel.child(new UiText("connection-screen/status-title", truncate(ui, title, 48), ui.theme().ivory));
        if (!presentation.detail().isBlank()) {
            panel.child(new UiText("connection-screen/status-detail", truncate(ui, localizedStatus(presentation.detail()), 86),
                    ui.theme().warmGold));
        }

        boolean ready = retryCooldown <= 0 && retryable(status);
        int countdownSecond = retryCooldown > 0 ? (int) Math.ceil(retryCooldown) : 0;
        animateCountdownTick(ui, countdownSecond);
        TextComponent label = countdownSecond > 0
                ? TextComponent.translatable("screen.engine.connection.retry_in", countdownSecond)
                : TextComponent.translatable("screen.engine.connection.retry");
        UiSurface retry = new UiSurface("connection-screen/retry",
                solidButtonStyle(ui, ready ? ui.theme().orange : ui.theme().charcoal, .36f))
                .stateStyle(fr.tofuxia.ui.UiVisualState.HOVERED,
                        solidButtonStyle(ui, ui.theme().warmGold, .40f))
                .stateStyle(fr.tofuxia.ui.UiVisualState.PRESSED,
                        solidButtonStyle(ui, ui.theme().yellow, .28f))
                .stateStyle(fr.tofuxia.ui.UiVisualState.DISABLED,
                        solidButtonStyle(ui, ui.theme().charcoal, .20f));
        retry.layout().size(ui.sx(190), ui.sx(38)).flow(UiLayout.Flow.COLUMN)
                .align(UiLayout.Align.CENTER, UiLayout.Align.CENTER);
        retry.enabled(ready).onEvent(event -> {
            if (event.type() == fr.tofuxia.ui.UiEvent.Type.CLICK) attemptRetry(network);
        }).semantics(UiRole.BUTTON, label);
        retry.child(new UiText(RETRY_LABEL_ID, label,
                ready ? ui.theme().charcoal : ui.theme().ivory));
        panel.child(retry);
        root.child(panel);
        ui.submit(root);
    }

    private static UiSurfaceStyle solidButtonStyle(UiContext ui, float[] body, float shadowOpacity) {
        return UiSurfaceStyle.button(ui.theme()).toBuilder()
                .primary(body).secondary(body).borderWidth(1)
                .cornerRadius(ui.sx(7)).gradient(0, 1, 0)
                .noise(UiSurfaceNoiseMode.NONE, 0, UiSurfaceNoiseAnchor.ELEMENT_LOCAL)
                .edgeDarkening(0)
                .shadow(UiTheme.rgba(0, 0, 0, shadowOpacity), 0, ui.sx(2), ui.sx(1), ui.sx(3))
                .build();
    }

    private void animateCountdownTick(UiContext ui, int countdownSecond) {
        if (countdownSecond == displayedCountdownSecond) return;
        displayedCountdownSecond = countdownSecond;
        if (countdownSecond > 0 && !ui.accessibility().reducedUiAnimations()) {
            ui.animator().play(RETRY_LABEL_ID, RETRY_COUNTDOWN_POP, InterruptionPolicy.REPLACE);
        }
    }

    private static UiTransform transform(float scale) {
        return UiTransform.scaled(scale);
    }

    boolean attemptRetry(NetworkStatusProvider network) {
        if (retryCooldown > 0 || !retryable(displayStatus(network.statusLine()))) return false;
        if (!network.requestReconnect()) return false;
        retryCooldown = RETRY_COOLDOWN_SECONDS;
        return true;
    }

    void advanceCooldown(float dt) {
        retryCooldown = Math.max(0, retryCooldown - Math.max(0, dt));
    }

    float retryCooldown() {
        return retryCooldown;
    }

    static boolean retryable(String status) {
        String normalized = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return normalized.contains("disconnected") || normalized.contains("rejected")
                || normalized.contains("failed") || normalized.contains("error");
    }

    static StatusPresentation presentStatus(String rawStatus) {
        String status = displayStatus(rawStatus).strip();
        String normalized = status.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("disconnected") || normalized.startsWith("failed")
                || normalized.startsWith("error")) {
            return new StatusPresentation("Failed to connect", failureDetail(status, normalized));
        }
        if (normalized.startsWith("rejected")) {
            String detail = status.substring("rejected".length()).strip();
            return new StatusPresentation("Connection rejected",
                    detail.isBlank() ? "The server rejected this connection." : sentenceCase(detail));
        }
        if (normalized.startsWith("reconnecting")) {
            return new StatusPresentation("Reconnecting…", pendingDetail(status, "reconnecting"));
        }
        if (normalized.startsWith("connecting")) {
            return new StatusPresentation("Connecting…", pendingDetail(status, "connecting"));
        }
        if (normalized.startsWith("retrying authentication")) {
            return new StatusPresentation("Retrying authentication…", "");
        }
        return new StatusPresentation(status, "");
    }

    private static String failureDetail(String status, String normalized) {
        int open = status.lastIndexOf('(');
        int close = status.endsWith(")") ? status.length() - 1 : -1;
        if (open >= 0 && close > open + 1) {
            String code = status.substring(open + 1, close).strip();
            return explainError(code) + " · " + code;
        }
        String prefix = normalized.startsWith("disconnected") ? "disconnected"
                : normalized.startsWith("failed") ? "failed" : "error";
        String detail = status.substring(prefix.length()).strip();
        if (!detail.isBlank()) return sentenceCase(detail);
        return normalized.startsWith("disconnected")
                ? "The server closed the connection."
                : "The server could not be reached.";
    }

    private static String explainError(String code) {
        String normalized = code.toLowerCase(Locale.ROOT);
        if (normalized.contains("connectexception")) return "Connection refused";
        if (normalized.contains("unknownhost")) return "Server address not found";
        if (normalized.contains("noroutetohost")) return "Server could not be reached";
        if (normalized.contains("timeout")) return "Connection timed out";
        if (normalized.contains("closedchannel") || normalized.contains("socketexception")) return "Connection closed";
        if (normalized.contains("ssl") || normalized.contains("tls")) return "Secure connection failed";
        return "Connection error";
    }

    private static String sentenceCase(String value) {
        return value.isBlank() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String pendingDetail(String status, String prefix) {
        String detail = status.substring(prefix.length()).strip();
        return detail.equals("…") || detail.equals("...") ? "" : detail;
    }

    static UiRect coverUv(float width, float height) {
        float screenAspect = Math.max(1, width) / Math.max(1, height);
        if (screenAspect > BACKGROUND_ASPECT) {
            float visibleHeight = BACKGROUND_ASPECT / screenAspect;
            return new UiRect(0, (1 - visibleHeight) * .5f, 1, visibleHeight);
        }
        float visibleWidth = screenAspect / BACKGROUND_ASPECT;
        return new UiRect((1 - visibleWidth) * .5f, 0, visibleWidth, 1);
    }

    private static String displayStatus(String status) {
        if (status == null || status.isBlank()) return "Connecting…";
        return status.startsWith("Network: ") ? status.substring("Network: ".length()) : status;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private static TextComponent truncate(UiContext ui, TextComponent value, int max) {
        return TextComponent.literal(truncate(ui.localization().resolvePlainText(value), max));
    }

    private static TextComponent localizedStatus(String value) {
        String key = switch (value) {
            case "Failed to connect" -> "failed";
            case "Connection rejected" -> "rejected";
            case "The server rejected this connection." -> "server_rejected";
            case "Reconnecting…" -> "reconnecting";
            case "Connecting…" -> "connecting";
            case "Retrying authentication…" -> "retrying_authentication";
            case "The server closed the connection." -> "server_closed";
            case "The server could not be reached." -> "server_unreachable";
            case "Connection refused" -> "refused";
            case "Server address not found" -> "unknown_host";
            case "Server could not be reached" -> "unreachable";
            case "Connection timed out" -> "timeout";
            case "Connection closed" -> "closed";
            case "Secure connection failed" -> "secure_failed";
            case "Connection error" -> "error";
            default -> null;
        };
        return key == null ? TextComponent.literal(value) : TextComponent.translatable("screen.engine.connection." + key);
    }

    record StatusPresentation(String title, String detail) {}
}
