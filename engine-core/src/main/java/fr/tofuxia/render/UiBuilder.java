package fr.tofuxia.render;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Arrays;

/** Accumulates pixel-space UI geometry (solid quads and atlas-textured text). */
public final class UiBuilder {
    private final FontAtlas font;
    private final float textScale;
    private final float logicalScaleX;
    private final float logicalScaleY;
    private float[] vertexValues = new float[4096];
    private int vertexFloatCount;
    private int[] indexValues = new int[1024];
    private int indexValueCount;
    private final Deque<Affine> transforms = new ArrayDeque<>();
    private final Deque<Float> opacities = new ArrayDeque<>();
    private final Deque<Clip> clips = new ArrayDeque<>();
    private final List<UiRenderData.Batch> batches = new ArrayList<>();
    private UiRenderData.TextureKind batchKind = UiRenderData.TextureKind.FONT;
    private String batchTexture;
    private boolean batchPixelArt = true;
    private UiRenderData.Blend batchBlend = UiRenderData.Blend.TRANSPARENT;
    private int batchStart;
    private int surfaceCount;
    private int compositeSurfaceCount;
    private int clippedBatchCount;

    public UiBuilder(FontAtlas font) {
        this(font, 1.0f);
    }

    public UiBuilder(FontAtlas font, float textScale) {
        this(font, textScale, 1, 1);
    }

    public UiBuilder(FontAtlas font, float textScale, float pixelScaleX, float pixelScaleY) {
        this.font = font;
        this.textScale = Math.max(0.5f, Math.min(2.5f, textScale));
        this.logicalScaleX = Math.max(.01f, pixelScaleX);
        this.logicalScaleY = Math.max(.01f, pixelScaleY);
        transforms.push(new Affine(logicalScaleX, 0, 0, logicalScaleY, 0, 0));
        opacities.push(1.0f);
        clips.push(Clip.INFINITE);
    }

    public void quad(float x0, float y0, float x1, float y1, float[] c) {
        useBatch(UiRenderData.TextureKind.FONT, null, true, UiRenderData.Blend.TRANSPARENT);
        emitQuad(Vertex.regular(x0, y0, -1, -1, c), Vertex.regular(x1, y0, -1, -1, c),
                Vertex.regular(x1, y1, -1, -1, c), Vertex.regular(x0, y1, -1, -1, c));
    }

    /** Emits a compact analytic surface without introducing a texture or style batch boundary. */
    public void surface(float x, float y, float width, float height, UiSurfaceData data) {
        if (width <= 0 || height <= 0 || data == null) return;
        useBatch(UiRenderData.TextureKind.FONT, null, true, UiRenderData.Blend.TRANSPARENT);
        float shadowLeft = data.shadowExpansion() + data.shadowSoftness() + Math.max(0, -data.shadowOffsetX());
        float shadowTop = data.shadowExpansion() + data.shadowSoftness() + Math.max(0, -data.shadowOffsetY());
        float shadowRight = data.shadowExpansion() + data.shadowSoftness() + Math.max(0, data.shadowOffsetX());
        float shadowBottom = data.shadowExpansion() + data.shadowSoftness() + Math.max(0, data.shadowOffsetY());
        float extensionTop = data.shape().composite() ? Math.max(0, data.extensionHeight() - data.extensionOverlap()) : 0;
        float extensionLeft = data.shape().composite()
                ? Math.max(0, data.extensionWidth()*.5f-width*.5f-data.extensionOffset()) : 0;
        float extensionRight = data.shape().composite()
                ? Math.max(0, data.extensionWidth()*.5f-width*.5f+data.extensionOffset()) : 0;
        float left = extensionLeft + shadowLeft;
        float top = extensionTop + shadowTop;
        float right = extensionRight + shadowRight;
        float bottom = shadowBottom;
        emitQuad(Vertex.surface(x-left, y-top, -left, -top, width, height, data),
                Vertex.surface(x+width+right, y-top, width+right, -top, width, height, data),
                Vertex.surface(x+width+right, y+height+bottom, width+right, height+bottom, width, height, data),
                Vertex.surface(x-left, y+height+bottom, -left, height+bottom, width, height, data));
        surfaceCount++;
        if (data.shape().composite()) compositeSurfaceCount++;
    }

    public void border(float x, float y, float w, float h, float[] c) {
        quad(x, y, x + w, y + 1, c);
        quad(x, y + h - 1, x + w, y + h, c);
        quad(x, y, x + 1, y + h, c);
        quad(x + w - 1, y, x + w, y + h, c);
    }

