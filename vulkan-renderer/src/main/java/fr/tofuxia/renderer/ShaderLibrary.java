package fr.tofuxia.renderer;

import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * Turns material feature sets into compiled shader variants. There is one
 * uber vertex/fragment source pair on disk; this class preprocesses it
 * (feature #defines + #include chunk resolution) and compiles each distinct
 * feature combination exactly once. Reload() drops the cache so edited
 * shader files take effect without restarting.
 */
public final class ShaderLibrary implements AutoCloseable {
    /** Upper bound for one skin's joint palette; also sized into the joint UBO. */
    public static final int MAX_JOINTS = 96;

    private static final String VERTEX_SOURCE = "surface.vert.glsl";
    private static final String FRAGMENT_SOURCE = "surface.frag.glsl";
    private static final int MAX_INCLUDE_DEPTH = 8;

    private final ShaderSources sources;
    private final Map<ShaderVariantKey, ShaderVariant> variants = new LinkedHashMap<>();
    private int nextVariantId = 1;
    private int compileCount;

    public ShaderLibrary(Path shaderRoot) {
        this(ShaderSources.directory(shaderRoot));
    }

    ShaderLibrary(ShaderSources sources) {
        this.sources = java.util.Objects.requireNonNull(sources, "sources");
    }

    public ShaderVariant variant(ShaderVariantKey key) {
        ShaderVariant cached = variants.get(key);
        if (cached != null) return cached;
        ShaderVariant compiled = compileVariant(key);
        variants.put(key, compiled);
        return compiled;
    }

    public int variantCount() {
        return variants.size();
    }

    public int compileCount() {
        return compileCount;
    }

    public Iterable<ShaderVariant> variants() {
        return variants.values();
    }

    /** Forgets compiled variants; next use recompiles from the files on disk. */
    public void reload() {
        int dropped = variants.size();
        variants.clear();
        System.out.println("[shaders] dropped " + dropped + " compiled variant(s); recompiling on demand");
    }

    private ShaderVariant compileVariant(ShaderVariantKey key) {
        String name = key.describe();
        String preamble = buildPreamble(key);
        String vertexSource = preprocess(sources, VERTEX_SOURCE, preamble);
        String fragmentSource = preprocess(sources, FRAGMENT_SOURCE, preamble);
        long start = System.nanoTime();
        ByteBuffer vertexSpirv = compile(name + ".vert", vertexSource, shaderc_glsl_vertex_shader);
        ByteBuffer fragmentSpirv = compile(name + ".frag", fragmentSource, shaderc_glsl_fragment_shader);
        compileCount++;
        ShaderVariant variant = new ShaderVariant(nextVariantId++, key, name, vertexSpirv, fragmentSpirv);
        System.out.printf("[shaders] compiled variant #%d %s (%.1f ms)%n",
                variant.id(), name, (System.nanoTime() - start) / 1_000_000.0);
        return variant;
    }

    private String buildPreamble(ShaderVariantKey key) {
        StringBuilder out = new StringBuilder("#version 450\n");
        out.append("#define MAX_JOINTS ").append(MAX_JOINTS).append('\n');
        for (MaterialFeature feature : key.features()) {
            out.append("#define FEATURE_").append(feature.name()).append('\n');
        }
        return out.toString();
    }

    /**
     * Preprocesses a GLSL file for shaderc. Includes stay relative to the
     * including file and cannot leave the configured shader root.
     */
    public static String preprocess(
            Path shaderRoot,
            String relativePath,
            String generatedPreamble
    ) {
        return preprocess(
                ShaderSources.directory(shaderRoot),
                relativePath,
                generatedPreamble);
    }

    static String preprocess(
            ShaderSources sources,
            String relativePath,
            String generatedPreamble
    ) {
        String expanded = resolveIncludes(
                sources,
                normalizeRelative(relativePath),
                0,
                new HashSet<>(),
                new HashSet<>());
        if (generatedPreamble == null || generatedPreamble.isEmpty()) {
            return expanded;
        }
        if (expanded.startsWith("#version")) {
            int lineEnd = expanded.indexOf('\n');
            if (lineEnd < 0) return expanded + '\n' + generatedPreamble;
            return expanded.substring(0, lineEnd + 1)
                    + generatedPreamble
                    + expanded.substring(lineEnd + 1);
        }
        return generatedPreamble + expanded;
    }

    public static String preprocess(Path shaderRoot, String relativePath) {
        return preprocess(shaderRoot, relativePath, "");
    }

    private static String resolveIncludes(
            ShaderSources sources,
            String relativePath,
            int depth,
            Set<String> stack,
            Set<String> onceIncluded
    ) {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IllegalStateException(
                    "Shader include depth exceeded in " + relativePath
                            + " (cycle?)");
        }
        String normalized = normalizeRelative(relativePath);
        if (onceIncluded.contains(normalized)) return "";
        if (!stack.add(normalized)) {
            throw new IllegalStateException(
                    "Shader include cycle detected at " + normalized);
        }
        String source = sources.read(normalized);
        boolean pragmaOnce = false;
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals("#pragma once")) {
                pragmaOnce = true;
            } else if (trimmed.startsWith("#include")) {
                int first = trimmed.indexOf('"');
                int last = trimmed.lastIndexOf('"');
                if (first < 0 || last <= first) {
                    throw new IllegalStateException(
                            "Malformed #include in " + normalized + ": "
                                    + trimmed);
                }
                String includeName = trimmed.substring(first + 1, last);
                int separator = normalized.lastIndexOf('/');
                String parent = separator < 0
                        ? ""
                        : normalized.substring(0, separator + 1);
                String include = normalizeRelative(parent + includeName);
                out.append("// begin include ").append(include).append('\n');
                out.append(resolveIncludes(
                        sources,
                        include,
                        depth + 1,
                        stack,
                        onceIncluded));
                out.append("\n// end include ").append(include).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        stack.remove(normalized);
        if (pragmaOnce) onceIncluded.add(normalized);
        return out.toString();
    }

    private static String normalizeRelative(String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) {
            throw new IllegalStateException(
                    "Invalid shader path: " + value);
        }
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalStateException(
                            "Shader include escapes shader root: " + value);
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("Empty shader path");
        }
        return String.join("/", segments);
    }
    static ByteBuffer compile(String name, String source, int kind) {
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new IllegalStateException("shaderc compiler initialization failed.");
        }
        long result = shaderc_compile_into_spv(compiler, source, kind, name, "main", 0);
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                throw new IllegalStateException("Shader compilation failed for " + name + ":\n"
                        + shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            if (bytes == null) {
                throw new IllegalStateException("Shader compilation returned no bytes for " + name);
            }
            ByteBuffer copy = BufferUtils.createByteBuffer(bytes.remaining());
            copy.put(bytes).flip();
            return copy;
        } finally {
            shaderc_result_release(result);
            shaderc_compiler_release(compiler);
        }
    }

    @Override
    public void close() {
        variants.clear();
    }
}
