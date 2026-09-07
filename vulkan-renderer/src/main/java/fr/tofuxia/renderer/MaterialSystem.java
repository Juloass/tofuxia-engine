package fr.tofuxia.renderer;

import fr.tofuxia.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads material definitions from JSON files under the asset root, caches
 * them by normalized path, and hands out a clearly visible fallback material
 * when a file is missing or broken. Also the registration point for
 * materials created in code (built-in demo materials, glTF imports).
 */
public final class MaterialSystem {
    private final Path assetRoot;
    private final RendererResources resources;
    private final Map<String, Material> byPath = new LinkedHashMap<>();
    private final Map<String, Material> byName = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final Material fallback;

    public MaterialSystem(Path assetRoot) {
        this.assetRoot = assetRoot;
        this.resources = null;
        this.fallback = Material.builder("missing_material")
                .shadingModel(ShadingModel.UNLIT)
                .baseColorFactor(1.0f, 0.0f, 0.8f, 1.0f)
                .resolve(warnings::add);
        byName.put(fallback.name(), fallback);
    }

    MaterialSystem(RendererResources resources) {
        this.assetRoot = null;
        this.resources = java.util.Objects.requireNonNull(
                resources, "resources");
        this.fallback = Material.builder("missing_material")
                .shadingModel(ShadingModel.UNLIT)
                .baseColorFactor(1.0f, 0.0f, 0.8f, 1.0f)
                .resolve(warnings::add);
        byName.put(fallback.name(), fallback);
    }

    /** Loads (or returns cached) material from a JSON file, e.g. "materials/grass.json". */
    public Material load(String assetPath) {
        String key = normalize(assetPath);
        Material cached = byPath.get(key);
        if (cached != null) return cached;
        Material material = parseFile(key);
        byPath.put(key, material);
        byName.put(material.name(), material);
        return material;
    }

    /** Registers a code-created material so debug tools can find it by name. */
    public Material register(Material material) {
        Material existing = byName.putIfAbsent(material.name(), material);
        if (existing != null && existing != material) {
            warn("duplicate material name '" + material.name() + "'; keeping first registration");
            return existing;
        }
        return material;
    }

    public Material byName(String name) {
        return byName.getOrDefault(name, fallback);
    }

    public Material fallback() {
        return fallback;
    }

    public int count() {
        return byName.size();
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** Drops file-loaded materials so the next load() re-reads from disk. */
    public void reload() {
        List<String> paths = new ArrayList<>(byPath.keySet());
        byPath.clear();
        for (String path : paths) {
            load(path);
        }
        System.out.println("[materials] reloaded " + paths.size() + " material file(s)");
    }

    private Material parseFile(String assetPath) {
        try {
            if (resources != null) {
                if (!resources.contains(assetPath)) {
                    warn("material file missing: " + assetPath
                            + " -> fallback");
                    return fallback;
                }
                return parse(resources.readString(assetPath),
                        defaultName(assetPath));
            }
            Path file = assetRoot.resolve(assetPath);
            if (!Files.exists(file)) {
                warn("material file missing: " + assetPath
                        + " -> fallback");
                return fallback;
            }
            return parse(Files.readString(file), defaultName(assetPath));
        } catch (IOException | RuntimeException e) {
            warn("material file broken: " + assetPath + " ("
                    + e.getMessage() + ") -> fallback");
            return fallback;
        }
    }
    /** Parses a material definition JSON document. */
    public Material parse(String jsonText, String fallbackName) {
        Json.JsonObject json = Json.parseObject(jsonText);
        Material.Builder builder = Material.builder(json.getString("name", fallbackName))
                .domain(MaterialDomain.parse(json.getString("domain", null), MaterialDomain.SURFACE))
                .blendMode(BlendMode.parse(json.getString("blendMode", null), BlendMode.OPAQUE))
                .cullMode(CullMode.parse(json.getString("cullMode", null), CullMode.BACK))
                .shadingModel(ShadingModel.parse(json.getString("shadingModel", null), ShadingModel.STANDARD_LIT));

        Json.JsonObject textures = json.getObject("textures");
        if (textures != null) {
            String baseColor = textures.getString("baseColor", null);
            if (baseColor != null && !baseColor.isBlank()) {
                builder.baseColorTexture(baseColor);
            }
        }
        Json.JsonObject samplerJson = json.getObject("sampler");
        if (samplerJson != null) {
            builder.sampler(SamplerSettings.parse(
                    samplerJson.getString("filter", null), samplerJson.getString("wrap", null)));
        }
        Json.JsonObject properties = json.getObject("properties");
        if (properties != null) {
            float[] baseColorFactor = properties.getFloats("baseColorFactor", new float[]{1, 1, 1, 1});
            if (baseColorFactor.length == 4) {
                builder.baseColorFactor(baseColorFactor[0], baseColorFactor[1], baseColorFactor[2], baseColorFactor[3]);
            }
            float[] emissiveColor = properties.getFloats("emissiveColor", new float[]{1, 1, 1});
            float emissiveStrength = (float) properties.getDouble("emissiveStrength", 0.0);
            if (emissiveColor.length == 3) {
                builder.emissive(emissiveColor[0], emissiveColor[1], emissiveColor[2], emissiveStrength);
            }
            builder.alphaCutoff((float) properties.getDouble("alphaCutoff", 0.5));
        }
        Json.JsonArray features = json.getArray("features");
        if (features != null) {
            for (int i = 0; i < features.size(); i++) {
                String featureName = features.getString(i);
                try {
                    builder.feature(MaterialFeature.parse(featureName));
                } catch (IllegalArgumentException e) {
                    warn(fallbackName + ": unknown material feature '" + featureName + "' ignored");
                }
            }
        }
        return builder.resolve(this::warn);
    }

    private void warn(String message) {
        warnings.add(message);
        System.out.println("[materials] WARN " + message);
    }

    private static String normalize(String assetPath) {
        return assetPath.replace('\\', '/');
    }

    private static String defaultName(String assetPath) {
        String name = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