    public void shadow(float x, float y, float w, float h, float spread, float[] c) {
        quad(x + spread, y + h, x + w + spread, y + h + spread, c);
        quad(x + w, y + spread, x + w + spread, y + h + spread, c);
    }

    /** Draws text with (x, y) as the top-left corner of the line box. */
    public void text(float x, float y, String string, float[] color) {
        text(font, x, y, string, color);
    }

    /** Draws text with an explicitly registered alternate font atlas. */
    public void text(FontAtlas selectedFont, float x, float y, String string, float[] color) {
        String fontKey = selectedFont == font ? null : selectedFont.atlasId();
        useBatch(UiRenderData.TextureKind.FONT, fontKey, true, UiRenderData.Blend.TRANSPARENT);
        for (FontAtlas.GlyphQuad quad : selectedFont.layoutQuads(string, x, y)) {
            float x0 = x + (quad.x0() - x) * textScale;
            float y0 = y + (quad.y0() - y) * textScale;
            float x1 = x + (quad.x1() - x) * textScale;
            float y1 = y + (quad.y1() - y) * textScale;
            emitQuad(Vertex.regular(x0, y0, quad.u0(), quad.v0(), color),
                    Vertex.regular(x1, y0, quad.u1(), quad.v0(), color),
                    Vertex.regular(x1, y1, quad.u1(), quad.v1(), color),
                    Vertex.regular(x0, y1, quad.u0(), quad.v1(), color));
        }
    }

    /** Emits one RGBA texture-backed quad, preserving ordering with surrounding geometry. */
    public void sprite(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                       String texture, boolean pixelArt, float[] tint) {
        useBatch(UiRenderData.TextureKind.RGBA, texture, pixelArt, UiRenderData.Blend.TRANSPARENT);
        emitQuad(Vertex.regular(x0, y0, u0, v0, tint), Vertex.regular(x1, y0, u1, v0, tint),
                Vertex.regular(x1, y1, u1, v1, tint), Vertex.regular(x0, y1, u0, v1, tint));
    }

    /** Emits an arbitrary texture-backed quadrilateral, suitable for isometric UI icons. */
    public void texturedQuad(String texture, boolean pixelArt, float[] tint,
                             float x0, float y0, float u0, float v0,
                             float x1, float y1, float u1, float v1,
                             float x2, float y2, float u2, float v2,
                             float x3, float y3, float u3, float v3) {
        useBatch(UiRenderData.TextureKind.RGBA, texture, pixelArt, UiRenderData.Blend.TRANSPARENT);
        emitQuad(Vertex.regular(x0, y0, u0, v0, tint), Vertex.regular(x1, y1, u1, v1, tint),
                Vertex.regular(x2, y2, u2, v2, tint), Vertex.regular(x3, y3, u3, v3, tint));
    }

    public void pushTransform(float translateX, float translateY, float scaleX, float scaleY,
                              float rotationRadians, float pivotX, float pivotY) {
        float cos = (float) Math.cos(rotationRadians);
        float sin = (float) Math.sin(rotationRadians);
        Affine local = new Affine(cos * scaleX, sin * scaleX, -sin * scaleY, cos * scaleY,
                translateX + pivotX - cos * scaleX * pivotX + sin * scaleY * pivotY,
                translateY + pivotY - sin * scaleX * pivotX - cos * scaleY * pivotY);
        transforms.push(transforms.peek().multiply(local));
    }

    public void popTransform() {
        if (transforms.size() <= 1) throw new IllegalStateException("Cannot pop the root UI transform");
        transforms.pop();
    }

    public void pushOpacity(float opacity) {
        opacities.push(opacities.peek() * Math.max(0, Math.min(1, opacity)));
    }

    public void popOpacity() {
        if (opacities.size() <= 1) throw new IllegalStateException("Cannot pop the root UI opacity");
        opacities.pop();
    }

