package fr.tofuxia.render;

import java.nio.file.Path;
import java.util.List;

/** Focused atlas regression checks for glyph identity, punctuation and descenders. */
public final class FontAtlasSelfTest {
    public static void main(String[] args) {
        FontAtlas atlas = new FontAtlas(Path.of("test-fixtures/assets/tofuxia/fonts/inter-variable.ttf"), 15);
        char[] chars = {':', '?', 'g', 'j', 'p', 'q', 'y', 'A'};
        for (char c : chars) {
            FontAtlas.Glyph glyph = atlas.glyph(c);
            long fingerprint = fingerprint(atlas, glyph);
            System.out.printf("%c index=%d rect=%d,%d %dx%d advance=%d bearing=%d,%d pixels=%d hash=%x%n",
                    c, glyph.glyphIndex(), glyph.x(), glyph.y(), glyph.width(), glyph.height(),
                    glyph.advance(), glyph.bearingX(), glyph.bearingY(), ink(atlas, glyph), fingerprint);
            require(ink(atlas, glyph) > 0, "glyph has ink: " + c);
        }
        require(atlas.glyph(':').glyphIndex() != atlas.glyph('?').glyphIndex(), "colon and question mark glyph ids differ");
        require(fingerprint(atlas, atlas.glyph(':')) != fingerprint(atlas, atlas.glyph('?')), "colon and question mark bitmaps differ");
        require(atlas.layout(":?gjpqyA").length == 8, "HarfBuzz preserves probe glyph count");
        require(atlas.layout(":?gjpqyA")[0].glyph().glyphIndex() != atlas.layout(":?gjpqyA")[1].glyph().glyphIndex(),
                "HarfBuzz keeps colon and question-mark substitutions distinct");
        require(atlas.layout(":gj").length == 3, "HarfBuzz preserves shorter ASCII glyph count");
        require(atlas.layout(":gj")[0].glyph().inkWidth() > 0, "HarfBuzz maps colon in shorter text to visible ink");
        require(atlas.supports('é') && atlas.supports('Ω') && atlas.supports('Ж'), "Unicode coverage is rasterized");
        require(atlas.layout("Été Ω Ж").length > 0, "Unicode text shapes");
        require(atlas.wrap("Localized interface text wraps deterministically", 90, 2, true).size() == 2,
                "Unicode-aware line wrapping obeys maximum lines");
        require(atlas.wrap("First line  \r\nSecond line\n", Float.MAX_VALUE, Integer.MAX_VALUE, false)
                        .equals(List.of("First line", "Second line", "")),
                "non-wrapping text preserves normalized line breaks and trailing-space cleanup");
        require(atlas.wrap("First line\nSecond line", Float.MAX_VALUE, 1, true).equals(List.of("First line…")),
                "non-wrapping text preserves maximum-line ellipsis behavior");
        FontAtlas.PositionedGlyph[] cacheSentinelBefore = atlas.layout("non-wrapping-cache-sentinel");
        atlas.wrap("界".repeat(800), Float.MAX_VALUE, Integer.MAX_VALUE, false);
        FontAtlas.PositionedGlyph[] cacheSentinelAfter = atlas.layout("non-wrapping-cache-sentinel");
        require(cacheSentinelBefore[0] == cacheSentinelAfter[0],
                "non-wrapping text does not evict reusable shaped layouts with transient prefixes");
        String longUnicodeToken = "ÉlectroencéphalographiquementΩЖ界🙂supercalifragilisticexpialidocious";
        List<String> emergencyLines = atlas.wrap(longUnicodeToken, 72, Integer.MAX_VALUE, false);
        require(emergencyLines.size() > 1, "over-wide Unicode token emergency-wraps");
        require(String.join("", emergencyLines).equals(longUnicodeToken),
                "emergency wrapping preserves every Unicode codepoint");
        require(emergencyLines.stream().limit(emergencyLines.size() - 1L)
                        .allMatch(line -> atlas.textWidth(line) <= 72),
                "emergency wrapped lines respect the requested width");
        FontAtlas.PositionedGlyph[] cachedA = atlas.layout("Frame profiler 1.23 ms / 45%");
        FontAtlas.PositionedGlyph[] cachedB = atlas.layout("Frame profiler 1.23 ms / 45%");
        require(cachedA != cachedB, "layout returns caller-owned arrays");
        require(cachedA.length == cachedB.length, "cached layout length stable");
        require(cachedA[0].glyph().glyphIndex() == cachedB[0].glyph().glyphIndex(), "cached layout glyph identity stable");
        FontAtlas.GlyphQuad[] baselineProbe = atlas.layoutQuads("Agj", 0, 0);
        require(baselineProbe.length == 3, "baseline probe emits three ink quads");
        require(baselineProbe[1].y1() > baselineProbe[0].y1(), "g descender extends below A");
        require(baselineProbe[2].y1() > baselineProbe[0].y1(), "j descender extends below A");
        FontAtlas.Glyph guardedGlyph = atlas.layout("g")[0].glyph();
        FontAtlas.GlyphQuad guardedQuad = atlas.layoutQuads("g", 0.5f, 0.5f)[0];
        require(guardedQuad.x1() - guardedQuad.x0() == guardedGlyph.inkWidth() + 2,
                "filtered glyph quad includes horizontal transparent guards");
        require(guardedQuad.y1() - guardedQuad.y0() == guardedGlyph.inkHeight() + 2,
                "filtered glyph quad includes vertical transparent guards");
        require(Math.round(guardedQuad.u0() * atlas.width()) == guardedGlyph.inkX() - 1
                        && Math.round(guardedQuad.v0() * atlas.height()) == guardedGlyph.inkY() - 1,
                "filtered glyph UV starts in the atlas guard texel");
        require(Math.round(guardedQuad.u1() * atlas.width()) == guardedGlyph.inkX() + guardedGlyph.inkWidth() + 1
                        && Math.round(guardedQuad.v1() * atlas.height()) == guardedGlyph.inkY() + guardedGlyph.inkHeight() + 1,
                "filtered glyph UV ends after the ink instead of clipping its last row");
        for (char a = FontAtlas.FIRST_CHAR; a <= FontAtlas.LAST_CHAR; a++) {
            for (char b = (char) (a + 1); b <= FontAtlas.LAST_CHAR; b++) {
                require(!overlaps(atlas.glyph(a), atlas.glyph(b)), "atlas cells overlap: " + a + " / " + b);
            }
        }
        System.out.println("[font-atlas] all checks passed");
    }

    private static int ink(FontAtlas atlas, FontAtlas.Glyph glyph) {
        int count = 0;
        byte[] pixels = atlas.pixels();
        for (int y = glyph.y(); y < glyph.y() + glyph.height(); y++)
            for (int x = glyph.x(); x < glyph.x() + glyph.width(); x++)
                if (pixels[y * atlas.width() + x] != 0) count++;
        return count;
    }

    private static long fingerprint(FontAtlas atlas, FontAtlas.Glyph glyph) {
        long hash = 0xcbf29ce484222325L;
        byte[] pixels = atlas.pixels();
        for (int y = glyph.y(); y < glyph.y() + glyph.height(); y++) {
            for (int x = glyph.x(); x < glyph.x() + glyph.width(); x++) {
                hash ^= pixels[y * atlas.width() + x] & 255;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static boolean overlaps(FontAtlas.Glyph a, FontAtlas.Glyph b) {
        return a.x() < b.x() + b.width() && a.x() + a.width() > b.x()
                && a.y() < b.y() + b.height() && a.y() + a.height() > b.y();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
