package fr.tofuxia.renderer;

/** Headless shader compilation check for the embedded analytic UI pipeline. */
public final class UiSurfaceRendererSelfTest {
    public static void main(String[] args){
        UiPassRenderer.verifyShadersCompile();
        if(!UiPassRenderer.FRAG.contains("shapeDistance")||!UiPassRenderer.FRAG.contains("surfaceNoiseTexture"))
            throw new AssertionError("analytic surface shader features missing");
        System.out.println("[vulkan-renderer] analytic UI shaders compiled");
    }
}
