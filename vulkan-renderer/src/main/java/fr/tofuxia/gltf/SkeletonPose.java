package fr.tofuxia.gltf;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Per-instance node pose: local TRS for every node (animation writes here)
 * plus derived model-space world matrices and skin joint matrices. Shared
 * asset data is never mutated.
 */
public final class SkeletonPose {
    private final Vector3f[] translations;
    private final Quaternionf[] rotations;
    private final Vector3f[] scales;
    private final Matrix4f[] worldMatrices;
    private final Matrix4f inverseMeshGlobal = new Matrix4f();

    public SkeletonPose(int nodeCount) {
        translations = new Vector3f[nodeCount];
        rotations = new Quaternionf[nodeCount];
        scales = new Vector3f[nodeCount];
        worldMatrices = new Matrix4f[nodeCount];
        for (int i = 0; i < nodeCount; i++) {
            translations[i] = new Vector3f();
            rotations[i] = new Quaternionf();
            scales[i] = new Vector3f(1, 1, 1);
            worldMatrices[i] = new Matrix4f();
        }
    }

    /** Restores every node to the asset's default (bind-pose) local transform. */
    public void reset(List<ModelNode> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            ModelNode node = nodes.get(i);
            translations[i].set(node.translation());
            rotations[i].set(node.rotation());
            scales[i].set(node.scale());
        }
    }

    public Vector3f translation(int node) {
        return translations[node];
    }

    public Quaternionf rotation(int node) {
        return rotations[node];
    }

    public Vector3f scale(int node) {
        return scales[node];
    }

    /** Recomputes model-space world matrices from the local TRS values. */
    public void computeWorldMatrices(List<ModelNode> nodes, int[] roots) {
        for (int root : roots) {
            computeRecursive(nodes, root, null);
        }
    }

    private void computeRecursive(List<ModelNode> nodes, int index, Matrix4f parent) {
        Matrix4f world = worldMatrices[index];
        world.identity()
                .translate(translations[index])
                .rotate(rotations[index])
                .scale(scales[index]);
        if (parent != null) {
            parent.mul(world, world);
        }
        for (int child : nodes.get(index).children()) {
            computeRecursive(nodes, child, world);
        }
    }

    /** Model-space world matrix of a node (valid after computeWorldMatrices). */
    public Matrix4f worldMatrix(int node) {
        return worldMatrices[node];
    }

    /**
     * Fills {@code out} with skin matrices per the glTF spec:
     * inverse(meshNodeGlobal) * jointGlobal * inverseBindMatrix.
     */
    public void jointMatrices(ModelSkin skin, int meshNode, Matrix4f[] out) {
        worldMatrices[meshNode].invertAffine(inverseMeshGlobal);
        int[] joints = skin.jointNodes();
        int count = Math.min(joints.length, out.length);
        for (int i = 0; i < count; i++) {
            inverseMeshGlobal.mul(worldMatrices[joints[i]], out[i])
                    .mul(skin.inverseBindMatrices()[i]);
        }
    }
}
