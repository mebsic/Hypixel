package io.github.mebsic.core.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.core.manager.MongoManager;
import org.bson.Document;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.mongodb.client.model.Filters.eq;

public class MapConfigStore {
    public static final String COLLECTION_NAME = "maps";
    public static final String DEFAULT_GAME_KEY = "murdermystery";
    private static final String MAP_DROP_ITEMS_KEY = "dropItem";

    private final MongoManager mongo;

    public MapConfigStore(MongoManager mongo) {
        this.mongo = mongo;
    }

    public void ensureDefaults(String gameKey) {
        MongoCollection<Document> collection = collection();
        if (collection == null) {
            return;
        }
        String key = resolveGameKey(gameKey);
        Document defaults = toDocument(defaultRoot(key));
        defaults.put("_id", key);
        collection.updateOne(
                eq("_id", key),
                new Document("$setOnInsert", defaults),
                new UpdateOptions().upsert(true)
        );
        ensureLocationCoordinateDefaults(key);
        ensureMurderMysteryNoMobSpawningDefaults(key);
    }

    public JsonObject loadRoot(String gameKey) {
        MongoCollection<Document> collection = collection();
        if (collection == null) {
            return null;
        }
        String key = resolveGameKey(gameKey);
        Document doc = collection.find(eq("_id", key)).first();
        if (doc == null) {
            return null;
        }
        JsonObject root = toJson(doc);
        if (root == null) {
            return null;
        }
        root.remove("_id");
        return root;
    }

    public void saveRoot(String gameKey, JsonObject root) {
        if (root == null) {
            return;
        }
        MongoCollection<Document> collection = collection();
        if (collection == null) {
            return;
        }
        String key = resolveGameKey(gameKey);
        Document replacement = toDocument(root);
        replacement.put("_id", key);
        replacement.put("updatedAt", System.currentTimeMillis());
        collection.replaceOne(eq("_id", key), replacement, new ReplaceOptions().upsert(true));
    }

    public String resolveWorldDirectory(String gameKey, boolean hubServer) {
        JsonObject root = loadRoot(gameKey);
        if (root == null) {
            return "";
        }
        String key = resolveGameKey(gameKey);
        return resolveWorldDirectoryFromRoot(root, key, hubServer);
    }

    public boolean setActiveMap(String gameKey, String mapName) {
        String key = resolveGameKey(gameKey);
        String resolvedMapName = safeText(mapName);
        if (resolvedMapName.isEmpty()) {
            return false;
        }

        JsonObject root = loadRoot(key);
        if (root == null) {
            ensureDefaults(key);
            root = loadRoot(key);
        }
        if (root == null) {
            return false;
        }

        JsonObject section = getOrCreateGameSection(root, key);
        String current = safeText(section.get("activeMap"));
        if (resolvedMapName.equalsIgnoreCase(current)) {
            return false;
        }

        section.addProperty("activeMap", resolvedMapName);
        JsonArray rotation = getOrCreateArray(section, "rotation");
        if (!containsIgnoreCase(rotation, resolvedMapName)) {
            rotation.add(new JsonPrimitive(resolvedMapName));
        }

        saveRoot(key, root);
        return true;
    }

    private void ensureLocationCoordinateDefaults(String gameKey) {
        String key = resolveGameKey(gameKey);
        JsonObject root = loadRoot(key);
        if (root == null) {
            return;
        }
        if (!normalizeRootLocationData(root, key)) {
            return;
        }
        saveRoot(key, root);
    }

    private void ensureMurderMysteryNoMobSpawningDefaults(String gameKey) {
        String key = resolveGameKey(gameKey);
        if (!DEFAULT_GAME_KEY.equalsIgnoreCase(key)) {
            return;
        }

        JsonObject root = loadRoot(key);
        if (root == null) {
            return;
        }

        JsonObject section = getOrCreateGameSection(root, key);
        JsonObject serverTypes = getOrCreateObject(section, "serverTypes");
        boolean changed = false;
        changed |= ensureNoMobSpawningRules(serverTypes, "MURDER_MYSTERY");
        changed |= ensureNoMobSpawningRules(serverTypes, "MURDER_MYSTERY_HUB");
        if (!changed) {
            return;
        }
        saveRoot(key, root);
    }

