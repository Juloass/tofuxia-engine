package fr.tofuxia.renderer;

import io.github.juloass.resource.ResourceDomain;
import io.github.juloass.resource.ResourcePath;
import io.github.juloass.resource.pack.ResourceSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Repeatable shader text source independent from shader compilation. */
interface ShaderSources {
    String read(String relativePath);

    static ShaderSources directory(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        return relativePath -> {
            Path file = normalized.resolve(relativePath).normalize();
            if (!file.startsWith(normalized)) {
                throw new IllegalStateException(
                        "Shader path escapes shader root: " + relativePath);
            }
            try {
                return Files.readString(file);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Shader source missing or unreadable: " + file,
                        failure);
            }
        };
    }

    static ShaderSources resources(
            ResourceSnapshot snapshot,
            String namespace,
            String prefix
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        String root = prefix.endsWith("/")
                ? prefix.substring(0, prefix.length() - 1)
                : prefix;
        return relativePath -> {
            String path = root + "/" + relativePath.replace('\\', '/');
            try {
                return snapshot.requireResource(ResourcePath.of(ResourceDomain.ASSETS, namespace, path))
                        .readUtf8(16L * 1024L * 1024L);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "Shader source is unreadable: "
                                + namespace + ":" + path,
                        failure);
            }
        };
    }
}