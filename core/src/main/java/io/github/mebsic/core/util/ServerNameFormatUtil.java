package io.github.mebsic.core.util;

import io.github.mebsic.core.server.ServerType;

import java.util.Locale;

public final class ServerNameFormatUtil {
    private static final String DEFAULT_LOBBY_SUFFIX = "1A";

    private ServerNameFormatUtil() {
    }

    public static String toScoreboardCode(String rawServerName, ServerType serverType) {
        String normalized = normalize(rawServerName);
        if (normalized.isEmpty()) {
            return defaultCode(serverType);
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("lobby")) {
            String suffix = normalized.substring("lobby".length());
            if (suffix.trim().isEmpty() || looksLikeCompactSuffix(suffix)) {
                return "L" + normalizeSuffix(suffix);
            }
        }
        if (lower.startsWith("mini")) {
            String suffix = normalized.substring("mini".length());
            if (suffix.trim().isEmpty() || looksLikeCompactSuffix(suffix)) {
                return "m" + normalizeSuffix(suffix);
            }
        }
        if (lower.startsWith("l") && normalized.length() > 1) {
            String suffix = normalized.substring(1);
            if (looksLikeCompactSuffix(suffix)) {
                return "L" + normalizeSuffix(suffix);
            }
        }
        if (lower.startsWith("m") && normalized.length() > 1) {
            String suffix = normalized.substring(1);
            if (looksLikeCompactSuffix(suffix)) {
                return "m" + normalizeSuffix(suffix);
            }
        }
        return normalized;
    }

    private static boolean looksLikeCompactSuffix(String suffix) {
        String sanitized = sanitizeSuffix(suffix);
        if (sanitized.isEmpty()) {
            return false;
        }
        for (int i = 0; i < sanitized.length(); i++) {
            if (Character.isDigit(sanitized.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSuffix(String suffix) {
        String sanitized = sanitizeSuffix(suffix);
        if (sanitized.isEmpty()) {
            return DEFAULT_LOBBY_SUFFIX;
        }
        return sanitized;
    }

    private static String sanitizeSuffix(String suffix) {
        if (suffix == null) {
            return "";
        }
        String trimmed = suffix.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toUpperCase(c));
            }
        }
        return out.toString();
    }

    private static String defaultCode(ServerType serverType) {
        ServerType safeType = serverType == null ? ServerType.UNKNOWN : serverType;
        if (safeType.isHub()) {
            return "L" + DEFAULT_LOBBY_SUFFIX;
        }
        if (safeType.isGame()) {
            return "m" + DEFAULT_LOBBY_SUFFIX;
        }
        if (safeType.isBuild()) {
            return "build";
        }
        return "m" + DEFAULT_LOBBY_SUFFIX;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
