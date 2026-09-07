package fr.tofuxia.ui;

import java.util.Arrays;
import java.util.List;

public record ProfilerSnapshot(float frameMs, List<Entry> entries, Memory memory) {
    public static final ProfilerSnapshot EMPTY = new ProfilerSnapshot(0.0f, List.of(), Memory.EMPTY);

    public ProfilerSnapshot {
        entries = List.copyOf(entries == null ? List.of() : entries);
        memory = memory == null ? Memory.EMPTY : memory;
    }

    public static ProfilerSnapshot of(float frameMs, Entry... entries) {
        return new ProfilerSnapshot(frameMs, Arrays.asList(entries), Memory.EMPTY);
    }

    public ProfilerSnapshot withMemory(Memory memory) {
        return new ProfilerSnapshot(frameMs, entries, memory);
    }

    public record Entry(String name, float millis, List<Entry> children) {
        public Entry {
            name = name == null || name.isBlank() ? "unnamed" : name;
            millis = Math.max(0.0f, millis);
            children = List.copyOf(children == null ? List.of() : children);
        }

        public Entry(String name, float millis, Entry... children) {
            this(name, millis, Arrays.asList(children));
        }
    }

    public record Memory(
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long nonHeapUsedBytes,
            long usedDeltaBytes,
            long gcCountDelta,
            long gcTimeDeltaMs
    ) {
        public static final Memory EMPTY = new Memory(0, 0, 0, 0, 0, 0, 0);
    }
}
