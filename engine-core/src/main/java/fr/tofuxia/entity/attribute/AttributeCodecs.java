package fr.tofuxia.entity.attribute;

import io.github.juloass.identifier.Identifier;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.UnaryOperator;

public final class AttributeCodecs {
    public static final EntityAttributeCodec<Integer> INT = codec(DataOutputStream::writeInt, DataInputStream::readInt);
    public static final EntityAttributeCodec<Long> LONG = codec(DataOutputStream::writeLong, DataInputStream::readLong);
    public static final EntityAttributeCodec<Float> FLOAT = codec(DataOutputStream::writeFloat, DataInputStream::readFloat);
    public static final EntityAttributeCodec<Double> DOUBLE = codec(DataOutputStream::writeDouble, DataInputStream::readDouble);
    public static final EntityAttributeCodec<Boolean> BOOLEAN = codec(DataOutputStream::writeBoolean, DataInputStream::readBoolean);
    public static final EntityAttributeCodec<String> STRING = codec(AttributeCodecs::writeString, AttributeCodecs::readString);
    public static final EntityAttributeCodec<Identifier> IDENTIFIER = mapped(STRING, Identifier::value, Identifier::parse);
    public static final EntityAttributeCodec<UUID> UUID = new EntityAttributeCodec<>() {
        @Override public void encode(DataOutputStream output, UUID value) throws IOException {
            output.writeLong(value.getMostSignificantBits());
            output.writeLong(value.getLeastSignificantBits());
        }

        @Override public UUID decode(DataInputStream input) throws IOException {
            return new UUID(input.readLong(), input.readLong());
        }
    };
    public static final EntityAttributeCodec<Long> ENTITY_ID = LONG;

    private AttributeCodecs() {}

    public static UnaryOperator<Float> rangedFloat(float minimum, float maximum) {
        if (!Float.isFinite(minimum) || !Float.isFinite(maximum) || minimum > maximum) {
            throw new IllegalArgumentException("Invalid float attribute range");
        }
        return value -> {
            if (value == null || !Float.isFinite(value)) throw new IllegalArgumentException("Attribute must be finite");
            return Math.clamp(value, minimum, maximum);
        };
    }

    public static UnaryOperator<Integer> nonNegativeInt() {
        return value -> {
            if (value == null) throw new IllegalArgumentException("Attribute is required");
            return Math.max(0, value);
        };
    }

    public static <E extends Enum<E>> EntityAttributeCodec<E> enumCodec(Class<E> enumType) {
        E[] constants = enumType.getEnumConstants();
        return new EntityAttributeCodec<>() {
            @Override public void encode(DataOutputStream output, E value) throws IOException {
                writeString(output, value.name());
            }

            @Override public E decode(DataInputStream input) throws IOException {
                String name = readString(input);
                for (E value : constants) if (value.name().equals(name)) return value;
                throw new IOException("Unknown " + enumType.getSimpleName() + " value '" + name + "'");
            }
        };
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 1_048_576) throw new IOException("Invalid attribute string length " + length);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated attribute string");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static <T> EntityAttributeCodec<T> codec(Writer<T> writer, Reader<T> reader) {
        return new EntityAttributeCodec<>() {
            @Override public void encode(DataOutputStream output, T value) throws IOException { writer.write(output, value); }
            @Override public T decode(DataInputStream input) throws IOException { return reader.read(input); }
        };
    }

    private static <A, B> EntityAttributeCodec<B> mapped(EntityAttributeCodec<A> codec,
                                                           java.util.function.Function<B, A> encode,
                                                           java.util.function.Function<A, B> decode) {
        return new EntityAttributeCodec<>() {
            @Override public void encode(DataOutputStream output, B value) throws IOException { codec.encode(output, encode.apply(value)); }
            @Override public B decode(DataInputStream input) throws IOException { return decode.apply(codec.decode(input)); }
        };
    }

    @FunctionalInterface private interface Writer<T> { void write(DataOutputStream output, T value) throws IOException; }
    @FunctionalInterface private interface Reader<T> { T read(DataInputStream input) throws IOException; }
}
