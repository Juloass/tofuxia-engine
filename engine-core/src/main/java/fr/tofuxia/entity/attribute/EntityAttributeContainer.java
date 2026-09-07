package fr.tofuxia.entity.attribute;

import io.github.juloass.identifier.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class EntityAttributeContainer {
    private final EntityAttributeDefaults defaults;
    private final Map<EntityAttribute<?>, Object> overrides = new LinkedHashMap<>();
    private final Set<EntityAttribute<?>> dirtySynced = new LinkedHashSet<>();
    private boolean persistenceDirty;

    public EntityAttributeContainer(EntityAttributeDefaults defaults) {
        this.defaults = defaults == null ? EntityAttributeDefaults.empty() : defaults;
    }

    @SuppressWarnings("unchecked")
    public synchronized <T> T get(EntityAttribute<T> attribute) {
        Object value = overrides.get(attribute);
        return value == null ? defaults.value(attribute) : (T) value;
    }

    public synchronized <T> boolean set(EntityAttribute<T> attribute, T value) {
        T normalized = attribute.normalize(value);
        T previous = get(attribute);
        if (Objects.equals(previous, normalized)) return false;
        T defaultValue = defaults.value(attribute);
        if (Objects.equals(defaultValue, normalized)) overrides.remove(attribute);
        else overrides.put(attribute, normalized);
        if (attribute.synced()) dirtySynced.add(attribute);
        if (attribute.persistent()) persistenceDirty = true;
        return true;
    }

    public synchronized <T> boolean reset(EntityAttribute<T> attribute) {
        return set(attribute, defaults.value(attribute));
    }

    public synchronized List<EncodedEntityAttribute> initialSynced(EntityAttributeRegistry registry) {
        ArrayList<EncodedEntityAttribute> encoded = new ArrayList<>();
        for (EntityAttribute<?> attribute : registry.values()) if (attribute.synced()) encoded.add(encode(attribute));
        return List.copyOf(encoded);
    }

    public synchronized List<EncodedEntityAttribute> collectDirtySynced() {
        ArrayList<EncodedEntityAttribute> encoded = new ArrayList<>(dirtySynced.size());
        for (EntityAttribute<?> attribute : dirtySynced) encoded.add(encode(attribute));
        dirtySynced.clear();
        return List.copyOf(encoded);
    }

    public synchronized List<EncodedEntityAttribute> persistentOverrides() {
        ArrayList<EncodedEntityAttribute> encoded = new ArrayList<>();
        for (EntityAttribute<?> attribute : overrides.keySet()) if (attribute.persistent()) encoded.add(encode(attribute));
        return List.copyOf(encoded);
    }

    public synchronized boolean persistenceDirty() { return persistenceDirty; }
    public synchronized boolean consumePersistenceDirty() {
        boolean result = persistenceDirty;
        persistenceDirty = false;
        return result;
    }

    public boolean applyEncoded(EntityAttributeRegistry registry, String rawId, byte[] bytes, boolean fromNetwork) {
        EntityAttribute<?> attribute;
        try { attribute = registry.find(Identifier.parse(rawId)).orElse(null); }
        catch (IllegalArgumentException ignored) { return false; }
        if (attribute == null || (fromNetwork && !attribute.synced())) return false;
        return applyDecoded(attribute, bytes, fromNetwork);
    }

    private synchronized <T> boolean applyDecoded(EntityAttribute<T> attribute, byte[] bytes, boolean fromNetwork) {
        try {
            T value = attribute.normalize(attribute.codec().decode(bytes));
            T defaultValue = defaults.value(attribute);
            if (Objects.equals(defaultValue, value)) overrides.remove(attribute); else overrides.put(attribute, value);
            if (!fromNetwork && attribute.persistent()) persistenceDirty = true;
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private <T> EncodedEntityAttribute encode(EntityAttribute<T> attribute) {
        return new EncodedEntityAttribute(attribute.id(), attribute.codec().encode(get(attribute)));
    }
}
