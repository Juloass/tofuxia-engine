package fr.tofuxia.entity.attribute;

import io.github.juloass.identifier.Identifier;

import java.util.Arrays;

public record EncodedEntityAttribute(Identifier id, byte[] value) {
    public EncodedEntityAttribute {
        if (id == null) throw new IllegalArgumentException("id is required");
        value = value == null ? new byte[0] : value.clone();
    }
    @Override public byte[] value() { return value.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof EncodedEntityAttribute that && id.equals(that.id) && Arrays.equals(value, that.value);
    }
    @Override public int hashCode() { return 31 * id.hashCode() + Arrays.hashCode(value); }
}
