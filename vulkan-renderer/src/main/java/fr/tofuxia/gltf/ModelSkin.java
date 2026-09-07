package fr.tofuxia.gltf;

import org.joml.Matrix4f;

/**
 * glTF skin: which nodes act as joints and how mesh space maps into each
 * joint's space at bind time.
 */
public record ModelSkin(
        String name,
        int[] jointNodes,
        Matrix4f[] inverseBindMatrices,
        int skeletonRoot) {

    public int jointCount() {
        return jointNodes.length;
    }
}
