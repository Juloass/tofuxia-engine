package fr.tofuxia.ui;

public final class UiTheme {
    private static final float[] BORDER_IVORY = hex("F7F6DE");
    private static final float[] BORDER_CHARCOAL = hex("20282B");

    public final float[] charcoal = hex("20282B");
    public final float[] orange = hex("FF9C3A");
    public final float[] warmGold = hex("FFD078");
    public final float[] ivory = hex("F7F6DE");
    public final float[] lightGreen = hex("B5E789");
    public final float[] vividGreen = hex("65D46B");
    public final float[] yellow = hex("F7E154");
    public final float[] canvas = rgba(charcoal[0]*.55f,charcoal[1]*.55f,charcoal[2]*.55f,.96f);
    public final float[] panel = rgba(charcoal[0],charcoal[1],charcoal[2],.96f);
    public final float[] panelRaised = mix(panel,ivory,.045f);
    public final float[] panelHover = mix(panel,ivory,.085f);
    public final float[] panelPressed = mix(panel,canvas,.35f);
    public final float[] border = mix(charcoal,ivory,.20f);
    public final float[] borderDim = mix(charcoal,ivory,.10f);
    public final float[] text = ivory.clone();
    public final float[] textDim = mix(charcoal,ivory,.62f);
    public final float[] accent = orange.clone();
    /** Compatibility name retained for callers; canonical accent is warm gold. */
    public final float[] accentBlue = warmGold.clone();
    public final float[] success = vividGreen.clone();
    public final float[] titleText = ivory.clone();
    public final float[] warning = yellow.clone();
    public final float[] danger = rgba(.92f,.28f,.18f,1);
    public final float[] shadow = rgba(0.000f, 0.000f, 0.000f, 0.42f);
    public final float pad = 10.0f;
    public final float gap = 6.0f;
    public final float row = 24.0f;

    public static float[] rgba(float r, float g, float b, float a) {
        return new float[]{r, g, b, a};
    }

    public void setAccent(float r, float g, float b) {
        accent[0] = r;
        accent[1] = g;
        accent[2] = b;
        accent[3] = 1.0f;
        success[0] = r;
        success[1] = g;
        success[2] = b;
        success[3] = 1.0f;
    }

    public void setWarmAccent() { setAccent(orange[0],orange[1],orange[2]); }

    /** @deprecated compatibility alias; new Tofuxia themes use the warm palette. */
    @Deprecated
    public void setTealAccent() {
        setWarmAccent();
    }

    /** @deprecated compatibility alias; new Tofuxia themes use the warm palette. */
    @Deprecated
    public void setCyanAccent() {
        setWarmAccent();
    }

    public static float[] mix(float[] a, float[] b, float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        return new float[]{
                a[0] + (b[0] - a[0]) * clamped,
                a[1] + (b[1] - a[1]) * clamped,
                a[2] + (b[2] - a[2]) * clamped,
                a.length > 3 ? a[3] + ((b.length > 3 ? b[3] : 1.0f) - a[3]) * clamped : 1.0f
        };
    }

    public static float[] alpha(float[] c, float a) {
        return new float[]{c[0], c[1], c[2], a};
    }

    /**
     * Derives a material-relative border from a surface fill. Color decisions
     * are made in perceptual sRGB while the actual mix remains linear-light.
     */
    public static float[] deriveBorder(float[] fill) {
        if (fill == null || fill.length < 4) throw new IllegalArgumentException("RGBA fill color required");
        float luminance = .2126f * fill[0] + .7152f * fill[1] + .0722f * fill[2];
        float r = linearToSrgb(fill[0]);
        float g = linearToSrgb(fill[1]);
        float b = linearToSrgb(fill[2]);
        float maximum = Math.max(r, Math.max(g, b));
        float minimum = Math.min(r, Math.min(g, b));
        float saturation = maximum <= .0001f ? 0 : (maximum - minimum) / maximum;

        float[] target;
        float amount;
        if (luminance >= .72f) {
            target = BORDER_CHARCOAL;
            amount = .18f;
        } else if (luminance < .20f || saturation < .35f) {
            target = BORDER_IVORY;
            amount = .15f;
        } else {
            target = BORDER_IVORY;
            amount = .20f;
        }
        float[] border = mix(fill, target, amount);
        border[3] = Math.max(0, Math.min(1, fill[3]));
        return border;
    }

    public static float[] hex(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Expected RRGGBB");
        return rgba(srgbToLinear(Integer.parseInt(value.substring(0,2),16)/255f),
                srgbToLinear(Integer.parseInt(value.substring(2,4),16)/255f),
                srgbToLinear(Integer.parseInt(value.substring(4,6),16)/255f),1);
    }

    /** Decodes an authored sRGB component for linear-light shader arithmetic and an sRGB swapchain. */
    private static float srgbToLinear(float value) {
        return value <= .04045f ? value / 12.92f
                : (float)Math.pow((value + .055f) / 1.055f, 2.4f);
    }

    private static float linearToSrgb(float value) {
        float clamped = Math.max(0, Math.min(1, value));
        return clamped <= .0031308f ? clamped * 12.92f
                : 1.055f * (float)Math.pow(clamped, 1 / 2.4f) - .055f;
    }
}
