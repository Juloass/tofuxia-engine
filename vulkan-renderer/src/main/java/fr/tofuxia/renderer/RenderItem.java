package fr.tofuxia.renderer;

import org.joml.Matrix4f;

/**
 * One submitted draw: mesh + world transform + material (+ joint palette for
 * skinned meshes). The transform is copied at submission time so callers can
 * reuse scratch matrices.
 */
final class RenderItem {
    final GpuMesh mesh;
    final Matrix4f transform;
    final Material material;
    final JointPalette palette;
    final float viewDepth;

    RenderItem(GpuMesh mesh, Matrix4f transform, Material material, JointPalette palette, float viewDepth) {
        this.mesh = mesh;
        this.transform = new Matrix4f(transform);
        this.material = material;
        this.palette = palette;
        this.viewDepth = viewDepth;
    }
}
