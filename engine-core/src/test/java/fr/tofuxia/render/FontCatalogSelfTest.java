package fr.tofuxia.render;

import java.nio.file.Path;

public final class FontCatalogSelfTest {
    public static void main(String[] args) {
        Path assetRoot = Path.of("assets");
        try (FontCatalog fonts = FontCatalog.load(assetRoot,
                Path.of("tofuxia", "fonts", "fonts.properties"))) {
            require(fonts.path(FontRole.UI).endsWith("inter-variable.ttf"), "UI role must use Inter");
            require(fonts.path(FontRole.DEBUG).equals(fonts.path(FontRole.UI)),
                    "debug and UI roles should share the configured face");
            require(fonts.path(FontRole.WORLD_NAME_TAG).equals(fonts.path(FontRole.UI)),
                    "world name tags must use Inter");
            require(fonts.path(FontRole.GAME_TITLE).endsWith("achafsex.ttf"),
                    "Achafsex must be reserved for the game title");

            FontAtlas ui = fonts.atlas(FontRole.UI, 15);
            FontAtlas debug = fonts.atlas(FontRole.DEBUG, 15);
            FontAtlas nameTag = fonts.atlas(FontRole.WORLD_NAME_TAG, 24);
            FontAtlas gameTitle = fonts.atlas(FontRole.GAME_TITLE, 52);
            require(ui == debug, "same face and size must reuse one atlas");
            require(ui != nameTag, "different font sizes must use distinct atlases");
            require(nameTag.source().equals(ui.source()), "name tags must use the Inter asset");
            require(!gameTitle.source().equals(ui.source()), "game title must use its reserved face");
            require(gameTitle.sources().size() == 2 && gameTitle.sources().get(1).equals(ui.source()),
                    "game-title family must retain Inter as its fallback");
            require(gameTitle.supports('Ж'), "game-title fallback must supply translated Cyrillic text");
            require(ui.source().equals(fonts.path(FontRole.UI)), "atlas must retain its asset source");
            require(nameTag.glyph('A').inkWidth() > 0, "name-tag font did not rasterize");

            UiBuilder builder = new UiBuilder(ui);
            builder.text(gameTitle, 10, 10, "TOFUXIA", new float[]{1, 1, 1, 1});
            UiRenderData titleData = builder.toRenderData();
            require(titleData.batches().size() == 1, "game title should produce one font batch");
            require(gameTitle.atlasId().equals(titleData.batches().getFirst().texture()),
                    "game title batch must select only the reserved atlas");
        }
        System.out.println("FontCatalogSelfTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
