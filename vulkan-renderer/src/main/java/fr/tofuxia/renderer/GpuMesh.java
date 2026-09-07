package fr.tofuxia.renderer;

/**
 * A mesh uploaded to GPU buffers, ready for render submission. Created and
 * owned by {@link MeshManager}; gameplay code treats it as an opaque handle.
 */
public final class GpuMesh {
    private final String name;
    private final VertexLayout layout;
    private final MeshManager.Allocation vertexAllocation;
    private final MeshManager.Allocation indexAllocation;
    private final int indexCount;
    private final BoundingSphere bounds;

    GpuMesh(String name, VertexLayout layout,
            MeshManager.Allocation vertexAllocation, MeshManager.Allocation indexAllocation, int indexCount,
            BoundingSphere bounds) {
        this.name = name;
        this.layout = layout;
        this.vertexAllocation = vertexAllocation;
        this.indexAllocation = indexAllocation;
        this.indexCount = indexCount;
        this.bounds = bounds;
    }

    public String name() {
        return name;
    }

    public VertexLayout layout() {
        return layout;
    }

    public int indexCount() {
        return indexCount;
    }

    VulkanContext.GpuBuffer vertexBuffer() {
        return vertexAllocation == null ? null : vertexAllocation.buffer();
    }

    VulkanContext.GpuBuffer indexBuffer() {
        return indexAllocation == null ? null : indexAllocation.buffer();
    }

    long vertexBufferOffset() {
        return vertexAllocation == null ? 0 : vertexAllocation.offset();
    }

    long indexBufferOffset() {
        return indexAllocation == null ? 0 : indexAllocation.offset();
    }

    MeshManager.Allocation vertexAllocation() {
        return vertexAllocation;
    }

    MeshManager.Allocation indexAllocation() {
        return indexAllocation;
    }

    BoundingSphere bounds() {
        return bounds;
    }

    record BoundingSphere(float x, float y, float z, float radius) {}
}
