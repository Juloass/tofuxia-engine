package fr.tofuxia.ui;

import fr.tofuxia.render.FontAtlas;
import fr.tofuxia.render.UiRenderData;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic layout screenshots spanning resolution, DPI, aspect ratio and user scale. */
public final class UiDisplayMatrixSelfTest {
    private record Case(String id, UiViewport viewport, int outputWidth, int outputHeight) {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of("build", "diagnostics", "ui-display-matrix");
        Files.createDirectories(output);
        try (FontAtlas font = new FontAtlas(Path.of("test-fixtures/assets/tofuxia/fonts/inter-variable.ttf"), 15)) {
            Case[] cases = {
                    new Case("720p-1x", new UiViewport(1280, 720, 1, 1, 1, UiInsets.all(0)), 640, 360),
                    new Case("720p-2x-dpi", new UiViewport(2560, 1440, 2, 2, 1, UiInsets.all(0)), 640, 360),
                    new Case("1080p-1x", new UiViewport(1920, 1080, 1, 1, 1, UiInsets.all(0)), 640, 360),
                    new Case("1080p-2x-dpi", new UiViewport(3840, 2160, 2, 2, 1, UiInsets.all(0)), 640, 360),
                    new Case("compact-200-percent", new UiViewport(1280, 720, 1, 1, 2, UiInsets.all(0)), 640, 360),
                    new Case("ultrawide", new UiViewport(3440, 1440, 1, 1, 1, UiInsets.all(0)), 860, 360)
            };
            Map<String, Long> hashes = new LinkedHashMap<>();
            for (Case value : cases) {
                UiRenderData data = render(font, value.viewport());
                BufferedImage image = rasterize(data, value.viewport().framebufferWidth(),
                        value.viewport().framebufferHeight(), value.outputWidth(), value.outputHeight());
                ImageIO.write(image, "png", output.resolve(value.id() + ".png").toFile());
                long hash = hash(image);
                require(hash != 0, value.id() + " screenshot is nonempty");
                hashes.put(value.id(), hash);
            }
            require(hashes.get("720p-1x").equals(hashes.get("720p-2x-dpi")),
                    "720p visual output is invariant across framebuffer density");
            require(hashes.get("1080p-1x").equals(hashes.get("1080p-2x-dpi")),
                    "1080p visual output is invariant across framebuffer density");
            require(!hashes.get("compact-200-percent").equals(hashes.get("720p-1x")),
                    "user scale changes responsive composition");
            System.out.println("[engine-ui] display matrix passed " + hashes);
        }
    }

    private static UiRenderData render(FontAtlas font, UiViewport viewport) {
        UiContext ui = new UiContext(font);
        ui.begin(UiInput.mouseOnly(-1, -1, false, 1f / 60f), viewport);
        UiRect safe = ui.safeBounds();
        UiBox canvas = new UiBox("matrix/canvas", UiTheme.rgba(.025f, .03f, .04f, 1));
        canvas.layout().position(safe.x(), safe.y()).size(safe.w(), safe.h()).flow(UiLayout.Flow.STACK);

        float panelWidth = Math.min(ui.responsive(300, 460, 620), safe.w() - 32);
        UiBox panel = new UiBox("matrix/panel", UiTheme.rgba(.08f, .10f, .14f, .98f))
                .border(UiTheme.rgba(.45f, .55f, .7f, 1), 1);
        panel.layout().position((safe.w() - panelWidth) * .5f, 36).size(panelWidth, 230)
                .padding(18).gap(10).flow(UiLayout.Flow.COLUMN);
        panel.child(new UiText("matrix/title", "Tofuxia UI matrix", ui.theme().titleText));
        UiText body = new UiText("matrix/body", "Été · Ω · Ж — localized text wraps deterministically across displays.",
                ui.theme().text).wrap(true).maxLines(3).overflow(UiTextOverflow.ELLIPSIS);
        body.layout().size(panelWidth - 36, -1);
        panel.child(body);
        panel.child(UiWidgets.button(ui, "matrix/continue", "Continue", panelWidth - 36, 38, () -> {}));
        panel.child(UiWidgets.button(ui, "matrix/cancel", "Cancel", panelWidth - 36, 34, () -> {}));
        canvas.child(panel);
        ui.submit(canvas);
        return ui.end();
    }

    private static BufferedImage rasterize(UiRenderData data, int framebufferWidth, int framebufferHeight,
                                           int outputWidth, int outputHeight) {
        BufferedImage image = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, outputWidth, outputHeight);
        float sx = outputWidth / (float) framebufferWidth;
        float sy = outputHeight / (float) framebufferHeight;
        float[] vertices = data.vertices();
        int[] indices = data.indices();
        for (int i = 0; i + 2 < indices.length; i += 3) {
            Polygon polygon = new Polygon();
            float r = 0, g = 0, b = 0, a = 0;
            for (int corner = 0; corner < 3; corner++) {
                int offset = indices[i + corner] * UiRenderData.FLOATS_PER_VERTEX;
                polygon.addPoint(Math.round(vertices[offset] * sx), Math.round(vertices[offset + 1] * sy));
                r += vertices[offset + 4]; g += vertices[offset + 5];
                b += vertices[offset + 6]; a += vertices[offset + 7];
            }
            graphics.setColor(new Color(clamp(r / 3), clamp(g / 3), clamp(b / 3), clamp(a / 3)));
            graphics.fillPolygon(polygon);
        }
        graphics.dispose();
        return image;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }

    private static long hash(BufferedImage image) {
        long value = 0xcbf29ce484222325L;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            value ^= image.getRGB(x, y);
            value *= 0x100000001b3L;
        }
        return value;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
