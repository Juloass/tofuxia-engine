package fr.tofuxia.renderer;

import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.EnumSet;
import java.util.Set;

/**
 * Data-driven surface description. A material says what the surface needs
 * (texture, lighting model, blending, emissive...) and the renderer resolves
 * it into a shader variant + GPU pipeline. Materials are immutable once
 * built; the {@link MaterialSystem} owns loading, caching and validation.
 */
public final class Material {
    private final String name;
    private final MaterialDomain domain;
    private final BlendMode blendMode;
    private final CullMode cullMode;
    private final ShadingModel shadingModel;
    private final Set<MaterialFeature> features;
    private final String baseColorTexture;
    private final SamplerSettings sampler;
    private final Vector4f baseColorFactor;
    private final Vector3f emissiveColor;
    private final float emissiveStrength;
    private final float alphaCutoff;
    private final float outlineWidthPixels;
    private final boolean depthTest;
    private final boolean depthWrite;
    private final boolean castShadows;
    private final boolean receiveShadows;

    private Material(Builder b) {
        this.name = b.name;
        this.domain = b.domain;
        this.blendMode = b.blendMode;
        this.cullMode = b.cullMode;
        this.shadingModel = b.shadingModel;
        this.features = EnumSet.copyOf(b.features);
        this.baseColorTexture = b.baseColorTexture;
        this.sampler = b.sampler;
        this.baseColorFactor = new Vector4f(b.baseColorFactor);
        this.emissiveColor = new Vector3f(b.emissiveColor);
        this.emissiveStrength = b.emissiveStrength;
        this.alphaCutoff = b.alphaCutoff;
        this.outlineWidthPixels = b.outlineWidthPixels;
        this.depthTest = b.depthTest;
        this.depthWrite = b.depthWrite;
        this.castShadows = b.castShadows;
        this.receiveShadows = b.receiveShadows;
    }

    public String name() {
        return name;
    }

    public MaterialDomain domain() {
        return domain;
    }

    public BlendMode blendMode() {
        return blendMode;
    }

    public CullMode cullMode() {
        return cullMode;
    }

    public ShadingModel shadingModel() {
        return shadingModel;
    }

    public Set<MaterialFeature> features() {
        return EnumSet.copyOf(features);
    }

    public boolean has(MaterialFeature feature) {
        return features.contains(feature);
    }

    public int featureMask() {
        return MaterialFeature.mask(features);
    }

    public ShaderVariantKey variantKey() {
        return new ShaderVariantKey(featureMask());
    }

    /** Asset path of the base color texture, or null when untextured. */
    public String baseColorTexture() {
        return baseColorTexture;
    }

    public SamplerSettings sampler() {
        return sampler;
    }

    public Vector4f baseColorFactor() {
        return baseColorFactor;
    }

    public Vector3f emissiveColor() {
        return emissiveColor;
    }

    public float emissiveStrength() {
        return emissiveStrength;
    }

    public float alphaCutoff() {
        return alphaCutoff;
    }

    public float outlineWidthPixels() { return outlineWidthPixels; }

    public boolean depthTest() {
        return depthTest;
    }

    public boolean depthWrite() {
        return depthWrite;
    }

    public boolean castShadows() { return castShadows; }
    public boolean receiveShadows() { return receiveShadows; }

    /** Render pass this material's draws belong to. */
    public PassId pass() {
        if (domain == MaterialDomain.BILLBOARD) return PassId.BILLBOARDS;
        if (domain == MaterialDomain.OUTLINE) return PassId.ENTITY_OUTLINE;
        return switch (blendMode) {
            case OPAQUE -> PassId.OPAQUE;
            case CUTOUT -> PassId.ALPHA_CUTOUT;
            case TRANSPARENT, ADDITIVE -> PassId.TRANSPARENT;
        };
    }

