package io.github.mebsic.game.manager;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.GameResult;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.MapConfigResolver;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.store.MapConfigStore;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.GameRewardUtil;
import io.github.mebsic.core.util.HubMessageUtil;
import io.github.mebsic.core.util.ScoreboardTitleAnimation;
import io.github.mebsic.core.util.ServerNameFormatUtil;
import io.github.mebsic.game.map.GameMap;
import io.github.mebsic.game.model.GamePlayer;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.service.BossBarService;
import io.github.mebsic.game.service.ScoreboardService;
import io.github.mebsic.game.service.TablistService;
import io.github.mebsic.game.service.TitleService;
import io.github.mebsic.game.util.LocationUtil;
import io.github.mebsic.game.util.ReturnToLobbyItem;
import io.github.mebsic.game.util.SpectatorItems;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class GameManager {
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    public static final String QUEUED_TRANSFER_MESSAGE = ChatColor.GREEN.toString() + ChatColor.BOLD + "Queued! Use the bed to return to lobby!";
    private static final long POST_GAME_TRANSFER_DELAY_TICKS = 200L; // 10 seconds
    private static final String RESTARTING_KICK_MESSAGE = ChatColor.RED + "This server is restarting!..";
    private static final String NO_LOBBY_AVAILABLE_MESSAGE = ChatColor.RED + CommonMessages.NO_SERVERS_AVAILABLE;
    private static final String TRANSFER_FAILED_MESSAGE = ChatColor.RED + "Could not send you to another server! Please try again later.";
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    private static final int DEFAULT_MAP_CONFIG_RELOAD_POLL_SECONDS = 5;
    private static final int FORCE_START_MIN_PLAYERS = 2;
    private static final double MYSTERY_DUST_REWARD_CHANCE = 0.40D;
    private static final int DEFAULT_MIN_MYSTERY_DUST_REWARD = 1;
    private static final int DEFAULT_MAX_MYSTERY_DUST_REWARD = 10;
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;
    private static final double[] SPAWN_Y_CORRECTION_OFFSETS = {0.5D, 1.0D, 1.5D, 2.0D, 2.5D, 3.0D};
    private static final String POST_GAME_FRAME_BAR = "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";
    private static final Sound COUNTDOWN_TICK_SOUND = Sound.CLICK;

    private final CorePlugin plugin;
    private final MapManager mapManager;
    private final ScoreboardService scoreboardService;
    private final TablistService tablistService;
    private final TitleService titleService;
    private final BossBarService bossBarService;
    private final Map<UUID, GamePlayer> players;
    private final Map<UUID, LinkedHashMap<String, Integer>> roundRewardSummary;
    private final Map<UUID, Integer> roundMysteryDustRewards;
    private final AtomicBoolean mapConfigReloadQueued;
    private final Map<UUID, ScoreboardTitleAnimation> scoreboardTitleAnimators;

    private GameState state;
    private Location lobby;
    private Location pregameSpawn;

    private int minPlayers;
    private int maxPlayers;
    private int countdownSeconds;
    private int gameSeconds;

    private int countdownRemaining;
    private int gameRemaining;
    private String serverName;
    private boolean restartServerAfterGameEnd;
    private boolean joinLockedForRestart;
    private boolean countdownIgnoresMinPlayers;

    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask restartWhenEmptyTask;
    private BukkitTask mapConfigPollTask;
    private BukkitTask nonInGameCleanupTask;
    private volatile String mapConfigFingerprint;
    private String endingScoreboardMapName;

    public GameManager(CorePlugin plugin, BossBarService bossBarService) {
        this.plugin = plugin;
        this.mapManager = new MapManager(plugin);
        this.tablistService = new TablistService(plugin.getCoreApi(), plugin.getServerType());
        this.tablistService.setVisibilityPolicy(this::isVisibleInTabForViewer);
        this.tablistService.setEnforceVisibilityWithHidePlayer(true);
        this.scoreboardService = new ScoreboardService(tablistService);
        this.titleService = new TitleService();
        this.bossBarService = bossBarService;
        this.players = new HashMap<>();
        this.roundRewardSummary = new HashMap<>();
        this.roundMysteryDustRewards = new HashMap<>();
        this.mapConfigReloadQueued = new AtomicBoolean(false);
        this.scoreboardTitleAnimators = new HashMap<>();
        this.state = GameState.WAITING;
        this.endingScoreboardMapName = "";
        publishStateToCore();
        this.joinLockedForRestart = false;
        this.countdownIgnoresMinPlayers = false;
        loadConfig();
        initializeMapConfigHotReload();
        startNonInGameCleanupTask();
    }

    public TablistService getTablistService() {
        return tablistService;
    }

    public void loadConfig() {
        refreshMinPlayers();
        this.maxPlayers = plugin.getConfig().getInt("maxPlayers", 16);
        this.countdownSeconds = plugin.getConfig().getInt("countdownSeconds", 15);
        this.gameSeconds = plugin.getConfig().getInt("gameSeconds", 270);
        this.lobby = LocationUtil.deserialize(plugin.getConfig().getString("lobby", ""));
        this.pregameSpawn = LocationUtil.deserialize(plugin.getConfig().getString("pregameSpawn", ""));
        this.serverName = ServerNameFormatUtil.toScoreboardCode(
                plugin.getConfig().getString("server.id", "mini1A"),
                plugin.getServerType()
        );
        this.restartServerAfterGameEnd = plugin.getConfig().getBoolean("game.restartServerOnEnd", true);
        mapManager.loadMaps();
    }

    private void initializeMapConfigHotReload() {
        if (!isGameServer()) {
            return;
        }
        this.mapConfigFingerprint = readMapConfigFingerprint();
        subscribeToMapConfigUpdates();
        startMapConfigPoller();
    }

    private void startNonInGameCleanupTask() {
        if (plugin == null) {
            return;
        }
        if (nonInGameCleanupTask != null) {
            nonInGameCleanupTask.cancel();
        }
        nonInGameCleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (state == GameState.IN_GAME) {
                return;
            }
            cleanupPregameWorldState();
        }, 20L, 20L);
    }

    private int defaultMinPlayers() {
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        if (type == ServerType.MURDER_MYSTERY) {
            return 4;
        }
        return 4;
    }

    private int minAllowedPlayers() {
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        if (type == ServerType.MURDER_MYSTERY) {
            return 4;
        }
        return 1;
    }

    private void refreshMinPlayers() {
        int configuredMinPlayers = plugin.getConfig().getInt("minPlayers", defaultMinPlayers());
        this.minPlayers = Math.max(minAllowedPlayers(), configuredMinPlayers);
    }

    public void saveConfig() {
        plugin.getConfig().set("lobby", LocationUtil.serialize(lobby));
        plugin.getConfig().set("pregameSpawn", LocationUtil.serialize(pregameSpawn));
        plugin.saveConfig();
        mapManager.saveMaps();
    }

    private boolean isGameServer() {
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        return type != null && type.isGame();
    }

    private void subscribeToMapConfigUpdates() {
        if (!isGameServer() || plugin == null || plugin.getPubSubService() == null) {
            return;
        }
        plugin.getPubSubService().subscribe(MAP_CONFIG_UPDATE_CHANNEL, this::handleMapConfigUpdateMessage);
    }

    private void handleMapConfigUpdateMessage(String message) {
        String updatedKey = parseUpdatedGameKey(message);
        if (updatedKey.isEmpty()) {
            return;
        }
        String currentKey = resolveMapConfigGameKey();
        if (!updatedKey.equalsIgnoreCase(currentKey)) {
            return;
        }
        queueMapConfigReload("redis-pubsub");
    }

    private void startMapConfigPoller() {
        if (!isGameServer() || plugin == null) {
            return;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return;
        }
        int configuredSeconds = plugin.getConfig().getInt("mapConfig.reloadPollSeconds", DEFAULT_MAP_CONFIG_RELOAD_POLL_SECONDS);
        if (configuredSeconds <= 0) {
            return;
        }
        long intervalTicks = Math.max(1L, configuredSeconds) * 20L;
        if (mapConfigPollTask != null) {
            mapConfigPollTask.cancel();
        }
        mapConfigPollTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            String latest = readMapConfigFingerprint();
            if (latest.isEmpty()) {
                return;
            }
            String previous = mapConfigFingerprint == null ? "" : mapConfigFingerprint;
            if (previous.isEmpty()) {
                mapConfigFingerprint = latest;
                return;
            }
            if (latest.equals(previous)) {
                return;
            }
            mapConfigFingerprint = latest;
            queueMapConfigReload("mongo-poll");
        }, intervalTicks, intervalTicks);
    }

    private void queueMapConfigReload(String source) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        if (!mapConfigReloadQueued.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                reloadMapConfig(source);
            } finally {
                mapConfigReloadQueued.set(false);
            }
        });
    }

    private void reloadMapConfig(String source) {
        if (!isGameServer()) {
            return;
        }
        try {
            String beforeMap = getActiveMapName();
            MapConfigResolver.apply(plugin, plugin.getConfig(), plugin.getMongoManager());
            boolean preserveActiveMap = state == GameState.STARTING
                    || state == GameState.IN_GAME
                    || state == GameState.ENDING;
            mapManager.loadMaps(preserveActiveMap);
            refreshMinPlayers();
            if (state == GameState.STARTING) {
                teleportToPregame();
            }
            updateScoreboardAll();
            String refreshedFingerprint = readMapConfigFingerprint();
            if (!refreshedFingerprint.isEmpty()) {
                mapConfigFingerprint = refreshedFingerprint;
            }
            String afterMap = getActiveMapName();
            plugin.getLogger().info("Reloaded game map config via " + safeString(source)
                    + " for key '" + resolveMapConfigGameKey() + "' (active map: " + beforeMap + " -> " + afterMap + ").");
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to hot reload game map config!\n" + ex.getMessage());
        }
    }

    private String readMapConfigFingerprint() {
        try {
            if (plugin == null) {
                return "";
            }
            MongoManager mongo = plugin.getMongoManager();
            if (mongo == null) {
                return "";
            }
            MongoCollection<Document> collection = mongo.getCollection(MongoManager.MAPS_COLLECTION);
            if (collection == null) {
                return "";
            }
            String gameKey = resolveMapConfigGameKey();
            String usedKey = gameKey;
            Document root = collection.find(Filters.eq("_id", gameKey)).first();
            if (root == null && !MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY.equals(gameKey)) {
                root = collection.find(Filters.eq("_id", MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY)).first();
                usedKey = MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
            }
            if (root == null) {
                return "";
            }
            Document copy = new Document(root);
            copy.remove("_id");
            return usedKey + "::" + copy.toJson();
        } catch (Exception ex) {
            return "";
        }
    }

    private String resolveMapConfigGameKey() {
        String group = plugin == null ? "" : plugin.getConfig().getString("server.group", "");
        String normalized = MapConfigStore.normalizeGameKey(group);
        if (normalized.isEmpty()) {
            return MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
        }
        return normalized;
    }

    private String parseUpdatedGameKey(String message) {
        String raw = safeString(message);
        if (raw.isEmpty()) {
            return "";
        }
        String key = raw;
        if (raw.toLowerCase(Locale.ROOT).startsWith(MAP_CONFIG_UPDATE_PREFIX)) {
            key = raw.substring(MAP_CONFIG_UPDATE_PREFIX.length());
        }
        key = MapConfigStore.normalizeGameKey(key);
        return key.isEmpty() ? "" : key;
    }

    public GameState getState() {
        return state;
    }

    public String getRegistryState() {
        if (joinLockedForRestart) {
            return "RESTARTING";
        }
        return state == null ? "WAITING" : state.name();
    }

    public int getPlayerCount() {
        return players.size();
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getMinPlayers() {
        refreshMinPlayers();
        return minPlayers;
    }

    public GamePlayer getPlayer(Player player) {
        if (player == null) {
            return null;
        }
        return players.get(player.getUniqueId());
    }

    public boolean isInGame(Player player) {
        return player != null && players.containsKey(player.getUniqueId());
    }

    public void handleJoin(Player player) {
        if (player == null) {
            return;
        }
        if (joinLockedForRestart) {
            player.kickPlayer(RESTARTING_KICK_MESSAGE);
            return;
        }
        if (players.size() >= maxPlayers) {
            player.kickPlayer(ChatColor.RED + "Game is full!");
            return;
        }
        scoreboardTitleAnimators.remove(player.getUniqueId());
        GamePlayer gamePlayer = createGamePlayer(player.getUniqueId());
        players.put(player.getUniqueId(), gamePlayer);
        titleService.reset(player);
        if (bossBarService != null) {
            bossBarService.show(player);
        }
        if (state == GameState.WAITING || state == GameState.STARTING) {
            preparePregamePlayer(player);
        } else {
            prepareLobbyPlayer(player);
        }
        if (state == GameState.IN_GAME) {
            gamePlayer.setAlive(false);
            applyDeadSpectatorState(player);
            player.sendMessage(ChatColor.GRAY + "You joined as a spectator.");
        }
        updateScoreboardAll();
        attemptStartCountdown();
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }
        GamePlayer gp = players.get(player.getUniqueId());
        if (state == GameState.IN_GAME && gp != null && gp.isAlive()) {
            onAlivePlayerQuitInGame(player, gp);
        }
        players.remove(player.getUniqueId());
        scoreboardTitleAnimators.remove(player.getUniqueId());
        scoreboardService.remove(player);
        if (bossBarService != null) {
            bossBarService.remove(player);
        }
        if (state == GameState.STARTING) {
            refreshMinPlayers();
            if (players.size() < getCountdownRequiredPlayers()) {
                cancelCountdownForInsufficientPlayers(!countdownIgnoresMinPlayers);
            }
        }
        if (state == GameState.IN_GAME) {
            checkWinConditions();
        }
        updateScoreboardAll();
    }

    public void setLobby(Location lobby) {
        this.lobby = lobby;
    }

    public void setPregameSpawn(Location location) {
        this.pregameSpawn = location;
    }

    public void addSpawn(Location location) {
        mapManager.addSpawn(location);
    }

    public void addDropItemSpawn(Location location) {
        mapManager.addDropItemSpawn(location);
    }

    public boolean setActiveMap(String name) {
        return mapManager.setActiveMap(name);
    }

    public List<String> getMapNames() {
        return mapManager.getMapNames();
    }

    public String getActiveMapName() {
        GameMap map = mapManager.getActiveMap();
        if (map == null) {
            return "none";
        }
        String display = safeString(mapManager.getActiveMapDisplayName());
        if (!display.isEmpty()) {
            return display;
        }
        return safeString(map.getName());
    }

    public void attemptStartCountdown() {
        refreshMinPlayers();
        if (state != GameState.WAITING) {
            return;
        }
        if (players.size() < minPlayers) {
            return;
        }
        startCountdown(false, true);
    }

    public boolean canForceStart() {
        return state == GameState.WAITING && players.size() >= FORCE_START_MIN_PLAYERS;
    }

    public void forceStart() {
        if (canForceStart()) {
            startCountdown(true, false);
        }
    }

    public void stopGame() {
        broadcast(ChatColor.YELLOW + "Game stopped.");
        endGame();
    }

    protected void startCountdown() {
        startCountdown(false, true);
    }

    protected void startCountdown(boolean ignoreMinPlayers) {
        startCountdown(ignoreMinPlayers, true);
    }

    protected void startCountdown(boolean ignoreMinPlayers, boolean movePlayersToPregame) {
        countdownIgnoresMinPlayers = ignoreMinPlayers;
        joinLockedForRestart = false;
        state = GameState.STARTING;
        publishStateToCore();
        countdownRemaining = countdownSeconds;
        cleanupPregameWorldState();
        if (movePlayersToPregame) {
            teleportToPregame();
        }
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                refreshMinPlayers();
                if (players.size() < getCountdownRequiredPlayers()) {
                    cancelCountdownForInsufficientPlayers(!countdownIgnoresMinPlayers);
                    return;
                }
                cleanupPregameWorldState();
                if (countdownRemaining <= 0) {
                    cancel();
                    startGame();
                    return;
                }
                if (isCountdownCueSecond(countdownRemaining)) {
                    broadcastCountdownMessage(countdownRemaining);
                    playCountdownTickSoundToAllPlayers();
                }
                updateScoreboardAll();
                countdownRemaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    protected void startGame() {
        countdownIgnoresMinPlayers = false;
        joinLockedForRestart = false;
        GameMap activeMap = mapManager.getActiveMap();
        if (activeMap == null || activeMap.getSpawnPoints().isEmpty()) {
            broadcast(ChatColor.RED + "No spawn points set!");
            state = GameState.WAITING;
            publishStateToCore();
            return;
        }
        state = GameState.IN_GAME;
        endingScoreboardMapName = "";
        publishStateToCore();
        gameRemaining = gameSeconds;
        applyMapTime(activeMap);
        clearRoundRewardSummary();
        resetGamePlayers();
        onGameStarted(activeMap);
        teleportToSpawns();
        startGameTimer();
        updateScoreboardAll();
    }

    protected void cleanupPregameWorldState() {
        for (World world : collectPregameCleanupWorlds()) {
            if (world == null) {
                continue;
            }
            for (Entity entity : new ArrayList<Entity>(world.getEntities())) {
                if (!shouldRemovePregameEntity(entity)) {
                    continue;
                }
                try {
                    entity.remove();
                } catch (Exception ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
    }

    protected boolean shouldRemovePregameEntity(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return false;
        }
        if (entity instanceof Item || entity instanceof Projectile) {
            return true;
        }
        if (entity instanceof ArmorStand) {
            return false;
        }
        return entity instanceof org.bukkit.entity.LivingEntity;
    }

    private Set<World> collectPregameCleanupWorlds() {
        Set<World> worlds = new HashSet<World>();
        GameMap activeMap = mapManager == null ? null : mapManager.getActiveMap();
        if (activeMap != null) {
            for (Location spawn : activeMap.getSpawnPoints()) {
                if (spawn != null && spawn.getWorld() != null) {
                    worlds.add(spawn.getWorld());
                }
            }
            for (Location dropSpawn : activeMap.getDropItemSpawns()) {
                if (dropSpawn != null && dropSpawn.getWorld() != null) {
                    worlds.add(dropSpawn.getWorld());
                }
            }
        }
        Location pregame = mapManager == null ? null : mapManager.getPregameSpawnForActiveMap();
        if (pregame == null) {
            pregame = pregameSpawn;
        }
        if (pregame != null && pregame.getWorld() != null) {
            worlds.add(pregame.getWorld());
        }
        if (lobby != null && lobby.getWorld() != null) {
            worlds.add(lobby.getWorld());
        }
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.getWorld() != null) {
                worlds.add(player.getWorld());
            }
        }
        return worlds;
    }

    private void startGameTimer() {
        if (gameTask != null) {
            gameTask.cancel();
        }
        gameTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.IN_GAME) {
                    cancel();
                    return;
                }
                gameRemaining--;
                if (onGameTimerTick(gameRemaining)) {
                    cancel();
                    return;
                }
                if (gameRemaining <= 0) {
                    broadcast(ChatColor.GREEN + "Time's up!");
                    endGame();
                    cancel();
                    return;
                }
                onGameSecondElapsed();
                updateScoreboardAll();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    protected void teleportToSpawns() {
        GameMap map = mapManager.getActiveMap();
        if (map == null) {
            return;
        }
        List<Player> online = new ArrayList<>();
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                online.add(player);
            }
        }
        List<Location> spawnPoints = new ArrayList<>(map.getSpawnPoints());
        if (spawnPoints.isEmpty()) {
            return;
        }
        java.util.Collections.shuffle(spawnPoints);
        for (int i = 0; i < online.size(); i++) {
            Player player = online.get(i);
            Location spawn = spawnPoints.get(i % spawnPoints.size());
            Location target = resolveSpawnTeleportTarget(spawn);
            if (target == null) {
                continue;
            }
            World world = target.getWorld();
            if (world != null) {
                world.getChunkAt(target).load();
            }
            player.teleport(target);
        }
    }

    private Location resolveSpawnTeleportTarget(Location configuredSpawn) {
        if (configuredSpawn == null) {
            return null;
        }
        Location spawn = configuredSpawn.clone();
        World world = spawn.getWorld();
        if (world == null) {
            return spawn;
        }
        if (isClearForPlayer(spawn)) {
            return spawn;
        }

        for (double offset : SPAWN_Y_CORRECTION_OFFSETS) {
            Location shifted = spawn.clone().add(0.0D, offset, 0.0D);
            if (isClearForPlayer(shifted)) {
                return shifted;
            }
        }

        int highestY = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ()) + 1;
        Location highest = spawn.clone();
        highest.setY(highestY);
        if (isClearForPlayer(highest)) {
            return highest;
        }
        return spawn;
    }

    private boolean isClearForPlayer(Location feet) {
        if (feet == null || feet.getWorld() == null) {
            return false;
        }
        Location head = feet.clone().add(0.0D, 1.0D, 0.0D);
        return !isSolidMaterial(feet.getBlock().getType()) && !isSolidMaterial(head.getBlock().getType());
    }

    private boolean isSolidMaterial(Material material) {
        if (material == null) {
            return false;
        }
        try {
            return material.isSolid();
        } catch (NoSuchMethodError ignored) {
            return material != Material.AIR;
        }
    }

    protected void teleportToPregame() {
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                preparePregamePlayer(player);
            }
        }
    }

    public void prepareLobbyPlayer(Player player) {
        if (player == null) {
            return;
        }
        resetPlayer(player);
        player.teleport(lobby == null ? player.getLocation() : lobby);
        player.getInventory().clear();
        onPrepareLobbyPlayer(player);
        giveReturnToLobbyItem(player);
    }

    public void preparePregamePlayer(Player player) {
        if (player == null) {
            return;
        }
        resetPlayer(player);
        Location mapPregame = mapManager == null ? null : mapManager.getPregameSpawnForActiveMap();
        Location target = mapPregame;
        if (target == null) {
            target = pregameSpawn;
        }
        if (target == null) {
            target = lobby;
        }
        if (target == null) {
            target = player.getLocation();
        }
        player.teleport(target);
        giveReturnToLobbyItem(player);
    }

    public void resetPlayer(Player player) {
        if (player == null) {
            return;
        }
        titleService.reset(player);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setFireTicks(0);
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        setPlayerEntityCollision(player, true);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGameMode(GameMode.ADVENTURE);
        for (UUID uuid : players.keySet()) {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null) {
                target.showPlayer(player);
            }
        }
    }

    protected void applyDeadSpectatorState(Player player) {
        if (player == null) {
            return;
        }
        Profile profile = plugin == null ? null : plugin.getProfile(player.getUniqueId());
        int speedLevel = profile == null ? 0 : clampSpectatorSpeed(profile.getSpectatorSpeedLevel());
        boolean nightVision = profile == null || profile.isSpectatorNightVisionEnabled();
        player.setGameMode(GameMode.ADVENTURE);
        setPlayerEntityCollision(player, false);
        player.setAllowFlight(true);
        player.setFlying(true);
        SpectatorItems.applyTo(player, plugin.getServerType());
        player.removePotionEffect(PotionEffectType.SPEED);
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (nightVision) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false), true);
        }
        if (speedLevel > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, speedLevel - 1, false, false), true);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false), true);
        updateSpectatorVisibility(player);
        refreshDeadSpectatorViewerVisibility();
    }

    public void restoreDeadSpectatorState(Player player) {
        applyDeadSpectatorState(player);
    }

    private void setPlayerEntityCollision(Player player, boolean collidesWithEntities) {
        if (player == null) {
            return;
        }
        try {
            player.spigot().setCollidesWithEntities(collidesWithEntities);
        } catch (Throwable ignored) {
            // Some forks may not expose this API consistently; continue without hard-failing.
        }
    }

    protected void updateSpectatorVisibility(Player spectator) {
        if (spectator == null) {
            return;
        }
        Profile profile = plugin == null ? null : plugin.getProfile(spectator.getUniqueId());
        boolean hideOtherSpectators = profile != null && profile.isSpectatorHideOtherSpectatorsEnabled();
        for (UUID uuid : players.keySet()) {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null) {
                continue;
            }
            GamePlayer gp = players.get(uuid);
            boolean alive = gp != null && gp.isAlive();
            if (alive) {
                target.hidePlayer(spectator);
                if (!target.getUniqueId().equals(spectator.getUniqueId())) {
                    spectator.showPlayer(target);
                }
            } else {
                target.showPlayer(spectator);
                if (!target.getUniqueId().equals(spectator.getUniqueId())) {
                    if (hideOtherSpectators) {
                        spectator.hidePlayer(target);
                    } else {
                        spectator.showPlayer(target);
                    }
                }
            }
        }
    }

    private void refreshDeadSpectatorViewerVisibility() {
        for (UUID uuid : players.keySet()) {
            GamePlayer gp = players.get(uuid);
            if (gp == null || gp.isAlive()) {
                continue;
            }
            Player spectator = Bukkit.getPlayer(uuid);
            if (spectator == null || !spectator.isOnline()) {
                continue;
            }
            updateSpectatorVisibility(spectator);
        }
    }

    private int clampSpectatorSpeed(int speed) {
        return Math.max(0, Math.min(4, speed));
    }

    protected boolean isVisibleInTabForViewer(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            return true;
        }
        if (state != GameState.IN_GAME) {
            return true;
        }
        GamePlayer viewerData = players.get(viewer.getUniqueId());
        if (viewerData == null || !viewerData.isAlive()) {
            return true;
        }
        GamePlayer targetData = players.get(target.getUniqueId());
        return targetData == null || targetData.isAlive();
    }

    public void checkWinConditions() {
        if (state != GameState.IN_GAME) {
            return;
        }
        int alive = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isAlive()) {
                alive++;
            }
        }
        if (alive <= 1) {
            endGame();
        }
    }

    protected void endGame() {
        endGame(POST_GAME_TRANSFER_DELAY_TICKS);
    }

    protected void endGame(long resetDelayTicks) {
        if (state == GameState.ENDING) {
            return;
        }
        endingScoreboardMapName = getActiveMapName();
        final boolean transferAfterEnd = shouldTransferPlayersAfterGameEnd();
        final boolean showQueuedTransferSummaryLine = transferAfterEnd && hasAvailableTransferTargetForCurrentGameType();
        if (transferAfterEnd) {
            joinLockedForRestart = true;
        }
        state = GameState.ENDING;
        publishStateToCore();
        applyPostGameSpectatorItems();
        if (transferAfterEnd) {
            preparePlayersForPostGameTransfer();
        }
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        onGameEnding();
        awardPostGameMysteryDustRewards();
        showPostGameSummary(showQueuedTransferSummaryLine);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (transferAfterEnd) {
                    rotateToNextMapAndPersistActiveSelection();
                    restartServerAndRequeuePlayers();
                    return;
                }
                joinLockedForRestart = false;
                state = GameState.WAITING;
                endingScoreboardMapName = "";
                publishStateToCore();
                rotateToNextMapAndPersistActiveSelection();
                for (UUID uuid : players.keySet()) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        prepareLobbyPlayer(player);
                    }
                }
                updateScoreboardAll();
                attemptStartCountdown();
            }
        }.runTaskLater(plugin, resetDelayTicks);
    }

    private void rotateToNextMapAndPersistActiveSelection() {
        if (mapManager == null) {
            return;
        }
        mapManager.rotateToNextMap();

        if (!isGameServer() || plugin == null) {
            return;
        }
        GameMap active = mapManager.getActiveMap();
        String activeName = active == null ? "" : safeString(active.getName());
        if (activeName.isEmpty()) {
            return;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return;
        }

        String gameKey = resolveMapConfigGameKey();
        try {
            MapConfigStore store = new MapConfigStore(mongo);
            store.ensureDefaults(gameKey);
            store.setActiveMap(gameKey, activeName);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to persist rotated map '" + activeName + "' for key '"
                    + gameKey + "'!\n" + ex.getMessage());
        }
    }

    protected boolean shouldRestartServerAfterGameEnd() {
        return restartServerAfterGameEnd;
    }

    protected boolean shouldTransferPlayersAfterGameEnd() {
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        if (type != null && type.isGame()) {
            return true;
        }
        return shouldRestartServerAfterGameEnd();
    }

    protected void restartServerAndRequeuePlayers() {
        joinLockedForRestart = true;
        if (restartWhenEmptyTask != null) {
            restartWhenEmptyTask.cancel();
            restartWhenEmptyTask = null;
        }
        TransferDestination destination = resolvePostGameTransferDestination();
        List<UUID> snapshot = new ArrayList<>(players.keySet());
        for (UUID uuid : snapshot) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                transferPlayerAfterGameEnd(player, destination);
            }
        }
        restartWhenEmptyTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (plugin.getServer().getOnlinePlayers().isEmpty()) {
                if (restartWhenEmptyTask != null) {
                    restartWhenEmptyTask.cancel();
                    restartWhenEmptyTask = null;
                }
                triggerServerRestart();
            }
        }, 20L, 20L);
    }

    private void giveReturnToLobbyItem(Player player) {
        if (player == null || state == GameState.IN_GAME) {
            return;
        }
        setReturnToLobbyItem(player);
    }

    private void preparePlayersForPostGameTransfer() {
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            SpectatorItems.applyPostGameTo(player, plugin.getServerType());
        }
    }

    private void applyPostGameSpectatorItems() {
        for (Map.Entry<UUID, GamePlayer> entry : players.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            SpectatorItems.applyPostGameTo(player, plugin.getServerType());
        }
    }

    private void setReturnToLobbyItem(Player player) {
        if (player == null) {
            return;
        }
        player.getInventory().setItem(ReturnToLobbyItem.HOTBAR_SLOT, ReturnToLobbyItem.create());
    }

    private void showPostGameSummary(boolean showQueuedTransferSummaryLine) {
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            sendPostGameSummary(player, players.get(uuid), showQueuedTransferSummaryLine);
        }
    }

    private void sendPostGameSummary(Player player, GamePlayer gamePlayer, boolean showQueuedTransferSummaryLine) {
        if (player == null || gamePlayer == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        appendPostGameSummaryHeader(player, gamePlayer, lines);
        appendPostGameSummaryLines(player, gamePlayer, lines);
        appendPostGameRewardSummary(player, gamePlayer, lines);
        appendPostGameMysteryDustRewardLine(gamePlayer, lines);
        if (lines.isEmpty()) {
            return;
        }
        String frameLine = getPostGameFrameLine();
        player.sendMessage("");
        if (!frameLine.isEmpty()) {
            player.sendMessage(frameLine);
        }
        for (String line : lines) {
            player.sendMessage(line == null ? "" : line);
        }
        if (!frameLine.isEmpty()) {
            player.sendMessage(frameLine);
        }
        sendQueuedPostGameLevelUpMessage(player);
        if (showQueuedTransferSummaryLine) {
            player.sendMessage("");
            player.sendMessage(QUEUED_TRANSFER_MESSAGE);
        }
        player.sendMessage("");
    }

    private void appendPostGameSummaryHeader(Player player, GamePlayer gamePlayer, List<String> lines) {
        String headline = cleanSummaryLine(getPostGameHeadline(player, gamePlayer));
        if (!headline.isEmpty()) {
            lines.add(headline);
        }
        String outcome = cleanSummaryLine(getPostGameOutcomeLine(player, gamePlayer));
        if (!outcome.isEmpty()) {
            lines.add(outcome);
        }
    }

    protected void appendPostGameRewardSummary(Player player, GamePlayer gamePlayer, List<String> lines) {
        GameResult result = createPostGameResult(player, gamePlayer);
        if (result == null) {
            return;
        }
        GameRewardUtil.ExperienceBreakdown breakdown = GameRewardUtil.calculateExperienceBreakdown(result);
        lines.add("");
        lines.add(ChatColor.WHITE + "Reward Summary:");
        lines.add(ChatColor.GRAY + "End of Game: " + ChatColor.AQUA + "+" + breakdown.getEndOfGameExperience() + " XP");
        if (breakdown.getWinExperience() > 0) {
            lines.add(ChatColor.GRAY + "Win Bonus: " + ChatColor.AQUA + "+" + breakdown.getWinExperience() + " XP");
        }
        if (breakdown.getKillExperience() > 0) {
            lines.add(ChatColor.GRAY + "Kills (" + Math.max(0, result.getKills()) + "): "
                    + ChatColor.AQUA + "+" + breakdown.getKillExperience() + " XP");
        }
        if (breakdown.getConsolationExperience() > 0) {
            lines.add(ChatColor.GRAY + "Consolation: " + ChatColor.AQUA + "+" + breakdown.getConsolationExperience() + " XP");
        }
        lines.add(ChatColor.WHITE + "Total Experience: " + ChatColor.GREEN + "+" + breakdown.getTotalExperience() + " XP");
    }

    private String cleanSummaryLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return line;
    }

    private void sendQueuedPostGameLevelUpMessage(Player player) {
        if (player == null || plugin == null) {
            return;
        }
        List<String> lines = plugin.consumeQueuedPostGameNetworkLevelUpAnnouncement(player.getUniqueId());
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            player.sendMessage(line == null ? "" : line);
        }
    }

    private boolean hasAvailableTransferTargetForCurrentGameType() {
        ServerType gameType = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        if (gameType == null || !gameType.isGame()) {
            return false;
        }
        String targetName = findBestQueueTargetName(gameType);
        return targetName != null && !targetName.isEmpty();
    }

    private TransferDestination resolvePostGameTransferDestination() {
        ServerType gameType = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        String gameTarget = findBestQueueTargetName(gameType);
        if (!safeString(gameTarget).isEmpty()) {
            return TransferDestination.toGame(gameType, gameTarget);
        }
        String hubTarget = findBestHubTargetName(gameType);
        if (!safeString(hubTarget).isEmpty()) {
            return TransferDestination.toHub(gameType, hubTarget, true);
        }
        return TransferDestination.none(gameType);
    }

    private void transferPlayerAfterGameEnd(Player player, TransferDestination destination) {
        if (player == null || !player.isOnline() || destination == null) {
            return;
        }
        if (destination.notifyNoEquivalentGame) {
            String gameName = HubMessageUtil.gameDisplayName(destination.gameType);
            player.sendMessage(ChatColor.RED + "Could not find another " + gameName + " game, so you were sent to the lobby!");
        }
        String target = safeString(destination.targetServer);
        if (target.isEmpty()) {
            player.sendMessage(NO_LOBBY_AVAILABLE_MESSAGE);
            return;
        }
        if (!sendConnect(player, target)) {
            player.sendMessage(TRANSFER_FAILED_MESSAGE);
        }
    }

    private boolean sendConnect(Player player, String targetServer) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        String target = safeString(targetServer);
        if (target.isEmpty()) {
            return false;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(target);
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String findBestQueueTargetName(ServerType gameType) {
        if (plugin == null || gameType == null || !gameType.isGame()) {
            return null;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return null;
        }
        MongoCollection<Document> collection = mongo.getServerRegistry();
        if (collection == null) {
            return null;
        }

        String group = safeString(plugin.getConfig().getString("server.group", ""));
        String currentServerId = safeString(plugin.getConfig().getString("server.id", ""));
        int staleSeconds = Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        long now = System.currentTimeMillis();

        Map<String, Document> latestByServerId = new HashMap<>();
        Map<String, Long> heartbeatByServerId = new HashMap<>();
        for (Document doc : collection.find(Filters.eq("type", gameType.getId()))) {
            if (!isQueueDestinationCandidate(doc, now, staleSeconds, group, currentServerId)) {
                continue;
            }
            String serverId = safeString(doc.getString("_id"));
            String key = serverId.toLowerCase(Locale.ROOT);
            long heartbeat = doc.getLong("lastHeartbeat") == null ? Long.MIN_VALUE : doc.getLong("lastHeartbeat");
            Long known = heartbeatByServerId.get(key);
            if (known != null && known >= heartbeat) {
                continue;
            }
            heartbeatByServerId.put(key, heartbeat);
            latestByServerId.put(key, doc);
        }
        if (latestByServerId.isEmpty()) {
            return null;
        }

        Document best = null;
        int bestPlayers = Integer.MAX_VALUE;
        String bestServerId = "";
        for (Document candidate : latestByServerId.values()) {
            int players = safeInt(candidate.get("players"));
            String serverId = safeString(candidate.getString("_id"));
            if (best == null
                    || players < bestPlayers
                    || (players == bestPlayers && serverId.compareToIgnoreCase(bestServerId) < 0)) {
                best = candidate;
                bestPlayers = players;
                bestServerId = serverId;
            }
        }
        return safeString(best == null ? null : best.getString("_id"));
    }

    private String findBestHubTargetName(ServerType gameType) {
        ServerType preferredHubType = gameType == null ? ServerType.UNKNOWN : gameType.toHubType();
        if (preferredHubType == null || !preferredHubType.isHub()) {
            return null;
        }
        return findBestHubTargetNameForType(preferredHubType);
    }

    private String findBestHubTargetNameForType(ServerType hubType) {
        if (plugin == null || hubType == null || !hubType.isHub()) {
            return null;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return null;
        }
        MongoCollection<Document> collection = mongo.getServerRegistry();
        if (collection == null) {
            return null;
        }

        String group = safeString(plugin.getConfig().getString("server.group", ""));
        String currentServerId = safeString(plugin.getConfig().getString("server.id", ""));
        int staleSeconds = Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        long now = System.currentTimeMillis();

        Map<String, Document> latestByServerId = new HashMap<>();
        Map<String, Long> heartbeatByServerId = new HashMap<>();
        for (Document doc : collection.find(Filters.eq("type", hubType.getId()))) {
            if (!isHubDestinationCandidate(doc, now, staleSeconds, group, currentServerId)) {
                continue;
            }
            String serverId = safeString(doc.getString("_id"));
            String key = serverId.toLowerCase(Locale.ROOT);
            long heartbeat = doc.getLong("lastHeartbeat") == null ? Long.MIN_VALUE : doc.getLong("lastHeartbeat");
            Long known = heartbeatByServerId.get(key);
            if (known != null && known >= heartbeat) {
                continue;
            }
            heartbeatByServerId.put(key, heartbeat);
            latestByServerId.put(key, doc);
        }
        if (latestByServerId.isEmpty()) {
            return null;
        }

        Document best = null;
        int bestPlayers = Integer.MAX_VALUE;
        String bestServerId = "";
        for (Document candidate : latestByServerId.values()) {
            int players = safeInt(candidate.get("players"));
            String serverId = safeString(candidate.getString("_id"));
            if (best == null
                    || players < bestPlayers
                    || (players == bestPlayers && serverId.compareToIgnoreCase(bestServerId) < 0)) {
                best = candidate;
                bestPlayers = players;
                bestServerId = serverId;
            }
        }
        return safeString(best == null ? null : best.getString("_id"));
    }

    private boolean isQueueDestinationCandidate(Document doc,
                                                long now,
                                                int staleSeconds,
                                                String group,
                                                String currentServerId) {
        if (doc == null) {
            return false;
        }
        String serverId = safeString(doc.getString("_id"));
        if (serverId.isEmpty()) {
            return false;
        }
        if (!currentServerId.isEmpty() && currentServerId.equalsIgnoreCase(serverId)) {
            return false;
        }

        if (!group.isEmpty()) {
            String entryGroup = safeString(doc.getString("group"));
            if (!group.equalsIgnoreCase(entryGroup)) {
                return false;
            }
        }

        String status = safeString(doc.getString("status"));
        if (!status.isEmpty() && !status.equalsIgnoreCase("online")) {
            return false;
        }

        if (staleSeconds > 0) {
            Long heartbeat = doc.getLong("lastHeartbeat");
            if (heartbeat != null && now - heartbeat > staleSeconds * 1000L) {
                return false;
            }
        }

        int players = safeInt(doc.get("players"));
        int maxPlayers = safeInt(doc.get("maxPlayers"));
        if (maxPlayers > 0 && players >= maxPlayers) {
            return false;
        }

        String stateValue = safeString(doc.getString("state"));
        if (stateValue.isEmpty()) {
            return true;
        }
        String normalizedState = stateValue.toUpperCase(Locale.ROOT);
        return !normalizedState.equals("IN_GAME")
                && !normalizedState.equals("ENDING")
                && !normalizedState.equals("RESTARTING")
                && !normalizedState.equals("LOCKED")
                && !normalizedState.equals("WAITING_RESTART");
    }

    private boolean isHubDestinationCandidate(Document doc,
                                              long now,
                                              int staleSeconds,
                                              String group,
                                              String currentServerId) {
        if (doc == null) {
            return false;
        }
        String serverId = safeString(doc.getString("_id"));
        if (serverId.isEmpty()) {
            return false;
        }
        if (!currentServerId.isEmpty() && currentServerId.equalsIgnoreCase(serverId)) {
            return false;
        }
        if (!group.isEmpty()) {
            String entryGroup = safeString(doc.getString("group"));
            if (!group.equalsIgnoreCase(entryGroup)) {
                return false;
            }
        }
        String status = safeString(doc.getString("status"));
        if (!status.isEmpty() && !status.equalsIgnoreCase("online")) {
            return false;
        }
        if (staleSeconds > 0) {
            Long heartbeat = doc.getLong("lastHeartbeat");
            if (heartbeat != null && now - heartbeat > staleSeconds * 1000L) {
                return false;
            }
        }
        int players = safeInt(doc.get("players"));
        int maxPlayers = safeInt(doc.get("maxPlayers"));
        if (maxPlayers > 0 && players >= maxPlayers) {
            return false;
        }
        String stateValue = safeString(doc.getString("state"));
        if (stateValue.isEmpty()) {
            return true;
        }
        String normalizedState = stateValue.toUpperCase(Locale.ROOT);
        return !normalizedState.equals("ENDING")
                && !normalizedState.equals("RESTARTING")
                && !normalizedState.equals("LOCKED")
                && !normalizedState.equals("WAITING_RESTART");
    }

    private String safeString(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private int safeInt(Object value) {
        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static final class TransferDestination {
        private final ServerType gameType;
        private final String targetServer;
        private final boolean notifyNoEquivalentGame;

        private TransferDestination(ServerType gameType, String targetServer, boolean notifyNoEquivalentGame) {
            this.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
            this.targetServer = targetServer;
            this.notifyNoEquivalentGame = notifyNoEquivalentGame;
        }

        private static TransferDestination toGame(ServerType gameType, String targetServer) {
            return new TransferDestination(gameType, targetServer, false);
        }

        private static TransferDestination toHub(ServerType gameType, String targetServer, boolean notifyNoEquivalentGame) {
            return new TransferDestination(gameType, targetServer, notifyNoEquivalentGame);
        }

        private static TransferDestination none(ServerType gameType) {
            return new TransferDestination(gameType, null, false);
        }
    }

    private void triggerServerRestart() {
        if (restartWhenEmptyTask != null) {
            restartWhenEmptyTask.cancel();
            restartWhenEmptyTask = null;
        }
        boolean accepted = false;
        try {
            ConsoleCommandSender console = Bukkit.getConsoleSender();
            accepted = Bukkit.dispatchCommand(console, "restart");
        } catch (Exception ex) {
            if (plugin != null) {
                plugin.getLogger().warning("Failed to execute restart command!\n" + ex.getMessage());
            }
        }

        if (!accepted) {
            Bukkit.shutdown();
            return;
        }

        // Some environments report restart command accepted even when no restart occurs.
        // Force a shutdown shortly after to guarantee container/server restart behavior.
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 40L);
    }

    protected void broadcast(String message) {
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    public void updateScoreboardAll() {
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updateScoreboard(player);
            }
        }
    }

    public void updateAnimatedScoreboardTitles() {
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            scoreboardService.updateTitle(player, getScoreboardTitle(player));
        }
    }

    public void updateScoreboard(Player player) {
        updateScoreboard(player, getScoreboardTitle(player));
    }

    private void updateScoreboard(Player player, String title) {
        GamePlayer gp = players.get(player.getUniqueId());
        List<String> lines = new ArrayList<>();
        if (state == GameState.WAITING || state == GameState.STARTING) {
            lines.add(ChatColor.WHITE + "Server: " + ChatColor.GREEN + serverName);
            lines.add(ChatColor.WHITE + "Map: " + ChatColor.GREEN + getScoreboardMapName());
            lines.add(ChatColor.WHITE + "Players: " + ChatColor.GREEN + players.size() + "/" + maxPlayers);
            if (state == GameState.STARTING) {
                lines.add(ChatColor.WHITE + "Starting in " + ChatColor.GREEN + formatTime(countdownRemaining) + "s");
            } else {
                lines.add(ChatColor.WHITE + "Waiting...");
            }
            appendPregameScoreboardLines(player, gp, lines);
        } else {
            appendInGameScoreboardLines(player, gp, lines);
            if (lines.isEmpty()) {
                appendDefaultInGameScoreboardLines(lines);
            }
        }
        scoreboardService.update(player, title, lines);
    }

    protected String getScoreboardTitle() {
        return ChatColor.YELLOW.toString() + ChatColor.BOLD + resolveScoreboardGameTypeTitle();
    }

    private String getScoreboardTitle(Player player) {
        if (player == null) {
            return getScoreboardTitle();
        }
        ScoreboardTitleAnimation animator = scoreboardTitleAnimators.computeIfAbsent(
                player.getUniqueId(),
                id -> new ScoreboardTitleAnimation(resolveScoreboardGameTypeTitle())
        );
        return animator.resolve(shouldAnimateScoreboardTitle());
    }

    private String resolveScoreboardGameTypeTitle() {
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        String title = type == null ? "" : type.getGameTypeDisplayName();
        if (title == null || title.trim().isEmpty()) {
            return "GAME";
        }
        return title;
    }

    private boolean shouldAnimateScoreboardTitle() {
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        if (type != null && type.isHub()) {
            return true;
        }
        if (type != null && type.isGame()) {
            return state == GameState.WAITING || state == GameState.STARTING;
        }
        return false;
    }

    protected String getPostGameFrameLine() {
        return ChatColor.GREEN.toString() + ChatColor.BOLD + POST_GAME_FRAME_BAR;
    }

    protected String getPostGameHeadline(Player player, GamePlayer gamePlayer) {
        return ChatColor.YELLOW.toString() + ChatColor.BOLD + "GAME OVER";
    }

    protected String getPostGameOutcomeLine(Player player, GamePlayer gamePlayer) {
        if (didPlayerWin(player, gamePlayer)) {
            return ChatColor.GREEN + "You won this round!";
        }
        return ChatColor.RED + "You lost this round!";
    }

    protected boolean didPlayerWin(Player player, GamePlayer gamePlayer) {
        return gamePlayer != null && gamePlayer.isAlive();
    }

    protected GameResult createPostGameResult(Player player, GamePlayer gamePlayer) {
        if (gamePlayer == null) {
            return null;
        }
        ServerType gameType = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        return new GameResult(gamePlayer.getUuid(), gameType, didPlayerWin(player, gamePlayer), gamePlayer.getKills());
    }

    protected void appendInGameScoreboardLines(Player player, GamePlayer gp, List<String> lines) {
        // Optional extension hook.
    }

    protected void appendDefaultInGameScoreboardLines(List<String> lines) {
        lines.add(ChatColor.WHITE + "Map: " + ChatColor.GREEN + getScoreboardMapName());
        lines.add(ChatColor.WHITE + "State: " + ChatColor.GREEN + state.name());
        lines.add(ChatColor.WHITE + "Alive: " + ChatColor.GREEN + getAlivePlayers());
        lines.add(ChatColor.WHITE + "Time: " + ChatColor.GREEN + formatTime(gameRemaining));
    }

    protected String getScoreboardMapName() {
        if (state == GameState.ENDING) {
            String endingMap = safeString(endingScoreboardMapName);
            if (!endingMap.isEmpty()) {
                return endingMap;
            }
        }
        return getActiveMapName();
    }

    protected void appendPregameScoreboardLines(Player player, GamePlayer gp, List<String> lines) {
        // Optional extension hook.
    }

    protected void onPrepareLobbyPlayer(Player player) {
        // Optional extension hook.
    }

    protected void onGameStarted(GameMap activeMap) {
        // Optional extension hook.
    }

    protected void onGameSecondElapsed() {
        // Optional extension hook.
    }

    protected void broadcastCountdownMessage(int seconds) {
        int safeSeconds = Math.max(0, seconds);
        ChatColor numberColor;
        if (safeSeconds <= 5) {
            numberColor = ChatColor.RED;
        } else if (safeSeconds <= 10) {
            numberColor = ChatColor.GOLD;
        } else if (safeSeconds <= 15) {
            numberColor = ChatColor.GREEN;
        } else {
            numberColor = ChatColor.YELLOW;
        }
        String unit = safeSeconds == 1 ? "second" : "seconds";
        broadcast(ChatColor.YELLOW + "The game starts in "
                + numberColor + safeSeconds
                + ChatColor.YELLOW + " " + unit + "!");
    }

    private boolean isCountdownCueSecond(int seconds) {
        return seconds == 15 || seconds == 10 || (seconds >= 1 && seconds <= 5);
    }

    private void playCountdownTickSoundToAllPlayers() {
        for (UUID uuid : players.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.playSound(player.getLocation(), COUNTDOWN_TICK_SOUND, 1.0f, 1.0f);
        }
    }

    public String getNotEnoughPlayersMessage() {
        return ChatColor.RED + "We don't have enough players! Start cancelled.";
    }

    private int getCountdownRequiredPlayers() {
        return countdownIgnoresMinPlayers ? FORCE_START_MIN_PLAYERS : minPlayers;
    }

    private void cancelCountdownForInsufficientPlayers(boolean broadcastMessage) {
        if (state != GameState.STARTING) {
            return;
        }
        state = GameState.WAITING;
        publishStateToCore();
        cleanupPregameWorldState();
        if (broadcastMessage) {
            broadcast(getNotEnoughPlayersMessage());
        }
        countdownIgnoresMinPlayers = false;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void publishStateToCore() {
        if (plugin == null) {
            return;
        }
        plugin.setCurrentGameState(state);
    }

    protected boolean onGameTimerTick(int remainingSeconds) {
        return false;
    }

    protected void onAlivePlayerQuitInGame(Player player, GamePlayer gamePlayer) {
        // Optional extension hook.
    }

    protected void onGameEnding() {
        // Optional extension hook.
    }

    protected void appendPostGameSummaryLines(Player player, GamePlayer gamePlayer, List<String> lines) {
        // Optional extension hook.
    }

    protected GamePlayer createGamePlayer(UUID uuid) {
        return new GamePlayer(uuid);
    }

    protected void resetGamePlayers() {
        for (GamePlayer gamePlayer : players.values()) {
            resetGamePlayer(gamePlayer);
        }
    }

    protected void resetGamePlayer(GamePlayer gamePlayer) {
        if (gamePlayer == null) {
            return;
        }
        gamePlayer.setAlive(true);
        gamePlayer.resetKills();
    }

    protected int getAlivePlayers() {
        int alive = 0;
        for (GamePlayer gp : players.values()) {
            if (gp.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    protected void applyMapTime(GameMap map) {
        if (map == null) {
            return;
        }
        long time = map.isNightTime() ? 18000L : 1000L;
        Set<World> worlds = new HashSet<>();
        for (Location location : map.getSpawnPoints()) {
            if (location != null && location.getWorld() != null) {
                worlds.add(location.getWorld());
            }
        }
        for (Location location : map.getDropItemSpawns()) {
            if (location != null && location.getWorld() != null) {
                worlds.add(location.getWorld());
            }
        }
        for (World world : worlds) {
            world.setTime(time);
        }
    }

    protected void trackRoundReward(UUID uuid, String label, int amount) {
        if (uuid == null || amount <= 0) {
            return;
        }
        String safeLabel = label == null ? "" : label.trim();
        if (safeLabel.isEmpty()) {
            safeLabel = "Reward";
        }
        LinkedHashMap<String, Integer> rewards = roundRewardSummary.computeIfAbsent(uuid, key -> new LinkedHashMap<>());
        int next = Math.max(0, rewards.getOrDefault(safeLabel, 0) + amount);
        rewards.put(safeLabel, next);
    }

    protected Map<String, Integer> getRoundRewardSummary(UUID uuid) {
        if (uuid == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Integer> rewards = roundRewardSummary.get(uuid);
        if (rewards == null || rewards.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(rewards);
    }

    protected int getRoundRewardTotal(UUID uuid) {
        int total = 0;
        for (int amount : getRoundRewardSummary(uuid).values()) {
            total += Math.max(0, amount);
        }
        return Math.max(0, total);
    }

    protected void clearRoundRewardSummary() {
        roundRewardSummary.clear();
        roundMysteryDustRewards.clear();
    }

    private void awardPostGameMysteryDustRewards() {
        CoreApi coreApi = plugin == null ? null : plugin.getCoreApi();
        if (coreApi == null) {
            return;
        }
        roundMysteryDustRewards.clear();
        for (UUID uuid : new ArrayList<>(players.keySet())) {
            if (uuid == null) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() >= MYSTERY_DUST_REWARD_CHANCE) {
                continue;
            }
            Profile profile = coreApi.getProfile(uuid);
            if (profile == null) {
                continue;
            }
            int amount = calculateMysteryDustReward(profile);
            if (amount <= 0) {
                continue;
            }
            coreApi.setMysteryDust(uuid, profile.getMysteryDust() + amount);
            roundMysteryDustRewards.put(uuid, amount);
        }
    }

    private int calculateMysteryDustReward(Profile profile) {
        Rank rank = profile == null || profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (rank == Rank.VIP) {
            return 5;
        }
        if (rank == Rank.VIP_PLUS) {
            return 10;
        }
        if (rank == Rank.MVP) {
            return 15;
        }
        if (rank == Rank.MVP_PLUS) {
            return 20;
        }
        if (rank.isAtLeast(Rank.MVP_PLUS_PLUS)) {
            return calculateMvpPlusPlusMysteryDustReward(profile);
        }
        return ThreadLocalRandom.current().nextInt(
                DEFAULT_MIN_MYSTERY_DUST_REWARD,
                DEFAULT_MAX_MYSTERY_DUST_REWARD + 1
        );
    }

    private int calculateMvpPlusPlusMysteryDustReward(Profile profile) {
        if (profile == null || !profile.hasActiveSubscription()) {
            return 25;
        }
        long remainingMillis = Math.max(0L, profile.getSubscriptionExpiresAt() - System.currentTimeMillis());
        long remainingDays = (remainingMillis + MILLIS_PER_DAY - 1L) / MILLIS_PER_DAY;
        if (remainingDays >= 365L) {
            return 40;
        }
        if (remainingDays >= 180L) {
            return 35;
        }
        if (remainingDays >= 90L) {
            return 30;
        }
        return 25;
    }

    private void appendPostGameMysteryDustRewardLine(GamePlayer gamePlayer, List<String> lines) {
        if (gamePlayer == null || lines == null) {
            return;
        }
        int amount = Math.max(0, roundMysteryDustRewards.getOrDefault(gamePlayer.getUuid(), 0));
        if (amount <= 0) {
            return;
        }
        lines.add(ChatColor.GRAY + "You earned " + ChatColor.AQUA + amount
                + ChatColor.GRAY + " Mystery Dust!");
    }

    protected CorePlugin getPlugin() {
        return plugin;
    }

    protected Map<UUID, GamePlayer> getPlayers() {
        return players;
    }

    protected MapManager getMapManager() {
        return mapManager;
    }

    protected TitleService getTitleService() {
        return titleService;
    }

    protected int getGameRemaining() {
        return gameRemaining;
    }

    protected int getCountdownRemaining() {
        return countdownRemaining;
    }

    protected String formatTime(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int minutes = seconds / 60;
        int secs = seconds % 60;
        return String.format("%d:%02d", minutes, secs);
    }
}
