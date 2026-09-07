package fr.tofuxia.renderer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Per-frame buckets of {@link RenderItem}s keyed by render pass. Cleared at
 * beginFrame and snapshotted for worker-side culling/sorting at endFrame.
 */
final class RenderQueue {
    private final Map<PassId, List<RenderItem>> buckets = new EnumMap<>(PassId.class);

    RenderQueue() {
        for (PassId pass : PassId.values()) {
            buckets.put(pass, new ArrayList<>());
        }
    }

    void add(PassId pass, RenderItem item) {
        buckets.get(pass).add(item);
    }

    List<RenderItem> items(PassId pass) {
        return buckets.get(pass);
    }

    void clear() {
        for (List<RenderItem> bucket : buckets.values()) {
            bucket.clear();
        }
    }

    Map<PassId, List<RenderItem>> snapshot() {
        EnumMap<PassId, List<RenderItem>> copy = new EnumMap<>(PassId.class);
        for (Map.Entry<PassId, List<RenderItem>> entry : buckets.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    int totalItems() {
        int total = 0;
        for (List<RenderItem> bucket : buckets.values()) {
            total += bucket.size();
        }
        return total;
    }
}
