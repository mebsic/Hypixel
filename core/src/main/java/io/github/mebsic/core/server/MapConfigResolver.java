package io.github.mebsic.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.store.MapConfigStore;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MapConfigResolver {
    private MapConfigResolver() {
    }

    public static void apply(JavaPlugin plugin, FileConfiguration config, MongoManager mongo) {
        JsonObject root = loadRoot(plugin, config, mongo);
        if (root == null) {
            return;
        }

        JsonObject gameSection = resolveGameSection(root, resolveGameKey(config));
        JsonObject gameRewards = child(gameSection, "rewards");

        boolean changed = false;
        changed |= setServerTypeRules(config, root, gameSection);
        changed |= setInt(config, "murdermystery.rewards.goldPickupTokens", readInt(gameRewards, "goldPickupTokens"));
        changed |= setInt(config, "murdermystery.rewards.survive30SecondsTokens", readInt(gameRewards, "survive30SecondsTokens"));
        changed |= setInt(config, "murdermystery.rewards.murdererKillTokens", readInt(gameRewards, "murdererKillTokens"));
        changed |= setStringAllowEmpty(config, "hub.spawn", readHubSpawn(root, gameSection, config));

        if (changed) {
            plugin.saveConfig();
        }
    }

    private static JsonObject loadRoot(JavaPlugin plugin, FileConfiguration config, MongoManager mongo) {
        if (mongo != null) {
            String gameKey = resolveGameKey(config);
            MapConfigStore store = new MapConfigStore(mongo);
            store.ensureDefaults(gameKey);
            JsonObject root = store.loadRoot(gameKey);
            if (root == null && !MapConfigStore.DEFAULT_GAME_KEY.equals(gameKey)) {
                store.ensureDefaults(MapConfigStore.DEFAULT_GAME_KEY);
                root = store.loadRoot(MapConfigStore.DEFAULT_GAME_KEY);
            }
            if (root != null) {
                return root;
            }
            plugin.getLogger().warning("No map config found in Mongo collection " + MapConfigStore.COLLECTION_NAME
                    + " for key '" + gameKey + "'.");
        }

        plugin.getLogger().warning("Map config is unavailable from Mongo collection "
                + MapConfigStore.COLLECTION_NAME + " for key '" + resolveGameKey(config) + "'.");
        return null;
    }

    private static boolean setServerTypeRules(FileConfiguration config, JsonObject root, JsonObject gameSection) {
        if (config == null || root == null) {
            return false;
        }
        boolean changed = false;
        Set<String> processed = new HashSet<>();

        JsonObject serverTypes = child(gameSection, "serverTypes");
        if (serverTypes == null) {
            serverTypes = child(root, "serverTypes");
        }
        if (serverTypes != null) {
            for (Map.Entry<String, JsonElement> typeEntry : serverTypes.entrySet()) {
                String resolved = resolveServerTypeKey(typeEntry == null ? null : typeEntry.getKey());
                if (resolved == null) {
                    continue;
                }
                processed.add(resolved);
                changed |= setServerTypeRuleSection(config, resolved, typeEntry.getValue());
            }
        }

        for (ServerType type : ServerType.values()) {
            if (type == null || processed.contains(type.name())) {
                continue;
            }
            JsonElement section = root.get(type.name());
            if (section == null || section.isJsonNull()) {
                continue;
            }
            changed |= setServerTypeRuleSection(config, type.name(), section);
        }
        return changed;
    }

    private static boolean setServerTypeRuleSection(FileConfiguration config, String serverType, JsonElement rawSection) {
        if (config == null || serverType == null || serverType.trim().isEmpty() || rawSection == null || !rawSection.isJsonObject()) {
            return false;
        }
        JsonObject section = rawSection.getAsJsonObject();
        JsonObject rules = child(section, "gamerules");
        if (rules == null) {
            rules = child(section, "rules");
        }
        if (rules == null) {
            rules = section;
        }

        boolean changed = false;
        for (Map.Entry<String, JsonElement> ruleEntry : rules.entrySet()) {
            if (ruleEntry == null || ruleEntry.getKey() == null) {
                continue;
            }
            String rawRule = ruleEntry.getKey().trim();
            if (rawRule.isEmpty()) {
                continue;
            }
            String toggleKey = gameplayToggleKey(rawRule);
            if (toggleKey != null) {
                Boolean value = readBoolean(ruleEntry.getValue());
                changed |= setBoolean(config, "gameplay." + toggleKey + ".byServerType." + serverType, value);
                continue;
            }
            String value = readRuleValue(ruleEntry.getValue());
            changed |= setString(config, "gameplay.gamerules.byServerType." + serverType + "." + rawRule, value);
        }
        return changed;
    }

    private static String resolveServerTypeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        ServerType exact = parseServerType(trimmed);
        if (exact != null) {
            return exact.name();
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        ServerType normalizedType = parseServerType(normalized);
        return normalizedType == null ? null : normalizedType.name();
    }

    private static ServerType parseServerType(String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) {
            return null;
        }
        try {
            return ServerType.valueOf(candidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String gameplayToggleKey(String rawRule) {
        if (rawRule == null || rawRule.trim().isEmpty()) {
            return null;
        }
        String normalized = rawRule.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if ("hungerloss".equals(normalized)) {
            return "hungerLoss";
        }
        if ("healthloss".equals(normalized)) {
            return "healthLoss";
        }
        if ("keepinventory".equals(normalized)) {
            return "keepInventory";
        }
        if ("inventorymovelocked".equals(normalized)) {
            return "inventoryMoveLocked";
        }
        if ("weathercycle".equals(normalized)) {
            return "weatherCycle";
        }
        if ("allowanimals".equals(normalized) || "passivemobspawning".equals(normalized)) {
            return "allowAnimals";
        }
        if ("allowmonsters".equals(normalized)) {
            return "allowMonsters";
        }
        if ("blockbreak".equals(normalized)) {
            return "blockBreak";
        }
        if ("vanillaachievements".equals(normalized)
                || "achievementannouncements".equals(normalized)
                || "advancementannouncements".equals(normalized)) {
            return "vanillaAchievements";
        }
        return null;
    }

    private static JsonObject child(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || !root.get(key).isJsonObject()) {
            return null;
        }
        return root.getAsJsonObject(key);
    }

    private static JsonElement childElement(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) {
            return null;
        }
        return root.get(key);
    }

    private static Boolean readBoolean(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }
        return readBoolean(root.get(key));
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

    private static String readRuleValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                return Boolean.toString(value.getAsBoolean());
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                return Integer.toString(value.getAsInt());
            }
            String raw = value.getAsString();
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            String normalized = raw.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return "true";
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return "false";
            }
            return normalized;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean setString(FileConfiguration config, String path, String value) {
        if (path == null || path.trim().isEmpty() || value == null || value.trim().isEmpty()) {
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
        if (path == null || path.trim().isEmpty() || value == null) {
            return false;
        }
        String current = config.getString(path, "");
        if (value.equals(current)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static Boolean readBoolean(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (Exception ignored) {
            try {
                String raw = value.getAsString();
                if (raw == null) {
                    return null;
                }
                String normalized = raw.trim();
                if ("true".equalsIgnoreCase(normalized)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(normalized)) {
                    return Boolean.FALSE;
                }
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
        return null;
    }

    private static String resolveGameKey(FileConfiguration config) {
        if (config == null) {
            return MapConfigStore.DEFAULT_GAME_KEY;
        }
        String normalized = MapConfigStore.normalizeGameKey(config.getString("server.group", ""));
        return normalized.isEmpty() ? MapConfigStore.DEFAULT_GAME_KEY : normalized;
    }

    private static JsonObject resolveGameSection(JsonObject root, String gameKey) {
        if (root == null) {
            return null;
        }
        JsonObject gameTypes = child(root, "gameTypes");
        JsonObject section = child(gameTypes, gameKey);
        if (section != null) {
            return section;
        }
        section = child(root, gameKey);
        if (section != null) {
            return section;
        }
        if (!MapConfigStore.DEFAULT_GAME_KEY.equals(gameKey)) {
            section = child(gameTypes, MapConfigStore.DEFAULT_GAME_KEY);
            if (section != null) {
                return section;
            }
            return child(root, MapConfigStore.DEFAULT_GAME_KEY);
        }
        return null;
    }

    private static String readHubSpawn(JsonObject root, JsonObject gameSection, FileConfiguration config) {
        if (root == null || config == null) {
            return null;
        }

        String mapScoped = readHubSpawnFromMaps(gameSection);
        if (mapScoped != null) {
            return mapScoped;
        }

        Set<String> sectionKeys = new LinkedHashSet<>();
        ServerType current = ServerType.fromString(config.getString("server.type", ""));
        if (current != null && current != ServerType.UNKNOWN) {
            sectionKeys.add(current.name());
            ServerType hub = current.toHubType();
            if (hub != null && hub != ServerType.UNKNOWN) {
                sectionKeys.add(hub.name());
            }
        }
        sectionKeys.add(ServerType.MURDER_MYSTERY_HUB.name());

        JsonObject gameServerTypes = child(gameSection, "serverTypes");
        for (String sectionKey : sectionKeys) {
            JsonObject section = child(gameServerTypes, sectionKey);
            if (section == null) {
                continue;
            }
            String value = readLocationValue(childElement(section, "hubSpawn"));
            if (value == null) {
                value = readLocationValue(childElement(section, "lobbySpawn"));
            }
            if (value == null) {
                value = readLocationValue(childElement(section, "spawn"));
            }
            if (value != null) {
                return value;
            }
        }

        JsonObject rootServerTypes = child(root, "serverTypes");
        for (String sectionKey : sectionKeys) {
            JsonObject section = child(rootServerTypes, sectionKey);
            if (section == null) {
                continue;
            }
            String value = readLocationValue(childElement(section, "hubSpawn"));
            if (value == null) {
                value = readLocationValue(childElement(section, "lobbySpawn"));
            }
            if (value == null) {
                value = readLocationValue(childElement(section, "spawn"));
            }
            if (value != null) {
                return value;
            }
        }

        for (String sectionKey : sectionKeys) {
            JsonObject section = child(root, sectionKey);
            if (section == null) {
                continue;
            }
            String value = readLocationValue(childElement(section, "hubSpawn"));
            if (value == null) {
                value = readLocationValue(childElement(section, "lobbySpawn"));
            }
            if (value == null) {
                value = readLocationValue(childElement(section, "spawn"));
            }
            if (value != null) {
                return value;
            }
        }

        return readLocationValue(childElement(root, "hubSpawn"));
    }

    private static String readHubSpawnFromMaps(JsonObject gameSection) {
        if (gameSection == null) {
            return null;
        }
        JsonElement mapsRaw = gameSection.get("maps");
        if (mapsRaw == null || !mapsRaw.isJsonArray()) {
            return null;
        }

        Set<String> orderedMapNames = new LinkedHashSet<>();
        addMapNameCandidate(orderedMapNames, childElement(gameSection, "activeMap"));
        addMapNameCandidates(orderedMapNames, childElement(gameSection, "rotation"));
        for (JsonElement entry : mapsRaw.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            addMapNameCandidate(orderedMapNames, childElement(map, "worldDirectory"));
            addMapNameCandidate(orderedMapNames, childElement(map, "name"));
        }

        // Prefer hub-like map names first for hub spawn resolution.
        for (String mapName : orderedMapNames) {
            if (!MapConfigStore.isHubMapName(mapName)) {
                continue;
            }
            String value = readHubSpawnFromMapEntry(mapsRaw, mapName);
            if (value != null) {
                return value;
            }
        }
        // If names are ambiguous, prefer maps that carry hub metadata.
        for (String mapName : orderedMapNames) {
            JsonObject map = findMapByName(mapsRaw, mapName);
            if (map == null || !mapHasHubMetadata(map)) {
                continue;
            }
            String value = readHubSpawnFromMapObject(map);
            if (value != null) {
                return value;
            }
        }
        for (JsonElement entry : mapsRaw.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            if (!mapHasHubMetadata(map)) {
                continue;
            }
            String value = readHubSpawnFromMapObject(map);
            if (value != null) {
                return value;
            }
        }
        for (String mapName : orderedMapNames) {
            String value = readHubSpawnFromMapEntry(mapsRaw, mapName);
            if (value != null) {
                return value;
            }
        }

        for (JsonElement entry : mapsRaw.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            String value = readHubSpawnFromMapObject(entry.getAsJsonObject());
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void addMapNameCandidates(Set<String> ordered, JsonElement raw) {
        if (ordered == null || raw == null || raw.isJsonNull() || !raw.isJsonArray()) {
            return;
        }
        for (JsonElement value : raw.getAsJsonArray()) {
            addMapNameCandidate(ordered, value);
        }
    }

    private static void addMapNameCandidate(Set<String> ordered, JsonElement raw) {
        if (ordered == null || raw == null || raw.isJsonNull()) {
            return;
        }
        String value;
        try {
            value = raw.getAsString();
        } catch (Exception ex) {
            return;
        }
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            ordered.add(trimmed);
        }
    }

    private static String readHubSpawnFromMapEntry(JsonElement mapsRaw, String mapName) {
        if (mapsRaw == null || !mapsRaw.isJsonArray() || mapName == null || mapName.trim().isEmpty()) {
            return null;
        }
        String target = mapName.trim();
        for (JsonElement entry : mapsRaw.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            String worldDirectory = readMapName(map, "worldDirectory");
            String name = readMapName(map, "name");
            if (!target.equalsIgnoreCase(worldDirectory) && !target.equalsIgnoreCase(name)) {
                continue;
            }
            String value = readExplicitHubSpawnFromMapObject(map);
            if (value == null) {
                value = readHubSpawnFromMapObject(map);
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String readMapName(JsonObject map, String key) {
        JsonElement raw = childElement(map, key);
        if (raw == null || raw.isJsonNull()) {
            return "";
        }
        try {
            String value = raw.getAsString();
            return value == null ? "" : value.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JsonObject findMapByName(JsonElement mapsRaw, String mapName) {
        if (mapsRaw == null || !mapsRaw.isJsonArray()) {
            return null;
        }
        String needle = mapName == null ? "" : mapName.trim();
        if (needle.isEmpty()) {
            return null;
        }
        for (JsonElement entry : mapsRaw.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            String worldDirectory = readMapName(map, "worldDirectory");
            String name = readMapName(map, "name");
            if (needle.equalsIgnoreCase(worldDirectory) || needle.equalsIgnoreCase(name)) {
                return map;
            }
        }
        return null;
    }

    private static boolean mapHasHubMetadata(JsonObject map) {
        if (map == null) {
            return false;
        }
        if (hasValue(childElement(map, "hubSpawn")) || hasValue(childElement(map, "lobbySpawn"))) {
            return true;
        }
        return hasNonEmptyArray(childElement(map, "npcs"))
                || hasNonEmptyArray(childElement(map, "profileNpcs"))
                || hasNonEmptyArray(childElement(map, "leaderboards"))
                || hasNonEmptyArray(childElement(map, "parkours"));
    }

    private static boolean hasNonEmptyArray(JsonElement value) {
        return value != null && value.isJsonArray() && value.getAsJsonArray().size() > 0;
    }

    private static boolean hasValue(JsonElement value) {
        return value != null && !value.isJsonNull();
    }

    private static String readHubSpawnFromMapObject(JsonObject map) {
        if (map == null) {
            return null;
        }
        String value = readExplicitHubSpawnFromMapObject(map);
        if (value == null) {
            value = readLocationValue(childElement(map, "spawn"));
        }
        if (value != null) {
            return value;
        }

        JsonElement spawnsRaw = childElement(map, "spawns");
        if (spawnsRaw == null || !spawnsRaw.isJsonArray()) {
            return null;
        }
        for (JsonElement spawn : spawnsRaw.getAsJsonArray()) {
            value = readLocationValue(spawn);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String readExplicitHubSpawnFromMapObject(JsonObject map) {
        if (map == null) {
            return null;
        }
        String value = readLocationValue(childElement(map, "hubSpawn"));
        if (value == null) {
            value = readLocationValue(childElement(map, "lobbySpawn"));
        }
        return value;
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
