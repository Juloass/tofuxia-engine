package fr.tofuxia.renderer;

import fr.tofuxia.util.Json;
import fr.tofuxia.render.FontAtlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Headless checks for the renderer's CPU-side architecture: JSON parsing,
 * material feature resolution, shader variant generation (including real
 * shaderc compilation of every variant the demo scene uses), pipeline keys
 * and built-in mesh validity. No window or GPU device is required.
 */
public final class RendererSelfTest {
    private static final Path FIXTURE_ROOT = Path.of("build", "renderer-selftest-assets");

    private static int failures;

    public static void main(String[] args) {
        prepareFixtureAssets();
        testJsonParser();
        testMaterialResolution();
        testPackedVertexLayouts();
        testMaterialFiles();
        testVariantNaming();
        testPipelineKeys();
        testMultithreadedRecordingArchitecture();
        testShaderVariantCompilation();
        testShadowConfigurationAndShaders();
        testWorldDayCycleLighting();
        testPointLightEnvironment();
        testDebugTextMeshGeneration();
        testBuiltinMeshes();
        testGltfImport();
        testAnimationRuntime();
        if (failures > 0) {
            System.err.println("[renderer-selftest] FAILED with " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("[renderer-selftest] all checks passed");
    }

    private static void testJsonParser() {
        Json.JsonObject obj = Json.parseObject("""
                {"name":"x","n":3.5,"flag":true,"none":null,
                 "arr":[1,2,3],"nested":{"a":"b"},"esc":"a\\nb\\u0041"}
                """);
        expect("json string", "x".equals(obj.getString("name", null)));
        expect("json number", obj.getDouble("n", 0) == 3.5);
        expect("json bool", obj.getBoolean("flag", false));
        expect("json null", obj.containsKey("none") && obj.get("none") == null);
        expect("json array", java.util.Arrays.equals(obj.getArray("arr").toInts(), new int[]{1, 2, 3}));
        expect("json nested", "b".equals(obj.getObject("nested").getString("a", null)));
        expect("json escapes", "a\nbA".equals(obj.getString("esc", null)));
        boolean threw = false;
        try {
            Json.parse("{\"broken\": ");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        expect("json error reporting", threw);
    }

    private static void testMaterialResolution() {
        List<String> warnings = new ArrayList<>();
        Material cutout = Material.builder("cutout_test")
                .blendMode(BlendMode.CUTOUT)
                .baseColorTexture("textures/foo.png")
                .resolve(warnings::add);
        expect("cutout adds ALPHA_CUTOUT", cutout.has(MaterialFeature.ALPHA_CUTOUT));
        expect("texture adds TEXTURED", cutout.has(MaterialFeature.TEXTURED));
        expect("lit default adds STANDARD_LIGHTING", cutout.has(MaterialFeature.STANDARD_LIGHTING));
        expect("cutout writes depth", cutout.depthWrite());
        expect("lit receives shadows", cutout.receiveShadows() && cutout.has(MaterialFeature.RECEIVE_SHADOWS));
        expect("cutout casts shadows", cutout.castShadows());

        Material conflicted = Material.builder("conflict_test")
                .shadingModel(ShadingModel.UNLIT)
                .feature(MaterialFeature.STANDARD_LIGHTING)
                .feature(MaterialFeature.TEXTURED)
                .resolve(warnings::add);
        expect("unlit strips standard lighting", !conflicted.has(MaterialFeature.STANDARD_LIGHTING));
        expect("TEXTURED without texture dropped", !conflicted.has(MaterialFeature.TEXTURED));
        expect("conflicts produce warnings", warnings.size() >= 2);

        Material transparent = Material.builder("transparent_test")
                .blendMode(BlendMode.TRANSPARENT)
                .resolve(warnings::add);
        expect("transparent no depth write", !transparent.depthWrite());
        expect("transparent pass", transparent.pass() == PassId.TRANSPARENT);
        expect("transparent does not cast", !transparent.castShadows());

        Material billboard = Material.builder("billboard_test")
                .domain(MaterialDomain.BILLBOARD)
                .blendMode(BlendMode.ADDITIVE)
                .resolve(warnings::add);
        expect("billboard feature from domain", billboard.has(MaterialFeature.BILLBOARD));
        expect("billboard pass", billboard.pass() == PassId.BILLBOARDS);
        expect("billboard does not cast", !billboard.castShadows());

        Material outline = Material.builder("outline_test")
                .domain(MaterialDomain.OUTLINE)
                .shadingModel(ShadingModel.UNLIT)
                .cullMode(CullMode.BACK)
                .outlineWidthPixels(2.0f)
                .resolve(warnings::add);
        expect("outline feature from domain", outline.has(MaterialFeature.OUTLINE));
        expect("outline custom pass", outline.pass() == PassId.ENTITY_OUTLINE);
        expect("outline is depth tested without writing", outline.depthTest() && !outline.depthWrite());
        expect("outline width is configurable up to two native pixels", outline.outlineWidthPixels() == 2.0f);
        boolean[][] nearMask = new boolean[9][9];
        for (int y=2;y<=6;y++) for(int x=2;x<=6;x++) nearMask[y][x]=true;
        boolean[][] angledMask = new boolean[9][9];
        for (int y=2;y<=6;y++) for(int x=2;x<=6;x++) if(x+y>=6&&x+y<=10) angledMask[y][x]=true;
        boolean[][] farMask = new boolean[9][9]; farMask[4][4]=true;
        boolean overlaps = false;
        for (boolean[][] projected : java.util.List.of(nearMask, angledMask, farMask)) {
            boolean[][] exterior = ScreenSpaceOutlineRenderer.exteriorOnly(projected,1);
            for(int y=0;y<9;y++)for(int x=0;x<9;x++)
                overlaps |= projected[y][x]&&exterior[y][x];
        }
        expect("screen outline never overlaps selected pixels", !overlaps);
        expect("near silhouette has one-pixel exterior", ScreenSpaceOutlineRenderer.exteriorOnly(nearMask,1)[1][2]);
        expect("angled silhouette has one-pixel exterior", ScreenSpaceOutlineRenderer.exteriorOnly(angledMask,1)[3][2]);
        expect("distant silhouette remains one native pixel", ScreenSpaceOutlineRenderer.exteriorOnly(farMask,1)[4][5]);

        Material emissive = Material.builder("emissive_test")
                .emissive(1, 0.5f, 0.2f, 2.0f)
                .resolve(warnings::add);
        expect("emissive strength adds EMISSIVE", emissive.has(MaterialFeature.EMISSIVE));
    }

    private static void testMultithreadedRecordingArchitecture() {
        List<SecondaryCommandWorkers.Range> ranges = SecondaryCommandWorkers.partition(10, 3);
        expect("secondary partitions are contiguous", ranges.equals(List.of(
                new SecondaryCommandWorkers.Range(0, 4),
                new SecondaryCommandWorkers.Range(4, 7),
                new SecondaryCommandWorkers.Range(7, 10))));
        List<SecondaryCommandWorkers.Range> sparse = SecondaryCommandWorkers.partition(2, 4);
        expect("secondary partitions preserve empty workers", sparse.equals(List.of(
                new SecondaryCommandWorkers.Range(0, 1),
                new SecondaryCommandWorkers.Range(1, 2),
                new SecondaryCommandWorkers.Range(2, 2),
                new SecondaryCommandWorkers.Range(2, 2))));

        RenderThreadGuard guard = new RenderThreadGuard();
        java.util.concurrent.atomic.AtomicBoolean rejected = new java.util.concurrent.atomic.AtomicBoolean();
        Thread foreign = new Thread(() -> {
            try {
                guard.check("test frame operation");
            } catch (IllegalStateException expected) {
                rejected.set(true);
            }
        }, "not-render-thread");
        foreign.start();
        try {
            foreign.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        expect("render ownership rejects foreign thread", rejected.get());
        guard.check("owner operation");
        expect("render ownership accepts owner", true);

        CpuMesh boundsMesh = CpuMesh.staticMesh("bounds",
                new float[]{
                        -1, -2, -3, 0, 1, 0, 0, 0, 1, 1, 1, 1,
                        1, 2, 3, 0, 1, 0, 0, 0, 1, 1, 1, 1
                }, new int[]{0, 1});
        GpuMesh.BoundingSphere bounds = MeshManager.bounds(boundsMesh);
        expect("mesh bounds center", bounds.x() == 0 && bounds.y() == 0 && bounds.z() == 0);
        expect("mesh bounds radius", Math.abs(bounds.radius() - (float) Math.sqrt(14)) < 0.0001f);

        Material visibleMaterial = Material.builder("visibility").resolve(message -> {});
        GpuMesh visibilityMesh = new GpuMesh("visibility", VertexLayout.STATIC,
                null, null, 3, new GpuMesh.BoundingSphere(0, 0, 0, 0.25f));
        RenderItem visible = new RenderItem(visibilityMesh, new Matrix4f(), visibleMaterial, null, 1);
        RenderItem outside = new RenderItem(visibilityMesh,
                new Matrix4f().translation(10, 0, 0), visibleMaterial, null, 2);
        RenderPreparationWorker.Plan plan = RenderPreparationWorker.build(
                Map.of(PassId.OPAQUE, List.of(visible, outside)), new Matrix4f());
        expect("visibility preparation keeps in-frustum draw", plan.items(PassId.OPAQUE).size() == 1);
        expect("visibility preparation counts culled draw", plan.culled() == 1);
    }

    private static void testPackedVertexLayouts() {
        expect("static packed stride", VertexLayout.STATIC.strideBytes() == 32);
        expect("voxel packed stride", VertexLayout.VOXEL.strideBytes() == 32);
        expect("skinned packed stride", VertexLayout.SKINNED.strideBytes() == 40);
        expect("cluster tile sizing rounds up", ClusteredLighting.tilesForPixels(1921) == 31);
        expect("cluster count includes z slices", ClusteredLighting.clusterCountFor(64, 32)
                == 1 * 1 * ClusteredLighting.LIGHT_CLUSTER_Z_SLICES);
        expect("cluster z slices tuned for top-down", ClusteredLighting.LIGHT_CLUSTER_Z_SLICES == 4);
        expect("cluster list capacity reduced", ClusteredLighting.MAX_LIGHTS_PER_CLUSTER == 32);
        expect("direct point light threshold", ClusteredLighting.DIRECT_LIGHT_THRESHOLD == 8);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var voxel = VertexLayout.VOXEL.attributeDescriptions(stack);
            expect("voxel normal packed snorm8", voxel.get(1).format() == VK_FORMAT_R8G8B8A8_SNORM && voxel.get(1).offset() == 12);
            expect("voxel local uv stays float32", voxel.get(2).format() == VK_FORMAT_R32G32_SFLOAT && voxel.get(2).offset() == 16);
            expect("voxel color packed unorm8", voxel.get(3).format() == VK_FORMAT_R8G8B8A8_UNORM && voxel.get(3).offset() == 24);
            expect("voxel foliage half", voxel.get(4).format() == VK_FORMAT_R16_SFLOAT && voxel.get(4).offset() == 28);
            expect("voxel sprite unsigned short", voxel.get(5).format() == VK_FORMAT_R16_UINT && voxel.get(5).offset() == 30);
            var skinned = VertexLayout.SKINNED.attributeDescriptions(stack);
            expect("skinned uv half", skinned.get(2).format() == VK_FORMAT_R16G16_SFLOAT && skinned.get(2).offset() == 16);
            expect("skinned joints unsigned shorts", skinned.get(4).format() == VK_FORMAT_R16G16B16A16_UINT && skinned.get(4).offset() == 24);
            expect("skinned weights unorm8", skinned.get(5).format() == VK_FORMAT_R8G8B8A8_UNORM && skinned.get(5).offset() == 32);
        }
    }

    private static void testShadowConfigurationAndShaders() {
        DirectionalShadowSettings s=DirectionalShadowSettings.validationLab();
        expect("shadow map resolution",s.resolution()==DirectionalShadowSettings.SHADOW_MAP_RESOLUTION);
        boolean rejectedResolution=false;
        try{new DirectionalShadowSettings(true,1024,ShadowFitMode.FIXED_VOLUME,new Vector3f(),8f,.1f,40f,.35f,.75f,.00045f,1,40f,4f,8f);}catch(IllegalArgumentException e){rejectedResolution=true;}
        expect("shadow resolution is renderer-owned",rejectedResolution);
        expect("shadow validation uses fixed fit",s.fitMode()==ShadowFitMode.FIXED_VOLUME);
        expect("shadow validation has lab coverage padding",s.halfExtent()>=9f&&s.farPlane()>=40f);
        expect("shadow PCF radius",s.pcfRadius()==1);
        expect("shadow bias defaults",s.constantBias()==.35f&&s.slopeBias()==.75f&&s.receiverBias()==.00045f);
        DirectionalShadowSettings camera=DirectionalShadowSettings.cameraView();
        expect("camera shadow fit mode",camera.fitMode()==ShadowFitMode.CAMERA_VIEW&&camera.fitDistance()>=35f&&camera.padding()>=3f);
        DirectionalShadowSettings soft=DirectionalShadowSettings.cameraViewSoft();
        expect("soft camera shadows use larger PCF",soft.fitMode()==ShadowFitMode.CAMERA_VIEW&&soft.pcfRadius()>camera.pcfRadius());
        float subTexel=.25f*2f/s.resolution();
        expect("sub-texel light projection snaps",Math.abs(ShadowMap.snapClipOffset(subTexel,s.resolution())+subTexel)<1e-7f);
        expect("whole-texel light projection remains stable",Math.abs(ShadowMap.snapClipOffset(2f/s.resolution(),s.resolution()))<1e-7f);
        expect("world texel snap",Math.abs(ShadowMap.snapToTexel(1.03f,.5f)-1.0f)<1e-7f);
        Matrix4f view=new Matrix4f().lookAt(new Vector3f(6,6,8),new Vector3f(0,0,0),new Vector3f(0,1,0));
        Matrix4f proj=new Matrix4f().perspective((float)Math.toRadians(45),16f/9f,.05f,100f,true);proj.m11(proj.m11()*-1f);
        Matrix4f fit=ShadowMap.cameraFitMatrix(new Vector3f(-1,-1,-1),camera,view,proj);
        for(Vector3f c:ShadowMap.cameraFrustumCorners(view,proj,camera.nearPlane(),camera.fitDistance())){
            Vector4f clip=fit.transform(new Vector4f(c,1));
            float x=clip.x/clip.w,y=clip.y/clip.w,z=clip.z/clip.w;
            expect("camera-fit covers frustum corner",x>=-1.001f&&x<=1.001f&&y>=-1.001f&&y<=1.001f&&z>=-0.001f&&z<=1.001f);
        }
        Matrix4f movedView=new Matrix4f().lookAt(new Vector3f(6.002f,6,8),new Vector3f(.002f,0,0),new Vector3f(0,1,0));
        Matrix4f fitMoved=ShadowMap.cameraFitMatrix(new Vector3f(-1,-1,-1),camera,movedView,proj);
        expect("sub-texel camera fit remains stable",matrixClose(fit,fitMoved,.002f));
        Matrix4f shiftedView=new Matrix4f().lookAt(new Vector3f(7,6,8),new Vector3f(1,0,0),new Vector3f(0,1,0));
        Matrix4f fitShifted=ShadowMap.cameraFitMatrix(new Vector3f(-1,-1,-1),camera,shiftedView,proj);
        expect("larger camera move updates fit",!matrixClose(fit,fitShifted,.002f));
        expect("debug enum bridge",fr.tofuxia.renderapi.DebugViewMode.fromInt(7)==fr.tofuxia.renderapi.DebugViewMode.SHADOW_MAP_DEPTH);
        expect("debug albedo bridge",fr.tofuxia.renderapi.DebugViewMode.fromInt(1)==fr.tofuxia.renderapi.DebugViewMode.ALBEDO);
        try {
            Path root=Path.of("test-fixtures","assets","tofuxia","shaders");
            String globalUbo=Files.readString(root.resolve("common").resolve("global_ubo.glsl"));
            expect("old point-light UBO positions removed", !globalUbo.contains("pointLightPositions"));
            expect("old point-light UBO colors removed", !globalUbo.contains("pointLightColors"));
            expect("cluster params in global UBO", globalUbo.contains("clusterParams"));
            String expanded=ShaderLibrary.preprocess(root,"surface.vert.glsl","#version 450\n#define MAX_JOINTS 96\n");
            expect("shader include expansion",expanded.contains("surfaceModelMatrix")&&!expanded.contains("#include"));
            String surfacePreamble="#version 450\n#define MAX_JOINTS "+ShaderLibrary.MAX_JOINTS+"\n#define FEATURE_STANDARD_LIGHTING\n#define FEATURE_RECEIVE_SHADOWS\n";
            expect("surface fragment shader",ShaderLibrary.compile("surface.frag",ShaderLibrary.preprocess(root,"surface.frag.glsl",surfacePreamble),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader).remaining()>0);
            String cutawayPreamble="#version 450\n#define MAX_JOINTS "+ShaderLibrary.MAX_JOINTS
                    +"\n#define FEATURE_TEXTURED\n#define FEATURE_VERTEX_COLOR\n#define FEATURE_VOXEL_DATA"
                    +"\n#define FEATURE_ALPHA_CUTOUT\n#define FEATURE_TERRAIN_OCCLUSION_CUTAWAY\n";
            expect("terrain occlusion cutaway fragment shader",
                    ShaderLibrary.compile("surface-cutaway.frag",
                            ShaderLibrary.preprocess(root,"surface.frag.glsl",cutawayPreamble),
                            org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader).remaining()>0);
            expect("clustered lighting compute shader",ShaderLibrary.compile("clustered_lighting.comp",ShaderLibrary.preprocess(root,"clustered_lighting.comp.glsl","#version 450\n"),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader).remaining()>0);
            testShaderIncludePragmaOnce();
            String shadowPreamble="#define MAX_JOINTS "+ShaderLibrary.MAX_JOINTS+"\n";
            expect("shadow static shader",ShaderLibrary.compile("shadow.vert",ShaderLibrary.preprocess(root,"shadow.vert.glsl",shadowPreamble),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader).remaining()>0);
            expect("shadow skinned shader",ShaderLibrary.compile("shadow-skinned.vert",ShaderLibrary.preprocess(root,"shadow.vert.glsl",shadowPreamble+"#define SHADOW_SKINNED\n"),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader).remaining()>0);
            expect("shadow voxel foliage shader",ShaderLibrary.compile("shadow-voxel.vert",ShaderLibrary.preprocess(root,"shadow.vert.glsl",shadowPreamble+"#define SHADOW_VOXEL\n"),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader).remaining()>0);
            expect("shadow cutout shader",ShaderLibrary.compile("shadow-cutout.frag",ShaderLibrary.preprocess(root,"shadow_cutout.frag.glsl",""),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader).remaining()>0);
            expect("shadow preview vertex",ShaderLibrary.compile("shadow-preview.vert",ShaderLibrary.preprocess(root,"shadow_preview.vert.glsl",""),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader).remaining()>0);
            expect("shadow preview fragment",ShaderLibrary.compile("shadow-preview.frag",ShaderLibrary.preprocess(root,"shadow_preview.frag.glsl",""),org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader).remaining()>0);
        } catch(IOException|IllegalStateException e){fail("shadow shader compilation: "+e.getMessage());}
    }

    private static void testWorldDayCycleLighting() {
        Environment noon = Environment.worldDayCycle(.25f);
        Environment midnight = Environment.worldDayCycle(.75f);
        Environment sunrise = Environment.worldDayCycle(.00f);
        Environment sunsetEnvironment = Environment.worldDayCycle(.50f);
        expect("world noon uses sun from above", noon.sunDirection().y < -0.9f);
        expect("world midnight uses moon from above", midnight.sunDirection().y < -0.9f);
        expect("world sun source is above at noon", Environment.worldSunSourceDirection(.25f).y > 0.30f);
        expect("world sun source is below at midnight", Environment.worldSunSourceDirection(.75f).y < -0.30f);
        expect("world moon source is below at noon", Environment.worldMoonSourceDirection(.25f).y < -0.30f);
        expect("world moon source is above at midnight", Environment.worldMoonSourceDirection(.75f).y > 0.30f);
        expect("world noon brighter than moon", noon.scaledSunColor().length() > midnight.scaledSunColor().length() * 2.0f);
        expect("world shadow settings stay stable across sun/moon blend",
                midnight.directionalShadows().pcfRadius() == noon.directionalShadows().pcfRadius());
        expect("world moon lighting is colder", midnight.scaledSunColor().z > midnight.scaledSunColor().x);
        expect("world day sun is warm but not overdriven",
                noon.scaledSunColor().x > noon.scaledSunColor().z
                        && noon.scaledSunColor().length() > 1.10f
                        && noon.scaledSunColor().length() < 1.70f);
        expect("world day ambient is readable without washing textures",
                noon.scaledAmbientSky().length() > 0.70f && noon.scaledAmbientSky().length() < 1.05f
                        && noon.scaledAmbientGround().length() > 0.35f);
        expect("world night has readable soft moonlight",
                midnight.scaledSunColor().length() > 0.30f && midnight.scaledSunColor().length() < 0.60f
                        && midnight.scaledAmbientSky().length() > 0.35f);
        expect("world noon color grade is gentle",
                noon.toneExposure() >= 0.98f && noon.toneExposure() <= 1.02f
                        && noon.toneSaturation() <= 1.08f
                        && noon.toneContrast() <= 1.05f
                        && noon.gradeTint().x < 1.04f);
        expect("world night color grade is cool but not crushed",
                midnight.toneExposure() >= 0.90f && midnight.toneExposure() <= 0.98f
                        && midnight.toneSaturation() >= 0.85f
                        && midnight.gradeTint().z > midnight.gradeTint().x);
        expect("world twilight grade stays subtle and warm",
                sunrise.toneContrast() <= 1.02f
                        && sunsetEnvironment.toneSaturation() <= 1.08f
                        && sunrise.gradeTint().x > midnight.gradeTint().x);
        float dawnShadow = projectedShadowX(Environment.worldDayCycle(.05f));
        float morningShadow = projectedShadowX(Environment.worldDayCycle(.15f));
        float noonShadow = projectedShadowX(noon);
        float afternoonShadow = projectedShadowX(Environment.worldDayCycle(.35f));
        float duskShadow = projectedShadowX(Environment.worldDayCycle(.45f));
        expect("world blended day shadow advances from dawn to dusk",
                dawnShadow > noonShadow && noonShadow > duskShadow && morningShadow > afternoonShadow);
        expect("world day shadow is almost overhead at noon", Math.abs(noonShadow) < 0.10f);
        float beforeSunset = projectedShadowX(Environment.worldDayCycle(.49f));
        float sunset = projectedShadowX(Environment.worldDayCycle(.50f));
        float afterSunset = projectedShadowX(Environment.worldDayCycle(.51f));
        expect("world sun/moon blend has no sunset direction jump",
                Math.abs(beforeSunset - sunset) < 0.35f && Math.abs(sunset - afterSunset) < 0.35f);
        for (float phase : new float[]{.05f, .25f, .50f, .75f, .95f}) {
            Vector3f sun = Environment.worldSunDirection(phase);
            Vector3f moon = Environment.worldMoonDirection(phase);
            expect("world moon stays opposite sun horizontally %.2f".formatted(phase),
                    Math.abs(sun.x + moon.x) < 0.0001f
                            && Math.abs(sun.z + moon.z) < 0.0001f
                            && Math.abs(sun.y - moon.y) < 0.0001f);
        }
        Vector3f sunA = celestialSource(Environment.worldSunDirection(.10f));
        Vector3f sunB = celestialSource(Environment.worldSunDirection(.15f));
        Vector3f moonA = celestialSource(Environment.worldMoonDirection(.10f));
        Vector3f moonB = celestialSource(Environment.worldMoonDirection(.15f));
        float sunOrbitStep = sunA.x * sunB.z - sunA.z * sunB.x;
        float moonOrbitStep = moonA.x * moonB.z - moonA.z * moonB.x;
        expect("world moon advances around same orbit direction as sun",
                sunOrbitStep < -0.0001f && moonOrbitStep < -0.0001f);
    }

    private static float projectedShadowX(Environment environment) {
        Vector3f direction = environment.sunDirection();
        return direction.x / -direction.y;
    }

    private static Vector3f celestialSource(Vector3f lightDirection) {
        return new Vector3f(lightDirection).negate();
    }

    private static void testPointLightEnvironment() {
        Environment environment = Environment.warmDay().pointLights(List.of(
                new PointLight(1.0f, 2.0f, 3.0f, 1.0f, 0.5f, 0.25f, 4.0f, 2.0f),
                new PointLight(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f)));
        expect("point light environment filters disabled lights", environment.pointLights().size() == 1);
        expect("point light environment keeps first light radius", environment.pointLights().get(0).radius() == 4.0f);
        expect("point light environment keeps first light intensity", environment.pointLights().get(0).intensity() == 2.0f);
    }

    private static void testShaderIncludePragmaOnce() {
        Path root = FIXTURE_ROOT.resolve("include-once-shaders");
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("a.glsl"), "#pragma once\nfloat sharedValue(){return 1.0;}\n");
            Files.writeString(root.resolve("b.glsl"), "#include \"a.glsl\"\nfloat b(){return sharedValue();}\n");
            Files.writeString(root.resolve("c.glsl"), "#include \"a.glsl\"\nfloat c(){return sharedValue();}\n");
            Files.writeString(root.resolve("main.glsl"), "#version 450\n#include \"b.glsl\"\n#include \"c.glsl\"\nvoid main(){}\n");
            String expanded = ShaderLibrary.preprocess(root, "main.glsl");
            expect("pragma once includes first branch", expanded.contains("float b()") && expanded.contains("float c()"));
            expect("pragma once deduplicates shared include", expanded.indexOf("float sharedValue") == expanded.lastIndexOf("float sharedValue"));
            Files.writeString(root.resolve("cycle_a.glsl"), "#pragma once\n#include \"cycle_b.glsl\"\n");
            Files.writeString(root.resolve("cycle_b.glsl"), "#include \"cycle_a.glsl\"\n");
            boolean cycleFailed = false;
            try { ShaderLibrary.preprocess(root, "cycle_a.glsl"); } catch (IllegalStateException e) { cycleFailed = true; }
            expect("pragma once does not hide active cycles", cycleFailed);
        } catch (IOException e) {
            fail("shader pragma once fixture: " + e.getMessage());
        }
    }

    private static void testDebugTextMeshGeneration() {
        FontAtlas font = new FontAtlas(java.nio.file.Path.of("test-fixtures/assets/tofuxia/fonts/inter-variable.ttf"), 15);
        String glyphProbe = ":?gjpqyA";
        float[] vertices = DebugTextRenderer.buildVertices(font, List.of(glyphProbe), 64);
        int floatsPerVertex = 8;
        int expectedVertices = (glyphProbe.length() + 1) * 6;
        expect("debug text vertex count", vertices.length == expectedVertices * floatsPerVertex);
        int firstGlyph = 6 * floatsPerVertex;
        int secondGlyph = firstGlyph + 6 * floatsPerVertex;
        float colonU = vertices[firstGlyph + 2];
        float questionU = vertices[secondGlyph + 2];
        expect("debug text keeps colon and question UVs distinct", Math.abs(colonU - questionU) > 0.0001f);
        FontAtlas.PositionedGlyph[] layout = font.layout(glyphProbe);
        expect("debug text uses shaped glyph count", layout.length == glyphProbe.length());
        expect("debug text colon shaped visibly", layout[0].glyph().inkWidth() > 0);
        expect("debug punctuation substitutions distinct", layout[0].glyph().glyphIndex() != layout[1].glyph().glyphIndex());
        expect("debug text question shaped correctly", layout[1].glyph().glyphIndex() == font.glyph('?').glyphIndex());
        expect("debug text descender glyph has ink", glyphInk(font, font.glyph('g')) > 0 && glyphInk(font, font.glyph('j')) > 0);
        FontAtlas.GlyphQuad[] quads = font.layoutQuads("Agj", 0, 0);
        expect("debug text descenders extend below cap glyph", quads[1].y1() > quads[0].y1() && quads[2].y1() > quads[0].y1());
    }

    private static int glyphInk(FontAtlas font, FontAtlas.Glyph glyph) {
        int count = 0;
        byte[] pixels = font.pixels();
        for (int y = glyph.y(); y < glyph.y() + glyph.height(); y++)
            for (int x = glyph.x(); x < glyph.x() + glyph.width(); x++)
                if (pixels[y * font.width() + x] != 0) count++;
        return count;
    }

    private static boolean matrixClose(Matrix4f a,Matrix4f b,float epsilon){
        for(int col=0;col<4;col++)for(int row=0;row<4;row++)if(Math.abs(a.get(row,col)-b.get(row,col))>epsilon)return false;
        return true;
    }

    private static void testMaterialFiles() {
        MaterialSystem materials = new MaterialSystem(FIXTURE_ROOT);
        String[] files = {
                "materials/terrain_grass.json", "materials/crate_lit.json", "materials/unlit_marker.json",
                "materials/glass_blue.json", "materials/foliage_card.json", "materials/crystal_emissive.json",
                "materials/particle_spark.json",
        };
        for (String file : files) {
            Material material = materials.load(file);
            expect("material file loads: " + file, material != materials.fallback());
        }
        expect("missing material file falls back",
                materials.load("materials/nope.json") == materials.fallback());
        expect("material lookup by name",
                materials.byName("terrain_grass").has(MaterialFeature.TEXTURED));
    }

    private static void testVariantNaming() {
        ShaderVariantKey lit = new ShaderVariantKey(
                MaterialFeature.TEXTURED.bit() | MaterialFeature.STANDARD_LIGHTING.bit());
        expect("variant name", "SURFACE+TEXTURED+STANDARD_LIGHTING".equals(lit.describe()));
        ShaderVariantKey billboard = new ShaderVariantKey(
                MaterialFeature.BILLBOARD.bit() | MaterialFeature.TEXTURED.bit());
        expect("billboard variant name", "BILLBOARD+TEXTURED".equals(billboard.describe()));
        expect("variant equality", lit.equals(new ShaderVariantKey(lit.featureMask())));
    }

    private static void testPipelineKeys() {
        List<String> warnings = new ArrayList<>();
        Material opaque = Material.builder("a").baseColorTexture("t.png").resolve(warnings::add);
        Material cutout = opaque.toBuilder().blendMode(BlendMode.CUTOUT).resolve(warnings::add);
        PipelineKey staticKey = PipelineKey.of(opaque, VertexLayout.STATIC);
        PipelineKey skinnedKey = PipelineKey.of(opaque, VertexLayout.SKINNED);
        PipelineKey cutoutKey = PipelineKey.of(cutout, VertexLayout.STATIC);
        Material terrainCutaway = opaque.toBuilder()
                .feature(MaterialFeature.TERRAIN_OCCLUSION_CUTAWAY).resolve(warnings::add);
        PipelineKey terrainCutawayKey = PipelineKey.of(terrainCutaway, VertexLayout.VOXEL);
        expect("same material+layout -> same key", staticKey.equals(PipelineKey.of(opaque, VertexLayout.STATIC)));
        expect("skinned layout changes key", !staticKey.equals(skinnedKey));
        expect("skinned layout adds SKINNED feature",
                skinnedKey.variant().features().contains(MaterialFeature.SKINNED));
        expect("blend mode changes key", !staticKey.equals(cutoutKey));
        expect("cutaway feature is voxel-only",
                terrainCutawayKey.variant().features().contains(MaterialFeature.TERRAIN_OCCLUSION_CUTAWAY)
                        && !PipelineKey.of(terrainCutaway, VertexLayout.STATIC).variant().features()
                        .contains(MaterialFeature.TERRAIN_OCCLUSION_CUTAWAY));
        Set<PipelineKey> keys = new HashSet<>(List.of(staticKey, skinnedKey, cutoutKey));
        expect("pipeline keys hash distinctly", keys.size() == 3);
    }

    /** Compiles every variant the demo scene resolves - catches GLSL breakage headlessly. */
    private static void testShaderVariantCompilation() {
        ShaderLibrary library = new ShaderLibrary(FIXTURE_ROOT.resolve("shaders"));
        MaterialSystem materials = new MaterialSystem(FIXTURE_ROOT);
        String[] files = {
                "materials/terrain_grass.json", "materials/crate_lit.json", "materials/unlit_marker.json",
                "materials/glass_blue.json", "materials/foliage_card.json", "materials/crystal_emissive.json",
                "materials/particle_spark.json",
        };
        Set<ShaderVariantKey> resolved = new HashSet<>();
        try {
            for (String file : files) {
                Material material = materials.load(file);
                for (VertexLayout layout : new VertexLayout[]{VertexLayout.STATIC, VertexLayout.SKINNED}) {
                    ShaderVariantKey key = PipelineKey.of(material, layout).variant();
                    ShaderVariant variant = library.variant(key);
                    resolved.add(key);
                    expect("variant has vertex SPIR-V: " + variant.name(), variant.vertexSpirv().remaining() > 0);
                    expect("variant has fragment SPIR-V: " + variant.name(), variant.fragmentSpirv().remaining() > 0);
                }
            }
            ShaderLibrary realShaders = new ShaderLibrary(Path.of("test-fixtures", "assets", "tofuxia", "shaders"));
            Material voxel = Material.builder("voxel_data_test")
                    .baseColorTexture("mem://test")
                    .feature(MaterialFeature.VERTEX_COLOR)
                    .resolve(System.out::println);
            ShaderVariant voxelVariant = realShaders.variant(PipelineKey.of(voxel, VertexLayout.VOXEL).variant());
            expect("real voxel data vertex SPIR-V", voxelVariant.vertexSpirv().remaining() > 0);
            expect("real voxel data fragment SPIR-V", voxelVariant.fragmentSpirv().remaining() > 0);
            Material outline = Material.builder("outline_shader_test")
                    .domain(MaterialDomain.OUTLINE).shadingModel(ShadingModel.UNLIT)
                    .cullMode(CullMode.FRONT).outlineWidthPixels(6f).resolve(System.out::println);
            ShaderVariant outlineVariant = realShaders.variant(PipelineKey.of(outline, VertexLayout.STATIC).variant());
            expect("real entity outline vertex SPIR-V", outlineVariant.vertexSpirv().remaining() > 0);
            expect("real entity outline fragment SPIR-V", outlineVariant.fragmentSpirv().remaining() > 0);
        } catch (IllegalStateException e) {
            fail("shader variant compilation threw: " + e.getMessage());
            return;
        }
        expect("variants deduplicate", library.variantCount() == resolved.size());
        expect("multiple distinct variants compiled", resolved.size() >= 8);
        System.out.println("[renderer-selftest] compiled " + library.variantCount() + " shader variants");
    }

    private static void testBuiltinMeshes() {
        CpuMesh cube = BuiltinMeshes.cube(1.0f);
        expect("cube 24 vertices", cube.vertexCount() == 24);
        expect("cube 12 triangles", cube.triangleCount() == 12);
        CpuMesh plane = BuiltinMeshes.plane(8, 16, 4);
        expect("plane vertex count", plane.vertexCount() == 17 * 17);
        expect("plane triangle count", plane.triangleCount() == 16 * 16 * 2);
        CpuMesh quad = BuiltinMeshes.quad("q");
        expect("quad triangles", quad.triangleCount() == 2);
        CpuMesh crystal = BuiltinMeshes.crystal(0.5f, 1.5f);
        expect("crystal triangles", crystal.triangleCount() == 12);
        boolean threw = false;
        try {
            CpuMesh.staticMesh("bad", new float[12], new int[]{0, 1, 2});
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        expect("mesh index validation", threw);
    }

    /** Generates the sample models, then round-trips them through the importer. */
    private static void testGltfImport() {
        Path samples = FIXTURE_ROOT.resolve(Path.of("models", "samples"));
        fr.tofuxia.gltf.SampleGltfWriter.ensureSampleModels(samples);
        try {
            // GLB container, embedded PNG, node hierarchy
            fr.tofuxia.gltf.ModelData crate = fr.tofuxia.gltf.GltfImporter.load(samples.resolve("crate.glb"));
            expect("crate has 2 nodes", crate.nodes().size() == 2);
            expect("crate root has child", crate.nodes().get(0).children().length == 1);
            expect("crate child carries the mesh", crate.nodes().get(1).hasMesh());
            expect("crate child translated up", Math.abs(crate.nodes().get(1).translation().y - 0.5f) < 1e-5f);
            expect("crate has 1 primitive", crate.totalPrimitives() == 1);
            expect("crate primitive is STATIC layout",
                    crate.meshes().get(0).primitives().get(0).mesh().layout() == VertexLayout.STATIC);
            Material crateMaterial = crate.materials().get(0);
            expect("crate material TEXTURED", crateMaterial.has(MaterialFeature.TEXTURED));
            expect("crate texture is embedded (mem://)",
                    crateMaterial.baseColorTexture() != null && crateMaterial.baseColorTexture().startsWith("mem://"));
            expect("crate embedded image extracted", crate.embeddedImages().size() == 1);
            expect("crate sampler NEAREST",
                    crateMaterial.sampler().filter() == SamplerSettings.Filter.NEAREST);

            // .gltf with external buffer, no normals, BLEND + doubleSided + emissive
            fr.tofuxia.gltf.ModelData gem = fr.tofuxia.gltf.GltfImporter.load(samples.resolve("gem.gltf"));
            Material gemMaterial = gem.materials().get(0);
            expect("gem alphaMode BLEND -> transparent", gemMaterial.blendMode() == BlendMode.TRANSPARENT);
            expect("gem transparent pass", gemMaterial.pass() == PassId.TRANSPARENT);
            expect("gem doubleSided -> cull NONE", gemMaterial.cullMode() == CullMode.NONE);
            expect("gem emissiveFactor -> EMISSIVE", gemMaterial.has(MaterialFeature.EMISSIVE));
            expect("gem missing normals diagnosed",
                    gem.diagnostics().stream().anyMatch(d -> d.contains("NORMAL")));
            CpuMesh gemMesh = gem.meshes().get(0).primitives().get(0).mesh();
            float normalLength = (float) Math.sqrt(gemMesh.normalX(0) * gemMesh.normalX(0)
                    + gemMesh.normalY(0) * gemMesh.normalY(0) + gemMesh.normalZ(0) * gemMesh.normalZ(0));
            expect("gem fallback normals are unit length", Math.abs(normalLength - 1.0f) < 1e-3f);

            // skinned + animated GLB
            fr.tofuxia.gltf.ModelData walker = fr.tofuxia.gltf.GltfImporter.load(samples.resolve("tofu_walker.glb"));
            expect("walker has 1 skin with 3 joints",
                    walker.skins().size() == 1 && walker.skins().get(0).jointCount() == 3);
            expect("walker has inverse bind matrices",
                    walker.skins().get(0).inverseBindMatrices()[1].m31() < -0.5f);
            expect("walker primitive is SKINNED layout",
                    walker.meshes().get(0).primitives().get(0).mesh().layout() == VertexLayout.SKINNED);
            expect("walker material gains VERTEX_COLOR from COLOR_0",
                    walker.materials().get(0).has(MaterialFeature.VERTEX_COLOR));
            expect("walker has 2 animations", walker.animations().size() == 2);
            fr.tofuxia.gltf.AnimationClip sway = walker.animations().get(0);
            expect("sway has 4 channels", sway.channels().size() == 4);
            expect("sway duration 2s", Math.abs(sway.duration() - 2.0f) < 1e-5f);
            CpuMesh walkerMesh = walker.meshes().get(0).primitives().get(0).mesh();
            float weightSum = walkerMesh.weight(0, 0) + walkerMesh.weight(0, 1)
                    + walkerMesh.weight(0, 2) + walkerMesh.weight(0, 3);
            expect("walker weights normalized", Math.abs(weightSum - 1.0f) < 0.012f);
            expect("walker no unexpected diagnostics", walker.diagnostics().isEmpty());
        } catch (java.io.IOException e) {
            fail("glTF import threw: " + e.getMessage());
        }
    }

    /** Pose sampling, looping, STEP interpolation and instance independence. */
    private static void testAnimationRuntime() {
        Path samples = FIXTURE_ROOT.resolve(Path.of("models", "samples"));
        try {
            fr.tofuxia.gltf.ModelData walker = fr.tofuxia.gltf.GltfImporter.load(samples.resolve("tofu_walker.glb"));
            var nodes = walker.nodes();
            int[] roots = walker.rootNodes();

            fr.tofuxia.gltf.AnimationPlayer player = new fr.tofuxia.gltf.AnimationPlayer(walker.animations());
            fr.tofuxia.gltf.SkeletonPose pose = new fr.tofuxia.gltf.SkeletonPose(nodes.size());
            expect("play by name", player.play("sway"));
            expect("play by bad name fails", !player.play("does_not_exist"));

            // bind pose: joint matrices must be identity
            pose.reset(nodes);
            pose.computeWorldMatrices(nodes, roots);
            org.joml.Matrix4f[] joints = {new org.joml.Matrix4f(), new org.joml.Matrix4f(), new org.joml.Matrix4f()};
            int meshNode = 3;
            pose.jointMatrices(walker.skins().get(0), meshNode, joints);
            expect("bind pose joint matrices are identity",
                    joints[1].equals(new org.joml.Matrix4f(), 1e-5f) && joints[2].equals(new org.joml.Matrix4f(), 1e-5f));

            // sample at t=0.5: mid joint leans +0.30 rad around Z (keyframe hit)
            player.setTime(0.5f);
            pose.reset(nodes);
            player.apply(pose);
            float expectedZ = (float) Math.sin(0.30f * 0.5);
            expect("rotation channel sampled at keyframe",
                    Math.abs(pose.rotation(1).z - expectedZ) < 1e-4f);
            // linear interpolation halfway between -0.3 and +0.3 is identity-ish
            player.setTime(0.25f);
            pose.reset(nodes);
            player.apply(pose);
            expect("rotation channel interpolates", Math.abs(pose.rotation(1).z) < 0.02f);

            // STEP scale: before 0.9 -> 1.0, after -> 1.18
            player.setTime(0.5f);
            pose.reset(nodes);
            player.apply(pose);
            expect("STEP holds previous key", Math.abs(pose.scale(2).x - 1.0f) < 1e-5f);
            player.setTime(1.0f);
            pose.reset(nodes);
            player.apply(pose);
            expect("STEP jumps at key", Math.abs(pose.scale(2).x - 1.18f) < 1e-5f);

            // translation channel bobs the root at t=0.5
            player.setTime(0.5f);
            pose.reset(nodes);
            player.apply(pose);
            expect("translation channel sampled", Math.abs(pose.translation(0).y - 0.08f) < 1e-4f);

            // looping wraps
            player.setTime(0.0f);
            player.update(2.3f);
            expect("looping playback wraps time", Math.abs(player.time() - 0.3f) < 1e-3f);

            // two players over one asset stay independent
            fr.tofuxia.gltf.AnimationPlayer playerB = new fr.tofuxia.gltf.AnimationPlayer(walker.animations());
            fr.tofuxia.gltf.SkeletonPose poseB = new fr.tofuxia.gltf.SkeletonPose(nodes.size());
            playerB.play("sway");
            playerB.setTime(1.5f);
            pose.reset(nodes);
            player.apply(pose);
            pose.computeWorldMatrices(nodes, roots);
            poseB.reset(nodes);
            playerB.apply(poseB);
            poseB.computeWorldMatrices(nodes, roots);
            expect("instances animate independently",
                    !pose.worldMatrix(2).equals(poseB.worldMatrix(2), 1e-4f));
        } catch (java.io.IOException e) {
            fail("animation runtime setup threw: " + e.getMessage());
        }
    }

    static void expect(String what, boolean condition) {
        if (condition) {
            System.out.println("  ok  " + what);
        } else {
            fail(what);
        }
    }

    static void fail(String what) {
        failures++;
        System.err.println("  FAIL " + what);
    }

    private static void prepareFixtureAssets() {
        try {
            Path materials = FIXTURE_ROOT.resolve("materials");
            Path shaders = FIXTURE_ROOT.resolve("shaders");
            Files.createDirectories(materials);
            Files.createDirectories(shaders);
            writeMaterial("terrain_grass.json", """
                    {"name":"terrain_grass","textures":{"baseColor":"textures/terrain_grass.png"}}
                    """);
            writeMaterial("crate_lit.json", """
                    {"name":"crate_lit","textures":{"baseColor":"textures/crate.png"}}
                    """);
            writeMaterial("unlit_marker.json", """
                    {"name":"unlit_marker","shadingModel":"unlit","features":["VERTEX_COLOR"]}
                    """);
            writeMaterial("glass_blue.json", """
                    {"name":"glass_blue","blendMode":"transparent","properties":{"baseColorFactor":[0.3,0.6,1.0,0.45]}}
                    """);
            writeMaterial("foliage_card.json", """
                    {"name":"foliage_card","blendMode":"cutout","features":["VERTEX_COLOR"],"textures":{"baseColor":"textures/foliage.png"}}
                    """);
            writeMaterial("crystal_emissive.json", """
                    {"name":"crystal_emissive","properties":{"emissiveColor":[0.4,0.8,1.0],"emissiveStrength":1.5}}
                    """);
            writeMaterial("particle_spark.json", """
                    {"name":"particle_spark","domain":"billboard","blendMode":"additive","textures":{"baseColor":"textures/spark.png"}}
                    """);
            Files.writeString(shaders.resolve("surface.vert.glsl"), """
                    layout(location = 0) in vec3 inPosition;
                    layout(location = 1) in vec3 inNormal;
                    layout(location = 2) in vec2 inUv;
                    layout(location = 3) in vec4 inColor;
                    #ifdef FEATURE_SKINNED
                    layout(location = 4) in vec4 inJoints;
                    layout(location = 5) in vec4 inWeights;
                    #endif
                    layout(location = 0) out vec2 vUv;
                    layout(location = 1) out vec4 vColor;
                    void main() {
                        vUv = inUv;
                        vColor = inColor;
                        gl_Position = vec4(inPosition, 1.0);
                    }
                    """);
            Files.writeString(shaders.resolve("surface.frag.glsl"), """
                    layout(location = 0) in vec2 vUv;
                    layout(location = 1) in vec4 vColor;
                    layout(location = 0) out vec4 outColor;
                    void main() {
                        outColor = vColor;
                    }
                    """);
        } catch (IOException e) {
            fail("prepare renderer fixture assets: " + e.getMessage());
        }
    }

    private static void writeMaterial(String fileName, String json) throws IOException {
        Files.writeString(FIXTURE_ROOT.resolve("materials").resolve(fileName), json);
    }
}
