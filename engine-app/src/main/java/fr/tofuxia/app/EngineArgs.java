package fr.tofuxia.app;

import fr.tofuxia.renderapi.DebugViewMode;

import java.util.Arrays;
import java.util.UUID;

public record EngineArgs(boolean smokeTest, String scene, DebugViewMode debugView, String connect,
                         String clientUuid, String displayName, String accountToken) {
    public static EngineArgs parse(String[] args, String defaultScene) {
        return new EngineArgs(Arrays.asList(args).contains("--smoke-test"),
                argument(args, "--scene=", defaultScene),
                parseDebug(argument(args, "--debug-view=", "lit")),
                argument(args, "--connect=", ""),
                normalizeUuid(argument(args, "--client-uuid=", "")),
                normalizeDisplayName(argument(args, "--display-name=", "Player")),
                argument(args, "--account-token=", "dev:" + normalizeUuid(argument(args, "--client-uuid=", ""))));
    }

    private static String argument(String[] args, String prefix, String fallback) {
        for (String arg : args) if (arg.startsWith(prefix)) return arg.substring(prefix.length());
        return fallback;
    }

    private static DebugViewMode parseDebug(String value) {
        return switch (value.toLowerCase()) {
            case "albedo", "base-color", "base" -> DebugViewMode.ALBEDO;
            case "direct", "direct-light" -> DebugViewMode.DIRECT_LIGHT;
            case "ambient", "ambient-light" -> DebugViewMode.AMBIENT;
            case "shadow", "shadow-factor" -> DebugViewMode.SHADOW_FACTOR;
            case "fog", "fog-factor" -> DebugViewMode.FOG_FACTOR;
            case "tone", "tone-mapped", "tonemapped" -> DebugViewMode.TONE_MAPPED;
            case "depth", "shadow-depth" -> DebugViewMode.SHADOW_MAP_DEPTH;
            case "normals" -> DebugViewMode.NORMALS;
            case "clusters", "cluster", "cluster-occupancy" -> DebugViewMode.CLUSTER_OCCUPANCY;
            case "local-light", "point-light", "point-lights" -> DebugViewMode.LOCAL_LIGHT;
            case "selection-mask", "outline-mask" -> DebugViewMode.SELECTION_MASK;
            case "selection-dilated", "outline-dilated" -> DebugViewMode.SELECTION_DILATED_MASK;
            case "selection-exterior", "outline-exterior" -> DebugViewMode.SELECTION_EXTERIOR;
            case "selection-occlusion", "outline-occlusion" -> DebugViewMode.SELECTION_DEPTH_OCCLUSION;
            default -> DebugViewMode.LIT;
        };
    }

    private static String normalizeUuid(String value) {
        if (value == null || value.isBlank()) return "00000000-0000-0000-0000-000000000001";
        return UUID.fromString(value.trim()).toString();
    }

    private static String normalizeDisplayName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) return "Player";
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
}
