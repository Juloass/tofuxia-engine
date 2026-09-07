package fr.tofuxia.gltf;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One node of the imported hierarchy with its default (bind-pose) local
 * transform, already decomposed to TRS so animation channels can override
 * individual components. Immutable shared data; per-instance pose state
 * lives in {@link SkeletonPose}.
 */
public record ModelNode(
        String name,
        int meshIndex,
        int skinIndex,
        int[] children,
        Vector3f translation,
        Quaternionf rotation,
        Vector3f scale) {

    public boolean hasMesh() {
        return meshIndex >= 0;
    }

    public boolean hasSkin() {
        return skinIndex >= 0;
    }
}
