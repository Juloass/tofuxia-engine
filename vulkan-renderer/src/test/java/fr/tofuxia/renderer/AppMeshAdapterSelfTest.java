package fr.tofuxia.renderer;

import fr.tofuxia.render.MeshData;
import fr.tofuxia.render.ParticleMeshData;
import fr.tofuxia.render.UiRenderData;
import fr.tofuxia.terrain.TerrainData;

/** Headless checks for main-app mesh conversion plus legacy projected-shadow mesh compatibility. */
public final class AppMeshAdapterSelfTest {
    private static int failures;

    public static void main(String[] args) {
        testMeshDataConversion();
        testParticleConversion();
        testProjectedSunShadow();
        testSmallCasterSunShadow();
        testBoxShadowUsesSingleFootprint();
        testGroundDecalDoesNotCastShadow();
        testTerrainConformingSunShadow();
        testUiAccounting();
        if (failures > 0) {
            System.err.println("[app-render-adapter-selftest] FAILED with " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("[app-render-adapter-selftest] all checks passed");
    }

    private static void testMeshDataConversion() {
        MeshData mesh = new MeshData(new float[]{
                1, 2, 3, 0.2f, 0.4f, 0.6f,
                4, 5, 6, 0.8f, 0.7f, 0.6f,
                7, 8, 9, 1.0f, 0.9f, 0.8f,
        }, new int[]{0, 1, 2});
        CpuMesh converted = Renderer.convertMeshDataVertices("test-mesh", mesh);
        expect("mesh conversion packed stride", converted.vertexByteSize() == 3L * VertexLayout.STATIC.strideBytes());
        expect("mesh position copied", converted.positionX(0) == 1 && converted.positionY(0) == 2 && converted.positionZ(0) == 3);
        expect("mesh normal defaults up", converted.normalX(0) == 0 && converted.normalY(0) == 1 && converted.normalZ(0) == 0);
        expect("mesh color copied", Math.abs(converted.colorR(0) - 0.2f) < 0.004f
                && Math.abs(converted.colorG(0) - 0.4f) < 0.004f
                && Math.abs(converted.colorB(0) - 0.6f) < 0.004f
                && converted.colorA(0) == 1.0f);
    }

    private static void testParticleConversion() {
        ParticleMeshData particles = new ParticleMeshData(new float[]{
                1, 2, 3, 0.1f, 0.2f, 1, 0.5f, 0.25f, 0.75f,
                4, 5, 6, 0.3f, 0.4f, 0.9f, 0.8f, 0.7f, 0.6f,
                7, 8, 9, 0.5f, 0.6f, 0.1f, 0.2f, 0.3f, 0.4f,
        }, new int[]{0, 1, 2});
        CpuMesh converted = Renderer.convertParticleVertices("test-particles", particles.alphaVertices(), particles.alphaIndices());
        expect("particle conversion packed stride", converted.vertexByteSize() == 3L * VertexLayout.STATIC.strideBytes());
        expect("particle uv copied", Math.abs(converted.uvU(0) - 0.1f) < 0.001f && Math.abs(converted.uvV(0) - 0.2f) < 0.001f);
        expect("particle rgba copied", converted.colorR(0) == 1
                && Math.abs(converted.colorG(0) - 0.5f) < 0.004f
                && Math.abs(converted.colorB(0) - 0.25f) < 0.004f
                && Math.abs(converted.colorA(0) - 0.75f) < 0.004f);
    }

    private static void testUiAccounting() {
        UiRenderData ui = new UiRenderData(new float[]{
                0, 0, -1, -1, 1, 1, 1, 1,
                1, 0, -1, -1, 1, 1, 1, 1,
                1, 1, -1, -1, 1, 1, 1, 1,
        }, new int[]{0, 1, 2}, java.util.List.of());
        expect("ui vertex count", ui.vertexCount() == 3);
        expect("ui index count", ui.indices().length == 3);
    }

    private static void testProjectedSunShadow() {
        MeshData side = new MeshData(new float[]{
                0, 0, 0, 1, 1, 1,
                0, 1, 0, 1, 1, 1,
                1, 0, 0, 1, 1, 1,
        }, new int[]{0, 1, 2});
        Renderer.ProjectedShadowMesh shadowA = Renderer.buildProjectedShadowMesh(side,
                new org.joml.Vector3f(-0.5f, -1.0f, -0.25f).normalize(),
                new org.joml.Vector3f(0.4f, 0.5f, 0.7f), 0.5f);
        Renderer.ProjectedShadowMesh shadowB = Renderer.buildProjectedShadowMesh(side,
                new org.joml.Vector3f(0.5f, -1.0f, -0.25f).normalize(),
                new org.joml.Vector3f(0.4f, 0.5f, 0.7f), 0.5f);
        expect("legacy projected shadow generated", shadowA.indices().length == 3);
        expect("legacy projected shadow uses static layout",
                shadowA.vertices().length == 3 * VertexLayout.STATIC.debugFloatComponents);
        expect("legacy projected shadow follows sun direction", shadowA.vertices()[12] != shadowB.vertices()[12]);
        expect("legacy projected shadow has alpha", shadowA.vertices()[11] > 0.0f && shadowA.vertices()[11] < 0.5f);
    }

    private static void testSmallCasterSunShadow() {
        MeshData rail = new MeshData(new float[]{
                0, 0.06f, 0, 1, 1, 1,
                0, 0.06f, 0.08f, 1, 1, 1,
                0.4f, 0.06f, 0, 1, 1, 1,
        }, new int[]{0, 1, 2});
        Renderer.ProjectedShadowMesh shadow = Renderer.buildProjectedShadowMesh(rail,
                new org.joml.Vector3f(-0.5f, -1.0f, -0.25f).normalize(),
                new org.joml.Vector3f(0.4f, 0.5f, 0.7f), 0.5f);
        expect("small prop shadow generated", shadow.indices().length == 3);
        expect("small prop shadow visible alpha", shadow.vertices()[11] >= 0.11f);
    }

    private static void testGroundDecalDoesNotCastShadow() {
        MeshData decal = new MeshData(new float[]{
                0, 0.03f, 0, 1, 1, 1,
                0, 0.03f, 0.4f, 1, 1, 1,
                0.4f, 0.03f, 0, 1, 1, 1,
        }, new int[]{0, 1, 2});
        Renderer.ProjectedShadowMesh shadow = Renderer.buildProjectedShadowMesh(decal,
                new org.joml.Vector3f(-0.5f, -1.0f, -0.25f).normalize(),
                new org.joml.Vector3f(0.4f, 0.5f, 0.7f), 0.5f);
        expect("ground decal skipped for shadow", shadow.indices().length == 0);
    }

    private static void testBoxShadowUsesSingleFootprint() {
        MeshData box = new MeshData(new float[]{
                -0.2f, 0.0f, -0.2f, 1, 1, 1,
                 0.2f, 0.0f, -0.2f, 1, 1, 1,
                 0.2f, 0.6f, -0.2f, 1, 1, 1,
                -0.2f, 0.6f, -0.2f, 1, 1, 1,
                -0.2f, 0.0f,  0.2f, 1, 1, 1,
                 0.2f, 0.0f,  0.2f, 1, 1, 1,
                 0.2f, 0.6f,  0.2f, 1, 1, 1,
                -0.2f, 0.6f,  0.2f, 1, 1, 1,
        }, new int[]{
                0, 1, 2, 0, 2, 3, 4, 7, 6, 4, 6, 5,
                0, 4, 5, 0, 5, 1, 1, 5, 6, 1, 6, 2,
                2, 6, 7, 2, 7, 3, 3, 7, 4, 3, 4, 0
        });
        Renderer.ProjectedShadowMesh shadow = Renderer.buildProjectedShadowMesh(box,
                new org.joml.Vector3f(-0.5f, -1.0f, -0.25f).normalize(),
                new org.joml.Vector3f(0.4f, 0.5f, 0.7f), 0.5f);
        expect("box shadow collapsed to footprint", shadow.indices().length < box.indices().length);
        expect("box shadow keeps contact anchor", Math.abs(shadow.vertices()[1] - 0.018f) < 0.0001f);
    }

    private static void testTerrainConformingSunShadow() {
        TerrainData terrain = new TerrainData(2, 2, 1L, 2.0f);
        for (int i = 0; i < terrain.heightField.length; i++) {
            terrain.heightField[i] = 0.20f;
        }
        MeshData side = new MeshData(new float[]{
                0, 0.20f, 0, 1, 1, 1,
                0, 1.00f, 0, 1, 1, 1,
                0.4f, 0.20f, 0, 1, 1, 1,
        }, new int[]{0, 1, 2});
        Renderer.ProjectedShadowMesh shadow = Renderer.buildProjectedShadowMesh(side,
                new org.joml.Vector3f(-0.5f, -1.0f, -0.25f).normalize(),
                new org.joml.Vector3f(0.4f, 0.5f, 0.7f), 0.5f,
                Renderer.ShadowReceiver.fromTerrain(terrain));
        expect("terrain shadow generated", shadow.indices().length == 3);
        expect("terrain shadow samples ground height", Math.abs(shadow.vertices()[1] - 0.218f) < 0.0001f);
    }

    private static void expect(String what, boolean condition) {
        if (condition) {
            System.out.println("  ok  " + what);
        } else {
            failures++;
            System.err.println("  FAIL " + what);
        }
    }
}
