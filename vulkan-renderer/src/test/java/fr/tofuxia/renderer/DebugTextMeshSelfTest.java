package fr.tofuxia.renderer;

import fr.tofuxia.render.FontAtlas;

import java.util.List;

/** Standard test-source smoke check for debug overlay glyph mesh generation. */
public final class DebugTextMeshSelfTest {
    public static void main(String[] args) {
        FontAtlas font = new FontAtlas(java.nio.file.Path.of("test-fixtures/assets/tofuxia/fonts/inter-variable.ttf"), 15);
        String probe = ":?gjpqyA";
        float[] vertices = DebugTextRenderer.buildVertices(font, List.of(probe), 64);
        int floatsPerVertex = 8;
        require(vertices.length == (probe.length() + 1) * 6 * floatsPerVertex,
                "unexpected debug text vertex count");

        int firstGlyph = 6 * floatsPerVertex;
        int secondGlyph = firstGlyph + 6 * floatsPerVertex;
        require(Math.abs(vertices[firstGlyph + 2] - vertices[secondGlyph + 2]) > 0.0001f,
                "colon and question mark share UVs");
        require(font.layout(probe)[0].glyph().inkWidth() > 0,
                "colon substitution has no visible ink");
        require(font.layout(probe)[0].glyph().glyphIndex() != font.layout(probe)[1].glyph().glyphIndex(),
                "colon and question mark shaped to the same glyph");
        System.out.println("[debug-text-mesh-selftest] all checks passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
