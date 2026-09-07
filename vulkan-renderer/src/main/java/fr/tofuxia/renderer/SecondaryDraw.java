package fr.tofuxia.renderer;

/** Immutable Vulkan draw state resolved on the render thread before worker recording. */
record SecondaryDraw(
        long pipeline,
        long textureSet,
        long jointsSet,
        long vertexBuffer,
        long indexBuffer,
        long vertexBufferOffset,
        long indexBufferOffset,
        int indexCount,
        byte[] pushConstants
) {
    SecondaryDraw {
        if (pipeline == 0 || vertexBuffer == 0 || indexBuffer == 0) {
            throw new IllegalArgumentException("Secondary draw requires resolved Vulkan handles");
        }
        if (indexCount < 0) throw new IllegalArgumentException("Negative index count");
        pushConstants = pushConstants.clone();
    }

    @Override
    public byte[] pushConstants() {
        return pushConstants.clone();
    }
}
