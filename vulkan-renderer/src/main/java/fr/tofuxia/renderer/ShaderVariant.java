package fr.tofuxia.renderer;

import java.nio.ByteBuffer;

/** A compiled shader variant: SPIR-V for both stages plus debug identity. */
public record ShaderVariant(int id, ShaderVariantKey key, String name, ByteBuffer vertexSpirv, ByteBuffer fragmentSpirv) {
}
