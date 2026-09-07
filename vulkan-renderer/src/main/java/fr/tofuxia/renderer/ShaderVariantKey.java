package fr.tofuxia.renderer;

import java.util.Set;
import java.util.TreeSet;

/**
 * Identity of a shader variant: the set of material features that turn into
 * preprocessor defines. Two materials with the same feature set share one
 * compiled variant.
 */
public record ShaderVariantKey(int featureMask) {
    public Set<MaterialFeature> features() {
        return MaterialFeature.fromMask(featureMask);
    }

    /** Stable, human-readable variant name, e.g. "SURFACE+TEXTURED+STANDARD_LIGHTING". */
    public String describe() {
        Set<MaterialFeature> features = features();
        StringBuilder out = new StringBuilder(features.contains(MaterialFeature.BILLBOARD) ? "BILLBOARD" : "SURFACE");
        for (MaterialFeature feature : new TreeSet<>(features)) {
            if (feature == MaterialFeature.BILLBOARD) continue;
            out.append('+').append(feature.name());
        }
        return out.toString();
    }
}
