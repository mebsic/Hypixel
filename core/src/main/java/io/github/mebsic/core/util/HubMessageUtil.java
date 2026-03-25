package io.github.mebsic.core.util;

import io.github.mebsic.core.server.ServerType;

import java.util.Locale;

public final class HubMessageUtil {
    private HubMessageUtil() {
    }

    public static String alreadyInHubMessage(ServerType type, String serverName) {
        String hubLabel = hubDisplayName(type);
        int lobbyNumber = extractLobbyNumber(serverName);
        if (lobbyNumber > 0) {
            return "You are already in " + hubLabel + " #" + lobbyNumber;
        }
        String normalized = normalize(serverName);
        if (!normalized.isEmpty()) {
            return "You are already in " + hubLabel + " (" + normalized.toUpperCase(Locale.ROOT) + ")";
        }
        return "You are already in " + hubLabel;
    }

    public static String gameDisplayName(ServerType type) {
        ServerType safeType = type == null ? ServerType.UNKNOWN : type;
        return titleCase(safeType.getGameTypeDisplayName());
    }

    public static String hubDisplayName(ServerType type) {
        String gameName = gameDisplayName(type);
        return gameName + " Lobby";
    }

    public static String transferToServerMessage(String serverName) {
        String displayName = displayServerName(serverName);
        if (displayName.isEmpty()) {
            return "Sending you to another server!";
        }
        return "Sending you to " + displayName + "!";
    }

    public static int extractLobbyNumber(String serverName) {
        String normalized = normalize(serverName);
        if (normalized.isEmpty()) {
            return -1;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String titleCase(String raw) {
        String[] words = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String displayServerName(String serverName) {
        String normalized = normalize(serverName);
        if (normalized.isEmpty()) {
            return "";
        }
        if (isGameServerName(normalized)) {
            return "mini" + stripPrefix(normalized, "m", "mini");
        }
        return normalized;
    }

    private static boolean isGameServerName(String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return false;
        }
        String lower = serverName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mini")) {
            return true;
        }
        if (!lower.startsWith("m") || lower.length() <= 1) {
            return false;
        }
        char next = lower.charAt(1);
        return Character.isDigit(next);
    }

    private static String stripPrefix(String value, String shortPrefix, String longPrefix) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(longPrefix)) {
            return trimmed.substring(longPrefix.length());
        }
        if (lower.startsWith(shortPrefix) && trimmed.length() > shortPrefix.length()) {
            return trimmed.substring(shortPrefix.length());
        }
        return trimmed;
    }
}
