package fr.tofuxia.entity.attribute;

import io.github.juloass.identifier.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class EntityAttributeRegistry {
    private final Map<Identifier, EntityAttribute<?>> attributes = new LinkedHashMap<>();

    public synchronized <T> EntityAttribute<T> register(Identifier id, EntityAttribute<T> definition) {
        if (attributes.containsKey(id)) throw new IllegalArgumentException("Duplicate entity attribute " + id);
        EntityAttribute<T> registered = definition.bind(id);
        attributes.put(id, registered);
        return registered;
    }

    public synchronized Optional<EntityAttribute<?>> find(Identifier id) {
        return Optional.ofNullable(attributes.get(id));
    }

    public synchronized Optional<EntityAttribute<?>> find(String id) {
        try { return find(Identifier.parse(id)); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    public synchronized Collection<EntityAttribute<?>> values() { return java.util.List.copyOf(attributes.values()); }
}
