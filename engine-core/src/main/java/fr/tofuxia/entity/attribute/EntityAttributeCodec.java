package fr.tofuxia.entity.attribute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface EntityAttributeCodec<T> {
    void encode(DataOutputStream output, T value) throws IOException;

    T decode(DataInputStream input) throws IOException;

    default byte[] encode(T value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                encode(output, value);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not encode entity attribute", exception);
        }
    }

    default T decode(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            T value = decode(input);
            if (input.available() != 0) throw new IOException("Trailing bytes in entity attribute value");
            return value;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Could not decode entity attribute", exception);
        }
    }
}
