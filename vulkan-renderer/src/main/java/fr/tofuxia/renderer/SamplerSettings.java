package fr.tofuxia.renderer;

/** Texture sampling behavior a material asks for. */
public record SamplerSettings(Filter filter, Wrap wrap) {
    public enum Filter { NEAREST, LINEAR }

    public enum Wrap { REPEAT, CLAMP }

    public static final SamplerSettings LINEAR_REPEAT = new SamplerSettings(Filter.LINEAR, Wrap.REPEAT);
    public static final SamplerSettings PIXEL_ART = new SamplerSettings(Filter.NEAREST, Wrap.REPEAT);
    public static final SamplerSettings PIXEL_CLAMP = new SamplerSettings(Filter.NEAREST, Wrap.CLAMP);
    public static final SamplerSettings UI_CLAMP = new SamplerSettings(Filter.LINEAR, Wrap.CLAMP);

    public static SamplerSettings parse(String filter, String wrap) {
        Filter f = filter != null && filter.equalsIgnoreCase("nearest") ? Filter.NEAREST : Filter.LINEAR;
        Wrap w = wrap != null && (wrap.equalsIgnoreCase("clamp") || wrap.equalsIgnoreCase("clamp_to_edge"))
                ? Wrap.CLAMP : Wrap.REPEAT;
        return new SamplerSettings(f, w);
    }
}
