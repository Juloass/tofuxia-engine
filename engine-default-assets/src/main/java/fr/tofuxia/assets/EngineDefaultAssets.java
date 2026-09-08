package fr.tofuxia.assets;

import io.github.juloass.identifier.Identifier;
import io.github.juloass.resource.ResourceDomain;
import io.github.juloass.resource.ResourcePath;
import io.github.juloass.resource.pack.PackMetadata;
import io.github.juloass.resource.pack.ResourcePackCandidate;
import io.github.juloass.resource.source.generated.GeneratedPackResources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Built-in, lowest-priority assets required by every desktop engine client. */
public final class EngineDefaultAssets {
    private static final String ROOT = "/engine-default-assets/";
    private EngineDefaultAssets() { }

    public static ResourcePackCandidate resourcePack() {
        Identifier id = Identifier.parse("engine:defaults");
        var resources = GeneratedPackResources.builder(Identifier.parse("engine:classpath"));
        for (String entry : read("index.txt").split("\\R")) {
            if (entry.isBlank() || entry.startsWith("#")) continue;
            String[] path = entry.split("/", 2);
            if (path.length != 2) throw new IllegalStateException("Invalid engine asset path: " + entry);
            resources.add(ResourcePath.of(ResourceDomain.ASSETS, path[0], path[1]), readBytes(entry));
        }
        return new ResourcePackCandidate(id, new PackMetadata("Generic engine defaults", 1), resources.build());
    }

    private static String read(String name) {
        return new String(readBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String name) {
        try (InputStream input = EngineDefaultAssets.class.getResourceAsStream(ROOT + name)) {
            if (input == null) throw new IllegalStateException("Missing built-in engine asset: " + name);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read built-in engine asset: " + name, failure);
        }
    }
}
