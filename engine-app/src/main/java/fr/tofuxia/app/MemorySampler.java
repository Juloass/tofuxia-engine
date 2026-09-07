package fr.tofuxia.app;

import fr.tofuxia.ui.ProfilerSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

final class MemorySampler {
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private long previousUsed = -1;
    private long previousGcCount = -1;
    private long previousGcTime = -1;

    ProfilerSnapshot.Memory sample() {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long used = Math.max(0, heap.getUsed());
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long count = collector.getCollectionCount();
            long time = collector.getCollectionTime();
            if (count > 0) gcCount += count;
            if (time > 0) gcTime += time;
        }
        long usedDelta = previousUsed < 0 ? 0 : used - previousUsed;
        long gcCountDelta = previousGcCount < 0 ? 0 : gcCount - previousGcCount;
        long gcTimeDelta = previousGcTime < 0 ? 0 : gcTime - previousGcTime;
        previousUsed = used;
        previousGcCount = gcCount;
        previousGcTime = gcTime;
        return new ProfilerSnapshot.Memory(
                used,
                Math.max(0, heap.getCommitted()),
                heap.getMax(),
                Math.max(0, nonHeap.getUsed()),
                usedDelta,
                Math.max(0, gcCountDelta),
                Math.max(0, gcTimeDelta));
    }
}
