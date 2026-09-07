package fr.tofuxia.ui;

import java.util.Set;

/** Immutable CSS-like style for shader-driven UI surfaces. */
public final class UiSurfaceStyle {
    private final float[] primary, secondary, border, shadow;
    private final float[] borderOverride;
    private final float borderWidth, cornerRadius, gradientX, gradientY, gradientStrength;
    private final UiSurfaceNoiseMode noiseMode;
    private final float noiseStrength;
    private final UiSurfaceNoiseAnchor noiseAnchor;
    private final float edgeDarkening, shadowOffsetX, shadowOffsetY, shadowExpansion, shadowSoftness;
    private final UiSurfaceShape shape;
    private final float extensionWidth, extensionHeight, extensionOffset, extensionOverlap, extensionRadius;
    private final UiSurfaceDebugMode debugMode;

    private UiSurfaceStyle(Builder b) {
        primary=color(b.primary); secondary=color(b.secondary);
        borderOverride=b.border==null?null:color(b.border);
        border=borderOverride==null?UiTheme.deriveBorder(primary):borderOverride.clone();
        shadow=color(b.shadow);
        borderWidth=Math.max(0,b.borderWidth); cornerRadius=Math.max(0,b.cornerRadius);
        float length=(float)Math.hypot(b.gradientX,b.gradientY);
        gradientX=length<.0001f?0:b.gradientX/length; gradientY=length<.0001f?1:b.gradientY/length;
        gradientStrength=clamp01(b.gradientStrength); noiseMode=b.noiseMode;
        noiseStrength=clamp01(b.noiseStrength); noiseAnchor=b.noiseAnchor;
        edgeDarkening=clamp01(b.edgeDarkening); shadowOffsetX=b.shadowOffsetX; shadowOffsetY=b.shadowOffsetY;
        shadowExpansion=Math.max(0,b.shadowExpansion); shadowSoftness=Math.max(0,b.shadowSoftness);
        shape=b.shape; extensionWidth=Math.max(0,b.extensionWidth); extensionHeight=Math.max(0,b.extensionHeight);
        extensionOffset=b.extensionOffset; extensionOverlap=Math.min(extensionHeight,Math.max(0,b.extensionOverlap));
        extensionRadius=Math.max(0,b.extensionRadius); debugMode=b.debugMode;
    }

    public static Builder builder() { return new Builder(); }
    public static float clampRadius(float radius,float width,float height){return Math.max(0,Math.min(radius,Math.min(width,height)*.5f));}

    public static UiSurfaceStyle panel(UiTheme theme) {
        return builder().primary(theme.panel).secondary(theme.panelRaised)
                .borderWidth(1).cornerRadius(8).gradient(0,1,.022f)
                .noise(UiSurfaceNoiseMode.SHARED_TEXTURE,.008f,UiSurfaceNoiseAnchor.ELEMENT_LOCAL)
                .edgeDarkening(.018f).shadow(theme.shadow,0,2,1.5f,4).build();
    }

    public static UiSurfaceStyle button(UiTheme theme) {
        return panel(theme).toBuilder().primary(theme.panelRaised).secondary(theme.panelHover)
                .cornerRadius(6).shadow(theme.shadow,0,1,1,2.5f).build();
    }

    public Builder toBuilder() {
        Builder builder=new Builder().primary(primary).secondary(secondary).borderWidth(borderWidth)
                .cornerRadius(cornerRadius).gradient(gradientX,gradientY,gradientStrength)
                .noise(noiseMode,noiseStrength,noiseAnchor).edgeDarkening(edgeDarkening)
                .shadow(shadow,shadowOffsetX,shadowOffsetY,shadowExpansion,shadowSoftness)
                .shape(shape).extension(extensionWidth,extensionHeight,extensionOffset,extensionOverlap,extensionRadius)
                .debug(debugMode);
        if(borderOverride!=null)builder.border(borderOverride);
        return builder;
    }

    /** Resolves interaction state without authored state textures or batch changes. */
    public UiSurfaceStyle resolve(Set<UiVisualState> states, UiSurfaceVariant variant, UiTheme theme) {
        Builder resolved=toBuilder();
        if (variant==UiSurfaceVariant.DANGER) {
            resolved.primary(UiTheme.mix(primary,theme.danger,.28f))
                    .secondary(UiTheme.mix(secondary,theme.orange,.18f));
        }
        if (states.contains(UiVisualState.HOVERED)) {
            resolved.primary(UiTheme.mix(resolved.primary,theme.ivory,.055f))
                    .secondary(UiTheme.mix(resolved.secondary,theme.warmGold,.06f));
        }
        if (states.contains(UiVisualState.PRESSED)) {
            resolved.primary(UiTheme.mix(resolved.primary,theme.canvas,.32f))
                    .secondary(UiTheme.mix(resolved.secondary,theme.canvas,.22f))
                    .shadow(resolved.shadow,0,0,.5f,1.5f);
        }
        if (states.contains(UiVisualState.SELECTED)) resolved.borderWidth(Math.max(1.5f,borderWidth));
        if (states.contains(UiVisualState.FOCUSED)) resolved.borderWidth(Math.max(1.5f,borderWidth));
        if (states.contains(UiVisualState.DISABLED)) {
            resolved.primary(alpha(resolved.primary,.45f)).secondary(alpha(resolved.secondary,.45f))
                    .shadow(alpha(resolved.shadow,.30f),
                            resolved.shadowOffsetX,resolved.shadowOffsetY,resolved.shadowExpansion,resolved.shadowSoftness)
                    .noise(noiseMode,noiseStrength*.35f,noiseAnchor);
        }
        return resolved.build();
    }

