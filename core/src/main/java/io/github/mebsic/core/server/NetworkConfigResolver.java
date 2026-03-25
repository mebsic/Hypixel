package io.github.mebsic.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileReader;

public final class NetworkConfigResolver {
    private static final String CONFIG_FILE_NAME = "config.json";

    private NetworkConfigResolver() {
    }

    public static void apply(JavaPlugin plugin, FileConfiguration config) {
        File configFile = new File(plugin.getDataFolder(), CONFIG_FILE_NAME);
        if (!configFile.exists()) {
            return;
        }

        JsonObject root;
        try (FileReader reader = new FileReader(configFile)) {
            root = new JsonParser().parse(reader).getAsJsonObject();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load " + CONFIG_FILE_NAME + ": " + ex.getMessage());
            return;
        }

        JsonObject mongo = child(root, "mongo");
        JsonObject redis = child(root, "redis");
        JsonObject registry = child(root, "registry");
        JsonObject server = child(root, "server");
        JsonObject hub = child(root, "hub");
        JsonObject menus = child(root, "menus");
        boolean changed = false;
        changed |= setBoolean(config, "mongo.enabled", readBoolean(mongo, "enabled"));
        changed |= setString(config, "mongo.uri", readString(mongo, "uri"));
        changed |= setString(config, "mongo.database", readString(mongo, "database"));

        changed |= setBoolean(config, "redis.enabled", readBoolean(redis, "enabled"));
        changed |= setString(config, "redis.host", readString(redis, "host"));
        changed |= setInt(config, "redis.port", readInt(redis, "port"));
        changed |= setStringAllowEmpty(config, "redis.password", readStringAllowEmpty(redis, "password"));
        changed |= setInt(config, "redis.database", readInt(redis, "database"));

        changed |= setInt(config, "registry.staleSeconds", readInt(registry, "staleSeconds"));
        changed |= setInt(config, "server.heartbeatSeconds", readInt(server, "heartbeatSeconds"));
        changed |= setInt(config, "minPlayers", readInt(server, "minPlayers"));
        changed |= setInt(config, "menus.registryDataRefreshTicks", readInt(menus, "registryDataRefreshTicks"));
        changed |= setInt(config, "menus.gameMenuRefreshTicks", readInt(menus, "gameMenuRefreshTicks"));
        changed |= setInt(config, "menus.lobbySelectorRefreshTicks", readInt(menus, "lobbySelectorRefreshTicks"));
        changed |= setInt(config, "menus.lobbySelectorDataRefreshTicks", readInt(menus, "lobbySelectorDataRefreshTicks"));
        changed |= setStringAllowEmpty(config, "hub.spawn", readHubSpawn(root, server, hub));

        if (changed) {
            plugin.saveConfig();
        }
    }

    private static JsonObject child(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || !root.get(key).isJsonObject()) {
            return null;
        }
        return root.getAsJsonObject(key);
    }

    private static String readString(JsonObject root, String key) {
        String value = readStringAllowEmpty(root, key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    private static String readStringAllowEmpty(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }
        try {
            return root.get(key).getAsString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer readInt(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }
        try {
            return root.get(key).getAsInt();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Boolean readBoolean(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }
        try {
            return root.get(key).getAsBoolean();
        } catch (Exception ex) {
            return null;
        }
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

    private static boolean setStringAllowEmpty(FileConfiguration config, String path, String value) {
        if (value == null) {
            return false;
        }
        String current = config.getString(path, "");
        if (value.equals(current)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static boolean setInt(FileConfiguration config, String path, Integer value) {
        if (value == null) {
            return false;
        }
        int current = config.getInt(path, Integer.MIN_VALUE);
        if (value == current) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static boolean setBoolean(FileConfiguration config, String path, Boolean value) {
        if (value == null) {
            return false;
        }
        boolean current = config.getBoolean(path, !value);
        if (current == value) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static String readHubSpawn(JsonObject root, JsonObject server, JsonObject hub) {
        String value = readLocationValue(childElement(hub, "spawn"));
        if (value != null) {
            return value;
        }
        value = readLocationValue(childElement(server, "hubSpawn"));
        if (value != null) {
            return value;
        }
        value = readLocationValue(childElement(server, "spawn"));
        if (value != null) {
            return value;
        }
        return readLocationValue(childElement(root, "hubSpawn"));
    }

    private static JsonElement childElement(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) {
            return null;
        }
        return root.get(key);
    }

    private static String readLocationValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            try {
                String raw = value.getAsString();
                return normalizeSerializedLocation(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!value.isJsonObject()) {
            return null;
        }
        JsonObject obj = value.getAsJsonObject();
        String world = stringField(obj, "world");
        Double x = numberField(obj, "x");
        Double y = numberField(obj, "y");
        Double z = numberField(obj, "z");
        Double yaw = numberField(obj, "yaw");
        Double pitch = numberField(obj, "pitch");
        if (world == null || x == null || y == null || z == null) {
            return null;
        }
        if (yaw == null) {
            yaw = 0.0d;
        }
        if (pitch == null) {
            pitch = 0.0d;
        }
        return world + "," + x + "," + y + "," + z + "," + yaw.floatValue() + "," + pitch.floatValue();
    }

    private static String normalizeSerializedLocation(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String[] parts = raw.trim().split(",");
        if (parts.length < 4) {
            return null;
        }

        String world = parts[0] == null ? "" : parts[0].trim();
        if (world.isEmpty()) {
            return null;
        }
        Double x = parseDouble(parts[1]);
        Double y = parseDouble(parts[2]);
        Double z = parseDouble(parts[3]);
        if (x == null || y == null || z == null) {
            return null;
        }
        Double yaw = parts.length > 4 ? parseDouble(parts[4]) : 0.0d;
        Double pitch = parts.length > 5 ? parseDouble(parts[5]) : 0.0d;
        if (yaw == null) {
            yaw = 0.0d;
        }
        if (pitch == null) {
            pitch = 0.0d;
        }
        return world + "," + x + "," + y + "," + z + "," + yaw.floatValue() + "," + pitch.floatValue();
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stringField(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = root.get(key).getAsString();
            return value == null || value.trim().isEmpty() ? null : value.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Double numberField(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }
        try {
            return root.get(key).getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }
}