    /** Pushes a local-space clip rectangle, transformed into the current screen-space AABB. */
    public void pushClip(float x, float y, float width, float height) {
        finishBatch();
        Affine m = transforms.peek();
        Point a = m.apply(x, y);
        Point b = m.apply(x + width, y);
        Point c = m.apply(x + width, y + height);
        Point d = m.apply(x, y + height);
        Clip transformed = new Clip(
                Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x)),
                Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y)),
                Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x)),
                Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y)));
        clips.push(clips.peek().intersect(transformed));
        batchStart = indexCount();
    }

    public void popClip() {
        if (clips.size() <= 1) throw new IllegalStateException("Cannot pop the root UI clip");
        finishBatch();
        clips.pop();
        batchStart = indexCount();
    }

    public int vertexCount() {
        return vertexFloatCount / UiRenderData.FLOATS_PER_VERTEX;
    }

    public int indexCount() {
        return indexValueCount;
    }

    public void v(float x, float y, float u, float uv, float[] c) {
        useBatch(UiRenderData.TextureKind.FONT, null, true, UiRenderData.Blend.TRANSPARENT);
        Point p = transforms.peek().apply(x, y);
        rawVertex(Vertex.regular(p.x, p.y, u, uv, c).withOpacity(opacities.peek()));
    }

    private void rawVertex(Vertex vertex) {
        int needed=vertexFloatCount+UiRenderData.FLOATS_PER_VERTEX;
        if(needed>vertexValues.length)vertexValues=Arrays.copyOf(vertexValues,nextCapacity(vertexValues.length,needed));
        System.arraycopy(vertex.values,0,vertexValues,vertexFloatCount,UiRenderData.FLOATS_PER_VERTEX);
        vertexFloatCount=needed;
    }

    public void tri(int a, int b, int c) {
        int needed=indexValueCount+3;
        if(needed>indexValues.length)indexValues=Arrays.copyOf(indexValues,nextCapacity(indexValues.length,needed));
        indexValues[indexValueCount++]=a; indexValues[indexValueCount++]=b; indexValues[indexValueCount++]=c;
    }

    public UiRenderData toRenderData() {
        finishBatch();
        float[] v = Arrays.copyOf(vertexValues,vertexFloatCount);
        int[] i = Arrays.copyOf(indexValues,indexValueCount);
        return new UiRenderData(v, i, batches, logicalScaleX, logicalScaleY,
                surfaceCount, compositeSurfaceCount, clippedBatchCount);
    }

    private static int nextCapacity(int current,int needed){int value=Math.max(16,current);while(value<needed)value=Math.multiplyExact(value,2);return value;}

    private void emitQuad(Vertex a, Vertex b, Vertex c, Vertex d) {
        Affine transform = transforms.peek();
        List<Vertex> polygon = new ArrayList<>(List.of(
                a.transform(transform).withOpacity(opacities.peek()),
                b.transform(transform).withOpacity(opacities.peek()),
                c.transform(transform).withOpacity(opacities.peek()),
                d.transform(transform).withOpacity(opacities.peek())));
        Clip clip = clips.peek();
        polygon = clip(polygon, 0, clip.x0);
        polygon = clip(polygon, 1, clip.x1);
        polygon = clip(polygon, 2, clip.y0);
        polygon = clip(polygon, 3, clip.y1);
        if (polygon.size() < 3) return;
        int base = vertexCount();
        for (Vertex vertex : polygon) rawVertex(vertex);
        for (int i = 1; i + 1 < polygon.size(); i++) tri(base, base + i, base + i + 1);
    }

    private List<Vertex> clip(List<Vertex> input, int edge, float boundary) {
        if (input.isEmpty() || !Float.isFinite(boundary)) return input;
        List<Vertex> output = new ArrayList<>();
        Vertex previous = input.getLast();
        boolean previousInside = inside(previous, edge, boundary);
        for (Vertex current : input) {
            boolean currentInside = inside(current, edge, boundary);
            if (currentInside != previousInside) output.add(intersection(previous, current, edge, boundary));
            if (currentInside) output.add(current);
            previous = current;
            previousInside = currentInside;
        }
        return output;
    }

    private boolean inside(Vertex vertex, int edge, float boundary) {
        return switch (edge) {
            case 0 -> vertex.x() >= boundary;
            case 1 -> vertex.x() <= boundary;
            case 2 -> vertex.y() >= boundary;
            default -> vertex.y() <= boundary;
        };
    }

    private Vertex intersection(Vertex a, Vertex b, int edge, float boundary) {
        float denominator = edge < 2 ? b.x() - a.x() : b.y() - a.y();
        float numerator = boundary - (edge < 2 ? a.x() : a.y());
        float t = Math.abs(denominator) < 0.000001f ? 0 : numerator / denominator;
        return a.interpolate(b, Math.max(0, Math.min(1, t)));
    }

    private void useBatch(UiRenderData.TextureKind kind, String texture, boolean pixelArt, UiRenderData.Blend blend) {
        if (kind == batchKind && java.util.Objects.equals(texture, batchTexture)
                && pixelArt == batchPixelArt && blend == batchBlend) return;
        finishBatch();
        batchKind = kind;
        batchTexture = texture;
        batchPixelArt = pixelArt;
        batchBlend = blend;
        batchStart = indexCount();
    }

    private void finishBatch() {
        int count = indexCount() - batchStart;
        if (count <= 0) return;
        Clip clip = clips.peek();
        batches.add(new UiRenderData.Batch(batchStart, count, batchKind, batchTexture, batchPixelArt, batchBlend,
                Float.isFinite(clip.x0) ? clip.x0 : 0,
                Float.isFinite(clip.y0) ? clip.y0 : 0,
                Float.isFinite(clip.x1) ? Math.max(0, clip.x1 - clip.x0) : Float.POSITIVE_INFINITY,
                Float.isFinite(clip.y1) ? Math.max(0, clip.y1 - clip.y0) : Float.POSITIVE_INFINITY));
        if (Float.isFinite(clip.x0) || Float.isFinite(clip.y0) || Float.isFinite(clip.x1) || Float.isFinite(clip.y1)) {
            clippedBatchCount++;
        }
        batchStart = indexCount();
    }

    private record Point(float x, float y) {}

    private record Affine(float a, float b, float c, float d, float tx, float ty) {
        static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);
        Point apply(float x, float y) { return new Point(a * x + c * y + tx, b * x + d * y + ty); }
        Affine multiply(Affine o) {
            return new Affine(a*o.a+c*o.b, b*o.a+d*o.b, a*o.c+c*o.d, b*o.c+d*o.d,
                    a*o.tx+c*o.ty+tx, b*o.tx+d*o.ty+ty);
        }
    }

    private record Clip(float x0, float y0, float x1, float y1) {
        static final Clip INFINITE = new Clip(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        Clip intersect(Clip o) {
            return new Clip(Math.max(x0, o.x0), Math.max(y0, o.y0), Math.min(x1, o.x1), Math.min(y1, o.y1));
        }
    }

    private static final class Vertex {
        private final float[] values;

        private Vertex(float[] values) { this.values = values; }

        static Vertex regular(float x, float y, float u, float v, float[] color) {
            float[] values = new float[UiRenderData.FLOATS_PER_VERTEX];
            values[0]=x; values[1]=y; values[2]=u; values[3]=v;
            putColor(values, 4, color);
            return new Vertex(values);
        }

        static Vertex surface(float x, float y, float localX, float localY,
                              float width, float height, UiSurfaceData data) {
            float[] values = new float[UiRenderData.FLOATS_PER_VERTEX];
            values[0]=x; values[1]=y; values[2]=-1; values[3]=-1;
            putColor(values, 4, data.primaryColor());
            values[8]=localX; values[9]=localY; values[10]=width; values[11]=height;
            putColor(values, 12, data.secondaryColor());
            putColor(values, 16, data.borderColor());
            values[20]=data.shape().shaderId(); values[21]=data.cornerRadius();
            values[22]=data.borderWidth(); values[23]=data.gradientStrength();
            values[24]=data.gradientDirectionX(); values[25]=data.gradientDirectionY();
            values[26]=data.noiseMode().shaderId(); values[27]=data.noiseStrength();
            values[28]=data.noiseAnchor().shaderId(); values[29]=data.edgeDarkening();
            values[30]=data.debugMode().shaderId(); values[31]=1;
            putColor(values, 32, data.shadowColor());
            values[36]=data.shadowOffsetX(); values[37]=data.shadowOffsetY();
            values[38]=data.shadowExpansion(); values[39]=data.shadowSoftness();
            values[40]=data.extensionWidth(); values[41]=data.extensionHeight();
            values[42]=data.extensionOffset(); values[43]=data.extensionOverlap();
            values[44]=data.extensionRadius(); values[47]=1;
            return new Vertex(values);
        }

        float x() { return values[0]; }
        float y() { return values[1]; }

        Vertex transform(Affine transform) {
            float[] copy = values.clone();
            Point point = transform.apply(values[0], values[1]);
            copy[0] = point.x; copy[1] = point.y;
            return new Vertex(copy);
        }

        Vertex withOpacity(float opacity) {
            if (opacity == 1) return this;
            float[] copy = values.clone();
            copy[7] *= opacity;
            copy[15] *= opacity;
            copy[19] *= opacity;
            copy[35] *= opacity;
            return new Vertex(copy);
        }

        Vertex interpolate(Vertex other, float t) {
            float[] mixed = new float[UiRenderData.FLOATS_PER_VERTEX];
            for (int i = 0; i < mixed.length; i++) mixed[i] = values[i] + (other.values[i]-values[i])*t;
            return new Vertex(mixed);
        }

        private static void putColor(float[] target, int offset, float[] color) {
            target[offset]=color[0]; target[offset+1]=color[1]; target[offset+2]=color[2];
            target[offset+3]=color.length > 3 ? color[3] : 1;
        }
    }
}
