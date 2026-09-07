package fr.tofuxia.entity.attribute;

import io.github.juloass.identifier.Identifier;

import java.util.List;

public final class EntityAttributeSelfTest {
    private enum Stance { PASSIVE, AGGRESSIVE }

    public static void main(String[] args) {
        codecsRoundTrip();
        registryRejectsDuplicates();
        containerTracksTypedChanges();
        encodedValuesFailSafely();
        System.out.println("Entity attribute self-test passed");
    }

    private static void codecsRoundTrip() {
        require(AttributeCodecs.INT.decode(AttributeCodecs.INT.encode(42)) == 42, "int codec");
        require(AttributeCodecs.LONG.decode(AttributeCodecs.LONG.encode(9_000_000_000L)) == 9_000_000_000L, "long codec");
        require(Float.compare(AttributeCodecs.FLOAT.decode(AttributeCodecs.FLOAT.encode(1.25f)), 1.25f) == 0, "float codec");
        require(Double.compare(AttributeCodecs.DOUBLE.decode(AttributeCodecs.DOUBLE.encode(2.5)), 2.5) == 0, "double codec");
        require(AttributeCodecs.BOOLEAN.decode(AttributeCodecs.BOOLEAN.encode(true)), "boolean codec");
        require(AttributeCodecs.IDENTIFIER.decode(AttributeCodecs.IDENTIFIER.encode(Identifier.parse("tofuxia:test")))
                .equals(Identifier.parse("tofuxia:test")), "identifier codec");
        var enumCodec = AttributeCodecs.enumCodec(Stance.class);
        require(enumCodec.decode(enumCodec.encode(Stance.AGGRESSIVE)) == Stance.AGGRESSIVE, "enum codec");
    }

    private static void registryRejectsDuplicates() {
        EntityAttributeRegistry registry = new EntityAttributeRegistry();
        Identifier id = Identifier.parse("tofuxia:health");
        registry.register(id, integerAttribute(100, true, true));
        boolean rejected = false;
        try { registry.register(id, integerAttribute(20, true, true)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "duplicate identifier rejection");
    }

    private static void containerTracksTypedChanges() {
        EntityAttributeRegistry registry = new EntityAttributeRegistry();
        EntityAttribute<Integer> health = registry.register(Identifier.parse("tofuxia:health"), integerAttribute(100, true, true));
        EntityAttribute<Float> speed = registry.register(Identifier.parse("tofuxia:speed"), EntityAttribute.builder(AttributeCodecs.FLOAT)
                .defaultValue(3.0f).normalize(AttributeCodecs.rangedFloat(0, 10)).persistent(false).synced(true).build());
        EntityAttribute<Boolean> secret = registry.register(Identifier.parse("tofuxia:secret"), EntityAttribute.builder(AttributeCodecs.BOOLEAN)
                .defaultValue(false).persistent(true).synced(false).build());
        EntityAttributeDefaults creature = EntityAttributeDefaults.builder().set(health, 20).build();
        EntityAttributeContainer attributes = new EntityAttributeContainer(creature);

        require(attributes.get(health) == 20, "entity-type default override");
        require(attributes.initialSynced(registry).size() == 2, "initial sync contains all synced definitions");
        require(attributes.set(health, 75), "typed setter changes value");
        require(!attributes.set(health, 75), "equal setter is ignored");
        require(attributes.collectDirtySynced().size() == 1, "only changed attribute is dirty");
        require(attributes.collectDirtySynced().isEmpty(), "dirty values clear after collection");
        require(attributes.persistenceDirty(), "persistent change marks persistence dirty");
        require(attributes.persistentOverrides().size() == 1, "only persistent override is serialized");
        require(attributes.set(speed, 99f) && Float.compare(attributes.get(speed), 10f) == 0, "numeric normalization");
        require(attributes.reset(health) && attributes.get(health) == 20, "reset restores entity-type default");
        require(attributes.persistentOverrides().isEmpty(), "defaults are not persisted");
        require(attributes.set(secret, true), "server-only attribute changes");
        require(attributes.collectDirtySynced().size() == 2, "reset and synced speed are batched without server-only value");
    }

    private static void encodedValuesFailSafely() {
        EntityAttributeRegistry registry = new EntityAttributeRegistry();
        EntityAttribute<Integer> health = registry.register(Identifier.parse("tofuxia:health"), integerAttribute(100, true, true));
        EntityAttributeContainer attributes = new EntityAttributeContainer(EntityAttributeDefaults.empty());
        require(!attributes.applyEncoded(registry, "other:unknown", new byte[]{1}, true), "unknown network attribute ignored");
        require(!attributes.applyEncoded(registry, health.id().value(), new byte[]{1}, true), "invalid network value ignored");
        require(attributes.get(health) == 100, "invalid value leaves default intact");
        require(attributes.applyEncoded(registry, health.id().value(), AttributeCodecs.INT.encode(50), false), "persistent decode");
        require(attributes.get(health) == 50, "decoded persisted value applied");
    }

    private static EntityAttribute<Integer> integerAttribute(int defaultValue, boolean persistent, boolean synced) {
        return EntityAttribute.builder(AttributeCodecs.INT).defaultValue(defaultValue)
                .normalize(AttributeCodecs.nonNegativeInt()).persistent(persistent).synced(synced).build();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
