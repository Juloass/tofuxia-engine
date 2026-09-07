package fr.tofuxia.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class UiStateStore {
    private final Set<String> open = new HashSet<>();
    private final Map<String, Float> scroll = new HashMap<>();
    private final Map<String, StringBuilder> text = new HashMap<>();

    public boolean isOpen(String id, boolean defaultOpen) {
        if (defaultOpen) {
            return !open.contains("closed:" + id);
        }
        return open.contains("open:" + id);
    }

    public void setOpen(String id, boolean value, boolean defaultOpen) {
        if (defaultOpen) {
            if (value) open.remove("closed:" + id);
            else open.add("closed:" + id);
        } else {
            if (value) open.add("open:" + id);
            else open.remove("open:" + id);
        }
    }

    public void toggleOpen(String id, boolean defaultOpen) {
        setOpen(id, !isOpen(id, defaultOpen), defaultOpen);
    }

    public float scroll(String id) {
        return scroll.getOrDefault(id, 0.0f);
    }

    public void setScroll(String id, float value) {
        scroll.put(id, Math.max(0.0f, value));
    }

    public StringBuilder text(String id) {
        return text.computeIfAbsent(id, ignored -> new StringBuilder());
    }
}