    public float[] primary(){return primary.clone();} public float[] secondary(){return secondary.clone();}
    public float[] border(){return border.clone();} public float[] shadow(){return shadow.clone();}
    public boolean hasExplicitBorder(){return borderOverride!=null;}
    public float borderWidth(){return borderWidth;} public float cornerRadius(){return cornerRadius;}
    public float gradientX(){return gradientX;} public float gradientY(){return gradientY;}
    public float gradientStrength(){return gradientStrength;} public UiSurfaceNoiseMode noiseMode(){return noiseMode;}
    public float noiseStrength(){return noiseStrength;} public UiSurfaceNoiseAnchor noiseAnchor(){return noiseAnchor;}
    public float edgeDarkening(){return edgeDarkening;} public float shadowOffsetX(){return shadowOffsetX;}
    public float shadowOffsetY(){return shadowOffsetY;} public float shadowExpansion(){return shadowExpansion;}
    public float shadowSoftness(){return shadowSoftness;} public UiSurfaceShape shape(){return shape;}
    public float extensionWidth(){return extensionWidth;} public float extensionHeight(){return extensionHeight;}
    public float extensionOffset(){return extensionOffset;} public float extensionOverlap(){return extensionOverlap;}
    public float extensionRadius(){return extensionRadius;} public UiSurfaceDebugMode debugMode(){return debugMode;}

    public static final class Builder {
        private float[] primary=UiTheme.hex("20282B"), secondary=UiTheme.hex("252E31"), border, shadow=UiTheme.rgba(0,0,0,.38f);
        private float borderWidth=1,cornerRadius=8,gradientX=0,gradientY=1,gradientStrength=.022f;
        private UiSurfaceNoiseMode noiseMode=UiSurfaceNoiseMode.SHARED_TEXTURE;
        private float noiseStrength=.008f; private UiSurfaceNoiseAnchor noiseAnchor=UiSurfaceNoiseAnchor.ELEMENT_LOCAL;
        private float edgeDarkening=.018f,shadowOffsetX=0,shadowOffsetY=2,shadowExpansion=1.5f,shadowSoftness=4;
        private UiSurfaceShape shape=UiSurfaceShape.ROUNDED_RECT;
        private float extensionWidth,extensionHeight,extensionOffset,extensionOverlap,extensionRadius=4;
        private UiSurfaceDebugMode debugMode=UiSurfaceDebugMode.NONE;
        public Builder primary(float[] v){primary=color(v);return this;} public Builder secondary(float[] v){secondary=color(v);return this;}
        public Builder border(float[] v){border=v==null?null:color(v);return this;} public Builder borderWidth(float v){borderWidth=v;return this;}
        public Builder cornerRadius(float v){cornerRadius=v;return this;}
        public Builder gradient(float x,float y,float strength){gradientX=x;gradientY=y;gradientStrength=strength;return this;}
        public Builder noise(UiSurfaceNoiseMode mode,float strength,UiSurfaceNoiseAnchor anchor){noiseMode=mode;noiseStrength=strength;noiseAnchor=anchor;return this;}
        public Builder edgeDarkening(float v){edgeDarkening=v;return this;}
        public Builder shadow(float[] color,float x,float y,float expansion,float softness){shadow=color(color);shadowOffsetX=x;shadowOffsetY=y;shadowExpansion=expansion;shadowSoftness=softness;return this;}
        public Builder shape(UiSurfaceShape v){shape=v;return this;}
        public Builder extension(float width,float height,float offset,float overlap,float radius){extensionWidth=width;extensionHeight=height;extensionOffset=offset;extensionOverlap=overlap;extensionRadius=radius;return this;}
        public Builder debug(UiSurfaceDebugMode v){debugMode=v;return this;}
        public UiSurfaceStyle build(){return new UiSurfaceStyle(this);}
    }

    private static float[] color(float[] v){if(v==null||v.length<4)throw new IllegalArgumentException("RGBA color required");return new float[]{clamp01(v[0]),clamp01(v[1]),clamp01(v[2]),clamp01(v[3])};}
    private static float[] alpha(float[] v,float factor){return new float[]{v[0],v[1],v[2],v[3]*factor};}
    private static float clamp01(float v){return Math.max(0,Math.min(1,v));}
}
