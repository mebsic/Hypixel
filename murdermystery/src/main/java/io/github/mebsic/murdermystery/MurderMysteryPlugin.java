package io.github.mebsic.murdermystery;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.server.MapConfigResolver;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.service.HubContext;
import io.github.mebsic.core.store.MapConfigStore;
import io.github.mebsic.core.store.RoleChanceStore;
import io.github.mebsic.game.manager.GameManager;
import io.github.mebsic.game.listener.SpectatorListener;
import io.github.mebsic.game.listener.TablistListener;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.service.TablistService;
import io.github.mebsic.game.service.BossBarService;
import io.github.mebsic.game.service.QueueService;
import io.github.mebsic.game.command.StartCommand;
import io.github.mebsic.hub.listener.HubCosmeticsListener;
import io.github.mebsic.core.listener.ImageListener;
import io.github.mebsic.core.listener.ItemFrameListener;
import io.github.mebsic.core.listener.HubLeaderboardListener;
import io.github.mebsic.core.listener.HubNpcListener;
import io.github.mebsic.core.listener.HubParkourListener;
import io.github.mebsic.core.listener.HubListener;
import io.github.mebsic.hub.menu.KnifeMenu;
import io.github.mebsic.hub.menu.KnifeSkinsMenu;
import io.github.mebsic.hub.menu.MurderMysteryMenu;
import io.github.mebsic.hub.service.KnifeMenuStateService;
import io.github.mebsic.hub.service.ServerRegistryService;
import io.github.mebsic.hub.service.HubScoreboardService;
import io.github.mebsic.hub.util.LocationUtil;
import io.github.mebsic.murdermystery.command.TokenCommand;
import io.github.mebsic.murdermystery.command.ToggleHintsCommand;
import io.github.mebsic.murdermystery.command.WhoIsRoleCommand;
import io.github.mebsic.murdermystery.command.GetRoleCommand;
import io.github.mebsic.murdermystery.listener.MurderMysteryListener;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.murdermystery.service.ActionBarService;
import io.github.mebsic.murdermystery.manager.MurderMysteryGameManager;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class MurderMysteryPlugin extends JavaPlugin implements HubContext {
    private static final double DEFAULT_MURDERER_CHANCE = 1.0;
    private static final double DEFAULT_DETECTIVE_CHANCE = 1.0;
    private static final long HUB_SCOREBOARD_INITIAL_DELAY_TICKS = 1L;
    private static final long HUB_SCOREBOARD_REFRESH_TICKS = 20L;
    private static final long SCOREBOARD_TITLE_ANIMATION_TICKS = 2L;
    private static final long HUB_SCOREBOARD_POST_JOIN_REFRESH_TICKS = 10L;
    private static final long HUB_SPAWN_STARTUP_REFRESH_DELAY_TICKS = 20L;
    private static final int PARKOUR_PROFILE_INIT_MAX_ATTEMPTS = 20;
    private static final long PARKOUR_PROFILE_INIT_RETRY_TICKS = 10L;
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    private static final String CITIZENS_PLUGIN_NAME = "Citizens";

    private CoreApi coreApi;
    private CorePlugin corePlugin;
    private ServerType serverType;
    private RoleChanceStore roleChanceStore;
    private final Set<UUID> pendingRoleChanceSeeds = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private final AtomicBoolean roleChanceSeedFlushInProgress = new AtomicBoolean(false);

    private MurderMysteryGameManager gameManager;
    private QueueService queueService;
    private ActionBarService actionBarService;
    private BossBarService bossBarService;
    private BukkitTask tablistTask;
    private io.github.mebsic.game.service.ServerRegistryService registryService;

    private HubScoreboardService hubScoreboardService;
    private Location hubSpawn;
    private BukkitTask hubSpawnStartupRefreshTask;
    private BukkitTask hubScoreboardTask;
    private BukkitTask hubScoreboardTitleTask;
    private BukkitTask gameScoreboardTitleTask;
    private BukkitTask hubNetworkLevelTask;
    private BukkitTask roleChanceSeedTask;
    private ServerRegistryService hubRegistryService;
    private KnifeMenuStateService knifeMenuStateService;
    private HubCosmeticsListener cosmeticsListener;
    private HubParkourListener hubParkourListener;
    private HubNpcListener hubNpcListener;
    private HubLeaderboardListener hubLeaderboardListener;
    private ImageListener hubImageDisplayListener;
    private ItemFrameListener itemFrameListener;

    @Override
    public void onEnable() {
        Plugin plugin = getServer().getPluginManager().getPlugin("Hypixel");
        if (!(plugin instanceof CorePlugin)) {
            getLogger().severe("Hypixel plugin not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CorePlugin corePlugin = (CorePlugin) plugin;
        this.corePlugin = corePlugin;
        this.coreApi = corePlugin.getCoreApi();
        this.serverType = corePlugin.getServerType();
        this.roleChanceStore = corePlugin.getRoleChanceStore();
        this.corePlugin.ensureServerIdentity();
        registerCommand("token", new TokenCommand(corePlugin));
        try {
            if (serverType != null && serverType.isHub()) {
                setupHub(corePlugin);
                return;
            }
            setupGame(corePlugin);
        } catch (NoClassDefFoundError missingClassError) {
            handleMissingClassOnStartup(missingClassError);
        }
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public QueueService getQueueService() {
        return queueService;
    }

    public CoreApi getCoreApi() {
        return coreApi;
    }

    public ActionBarService getActionBarService() {
        return actionBarService;
    }

    public Location getHubSpawn() {
        if (hubSpawn == null && corePlugin != null) {
            Location refreshed = LocationUtil.deserialize(corePlugin.getConfig().getString("hub.spawn", ""));
            if (refreshed != null) {
                this.hubSpawn = refreshed;
            }
        }
        return hubSpawn;
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
        }
    }

    private void handleMissingClassOnStartup(NoClassDefFoundError error) {
        String className = resolveMissingClassName(error);
        if (!className.isEmpty()) {
            getLogger().severe("Startup failed because class " + className + " is missing.");
        } else {
            getLogger().severe("Startup failed due to a missing class dependency.");
        }
        getLogger().severe("This usually means plugin jars are out of sync during deployment.");
        getLogger().severe("Build matching jars before restarting servers, then deploy them as one release set:");
        getLogger().severe("./gradlew shadowAll");
        getLogger().log(Level.SEVERE, "Disabling MurderMystery due to NoClassDefFoundError.", error);
        getServer().getPluginManager().disablePlugin(this);
    }

    private String resolveMissingClassName(NoClassDefFoundError error) {
        if (error == null) {
            return "";
        }
        String raw = safeText(error.getMessage());
        if (raw.isEmpty()) {
            return "";
        }
        return raw.replace('/', '.');
    }

    @Override
    public void handleHubJoin(org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        queueDefaultRoleChanceSeed(player.getUniqueId());
        ensureParkourStatsInitialized(player.getUniqueId(), 0);
        if (bossBarService != null) {
            bossBarService.show(player);
        }
        if (cosmeticsListener != null && shouldRefreshHubMenuItem(player)) {
            cosmeticsListener.giveMenuItem(player);
        }
        if (knifeMenuStateService != null) {
            knifeMenuStateService.syncPlayer(player);
            getServer().getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    knifeMenuStateService.syncPlayer(player);
                }
            }, 2L);
            getServer().getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    knifeMenuStateService.syncPlayer(player);
                }
            }, 20L);
        }
        if (hubScoreboardService != null) {
            hubScoreboardService.restartTitleAnimation(player);
            hubScoreboardService.update(player);
            // Profile data loads asynchronously, so do a quick follow-up refresh after join.
            getServer().getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    hubScoreboardService.update(player);
                }
            }, HUB_SCOREBOARD_POST_JOIN_REFRESH_TICKS);
        }
    }

    @Override
    public void handleHubQuit(org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        if (bossBarService != null) {
            bossBarService.remove(player);
        }
        if (hubScoreboardService != null) {
            hubScoreboardService.remove(player);
        }
    }

    @Override
    public void onDisable() {
        if (hubRegistryService != null) {
            hubRegistryService.stop();
            hubRegistryService = null;
        }
        if (hubSpawnStartupRefreshTask != null) {
            hubSpawnStartupRefreshTask.cancel();
            hubSpawnStartupRefreshTask = null;
        }
        knifeMenuStateService = null;
        if (hubScoreboardTask != null) {
            hubScoreboardTask.cancel();
            hubScoreboardTask = null;
        }
        if (hubScoreboardTitleTask != null) {
            hubScoreboardTitleTask.cancel();
            hubScoreboardTitleTask = null;
        }
        if (gameScoreboardTitleTask != null) {
            gameScoreboardTitleTask.cancel();
            gameScoreboardTitleTask = null;
        }
        if (hubNetworkLevelTask != null) {
            hubNetworkLevelTask.cancel();
            hubNetworkLevelTask = null;
        }
        if (roleChanceSeedTask != null) {
            roleChanceSeedTask.cancel();
            roleChanceSeedTask = null;
        }
        if (hubNpcListener != null) {
            hubNpcListener.shutdown();
            hubNpcListener = null;
        }
        if (hubLeaderboardListener != null) {
            hubLeaderboardListener.shutdown();
            hubLeaderboardListener = null;
        }
        if (hubImageDisplayListener != null) {
            hubImageDisplayListener.shutdown();
            hubImageDisplayListener = null;
        }
        itemFrameListener = null;
        if (hubParkourListener != null) {
            hubParkourListener.shutdown();
            hubParkourListener = null;
        }
        if (corePlugin != null) {
            corePlugin.setHubParkourCommandHandler(null);
        }
        if (registryService != null) {
            registryService.stop();
            registryService = null;
        }
        if (bossBarService != null) {
            bossBarService.stop();
            bossBarService = null;
        }
        if (tablistTask != null) {
            tablistTask.cancel();
            tablistTask = null;
        }
        if (queueService != null) {
            queueService.stop();
            queueService = null;
        }
        if (gameManager != null) {
            gameManager.cleanupTransientRoundEntitiesForShutdown();
            gameManager.stopGame();
            gameManager = null;
        }
        if (actionBarService != null) {
            actionBarService.stop();
            actionBarService = null;
        }
    }

    private void setupGame(CorePlugin corePlugin) {
        this.bossBarService = new BossBarService(
                this,
                corePlugin,
                () -> gameManager == null ? GameState.WAITING : gameManager.getState()
        );
        this.gameManager = new MurderMysteryGameManager(corePlugin, bossBarService);
        this.queueService = new QueueService(this, gameManager);
        this.queueService.start();
        getServer().getPluginManager().registerEvents(new TablistListener(this, gameManager.getTablistService()), this);
        this.tablistTask = getServer().getScheduler().runTaskTimer(this, () -> gameManager.getTablistService().updateAll(), 20L, 20L);
        this.gameScoreboardTitleTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (gameManager != null) {
                gameManager.updateAnimatedScoreboardTitles();
            }
        }, HUB_SCOREBOARD_INITIAL_DELAY_TICKS, SCOREBOARD_TITLE_ANIMATION_TICKS);
        this.bossBarService.start();
        this.registryService = new io.github.mebsic.game.service.ServerRegistryService(this, corePlugin.getConfig(), gameManager);
        this.registryService.start();
        getServer().getPluginManager().registerEvents(new MurderMysteryListener(this, gameManager, queueService), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(corePlugin, gameManager), this);
        this.itemFrameListener = new ItemFrameListener(serverType, null);
        getServer().getPluginManager().registerEvents(itemFrameListener, this);
        this.actionBarService = new ActionBarService(
                this,
                gameManager,
                corePlugin,
                roleChanceStore,
                DEFAULT_MURDERER_CHANCE,
                DEFAULT_DETECTIVE_CHANCE
        );
        this.gameManager.setActionBarService(actionBarService);
        this.actionBarService.start();
        getServer().getOnlinePlayers().forEach(actionBarService::handlePlayerJoin);
        registerCommand("start", new StartCommand(gameManager, coreApi));
        registerCommand("togglehints", new ToggleHintsCommand(corePlugin, actionBarService));
        registerCommand("whoismurderer", new WhoIsRoleCommand(corePlugin, gameManager, WhoIsRoleCommand.QueryType.MURDERER));
        registerCommand("whoisdetective", new WhoIsRoleCommand(corePlugin, gameManager, WhoIsRoleCommand.QueryType.DETECTIVE));
        registerCommand("getrole", new GetRoleCommand(corePlugin, gameManager));
    }

    private void setupHub(CorePlugin corePlugin) {
        String serverName = corePlugin.getConfig().getString("server.id", "lobby1A");
        this.hubScoreboardService = new HubScoreboardService(coreApi, serverType, serverName);
        this.bossBarService = new BossBarService(this, corePlugin);
        syncHubMapConfigFromRuntimeWorld();
        MapConfigResolver.apply(corePlugin, corePlugin.getConfig(), corePlugin.getMongoManager());
        this.hubSpawn = LocationUtil.deserialize(corePlugin.getConfig().getString("hub.spawn", ""));
        startHubSpawnStartupRefresh();
        this.knifeMenuStateService = new KnifeMenuStateService(coreApi, corePlugin.getMongoManager());
        this.knifeMenuStateService.initialize(getServer().getOnlinePlayers());
        KnifeSkinsMenu knifeSkinsMenu = new KnifeSkinsMenu(coreApi, loadKnifeCosts(), knifeMenuStateService);
        KnifeMenu knifeMenu = new KnifeMenu(coreApi, knifeSkinsMenu);
        this.cosmeticsListener = new HubCosmeticsListener(new MurderMysteryMenu(coreApi, knifeMenu), coreApi);
        this.hubParkourListener = new HubParkourListener(this, corePlugin, serverType);
        if (!isCitizensReady()) {
            getLogger().warning("Citizens was not found; hub Profile/Click to Play NPCs are disabled.");
            this.hubNpcListener = null;
        } else {
            try {
                this.hubNpcListener = new HubNpcListener(this, corePlugin, serverType);
            } catch (Throwable throwable) {
                this.hubNpcListener = null;
                getLogger().log(
                        Level.WARNING,
                        "Citizens was detected, but NPC initialization failed. "
                                + "Hub Profile/Click to Play NPCs are disabled for this startup.",
                        throwable
                );
            }
        }
        this.hubLeaderboardListener = new HubLeaderboardListener(this, corePlugin, serverType);
        this.hubImageDisplayListener = new ImageListener(this, corePlugin, serverType);
        this.itemFrameListener = new ItemFrameListener(serverType, hubImageDisplayListener);
        corePlugin.setHubParkourCommandHandler(hubParkourListener);
        TablistService tablistService = new TablistService(coreApi, serverType);
        getServer().getPluginManager().registerEvents(new HubListener(this), this);
        getServer().getPluginManager().registerEvents(new TablistListener(this, tablistService), this);
        getServer().getPluginManager().registerEvents(cosmeticsListener, this);
        getServer().getPluginManager().registerEvents(hubParkourListener, this);
        if (hubNpcListener != null) {
            getServer().getPluginManager().registerEvents(hubNpcListener, this);
        }
        getServer().getPluginManager().registerEvents(hubLeaderboardListener, this);
        getServer().getPluginManager().registerEvents(hubImageDisplayListener, this);
        getServer().getPluginManager().registerEvents(itemFrameListener, this);
        subscribeToHubMapConfigUpdates();
        this.tablistTask = getServer().getScheduler().runTaskTimer(this, tablistService::updateAll, 20L, 20L);
        this.hubScoreboardTask = getServer().getScheduler().runTaskTimer(this,
                () -> hubScoreboardService.updateAll(getServer().getOnlinePlayers()),
                HUB_SCOREBOARD_INITIAL_DELAY_TICKS,
                HUB_SCOREBOARD_REFRESH_TICKS);
        this.hubScoreboardTitleTask = getServer().getScheduler().runTaskTimer(this,
                () -> hubScoreboardService.updateAnimatedTitle(),
                HUB_SCOREBOARD_INITIAL_DELAY_TICKS,
                SCOREBOARD_TITLE_ANIMATION_TICKS);
        this.hubNetworkLevelTask = getServer().getScheduler().runTaskTimer(this,
                this::updateNetworkLevels,
                20L,
                40L);
        if (roleChanceStore != null) {
            this.roleChanceSeedTask = getServer().getScheduler().runTaskTimer(this,
                    this::flushRoleChanceSeedsAsync,
                    20L,
                    20L);
        }
        this.hubRegistryService = new ServerRegistryService(this, corePlugin.getConfig());
        this.hubRegistryService.start();
        this.bossBarService.start();
        getServer().getOnlinePlayers().forEach(player -> ensureParkourStatsInitialized(player.getUniqueId(), 0));
    }

    private boolean isCitizensReady() {
        if (getServer() == null || getServer().getPluginManager() == null) {
            return false;
        }
        Plugin citizensPlugin = getServer().getPluginManager().getPlugin(CITIZENS_PLUGIN_NAME);
        return citizensPlugin != null && citizensPlugin.isEnabled();
    }

    private void subscribeToHubMapConfigUpdates() {
        if (corePlugin == null || serverType == null || !serverType.isHub()) {
            return;
        }
        if (corePlugin.getPubSubService() == null) {
            return;
        }
        corePlugin.getPubSubService().subscribe(MAP_CONFIG_UPDATE_CHANNEL, this::handleHubMapConfigUpdateMessage);
    }

    private void handleHubMapConfigUpdateMessage(String message) {
        String updatedKey = parseUpdatedGameKey(message);
        if (updatedKey.isEmpty()) {
            return;
        }
        String currentKey = resolveHubGameKey();
        if (!updatedKey.equalsIgnoreCase(currentKey)) {
            return;
        }
        getServer().getScheduler().runTask(this, () -> {
            reloadHubSpawnFromMapConfig();
            if (hubImageDisplayListener != null) {
                hubImageDisplayListener.refreshNow();
            }
        });
    }

    private void reloadHubSpawnFromMapConfig() {
        if (corePlugin == null || serverType == null || !serverType.isHub()) {
            return;
        }
        MapConfigResolver.apply(corePlugin, corePlugin.getConfig(), corePlugin.getMongoManager());
        Location refreshed = LocationUtil.deserialize(corePlugin.getConfig().getString("hub.spawn", ""));
        if (refreshed != null) {
            this.hubSpawn = refreshed;
        }
    }

    private void syncHubMapConfigFromRuntimeWorld() {
        if (corePlugin == null || serverType == null || !serverType.isHub()) {
            return;
        }
        if (corePlugin.getMongoManager() == null) {
            return;
        }
        String runtimeWorldDirectory = resolveRuntimeHubWorldDirectory();
        if (runtimeWorldDirectory.isEmpty()) {
            return;
        }
        String gameKey = resolveHubGameKey();
        try {
            MapConfigStore store = new MapConfigStore(corePlugin.getMongoManager());
            boolean changed = store.setActiveMap(gameKey, runtimeWorldDirectory);
            if (changed) {
                publishMapConfigUpdate(gameKey);
                getLogger().info("Set active hub map from runtime world \"" + runtimeWorldDirectory
                        + "\" for key \"" + gameKey + "\".");
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to sync hub map config from runtime world: " + ex.getMessage());
        }
    }

    private String resolveRuntimeHubWorldDirectory() {
        String marker = readMapNameFromWorldMarker();
        if (!marker.isEmpty()) {
            return marker;
        }
        for (World world : getServer().getWorlds()) {
            if (world == null || world.getName() == null) {
                continue;
            }
            String worldName = safeText(world.getName());
            if (worldName.isEmpty() || isPlaceholderMapName(worldName)) {
                continue;
            }
            if (MapConfigStore.isHubMapName(worldName)) {
                return worldName;
            }
        }
        for (World world : getServer().getWorlds()) {
            if (world == null || world.getName() == null) {
                continue;
            }
            String worldName = safeText(world.getName());
            if (worldName.isEmpty() || isPlaceholderMapName(worldName)) {
                continue;
            }
            return worldName;
        }
        return "";
    }

    private String readMapNameFromWorldMarker() {
        for (World world : getServer().getWorlds()) {
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
                // Best-effort marker read only.
            }
        }
        return "";
    }

    private void publishMapConfigUpdate(String gameKey) {
        if (corePlugin == null || corePlugin.getPubSubService() == null) {
            return;
        }
        String resolvedGameKey = MapConfigStore.normalizeGameKey(gameKey);
        if (resolvedGameKey.isEmpty()) {
            resolvedGameKey = MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
        }
        try {
            corePlugin.getPubSubService().publish(MAP_CONFIG_UPDATE_CHANNEL, MAP_CONFIG_UPDATE_PREFIX + resolvedGameKey);
        } catch (Exception ignored) {
            // Mongo save already succeeded; Redis fan-out is best effort.
        }
    }

    private void startHubSpawnStartupRefresh() {
        if (corePlugin == null || serverType == null || !serverType.isHub()) {
            return;
        }
        if (hubSpawnStartupRefreshTask != null) {
            hubSpawnStartupRefreshTask.cancel();
            hubSpawnStartupRefreshTask = null;
        }
        hubSpawnStartupRefreshTask = getServer().getScheduler().runTaskLater(
                this,
                () -> {
                    hubSpawnStartupRefreshTask = null;
                    reloadHubSpawnFromMapConfig();
                },
                HUB_SPAWN_STARTUP_REFRESH_DELAY_TICKS
        );
    }

    private String resolveHubGameKey() {
        if (corePlugin == null) {
            return MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
        }
        String normalized = MapConfigStore.normalizeGameKey(corePlugin.getConfig().getString("server.group", ""));
        return normalized.isEmpty() ? MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY : normalized;
    }

    private String parseUpdatedGameKey(String message) {
        String raw = message == null ? "" : message.trim();
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

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
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

    private java.util.Map<String, Integer> loadKnifeCosts() {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        if (coreApi != null) {
            for (io.github.mebsic.core.model.KnifeSkinDefinition skin : coreApi.getKnifeSkins().values()) {
                map.put(skin.getId().toLowerCase(), skin.getCost());
            }
        }
        return map;
    }

    private void updateNetworkLevels() {
        if (coreApi == null) {
            return;
        }
        getServer().getOnlinePlayers().forEach(player -> {
            int level = coreApi.getNetworkLevel(player.getUniqueId());
            long exp = coreApi.getHypixelExperience(player.getUniqueId());
            player.setLevel(Math.max(0, level));
            player.setExp(io.github.mebsic.core.util.HypixelExperienceUtil.getProgressToNext(exp));
            if (cosmeticsListener != null && shouldRefreshHubMenuItem(player)) {
                cosmeticsListener.giveMenuItem(player);
            }
        });
    }

    private boolean shouldRefreshHubMenuItem(org.bukkit.entity.Player player) {
        if (player == null) {
            return false;
        }
        return corePlugin == null || !corePlugin.isBuildModeActive(player.getUniqueId());
    }

    private void queueDefaultRoleChanceSeed(UUID uuid) {
        if (uuid == null || roleChanceStore == null) {
            return;
        }
        pendingRoleChanceSeeds.add(uuid);
    }

    private void flushRoleChanceSeedsAsync() {
        if (roleChanceStore == null || pendingRoleChanceSeeds.isEmpty()) {
            return;
        }
        if (!roleChanceSeedFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        final Set<UUID> batch = new HashSet<>(pendingRoleChanceSeeds);
        pendingRoleChanceSeeds.removeAll(batch);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                roleChanceStore.ensureDefaults(batch, DEFAULT_MURDERER_CHANCE, DEFAULT_DETECTIVE_CHANCE);
            } catch (Exception ignored) {
                pendingRoleChanceSeeds.addAll(batch);
            } finally {
                roleChanceSeedFlushInProgress.set(false);
            }
        });
    }

    private void ensureParkourStatsInitialized(UUID uuid, int attempt) {
        if (uuid == null || serverType == null || !serverType.isHub()) {
            return;
        }
        Profile profile = coreApi == null ? null : coreApi.getProfile(uuid);
        if (profile != null) {
            if (initializeParkourCounters(profile) && corePlugin != null) {
                corePlugin.saveProfile(profile);
            }
            return;
        }
        if (attempt >= PARKOUR_PROFILE_INIT_MAX_ATTEMPTS) {
            return;
        }
        getServer().getScheduler().runTaskLater(
                this,
                () -> ensureParkourStatsInitialized(uuid, attempt + 1),
                PARKOUR_PROFILE_INIT_RETRY_TICKS
        );
    }

    private boolean initializeParkourCounters(Profile profile) {
        if (profile == null || profile.getStats() == null) {
            return false;
        }
        Stats stats = profile.getStats();
        Map<String, Integer> counters = stats.getCustomCounters();
        String typeKey = serverType == null ? ServerType.UNKNOWN.name().toLowerCase(Locale.ROOT) : serverType.name().toLowerCase(Locale.ROOT);
        boolean changed = false;
        changed |= ensureCounterExists(stats, counters, "parkour.best_ms." + typeKey);
        changed |= ensureCounterExists(stats, counters, "parkour.last_ms." + typeKey);
        changed |= ensureCounterExists(stats, counters, "parkour.completions." + typeKey);
        // Persist profile NPC time-stat placeholders on first join/load.
        changed |= ensureCounterExists(stats, counters, MongoManager.MURDER_MYSTERY_QUICKEST_DETECTIVE_WIN_SECONDS_KEY);
        changed |= ensureCounterExists(stats, counters, MongoManager.MURDER_MYSTERY_QUICKEST_MURDERER_WIN_SECONDS_KEY);
        return changed;
    }

    private boolean ensureCounterExists(Stats stats, Map<String, Integer> counters, String key) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return false;
        }
        if (counters != null && counters.containsKey(key)) {
            return false;
        }
        stats.addCustomCounter(key, 1);
        stats.addCustomCounter(key, -1);
        return true;
    }
}
