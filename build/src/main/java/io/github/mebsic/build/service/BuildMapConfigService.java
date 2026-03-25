package io.github.mebsic.build.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mongodb.client.MongoCollection;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.PubSubService;
import io.github.mebsic.core.store.MapConfigStore;
import io.github.mebsic.core.util.RankFormatUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BuildMapConfigService {
    private static final DecimalFormat COORD_FORMAT = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.US));
    private static final double HOLOGRAM_LINE_SPACING = 0.30d;
    private static final double NPC_HOLOGRAM_BOTTOM_Y_OFFSET = 2.45d;
    private static final double PROFILE_NPC_HOLOGRAM_BOTTOM_Y_OFFSET = 2.60d;
    private static final double LEGACY_PARKOUR_HOLOGRAM_BASE_Y_OFFSET = 0.72d;
    private static final double PARKOUR_HOLOGRAM_BASE_Y_OFFSET = 2.0d;
    private static final String MAP_SPAWNS_KEY = "spawns";
    private static final String MAP_DROP_ITEMS_KEY = "dropItem";
    private static final String MAP_CREATED_AT_KEY = "createdAt";
    private static final String MAP_PREGAME_SPAWN_KEY = "pregameSpawn";
    private static final String MAP_HUB_SPAWN_KEY = "hubSpawn";
    private static final String MAP_LOBBY_SPAWN_KEY = "lobbySpawn";
    private static final String MAP_NPCS_KEY = "npcs";
    private static final String MAP_PROFILE_NPCS_KEY = "profileNpcs";
    private static final String MAP_LEADERBOARDS_KEY = "leaderboards";
    private static final String MAP_PARKOURS_KEY = "parkours";
    private static final String MAP_CHECKPOINTS_KEY = "checkpoints";
    private static final String MAP_START_KEY = "start";
    private static final String MAP_END_KEY = "end";
    private static final String MAP_PARKOUR_TITLE_COLOR_KEY = "titleColor";
    private static final String MAP_PARKOUR_START_COLOR_KEY = "startColor";
    private static final String MAP_PARKOUR_CHECKPOINT_COLOR_KEY = "checkpointColor";
    private static final String MAP_PARKOUR_END_COLOR_KEY = "endColor";
    private static final String MAP_NPC_KIND_KEY = "npcKind";
    private static final String MAP_ENTITY_ID_KEY = "entityId";
    private static final String MAP_HOLOGRAM_COLOR_KEY = "hologramColor";
    private static final String MAP_SKIN_OWNER_KEY = "skinOwner";
    private static final String MAP_OWNER_NAME_KEY = "ownerName";
    private static final String MAP_OWNER_UUID_KEY = "ownerUuid";
    private static final String MAP_METRIC_KEY = "metric";
    private static final String MAP_LEADERBOARD_TITLE_COLOR_KEY = "titleColor";
    private static final String MAP_LEADERBOARD_MODE_COLOR_KEY = "modeColor";
    private static final String MAP_LEADERBOARD_RANK_COLOR_KEY = "rankColor";
    private static final String MAP_LEADERBOARD_SEPARATOR_COLOR_KEY = "separatorColor";
    private static final String MAP_LEADERBOARD_VALUE_COLOR_KEY = "valueColor";
    private static final String MAP_LEADERBOARD_EMPTY_COLOR_KEY = "emptyColor";
    private static final String MAP_LEADERBOARD_FOOTER_COLOR_KEY = "footerColor";
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    // Build is editor-only: runtime NPC/hologram rendering belongs on hub servers.
    private static final boolean BUILD_RUNTIME_VISUALS_ENABLED = false;
    private static final String DEFAULT_NPC_SKIN = "Steve";
    private static final String DEFAULT_CLICK_TO_PLAY_NPC_COLOR = ChatColor.GOLD.name();
    private static final String NPC_KIND_CLICK_TO_PLAY = "CLICK_TO_PLAY";
    // Only known public owner for this skin.
    public static final String MURDER_MYSTERY_CLICK_TO_PLAY_SKIN_REFERENCE = "MURDER4SVTVN";
    private static final String DEFAULT_PROFILE_NPC_COLOR = ChatColor.AQUA.name();
    private static final String DEFAULT_NPC_COLOR = ChatColor.GREEN.name();
    private static final String LEADERBOARD_METRIC_KILLS = "kills";
    private static final String LEADERBOARD_METRIC_WINS = "wins";
    private static final String LEADERBOARD_METRIC_WINS_AS_MURDERER = "wins_as_murderer";
    private static final String LEGACY_LEADERBOARD_METRIC_KILLS_AS_MURDERER = "kills_as_murderer";
    private static final String MURDER_MYSTERY_WINS_AS_MURDERER_KEY = "murdermystery.winsAsMurderer";
    private static final String MURDER_MYSTERY_KILLS_AS_MURDERER_KEY = "murdermystery.killsAsMurderer";
    private static final Set<PosixFilePermission> EXPORT_DIRECTORY_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            )
    );
    private static final Set<PosixFilePermission> EXPORT_FILE_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ
            )
    );

    private final CorePlugin corePlugin;
    private final Object mapConfigLock = new Object();
    private final Map<UUID, RuntimeNpc> npcsByEntityId = new ConcurrentHashMap<UUID, RuntimeNpc>();
    private final Map<UUID, UUID> npcHologramToEntity = new ConcurrentHashMap<UUID, UUID>();
    private final Map<UUID, RuntimeLeaderboard> leaderboardsByAnchor = new ConcurrentHashMap<UUID, RuntimeLeaderboard>();
    private final Map<UUID, ParkourDraft> parkourDraftsByPlayer = new ConcurrentHashMap<UUID, ParkourDraft>();
    private final Map<UUID, String> leaderboardMetricByPlayer = new ConcurrentHashMap<UUID, String>();
    private final Map<String, UUID> worldExportLocks = new ConcurrentHashMap<String, UUID>();

    public BuildMapConfigService(CorePlugin corePlugin) {
        this.corePlugin = corePlugin;
    }

    public void shutdown() {
        despawnAllRuntimeArtifacts();
        parkourDraftsByPlayer.clear();
        leaderboardMetricByPlayer.clear();
    }

    public void clearPlayerState(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        parkourDraftsByPlayer.remove(playerUuid);
        leaderboardMetricByPlayer.remove(playerUuid);
    }

    public void clearPlayerStateOnDisconnect(Player player) {
        if (player == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (playerUuid == null) {
            return;
        }
        ParkourDraft draft = parkourDraftsByPlayer.remove(playerUuid);
        if (draft != null) {
            removeParkourDraftMarkers(draft, player.getWorld());
        }
        leaderboardMetricByPlayer.remove(playerUuid);
    }

    public ServerType parseGameType(String input) {
        String normalized = normalizeTypeToken(input);
        if (normalized.isEmpty() || !hasExplicitSuffix(normalized)) {
            return ServerType.UNKNOWN;
        }
        String requestedGameToken = compactToken(gameTypeName(normalized));
        if (requestedGameToken.isEmpty()) {
            return ServerType.UNKNOWN;
        }
        boolean wantsHub = normalized.endsWith("_HUB");
        for (ServerType type : ServerType.values()) {
            if (!isSupportedGameType(type)) {
                continue;
            }
            if (wantsHub && !type.isHub()) {
                continue;
            }
            if (!wantsHub && !type.isGame()) {
                continue;
            }
            String candidateGameToken = compactToken(gameTypeName(type.name()));
            if (requestedGameToken.equals(candidateGameToken)) {
                return type;
            }
        }
        return ServerType.UNKNOWN;
    }

    public ServerType parseGameType(String input, String worldDirectory) {
        return parseGameType(input);
    }

    public List<String> availableGameTypes() {
        return serverTypeOptions();
    }

    public String mapDisplayName(String worldDirectory) {
        String world = safeString(worldDirectory);
        if (world.isEmpty()) {
            return "";
        }
        String display = MapConfigStore.displayNameFromWorldDirectory(world);
        return display.isEmpty() ? world : display;
    }

    public boolean isHubMap(String worldDirectory) {
        return MapConfigStore.isHubMapName(safeString(worldDirectory));
    }

    public boolean isWorldTypeCompatible(ServerType gameType, String worldDirectory) {
        ServerType type = gameType == null ? ServerType.UNKNOWN : gameType;
        if (type == ServerType.UNKNOWN || type.isBuild()) {
            return true;
        }
        boolean hubWorld = isHubMap(worldDirectory);
        if (type.isHub()) {
            return hubWorld;
        }
        if (type.isGame()) {
            return !hubWorld;
        }
        return true;
    }

    public void exportWorldTemplateFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return;
        }
        if (corePlugin == null) {
            player.sendMessage(ChatColor.RED + "Build export is unavailable right now.");
            return;
        }

        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return;
        }

        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return;
        }

        World sourceWorld = resolveWorldByName(mapWorld);
        if (sourceWorld == null) {
            player.sendMessage(ChatColor.RED + "World \"" + mapWorld + "\" is not loaded.");
            return;
        }
        File sourceFolder = sourceWorld.getWorldFolder();
        if (sourceFolder == null || !sourceFolder.isDirectory()) {
            player.sendMessage(ChatColor.RED + "World folder is missing for \"" + sourceWorld.getName() + "\".");
            return;
        }
        for (Player occupant : sourceWorld.getPlayers()) {
            if (occupant == null || occupant.getUniqueId() == null) {
                continue;
            }
            if (occupant.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            player.sendMessage(ChatColor.RED + "Cannot export while " + occupant.getName() + " is in that world.");
            return;
        }

        World transitWorld = resolveExportTransitWorld(sourceWorld.getName());
        if (transitWorld == null) {
            player.sendMessage(ChatColor.RED + "Load another world first so this one can be safely unloaded.");
            return;
        }

        UUID playerUuid = player.getUniqueId();
        String sourceWorldName = safeString(sourceWorld.getName());
        String lockKey = gameKey.toLowerCase(Locale.ROOT) + ":" + sourceWorldName.toLowerCase(Locale.ROOT);
        if (worldExportLocks.putIfAbsent(lockKey, playerUuid) != null) {
            player.sendMessage(ChatColor.RED + "An export is already running for this world.");
            return;
        }

        Location playerLocation = player.getLocation();
        Location returnLocation;
        if (playerLocation != null
                && playerLocation.getWorld() != null
                && sourceWorldName.equalsIgnoreCase(safeString(playerLocation.getWorld().getName()))) {
            returnLocation = playerLocation.clone();
        } else {
            returnLocation = sourceWorld.getSpawnLocation().clone();
        }
        Path sourcePath = sourceFolder.toPath();
        Path targetPath = Paths.get(resolveMapRootPath(), gameKey, sourceWorldName);

        boolean movedForExport = false;
        try {
            if (player.getWorld() != null && sourceWorldName.equalsIgnoreCase(player.getWorld().getName())) {
                movedForExport = player.teleport(transitWorld.getSpawnLocation());
            }

            sourceWorld.save();
            if (!Bukkit.unloadWorld(sourceWorld, true)) {
                player.sendMessage(ChatColor.RED + "Failed to unload world \"" + sourceWorldName + "\"!");
                Player online = Bukkit.getPlayer(playerUuid);
                if (movedForExport && online != null && online.isOnline()) {
                    online.teleport(returnLocation);
                }
                worldExportLocks.remove(lockKey);
                return;
            }
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to prepare world!\n" + safeString(ex.getMessage()));
            worldExportLocks.remove(lockKey);
            return;
        }

        player.sendMessage(ChatColor.YELLOW + "Exporting map to /maps/" + gameKey + "/" + sourceWorldName + "...");

        Bukkit.getScheduler().runTaskAsynchronously(corePlugin, () -> {
            String exportError = "";
            String metadataError = "";
            try {
                copyDirectoryReplacing(sourcePath, targetPath);
            } catch (Exception ex) {
                exportError = safeString(ex.getMessage());
            }
            if (exportError.isEmpty()) {
                try {
                    registerExportedMapInConfig(gameKey, sourceWorldName);
                } catch (Exception ex) {
                    metadataError = safeString(ex.getMessage());
                }
            }

            final String finalExportError = exportError;
            final String finalMetadataError = metadataError;
            Bukkit.getScheduler().runTask(corePlugin, () -> {
                try {
                    World reloadedWorld = Bukkit.createWorld(new WorldCreator(sourceWorldName));
                    Player online = Bukkit.getPlayer(playerUuid);
                    if (reloadedWorld == null) {
                        if (online != null && online.isOnline()) {
                            online.sendMessage(ChatColor.RED + "Export finished, but failed to reload world \"" + sourceWorldName + "\"!");
                        }
                        return;
                    }

                    if (online != null && online.isOnline()) {
                        online.teleport(remapLocationToWorld(returnLocation, reloadedWorld));
                        if (finalExportError.isEmpty() && finalMetadataError.isEmpty()) {
                            online.sendMessage(ChatColor.GREEN + "Done!");
                        } else if (finalExportError.isEmpty()) {
                            online.sendMessage(ChatColor.RED + "Exported finished, but failed to update map config!\n" + finalMetadataError);
                        } else {
                            online.sendMessage(ChatColor.RED + "Export failed!\n" + finalExportError);
                        }
                    }
                } finally {
                    worldExportLocks.remove(lockKey);
                }
            });
        });
    }

    public boolean addSpawnFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }

        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();

        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                JsonArray spawns = getOrCreateArray(map, MAP_SPAWNS_KEY);
                spawns.add(toLocationJson(location, System.currentTimeMillis()));

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            sendDone(player, location, null);
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean addPregameSpawnFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        if (!gameType.isGame()) {
            player.sendMessage(ChatColor.RED + "Waiting spawn can only be set for game servers.");
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }

        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();

        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                // Waiting spawn is a single location for each map; replace any existing value.
                map.remove(MAP_PREGAME_SPAWN_KEY);
                map.add(MAP_PREGAME_SPAWN_KEY, toLocationJson(location, System.currentTimeMillis()));

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            sendDone(player, location, "waiting_spawn");
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean setHubSpawnFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        if (!gameType.isHub()) {
            player.sendMessage(ChatColor.RED + "Hub spawn can only be set for hub servers.");
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();
        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);
                long createdAt = System.currentTimeMillis();
                map.add(MAP_HUB_SPAWN_KEY, toLocationJson(location, createdAt));
                // Keep legacy-compatible field in sync for older readers.
                map.add(MAP_LOBBY_SPAWN_KEY, toLocationJson(location, createdAt));
                applyMapRotationDefaults(gameSection, resolvedMapName);
                // Setting hub spawn should always promote this map to active.
                gameSection.addProperty("activeMap", resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            sendDone(player, location, null);
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean addHubNpcFromMenu(Player player, ServerType gameType, String worldDirectory) {
        return addClickToPlayNpcFromMenu(player, gameType, worldDirectory);
    }

    public boolean addClickToPlayNpcFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        if (!gameType.isHub()) {
            player.sendMessage(ChatColor.RED + "Click to Play NPCs can only be added in hub edit mode.");
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();
        String entityId = UUID.randomUUID().toString();
        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                JsonArray npcs = getOrCreateArray(map, MAP_NPCS_KEY);
                JsonObject npc = toLocationJson(location, System.currentTimeMillis());
                npc.addProperty(MAP_ENTITY_ID_KEY, entityId);
                npc.addProperty(MAP_SKIN_OWNER_KEY, MURDER_MYSTERY_CLICK_TO_PLAY_SKIN_REFERENCE);
                npc.addProperty(MAP_HOLOGRAM_COLOR_KEY, DEFAULT_CLICK_TO_PLAY_NPC_COLOR);
                npc.addProperty(MAP_NPC_KIND_KEY, NPC_KIND_CLICK_TO_PLAY);
                npcs = new JsonArray();
                npcs.add(npc);
                map.add(MAP_NPCS_KEY, npcs);

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            despawnRuntimeNpcsForWorld(gameType, mapWorld, false, true);
            spawnNpcRuntime(
                    entityId,
                    location,
                    MURDER_MYSTERY_CLICK_TO_PLAY_SKIN_REFERENCE,
                    DEFAULT_CLICK_TO_PLAY_NPC_COLOR,
                    false,
                    true,
                    null,
                    gameType,
                    mapWorld
            );
            player.sendMessage(ChatColor.GREEN + "Done!");
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean addProfileNpcFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        if (!gameType.isHub()) {
            player.sendMessage(ChatColor.RED + "Profile NPCs can only be added in hub edit mode.");
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();
        UUID ownerUuid = player.getUniqueId();
        String ownerName = safeString(player.getName());
        if (ownerName.isEmpty()) {
            ownerName = "Player";
        }
        String entityId = UUID.randomUUID().toString();
        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                JsonArray profileNpcs = getOrCreateArray(map, MAP_PROFILE_NPCS_KEY);
                JsonObject npc = toLocationJson(location, System.currentTimeMillis());
                npc.addProperty(MAP_ENTITY_ID_KEY, entityId);
                npc.addProperty(MAP_OWNER_UUID_KEY, ownerUuid == null ? "" : ownerUuid.toString());
                npc.addProperty(MAP_OWNER_NAME_KEY, ownerName);
                npc.addProperty(MAP_HOLOGRAM_COLOR_KEY, DEFAULT_PROFILE_NPC_COLOR);
                profileNpcs = new JsonArray();
                profileNpcs.add(npc);
                map.add(MAP_PROFILE_NPCS_KEY, profileNpcs);

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            despawnRuntimeNpcsForWorld(gameType, mapWorld, true, false);
            spawnNpcRuntime(entityId, location, ownerName, DEFAULT_PROFILE_NPC_COLOR, true, false, ownerUuid, gameType, mapWorld);
            player.sendMessage(ChatColor.GREEN + "Done!");
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public String getLeaderboardMetricSelection(Player player) {
        if (player == null || player.getUniqueId() == null) {
            return LEADERBOARD_METRIC_KILLS;
        }
        UUID uuid = player.getUniqueId();
        String metric = normalizeLeaderboardMetric(leaderboardMetricByPlayer.get(uuid));
        leaderboardMetricByPlayer.put(uuid, metric);
        return metric;
    }

    public String toggleLeaderboardMetricSelection(Player player) {
        if (player == null || player.getUniqueId() == null) {
            return LEADERBOARD_METRIC_KILLS;
        }
        UUID uuid = player.getUniqueId();
        String next = nextLeaderboardMetric(getLeaderboardMetricSelection(player));
        leaderboardMetricByPlayer.put(uuid, next);
        return next;
    }

    public boolean addLeaderboardHologramFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        if (!gameType.isHub()) {
            player.sendMessage(ChatColor.RED + "Leaderboard holograms can only be added in hub edit mode.");
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();
        String entityId = UUID.randomUUID().toString();
        String selectedMetric = getLeaderboardMetricSelection(player);
        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                JsonArray leaderboards = getOrCreateArray(map, MAP_LEADERBOARDS_KEY);
                JsonObject board = toLocationJson(location, System.currentTimeMillis());
                board.addProperty(MAP_ENTITY_ID_KEY, entityId);
                board.addProperty(MAP_METRIC_KEY, normalizeLeaderboardMetric(selectedMetric));
                board.addProperty(MAP_LEADERBOARD_TITLE_COLOR_KEY, ChatColor.AQUA.name());
                board.addProperty(MAP_LEADERBOARD_MODE_COLOR_KEY, ChatColor.GRAY.name());
                board.addProperty(MAP_LEADERBOARD_RANK_COLOR_KEY, ChatColor.YELLOW.name());
                board.addProperty(MAP_LEADERBOARD_SEPARATOR_COLOR_KEY, ChatColor.GRAY.name());
                board.addProperty(MAP_LEADERBOARD_VALUE_COLOR_KEY, ChatColor.YELLOW.name());
                board.addProperty(MAP_LEADERBOARD_EMPTY_COLOR_KEY, ChatColor.DARK_GRAY.name());
                board.addProperty(MAP_LEADERBOARD_FOOTER_COLOR_KEY, ChatColor.GRAY.name());
                leaderboards = rebuildLeaderboardsForAdd(leaderboards, board, selectedMetric);
                map.add(MAP_LEADERBOARDS_KEY, leaderboards);

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            despawnRuntimeLeaderboardsForMetric(gameType, mapWorld, selectedMetric);
            spawnLeaderboardRuntime(entityId, location, gameType, mapWorld, selectedMetric, player.getUniqueId(), player.getName());
            sendDone(player, location, null);
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean handleParkourSetupFromMenu(Player player, ServerType gameType, String worldDirectory, boolean checkpointClick) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        if (!gameType.isHub()) {
            player.sendMessage(ChatColor.RED + "Parkour setup is only available for hub edit mode.");
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        if (hasParkourCourseConfigured(gameType, mapWorld)) {
            return resetParkourCourseFromMenu(player, gameType, mapWorld);
        }
        UUID uuid = player.getUniqueId();
        UUID activeEditorUuid = findActiveParkourEditor(gameType, mapWorld, uuid);
        if (activeEditorUuid != null) {
            player.sendMessage(parkourEditorBusyMessage(activeEditorUuid));
            return true;
        }
        ParkourDraft draft = parkourDraftsByPlayer.get(uuid);
        Location location = player.getLocation();
        if (checkpointClick) {
            if (draft == null || draft.start == null) {
                player.sendMessage(ChatColor.RED + "Set the parkour start first!");
                return true;
            }
            if (!mapWorld.equalsIgnoreCase(draft.worldDirectory) || draft.gameType != gameType) {
                draft = null;
                parkourDraftsByPlayer.remove(uuid);
                player.sendMessage(ChatColor.RED + "Parkour draft reset because you switched map/server type!");
                return true;
            }
            draft.checkpoints.add(location.clone());
            placeParkourMarker(location, parkourCheckpointHologramLines(draft.checkpoints.size()), true);
            player.sendMessage(ChatColor.GREEN + "Checkpoint " + draft.checkpoints.size() + " added!");
            return true;
        }

        if (draft == null || draft.start == null) {
            ParkourDraft created = new ParkourDraft();
            created.worldDirectory = mapWorld;
            created.gameType = gameType;
            created.start = location.clone();
            parkourDraftsByPlayer.put(uuid, created);
            placeParkourMarker(location, parkourStartHologramLines(), false);
            player.sendMessage(ChatColor.GREEN + "Parkour start added! Click again for end, right-click for checkpoints!");
            return true;
        }

        if (!mapWorld.equalsIgnoreCase(draft.worldDirectory) || draft.gameType != gameType) {
            parkourDraftsByPlayer.remove(uuid);
            player.sendMessage(ChatColor.RED + "Parkour draft reset because you switched map/server type!");
            return true;
        }

        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }

        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                JsonArray parkours = getOrCreateArray(map, MAP_PARKOURS_KEY);
                JsonObject route = new JsonObject();
                route.addProperty(MAP_ENTITY_ID_KEY, UUID.randomUUID().toString());
                route.addProperty(MAP_CREATED_AT_KEY, System.currentTimeMillis());
                route.addProperty(MAP_PARKOUR_TITLE_COLOR_KEY, ChatColor.YELLOW.name());
                route.addProperty(MAP_PARKOUR_START_COLOR_KEY, ChatColor.GREEN.name());
                route.addProperty(MAP_PARKOUR_CHECKPOINT_COLOR_KEY, ChatColor.AQUA.name());
                route.addProperty(MAP_PARKOUR_END_COLOR_KEY, ChatColor.RED.name());
                route.add(MAP_START_KEY, toLocationJson(draft.start, System.currentTimeMillis()));
                route.add(MAP_END_KEY, toLocationJson(location, System.currentTimeMillis()));
                JsonArray checkpoints = new JsonArray();
                for (Location checkpoint : draft.checkpoints) {
                    if (checkpoint == null) {
                        continue;
                    }
                    checkpoints.add(toLocationJson(checkpoint, System.currentTimeMillis()));
                }
                route.add(MAP_CHECKPOINTS_KEY, checkpoints);
                parkours = new JsonArray();
                parkours.add(route);
                map.add(MAP_PARKOURS_KEY, parkours);

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            placeParkourMarker(location, parkourEndHologramLines(), false);
            player.sendMessage(ChatColor.GREEN + "Parkour route saved with " + draft.checkpoints.size() + " checkpoints!");
            parkourDraftsByPlayer.remove(uuid);
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean hasParkourCourseConfigured(ServerType gameType, String worldDirectory) {
        if (gameType == null || gameType == ServerType.UNKNOWN) {
            return false;
        }
        List<MapLocationEntry> locations = loadMapLocations(gameType, worldDirectory);
        if (locations == null || locations.isEmpty()) {
            return false;
        }
        for (MapLocationEntry entry : locations) {
            if (entry == null || entry.getType() == null) {
                continue;
            }
            if (entry.getType() == MapLocationType.PARKOUR_ROUTE
                    || entry.getType() == MapLocationType.PARKOUR_START
                    || entry.getType() == MapLocationType.PARKOUR_END
                    || entry.getType() == MapLocationType.PARKOUR_CHECKPOINT) {
                return true;
            }
        }
        return false;
    }

    private boolean resetParkourCourseFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }

        List<MapLocationEntry> markers = new ArrayList<MapLocationEntry>();
        boolean cleared = false;
        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findMapByWorldDirectory(maps, mapWorld);
                if (map == null) {
                    map = findMap(maps, mapWorld);
                }
                if (map != null) {
                    JsonArray parkours = existingArray(map, MAP_PARKOURS_KEY);
                    if (parkours != null && parkours.size() > 0) {
                        markers = collectAllParkourMarkers(map);
                        map.add(MAP_PARKOURS_KEY, new JsonArray());
                        String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);
                        applyMapRotationDefaults(gameSection, resolvedMapName);
                        saveMapConfigRoot(store, gameKey, root);
                        cleared = true;
                    }
                }
            }
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
            return true;
        }

        clearParkourDraftsForMap(gameType, mapWorld);
        if (cleared) {
            removeParkourWorldMarkers(markers, player.getWorld());
            player.sendMessage(ChatColor.GREEN + "Parkour course reset.");
        } else {
            player.sendMessage(ChatColor.RED + "No parkour course is configured for this map.");
        }
        return true;
    }

    private void clearParkourDraftsForMap(ServerType gameType, String worldDirectory) {
        if (gameType == null) {
            return;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() || parkourDraftsByPlayer.isEmpty()) {
            return;
        }
        List<UUID> remove = new ArrayList<UUID>();
        for (Map.Entry<UUID, ParkourDraft> entry : parkourDraftsByPlayer.entrySet()) {
            if (entry == null) {
                continue;
            }
            ParkourDraft draft = entry.getValue();
            if (draft == null || draft.gameType != gameType) {
                continue;
            }
            if (!mapWorld.equalsIgnoreCase(safeString(draft.worldDirectory))) {
                continue;
            }
            UUID key = entry.getKey();
            if (key != null) {
                remove.add(key);
            }
        }
        for (UUID key : remove) {
            parkourDraftsByPlayer.remove(key);
        }
    }

    private JsonArray rebuildLeaderboardsForAdd(JsonArray current, JsonObject replacement, String metric) {
        String targetMetric = normalizeLeaderboardMetric(metric);
        JsonArray rebuilt = new JsonArray();
        List<String> keptMetrics = new ArrayList<String>();
        if (current != null) {
            for (JsonElement raw : current) {
                if (raw == null || !raw.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = raw.getAsJsonObject();
                String candidateMetric = normalizeLeaderboardMetric(safeString(candidate, MAP_METRIC_KEY));
                if (targetMetric.equals(candidateMetric)) {
                    continue;
                }
                if (keptMetrics.contains(candidateMetric)) {
                    continue;
                }
                candidate.addProperty(MAP_METRIC_KEY, candidateMetric);
                rebuilt.add(candidate);
                keptMetrics.add(candidateMetric);
            }
        }
        if (replacement != null) {
            replacement.addProperty(MAP_METRIC_KEY, targetMetric);
            rebuilt.add(replacement);
        }
        return rebuilt;
    }

    private void despawnRuntimeNpcsForWorld(ServerType gameType,
                                            String worldDirectory,
                                            boolean profileNpc,
                                            boolean clickToPlayNpc) {
        if (gameType == null || gameType == ServerType.UNKNOWN) {
            return;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty()) {
            return;
        }
        List<RuntimeNpc> snapshot = new ArrayList<RuntimeNpc>(npcsByEntityId.values());
        for (RuntimeNpc runtime : snapshot) {
            if (runtime == null) {
                continue;
            }
            if (runtime.profileNpc != profileNpc || runtime.clickToPlayNpc != clickToPlayNpc) {
                continue;
            }
            if (runtime.gameType != gameType) {
                continue;
            }
            if (!mapWorld.equalsIgnoreCase(safeString(runtime.worldDirectory))) {
                continue;
            }
            despawnRuntimeNpc(runtime);
        }
    }

    private void despawnRuntimeLeaderboardsForMetric(ServerType gameType, String worldDirectory, String metric) {
        if (gameType == null || gameType == ServerType.UNKNOWN) {
            return;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty()) {
            return;
        }
        String targetMetric = normalizeLeaderboardMetric(metric);
        List<RuntimeLeaderboard> snapshot = new ArrayList<RuntimeLeaderboard>(leaderboardsByAnchor.values());
        for (RuntimeLeaderboard runtime : snapshot) {
            if (runtime == null) {
                continue;
            }
            if (runtime.gameType != gameType) {
                continue;
            }
            if (!mapWorld.equalsIgnoreCase(safeString(runtime.worldDirectory))) {
                continue;
            }
            if (!targetMetric.equals(normalizeLeaderboardMetric(runtime.metric))) {
                continue;
            }
            despawnLeaderboard(runtime);
        }
    }

    private UUID findActiveParkourEditor(ServerType gameType, String worldDirectory, UUID excludePlayerUuid) {
        if (gameType == null || worldDirectory == null || worldDirectory.trim().isEmpty()) {
            return null;
        }
        for (Map.Entry<UUID, ParkourDraft> entry : parkourDraftsByPlayer.entrySet()) {
            if (entry == null) {
                continue;
            }
            UUID editorUuid = entry.getKey();
            if (editorUuid == null || editorUuid.equals(excludePlayerUuid)) {
                continue;
            }
            ParkourDraft draft = entry.getValue();
            if (draft == null || draft.start == null || draft.gameType != gameType) {
                continue;
            }
            if (!safeString(worldDirectory).equalsIgnoreCase(safeString(draft.worldDirectory))) {
                continue;
            }
            return editorUuid;
        }
        return null;
    }

    private String parkourEditorBusyMessage(UUID editorUuid) {
        Player editor = editorUuid == null ? null : Bukkit.getPlayer(editorUuid);
        Profile profile = corePlugin == null || editorUuid == null ? null : corePlugin.getProfile(editorUuid);
        Rank rank = profile == null || profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        int networkLevel = profile == null ? 0 : Math.max(0, profile.getNetworkLevel());
        String plusColor = profile == null ? null : profile.getPlusColor();
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        String prefix = RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
        ChatColor nameColor = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);

        String name = editor == null ? "" : safeString(editor.getName());
        if (name.isEmpty() && profile != null) {
            name = safeString(profile.getName());
        }
        if (name.isEmpty()) {
            name = "A player";
        }
        return prefix + nameColor + name + ChatColor.RED + " is currently editing the parkour challenge!";
    }

    public boolean handleEntityInteract(Player player, Entity entity) {
        if (player == null || entity == null) {
            return false;
        }
        RuntimeNpc runtime = resolveRuntimeNpc(entity.getUniqueId());
        if (runtime == null) {
            return false;
        }
        if (runtime.profileNpc) {
            applyProfileNpcViewerHologram(runtime, player);
            player.sendMessage(ChatColor.RED + "Stats are currently disabled!");
            return true;
        }
        if (runtime.clickToPlayNpc) {
            applyNpcVisuals(runtime);
            player.sendMessage(ChatColor.GOLD + "Click to Play NPC configured.");
            return true;
        }
        player.sendMessage(ChatColor.RED + "Only Profile NPC and Click to Play NPC are supported here.");
        return true;
    }

    public boolean updateNpcSkinFromMenu(Player player, UUID entityUuid, String skinOwner) {
        RuntimeNpc runtime = entityUuid == null ? null : npcsByEntityId.get(entityUuid);
        if (runtime == null) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "That NPC is no longer available.");
            }
            return false;
        }
        if (runtime.profileNpc) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "Profile NPC skin is tied to the profile owner.");
            }
            return false;
        }
        if (runtime.clickToPlayNpc) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "Click to Play NPC skin is fixed for Murder Mystery.");
            }
            return false;
        }
        String resolvedSkin = safeString(skinOwner);
        if (resolvedSkin.isEmpty()) {
            resolvedSkin = DEFAULT_NPC_SKIN;
        }
        runtime.skinOwner = resolvedSkin;
        respawnNpcBaseEntity(runtime);
        applyNpcVisuals(runtime);
        saveNpcVisual(runtime, false);
        if (player != null) {
            player.sendMessage(ChatColor.GREEN + "NPC skin updated.");
        }
        return true;
    }

    public boolean updateNpcHologramColorFromMenu(Player player, UUID entityUuid, ChatColor color) {
        RuntimeNpc runtime = entityUuid == null ? null : npcsByEntityId.get(entityUuid);
        if (runtime == null) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "That NPC is no longer available.");
            }
            return false;
        }
        if (runtime.clickToPlayNpc) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "Click to Play NPC hologram style is fixed.");
            }
            return false;
        }
        if (runtime.profileNpc) {
            if (player != null) {
                player.sendMessage(ChatColor.RED + "Profile NPC hologram style is fixed.");
            }
            return false;
        }
        ChatColor resolved = color == null ? ChatColor.GREEN : color;
        runtime.hologramColor = resolved.name();
        applyNpcVisuals(runtime);
        saveNpcVisual(runtime, runtime.profileNpc);
        if (player != null) {
            player.sendMessage(ChatColor.GREEN + "NPC hologram color updated.");
        }
        return true;
    }

    public boolean addItemDropFromMenu(Player player, ServerType gameType, String worldDirectory, ItemStack itemStack) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return true;
        }
        Material selectedItem = itemStack == null ? Material.AIR : itemStack.getType();
        if (selectedItem == Material.AIR) {
            player.sendMessage(ChatColor.RED + "You have to hold an item!");
            return true;
        }
        int selectedData = itemStack == null ? 0 : Math.max(0, itemStack.getDurability());
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return true;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return true;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return true;
        }
        Location location = player.getLocation();

        try {
            synchronized (mapConfigLock) {
                JsonObject root = loadMapConfigRoot(store, gameKey);
                JsonObject gameSection = getOrCreateGameSection(root, gameKey);
                JsonArray maps = getOrCreateArray(gameSection, "maps");
                JsonObject map = findOrCreateMapByWorldDirectory(maps, mapWorld);
                String resolvedMapName = mapWorldDirectoryOf(map, mapWorld);

                JsonArray drops = getOrCreateDropItemArray(map);
                JsonObject drop = toLocationJson(location, System.currentTimeMillis());
                drop.addProperty("item", selectedItem.name());
                drop.addProperty("itemData", selectedData);
                drops.add(drop);

                applyMapRotationDefaults(gameSection, resolvedMapName);
                saveMapConfigRoot(store, gameKey, root);
            }
            sendDone(player, location, selectedItem.name());
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to update map config in MongoDB!\n" + ex.getMessage());
        }
        return true;
    }

    public boolean deleteMapLocationFromMenu(Player player,
                                             ServerType gameType,
                                             String worldDirectory,
                                             MapLocationEntry entry) {
        if (player == null || entry == null) {
            return false;
        }
        List<MapLocationEntry> parkourMarkers = collectParkourMarkersForDeletion(gameType, worldDirectory, entry);
        boolean removed = deleteMapLocation(gameType, worldDirectory, entry);
        if (!removed) {
            return false;
        }
        removeParkourWorldMarkers(parkourMarkers, player.getWorld());
        World world = resolveWorldByName(entry.getWorldName());
        if (world == null) {
            world = player.getWorld();
        }
        Location location = new Location(world, entry.getX(), entry.getY(), entry.getZ(), entry.getYaw(), entry.getPitch());
        String itemName = entry.getType() == MapLocationType.ITEM_DROP ? safeString(entry.getItemName()) : null;
        sendDeleted(player, location, itemName);
        return true;
    }

    public boolean clearMapLocationsFromMenu(Player player, ServerType gameType, String worldDirectory) {
        if (player == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return false;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty() && player.getWorld() != null) {
            mapWorld = safeString(player.getWorld().getName());
        }
        if (mapWorld.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve the map world!");
            return false;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Failed to resolve game key for that game type!");
            return false;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            player.sendMessage(ChatColor.RED + "MongoDB map config store is unavailable.");
            return false;
        }
        List<MapLocationEntry> parkourMarkers = new ArrayList<MapLocationEntry>();
        synchronized (mapConfigLock) {
            JsonObject root = loadMapConfigRoot(store, gameKey);
            JsonObject gameSection = getOrCreateGameSection(root, gameKey);
            JsonArray maps = getOrCreateArray(gameSection, "maps");
            JsonObject map = findMapByWorldDirectory(maps, mapWorld);
            if (map == null) {
                map = findMap(maps, mapWorld);
            }
            if (map == null) {
                player.sendMessage(ChatColor.RED + "No saved map data found for this world.");
                return false;
            }
            parkourMarkers = collectAllParkourMarkers(map);
            map.add(MAP_SPAWNS_KEY, new JsonArray());
            map.add(MAP_DROP_ITEMS_KEY, new JsonArray());
            map.add(MAP_NPCS_KEY, new JsonArray());
            map.add(MAP_PROFILE_NPCS_KEY, new JsonArray());
            map.add(MAP_LEADERBOARDS_KEY, new JsonArray());
            map.add(MAP_PARKOURS_KEY, new JsonArray());
            map.remove(MAP_PREGAME_SPAWN_KEY);
            map.remove(MAP_HUB_SPAWN_KEY);
            map.remove(MAP_LOBBY_SPAWN_KEY);
            saveMapConfigRoot(store, gameKey, root);
        }
        despawnAllRuntimeArtifacts();
        removeParkourWorldMarkers(parkourMarkers, player.getWorld());
        player.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }

    public List<MapLocationEntry> loadMapLocations(ServerType gameType, String worldDirectory) {
        ServerType type = gameType == null ? ServerType.UNKNOWN : gameType;
        String mapWorld = safeString(worldDirectory);
        if (type == ServerType.UNKNOWN || mapWorld.isEmpty()) {
            return Collections.emptyList();
        }
        String gameKey = gameKeyForType(type);
        if (gameKey.isEmpty()) {
            return Collections.emptyList();
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            return Collections.emptyList();
        }
        synchronized (mapConfigLock) {
            JsonObject root = loadMapConfigRoot(store, gameKey);
            JsonObject gameSection = getOrCreateGameSection(root, gameKey);
            JsonArray maps = getOrCreateArray(gameSection, "maps");
            JsonObject map = findMap(maps, mapWorld);
            if (map == null) {
                return Collections.emptyList();
            }
            List<MapLocationEntry> entries = new ArrayList<>();
            appendLocationEntries(entries, getOrCreateArray(map, MAP_SPAWNS_KEY), MapLocationType.PLAYER_SPAWN);
            if (!type.isHub()) {
                appendLocationEntries(entries, getOrCreateDropItemArray(map), MapLocationType.ITEM_DROP);
            }
            appendSingleLocationEntry(entries, child(map, MAP_PREGAME_SPAWN_KEY), MapLocationType.WAITING_SPAWN);
            appendSingleLocationEntry(entries, child(map, MAP_HUB_SPAWN_KEY), MapLocationType.HUB_SPAWN);
            appendLocationEntries(entries, getOrCreateArray(map, MAP_NPCS_KEY), MapLocationType.HUB_NPC);
            appendLocationEntries(entries, getOrCreateArray(map, MAP_PROFILE_NPCS_KEY), MapLocationType.PROFILE_NPC);
            appendLocationEntries(entries, getOrCreateArray(map, MAP_LEADERBOARDS_KEY), MapLocationType.LEADERBOARD);
            appendParkourEntries(entries, getOrCreateArray(map, MAP_PARKOURS_KEY));
            sortMostRecentFirst(entries);
            return entries;
        }
    }

    public boolean deleteMapLocation(ServerType gameType, String worldDirectory, MapLocationEntry entry) {
        if (gameType == null || gameType == ServerType.UNKNOWN || entry == null) {
            return false;
        }
        String mapWorld = safeString(worldDirectory);
        if (mapWorld.isEmpty()) {
            return false;
        }
        String gameKey = gameKeyForType(gameType);
        if (gameKey.isEmpty()) {
            return false;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            return false;
        }
        synchronized (mapConfigLock) {
            JsonObject root = loadMapConfigRoot(store, gameKey);
            JsonObject gameSection = getOrCreateGameSection(root, gameKey);
            JsonArray maps = getOrCreateArray(gameSection, "maps");
            JsonObject map = findMapByWorldDirectory(maps, mapWorld);
            if (map == null) {
                map = findMap(maps, mapWorld);
            }
            if (map == null) {
                return false;
            }
            if (entry.getType() == MapLocationType.WAITING_SPAWN) {
                if (!map.has(MAP_PREGAME_SPAWN_KEY)) {
                    return false;
                }
                map.remove(MAP_PREGAME_SPAWN_KEY);
                saveMapConfigRoot(store, gameKey, root);
                return true;
            }
            if (entry.getType() == MapLocationType.HUB_SPAWN) {
                if (!map.has(MAP_HUB_SPAWN_KEY) && !map.has(MAP_LOBBY_SPAWN_KEY)) {
                    return false;
                }
                map.remove(MAP_HUB_SPAWN_KEY);
                map.remove(MAP_LOBBY_SPAWN_KEY);
                saveMapConfigRoot(store, gameKey, root);
                return true;
            }
            if (entry.getType() == MapLocationType.PARKOUR_START
                    || entry.getType() == MapLocationType.PARKOUR_END
                    || entry.getType() == MapLocationType.PARKOUR_CHECKPOINT
                    || entry.getType() == MapLocationType.PARKOUR_ROUTE) {
                JsonArray parkours = existingArray(map, MAP_PARKOURS_KEY);
                if (parkours == null || parkours.size() == 0) {
                    return false;
                }
                int routeIndex = resolveParkourRouteIndex(parkours, entry);
                if (routeIndex < 0 || routeIndex >= parkours.size()) {
                    return false;
                }
                JsonArray rebuilt = new JsonArray();
                for (int i = 0; i < parkours.size(); i++) {
                    if (i == routeIndex) {
                        continue;
                    }
                    JsonElement current = parkours.get(i);
                    if (current != null) {
                        rebuilt.add(current);
                    }
                }
                map.add(MAP_PARKOURS_KEY, rebuilt);
                saveMapConfigRoot(store, gameKey, root);
                return true;
            }
            JsonArray locations = existingLocationArray(map, entry.getType());
            if (locations == null || locations.size() == 0) {
                return false;
            }
            int removeIndex = -1;
            int fallbackIndex = entry.getArrayIndex();
            if (fallbackIndex >= 0 && fallbackIndex < locations.size()) {
                JsonElement fallbackEntry = locations.get(fallbackIndex);
                if (fallbackEntry != null && fallbackEntry.isJsonObject()
                        && matchesLocation(fallbackEntry.getAsJsonObject(), entry)) {
                    removeIndex = fallbackIndex;
                }
            }
            if (removeIndex < 0) {
                removeIndex = findLocationIndex(locations, entry);
            }
            if (removeIndex < 0) {
                return false;
            }
            String runtimeId = runtimeIdAt(locations, removeIndex);
            removeLocationAt(map, entry.getType(), locations, removeIndex);
            saveMapConfigRoot(store, gameKey, root);
            cleanupRuntimeAfterDelete(entry.getType(), runtimeId, entry);
            return true;
        }
    }

    public void teleportToMapLocation(Player player, MapLocationEntry entry, String fallbackWorldDirectory) {
        if (player == null || entry == null) {
            return;
        }
        World world = resolveWorldByName(entry.getWorldName());
        if (world == null) {
            world = resolveWorldByName(fallbackWorldDirectory);
        }
        if (world == null) {
            world = player.getWorld();
        }
        if (world == null) {
            return;
        }
        player.teleport(new Location(
                world,
                entry.getX(),
                entry.getY(),
                entry.getZ(),
                entry.getYaw(),
                entry.getPitch()
        ));
    }

    public List<String> completeGameTypes(String prefix) {
        return filterByPrefix(serverTypeOptions(), prefix);
    }

    public ServerType oppositeServerType(ServerType type) {
        ServerType resolved = type == null ? ServerType.UNKNOWN : type;
        if (resolved == ServerType.UNKNOWN) {
            return ServerType.UNKNOWN;
        }
        if (resolved.isHub()) {
            return serverTypeVariantFor(resolved, false);
        }
        if (resolved.isGame()) {
            return serverTypeVariantFor(resolved, true);
        }
        return ServerType.UNKNOWN;
    }

    private List<String> serverTypeOptions() {
        final int FLAG_HUB = 1;
        final int FLAG_GAME = 2;
        Map<String, Integer> grouped = new HashMap<String, Integer>();
        for (ServerType type : ServerType.values()) {
            if (!isSupportedGameType(type)) {
                continue;
            }
            String gameType = gameTypeName(type.name());
            if (gameType.isEmpty()) {
                continue;
            }
            Integer existing = grouped.get(gameType);
            int flags = existing == null ? 0 : existing.intValue();
            if (type.isHub()) {
                flags |= FLAG_HUB;
            }
            if (type.isGame()) {
                flags |= FLAG_GAME;
            }
            grouped.put(gameType, flags);
        }

        List<String> values = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : grouped.entrySet()) {
            String gameType = entry.getKey();
            int flags = entry.getValue() == null ? 0 : entry.getValue().intValue();
            if ((flags & FLAG_HUB) != 0) {
                values.add(gameType + "_HUB");
            }
            if ((flags & FLAG_GAME) != 0) {
                values.add(gameType + "_GAME");
            }
        }
        Collections.sort(values);
        return values;
    }

    private ServerType serverTypeVariantFor(ServerType sourceType, boolean hub) {
        if (sourceType == null || sourceType == ServerType.UNKNOWN) {
            return ServerType.UNKNOWN;
        }
        String base = gameTypeName(sourceType.name());
        if (base.isEmpty()) {
            return ServerType.UNKNOWN;
        }
        for (ServerType candidate : ServerType.values()) {
            if (!isSupportedGameType(candidate)) {
                continue;
            }
            if (!base.equals(gameTypeName(candidate.name()))) {
                continue;
            }
            if (hub && candidate.isHub()) {
                return candidate;
            }
            if (!hub && candidate.isGame()) {
                return candidate;
            }
        }
        return ServerType.UNKNOWN;
    }

    private String normalizeTypeToken(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(trimmed.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            boolean alphaNumeric = (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
            if (alphaNumeric) {
                normalized.append(ch);
                previousUnderscore = false;
                continue;
            }
            if (normalized.length() > 0 && !previousUnderscore) {
                normalized.append('_');
                previousUnderscore = true;
            }
        }
        int length = normalized.length();
        while (length > 0 && normalized.charAt(length - 1) == '_') {
            normalized.setLength(length - 1);
            length = normalized.length();
        }
        return normalized.toString();
    }

    private String compactToken(String value) {
        String normalized = normalizeTypeToken(value);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder compact = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch != '_') {
                compact.append(ch);
            }
        }
        return compact.toString();
    }

    private String gameTypeName(String input) {
        String normalized = normalizeTypeToken(input);
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.endsWith("_HUB") && normalized.length() > "_HUB".length()) {
            normalized = normalized.substring(0, normalized.length() - "_HUB".length());
        }
        if (normalized.endsWith("_GAME") && normalized.length() > "_GAME".length()) {
            normalized = normalized.substring(0, normalized.length() - "_GAME".length());
        }
        if (normalized.isEmpty()
                || "HUB".equals(normalized)
                || "GAME".equals(normalized)
                || "UNKNOWN".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private boolean isSupportedGameType(ServerType type) {
        return type != null && type != ServerType.UNKNOWN && !type.isBuild();
    }

    private boolean hasExplicitSuffix(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        return normalized.endsWith("_HUB") || normalized.endsWith("_GAME");
    }

    private String gameKeyForType(ServerType type) {
        if (type == null || type == ServerType.UNKNOWN) {
            return "";
        }
        String base = gameTypeName(type.name()).toLowerCase(Locale.ROOT).replace("_", "").trim();
        if (base.isEmpty()) {
            String fallback = type.getGameTypeDisplayName();
            if (fallback == null) {
                return "";
            }
            base = fallback.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").trim();
        }
        return MapConfigStore.normalizeGameKey(base);
    }

    private MapConfigStore mapConfigStore() {
        if (corePlugin == null) {
            return null;
        }
        MongoManager mongo = corePlugin.getMongoManager();
        if (mongo == null) {
            return null;
        }
        return new MapConfigStore(mongo);
    }

    private JsonObject loadMapConfigRoot(MapConfigStore store, String gameKey) {
        if (store == null) {
            return new JsonObject();
        }
        store.ensureDefaults(gameKey);
        JsonObject root = store.loadRoot(gameKey);
        if (root == null && !MapConfigStore.DEFAULT_GAME_KEY.equals(gameKey)) {
            store.ensureDefaults(MapConfigStore.DEFAULT_GAME_KEY);
            root = store.loadRoot(MapConfigStore.DEFAULT_GAME_KEY);
        }
        return root == null ? new JsonObject() : root;
    }

    private void saveMapConfigRoot(MapConfigStore store, String gameKey, JsonObject root) {
        if (store == null) {
            throw new IllegalStateException("MongoDB map config store is unavailable.");
        }
        String resolvedGameKey = MapConfigStore.normalizeGameKey(gameKey);
        if (resolvedGameKey.isEmpty()) {
            resolvedGameKey = MapConfigStore.DEFAULT_GAME_KEY;
        }
        store.saveRoot(resolvedGameKey, root == null ? new JsonObject() : root);
        publishMapConfigUpdate(resolvedGameKey);
    }

    private void publishMapConfigUpdate(String gameKey) {
        if (corePlugin == null) {
            return;
        }
        PubSubService pubSub = corePlugin.getPubSubService();
        if (pubSub == null) {
            return;
        }
        String resolvedGameKey = MapConfigStore.normalizeGameKey(gameKey);
        if (resolvedGameKey.isEmpty()) {
            resolvedGameKey = MapConfigStore.DEFAULT_GAME_KEY;
        }
        try {
            pubSub.publish(MAP_CONFIG_UPDATE_CHANNEL, MAP_CONFIG_UPDATE_PREFIX + resolvedGameKey);
        } catch (Exception ignored) {
            // Mongo save already succeeded; Redis fan-out is best effort.
        }
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

    private JsonObject getOrCreateGameSection(JsonObject root, String gameKey) {
        if (root == null) {
            return new JsonObject();
        }
        String key = MapConfigStore.normalizeGameKey(gameKey);
        if (key.isEmpty()) {
            key = MapConfigStore.DEFAULT_GAME_KEY;
        }
        JsonObject gameTypes = getOrCreateObject(root, "gameTypes");
        JsonObject section = child(gameTypes, key);
        if (section != null) {
            return section;
        }

        JsonObject legacy = child(root, key);
        if (legacy != null) {
            root.remove(key);
            gameTypes.add(key, legacy);
            return legacy;
        }

        JsonObject created = new JsonObject();
        gameTypes.add(key, created);
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

    private JsonObject child(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key)) {
            return null;
        }
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        return value.getAsJsonObject();
    }

    private JsonObject findMap(JsonArray maps, String mapName) {
        if (maps == null || mapName == null || mapName.trim().isEmpty()) {
            return null;
        }
        String target = mapName.trim();
        for (JsonElement entry : maps) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            String worldDirectory = safeString(map, "worldDirectory");
            String candidate = safeString(map, "name");
            if (worldDirectory.isEmpty() && !candidate.isEmpty()) {
                map.addProperty("worldDirectory", candidate);
                worldDirectory = candidate;
            }
            boolean matchedWorldDirectory = target.equalsIgnoreCase(worldDirectory);
            boolean matchedDisplayName = target.equalsIgnoreCase(candidate);
            if (matchedWorldDirectory || matchedDisplayName) {
                getOrCreateArray(map, MAP_SPAWNS_KEY);
                getOrCreateDropItemArray(map);
                if (!map.has("nightTime")) {
                    map.addProperty("nightTime", false);
                }
                // Prefer the concrete world folder name when the match came from display name.
                if (!matchedWorldDirectory && matchedDisplayName && !target.isEmpty()) {
                    worldDirectory = target;
                    map.addProperty("worldDirectory", target);
                }
                if (!worldDirectory.isEmpty()) {
                    map.addProperty("name", MapConfigStore.displayNameFromWorldDirectory(worldDirectory));
                }
                return map;
            }
        }
        return null;
    }

    private JsonObject findMapByWorldDirectory(JsonArray maps, String worldDirectory) {
        if (maps == null) {
            return null;
        }
        String target = safeString(worldDirectory);
        if (target.isEmpty()) {
            return null;
        }
        for (JsonElement entry : maps) {
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject map = entry.getAsJsonObject();
            String candidate = safeString(map, "worldDirectory");
            if (target.equalsIgnoreCase(candidate)) {
                return map;
            }
        }
        return null;
    }

    private JsonObject findOrCreateMapByWorldDirectory(JsonArray maps, String worldDirectory) {
        String resolvedWorldDirectory = safeString(worldDirectory);
        JsonObject existing = findMap(maps, resolvedWorldDirectory);
        if (existing != null) {
            return existing;
        }
        JsonObject map = new JsonObject();
        map.addProperty("worldDirectory", resolvedWorldDirectory);
        map.addProperty("name", MapConfigStore.displayNameFromWorldDirectory(resolvedWorldDirectory));
        map.addProperty("nightTime", false);
        map.add(MAP_SPAWNS_KEY, new JsonArray());
        map.add(MAP_DROP_ITEMS_KEY, new JsonArray());
        maps.add(map);
        return map;
    }

    private String mapWorldDirectoryOf(JsonObject map, String fallback) {
        String fallbackWorldDirectory = safeString(fallback);
        if (map != null) {
            String worldDirectory = safeString(map, "worldDirectory");
            if (worldDirectory.isEmpty()) {
                worldDirectory = fallbackWorldDirectory;
                if (worldDirectory.isEmpty()) {
                    worldDirectory = safeString(map, "name");
                }
                if (!worldDirectory.isEmpty()) {
                    map.addProperty("worldDirectory", worldDirectory);
                }
            }
            if (!worldDirectory.isEmpty()) {
                map.addProperty("name", MapConfigStore.displayNameFromWorldDirectory(worldDirectory));
                return worldDirectory;
            }
        }
        return fallbackWorldDirectory;
    }

    private void appendLocationEntries(List<MapLocationEntry> target, JsonArray locations, MapLocationType type) {
        if (target == null || locations == null || type == null) {
            return;
        }
        for (int i = 0; i < locations.size(); i++) {
            JsonElement entry = locations.get(i);
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            JsonObject location = entry.getAsJsonObject();
            target.add(new MapLocationEntry(
                    type,
                    i,
                    safeString(location, "world"),
                    safeDouble(location, "x"),
                    safeDouble(location, "y"),
                    safeDouble(location, "z"),
                    (float) safeDouble(location, "yaw"),
                    (float) safeDouble(location, "pitch"),
                    locationItemName(location, type),
                    safeInt(location, "itemData"),
                    safeLong(location, MAP_CREATED_AT_KEY)
            ));
        }
    }

    private String locationItemName(JsonObject location, MapLocationType type) {
        if (location == null || type == null) {
            return "";
        }
        if (type == MapLocationType.ITEM_DROP) {
            return safeString(location, "item");
        }
        if (type == MapLocationType.HUB_NPC) {
            return safeString(location, MAP_SKIN_OWNER_KEY);
        }
        if (type == MapLocationType.PROFILE_NPC) {
            String ownerName = safeString(location, MAP_OWNER_NAME_KEY);
            return ownerName.isEmpty() ? safeString(location, MAP_OWNER_UUID_KEY) : ownerName;
        }
        if (type == MapLocationType.LEADERBOARD) {
            return normalizeLeaderboardMetric(safeString(location, MAP_METRIC_KEY));
        }
        return "";
    }

    private void appendSingleLocationEntry(List<MapLocationEntry> target, JsonObject location, MapLocationType type) {
        if (target == null || location == null || type == null) {
            return;
        }
        target.add(new MapLocationEntry(
                type,
                0,
                safeString(location, "world"),
                safeDouble(location, "x"),
                safeDouble(location, "y"),
                safeDouble(location, "z"),
                (float) safeDouble(location, "yaw"),
                (float) safeDouble(location, "pitch"),
                locationItemName(location, type),
                0,
                safeLong(location, MAP_CREATED_AT_KEY)
        ));
    }

    private void appendParkourEntries(List<MapLocationEntry> target, JsonArray parkours) {
        if (target == null || parkours == null) {
            return;
        }
        for (int i = 0; i < parkours.size(); i++) {
            JsonElement raw = parkours.get(i);
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            JsonObject parkour = raw.getAsJsonObject();
            JsonObject start = child(parkour, MAP_START_KEY);
            JsonObject end = child(parkour, MAP_END_KEY);
            JsonArray points = existingArray(parkour, MAP_CHECKPOINTS_KEY);
            int checkpoints = points == null ? 0 : points.size();
            long routeCreatedAt = safeLong(parkour, MAP_CREATED_AT_KEY);
            if (start != null) {
                target.add(new MapLocationEntry(
                        MapLocationType.PARKOUR_START,
                        i,
                        safeString(start, "world"),
                        safeDouble(start, "x"),
                        safeDouble(start, "y"),
                        safeDouble(start, "z"),
                        (float) safeDouble(start, "yaw"),
                        (float) safeDouble(start, "pitch"),
                        String.valueOf(Math.max(0, checkpoints)),
                        -1,
                        safeLong(start, MAP_CREATED_AT_KEY) > 0L ? safeLong(start, MAP_CREATED_AT_KEY) : routeCreatedAt
                ));
            }
            if (end != null) {
                target.add(new MapLocationEntry(
                        MapLocationType.PARKOUR_END,
                        i,
                        safeString(end, "world"),
                        safeDouble(end, "x"),
                        safeDouble(end, "y"),
                        safeDouble(end, "z"),
                        (float) safeDouble(end, "yaw"),
                        (float) safeDouble(end, "pitch"),
                        "",
                        -1,
                        safeLong(end, MAP_CREATED_AT_KEY) > 0L ? safeLong(end, MAP_CREATED_AT_KEY) : routeCreatedAt
                ));
            }
            if (points == null || points.size() == 0) {
                continue;
            }
            for (int checkpointIndex = 0; checkpointIndex < points.size(); checkpointIndex++) {
                JsonElement checkpointRaw = points.get(checkpointIndex);
                if (checkpointRaw == null || !checkpointRaw.isJsonObject()) {
                    continue;
                }
                JsonObject checkpoint = checkpointRaw.getAsJsonObject();
                target.add(new MapLocationEntry(
                        MapLocationType.PARKOUR_CHECKPOINT,
                        i,
                        safeString(checkpoint, "world"),
                        safeDouble(checkpoint, "x"),
                        safeDouble(checkpoint, "y"),
                        safeDouble(checkpoint, "z"),
                        (float) safeDouble(checkpoint, "yaw"),
                        (float) safeDouble(checkpoint, "pitch"),
                        String.valueOf(checkpointIndex + 1),
                        checkpointIndex,
                        safeLong(checkpoint, MAP_CREATED_AT_KEY) > 0L ? safeLong(checkpoint, MAP_CREATED_AT_KEY) : routeCreatedAt
                ));
            }
        }
    }

    private int resolveParkourRouteIndex(JsonArray parkours, MapLocationEntry entry) {
        if (parkours == null || entry == null || parkours.size() == 0) {
            return -1;
        }
        int fallback = entry.getArrayIndex();
        if (fallback >= 0 && fallback < parkours.size()) {
            return fallback;
        }
        for (int i = 0; i < parkours.size(); i++) {
            JsonElement raw = parkours.get(i);
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            JsonObject parkour = raw.getAsJsonObject();
            if (entry.getType() == MapLocationType.PARKOUR_END) {
                if (matchesLocation(child(parkour, MAP_END_KEY), entry)) {
                    return i;
                }
                continue;
            }
            if (entry.getType() == MapLocationType.PARKOUR_CHECKPOINT) {
                JsonArray checkpoints = existingArray(parkour, MAP_CHECKPOINTS_KEY);
                if (checkpoints == null) {
                    continue;
                }
                for (int checkpointIndex = 0; checkpointIndex < checkpoints.size(); checkpointIndex++) {
                    JsonElement checkpointRaw = checkpoints.get(checkpointIndex);
                    if (checkpointRaw == null || !checkpointRaw.isJsonObject()) {
                        continue;
                    }
                    if (matchesLocation(checkpointRaw.getAsJsonObject(), entry)) {
                        return i;
                    }
                }
                continue;
            }
            if (matchesLocation(child(parkour, MAP_START_KEY), entry)) {
                return i;
            }
        }
        return -1;
    }

    private int resolveCheckpointIndex(JsonArray checkpoints, MapLocationEntry entry) {
        if (checkpoints == null || entry == null || checkpoints.size() == 0) {
            return -1;
        }
        int fallback = entry.getItemData();
        if (fallback >= 0 && fallback < checkpoints.size()) {
            JsonElement raw = checkpoints.get(fallback);
            if (raw != null && raw.isJsonObject() && matchesLocation(raw.getAsJsonObject(), entry)) {
                return fallback;
            }
        }
        for (int i = 0; i < checkpoints.size(); i++) {
            JsonElement raw = checkpoints.get(i);
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            if (matchesLocation(raw.getAsJsonObject(), entry)) {
                return i;
            }
        }
        return -1;
    }

    private void sortMostRecentFirst(List<MapLocationEntry> entries) {
        if (entries == null || entries.size() < 2) {
            return;
        }
        entries.sort((left, right) -> {
            long leftCreatedAt = left == null ? 0L : left.getCreatedAt();
            long rightCreatedAt = right == null ? 0L : right.getCreatedAt();
            if (leftCreatedAt != rightCreatedAt) {
                return Long.compare(rightCreatedAt, leftCreatedAt);
            }
            int leftIndex = left == null ? Integer.MAX_VALUE : left.getArrayIndex();
            int rightIndex = right == null ? Integer.MAX_VALUE : right.getArrayIndex();
            return Integer.compare(rightIndex, leftIndex);
        });
    }

    private JsonArray existingLocationArray(JsonObject map, MapLocationType type) {
        if (map == null || type == null) {
            return null;
        }
        if (type == MapLocationType.PLAYER_SPAWN) {
            return existingArray(map, MAP_SPAWNS_KEY);
        }
        if (type == MapLocationType.ITEM_DROP) {
            return existingArray(map, MAP_DROP_ITEMS_KEY);
        }
        if (type == MapLocationType.HUB_NPC) {
            return existingArray(map, MAP_NPCS_KEY);
        }
        if (type == MapLocationType.PROFILE_NPC) {
            return existingArray(map, MAP_PROFILE_NPCS_KEY);
        }
        if (type == MapLocationType.LEADERBOARD) {
            return existingArray(map, MAP_LEADERBOARDS_KEY);
        }
        if (type == MapLocationType.PARKOUR_ROUTE) {
            return existingArray(map, MAP_PARKOURS_KEY);
        }
        if (type == MapLocationType.PARKOUR_START
                || type == MapLocationType.PARKOUR_END
                || type == MapLocationType.PARKOUR_CHECKPOINT) {
            return existingArray(map, MAP_PARKOURS_KEY);
        }
        return null;
    }

    private void removeLocationAt(JsonObject map, MapLocationType type, JsonArray locations, int removeIndex) {
        if (map == null || type == null || locations == null || removeIndex < 0 || removeIndex >= locations.size()) {
            return;
        }
        JsonArray rebuilt = new JsonArray();
        for (int i = 0; i < locations.size(); i++) {
            if (i == removeIndex) {
                continue;
            }
            JsonElement current = locations.get(i);
            if (current == null) {
                continue;
            }
            rebuilt.add(current);
        }
        if (type == MapLocationType.PLAYER_SPAWN) {
            map.add(MAP_SPAWNS_KEY, rebuilt);
            return;
        }
        if (type == MapLocationType.ITEM_DROP) {
            map.add(MAP_DROP_ITEMS_KEY, rebuilt);
            return;
        }
        if (type == MapLocationType.HUB_NPC) {
            map.add(MAP_NPCS_KEY, rebuilt);
            return;
        }
        if (type == MapLocationType.PROFILE_NPC) {
            map.add(MAP_PROFILE_NPCS_KEY, rebuilt);
            return;
        }
        if (type == MapLocationType.LEADERBOARD) {
            map.add(MAP_LEADERBOARDS_KEY, rebuilt);
            return;
        }
        if (type == MapLocationType.PARKOUR_ROUTE) {
            map.add(MAP_PARKOURS_KEY, rebuilt);
        }
    }

    private JsonArray getOrCreateDropItemArray(JsonObject map) {
        if (map == null) {
            return new JsonArray();
        }
        JsonArray dropItems = existingArray(map, MAP_DROP_ITEMS_KEY);
        if (dropItems != null) {
            return dropItems;
        }
        JsonArray created = new JsonArray();
        map.add(MAP_DROP_ITEMS_KEY, created);
        return created;
    }

    private JsonArray existingArray(JsonObject parent, String key) {
        if (parent == null || key == null || key.trim().isEmpty()) {
            return null;
        }
        JsonElement raw = parent.get(key);
        if (raw == null || raw.isJsonNull() || !raw.isJsonArray()) {
            return null;
        }
        return raw.getAsJsonArray();
    }

    private int findLocationIndex(JsonArray locations, MapLocationEntry entry) {
        if (locations == null || entry == null) {
            return -1;
        }
        for (int i = 0; i < locations.size(); i++) {
            JsonElement raw = locations.get(i);
            if (raw == null || !raw.isJsonObject()) {
                continue;
            }
            if (matchesLocation(raw.getAsJsonObject(), entry)) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchesLocation(JsonObject location, MapLocationEntry entry) {
        if (location == null || entry == null) {
            return false;
        }
        if (!safeString(entry.getWorldName()).isEmpty()) {
            String world = safeString(location, "world");
            if (!world.equalsIgnoreCase(entry.getWorldName())) {
                return false;
            }
        }
        if (!nearlyEqual(safeDouble(location, "x"), entry.getX())) {
            return false;
        }
        if (!nearlyEqual(safeDouble(location, "y"), entry.getY())) {
            return false;
        }
        if (!nearlyEqual(safeDouble(location, "z"), entry.getZ())) {
            return false;
        }
        if (!nearlyEqual(safeDouble(location, "yaw"), entry.getYaw())) {
            return false;
        }
        if (!nearlyEqual(safeDouble(location, "pitch"), entry.getPitch())) {
            return false;
        }
        if (entry.getType() == MapLocationType.ITEM_DROP) {
            String expectedItem = safeString(entry.getItemName());
            String currentItem = safeString(location, "item");
            if (!expectedItem.isEmpty() && !currentItem.equalsIgnoreCase(expectedItem)) {
                return false;
            }
            int expectedData = entry.getItemData();
            int currentData = safeInt(location, "itemData");
            return expectedData == currentData;
        }
        return true;
    }

    private boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) <= 0.0001d;
    }

    private String formatCoord(double value) {
        return COORD_FORMAT.format(value);
    }

    private String formatNumber(int value) {
        return NUMBER_FORMAT.format(Math.max(0, value));
    }

    private void applyMapRotationDefaults(JsonObject gameSection, String resolvedMapName) {
        if (gameSection == null) {
            return;
        }
        String resolved = safeString(resolvedMapName);
        if (resolved.isEmpty()) {
            return;
        }
        JsonArray rotation = getOrCreateArray(gameSection, "rotation");
        ensureContains(rotation, resolved);

        String activeMap = safeString(gameSection, "activeMap");
        if (activeMap.isEmpty()) {
            gameSection.addProperty("activeMap", resolved);
            return;
        }
        if (activeMap.equalsIgnoreCase(resolved)) {
            return;
        }

        // Migrate generic aliases to the concrete world folder
        // being edited so runtime map selection and world template selection stay aligned.
        String normalizedActive = activeMap.toLowerCase(Locale.ROOT);
        boolean activeIsAlias = "hub".equals(normalizedActive)
                || "default".equals(normalizedActive)
                || "world".equals(normalizedActive)
                || "world_nether".equals(normalizedActive)
                || "world_the_end".equals(normalizedActive);
        if (activeIsAlias) {
            gameSection.addProperty("activeMap", resolved);
        }
    }

    private void registerExportedMapInConfig(String gameKey, String worldDirectory) {
        String resolvedGameKey = MapConfigStore.normalizeGameKey(gameKey);
        String resolvedWorldDirectory = safeString(worldDirectory);
        if (resolvedGameKey.isEmpty() || resolvedWorldDirectory.isEmpty()) {
            return;
        }

        MapConfigStore store = mapConfigStore();
        if (store == null) {
            throw new IllegalStateException("MongoDB map config store is unavailable.");
        }

        synchronized (mapConfigLock) {
            JsonObject root = loadMapConfigRoot(store, resolvedGameKey);
            JsonObject gameSection = getOrCreateGameSection(root, resolvedGameKey);
            JsonArray maps = getOrCreateArray(gameSection, "maps");

            JsonObject map = findOrCreateMapByWorldDirectory(maps, resolvedWorldDirectory);
            map.addProperty("worldDirectory", resolvedWorldDirectory);
            map.addProperty("name", MapConfigStore.displayNameFromWorldDirectory(resolvedWorldDirectory));
            String resolvedMapName = mapWorldDirectoryOf(map, resolvedWorldDirectory);
            applyMapRotationDefaults(gameSection, resolvedMapName);

            saveMapConfigRoot(store, resolvedGameKey, root);
        }
    }

    private void ensureContains(JsonArray array, String value) {
        if (array == null || value == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String current;
            try {
                current = element.getAsString();
            } catch (Exception ex) {
                continue;
            }
            if (value.equalsIgnoreCase(current)) {
                return;
            }
        }
        array.add(new JsonPrimitive(value));
    }

    private JsonObject toLocationJson(Location location, long createdAt) {
        JsonObject json = new JsonObject();
        String world = location == null || location.getWorld() == null ? "world" : location.getWorld().getName();
        double x = location == null ? 0.5d : location.getX();
        double y = location == null ? 64.0d : location.getY();
        double z = location == null ? 0.5d : location.getZ();
        float yaw = location == null ? 0.0f : location.getYaw();
        float pitch = location == null ? 0.0f : location.getPitch();
        json.addProperty("world", world);
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        json.addProperty("yaw", yaw);
        json.addProperty("pitch", pitch);
        json.addProperty(MAP_CREATED_AT_KEY, createdAt <= 0L ? System.currentTimeMillis() : createdAt);
        return json;
    }

    private void sendDone(Player player, Location location, String itemName) {
        if (player == null) {
            return;
        }
        Location loc = location == null ? player.getLocation() : location;
        String coords = "(" + COORD_FORMAT.format(loc.getX()) + ", "
                + COORD_FORMAT.format(loc.getY()) + ", "
                + COORD_FORMAT.format(loc.getZ()) + ")";
        StringBuilder message = new StringBuilder();
        message.append(ChatColor.GREEN).append("Done! ");
        message.append(ChatColor.DARK_GRAY).append(coords);
        if (itemName != null && !itemName.trim().isEmpty()) {
            message.append(" ").append(ChatColor.GOLD).append("(")
                    .append(itemName.toUpperCase(Locale.ROOT))
                    .append(")");
        }
        player.sendMessage(message.toString());
    }

    private void sendDeleted(Player player, Location location, String itemName) {
        if (player == null) {
            return;
        }
        Location loc = location == null ? player.getLocation() : location;
        String coords = "(" + COORD_FORMAT.format(loc.getX()) + ", "
                + COORD_FORMAT.format(loc.getY()) + ", "
                + COORD_FORMAT.format(loc.getZ()) + ")";
        StringBuilder message = new StringBuilder();
        message.append(ChatColor.RED).append("Deleted! ");
        message.append(ChatColor.DARK_GRAY).append(coords);
        if (itemName != null && !itemName.trim().isEmpty()) {
            message.append(" ").append(ChatColor.GOLD).append("(")
                    .append(itemName.toUpperCase(Locale.ROOT))
                    .append(")");
        }
        player.sendMessage(message.toString());
    }

    private String runtimeIdAt(JsonArray locations, int index) {
        if (locations == null || index < 0 || index >= locations.size()) {
            return "";
        }
        JsonElement raw = locations.get(index);
        if (raw == null || !raw.isJsonObject()) {
            return "";
        }
        return safeString(raw.getAsJsonObject(), MAP_ENTITY_ID_KEY);
    }

    private void cleanupRuntimeAfterDelete(MapLocationType type, String runtimeId, MapLocationEntry entry) {
        if (type == null) {
            return;
        }
        if (type == MapLocationType.HUB_NPC || type == MapLocationType.PROFILE_NPC) {
            despawnRuntimeNpcByEntityId(runtimeId, entry);
            return;
        }
        if (type == MapLocationType.LEADERBOARD) {
            despawnLeaderboardByEntityId(runtimeId, entry);
        }
    }

    private List<MapLocationEntry> collectParkourMarkersForDeletion(ServerType gameType,
                                                                    String worldDirectory,
                                                                    MapLocationEntry entry) {
        List<MapLocationEntry> markers = new ArrayList<MapLocationEntry>();
        if (entry == null || entry.getType() == null) {
            return markers;
        }
        MapLocationType type = entry.getType();
        if (isParkourMarkerType(type)) {
            markers.add(entry);
        }
        if (type == MapLocationType.PARKOUR_START
                || type == MapLocationType.PARKOUR_END
                || type == MapLocationType.PARKOUR_CHECKPOINT
                || type == MapLocationType.PARKOUR_ROUTE) {
            List<MapLocationEntry> existing = loadMapLocations(gameType, safeString(worldDirectory));
            for (MapLocationEntry candidate : existing) {
                if (candidate == null || !isParkourMarkerType(candidate.getType())) {
                    continue;
                }
                if (candidate.getArrayIndex() != entry.getArrayIndex()) {
                    continue;
                }
                markers.add(candidate);
            }
        }
        return dedupeParkourMarkers(markers);
    }

    private List<MapLocationEntry> collectAllParkourMarkers(JsonObject map) {
        List<MapLocationEntry> markers = new ArrayList<MapLocationEntry>();
        if (map == null) {
            return markers;
        }
        List<MapLocationEntry> routeEntries = new ArrayList<MapLocationEntry>();
        appendParkourEntries(routeEntries, existingArray(map, MAP_PARKOURS_KEY));
        for (MapLocationEntry candidate : routeEntries) {
            if (candidate == null || !isParkourMarkerType(candidate.getType())) {
                continue;
            }
            markers.add(candidate);
        }
        return dedupeParkourMarkers(markers);
    }

    private List<MapLocationEntry> dedupeParkourMarkers(List<MapLocationEntry> markers) {
        List<MapLocationEntry> deduped = new ArrayList<MapLocationEntry>();
        if (markers == null || markers.isEmpty()) {
            return deduped;
        }
        for (MapLocationEntry candidate : markers) {
            if (candidate == null) {
                continue;
            }
            boolean duplicate = false;
            for (MapLocationEntry existing : deduped) {
                if (existing == null) {
                    continue;
                }
                if (existing.getType() != candidate.getType()) {
                    continue;
                }
                if (existing.getArrayIndex() != candidate.getArrayIndex()) {
                    continue;
                }
                if (existing.getItemData() != candidate.getItemData()) {
                    continue;
                }
                if (!safeString(existing.getWorldName()).equalsIgnoreCase(safeString(candidate.getWorldName()))) {
                    continue;
                }
                if (!nearlyEqual(existing.getX(), candidate.getX())) {
                    continue;
                }
                if (!nearlyEqual(existing.getY(), candidate.getY())) {
                    continue;
                }
                if (!nearlyEqual(existing.getZ(), candidate.getZ())) {
                    continue;
                }
                duplicate = true;
                break;
            }
            if (!duplicate) {
                deduped.add(candidate);
            }
        }
        return deduped;
    }

    private boolean isParkourMarkerType(MapLocationType type) {
        return type == MapLocationType.PARKOUR_START
                || type == MapLocationType.PARKOUR_END
                || type == MapLocationType.PARKOUR_CHECKPOINT;
    }

    private void removeParkourWorldMarkers(List<MapLocationEntry> markers, World fallbackWorld) {
        if (markers == null || markers.isEmpty()) {
            return;
        }
        for (MapLocationEntry marker : markers) {
            removeParkourWorldMarker(marker, fallbackWorld);
        }
    }

    private void removeParkourDraftMarkers(ParkourDraft draft, World fallbackWorld) {
        if (draft == null) {
            return;
        }
        World draftWorld = resolveWorldByName(draft.worldDirectory);
        World world = draftWorld == null ? fallbackWorld : draftWorld;
        removeParkourDraftMarker(draft.start, world);
        if (draft.checkpoints == null || draft.checkpoints.isEmpty()) {
            return;
        }
        for (Location checkpoint : draft.checkpoints) {
            removeParkourDraftMarker(checkpoint, world);
        }
    }

    private void removeParkourDraftMarker(Location markerLocation, World fallbackWorld) {
        if (markerLocation == null) {
            return;
        }
        World world = markerLocation.getWorld();
        if (world == null) {
            world = fallbackWorld;
        }
        if (world == null) {
            return;
        }
        Location origin = new Location(world, markerLocation.getX(), markerLocation.getY(), markerLocation.getZ())
                .getBlock()
                .getLocation();
        Location above = origin.clone().add(0.0d, 1.0d, 0.0d);
        Location below = origin.clone().subtract(0.0d, 1.0d, 0.0d);
        Location markerBlock = origin;
        if (isParkourPressurePlateMaterial(above.getBlock().getType())) {
            markerBlock = above;
        } else if (isParkourPressurePlateMaterial(below.getBlock().getType())) {
            markerBlock = below;
        } else if (!isParkourPressurePlateMaterial(origin.getBlock().getType())) {
            markerBlock = origin;
        }
        if (isParkourPressurePlateMaterial(markerBlock.getBlock().getType())) {
            markerBlock.getBlock().setType(Material.AIR);
        }
        removeParkourMarkerHolograms(world, markerBlock);
    }

    private void removeParkourWorldMarker(MapLocationEntry marker, World fallbackWorld) {
        if (marker == null || !isParkourMarkerType(marker.getType())) {
            return;
        }
        World world = resolveWorldByName(marker.getWorldName());
        if (world == null) {
            world = fallbackWorld;
        }
        if (world == null) {
            return;
        }
        Location markerBlock = resolveParkourMarkerBlock(world, marker);
        if (markerBlock == null) {
            return;
        }
        Material currentType = markerBlock.getBlock().getType();
        if (isParkourPressurePlateMaterial(currentType)) {
            markerBlock.getBlock().setType(Material.AIR);
        }
        removeParkourMarkerHolograms(world, markerBlock);
    }

    private Location resolveParkourMarkerBlock(World world, MapLocationEntry marker) {
        if (world == null || marker == null) {
            return null;
        }
        Location origin = new Location(world, marker.getX(), marker.getY(), marker.getZ()).getBlock().getLocation();
        Location above = origin.clone().add(0.0d, 1.0d, 0.0d);
        Location below = origin.clone().subtract(0.0d, 1.0d, 0.0d);
        if (isParkourPressurePlateMaterial(origin.getBlock().getType())) {
            return origin;
        }
        if (isParkourPressurePlateMaterial(above.getBlock().getType())) {
            return above;
        }
        if (isParkourPressurePlateMaterial(below.getBlock().getType())) {
            return below;
        }
        return origin;
    }

    private void removeParkourMarkerHolograms(World world, Location markerBlock) {
        if (world == null || markerBlock == null) {
            return;
        }
        Location expected = markerBlock.clone().add(0.5d, PARKOUR_HOLOGRAM_BASE_Y_OFFSET, 0.5d);
        Location legacyExpected = markerBlock.clone().add(0.5d, LEGACY_PARKOUR_HOLOGRAM_BASE_Y_OFFSET, 0.5d);
        List<Entity> entities = world.getEntities();
        for (Entity entity : entities) {
            if (entity == null || !isParkourMarkerHologram(entity)) {
                continue;
            }
            Location entityLocation = entity.getLocation();
            if (entityLocation == null || entityLocation.getWorld() != world) {
                continue;
            }
            if (!withinRadius(entityLocation, expected, 1.0d)
                    && !withinRadius(entityLocation, legacyExpected, 1.0d)) {
                continue;
            }
            entity.remove();
        }
    }

    private boolean withinRadius(Location actual, Location expected, double radius) {
        if (actual == null || expected == null || actual.getWorld() != expected.getWorld()) {
            return false;
        }
        double dx = actual.getX() - expected.getX();
        double dy = actual.getY() - expected.getY();
        double dz = actual.getZ() - expected.getZ();
        double radiusSquared = radius * radius;
        return (dx * dx + dy * dy + dz * dz) <= radiusSquared;
    }

    private boolean isParkourMarkerHologram(Entity entity) {
        if (!(entity instanceof ArmorStand)) {
            return false;
        }
        ArmorStand stand = (ArmorStand) entity;
        if (!stand.isCustomNameVisible()) {
            return false;
        }
        String customName = stand.getCustomName();
        if (customName == null || customName.trim().isEmpty()) {
            return false;
        }
        String stripped = ChatColor.stripColor(customName);
        if (stripped == null) {
            return false;
        }
        String normalized = stripped.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("parkour start")
                || normalized.startsWith("parkour end")
                || normalized.startsWith("checkpoint ")
                || normalized.equals("parkour challenge")
                || normalized.equals("start")
                || normalized.equals("end")
                || normalized.equals("checkpoint")
                || normalized.startsWith("#");
    }

    private boolean isParkourPressurePlateMaterial(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return "STONE_PRESSURE_PLATE".equals(name)
                || "STONE_PLATE".equals(name)
                || "OAK_PRESSURE_PLATE".equals(name)
                || "WOOD_PRESSURE_PLATE".equals(name)
                || "WOOD_PLATE".equals(name)
                || "LIGHT_WEIGHTED_PRESSURE_PLATE".equals(name)
                || "GOLD_PLATE".equals(name)
                || "HEAVY_WEIGHTED_PRESSURE_PLATE".equals(name)
                || "IRON_PRESSURE_PLATE".equals(name)
                || "IRON_PLATE".equals(name);
    }

    private void despawnAllRuntimeArtifacts() {
        List<RuntimeNpc> npcs = new ArrayList<RuntimeNpc>(npcsByEntityId.values());
        for (RuntimeNpc npc : npcs) {
            despawnRuntimeNpc(npc);
        }
        npcsByEntityId.clear();
        npcHologramToEntity.clear();

        List<RuntimeLeaderboard> boards = new ArrayList<RuntimeLeaderboard>(leaderboardsByAnchor.values());
        for (RuntimeLeaderboard board : boards) {
            despawnLeaderboard(board);
        }
        leaderboardsByAnchor.clear();
    }

    private RuntimeNpc resolveRuntimeNpc(UUID entityId) {
        if (entityId == null) {
            return null;
        }
        RuntimeNpc direct = npcsByEntityId.get(entityId);
        if (direct != null) {
            return direct;
        }
        UUID anchor = npcHologramToEntity.get(entityId);
        if (anchor == null) {
            return null;
        }
        return npcsByEntityId.get(anchor);
    }

    private void spawnNpcRuntime(String entityId,
                                 Location location,
                                 String skinOwner,
                                 String hologramColor,
                                 boolean profileNpc,
                                 boolean clickToPlayNpc,
                                 UUID profileOwnerUuid,
                                 ServerType gameType,
                                 String worldDirectory) {
        if (!BUILD_RUNTIME_VISUALS_ENABLED) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        despawnRuntimeNpcByEntityId(entityId, null);

        Location anchorLocation = location.clone();
        String resolvedSkin = safeString(skinOwner);
        if (clickToPlayNpc) {
            resolvedSkin = MURDER_MYSTERY_CLICK_TO_PLAY_SKIN_REFERENCE;
        }
        if (resolvedSkin.isEmpty()) {
            resolvedSkin = DEFAULT_NPC_SKIN;
        }
        String fallbackSkinOwner = clickToPlayNpc ? "" : DEFAULT_NPC_SKIN;
        Entity anchor = spawnNpcBaseEntity(anchorLocation, resolvedSkin, fallbackSkinOwner);
        if (anchor == null) {
            return;
        }

        RuntimeNpc runtime = new RuntimeNpc();
        runtime.entityId = safeString(entityId);
        runtime.entityUuid = anchor.getUniqueId();
        runtime.profileNpc = profileNpc;
        runtime.clickToPlayNpc = clickToPlayNpc;
        runtime.profileOwnerUuid = profileOwnerUuid;
        runtime.profileOwnerName = safeString(skinOwner);
        runtime.skinOwner = resolvedSkin;
        String defaultColor = clickToPlayNpc ? DEFAULT_CLICK_TO_PLAY_NPC_COLOR : DEFAULT_NPC_COLOR;
        runtime.hologramColor = safeString(hologramColor).isEmpty() ? defaultColor : safeString(hologramColor);
        runtime.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
        runtime.worldDirectory = safeString(worldDirectory);

        npcsByEntityId.put(runtime.entityUuid, runtime);
        applyNpcVisuals(runtime);
    }

    private void despawnRuntimeNpcByEntityId(String entityId, MapLocationEntry fallbackEntry) {
        String targetId = safeString(entityId);
        if (!targetId.isEmpty()) {
            RuntimeNpc matched = null;
            for (RuntimeNpc runtime : npcsByEntityId.values()) {
                if (runtime == null) {
                    continue;
                }
                if (targetId.equalsIgnoreCase(safeString(runtime.entityId))) {
                    matched = runtime;
                    break;
                }
            }
            if (matched != null) {
                despawnRuntimeNpc(matched);
                return;
            }
        }
        if (fallbackEntry == null) {
            return;
        }
        RuntimeNpc nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (RuntimeNpc runtime : npcsByEntityId.values()) {
            if (runtime == null || runtime.entityUuid == null) {
                continue;
            }
            Entity entity = resolveEntity(runtime.entityUuid);
            if (entity == null || entity.getLocation() == null) {
                continue;
            }
            Location loc = entity.getLocation();
            if (!safeString(fallbackEntry.getWorldName()).equalsIgnoreCase(safeString(loc.getWorld() == null ? null : loc.getWorld().getName()))) {
                continue;
            }
            double dx = fallbackEntry.getX() - loc.getX();
            double dy = fallbackEntry.getY() - loc.getY();
            double dz = fallbackEntry.getZ() - loc.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = runtime;
            }
        }
        if (nearest != null && bestDistance <= 4.0d) {
            despawnRuntimeNpc(nearest);
        }
    }

    private void despawnRuntimeNpc(RuntimeNpc runtime) {
        if (runtime == null) {
            return;
        }
        clearNpcHologramLines(runtime);
        if (runtime.entityUuid != null) {
            removeEntity(runtime.entityUuid);
            npcsByEntityId.remove(runtime.entityUuid);
        }
    }

    private void applyNpcVisuals(RuntimeNpc runtime) {
        if (runtime == null || runtime.entityUuid == null) {
            return;
        }
        Entity baseEntity = resolveEntity(runtime.entityUuid);
        if (baseEntity == null) {
            return;
        }
        if (runtime.profileNpc) {
            applyProfileNpcHeldItem(baseEntity);
        }

        if (runtime.clickToPlayNpc) {
            applyNpcHologramLines(runtime, clickToPlayHologramLines(runtime));
            return;
        }
        if (runtime.profileNpc) {
            applyNpcHologramLines(runtime, profileHologramLines(runtime, null));
            return;
        }
        ChatColor color = chatColorFromName(runtime.hologramColor, ChatColor.GREEN);
        applyNpcHologramLines(runtime, Collections.singletonList(color + "Click to customize"));
    }

    private Entity respawnNpcBaseEntity(RuntimeNpc runtime) {
        if (runtime == null || runtime.entityUuid == null) {
            return null;
        }
        Entity currentBaseEntity = resolveEntity(runtime.entityUuid);
        Location location = currentBaseEntity == null ? null : currentBaseEntity.getLocation();
        if (location == null || location.getWorld() == null) {
            return currentBaseEntity;
        }

        String skinSource = runtime.profileNpc ? safeString(runtime.profileOwnerName) : safeString(runtime.skinOwner);
        if (runtime.clickToPlayNpc) {
            skinSource = MURDER_MYSTERY_CLICK_TO_PLAY_SKIN_REFERENCE;
        }
        if (skinSource.isEmpty()) {
            skinSource = DEFAULT_NPC_SKIN;
        }
        String fallbackOwner = runtime.clickToPlayNpc ? "" : DEFAULT_NPC_SKIN;
        Entity replacement = spawnNpcBaseEntity(location, skinSource, fallbackOwner);
        if (replacement == null || replacement.getUniqueId() == null) {
            return currentBaseEntity;
        }

        UUID previousUuid = runtime.entityUuid;
        runtime.entityUuid = replacement.getUniqueId();
        npcsByEntityId.put(runtime.entityUuid, runtime);

        if (previousUuid != null && !previousUuid.equals(runtime.entityUuid)) {
            removeEntity(previousUuid);
            npcsByEntityId.remove(previousUuid);
        }
        return replacement;
    }

    private void applyProfileNpcViewerHologram(RuntimeNpc runtime, Player viewer) {
        if (runtime == null || !runtime.profileNpc) {
            return;
        }
        applyNpcHologramLines(runtime, profileHologramLines(runtime, viewer));
    }

    private void applyProfileNpcHeldItem(Entity baseEntity) {
        if (baseEntity == null) {
            return;
        }
        ItemStack paper = new ItemStack(Material.PAPER, 1);
        if (baseEntity instanceof Player) {
            Player npcPlayer = (Player) baseEntity;
            if (npcPlayer.getInventory() != null) {
                npcPlayer.getInventory().setItemInHand(paper);
            }
        }
        if (baseEntity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) baseEntity;
            if (living.getEquipment() != null) {
                living.getEquipment().setItemInHand(paper);
            }
        }
    }

    private void applyNpcHologramLines(RuntimeNpc runtime, List<String> lines) {
        if (runtime == null || runtime.entityUuid == null) {
            return;
        }
        clearNpcHologramLines(runtime);
        Entity baseEntity = resolveEntity(runtime.entityUuid);
        if (baseEntity == null || baseEntity.getLocation() == null || baseEntity.getLocation().getWorld() == null) {
            return;
        }
        List<String> resolved = lines == null ? Collections.<String>emptyList() : lines;
        if (resolved.isEmpty()) {
            return;
        }
        runtime.hologramLineUuids = new ArrayList<UUID>();
        double bottomOffset = NPC_HOLOGRAM_BOTTOM_Y_OFFSET;
        if (runtime.profileNpc) {
            bottomOffset = PROFILE_NPC_HOLOGRAM_BOTTOM_Y_OFFSET;
        } else if (runtime.clickToPlayNpc) {
            bottomOffset = NPC_HOLOGRAM_BOTTOM_Y_OFFSET;
        }
        double firstLineOffset = bottomOffset + ((resolved.size() - 1) * HOLOGRAM_LINE_SPACING);
        Location current = baseEntity.getLocation().clone().add(0.0d, firstLineOffset, 0.0d);
        for (String line : resolved) {
            UUID lineUuid = spawnHologramLine(current, line);
            if (lineUuid != null) {
                runtime.hologramLineUuids.add(lineUuid);
                npcHologramToEntity.put(lineUuid, runtime.entityUuid);
                if (runtime.hologramUuid == null) {
                    runtime.hologramUuid = lineUuid;
                }
            }
            current.subtract(0.0d, HOLOGRAM_LINE_SPACING, 0.0d);
        }
    }

    private void clearNpcHologramLines(RuntimeNpc runtime) {
        if (runtime == null) {
            return;
        }
        if (runtime.hologramLineUuids != null) {
            for (UUID lineUuid : runtime.hologramLineUuids) {
                removeEntity(lineUuid);
                npcHologramToEntity.remove(lineUuid);
            }
            runtime.hologramLineUuids.clear();
        }
        if (runtime.hologramUuid != null) {
            removeEntity(runtime.hologramUuid);
            npcHologramToEntity.remove(runtime.hologramUuid);
            runtime.hologramUuid = null;
        }
    }

    private List<String> clickToPlayHologramLines(RuntimeNpc runtime) {
        ServerType type = runtime == null ? ServerType.UNKNOWN : runtime.gameType;
        int players = currentPlayersForMode(type);
        String label = players == 1 ? "Player" : "Players";
        List<String> lines = new ArrayList<String>(3);
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + "CLICK TO PLAY!");
        lines.add(ChatColor.AQUA + modeNameForType(type));
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + players + " " + label);
        return lines;
    }

    private List<String> profileHologramLines(RuntimeNpc runtime, Player viewer) {
        ServerType type = runtime == null ? ServerType.UNKNOWN : runtime.gameType;
        Profile profile = resolveProfileForRuntime(runtime, viewer);
        int wins = 0;
        int winsAsMurderer = 0;
        if (profile != null && profile.getStats() != null) {
            wins = Math.max(0, profile.getStats().getWins());
            winsAsMurderer = readWinsAsMurderer(profile);
        }
        List<String> lines = new ArrayList<String>(4);
        lines.add(ChatColor.GOLD.toString() + ChatColor.BOLD + "Your " + gameTypeLabel(type) + " Profile");
        lines.add(ChatColor.WHITE + "Total Wins: " + ChatColor.GREEN + wins);
        lines.add(ChatColor.WHITE + "Wins as Murderer: " + ChatColor.GREEN + winsAsMurderer);
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + "CLICK FOR STATS");
        return lines;
    }

    private Profile resolveProfileForRuntime(RuntimeNpc runtime, Player viewer) {
        if (corePlugin == null) {
            return null;
        }
        if (viewer != null && viewer.getUniqueId() != null) {
            Profile viewerProfile = corePlugin.getProfile(viewer.getUniqueId());
            if (viewerProfile != null) {
                return viewerProfile;
            }
        }
        if (runtime != null && runtime.profileOwnerUuid != null) {
            return corePlugin.getProfile(runtime.profileOwnerUuid);
        }
        return null;
    }

    private int readWinsAsMurderer(Profile profile) {
        if (profile == null || profile.getStats() == null) {
            return 0;
        }
        int wins = profile.getStats().getCustomCounter(MURDER_MYSTERY_WINS_AS_MURDERER_KEY);
        if (wins > 0) {
            return wins;
        }
        return profile.getStats().getCustomCounter("winsAsMurderer");
    }

    private String modeNameForType(ServerType type) {
        String normalized = gameTypeToken(type);
        if ("murdermystery".equals(normalized)) {
            return "Classic";
        }
        return "Default";
    }

    private int currentPlayersForMode(ServerType type) {
        ServerType gameType = gameServerType(type);
        if (gameType == ServerType.UNKNOWN) {
            return 0;
        }
        int players = currentPlayersForType(gameType);
        ServerType hubType = gameType.toHubType();
        if (hubType != null && hubType != ServerType.UNKNOWN) {
            players += currentPlayersForType(hubType);
        }
        return Math.max(0, players);
    }

    private int currentPlayersForType(ServerType type) {
        if (type == null || type == ServerType.UNKNOWN) {
            return 0;
        }
        MongoManager mongo = corePlugin == null ? null : corePlugin.getMongoManager();
        if (mongo == null) {
            return 0;
        }
        MongoCollection<Document> registry = mongo.getServerRegistry();
        if (registry == null) {
            return 0;
        }
        int staleSeconds = corePlugin == null ? 20 : Math.max(0, corePlugin.getConfig().getInt("registry.staleSeconds", 20));
        long now = System.currentTimeMillis();
        Map<String, Document> latestByServerId = new HashMap<String, Document>();
        Map<String, Long> heartbeatByServerId = new HashMap<String, Long>();
        for (Document doc : registry.find(com.mongodb.client.model.Filters.eq("type", type.getId()))) {
            if (!isActiveRegistryEntry(doc, now, staleSeconds)) {
                continue;
            }
            String serverId = safeString(doc.getString("_id"));
            if (serverId.isEmpty()) {
                continue;
            }
            String key = serverId.toLowerCase(Locale.ROOT);
            long heartbeat = safeLong(doc.get("lastHeartbeat"));
            Long known = heartbeatByServerId.get(key);
            if (known != null && known >= heartbeat) {
                continue;
            }
            heartbeatByServerId.put(key, heartbeat);
            latestByServerId.put(key, doc);
        }
        int total = 0;
        for (Document entry : latestByServerId.values()) {
            total += safeInt(entry.get("players"));
        }
        return Math.max(0, total);
    }

    private boolean isActiveRegistryEntry(Document doc, long now, int staleSeconds) {
        if (doc == null) {
            return false;
        }
        String status = safeString(doc.getString("status"));
        if (!status.isEmpty() && !status.equalsIgnoreCase("online")) {
            return false;
        }
        if (staleSeconds > 0) {
            long heartbeat = safeLong(doc.get("lastHeartbeat"));
            if (heartbeat > 0 && now - heartbeat > staleSeconds * 1000L) {
                return false;
            }
        }
        return true;
    }

    private ServerType gameServerType(ServerType type) {
        if (type == null || type == ServerType.UNKNOWN) {
            return ServerType.UNKNOWN;
        }
        if (type.isGame()) {
            return type;
        }
        String id = type.getId();
        if (id == null || !id.endsWith("_HUB")) {
            return ServerType.UNKNOWN;
        }
        return ServerType.fromString(id.substring(0, id.length() - "_HUB".length()));
    }

    private String gameTypeLabel(ServerType type) {
        String label = type == null ? "" : safeString(type.getGameTypeDisplayName());
        if (label.isEmpty() && type != null) {
            label = safeString(type.getId());
        }
        label = label.replace('_', ' ').trim();
        if (label.isEmpty()) {
            return "Game";
        }
        String[] words = label.split("\\s+");
        StringBuilder normalized = new StringBuilder();
        for (String word : words) {
            String part = safeString(word).toLowerCase(Locale.ROOT);
            if (part.isEmpty()) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append(' ');
            }
            normalized.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                normalized.append(part.substring(1));
            }
        }
        return normalized.length() == 0 ? "Game" : normalized.toString();
    }

    private String gameTypeToken(ServerType type) {
        String label = type == null ? "" : safeString(type.getGameTypeDisplayName());
        if (label.isEmpty() && type != null) {
            label = safeString(type.getId());
        }
        return label.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
    }

    private Entity spawnNpcBaseEntity(Location location, String skinSource, String fallbackOwner) {
        if (!BUILD_RUNTIME_VISUALS_ENABLED) {
            return null;
        }
        return null;
    }

    private void saveNpcVisual(RuntimeNpc runtime, boolean profileNpc) {
        if (runtime == null || runtime.gameType == null || runtime.gameType == ServerType.UNKNOWN) {
            return;
        }
        String gameKey = gameKeyForType(runtime.gameType);
        if (gameKey.isEmpty()) {
            return;
        }
        MapConfigStore store = mapConfigStore();
        if (store == null) {
            return;
        }
        synchronized (mapConfigLock) {
            JsonObject root = loadMapConfigRoot(store, gameKey);
            JsonObject gameSection = getOrCreateGameSection(root, gameKey);
            JsonArray maps = getOrCreateArray(gameSection, "maps");
            JsonObject map = findMapByWorldDirectory(maps, runtime.worldDirectory);
            if (map == null) {
                map = findMap(maps, runtime.worldDirectory);
            }
            if (map == null) {
                return;
            }
            JsonArray npcs = getOrCreateArray(map, profileNpc ? MAP_PROFILE_NPCS_KEY : MAP_NPCS_KEY);
            for (JsonElement raw : npcs) {
                if (raw == null || !raw.isJsonObject()) {
                    continue;
                }
                JsonObject npc = raw.getAsJsonObject();
                if (!safeString(runtime.entityId).equalsIgnoreCase(safeString(npc, MAP_ENTITY_ID_KEY))) {
                    continue;
                }
                npc.addProperty(MAP_HOLOGRAM_COLOR_KEY, safeString(runtime.hologramColor));
                if (!profileNpc) {
                    npc.addProperty(MAP_SKIN_OWNER_KEY, safeString(runtime.skinOwner));
                }
                break;
            }
            saveMapConfigRoot(store, gameKey, root);
        }
    }

    private void sendProfileNpcStats(Player viewer, RuntimeNpc runtime) {
        if (viewer == null || runtime == null) {
            return;
        }
        Profile profile = corePlugin == null || viewer.getUniqueId() == null ? null : corePlugin.getProfile(viewer.getUniqueId());
        String name = safeString(viewer.getName());
        if (name.isEmpty()) {
            name = "Player";
        }
        viewer.sendMessage(ChatColor.GOLD + "Profile NPC: " + ChatColor.YELLOW + name);
        if (profile == null || profile.getStats() == null) {
            viewer.sendMessage(ChatColor.RED + "Profile stats are not loaded.");
            return;
        }
        viewer.sendMessage(ChatColor.GRAY + "Wins: " + ChatColor.AQUA + profile.getStats().getWins());
        viewer.sendMessage(ChatColor.GRAY + "Wins as Murderer: " + ChatColor.AQUA + readWinsAsMurderer(profile));
        viewer.sendMessage(ChatColor.GRAY + "Kills: " + ChatColor.AQUA + profile.getStats().getKills());
        viewer.sendMessage(ChatColor.GRAY + "Games: " + ChatColor.AQUA + profile.getStats().getGames());
    }

    private void spawnLeaderboardRuntime(String entityId,
                                         Location location,
                                         ServerType gameType,
                                         String worldDirectory,
                                         String metric,
                                         UUID viewerUuid,
                                         String viewerName) {
        if (!BUILD_RUNTIME_VISUALS_ENABLED) {
            return;
        }
        if (location == null || location.getWorld() == null || gameType == null) {
            return;
        }
        despawnLeaderboardByEntityId(entityId, null);
        String resolvedMetric = normalizeLeaderboardMetric(metric);
        List<String> lines = new ArrayList<String>();
        lines.add(ChatColor.AQUA.toString() + ChatColor.BOLD + ChatColor.UNDERLINE + leaderboardMetricLabel(resolvedMetric));
        lines.add(ChatColor.GRAY + modeNameForType(gameType));
        List<LeaderboardEntry> ranked = fetchLeaderboardEntries(gameType, resolvedMetric);
        String resolvedViewerName = safeString(viewerName);
        LeaderboardEntry viewerEntry = null;
        if (viewerUuid != null || !resolvedViewerName.isEmpty()) {
            for (LeaderboardEntry entry : ranked) {
                if (isViewerEntry(entry, viewerUuid, resolvedViewerName)) {
                    viewerEntry = entry;
                    break;
                }
            }
        }
        int rankIndex = 1;
        boolean viewerInTopTen = false;
        for (LeaderboardEntry entry : ranked) {
            if (rankIndex > 10) {
                break;
            }
            if (entry == null) {
                rankIndex++;
                continue;
            }
            boolean viewerLine = isViewerEntry(entry, viewerUuid, resolvedViewerName);
            if (viewerLine) {
                viewerInTopTen = true;
            }
            lines.add(ChatColor.YELLOW + "" + rankIndex + ". "
                    + entry.nameColor + (viewerLine ? ChatColor.UNDERLINE.toString() : "") + entry.name
                    + ChatColor.GRAY + " - "
                    + ChatColor.YELLOW + formatNumber(entry.score));
            rankIndex++;
        }
        while (rankIndex <= 10) {
            lines.add(ChatColor.YELLOW + "" + rankIndex + ". " + ChatColor.DARK_GRAY + "-");
            rankIndex++;
        }
        if (viewerEntry != null && !viewerInTopTen) {
            lines.add(ChatColor.YELLOW + "" + viewerEntry.position + ". "
                    + viewerEntry.nameColor + ChatColor.UNDERLINE + viewerEntry.name
                    + ChatColor.GRAY + " - "
                    + ChatColor.YELLOW + formatNumber(viewerEntry.score));
        }
        lines.add(ChatColor.GRAY + "Never resets.");

        RuntimeLeaderboard runtime = new RuntimeLeaderboard();
        runtime.entityId = safeString(entityId);
        runtime.gameType = gameType;
        runtime.worldDirectory = safeString(worldDirectory);
        runtime.metric = resolvedMetric;
        runtime.lineUuids = new ArrayList<UUID>();

        Location current = location.clone().add(0.0d, 2.4d, 0.0d);
        for (String line : lines) {
            UUID lineUuid = spawnHologramLine(current, line);
            if (lineUuid != null) {
                runtime.lineUuids.add(lineUuid);
                if (runtime.anchorUuid == null) {
                    runtime.anchorUuid = lineUuid;
                }
            }
            current.subtract(0.0d, HOLOGRAM_LINE_SPACING, 0.0d);
        }

        if (runtime.anchorUuid != null) {
            leaderboardsByAnchor.put(runtime.anchorUuid, runtime);
        }
    }

    private void despawnLeaderboardByEntityId(String entityId, MapLocationEntry fallbackEntry) {
        String targetId = safeString(entityId);
        RuntimeLeaderboard matched = null;
        if (!targetId.isEmpty()) {
            for (RuntimeLeaderboard runtime : leaderboardsByAnchor.values()) {
                if (runtime == null) {
                    continue;
                }
                if (targetId.equalsIgnoreCase(safeString(runtime.entityId))) {
                    matched = runtime;
                    break;
                }
            }
        }
        if (matched == null && fallbackEntry != null) {
            for (RuntimeLeaderboard runtime : leaderboardsByAnchor.values()) {
                if (runtime == null || runtime.anchorUuid == null) {
                    continue;
                }
                Entity anchor = resolveEntity(runtime.anchorUuid);
                if (anchor == null || anchor.getLocation() == null) {
                    continue;
                }
                Location loc = anchor.getLocation();
                if (!safeString(fallbackEntry.getWorldName()).equalsIgnoreCase(safeString(loc.getWorld() == null ? null : loc.getWorld().getName()))) {
                    continue;
                }
                double dx = fallbackEntry.getX() - loc.getX();
                double dy = fallbackEntry.getY() - loc.getY();
                double dz = fallbackEntry.getZ() - loc.getZ();
                if ((dx * dx + dy * dy + dz * dz) <= 1.0d) {
                    matched = runtime;
                    break;
                }
            }
        }
        if (matched != null) {
            despawnLeaderboard(matched);
        }
    }

    private void despawnLeaderboard(RuntimeLeaderboard runtime) {
        if (runtime == null) {
            return;
        }
        if (runtime.lineUuids != null) {
            for (UUID uuid : runtime.lineUuids) {
                removeEntity(uuid);
            }
        }
        if (runtime.anchorUuid != null) {
            leaderboardsByAnchor.remove(runtime.anchorUuid);
        }
    }

    private String normalizeLeaderboardMetric(String metric) {
        String normalized = safeString(metric).toLowerCase(Locale.ROOT);
        if (LEADERBOARD_METRIC_WINS_AS_MURDERER.equals(normalized)
                || LEGACY_LEADERBOARD_METRIC_KILLS_AS_MURDERER.equals(normalized)
                || "murdererwins".equals(normalized)
                || "winsasmurderer".equals(normalized)
                || "murderer_wins".equals(normalized)
                || "murdererkills".equals(normalized)
                || "killsasmurderer".equals(normalized)
                || "murderer_kills".equals(normalized)) {
            return LEADERBOARD_METRIC_WINS_AS_MURDERER;
        }
        if (LEADERBOARD_METRIC_WINS.equals(normalized)) {
            return LEADERBOARD_METRIC_WINS;
        }
        return LEADERBOARD_METRIC_KILLS;
    }

    private String nextLeaderboardMetric(String metric) {
        String current = normalizeLeaderboardMetric(metric);
        if (LEADERBOARD_METRIC_KILLS.equals(current)) {
            return LEADERBOARD_METRIC_WINS;
        }
        if (LEADERBOARD_METRIC_WINS.equals(current)) {
            return LEADERBOARD_METRIC_WINS_AS_MURDERER;
        }
        return LEADERBOARD_METRIC_KILLS;
    }

    private String leaderboardMetricLabel(String metric) {
        String resolved = normalizeLeaderboardMetric(metric);
        if (LEADERBOARD_METRIC_WINS_AS_MURDERER.equals(resolved)) {
            return "Lifetime Wins as Murderer";
        }
        if (LEADERBOARD_METRIC_WINS.equals(resolved)) {
            return "Lifetime Wins";
        }
        return "Lifetime Kills";
    }

    private List<LeaderboardEntry> fetchLeaderboardEntries(ServerType gameType, String metric) {
        MongoManager mongo = corePlugin == null ? null : corePlugin.getMongoManager();
        if (mongo == null) {
            return Collections.emptyList();
        }
        MongoCollection<Document> profiles = mongo.getProfiles();
        if (profiles == null) {
            return Collections.emptyList();
        }
        String resolvedMetric = normalizeLeaderboardMetric(metric);
        boolean winsMetric = LEADERBOARD_METRIC_WINS.equals(resolvedMetric);
        boolean murdererWinsMetric = LEADERBOARD_METRIC_WINS_AS_MURDERER.equals(resolvedMetric);
        String statKey = winsMetric ? winsStatKeyForType(gameType) : killsStatKeyForType(gameType);
        List<LeaderboardEntry> entries = new ArrayList<LeaderboardEntry>();
        for (Document doc : profiles.find()) {
            if (doc == null) {
                continue;
            }
            String name = safeString(doc.getString("name"));
            if (name.isEmpty()) {
                continue;
            }
            Document stats = doc.get("stats", Document.class);
            int score;
            if (winsMetric) {
                score = readWins(stats, statKey);
            } else if (murdererWinsMetric) {
                score = readMurdererWins(stats);
            } else {
                score = readKills(stats, statKey);
            }
            if (score <= 0) {
                continue;
            }
            Rank rank = parseRank(doc.getString("rank"));
            String mvpPlusPlusPrefixColor = safeString(doc.getString("mvpPlusPlusPrefixColor"));
            ChatColor nameColor = RankFormatUtil.baseColor(
                    rank,
                    mvpPlusPlusPrefixColor.isEmpty() ? null : mvpPlusPlusPrefixColor
            );
            entries.add(new LeaderboardEntry(
                    safeUuid(doc.get("uuid")),
                    name,
                    score,
                    nameColor
            ));
        }
        entries.sort(Comparator
                .comparingInt((LeaderboardEntry e) -> e.score)
                .reversed()
                .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        int position = 1;
        for (LeaderboardEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            entry.position = position++;
        }
        return entries;
    }

    private Rank parseRank(String raw) {
        String normalized = safeString(raw).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Rank.DEFAULT;
        }
        try {
            return Rank.valueOf(normalized);
        } catch (Exception ignored) {
            return Rank.DEFAULT;
        }
    }

    private boolean isViewerEntry(LeaderboardEntry entry, UUID viewerUuid, String viewerName) {
        if (entry == null) {
            return false;
        }
        if (viewerUuid != null && viewerUuid.equals(entry.uuid)) {
            return true;
        }
        if (safeString(viewerName).isEmpty()) {
            return false;
        }
        return safeString(viewerName).equalsIgnoreCase(safeString(entry.name));
    }

    private UUID safeUuid(Object raw) {
        String value = raw == null ? "" : safeString(String.valueOf(raw));
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String winsStatKeyForType(ServerType gameType) {
        if (gameType == null) {
            return "murderMysteryWins";
        }
        String normalized = safeString(gameType.getGameTypeDisplayName()).toLowerCase(Locale.ROOT).replace(" ", "");
        if ("murdermystery".equals(normalized)) {
            return "murderMysteryWins";
        }
        return "wins";
    }

    private int readWins(Document stats, String winsKey) {
        if (stats == null) {
            return 0;
        }
        Object modern = stats.get(safeString(winsKey));
        if (modern instanceof Number) {
            return Math.max(0, ((Number) modern).intValue());
        }
        Object legacy = stats.get("wins");
        if (legacy instanceof Number) {
            return Math.max(0, ((Number) legacy).intValue());
        }
        return 0;
    }

    private String killsStatKeyForType(ServerType gameType) {
        if (gameType == null) {
            return "murderMysteryKills";
        }
        String normalized = safeString(gameType.getGameTypeDisplayName()).toLowerCase(Locale.ROOT).replace(" ", "");
        if ("murdermystery".equals(normalized)) {
            return "murderMysteryKills";
        }
        return "kills";
    }

    private int readKills(Document stats, String killsKey) {
        if (stats == null) {
            return 0;
        }
        Object modern = stats.get(safeString(killsKey));
        if (modern instanceof Number) {
            return Math.max(0, ((Number) modern).intValue());
        }
        Object legacy = stats.get("kills");
        if (legacy instanceof Number) {
            return Math.max(0, ((Number) legacy).intValue());
        }
        return 0;
    }

    private int readMurdererKills(Document stats) {
        if (stats == null) {
            return 0;
        }
        Object modern = stats.get(MURDER_MYSTERY_KILLS_AS_MURDERER_KEY);
        if (modern instanceof Number) {
            return Math.max(0, ((Number) modern).intValue());
        }
        Object legacy = stats.get("killsAsMurderer");
        if (legacy instanceof Number) {
            return Math.max(0, ((Number) legacy).intValue());
        }
        Document custom = stats.get("custom", Document.class);
        if (custom == null) {
            return 0;
        }
        Object customModern = custom.get(MURDER_MYSTERY_KILLS_AS_MURDERER_KEY);
        if (customModern instanceof Number) {
            return Math.max(0, ((Number) customModern).intValue());
        }
        Object customLegacy = custom.get("killsAsMurderer");
        if (customLegacy instanceof Number) {
            return Math.max(0, ((Number) customLegacy).intValue());
        }
        return 0;
    }

    private int readMurdererWins(Document stats) {
        if (stats == null) {
            return 0;
        }
        Object modern = stats.get(MURDER_MYSTERY_WINS_AS_MURDERER_KEY);
        if (modern instanceof Number) {
            return Math.max(0, ((Number) modern).intValue());
        }
        Object legacy = stats.get("winsAsMurderer");
        if (legacy instanceof Number) {
            return Math.max(0, ((Number) legacy).intValue());
        }
        Document custom = stats.get("custom", Document.class);
        if (custom == null) {
            return 0;
        }
        Object customModern = custom.get(MURDER_MYSTERY_WINS_AS_MURDERER_KEY);
        if (customModern instanceof Number) {
            return Math.max(0, ((Number) customModern).intValue());
        }
        Object customLegacy = custom.get("winsAsMurderer");
        if (customLegacy instanceof Number) {
            return Math.max(0, ((Number) customLegacy).intValue());
        }
        return 0;
    }

    private UUID spawnHologramLine(Location location, String line) {
        if (!BUILD_RUNTIME_VISUALS_ENABLED) {
            return null;
        }
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class);
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setBasePlate(false);
        stand.setCanPickupItems(false);
        stand.setCustomNameVisible(true);
        stand.setCustomName(line == null ? "" : line);
        return stand.getUniqueId();
    }

    private void placeParkourMarker(Location location, List<String> hologramLines, boolean checkpoint) {
        if (!BUILD_RUNTIME_VISUALS_ENABLED) {
            return;
        }
        if (location == null || location.getWorld() == null) {
            return;
        }
        Location blockLocation = location.getBlock().getLocation();
        if (!isAir(blockLocation.getBlock().getType())) {
            blockLocation.add(0.0d, 1.0d, 0.0d);
        }
        if (isAir(blockLocation.getBlock().getType())) {
            blockLocation.getBlock().setType(pressurePlateMaterial(checkpoint));
        }
        List<String> lines = hologramLines == null ? Collections.<String>emptyList() : hologramLines;
        Location current = blockLocation.clone().add(0.5d, PARKOUR_HOLOGRAM_BASE_Y_OFFSET, 0.5d);
        for (String line : lines) {
            spawnHologramLine(current, line);
            current.subtract(0.0d, HOLOGRAM_LINE_SPACING, 0.0d);
        }
    }

    private List<String> parkourStartHologramLines() {
        List<String> lines = new ArrayList<String>(2);
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + "Parkour Challenge");
        lines.add(ChatColor.GREEN.toString() + ChatColor.BOLD + "Start");
        return lines;
    }

    private List<String> parkourCheckpointHologramLines(int checkpointNumber) {
        List<String> lines = new ArrayList<String>(2);
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + "Checkpoint");
        int index = Math.max(1, checkpointNumber);
        lines.add(ChatColor.AQUA.toString() + ChatColor.BOLD + "#" + index);
        return lines;
    }

    private List<String> parkourEndHologramLines() {
        List<String> lines = new ArrayList<String>(2);
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + "Parkour Challenge");
        lines.add(ChatColor.RED.toString() + ChatColor.BOLD + "End");
        return lines;
    }

    private Material pressurePlateMaterial(boolean checkpoint) {
        if (!checkpoint) {
            Material modernGold = Material.matchMaterial("LIGHT_WEIGHTED_PRESSURE_PLATE");
            if (modernGold != null) {
                return modernGold;
            }
            Material legacyGold = Material.matchMaterial("GOLD_PLATE");
            if (legacyGold != null) {
                return legacyGold;
            }
            return Material.GOLD_BLOCK;
        }
        Material modernIron = Material.matchMaterial("HEAVY_WEIGHTED_PRESSURE_PLATE");
        if (modernIron != null) {
            return modernIron;
        }
        Material legacyIron = Material.matchMaterial("IRON_PLATE");
        if (legacyIron != null) {
            return legacyIron;
        }
        return Material.IRON_BLOCK;
    }

    private boolean isAir(Material type) {
        return type == null || type == Material.AIR;
    }

    private ChatColor chatColorFromName(String raw, ChatColor fallback) {
        String value = safeString(raw).toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return ChatColor.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void removeEntity(UUID uuid) {
        if (uuid == null) {
            return;
        }
        Entity entity = resolveEntity(uuid);
        if (entity != null) {
            entity.remove();
        }
    }

    private Entity resolveEntity(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (entity == null || entity.getUniqueId() == null) {
                    continue;
                }
                if (uuid.equals(entity.getUniqueId())) {
                    return entity;
                }
            }
        }
        return null;
    }

    private double safeDouble(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return 0.0d;
        }
        try {
            return parent.get(key).getAsDouble();
        } catch (Exception ignored) {
            return 0.0d;
        }
    }

    private long safeLong(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return parent.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long safeLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private int safeInt(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return parent.get(key).getAsInt();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int safeInt(Object value) {
        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.toString().trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private World resolveWorldByName(String worldName) {
        String target = safeString(worldName);
        if (target.isEmpty()) {
            return null;
        }
        World exact = Bukkit.getWorld(target);
        if (exact != null) {
            return exact;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null || world.getName() == null) {
                continue;
            }
            if (target.equalsIgnoreCase(world.getName())) {
                return world;
            }
        }
        return null;
    }

    private World resolveExportTransitWorld(String excludedWorldName) {
        String excluded = safeString(excludedWorldName);
        World preferred = Bukkit.getWorld("world");
        if (preferred != null && (excluded.isEmpty() || !excluded.equalsIgnoreCase(safeString(preferred.getName())))) {
            return preferred;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null || world.getName() == null) {
                continue;
            }
            if (excluded.equalsIgnoreCase(world.getName())) {
                continue;
            }
            return world;
        }
        return null;
    }

    private Location remapLocationToWorld(Location source, World targetWorld) {
        if (targetWorld == null) {
            return null;
        }
        if (source == null) {
            return targetWorld.getSpawnLocation();
        }
        Location remapped = source.clone();
        remapped.setWorld(targetWorld);
        return remapped;
    }

    private String resolveMapRootPath() {
        String mapRoot = safeString(System.getenv("MAP_ROOT"));
        return mapRoot.isEmpty() ? "/maps" : mapRoot;
    }

    private void copyDirectoryReplacing(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            throw new IOException("Source world folder was not found: " + source);
        }
        if (target == null) {
            throw new IOException("Target map path is invalid.");
        }
        Path targetParent = target.getParent();
        if (targetParent != null) {
            Files.createDirectories(targetParent);
        }
        deleteDirectoryRecursively(target);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path targetDir = relative.getNameCount() == 0 ? target : target.resolve(relative.toString());
                Files.createDirectories(targetDir);
                applyExportPermissions(targetDir, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path targetFile = target.resolve(relative.toString());
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                applyExportPermissions(targetFile, false);
                return FileVisitResult.CONTINUE;
            }
        });
        normalizeExportPermissionsRecursively(target);
    }

    private void applyExportPermissions(Path path, boolean directory) {
        if (path == null) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            // Keep link metadata untouched; chmod-like operations may affect link targets.
            return;
        }
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            applyFallbackExportPermissions(path, directory);
            return;
        }
        try {
            Files.setPosixFilePermissions(path, directory ? EXPORT_DIRECTORY_PERMISSIONS : EXPORT_FILE_PERMISSIONS);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Fall back when POSIX attrs are unavailable or fail on mounted filesystems.
            applyFallbackExportPermissions(path, directory);
        }
    }

    private void normalizeExportPermissionsRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    applyExportPermissions(dir, true);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    applyExportPermissions(file, false);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort normalization only.
        }
    }

    private void applyFallbackExportPermissions(Path path, boolean directory) {
        if (path == null) {
            return;
        }
        try {
            File file = path.toFile();
            if (file == null) {
                return;
            }
            // Mirror 755 for directories and 644 for files as closely as Java IO allows.
            file.setReadable(true, false);
            file.setWritable(false, false);
            file.setWritable(true, true);
            file.setExecutable(directory, false);
        } catch (Exception ignored) {
            // Best-effort only.
        }
    }

    private void deleteDirectoryRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String safeString(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private String safeString(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return "";
        }
        try {
            return safeString(parent.get(key).getAsString());
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> filterByPrefix(List<String> source, String prefix) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        String raw = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String value : source) {
            if (value == null) {
                continue;
            }
            if (raw.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(raw)) {
                filtered.add(value);
            }
        }
        return filtered;
    }

    private static final class ParkourDraft {
        private String worldDirectory;
        private ServerType gameType;
        private Location start;
        private final List<Location> checkpoints = new ArrayList<Location>();
    }

    private static final class RuntimeNpc {
        private String entityId;
        private UUID entityUuid;
        private UUID hologramUuid;
        private List<UUID> hologramLineUuids;
        private boolean profileNpc;
        private boolean clickToPlayNpc;
        private UUID profileOwnerUuid;
        private String profileOwnerName;
        private String skinOwner;
        private String hologramColor;
        private ServerType gameType;
        private String worldDirectory;
    }

    private static final class RuntimeLeaderboard {
        private String entityId;
        private UUID anchorUuid;
        private List<UUID> lineUuids;
        private ServerType gameType;
        private String worldDirectory;
        private String metric;
    }

    private static final class LeaderboardEntry {
        private final UUID uuid;
        private final String name;
        private final int score;
        private final ChatColor nameColor;
        private int position;

        private LeaderboardEntry(UUID uuid, String name, int score, ChatColor nameColor) {
            this.uuid = uuid;
            this.name = name == null ? "" : name;
            this.score = Math.max(0, score);
            this.nameColor = nameColor == null ? ChatColor.GRAY : nameColor;
            this.position = 0;
        }
    }

    public static final class MapLocationEntry {
        private final MapLocationType type;
        private final int arrayIndex;
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final String itemName;
        private final int itemData;
        private final long createdAt;

        public MapLocationEntry(MapLocationType type,
                                int arrayIndex,
                                String worldName,
                                double x,
                                double y,
                                double z,
                                float yaw,
                                float pitch,
                                String itemName,
                                int itemData,
                                long createdAt) {
            this.type = type == null ? MapLocationType.PLAYER_SPAWN : type;
            this.arrayIndex = Math.max(0, arrayIndex);
            this.worldName = worldName == null ? "" : worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.itemName = itemName == null ? "" : itemName;
            this.itemData = Math.max(0, itemData);
            this.createdAt = Math.max(0L, createdAt);
        }

        public MapLocationType getType() {
            return type;
        }

        public int getArrayIndex() {
            return arrayIndex;
        }

        public String getWorldName() {
            return worldName;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }

        public float getYaw() {
            return yaw;
        }

        public float getPitch() {
            return pitch;
        }

        public String getItemName() {
            return itemName;
        }

        public int getItemData() {
            return itemData;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public String displayType() {
            switch (type) {
                case ITEM_DROP:
                    return "Drop Item";
                case HUB_SPAWN:
                    return "Hub Spawn";
                case WAITING_SPAWN:
                    return "Waiting Spawn";
                case HUB_NPC:
                    return "Click to Play NPC";
                case PROFILE_NPC:
                    return "Profile NPC";
                case LEADERBOARD:
                    return "Leaderboard Hologram";
                case PARKOUR_START:
                    return "Parkour Start";
                case PARKOUR_END:
                    return "Parkour End";
                case PARKOUR_CHECKPOINT:
                    int checkpointNumber = 0;
                    try {
                        checkpointNumber = Integer.parseInt(safeNumber(itemName));
                    } catch (Exception ignored) {
                    }
                    if (checkpointNumber <= 0) {
                        return "Parkour Checkpoint";
                    }
                    return "Parkour Checkpoint " + checkpointNumber;
                case PARKOUR_ROUTE:
                    int checkpoints = 0;
                    try {
                        checkpoints = Integer.parseInt(safeNumber(itemName));
                    } catch (Exception ignored) {
                    }
                    return "Parkour Route (" + checkpoints + " checkpoints)";
                case PLAYER_SPAWN:
                default:
                    return "Player Spawn";
            }
        }

        private String safeNumber(String value) {
            if (value == null) {
                return "0";
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                return "0";
            }
            for (int i = 0; i < trimmed.length(); i++) {
                char ch = trimmed.charAt(i);
                if (ch < '0' || ch > '9') {
                    return "0";
                }
            }
            return trimmed;
        }

        public String coordinates(BuildMapConfigService service) {
            if (service == null) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            out.append(service.formatCoord(x))
                    .append(", ")
                    .append(service.formatCoord(y))
                    .append(", ")
                    .append(service.formatCoord(z))
                    .append(", ")
                    .append(service.formatCoord(yaw))
                    .append(", ")
                    .append(service.formatCoord(pitch));
            return out.toString();
        }
    }

    public enum MapLocationType {
        PLAYER_SPAWN,
        ITEM_DROP,
        WAITING_SPAWN,
        HUB_SPAWN,
        HUB_NPC,
        PROFILE_NPC,
        LEADERBOARD,
        PARKOUR_START,
        PARKOUR_END,
        PARKOUR_CHECKPOINT,
        PARKOUR_ROUTE
    }
}
