package fr.tofuxia.entity.attribute;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EntityAttributeDefaults {
    private final Map<EntityAttribute<?>, Object> values;

    private EntityAttributeDefaults(Map<EntityAttribute<?>, Object> values) { this.values = Map.copyOf(values); }

    public static Builder builder() { return new Builder(); }
    public static EntityAttributeDefaults empty() { return new EntityAttributeDefaults(Map.of()); }

    @SuppressWarnings("unchecked")
    public <T> T value(EntityAttribute<T> attribute) {
        Object value = values.get(attribute);
        return value == null ? attribute.defaultValue() : (T) value;
    }

    public static final class Builder {
        private final Map<EntityAttribute<?>, Object> values = new LinkedHashMap<>();
        public <T> Builder set(EntityAttribute<T> attribute, T value) {
            values.put(attribute, attribute.normalize(value));
            return this;
        }
        public EntityAttributeDefaults build() { return new EntityAttributeDefaults(values); }
    }
}
