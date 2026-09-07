package fr.tofuxia.entity.attribute;

import io.github.juloass.identifier.Identifier;

import java.util.Objects;
import java.util.function.UnaryOperator;

public final class EntityAttribute<T> {
    private final Identifier id;
    private final EntityAttributeCodec<T> codec;
    private final T defaultValue;
    private final boolean persistent;
    private final boolean synced;
    private final UnaryOperator<T> normalizer;

    private EntityAttribute(Identifier id, EntityAttributeCodec<T> codec, T defaultValue,
                            boolean persistent, boolean synced, UnaryOperator<T> normalizer) {
        this.id = id;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.defaultValue = normalize(defaultValue);
        this.persistent = persistent;
        this.synced = synced;
    }

    public static <T> Builder<T> builder(EntityAttributeCodec<T> codec) { return new Builder<>(codec); }

    EntityAttribute<T> bind(Identifier id) {
        if (this.id != null) throw new IllegalStateException("Attribute is already registered as " + this.id);
        return new EntityAttribute<>(Objects.requireNonNull(id, "id"), codec, defaultValue, persistent, synced, normalizer);
    }

    public Identifier id() {
        if (id == null) throw new IllegalStateException("Attribute definition is not registered");
        return id;
    }
    public EntityAttributeCodec<T> codec() { return codec; }
    public T defaultValue() { return defaultValue; }
    public boolean persistent() { return persistent; }
    public boolean synced() { return synced; }
    public T normalize(T value) { return Objects.requireNonNull(normalizer.apply(value), "normalized attribute value"); }

    public static final class Builder<T> {
        private final EntityAttributeCodec<T> codec;
        private T defaultValue;
        private boolean defaultSet;
        private boolean persistent;
        private boolean synced;
        private UnaryOperator<T> normalizer = UnaryOperator.identity();

        private Builder(EntityAttributeCodec<T> codec) { this.codec = Objects.requireNonNull(codec, "codec"); }
        public Builder<T> defaultValue(T value) { defaultValue = value; defaultSet = true; return this; }
        public Builder<T> persistent(boolean value) { persistent = value; return this; }
        public Builder<T> synced(boolean value) { synced = value; return this; }
        public Builder<T> normalize(UnaryOperator<T> value) { normalizer = Objects.requireNonNull(value); return this; }
        public EntityAttribute<T> build() {
            if (!defaultSet) throw new IllegalStateException("Attribute default value is required");
            return new EntityAttribute<>(null, codec, defaultValue, persistent, synced, normalizer);
        }
    }
}
