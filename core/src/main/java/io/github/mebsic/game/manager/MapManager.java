package io.github.mebsic.game.manager;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.store.MapConfigStore;
import io.github.mebsic.game.map.GameMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MapManager {
    private final CorePlugin plugin;
    private final Map<String, GameMap> maps;
    private final Map<String, Location> pregameSpawns;
    private final Map<String, String> mapAliases;
    private String configuredMapDisplayName;
    private String activeMapName;
    private Location pregameSpawn;
    private List<String> rotation;
    private int rotationIndex;

    public MapManager(CorePlugin plugin) {
        this.plugin = plugin;
        this.maps = new LinkedHashMap<>();
        this.pregameSpawns = new HashMap<>();
        this.mapAliases = new LinkedHashMap<>();
        this.configuredMapDisplayName = "";
        this.pregameSpawn = null;
        this.rotation = new ArrayList<>();
        this.rotationIndex = 0;
    }

    public void loadMaps() {
        loadMaps(false);
    }

    public void loadMaps(boolean preserveActiveMap) {
        String previousActiveMap = preserveActiveMap ? safeText(activeMapName) : "";
        maps.clear();
        pregameSpawns.clear();
        mapAliases.clear();
        configuredMapDisplayName = "";
        pregameSpawn = null;
        MapConfig config = loadConfig();
        Location configuredPregame = config == null ? null : toLocation(config.pregameSpawn);
        if (configuredPregame != null) {
            pregameSpawn = configuredPregame;
        }
        if (config != null && config.maps != null) {
            for (MapEntry entry : config.maps) {
                String worldDirectory = safeText(entry == null ? null : entry.worldDirectory);
                String displayName = safeText(entry == null ? null : entry.name);
                String mapName = worldDirectory;
                if (mapName.isEmpty() || isPlaceholderMapName(mapName)) {
                    mapName = displayName;
                }
                if (mapName.isEmpty()) {
                    continue;
                }
                if (displayName.isEmpty()) {
                    displayName = MapConfigStore.displayNameFromWorldDirectory(mapName);
                }
                boolean nightTime = entry.nightTime != null && entry.nightTime;
                GameMap map = new GameMap(mapName, displayName, nightTime);
                if (entry.spawns != null) {
                    for (LocationEntry loc : entry.spawns) {
                        Location location = toLocation(loc);
                        if (location != null) {
                            map.getSpawnPoints().add(location);
                        }
                    }
                }
                if (entry.dropItem != null) {
                    for (LocationEntry loc : entry.dropItem) {
                        if (loc == null) {
                            continue;
                        }
                        Location location = toLocation(loc);
                        if (location != null) {
                            map.getDropItemSpawns().add(location);
                        }
                    }
                }
                maps.put(normalizeMapKey(map.getName()), map);
                registerMapAlias(map.getName(), map.getName());
                registerMapAlias(worldDirectory, map.getName());
                registerMapAlias(displayName, map.getName());
                Location mapPregame = toLocation(entry.pregameSpawn);
                if (mapPregame != null) {
                    pregameSpawns.put(normalizeMapKey(map.getName()), mapPregame);
                    registerPregameAlias(worldDirectory, mapPregame);
                    registerPregameAlias(displayName, mapPregame);
                }
            }
        }
        if (maps.isEmpty()) {
            plugin.getLogger().warning("No maps loaded from MongoDB collection " + MapConfigStore.COLLECTION_NAME + ".");
        }
        this.rotation = config != null && config.rotation != null ? new ArrayList<>(config.rotation) : new ArrayList<>();
        registerConfiguredAliases(config);
        String preferredActiveMap = resolvePreferredActiveMapName(config);
        configuredMapDisplayName = resolveConfiguredDisplayName(config, preferredActiveMap);
        if (!previousActiveMap.isEmpty()) {
            String preserved = firstMatchingMapForServerKind(previousActiveMap);
            if (preserved != null && !isPlaceholderMapName(preserved)) {
                this.activeMapName = preserved;
                syncRotationIndex();
                return;
            }
        }
        this.activeMapName = preferredActiveMap;
        syncRotationIndex();
    }

    public void saveMaps() {
        // Map metadata is sourced from MongoDB maps; no-op.
    }

    public GameMap getActiveMap() {
        String current = safeText(activeMapName);
        GameMap active = current.isEmpty() ? null : maps.get(normalizeMapKey(current));
        if (active != null && !isPlaceholderMapName(active.getName())) {
            return active;
        }

        String runtimePreferred = resolvePreferredMapFromRuntimeWorld();
        if (runtimePreferred != null) {
            GameMap runtimeMap = maps.get(normalizeMapKey(runtimePreferred));
            if (runtimeMap != null && runtimeMap.getName() != null) {
                activeMapName = runtimeMap.getName();
                return runtimeMap;
            }
        }

        if (active != null) {
            return active;
        }

        String fallback = getFirstMapName();
        if (fallback == null) {
            return null;
        }
        GameMap fallbackMap = maps.get(normalizeMapKey(fallback));
        if (fallbackMap != null && fallbackMap.getName() != null) {
            activeMapName = fallbackMap.getName();
        }
        return fallbackMap;
    }

    public String getActiveMapDisplayName() {
        GameMap active = getActiveMap();
        if (active == null) {
            return "";
        }
        String display = safeText(active.getDisplayName());
        if (!display.isEmpty() && !isPlaceholderMapName(display)) {
            return display;
        }
        String runtimeMarkerMap = readMapNameFromWorldMarker();
        if (!runtimeMarkerMap.isEmpty() && !isPlaceholderMapName(runtimeMarkerMap)) {
            String runtimeDisplay = MapConfigStore.displayNameFromWorldDirectory(runtimeMarkerMap);
            if (!runtimeDisplay.isEmpty()) {
                return runtimeDisplay;
            }
        }
        String fallbackConfigured = safeText(configuredMapDisplayName);
        if (!fallbackConfigured.isEmpty() && !isPlaceholderMapName(fallbackConfigured)) {
            return fallbackConfigured;
        }
        String canonical = safeText(active.getName());
        for (Map.Entry<String, String> entry : mapAliases.entrySet()) {
            if (entry == null) {
                continue;
            }
            String alias = safeText(entry.getKey());
            String mapped = safeText(entry.getValue());
            if (alias.isEmpty() || mapped.isEmpty() || isPlaceholderMapName(alias)) {
                continue;
            }
            if (mapped.equalsIgnoreCase(canonical)) {
                return MapConfigStore.displayNameFromWorldDirectory(alias);
            }
        }
        if (!canonical.isEmpty()) {
            return MapConfigStore.displayNameFromWorldDirectory(canonical);
        }
        return "";
    }

    public boolean setActiveMap(String name) {
        String resolved = resolveCanonicalMapName(name);
        if (resolved == null) {
            return false;
        }
        this.activeMapName = resolved;
        if (!containsIgnoreCase(rotation, resolved)) {
            rotation.add(resolved);
        }
        return true;
    }

    public void rotateToNextMap() {
        if (rotation.isEmpty()) {
            return;
        }
        rotationIndex = (rotationIndex + 1) % rotation.size();
        String candidate = safeText(rotation.get(rotationIndex));
        String resolved = resolveCanonicalMapName(candidate);
        activeMapName = resolved == null ? candidate : resolved;
    }

    public List<String> getMapNames() {
        List<String> names = new ArrayList<>();
        for (GameMap map : maps.values()) {
            names.add(map.getName());
        }
        Collections.sort(names);
        return names;
    }

    public Location getPregameSpawnForActiveMap() {
        String runtimePreferred = resolvePreferredMapFromRuntimeWorld();
        Location perMap = resolvePregameSpawnForMap(runtimePreferred);
        if (perMap != null) {
            return perMap.clone();
        }

        perMap = resolvePregameSpawnForMap(activeMapName);
        if (perMap != null) {
            return perMap.clone();
        }

        Location singleMapPregame = singleMapPregameSpawnForServerKind();
        if (singleMapPregame != null) {
            return singleMapPregame.clone();
        }

        if (pregameSpawn != null) {
            return pregameSpawn.clone();
        }
        GameMap active = getActiveMap();
        if (active == null || active.getSpawnPoints().isEmpty()) {
            return null;
        }
        Location firstSpawn = active.getSpawnPoints().get(0);
        return firstSpawn == null ? null : firstSpawn.clone();
    }

    private Location resolvePregameSpawnForMap(String rawMapName) {
        String resolved = resolveCanonicalMapName(rawMapName);
        String key = normalizeMapKey(resolved == null ? rawMapName : resolved);
        if (key.isEmpty()) {
            return null;
        }
        return pregameSpawns.get(key);
    }

    public void addSpawn(Location location) {
        // Disabled: spawns are managed by build tooling and persisted in MongoDB maps.
    }

    public void addDropItemSpawn(Location location) {
        // Disabled: spawns are managed by build tooling and persisted in MongoDB maps.
    }

    private MapConfig loadConfig() {
        Gson gson = new Gson();
        JsonObject root = loadRootFromMongo();
        if (root == null) {
            return null;
        }

        for (String key : resolveGameTypeKeys()) {
            MapConfig scoped = parseScopedMapConfig(root, key, gson);
            if (scoped != null) {
                return scoped;
            }
        }
        return gson.fromJson(root, MapConfig.class);
    }

    private JsonObject loadRootFromMongo() {
        if (plugin == null || plugin.getMongoManager() == null) {
            return null;
        }
        String gameKey = MapConfigStore.normalizeGameKey(plugin.getConfig().getString("server.group", ""));
        if (gameKey.isEmpty()) {
            gameKey = MapConfigStore.DEFAULT_GAME_KEY;
        }
        MapConfigStore store = new MapConfigStore(plugin.getMongoManager());
        store.ensureDefaults(gameKey);
        JsonObject root = store.loadRoot(gameKey);
        if (root == null && !MapConfigStore.DEFAULT_GAME_KEY.equals(gameKey)) {
            store.ensureDefaults(MapConfigStore.DEFAULT_GAME_KEY);
            root = store.loadRoot(MapConfigStore.DEFAULT_GAME_KEY);
        }
        return root;
    }

    private MapConfig parseScopedMapConfig(JsonObject root, String gameTypeKey, Gson gson) {
        if (root == null || gameTypeKey == null || gameTypeKey.trim().isEmpty() || gson == null) {
            return null;
        }
        JsonObject gameTypes = child(root, "gameTypes");
        JsonObject section = child(gameTypes, gameTypeKey);
        if (section == null) {
            section = child(root, gameTypeKey);
        }
        if (section == null) {
            return null;
        }
        return gson.fromJson(section, MapConfig.class);
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

    private List<String> resolveGameTypeKeys() {
        Set<String> keys = new LinkedHashSet<>();

        String group = plugin.getConfig().getString("server.group", "");
        addGameTypeKeyVariants(keys, group);

        ServerType type = plugin.getServerType() == null ? ServerType.UNKNOWN : plugin.getServerType();
        String typeName = type.name();
        if (typeName.endsWith("_HUB")) {
            typeName = typeName.substring(0, typeName.length() - "_HUB".length());
        }
        addGameTypeKeyVariants(keys, typeName);
        addGameTypeKeyVariants(keys, type.getGameTypeDisplayName());
        addGameTypeKeyVariants(keys, "murdermystery");

        return new ArrayList<>(keys);
    }

    private void addGameTypeKeyVariants(Set<String> keys, String raw) {
        if (keys == null || raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        keys.add(trimmed);
        keys.add(trimmed.toLowerCase(Locale.ROOT));

        String normalized = trimmed.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        keys.add(normalized);

        String compact = normalized.replace("_", "");
        if (!compact.isEmpty()) {
            keys.add(compact);
        }
    }

    private Location toLocation(LocationEntry entry) {
        if (entry == null) {
            return null;
        }
        World world = resolveWorld(entry.world);
        if (world == null) {
            return null;
        }
        return new Location(world, entry.x, entry.y, entry.z, entry.yaw, entry.pitch);
    }

    private World resolveWorld(String name) {
        String target = safeText(name);
        if (!target.isEmpty()) {
            World direct = Bukkit.getWorld(target);
            if (direct != null) {
                return direct;
            }
            for (World world : Bukkit.getWorlds()) {
                if (world == null || world.getName() == null) {
                    continue;
                }
                if (target.equalsIgnoreCase(world.getName())) {
                    return world;
                }
            }
        }
        World defaultWorld = Bukkit.getWorld("world");
        if (defaultWorld != null) {
            return defaultWorld;
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        return Bukkit.getWorlds().get(0);
    }

    private String getFirstMapName() {
        for (GameMap map : maps.values()) {
            if (map == null || map.getName() == null) {
                continue;
            }
            if (!isPlaceholderMapName(map.getName())) {
                return map.getName();
            }
        }
        for (GameMap map : maps.values()) {
            if (map == null || map.getName() == null) {
                continue;
            }
            return map.getName();
        }
        return null;
    }

    private String resolvePreferredActiveMapName(MapConfig config) {
        String runtimePreferred = resolvePreferredMapFromRuntimeWorld();
        if (runtimePreferred != null && !isPlaceholderMapName(runtimePreferred)) {
            return runtimePreferred;
        }

        String configured = config == null ? "" : safeText(config.activeMap);
        String preferred = firstMatchingMapForServerKind(configured);
        if (preferred != null && !isPlaceholderMapName(preferred)) {
            return preferred;
        }

        if (rotation != null) {
            for (String mapName : rotation) {
                preferred = firstMatchingMapForServerKind(mapName);
                if (preferred != null && !isPlaceholderMapName(preferred)) {
                    return preferred;
                }
            }
        }

        String fallback = configured;
        if (fallback.isEmpty() && rotation != null && !rotation.isEmpty()) {
            fallback = safeText(rotation.get(0));
        }
        String resolvedFallback = resolveCanonicalMapName(fallback);
        if (resolvedFallback != null && !isPlaceholderMapName(resolvedFallback)) {
            return resolvedFallback;
        }
        if (runtimePreferred != null) {
            return runtimePreferred;
        }
        if (resolvedFallback != null) {
            return resolvedFallback;
        }
        return getFirstMapName();
    }

    private void registerConfiguredAliases(MapConfig config) {
        if (config == null) {
            return;
        }
        List<String> configuredNames = new ArrayList<>();
        configuredNames.add(safeText(config.activeMap));
        if (config.rotation != null) {
            configuredNames.addAll(config.rotation);
        }
        for (String raw : configuredNames) {
            String alias = safeText(raw);
            if (alias.isEmpty() || isPlaceholderMapName(alias)) {
                continue;
            }
            if (mapAliases.containsKey(normalizeMapKey(alias))) {
                continue;
            }
            String mapped = resolveCanonicalMapName(alias);
            if (mapped == null || mapped.isEmpty()) {
                mapped = singleMapForServerKind();
            }
            if (mapped == null || mapped.isEmpty()) {
                continue;
            }
            registerMapAlias(alias, mapped);
        }
    }

    private String resolveConfiguredDisplayName(MapConfig config, String preferredActiveMap) {
        if (config == null) {
            return "";
        }
        List<String> configuredNames = new ArrayList<>();
        configuredNames.add(safeText(config.activeMap));
        if (config.rotation != null) {
            configuredNames.addAll(config.rotation);
        }
        String preferred = safeText(preferredActiveMap);
        for (String raw : configuredNames) {
            String alias = safeText(raw);
            if (alias.isEmpty() || isPlaceholderMapName(alias)) {
                continue;
            }
            String mapped = resolveCanonicalMapName(alias);
            if (!preferred.isEmpty() && mapped != null && !mapped.equalsIgnoreCase(preferred)) {
                continue;
            }
            return MapConfigStore.displayNameFromWorldDirectory(alias);
        }
        return "";
    }

    private String resolvePreferredMapFromRuntimeWorld() {
        String markerMap = readMapNameFromWorldMarker();
        if (!markerMap.isEmpty()) {
            String byMarker = firstMatchingMapForServerKind(markerMap);
            if (byMarker != null) {
                return byMarker;
            }
        }

        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            String worldName = safeText(world.getName());
            if (worldName.isEmpty() || isPlaceholderMapName(worldName)) {
                continue;
            }
            String byWorldName = firstMatchingMapForServerKind(worldName);
            if (byWorldName != null) {
                return byWorldName;
            }
        }
        return null;
    }

    private String readMapNameFromWorldMarker() {
        for (World world : Bukkit.getWorlds()) {
            if (world == null || world.getWorldFolder() == null) {
                continue;
            }
            Path markerPath = world.getWorldFolder().toPath().resolve(".hypixel-map-source");
            if (!Files.exists(markerPath)) {
                continue;
            }
            try {
                String raw = safeText(new String(Files.readAllBytes(markerPath), StandardCharsets.UTF_8));
                if (raw.isEmpty()) {
                    continue;
                }
                int slash = raw.lastIndexOf('/');
                String mapName = slash >= 0 ? raw.substring(slash + 1) : raw;
                mapName = safeText(mapName);
                if (!mapName.isEmpty()) {
                    return mapName;
                }
            } catch (IOException ignored) {
                // Best-effort marker resolution.
            }
        }
        return "";
    }

    private void syncRotationIndex() {
        if (rotation == null || rotation.isEmpty()) {
            rotationIndex = 0;
            return;
        }
        String current = safeText(activeMapName);
        if (current.isEmpty()) {
            rotationIndex = 0;
            return;
        }
        for (int i = 0; i < rotation.size(); i++) {
            String candidate = safeText(rotation.get(i));
            String resolvedCandidate = resolveCanonicalMapName(candidate);
            if (!safeText(resolvedCandidate).isEmpty() && safeText(resolvedCandidate).equalsIgnoreCase(current)) {
                rotationIndex = i;
                return;
            }
            if (candidate.equalsIgnoreCase(current)) {
                rotationIndex = i;
                return;
            }
        }
        rotationIndex = 0;
    }

    private String firstMatchingMapForServerKind(String mapName) {
        String candidate = resolveCanonicalMapName(mapName);
        if (candidate == null) {
            return null;
        }
        GameMap map = maps.get(normalizeMapKey(candidate));
        if (map == null || map.getName() == null) {
            return null;
        }
        ServerType type = plugin.getServerType() == null ? ServerType.UNKNOWN : plugin.getServerType();
        if (type == ServerType.UNKNOWN) {
            return map.getName();
        }
        boolean hubMap = MapConfigStore.isHubMapName(map.getName());
        if (type.isHub() && hubMap) {
            return map.getName();
        }
        if (type.isGame() && !hubMap) {
            return map.getName();
        }
        return null;
    }

    private String singleMapForServerKind() {
        String only = null;
        for (GameMap map : maps.values()) {
            if (map == null || map.getName() == null) {
                continue;
            }
            ServerType type = plugin.getServerType() == null ? ServerType.UNKNOWN : plugin.getServerType();
            if (type != ServerType.UNKNOWN) {
                boolean hubMap = MapConfigStore.isHubMapName(map.getName());
                if (type.isHub() && !hubMap) {
                    continue;
                }
                if (type.isGame() && hubMap) {
                    continue;
                }
            }
            if (only != null) {
                return null;
            }
            only = map.getName();
        }
        return only;
    }

    private Location singleMapPregameSpawnForServerKind() {
        Location only = null;
        for (Map.Entry<String, Location> entry : pregameSpawns.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            GameMap map = maps.get(normalizeMapKey(entry.getKey()));
            if (map == null || map.getName() == null) {
                continue;
            }
            ServerType type = plugin.getServerType() == null ? ServerType.UNKNOWN : plugin.getServerType();
            if (type != ServerType.UNKNOWN) {
                boolean hubMap = MapConfigStore.isHubMapName(map.getName());
                if (type.isHub() && !hubMap) {
                    continue;
                }
                if (type.isGame() && hubMap) {
                    continue;
                }
            }
            if (only != null) {
                return null;
            }
            only = entry.getValue();
        }
        return only;
    }

    private void registerMapAlias(String alias, String canonicalMapName) {
        String key = normalizeMapKey(alias);
        String canonical = safeText(canonicalMapName);
        if (key.isEmpty() || canonical.isEmpty() || isPlaceholderMapName(key)) {
            return;
        }
        mapAliases.put(key, canonical);
    }

    private boolean isPlaceholderMapName(String mapName) {
        String normalized = normalizeMapKey(mapName);
        if (normalized.isEmpty()) {
            return false;
        }
        return "world".equals(normalized)
                || "world_nether".equals(normalized)
                || "world_the_end".equals(normalized)
                || "default".equals(normalized);
    }

    private void registerPregameAlias(String alias, Location location) {
        if (location == null) {
            return;
        }
        String aliasKey = normalizeMapKey(alias);
        if (aliasKey.isEmpty()) {
            return;
        }
        String canonical = mapAliases.get(aliasKey);
        if (canonical == null) {
            return;
        }
        pregameSpawns.put(normalizeMapKey(canonical), location);
    }

    private String resolveCanonicalMapName(String raw) {
        String key = normalizeMapKey(raw);
        if (key.isEmpty()) {
            return null;
        }
        GameMap direct = maps.get(key);
        if (direct != null && direct.getName() != null) {
            return direct.getName();
        }
        String alias = mapAliases.get(key);
        return alias == null || alias.trim().isEmpty() ? null : alias;
    }

    private String normalizeMapKey(String raw) {
        String value = safeText(raw);
        if (value.isEmpty()) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("_+", "_");
        while (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null || target.trim().isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value != null && target.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static class MapConfig {
        private List<MapEntry> maps;
        private List<String> rotation;
        private String activeMap;
        private LocationEntry pregameSpawn;
    }

    private static class MapEntry {
        private String name;
        private String worldDirectory;
        private Boolean nightTime;
        private List<LocationEntry> spawns;
        private List<LocationEntry> dropItem;
        private LocationEntry pregameSpawn;
    }

    private static class LocationEntry {
        private String world;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
    }
}
