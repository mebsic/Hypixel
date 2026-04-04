package io.github.mebsic.core.server;

import io.github.mebsic.core.manager.MongoManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;

public final class ServerIdentityResolver {
    private ServerIdentityResolver() {
    }

    public static void apply(JavaPlugin plugin, FileConfiguration config) {
        boolean changed = false;
        changed |= applyEnvironment(config);
        changed |= applyDefaults(config);
        String unifiedIdentity = resolveIdentityFromEnvironmentAndConfig(config);
        changed |= setString(config, "server.id", unifiedIdentity);

        if (changed) {
            plugin.saveConfig();
        }
    }

    public static String resolveIdentity(FileConfiguration config, String fallback) {
        ServerType type = ServerType.fromString(config == null ? "" : config.getString("server.type", ""));
        String configuredId = normalizeCanonicalIdentity(config == null ? null : config.getString("server.id", ""), type);
        if (isNotBlank(configuredId)) {
            return configuredId;
        }
        return firstNonBlank(fallback);
    }

    private static boolean applyEnvironment(FileConfiguration config) {
        boolean changed = false;
        changed |= setString(config, "server.id", env("SERVER_ID"));
        changed |= setString(config, "server.type", env("SERVER_TYPE"));
        changed |= setString(config, "server.group", env("SERVER_GROUP"));
        changed |= setString(config, "server.address", env("SERVER_ADDRESS"));
        changed |= setInt(config, "server.port", parsePositiveInt(env("SERVER_PORT")));
        changed |= setInt(config, "server.heartbeatSeconds", parsePositiveInt(env("SERVER_HEARTBEAT_SECONDS")));
        return changed;
    }

    private static boolean applyDefaults(FileConfiguration config) {
        boolean changed = false;
        String group = firstNonBlank(config.getString("server.group", ""), env("GAME_TYPE"), MongoManager.MURDER_MYSTERY_COLLECTION);
        changed |= setStringIfMissing(config, "server.group", group);
        changed |= setStringIfMissing(config, "server.type", inferType());
        changed |= setStringIfMissing(config, "server.address", firstNonBlank(env("HOSTNAME"), "localhost"));
        changed |= setIntIfMissing(config, "server.port", 25565);
        changed |= setIntIfMissing(config, "server.heartbeatSeconds", 1);
        return changed;
    }

    private static String inferType() {
        String explicit = env("SERVER_TYPE");
        if (isNotBlank(explicit)) {
            return explicit;
        }
        String kind = env("SERVER_KIND");
        if (kind != null) {
            if (kind.equalsIgnoreCase("hub")) {
                return ServerType.MURDER_MYSTERY_HUB.getId();
            }
            if (kind.equalsIgnoreCase("build")) {
                return ServerType.BUILD.getId();
            }
        }
        return ServerType.MURDER_MYSTERY.getId();
    }

    private static String resolveIdentityFromEnvironmentAndConfig(FileConfiguration config) {
        String explicitId = env("SERVER_ID");
        String configuredId = config == null ? null : config.getString("server.id", "");
        ServerType type = ServerType.fromString(config == null ? "" : config.getString("server.type", ""));

        String canonical = normalizeCanonicalIdentity(explicitId, type);
        if (isNotBlank(canonical)) {
            return canonical;
        }
        canonical = normalizeCanonicalIdentity(configuredId, type);
        if (isNotBlank(canonical)) {
            return canonical;
        }
        String seed = firstNonBlank(explicitId, configuredId, env("HOSTNAME"), UUID.randomUUID().toString());
        return buildGeneratedServerName(config, seed);
    }

    private static String buildGeneratedServerName(FileConfiguration config, String seed) {
        String serverId = firstNonBlank(seed, UUID.randomUUID().toString());
        ServerType type = ServerType.fromString(config.getString("server.type", ""));
        if (type.isBuild()) {
            return "build";
        }
        long hashSeed = Integer.toUnsignedLong(serverId.hashCode());
        int number = (int) (hashSeed % 10L) + 1;
        char suffix = (char) ('A' + (int) ((hashSeed / 10L) % 26L));
        String prefix = type.isHub() ? "lobby" : "mini";
        return prefix + number + suffix;
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? null : value.trim();
    }

    private static Integer parsePositiveInt(String raw) {
        if (!isNotBlank(raw)) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (isNotBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean setString(FileConfiguration config, String path, String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String current = config.getString(path, "");
        if (value.equals(current)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static boolean setStringIfMissing(FileConfiguration config, String path, String value) {
        String current = config.getString(path, "");
        if (current != null && !current.trim().isEmpty()) {
            return false;
        }
        return setString(config, path, value);
    }

    private static boolean setInt(FileConfiguration config, String path, Integer value) {
        if (value == null || value <= 0) {
            return false;
        }
        int current = config.getInt(path, -1);
        if (value == current) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static boolean setIntIfMissing(FileConfiguration config, String path, Integer value) {
        int current = config.getInt(path, -1);
        if (current > 0) {
            return false;
        }
        return setInt(config, path, value);
    }

    private static String normalizeCanonicalIdentity(String raw, ServerType type) {
        if (!isNotBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if ("build".equalsIgnoreCase(trimmed)) {
            return "build";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("lobby") && typeAllowsHub(type)) || (lower.startsWith("mini") && typeAllowsGame(type))) {
            int prefixLength = lower.startsWith("lobby") ? 5 : 4;
            String normalizedSuffix = normalizeCanonicalSuffix(trimmed.substring(prefixLength));
            if (!isNotBlank(normalizedSuffix)) {
                return null;
            }
            String prefix = lower.startsWith("lobby") ? "lobby" : "mini";
            return prefix + normalizedSuffix;
        }
        if ((lower.startsWith("l") && typeAllowsHub(type)) || (lower.startsWith("m") && typeAllowsGame(type))) {
            String normalizedSuffix = normalizeCanonicalSuffix(trimmed.substring(1));
            if (!isNotBlank(normalizedSuffix)) {
                return null;
            }
            String prefix = lower.startsWith("l") ? "lobby" : "mini";
            return prefix + normalizedSuffix;
        }
        return null;
    }

    private static String normalizeCanonicalSuffix(String suffix) {
        if (!isNotBlank(suffix) || suffix.length() < 2) {
            return null;
        }
        String trimmed = suffix.trim();
        char letter = trimmed.charAt(trimmed.length() - 1);
        if (!Character.isLetter(letter)) {
            return null;
        }
        String numberPart = trimmed.substring(0, trimmed.length() - 1);
        int number;
        try {
            number = Integer.parseInt(numberPart);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (number < 1 || number > 10) {
            return null;
        }
        return number + String.valueOf(Character.toUpperCase(letter));
    }

    private static boolean typeAllowsHub(ServerType type) {
        return type == null || type == ServerType.UNKNOWN || type.isHub();
    }

    private static boolean typeAllowsGame(ServerType type) {
        return type == null || type == ServerType.UNKNOWN || type.isGame();
    }
}
