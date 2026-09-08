# Tofuxia Engine

Reusable Java 21 game-client modules extracted from the proven Tofuxia desktop client. Applications may run on newer Java versions; published bytecode remains Java 21 compatible.

## Modules

- `engine-core`: backend-neutral render values, fonts, and entity attributes.
- `render-api`: renderer-facing scene and viewport contracts.
- `engine-ui`: retained UI composition primitives.
- `vulkan-renderer`: LWJGL Vulkan implementation, materials, meshes, glTF, lighting, and shadows.
- `engine-app`: game module, scene, loading, input, and application-loop contracts.
- `engine-desktop`: GLFW/OpenAL desktop host with an optional game-provided network integration.
- `engine-default-assets`: generic translations, Inter fallback fonts, shaders, and the Tofuxia Studios startup mark.

Every module is published separately under `io.github.juloass.tofuxia-engine`. The repository remains a monorepo because API changes across rendering, application, and desktop seams must be tested and released atomically.

```groovy
repositories {
    maven { url = uri('https://raw.githubusercontent.com/Juloass/maven/main/releases') }
    mavenCentral()
}

dependencies {
    implementation 'io.github.juloass.tofuxia-engine:engine-desktop:0.3.0'
    runtimeOnly 'org.lwjgl:lwjgl::natives-windows'
    runtimeOnly 'org.lwjgl:lwjgl-freetype::natives-windows'
    runtimeOnly 'org.lwjgl:lwjgl-glfw::natives-windows'
    runtimeOnly 'org.lwjgl:lwjgl-harfbuzz::natives-windows'
    runtimeOnly 'org.lwjgl:lwjgl-openal::natives-windows'
    runtimeOnly 'org.lwjgl:lwjgl-shaderc::natives-windows'
}
```

`engine-desktop` is offline by default. A networked game supplies a `DesktopNetworkFactory`; the engine contains no game protocol or server dependency.
Applications select their own LWJGL native classifiers; replace `natives-windows` for Linux, macOS, or ARM targets.

## Validation

```text
./gradlew clean check publishToMavenLocal
```

The reusable modules must never depend on a game module. Native classifiers are selected from the host operating system and architecture.

No public license has been granted.
