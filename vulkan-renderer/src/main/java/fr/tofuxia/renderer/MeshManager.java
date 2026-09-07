package fr.tofuxia.renderer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Uploads {@link CpuMesh} data into shared GPU buffers and owns the resulting
 * suballocations for the renderer's lifetime.
 */
public final class MeshManager implements AutoCloseable {
    private static final long VERTEX_PAGE_BYTES = 64L * 1024L * 1024L;
    private static final long INDEX_PAGE_BYTES = 32L * 1024L * 1024L;
    private static final long ALLOCATION_ALIGNMENT = 256L;

    private final VulkanContext context;
    private final Pool vertexPool;
    private final Pool indexPool;
    private final List<GpuMesh> meshes = new ArrayList<>();
    private final List<Retired> retired = new ArrayList<>();
    private long frame;
    private int frameBufferCreations;

    public MeshManager(VulkanContext context) {
        this.context = context;
        this.vertexPool = new Pool(VERTEX_PAGE_BYTES, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        this.indexPool = new Pool(INDEX_PAGE_BYTES, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
    }

    public GpuMesh upload(CpuMesh mesh) {
        long vertexBytes = mesh.vertexByteSize();
        long indexBytes = (long) mesh.indices().length * Integer.BYTES;
        Allocation vertex = vertexPool.allocate(vertexBytes);
        Allocation index = indexPool.allocate(indexBytes);
        context.uploadBytes(vertex.buffer(), ByteBuffer.wrap(mesh.vertices()), vertex.offset());
        context.uploadInts(index.buffer(), mesh.indices(), index.offset());
        GpuMesh gpuMesh = new GpuMesh(mesh.name(), mesh.layout(), vertex, index,
                mesh.indices().length, bounds(mesh));
        meshes.add(gpuMesh);
        return gpuMesh;
    }

    static GpuMesh.BoundingSphere bounds(CpuMesh mesh) {
        if (mesh.vertexCount() == 0) return new GpuMesh.BoundingSphere(0, 0, 0, 0);
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float x = mesh.positionX(i), y = mesh.positionY(i), z = mesh.positionZ(i);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        float x = (minX + maxX) * 0.5f, y = (minY + maxY) * 0.5f, z = (minZ + maxZ) * 0.5f;
        float radiusSquared = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            float dx = mesh.positionX(i) - x, dy = mesh.positionY(i) - y, dz = mesh.positionZ(i) - z;
            radiusSquared = Math.max(radiusSquared, dx * dx + dy * dy + dz * dz);
        }
        return new GpuMesh.BoundingSphere(x, y, z, (float) Math.sqrt(radiusSquared));
    }

    public int count() {
        return meshes.size();
    }

    public void beginFrame() {
        frame++;
        frameBufferCreations = 0;
        Iterator<Retired> iterator = retired.iterator();
        while (iterator.hasNext()) {
            Retired entry = iterator.next();
            if (entry.safeAfterFrame() > frame) continue;
            free(entry.mesh());
            iterator.remove();
        }
    }

    public void retire(GpuMesh mesh) {
        if (mesh == null || !meshes.remove(mesh)) return;
        retired.add(new Retired(mesh, frame + VulkanContext.FRAMES_IN_FLIGHT));
    }

    public int frameBufferCreations() {
        return frameBufferCreations;
    }

    private void free(GpuMesh mesh) {
        vertexPool.free(mesh.vertexAllocation());
        indexPool.free(mesh.indexAllocation());
    }

    @Override
    public void close() {
        meshes.clear();
        retired.clear();
        vertexPool.close();
        indexPool.close();
    }

    static long align(long value, long alignment) {
        long mask = alignment - 1;
        return (value + mask) & ~mask;
    }

    private final class Pool implements AutoCloseable {
        private final long pageBytes;
        private final int usage;
        private final List<Page> pages = new ArrayList<>();

        Pool(long pageBytes, int usage) {
            this.pageBytes = pageBytes;
            this.usage = usage;
        }

        Allocation allocate(long requestedBytes) {
            long bytes = align(Math.max(1L, requestedBytes), ALLOCATION_ALIGNMENT);
            for (Page page : pages) {
                Allocation allocation = page.allocate(bytes);
                if (allocation != null) return allocation;
            }
            long capacity = Math.max(pageBytes, bytes);
            Page page = new Page(context.createBuffer(capacity, usage), capacity);
            frameBufferCreations++;
            pages.add(page);
            Allocation allocation = page.allocate(bytes);
            if (allocation == null) throw new IllegalStateException("Failed to suballocate new GPU buffer page");
            return allocation;
        }

        void free(Allocation allocation) {
            if (allocation != null) allocation.page().free(allocation.offset(), allocation.size());
        }

        @Override
        public void close() {
            for (Page page : pages) context.destroyBuffer(page.buffer());
            pages.clear();
        }
    }

    private static final class Page {
        private final VulkanContext.GpuBuffer buffer;
        private final List<FreeBlock> freeBlocks = new ArrayList<>();

        Page(VulkanContext.GpuBuffer buffer, long capacity) {
            this.buffer = buffer;
            freeBlocks.add(new FreeBlock(0, capacity));
        }

        VulkanContext.GpuBuffer buffer() {
            return buffer;
        }

        Allocation allocate(long bytes) {
            for (int i = 0; i < freeBlocks.size(); i++) {
                FreeBlock block = freeBlocks.get(i);
                long alignedOffset = align(block.offset(), ALLOCATION_ALIGNMENT);
                long padding = alignedOffset - block.offset();
                long remaining = block.size() - padding;
                if (remaining < bytes) continue;
                long tailOffset = alignedOffset + bytes;
                long tailSize = block.offset() + block.size() - tailOffset;
                if (padding > 0) {
                    block.size(padding);
                    if (tailSize > 0) freeBlocks.add(i + 1, new FreeBlock(tailOffset, tailSize));
                } else if (tailSize > 0) {
                    block.offset(tailOffset);
                    block.size(tailSize);
                } else {
                    freeBlocks.remove(i);
                }
                return new Allocation(this, buffer, alignedOffset, bytes);
            }
            return null;
        }

        void free(long offset, long size) {
            freeBlocks.add(new FreeBlock(offset, size));
            freeBlocks.sort(Comparator.comparingLong(FreeBlock::offset));
            for (int i = 0; i < freeBlocks.size() - 1; ) {
                FreeBlock current = freeBlocks.get(i);
                FreeBlock next = freeBlocks.get(i + 1);
                long currentEnd = current.offset() + current.size();
                if (currentEnd >= next.offset()) {
                    long mergedEnd = Math.max(currentEnd, next.offset() + next.size());
                    current.size(mergedEnd - current.offset());
                    freeBlocks.remove(i + 1);
                } else {
                    i++;
                }
            }
        }
    }

    record Allocation(Page page, VulkanContext.GpuBuffer buffer, long offset, long size) {}

    private static final class FreeBlock {
        private long offset;
        private long size;

        FreeBlock(long offset, long size) {
            this.offset = offset;
            this.size = size;
        }

        long offset() {
            return offset;
        }

        void offset(long offset) {
            this.offset = offset;
        }

        long size() {
            return size;
        }

        void size(long size) {
            this.size = size;
        }
    }

    private record Retired(GpuMesh mesh, long safeAfterFrame) {}
}
