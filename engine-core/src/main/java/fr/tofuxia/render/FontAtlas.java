package fr.tofuxia.render;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.freetype.FT_Bitmap;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FT_GlyphSlot;
import org.lwjgl.util.freetype.FreeType;
import org.lwjgl.util.harfbuzz.HarfBuzz;
import org.lwjgl.util.harfbuzz.hb_glyph_info_t;
import org.lwjgl.util.harfbuzz.hb_glyph_position_t;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.text.BreakIterator;
import java.util.Locale;

/**
 * CPU-side Unicode glyph atlas rasterized once with LWJGL FreeType and shaped
 * with HarfBuzz. Unsupported characters fall back to '?'.
 */
public final class FontAtlas implements AutoCloseable {
    static {
        // LWJGL ships the HarfBuzz symbols inside its FreeType native library;
        // point the HarfBuzz bindings at the loaded FreeType library so the
        // hb_ft_* interop functions resolve.
        org.lwjgl.system.Configuration.HARFBUZZ_LIBRARY_NAME.set(FreeType.getLibrary());
    }

    public static final int FIRST_CHAR = 32;
    public static final int LAST_CHAR = 126;

    private static final int ATLAS_WIDTH = 2048;
    private static final int PADDING = 1;
    private static final int FILTER_GUARD = 1;
    private static final int MAX_LAYOUT_CACHE_ENTRIES = 512;
    private final int width;
    private final int height;
    private final byte[] pixels;
    private final Glyph[] glyphs = new Glyph[LAST_CHAR - FIRST_CHAR + 1];
    private final Map<Integer, Glyph> glyphsByCodepoint = new HashMap<>();
    private final Map<Long, Glyph> glyphsByFaceIndex = new HashMap<>();
    private final Map<Integer, Integer> faceByCodepoint = new HashMap<>();
    private final float lineHeight;
    private final float ascent;
    private final long library;
    private final List<FaceResource> faces;
    private final List<Path> sources;
    private final Path source;
    private final int pixelSize;
    private boolean closed;
    private final Map<String, PositionedGlyph[]> layoutCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PositionedGlyph[]> eldest) {
            return size() > MAX_LAYOUT_CACHE_ENTRIES;
        }
    };

    public FontAtlas(Path fontFile, float fontSizePx) {
        this(List.of(fontFile), fontSizePx);
    }

    public FontAtlas(List<Path> fontFiles, float fontSizePx) {
        this(fontFiles == null ? null : fontFiles.stream()
                        .map(path -> new FontSource(
                                path.toAbsolutePath().normalize(),
                                readFont(path)))
                        .toList(),
                fontSizePx,
                true);
    }

    public static FontAtlas fromSources(
            List<FontSource> sources,
            float fontSizePx
    ) {
        return new FontAtlas(sources, fontSizePx, true);
    }

    private FontAtlas(
            List<FontSource> fontSources,
            float fontSizePx,
            boolean memorySources
    ) {
        if (fontSources == null || fontSources.isEmpty()) {
            throw new IllegalArgumentException("At least one font is required");
        }
        sources = fontSources.stream().map(FontSource::source)
                .distinct().toList();
        if (sources.size() != fontSources.size()) {
            throw new IllegalArgumentException("Duplicate font source");
        }
        source = sources.getFirst();
        pixelSize = Math.max(1, Math.round(fontSizePx));
        long libraryHandle = 0;
        List<FaceResource> loadedFaces = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer libraryOut = stack.mallocPointer(1);
            check(FreeType.FT_Init_FreeType(libraryOut), "FT_Init_FreeType");
            libraryHandle = libraryOut.get(0);
            for (FontSource fontSource : fontSources) {
                Path path = fontSource.source();
                ByteBuffer data = loadFont(fontSource.bytes());
                PointerBuffer faceOut = stack.mallocPointer(1);
                check(FreeType.FT_New_Memory_Face(libraryHandle, data, 0, faceOut), "FT_New_Memory_Face");
                long faceAddress = faceOut.get(0);
                FT_Face face = FT_Face.create(faceAddress);
                check(FreeType.FT_Set_Pixel_Sizes(face, 0, pixelSize), "FT_Set_Pixel_Sizes");
                long hbFont = HarfBuzz.hb_ft_font_create_referenced(faceAddress);
                if (hbFont == 0) throw new IllegalStateException("hb_ft_font_create_referenced failed");
                HarfBuzz.hb_ft_font_set_funcs(hbFont);
                loadedFaces.add(new FaceResource(path, data, faceAddress, hbFont));
            }

            int ascender = loadedFaces.stream().mapToInt(value ->
                    (int) (FT_Face.create(value.faceAddress).size().metrics().ascender() >> 6)).max().orElse(pixelSize);
            int descender = loadedFaces.stream().mapToInt(value ->
                    (int) (FT_Face.create(value.faceAddress).size().metrics().descender() >> 6)).min().orElse(0);
            int rowHeight = loadedFaces.stream().mapToInt(value -> Math.max(1,
                    (int) (FT_Face.create(value.faceAddress).size().metrics().height() >> 6))).max().orElse(pixelSize);
            int cellHeight = Math.max(1, ascender - descender) + 2;
            lineHeight = rowHeight;
            ascent = ascender;

            int[] codepoints = supportedCodepoints(loadedFaces);
            List<GlyphRaster> extraGlyphs = new ArrayList<>();
            int penX = PADDING;
            int penY = PADDING;
            for (int c : codepoints) {
                int faceIndex = supportingFace(loadedFaces, c);
                FT_Face face = FT_Face.create(loadedFaces.get(faceIndex).faceAddress);
                LoadedGlyph loaded = loadGlyph(face, c, ascender, descender);
                if (penX + loaded.width + PADDING > ATLAS_WIDTH) {
                    penX = PADDING;
                    // Loaded glyph cells include a one-pixel guard on both
                    // sides. Advancing by the font line height made adjacent
                    // atlas rows overlap and overwrite punctuation/descenders.
                    penY += cellHeight + PADDING;
                }
                Glyph glyph = new Glyph(penX, penY, loaded.width, loaded.height,
                        loaded.advance, loaded.bearingX, loaded.bearingY, loaded.glyphIndex,
                        penX + 1, penY + 1 + loaded.drawOffsetY, loaded.inkWidth, loaded.inkHeight,
                        loaded.drawOffsetX, loaded.drawOffsetY);
                glyphsByCodepoint.put(c, glyph);
                faceByCodepoint.put(c, faceIndex);
                if (c >= FIRST_CHAR && c <= LAST_CHAR) glyphs[c - FIRST_CHAR] = glyph;
                glyphsByFaceIndex.putIfAbsent(glyphKey(faceIndex, loaded.glyphIndex), glyph);
                penX += loaded.width + PADDING;
            }

            // HarfBuzz can select contextual, ligature, and mark glyphs that
            // are not directly addressable by a Unicode codepoint. Pack every
            // remaining glyph so shaping never degrades back to nominal forms.
            for (int faceIndex = 0; faceIndex < loadedFaces.size(); faceIndex++) {
                FT_Face face = FT_Face.create(loadedFaces.get(faceIndex).faceAddress);
                int glyphCount = Math.toIntExact(face.num_glyphs());
                for (int glyphIndex = 1; glyphIndex < glyphCount; glyphIndex++) {
                    long key = glyphKey(faceIndex, glyphIndex);
                    if (glyphsByFaceIndex.containsKey(key)) continue;
                    LoadedGlyph loaded = loadGlyphIndex(face, glyphIndex, ascender, descender);
                    if (penX + loaded.width + PADDING > ATLAS_WIDTH) {
                        penX = PADDING;
                        penY += cellHeight + PADDING;
                    }
                    Glyph glyph = new Glyph(penX, penY, loaded.width, loaded.height,
                            loaded.advance, loaded.bearingX, loaded.bearingY, glyphIndex,
                            penX + 1, penY + 1 + loaded.drawOffsetY, loaded.inkWidth, loaded.inkHeight,
                            loaded.drawOffsetX, loaded.drawOffsetY);
                    glyphsByFaceIndex.put(key, glyph);
                    extraGlyphs.add(new GlyphRaster(faceIndex, glyphIndex, glyph));
                    penX += loaded.width + PADDING;
                }
            }

            width = ATLAS_WIDTH;
            height = nextPowerOfTwo(penY + cellHeight + PADDING);
            if (height > 8192) throw new IllegalStateException("Font family atlas exceeds 8192px at size " + pixelSize + ": " + sources);
            pixels = new byte[width * height];
            for (int c : codepoints) {
                Glyph glyph = glyphsByCodepoint.get(c);
                blitGlyph(FT_Face.create(loadedFaces.get(faceByCodepoint.get(c)).faceAddress), c, glyph);
            }
            for (GlyphRaster raster : extraGlyphs) {
                blitGlyphIndex(FT_Face.create(loadedFaces.get(raster.faceIndex).faceAddress),
                        raster.glyphIndex, raster.glyph);
            }
        }
        library = libraryHandle;
        faces = List.copyOf(loadedFaces);
    }

    private static int[] supportedCodepoints(List<FaceResource> faces) {
        List<Integer> values = new ArrayList<>();
        addSupported(faces, values, 0x20, 0x2af);
        addSupported(faces, values, 0x300, 0x52f);
        addSupported(faces, values, 0x590, 0xfff);
        addSupported(faces, values, 0x1e00, 0x1fff);
        addSupported(faces, values, 0x2000, 0x22ff);
        addSupported(faces, values, 0x2460, 0x24ff);
        addSupported(faces, values, 0x25a0, 0x25ff);
        addSupported(faces, values, 0xfb00, 0xfb06);
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void addSupported(List<FaceResource> faces, List<Integer> values, int first, int last) {
        for (int codepoint = first; codepoint <= last; codepoint++) {
            if (codepoint == ' ' || supportingFace(faces, codepoint) >= 0) values.add(codepoint);
        }
    }

    private static int supportingFace(List<FaceResource> faces, int codepoint) {
        for (int i = 0; i < faces.size(); i++) {
            if (FreeType.FT_Get_Char_Index(FT_Face.create(faces.get(i).faceAddress), codepoint) != 0) return i;
        }
        return codepoint == ' ' ? 0 : -1;
    }

    private static long glyphKey(int faceIndex, int glyphIndex) {
        return ((long) faceIndex << 32) | (glyphIndex & 0xffffffffL);
    }

    private static LoadedGlyph loadGlyph(FT_Face face, int codepoint, int ascender, int descender) {
        check(FreeType.FT_Load_Char(face, codepoint, FreeType.FT_LOAD_RENDER), "FT_Load_Char");
        return loadedGlyph(face.glyph(), ascender, descender);
    }

    private static LoadedGlyph loadGlyphIndex(FT_Face face, int glyphIndex, int ascender, int descender) {
        check(FreeType.FT_Load_Glyph(face, glyphIndex, FreeType.FT_LOAD_RENDER), "FT_Load_Glyph");
        return loadedGlyph(face.glyph(), ascender, descender);
    }

    private static LoadedGlyph loadedGlyph(FT_GlyphSlot slot, int ascender, int descender) {
        FT_Bitmap bitmap = slot.bitmap();
        int advance = Math.max(1, Math.round(slot.advance().x() / 64.0f));
        int inkWidth = Math.max(0, bitmap.width());
        int inkHeight = Math.max(0, bitmap.rows());
        int width = Math.max(1, inkWidth);
        int height = Math.max(1, ascender - descender);
        int drawOffsetX = slot.bitmap_left();
        int drawOffsetY = ascender - slot.bitmap_top();
        return new LoadedGlyph(width + 2, height + 2, advance, slot.bitmap_left(), drawOffsetY,
                slot.glyph_index(), 0, 0, inkWidth, inkHeight, drawOffsetX, drawOffsetY);
    }

    private void blitGlyph(FT_Face face, int codepoint, Glyph glyph) {
        check(FreeType.FT_Load_Char(face, codepoint, FreeType.FT_LOAD_RENDER), "FT_Load_Char render");
        blitLoadedGlyph(face.glyph(), glyph);
    }

    private void blitGlyphIndex(FT_Face face, int glyphIndex, Glyph glyph) {
        check(FreeType.FT_Load_Glyph(face, glyphIndex, FreeType.FT_LOAD_RENDER), "FT_Load_Glyph render");
        blitLoadedGlyph(face.glyph(), glyph);
    }

    private void blitLoadedGlyph(FT_GlyphSlot slot, Glyph glyph) {
        FT_Bitmap bitmap = slot.bitmap();
        ByteBuffer buffer = bitmap.buffer(bitmap.rows() * bitmap.pitch());
        if (buffer == null) return;
        int dstX = glyph.inkX;
        int dstY = glyph.inkY;
        for (int row = 0; row < bitmap.rows(); row++) {
            for (int col = 0; col < bitmap.width(); col++) {
                int src = row * bitmap.pitch() + col;
                int x = dstX + col;
                int y = dstY + row;
                if (x >= 0 && x < width && y >= 0 && y < height) {
                    pixels[y * width + x] = buffer.get(src);
                }
            }
        }
    }

    private static byte[] readFont(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Failed to load font asset " + path, failure);
        }
    }

    private static ByteBuffer loadFont(byte[] bytes) {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }
    private static int nextPowerOfTwo(int value) {
        int out = 1;
        while (out < value) out <<= 1;
        return out;
    }

    private static void check(int error, String operation) {
        if (error != FreeType.FT_Err_Ok) {
            throw new IllegalStateException(operation + " failed with FreeType error " + error);
        }
    }

    public record FontSource(Path source, byte[] bytes) {
        public FontSource {
            if (source == null) throw new NullPointerException("source");
            if (bytes == null) throw new NullPointerException("bytes");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] pixels() {
        return pixels;
    }

    public float lineHeight() {
        return lineHeight;
    }

    public float ascent() {
        return ascent;
    }

    public Path source() {
        return source;
    }

    public List<Path> sources() { return sources; }

    /** Stable identifier used by UI batches to select this exact face and size. */
    public String atlasId() {
        return sources + "#" + pixelSize;
    }

    public Glyph glyph(char c) {
        return glyph((int) c);
    }

    public Glyph glyph(int codepoint) {
        return glyphsByCodepoint.getOrDefault(codepoint, glyphs['?' - FIRST_CHAR]);
    }

    public boolean supports(int codepoint) {
        return glyphsByCodepoint.containsKey(codepoint);
    }

    public int nominalGlyphIndex(int codepoint) {
        int faceIndex = faceByCodepoint.getOrDefault(codepoint, 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer glyph = stack.mallocInt(1);
            if (HarfBuzz.hb_font_get_nominal_glyph(faces.get(faceIndex).hbFont, codepoint, glyph)) {
                return glyph.get(0);
            }
            return glyph('?').glyphIndex();
        }
    }

    public float textWidth(String text) {
        float total = 0.0f;
        for (PositionedGlyph glyph : layoutCached(text)) {
            total += glyph.xAdvance;
        }
        return total;
    }

    /**
     * Returns baseline-aware glyph quads for text drawn at line-box top-left
     * (x,y). Each quad includes one transparent atlas texel around the ink so
     * linear filtering cannot clip edge rows at fractional framebuffer phases.
     */
    public GlyphQuad[] layoutQuads(String text, float x, float y) {
        List<GlyphQuad> quads = new ArrayList<>();
        float penX = x;
        float penY = y;
        for (PositionedGlyph positioned : layoutCached(text)) {
            Glyph glyph = positioned.glyph();
            if (glyph.inkWidth() > 0 && glyph.inkHeight() > 0) {
                float gx = penX + positioned.xOffset();
                float gy = penY + positioned.yOffset();
                quads.add(new GlyphQuad(
                        gx - FILTER_GUARD, gy - FILTER_GUARD,
                        gx + glyph.inkWidth() + FILTER_GUARD,
                        gy + glyph.inkHeight() + FILTER_GUARD,
                        (glyph.inkX() - FILTER_GUARD) / (float) width,
                        (glyph.inkY() - FILTER_GUARD) / (float) height,
                        (glyph.inkX() + glyph.inkWidth() + FILTER_GUARD) / (float) width,
                        (glyph.inkY() + glyph.inkHeight() + FILTER_GUARD) / (float) height,
                        glyph.glyphIndex()));
            }
            penX += positioned.xAdvance();
            penY += positioned.yAdvance();
        }
        return quads.toArray(GlyphQuad[]::new);
    }

    public PositionedGlyph[] layout(String text) {
        return layoutCached(text).clone();
    }

    private PositionedGlyph[] layoutCached(String text) {
        if (text.isEmpty()) return new PositionedGlyph[0];
        PositionedGlyph[] cached = layoutCache.get(text);
        if (cached != null) return cached;
        PositionedGlyph[] shaped = shape(text);
        layoutCache.put(text, shaped);
        return shaped;
    }

    private PositionedGlyph[] shape(String text) {
        int[] codepoints = text.codePoints().toArray();
        List<PositionedGlyph> output = new ArrayList<>();
        int start = 0;
        while (start < codepoints.length) {
            int faceIndex = faceByCodepoint.getOrDefault(codepoints[start], 0);
            int end = start + 1;
            while (end < codepoints.length && faceByCodepoint.getOrDefault(codepoints[end], 0) == faceIndex) end++;
            shapeRun(codepoints, start, end, faceIndex, output);
            start = end;
        }
        return output.toArray(PositionedGlyph[]::new);
    }

    private void shapeRun(int[] codepoints, int start, int end, int faceIndex, List<PositionedGlyph> output) {
        long buffer = HarfBuzz.hb_buffer_create();
        if (buffer == 0) {
            throw new IllegalStateException("hb_buffer_create failed");
        }
        try {
            for (int i = start; i < end; i++) {
                HarfBuzz.hb_buffer_add(buffer, codepoints[i], i - start);
            }
            HarfBuzz.hb_buffer_guess_segment_properties(buffer);
            HarfBuzz.hb_shape(faces.get(faceIndex).hbFont, buffer, null);
            hb_glyph_info_t.Buffer infos = HarfBuzz.hb_buffer_get_glyph_infos(buffer);
            hb_glyph_position_t.Buffer positions = HarfBuzz.hb_buffer_get_glyph_positions(buffer);
            int count = HarfBuzz.hb_buffer_get_length(buffer);
            for (int i = 0; i < count; i++) {
                hb_glyph_info_t info = infos.get(i);
                hb_glyph_position_t position = positions.get(i);
                int shapedGlyphIndex = info.codepoint();
                int sourceIndex = start + Math.max(0, Math.min(end - start - 1, info.cluster()));
                Glyph glyph = glyphsByFaceIndex.get(glyphKey(faceIndex, shapedGlyphIndex));
                boolean shapedGlyphAvailable = glyph != null;
                if (glyph == null) glyph = glyph(codepoints[sourceIndex]);
                output.add(new PositionedGlyph(glyph,
                        glyph.drawOffsetX() + position.x_offset() / 64.0f,
                        glyph.drawOffsetY() + position.y_offset() / -64.0f,
                        shapedGlyphAvailable ? position.x_advance() / 64.0f : glyph.advance(),
                        position.y_advance() / -64.0f));
            }
        } finally {
            HarfBuzz.hb_buffer_destroy(buffer);
        }
    }

    /** Locale-aware line breaking with codepoint-safe emergency wrapping. */
    public List<String> wrap(String text, float maxWidth, int maxLines, boolean ellipsis) {
        if (text == null || text.isEmpty()) return List.of("");
        String[] paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        float width = Math.max(1, maxWidth);
        List<String> lines;
        if (!Float.isFinite(width) || width >= Float.MAX_VALUE * .5f) {
            // Non-wrapping UiText uses Float.MAX_VALUE. Preserve authored line breaks without
            // shaping every progressively longer prefix merely to prove that it fits.
            lines = new ArrayList<>(paragraphs.length);
            for (String paragraph : paragraphs) lines.add(paragraph.stripTrailing());
        } else {
            lines = new ArrayList<>();
            for (String paragraph : paragraphs) {
                wrapParagraph(paragraph, width, lines);
            }
        }
        int limit = Math.max(1, maxLines);
        if (lines.size() <= limit) return List.copyOf(lines);
        List<String> limited = new ArrayList<>(lines.subList(0, limit));
        if (ellipsis) limited.set(limit - 1, ellipsize(limited.get(limit - 1), width));
        return List.copyOf(limited);
    }

    private void wrapParagraph(String paragraph, float maxWidth, List<String> lines) {
        if (paragraph.isEmpty()) { lines.add(""); return; }
        BreakIterator breaks = BreakIterator.getLineInstance(Locale.ROOT);
        breaks.setText(paragraph);
        StringBuilder line = new StringBuilder();
        int start = breaks.first();
        for (int end = breaks.next(); end != BreakIterator.DONE; start = end, end = breaks.next()) {
            String segment = paragraph.substring(start, end);
            if (!line.isEmpty()) {
                if (textWidth(line + segment) <= maxWidth) {
                    line.append(segment);
                    continue;
                }
                flushWrappedLine(line, lines);
            }
            if (textWidth(segment) <= maxWidth) {
                line.append(segment);
            } else {
                appendEmergencyWrapped(segment, maxWidth, line, lines);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString().stripTrailing());
    }

    /** Splits an over-wide break segment with logarithmic exact-width probes at codepoint boundaries. */
    private void appendEmergencyWrapped(String value, float maxWidth, StringBuilder line, List<String> lines) {
        int offset = 0;
        while (offset < value.length()) {
            int remaining = value.codePointCount(offset, value.length());
            int low = 1;
            int high = remaining;
            int fittingCodepoints = 1; // Preserve one oversized codepoint rather than dropping it.
            while (low <= high) {
                int middle = (low + high) >>> 1;
                int end = value.offsetByCodePoints(offset, middle);
                if (textWidth(value.substring(offset, end)) <= maxWidth) {
                    fittingCodepoints = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            int end = value.offsetByCodePoints(offset, fittingCodepoints);
            line.append(value, offset, end);
            offset = end;
            if (offset < value.length()) flushWrappedLine(line, lines);
        }
    }

    private static void flushWrappedLine(StringBuilder line, List<String> lines) {
        lines.add(line.toString().stripTrailing());
        line.setLength(0);
    }

    private String ellipsize(String value, float maxWidth) {
        String suffix = supports(0x2026) ? "…" : "...";
        String result = value.stripTrailing();
        while (!result.isEmpty() && textWidth(result + suffix) > maxWidth) {
            result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        }
        return result + suffix;
    }

    private record LoadedGlyph(int width, int height, int advance, int bearingX, int bearingY, int glyphIndex,
                               int inkX, int inkY, int inkWidth, int inkHeight, int drawOffsetX, int drawOffsetY) {
    }

    private record FaceResource(Path path, ByteBuffer data, long faceAddress, long hbFont) {
    }

    private record GlyphRaster(int faceIndex, int glyphIndex, Glyph glyph) {
    }

    /** Pixel-space glyph rectangle inside the atlas plus its horizontal advance. */
    public record Glyph(int x, int y, int width, int height, int advance, int bearingX, int bearingY, int glyphIndex,
                        int inkX, int inkY, int inkWidth, int inkHeight, int drawOffsetX, int drawOffsetY) {
    }

    public record PositionedGlyph(Glyph glyph, float xOffset, float yOffset, float xAdvance, float yAdvance) {
    }

    public record GlyphQuad(float x0, float y0, float x1, float y1,
                            float u0, float v0, float u1, float v1, int glyphIndex) {
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        layoutCache.clear();
        for (FaceResource face : faces) {
            HarfBuzz.hb_font_destroy(face.hbFont);
            FreeType.FT_Done_Face(FT_Face.create(face.faceAddress));
            MemoryUtil.memFree(face.data);
        }
        FreeType.FT_Done_FreeType(library);
        closed = true;
    }
}
