package fr.tofuxia.renderer;

import io.github.juloass.resource.ResourceDomain;
import io.github.juloass.resource.ResourcePath;
import io.github.juloass.resource.pack.ResourceSnapshot;

import java.io.IOException;
import java.util.Objects;

/** Renderer adapter over one generation-pinned resource snapshot. */
final class RendererResources {
    private final ResourceSnapshot snapshot;
    private final String defaultNamespace;

    RendererResources(ResourceSnapshot snapshot, String defaultNamespace) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.defaultNamespace = Objects.requireNonNull(
                defaultNamespace, "defaultNamespace");
    }

    boolean contains(String assetPath) {
        return snapshot.findResource(path(assetPath)).isPresent();
    }

    byte[] readBytes(String assetPath) throws IOException {
        return snapshot.requireResource(path(assetPath))
                .readAllBytes(256L * 1024L * 1024L);
    }

    String readString(String assetPath) throws IOException {
        return snapshot.requireResource(path(assetPath))
                .readUtf8(16L * 1024L * 1024L);
    }

    private ResourcePath path(String value) {
        String normalized = value.replace('\\', '/');
        int colon = normalized.indexOf(':');
        if (colon >= 0) return ResourcePath.parse(ResourceDomain.ASSETS, normalized);
        int slash = normalized.indexOf('/');
        if (slash > 0) {
            String possibleNamespace = normalized.substring(0, slash);
            if (snapshot.namespaces().contains(possibleNamespace)) {
                return ResourcePath.of(
                        ResourceDomain.ASSETS,
                        possibleNamespace,
                        normalized.substring(slash + 1));
            }
        }
        return ResourcePath.of(ResourceDomain.ASSETS, defaultNamespace, normalized);
    }
}