    @Override
    public String toString() {
        return "Material[" + name + " " + domain + "/" + blendMode + " " + features + "]";
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public Builder toBuilder() {
        Builder b = new Builder(name);
        b.domain = domain;
        b.blendMode = blendMode;
        b.cullMode = cullMode;
        b.shadingModel = shadingModel;
        b.features = EnumSet.copyOf(features);
        b.baseColorTexture = baseColorTexture;
        b.sampler = sampler;
        b.baseColorFactor = new Vector4f(baseColorFactor);
        b.emissiveColor = new Vector3f(emissiveColor);
        b.emissiveStrength = emissiveStrength;
        b.alphaCutoff = alphaCutoff;
        b.outlineWidthPixels = outlineWidthPixels;
        b.castShadows = castShadows;
        b.receiveShadows = receiveShadows;
        return b;
    }

    public static final class Builder {
        private final String name;
        private MaterialDomain domain = MaterialDomain.SURFACE;
        private BlendMode blendMode = BlendMode.OPAQUE;
        private CullMode cullMode = CullMode.BACK;
        private ShadingModel shadingModel = ShadingModel.STANDARD_LIT;
        private Set<MaterialFeature> features = EnumSet.noneOf(MaterialFeature.class);
        private String baseColorTexture;
        private SamplerSettings sampler = SamplerSettings.LINEAR_REPEAT;
        private Vector4f baseColorFactor = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
        private Vector3f emissiveColor = new Vector3f(1.0f, 1.0f, 1.0f);
        private float emissiveStrength;
        private float alphaCutoff = 0.5f;
        private float outlineWidthPixels = 1.0f;
        private boolean depthTest = true;
        private boolean depthWrite = true;
        private boolean castShadows = true;
        private boolean receiveShadows = true;

        private Builder(String name) {
            this.name = name;
        }

        public Builder domain(MaterialDomain value) {
            domain = value;
            return this;
        }

        public Builder blendMode(BlendMode value) {
            blendMode = value;
            return this;
        }

        public Builder cullMode(CullMode value) {
            cullMode = value;
            return this;
        }

        public Builder shadingModel(ShadingModel value) {
            shadingModel = value;
            return this;
        }

        public Builder feature(MaterialFeature value) {
            features.add(value);
            return this;
        }

        public Builder baseColorTexture(String assetPath) {
            baseColorTexture = assetPath;
            return this;
        }

        public Builder sampler(SamplerSettings value) {
            sampler = value;
            return this;
        }

        public Builder baseColorFactor(float r, float g, float b, float a) {
            baseColorFactor = new Vector4f(r, g, b, a);
            return this;
        }

        public Builder emissive(float r, float g, float b, float strength) {
            emissiveColor = new Vector3f(r, g, b);
            emissiveStrength = strength;
            return this;
        }

        public Builder alphaCutoff(float value) {
            alphaCutoff = value;
            return this;
        }

        public Builder outlineWidthPixels(float value) {
            outlineWidthPixels = Math.max(1.0f, Math.min(2.0f, value));
            return this;
        }

        public Builder castShadows(boolean value) { castShadows = value; return this; }
        public Builder receiveShadows(boolean value) { receiveShadows = value; return this; }

        /**
         * Applies the cross-field rules that make a material self-consistent
         * (shading model -> lighting features, blend mode -> cutout feature,
         * transparent -> no depth write...). Warnings go to the given sink so
         * the material system can surface them in logs/debug UI.
         */
        public Material resolve(java.util.function.Consumer<String> warnings) {
            switch (shadingModel) {
                case UNLIT -> {
                    if (features.contains(MaterialFeature.STANDARD_LIGHTING)) {
                        warnings.accept(name + ": STANDARD_LIGHTING ignored because shadingModel is unlit");
                        features.remove(MaterialFeature.STANDARD_LIGHTING);
                    }
                    features.add(MaterialFeature.UNLIT);
                }
                case STANDARD_LIT -> {
                    if (features.contains(MaterialFeature.UNLIT)) {
                        warnings.accept(name + ": UNLIT feature overrides shadingModel standard_lit");
                        shadingModel = ShadingModel.UNLIT;
                        features.remove(MaterialFeature.STANDARD_LIGHTING);
                    } else {
                        features.add(MaterialFeature.STANDARD_LIGHTING);
                        features.add(MaterialFeature.FOG);
                    }
                }
            }
            if (baseColorTexture != null && !baseColorTexture.isBlank()) {
                features.add(MaterialFeature.TEXTURED);
            } else if (features.contains(MaterialFeature.TEXTURED)) {
                warnings.accept(name + ": TEXTURED requested but no baseColor texture set; feature dropped");
                features.remove(MaterialFeature.TEXTURED);
            }
            if (blendMode == BlendMode.CUTOUT) {
                features.add(MaterialFeature.ALPHA_CUTOUT);
            } else if (features.contains(MaterialFeature.ALPHA_CUTOUT)) {
                warnings.accept(name + ": ALPHA_CUTOUT feature implies cutout blend mode; blendMode adjusted");
                blendMode = BlendMode.CUTOUT;
            }
            if (emissiveStrength > 0.0f) {
                features.add(MaterialFeature.EMISSIVE);
            } else if (features.contains(MaterialFeature.EMISSIVE)) {
                emissiveStrength = 1.0f;
            }
            if (domain == MaterialDomain.BILLBOARD) {
                features.add(MaterialFeature.BILLBOARD);
            } else if (domain == MaterialDomain.OUTLINE) {
                features.add(MaterialFeature.OUTLINE);
                depthWrite = false;
                castShadows = false;
                receiveShadows = false;
            } else if (features.contains(MaterialFeature.BILLBOARD)) {
                domain = MaterialDomain.BILLBOARD;
            }
            if (domain != MaterialDomain.OUTLINE) depthWrite = !blendMode.blendsWithBackground();
            depthTest = true;
            if (blendMode.blendsWithBackground() || domain == MaterialDomain.BILLBOARD) castShadows = false;
            if (shadingModel == ShadingModel.UNLIT) receiveShadows = false;
            if (receiveShadows) features.add(MaterialFeature.RECEIVE_SHADOWS);
            else features.remove(MaterialFeature.RECEIVE_SHADOWS);
            return new Material(this);
        }
    }
}
