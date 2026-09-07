package fr.tofuxia.render;

import io.github.juloass.resource.ResourcePath;
import io.github.juloass.resource.pack.ResourceSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Loads font roles and lazily caches rasterized atlases. */
public final class FontCatalog implements AutoCloseable {
    private final Map<FontRole, Path> paths;
    private final Map<FontRole, List<FontAtlas.FontSource>> families;
    private final Map<AtlasKey, FontAtlas> atlases = new HashMap<>();
    private boolean closed;

    private FontCatalog(
            Map<FontRole, Path> paths,
            Map<FontRole, List<FontAtlas.FontSource>> families
    ) {
        this.paths = Map.copyOf(paths);
        this.families = Map.copyOf(families);
    }

    public static FontCatalog load(Path assetRoot, Path manifestPath) {
        Path root = assetRoot.toAbsolutePath().normalize();
        Path manifest = root.resolve(manifestPath).normalize();
        requireInside(root, manifest, "Font manifest");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(manifest)) {
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Failed to load font manifest " + manifest, failure);
        }
        return build(properties, role -> {
            String configured = required(properties, role, manifest.toString());
            Path path = manifest.getParent().resolve(configured).normalize();
            requireInside(root, path, "Font role '" + role.key() + "'");
            return source(path);
        }, role -> {
            ArrayList<FontAtlas.FontSource> values = new ArrayList<>();
            String configured = properties.getProperty(
                    role.key() + ".fallback", "");
            for (String fallback : configured.split(",")) {
                if (fallback.isBlank()) continue;
                Path path = manifest.getParent().resolve(fallback.trim())
                        .normalize();
                requireInside(root, path,
                        "Font fallback for role '" + role.key() + "'");
                values.add(source(path));
            }
            return values;
        });
    }

    public static FontCatalog load(
            ResourceSnapshot assets,
            ResourcePath manifest
    ) {
        Properties properties = new Properties();
        try (InputStream input = assets.requireResource(manifest).openStream()) {
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Failed to load font manifest " + manifest, failure);
        }
        String directory = directory(manifest.path());
        return build(properties, role -> resourceSource(
                        assets,
                        ResourcePath.of(
                                manifest.domain(),
                                manifest.namespace(),
                                directory + required(
                                        properties, role, manifest.value()))),
                role -> {
                    ArrayList<FontAtlas.FontSource> values = new ArrayList<>();
                    String configured = properties.getProperty(
                            role.key() + ".fallback", "");
                    for (String fallback : configured.split(",")) {
                        if (fallback.isBlank()) continue;
                        values.add(resourceSource(
                                assets,
                                ResourcePath.of(
                                        manifest.domain(),
                                        manifest.namespace(),
                                        directory + fallback.trim())));
                    }
                    return values;
                });
    }

    private static FontCatalog build(
            Properties properties,
            java.util.function.Function<FontRole, FontAtlas.FontSource> primary,
            java.util.function.Function<FontRole, List<FontAtlas.FontSource>> fallbacks
    ) {
        for (String key : properties.stringPropertyNames()) {
            FontRole.fromKey(key.endsWith(".fallback")
                    ? key.substring(0, key.length() - ".fallback".length())
                    : key);
        }
        EnumMap<FontRole, Path> paths = new EnumMap<>(FontRole.class);
        EnumMap<FontRole, List<FontAtlas.FontSource>> families =
                new EnumMap<>(FontRole.class);
        for (FontRole role : FontRole.values()) {
            FontAtlas.FontSource main = primary.apply(role);
            ArrayList<FontAtlas.FontSource> family = new ArrayList<>();
            family.add(main);
            for (FontAtlas.FontSource fallback : fallbacks.apply(role)) {
                if (family.stream().noneMatch(value ->
                        value.source().equals(fallback.source()))) {
                    family.add(fallback);
                }
            }
            paths.put(role, main.source());
            families.put(role, List.copyOf(family));
            System.out.println("[fonts] " + role.key() + "="
                    + main.source());
        }
        return new FontCatalog(paths, families);
    }

    private static String required(
            Properties properties,
            FontRole role,
            String manifest
    ) {
        String value = properties.getProperty(role.key());
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Font manifest " + manifest + " is missing role '"
                            + role.key() + "'");
        }
        return value.trim();
    }

    private static FontAtlas.FontSource source(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        "Font source is not a file: " + path);
            }
            return new FontAtlas.FontSource(
                    path.toAbsolutePath().normalize(),
                    Files.readAllBytes(path));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read font source " + path, failure);
        }
    }

    private static FontAtlas.FontSource resourceSource(
            ResourceSnapshot assets,
            ResourcePath path
    ) {
        try {
            return new FontAtlas.FontSource(
                    // ResourcePath is a logical ID. Its colon is not a valid
                    // Windows path character. FontSource uses this path only
                    // as a stable source and cache identity.
                    Path.of(path.domain().directory(), path.namespace(), path.path()),
                    assets.requireResource(path)
                            .readAllBytes(64L * 1024L * 1024L));
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot read font source " + path, failure);
        }
    }

    private static String directory(String path) {
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "" : path.substring(0, separator + 1);
    }

    public synchronized FontAtlas atlas(FontRole role, float fontSizePx) {
        if (closed) throw new IllegalStateException("Font catalog is closed");
        List<FontAtlas.FontSource> family = families.get(role);
        if (family == null) {
            throw new IllegalArgumentException(
                    "Unconfigured font role " + role);
        }
        int pixelSize = Math.max(1, Math.round(fontSizePx));
        return atlases.computeIfAbsent(
                new AtlasKey(
                        family.stream().map(FontAtlas.FontSource::source)
                                .toList(),
                        pixelSize),
                key -> FontAtlas.fromSources(family, key.pixelSize()));
    }

    public Path path(FontRole role) {
        return paths.get(role);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        atlases.values().forEach(FontAtlas::close);
        atlases.clear();
        closed = true;
    }

    private static void requireInside(Path root, Path path, String label) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException(
                    label + " escapes asset root " + root + ": " + path);
        }
    }

    private record AtlasKey(List<Path> paths, int pixelSize) {
    }
}