    private boolean ensureNoMobSpawningRules(JsonObject serverTypes, String serverTypeKey) {
        if (serverTypes == null || serverTypeKey == null || serverTypeKey.trim().isEmpty()) {
            return false;
        }
        JsonObject serverType = child(serverTypes, serverTypeKey);
        if (serverType == null) {
            serverType = defaultServerTypeRules();
            serverTypes.add(serverTypeKey, serverType);
            return true;
        }
        JsonObject rules = getOrCreateObject(serverType, "gamerules");
        boolean changed = false;
        changed |= ensureBooleanRule(rules, "allowMonsters", false);
        changed |= ensureBooleanRule(rules, "doMobSpawning", false);
        return changed;
    }

    private boolean ensureBooleanRule(JsonObject rules, String key, boolean value) {
        if (rules == null || key == null || key.trim().isEmpty()) {
            return false;
        }
        JsonElement existing = rules.get(key);
        if (existing != null && !existing.isJsonNull()) {
            try {
                if (existing.getAsBoolean() == value) {
                    return false;
                }
            } catch (Exception ignored) {
                // Fall through and normalize to a strict boolean.
            }
        }
        rules.addProperty(key, value);
        return true;
    }

    public static String normalizeGameKey(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                normalized.append(ch);
                continue;
            }
            if (ch >= '0' && ch <= '9') {
                normalized.append(ch);
            }
        }
        return normalized.toString();
    }

    public static boolean isHubMapName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return false;
        }
        return trimmed.contains("hub");
    }

    public static String displayNameFromWorldDirectory(String worldDirectory) {
        if (worldDirectory == null) {
            return "";
        }
        String trimmed = worldDirectory.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String normalized = trimmed
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return "";
        }

        String[] parts = normalized.split(" ");
        StringBuilder out = new StringBuilder(normalized.length());
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(capitalizeToken(token));
        }
        return out.toString();
    }

    private String resolveGameKey(String gameKey) {
        String normalized = normalizeGameKey(gameKey);
        return normalized.isEmpty() ? DEFAULT_GAME_KEY : normalized;
    }

    private MongoCollection<Document> collection() {
        if (mongo == null) {
            return null;
        }
        mongo.ensureCollection(COLLECTION_NAME);
        return mongo.getCollection(COLLECTION_NAME);
    }

    private JsonObject defaultRoot(String gameKey) {
        JsonObject root = new JsonObject();
        if (DEFAULT_GAME_KEY.equalsIgnoreCase(gameKey)) {
            JsonObject gameTypes = new JsonObject();
            gameTypes.add(DEFAULT_GAME_KEY, defaultGameSection("hub", true, true));
            root.add("gameTypes", gameTypes);
            return root;
        }
        JsonObject gameTypes = new JsonObject();
        gameTypes.add(gameKey, defaultGameSection("default", false, false));
        root.add("gameTypes", gameTypes);
        return root;
    }

    private JsonObject defaultGameSection(String mapName, boolean includeRewards, boolean includeMurderMysteryServerTypes) {
        JsonObject section = new JsonObject();
        JsonObject serverTypes = new JsonObject();
        if (includeMurderMysteryServerTypes) {
            serverTypes.add("MURDER_MYSTERY_HUB", defaultServerTypeRules());
            serverTypes.add("MURDER_MYSTERY", defaultServerTypeRules());
        }
        section.add("serverTypes", serverTypes);

        if (includeRewards) {
            JsonObject rewards = new JsonObject();
            rewards.addProperty("goldPickupTokens", 10);
            rewards.addProperty("survive30SecondsTokens", 50);
            rewards.addProperty("murdererKillTokens", 100);
            section.add("rewards", rewards);
        }

        section.addProperty("activeMap", mapName);

        JsonArray rotation = new JsonArray();
        rotation.add(new JsonPrimitive(mapName));
        section.add("rotation", rotation);

        JsonArray maps = new JsonArray();
        maps.add(defaultMap(mapName));
        section.add("maps", maps);
        return section;
    }

    private JsonObject defaultMap(String mapName) {
        JsonObject map = new JsonObject();
        map.addProperty("name", displayNameFromWorldDirectory(mapName));
        map.addProperty("worldDirectory", mapName);
        map.addProperty("nightTime", false);

        JsonArray spawns = new JsonArray();
        spawns.add(defaultLocation(0.5d, 64.0d, 0.5d, 0.0f, 0.0f));
        map.add("spawns", spawns);

        JsonArray dropItem = new JsonArray();
        dropItem.add(defaultLocation(2.5d, 64.0d, 2.5d, 0.0f, 0.0f));
        map.add(MAP_DROP_ITEMS_KEY, dropItem);
        return map;
    }

    private JsonObject defaultLocation(double x, double y, double z, float yaw, float pitch) {
        JsonObject location = new JsonObject();
        location.addProperty("world", "world");
        location.addProperty("x", x);
        location.addProperty("y", y);
        location.addProperty("z", z);
        location.addProperty("yaw", yaw);
        location.addProperty("pitch", pitch);
        return location;
    }

    private JsonObject defaultServerTypeRules() {
        JsonObject section = new JsonObject();
        JsonObject rules = new JsonObject();
        rules.addProperty("hungerLoss", false);
        rules.addProperty("healthLoss", false);
        rules.addProperty("vanillaAchievements", false);
        rules.addProperty("keepInventory", true);
        rules.addProperty("inventoryMoveLocked", true);
        rules.addProperty("weatherCycle", false);
        rules.addProperty("allowAnimals", false);
        rules.addProperty("allowMonsters", false);
        rules.addProperty("blockBreak", false);
        rules.addProperty("commandBlockOutput", false);
        rules.addProperty("doDaylightCycle", false);
        rules.addProperty("doEntityDrops", true);
        rules.addProperty("doFireTick", false);
        rules.addProperty("doMobLoot", true);
        rules.addProperty("doMobSpawning", false);
        rules.addProperty("doTileDrops", false);
        rules.addProperty("logAdminCommands", false);
        rules.addProperty("mobGriefing", false);
        rules.addProperty("naturalRegeneration", true);
        rules.addProperty("reducedDebugInfo", false);
        rules.addProperty("sendCommandFeedback", false);
        rules.addProperty("showDeathMessages", false);
        rules.addProperty("randomTickSpeed", 0);
        section.add("gamerules", rules);
        return section;
    }

    private JsonObject toJson(Document doc) {
        if (doc == null) {
            return null;
        }
        try {
            JsonElement parsed = new JsonParser().parse(doc.toJson());
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private Document toDocument(JsonObject root) {
        if (root == null) {
            return new Document();
        }
        try {
            return Document.parse(root.toString());
        } catch (Exception ignored) {
            return new Document();
        }
    }

    private String resolveWorldDirectoryFromRoot(JsonObject root, String gameKey, boolean hubServer) {
        JsonObject section = resolveGameSection(root, gameKey);
        if (section == null) {
            return "";
        }

        Set<String> orderedCandidates = new LinkedHashSet<>();
        addCandidate(orderedCandidates, section.get("activeMap"));
        addCandidates(orderedCandidates, section.get("rotation"));

        JsonElement mapsRaw = section.get("maps");
        if (mapsRaw != null && mapsRaw.isJsonArray()) {
            for (JsonElement entry : mapsRaw.getAsJsonArray()) {
                if (entry == null || !entry.isJsonObject()) {
                    continue;
                }
                JsonObject map = entry.getAsJsonObject();
                addCandidate(orderedCandidates, map.get("worldDirectory"));
                addCandidate(orderedCandidates, map.get("name"));
            }
        }

        for (String candidate : orderedCandidates) {
            if (isPlaceholderMapName(candidate)) {
                continue;
            }
            if (matchesServerKind(candidate, hubServer)) {
                return candidate;
            }
        }
        if (hubServer) {
            String hubByData = resolveHubWorldDirectoryByMetadata(mapsRaw, orderedCandidates);
            if (!hubByData.isEmpty()) {
                return hubByData;
            }
        }
        for (String candidate : orderedCandidates) {
            if (!candidate.isEmpty() && !isPlaceholderMapName(candidate)) {
                return candidate;
            }
        }
        for (String candidate : orderedCandidates) {
            if (matchesServerKind(candidate, hubServer)) {
                return candidate;
            }
        }
        for (String candidate : orderedCandidates) {
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return "";
    }

    private String resolveHubWorldDirectoryByMetadata(JsonElement mapsRaw, Set<String> orderedCandidates) {
        if (mapsRaw == null || !mapsRaw.isJsonArray()) {
            return "";
        }
        JsonArray maps = mapsRaw.getAsJsonArray();
        if (maps == null || maps.size() == 0) {
            return "";
        }

        if (orderedCandidates != null) {
            for (String candidate : orderedCandidates) {
                JsonObject map = findMapByCandidate(maps, candidate);
                if (map == null || !mapHasHubMetadata(map)) {
                    continue;
                }
                String preferred = safeText(map.get("worldDirectory"));
                if (!preferred.isEmpty() && !isPlaceholderMapName(preferred)) {
                    return preferred;
                }
                preferred = safeText(map.get("name"));
                if (!preferred.isEmpty() && !isPlaceholderMapName(preferred)) {
                    return preferred;
                }
            }
        }

        for (JsonElement entry : maps) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            if (!mapHasHubMetadata(map)) {
                continue;
            }
            String preferred = safeText(map.get("worldDirectory"));
            if (!preferred.isEmpty() && !isPlaceholderMapName(preferred)) {
                return preferred;
            }
            preferred = safeText(map.get("name"));
            if (!preferred.isEmpty() && !isPlaceholderMapName(preferred)) {
                return preferred;
            }
        }
        return "";
    }

    private JsonObject findMapByCandidate(JsonArray maps, String candidate) {
        String needle = safeText(candidate);
        if (maps == null || maps.size() == 0 || needle.isEmpty()) {
            return null;
        }
        for (JsonElement entry : maps) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            String worldDirectory = safeText(map.get("worldDirectory"));
            String name = safeText(map.get("name"));
            if (needle.equalsIgnoreCase(worldDirectory) || needle.equalsIgnoreCase(name)) {
                return map;
            }
        }
        return null;
    }

    private boolean mapHasHubMetadata(JsonObject map) {
        if (map == null) {
            return false;
        }
        if (hasValue(map.get("hubSpawn")) || hasValue(map.get("lobbySpawn"))) {
            return true;
        }
        return hasNonEmptyArray(map.get("npcs"))
                || hasNonEmptyArray(map.get("profileNpcs"))
                || hasNonEmptyArray(map.get("leaderboards"))
                || hasNonEmptyArray(map.get("parkours"));
    }

    private boolean hasNonEmptyArray(JsonElement value) {
        return value != null && value.isJsonArray() && value.getAsJsonArray().size() > 0;
    }

    private boolean hasValue(JsonElement value) {
        return value != null && !value.isJsonNull();
    }

    private boolean normalizeRootLocationData(JsonObject root, String gameKey) {
        if (root == null) {
            return false;
        }
        boolean changed = false;
        changed |= normalizeLocationField(root, "hubSpawn");
        changed |= normalizeLocationField(root, "lobbySpawn");
        changed |= normalizeLocationField(root, "spawn");
        changed |= normalizeServerTypeLocations(child(root, "serverTypes"));

        JsonObject resolvedGameSection = resolveGameSection(root, gameKey);
        changed |= normalizeGameSectionLocations(resolvedGameSection);

        JsonObject gameTypes = child(root, "gameTypes");
        if (gameTypes != null) {
            for (Map.Entry<String, JsonElement> entry : gameTypes.entrySet()) {
                if (entry == null || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                changed |= normalizeGameSectionLocations(entry.getValue().getAsJsonObject());
            }
        }

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (entry == null || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            String key = safeText(entry.getKey());
            if (key.isEmpty() || "gameTypes".equalsIgnoreCase(key) || "serverTypes".equalsIgnoreCase(key)) {
                continue;
            }
            changed |= normalizeGameSectionLocations(entry.getValue().getAsJsonObject());
        }
        return changed;
    }

    private boolean normalizeGameSectionLocations(JsonObject section) {
        if (section == null) {
            return false;
        }
        boolean changed = false;
        changed |= normalizeLocationField(section, "hubSpawn");
        changed |= normalizeLocationField(section, "lobbySpawn");
        changed |= normalizeLocationField(section, "spawn");
        changed |= normalizeServerTypeLocations(child(section, "serverTypes"));

        JsonElement mapsRaw = section.get("maps");
        if (mapsRaw != null && mapsRaw.isJsonArray()) {
            for (JsonElement entry : mapsRaw.getAsJsonArray()) {
                if (entry == null || !entry.isJsonObject()) {
                    continue;
                }
                changed |= normalizeMapLocations(entry.getAsJsonObject());
            }
        }
        return changed;
    }

    private boolean normalizeMapLocations(JsonObject map) {
        if (map == null) {
            return false;
        }
        boolean changed = false;
        changed |= normalizeLocationField(map, "spawn");
        changed |= normalizeLocationField(map, "hubSpawn");
        changed |= normalizeLocationField(map, "lobbySpawn");
        changed |= normalizeLocationArray(map, "spawns");
        changed |= normalizeLocationArray(map, MAP_DROP_ITEMS_KEY);
        return changed;
    }

    private boolean normalizeServerTypeLocations(JsonObject serverTypes) {
        if (serverTypes == null) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, JsonElement> entry : serverTypes.entrySet()) {
            if (entry == null || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject section = entry.getValue().getAsJsonObject();
            changed |= normalizeLocationField(section, "hubSpawn");
            changed |= normalizeLocationField(section, "lobbySpawn");
            changed |= normalizeLocationField(section, "spawn");
        }
        return changed;
    }

    private boolean normalizeLocationArray(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return false;
        }

        JsonElement raw = parent.get(key);
        if (!raw.isJsonArray()) {
            JsonObject converted = parseLocationObject(raw);
            if (converted == null) {
                return false;
            }
            JsonArray single = new JsonArray();
            single.add(converted);
            parent.add(key, single);
            return true;
        }

        JsonArray locations = raw.getAsJsonArray();
        JsonArray rebuilt = new JsonArray();
        boolean changed = false;
        boolean rebuiltAny = false;
        for (JsonElement entry : locations) {
            if (entry == null || entry.isJsonNull()) {
                rebuilt.add(entry);
                continue;
            }
            if (entry.isJsonObject()) {
                JsonObject object = entry.getAsJsonObject();
                changed |= normalizeLocationObject(object);
                rebuilt.add(object);
                continue;
            }
            JsonObject converted = parseLocationObject(entry);
            if (converted != null) {
                rebuilt.add(converted);
                changed = true;
                rebuiltAny = true;
                continue;
            }
            rebuilt.add(entry);
        }
        if (rebuiltAny) {
            parent.add(key, rebuilt);
        }
        return changed;
    }

    private boolean normalizeLocationField(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return false;
        }
        JsonElement value = parent.get(key);
        if (value.isJsonObject()) {
            return normalizeLocationObject(value.getAsJsonObject());
        }
        JsonObject converted = parseLocationObject(value);
        if (converted == null) {
            return false;
        }
        parent.add(key, converted);
        return true;
    }

    private boolean normalizeLocationObject(JsonObject location) {
        if (location == null) {
            return false;
        }
        boolean changed = false;

        String world = safeText(location.get("world"));
        if (world.isEmpty()) {
            location.addProperty("world", "world");
            changed = true;
        }
        changed |= ensureNumericField(location, "x", 0.0d);
        changed |= ensureNumericField(location, "y", 0.0d);
        changed |= ensureNumericField(location, "z", 0.0d);
        changed |= ensureNumericField(location, "yaw", 0.0d);
        changed |= ensureNumericField(location, "pitch", 0.0d);
        return changed;
    }

    private boolean ensureNumericField(JsonObject root, String key, double fallback) {
        if (root == null || key == null || key.trim().isEmpty()) {
            return false;
        }
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            root.addProperty(key, fallback);
            return true;
        }
        try {
            value.getAsDouble();
            return false;
        } catch (Exception ignored) {
            root.addProperty(key, fallback);
            return true;
        }
    }

    private JsonObject parseLocationObject(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            normalizeLocationObject(object);
            return object;
        }
        if (!value.isJsonPrimitive()) {
            return null;
        }

        String raw = safeText(value);
        if (raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        String world = safeText(parts[0]);
        if (world.isEmpty()) {
            world = "world";
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

        JsonObject parsed = new JsonObject();
        parsed.addProperty("world", world);
        parsed.addProperty("x", x);
        parsed.addProperty("y", y);
        parsed.addProperty("z", z);
        parsed.addProperty("yaw", yaw.floatValue());
        parsed.addProperty("pitch", pitch.floatValue());
        return parsed;
    }

    private Double parseDouble(String raw) {
        String value = safeText(raw);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matchesServerKind(String mapName, boolean hubServer) {
        if (mapName == null || mapName.trim().isEmpty()) {
            return false;
        }
        boolean hubName = isHubMapName(mapName);
        return hubServer ? hubName : !hubName;
    }

    private boolean isPlaceholderMapName(String mapName) {
        String normalized = safeText(mapName).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        return "world".equals(normalized)
                || "world_nether".equals(normalized)
                || "world_the_end".equals(normalized)
                || "default".equals(normalized);
    }

    private JsonObject resolveGameSection(JsonObject root, String gameKey) {
        if (root == null) {
            return null;
        }
        JsonObject gameTypes = child(root, "gameTypes");
        JsonObject section = child(gameTypes, gameKey);
        if (section != null) {
            return section;
        }
        return child(root, gameKey);
    }

    private JsonObject getOrCreateGameSection(JsonObject root, String gameKey) {
        if (root == null) {
            return new JsonObject();
        }
        JsonObject gameTypes = getOrCreateObject(root, "gameTypes");
        JsonObject section = child(gameTypes, gameKey);
        if (section == null) {
            JsonObject legacySection = child(root, gameKey);
            if (legacySection != null) {
                section = legacySection;
                root.remove(gameKey);
                gameTypes.add(gameKey, section);
            } else {
                section = defaultGameSection(DEFAULT_GAME_KEY.equalsIgnoreCase(gameKey) ? "hub" : "default",
                        DEFAULT_GAME_KEY.equalsIgnoreCase(gameKey),
                        DEFAULT_GAME_KEY.equalsIgnoreCase(gameKey));
                gameTypes.add(gameKey, section);
            }
        }

        normalizeServerTypeSections(root, gameKey, section);
        return section;
    }

    private void normalizeServerTypeSections(JsonObject root, String gameKey, JsonObject gameSection) {
        if (gameSection == null) {
            return;
        }
        JsonObject serverTypes = getOrCreateObject(gameSection, "serverTypes");

        JsonObject legacyServerTypes = child(root, "serverTypes");
        if (legacyServerTypes != null) {
            for (Map.Entry<String, JsonElement> entry : legacyServerTypes.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                    continue;
                }
                if (!serverTypes.has(entry.getKey()) || !serverTypes.get(entry.getKey()).isJsonObject()) {
                    serverTypes.add(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            }
            root.remove("serverTypes");
        }

        if (!DEFAULT_GAME_KEY.equalsIgnoreCase(gameKey)) {
            return;
        }

        migrateLegacyServerType(root, serverTypes, "MURDER_MYSTERY_HUB");
        migrateLegacyServerType(root, serverTypes, "MURDER_MYSTERY");

        if (!serverTypes.has("MURDER_MYSTERY_HUB") || !serverTypes.get("MURDER_MYSTERY_HUB").isJsonObject()) {
            serverTypes.add("MURDER_MYSTERY_HUB", defaultServerTypeRules());
        }
        if (!serverTypes.has("MURDER_MYSTERY") || !serverTypes.get("MURDER_MYSTERY").isJsonObject()) {
            serverTypes.add("MURDER_MYSTERY", defaultServerTypeRules());
        }
    }

    private void migrateLegacyServerType(JsonObject root, JsonObject serverTypes, String key) {
        if (root == null || serverTypes == null || key == null || key.trim().isEmpty()) {
            return;
        }
        JsonObject legacy = child(root, key);
        if (legacy != null && (!serverTypes.has(key) || !serverTypes.get(key).isJsonObject())) {
            serverTypes.add(key, legacy);
        }
        root.remove(key);
    }

    private JsonObject child(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key)) {
            return null;
        }
        JsonElement value = root.get(key);
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        return value.getAsJsonObject();
    }

    private JsonObject getOrCreateObject(JsonObject parent, String key) {
        if (parent == null || key == null || key.trim().isEmpty()) {
            return new JsonObject();
        }
        JsonElement existing = parent.get(key);
        if (existing != null && existing.isJsonObject()) {
            return existing.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        parent.add(key, created);
        return created;
    }

    private JsonArray getOrCreateArray(JsonObject parent, String key) {
        if (parent == null || key == null || key.trim().isEmpty()) {
            return new JsonArray();
        }
        JsonElement existing = parent.get(key);
        if (existing != null && existing.isJsonArray()) {
            return existing.getAsJsonArray();
        }
        JsonArray created = new JsonArray();
        parent.add(key, created);
        return created;
    }

    private void addCandidates(Set<String> target, JsonElement source) {
        if (target == null || source == null || source.isJsonNull() || !source.isJsonArray()) {
            return;
        }
        for (JsonElement entry : source.getAsJsonArray()) {
            addCandidate(target, entry);
        }
    }

    private void addCandidate(Set<String> target, JsonElement source) {
        if (target == null || source == null || source.isJsonNull()) {
            return;
        }
        String value = safeText(source);
        if (!value.isEmpty()) {
            target.add(value);
        }
    }

    private boolean containsIgnoreCase(JsonArray source, String target) {
        if (source == null || target == null || target.trim().isEmpty()) {
            return false;
        }
        for (JsonElement entry : source) {
            if (entry == null || entry.isJsonNull()) {
                continue;
            }
            String value = safeText(entry);
            if (target.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String safeText(JsonElement source) {
        if (source == null || source.isJsonNull()) {
            return "";
        }
        try {
            String raw = source.getAsString();
            return safeText(raw);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeText(String source) {
        if (source == null) {
            return "";
        }
        String trimmed = source.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static String capitalizeToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        String lower = token.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean nextUpper = true;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (nextUpper && ch >= 'a' && ch <= 'z') {
                out.append((char) (ch - ('a' - 'A')));
                nextUpper = false;
                continue;
            }
            out.append(ch);
            if (ch == '\'' || ch == '/' || ch == '.') {
                nextUpper = true;
            } else if (!(ch >= 'a' && ch <= 'z') && !(ch >= '0' && ch <= '9')) {
                nextUpper = true;
            } else {
                nextUpper = false;
            }
        }
        return out.toString();
    }
}
