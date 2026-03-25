package io.github.mebsic.murdermystery;

import io.github.mebsic.core.CorePlugin;
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
import io.github.mebsic.murdermystery.service.TipService;
import io.github.mebsic.murdermystery.manager.MurderMysteryGameManager;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MurderMysteryPlugin extends JavaPlugin implements HubContext {
    private static final double DEFAULT_MURDERER_CHANCE = 1.0;
    private static final double DEFAULT_DETECTIVE_CHANCE = 1.0;
    private static final long HUB_SCOREBOARD_INITIAL_DELAY_TICKS = 1L;
    private static final long HUB_SCOREBOARD_REFRESH_TICKS = 20L;
    private static final long HUB_SCOREBOARD_POST_JOIN_REFRESH_TICKS = 10L;
    private static final int PARKOUR_PROFILE_INIT_MAX_ATTEMPTS = 20;
    private static final long PARKOUR_PROFILE_INIT_RETRY_TICKS = 10L;
    private static final String QUICKEST_DETECTIVE_WIN_SECONDS_KEY = "murdermystery.quickestDetectiveWinSeconds";
    private static final String QUICKEST_MURDERER_WIN_SECONDS_KEY = "murdermystery.quickestMurdererWinSeconds";
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
    private TipService tipService;
    private BossBarService bossBarService;
    private BukkitTask tablistTask;
    private io.github.mebsic.game.service.ServerRegistryService registryService;

    private HubScoreboardService hubScoreboardService;
    private Location hubSpawn;
    private BukkitTask hubScoreboardTask;
    private BukkitTask hubNetworkLevelTask;
    private BukkitTask roleChanceSeedTask;
    private ServerRegistryService hubRegistryService;
    private KnifeMenuStateService knifeMenuStateService;
    private HubCosmeticsListener cosmeticsListener;
    private HubParkourListener hubParkourListener;
    private HubNpcListener hubNpcListener;
    private HubLeaderboardListener hubLeaderboardListener;

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
        if (serverType != null && serverType.isHub()) {
            setupHub(corePlugin);
            return;
        }
        setupGame(corePlugin);
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

    public TipService getTipService() {
        return tipService;
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
        if (cosmeticsListener != null) {
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
        knifeMenuStateService = null;
        if (hubScoreboardTask != null) {
            hubScoreboardTask.cancel();
            hubScoreboardTask = null;
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
            gameManager.stopGame();
            gameManager = null;
        }
        if (tipService != null) {
            tipService.stop();
            tipService = null;
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
        this.bossBarService.start();
        this.registryService = new io.github.mebsic.game.service.ServerRegistryService(this, corePlugin.getConfig(), gameManager);
        this.registryService.start();
        getServer().getPluginManager().registerEvents(new MurderMysteryListener(this, gameManager, queueService), this);
        getServer().getPluginManager().registerEvents(new SpectatorListener(corePlugin, gameManager), this);
        this.tipService = new TipService(
                this,
                gameManager,
                corePlugin,
                roleChanceStore,
                DEFAULT_MURDERER_CHANCE,
                DEFAULT_DETECTIVE_CHANCE
        );
        this.gameManager.setTipService(tipService);
        this.tipService.start();
        getServer().getOnlinePlayers().forEach(tipService::handlePlayerJoin);
        registerCommand("start", new StartCommand(gameManager, coreApi));
        registerCommand("togglehints", new ToggleHintsCommand(corePlugin, tipService));
        registerCommand("whoismurderer", new WhoIsRoleCommand(corePlugin, gameManager, WhoIsRoleCommand.QueryType.MURDERER));
        registerCommand("whoisdetective", new WhoIsRoleCommand(corePlugin, gameManager, WhoIsRoleCommand.QueryType.DETECTIVE));
        registerCommand("getrole", new GetRoleCommand(corePlugin, gameManager));
    }

    private void setupHub(CorePlugin corePlugin) {
        String serverName = corePlugin.getConfig().getString("server.id", "lobby1A");
        this.hubScoreboardService = new HubScoreboardService(coreApi, serverType, serverName);
        this.bossBarService = new BossBarService(this, corePlugin);
        this.hubSpawn = LocationUtil.deserialize(corePlugin.getConfig().getString("hub.spawn", ""));
        this.knifeMenuStateService = new KnifeMenuStateService(coreApi, corePlugin.getMongoManager());
        this.knifeMenuStateService.initialize(getServer().getOnlinePlayers());
        KnifeSkinsMenu knifeSkinsMenu = new KnifeSkinsMenu(coreApi, loadKnifeCosts(), knifeMenuStateService);
        KnifeMenu knifeMenu = new KnifeMenu(coreApi, knifeSkinsMenu);
        this.cosmeticsListener = new HubCosmeticsListener(new MurderMysteryMenu(coreApi, knifeMenu), coreApi);
        this.hubParkourListener = new HubParkourListener(this, corePlugin, serverType);
        if (getServer().getPluginManager().getPlugin(CITIZENS_PLUGIN_NAME) == null) {
            getLogger().warning("Citizens was not found; hub Profile/Click to Play NPCs are disabled.");
            this.hubNpcListener = null;
        } else {
            this.hubNpcListener = new HubNpcListener(this, corePlugin, serverType);
        }
        this.hubLeaderboardListener = new HubLeaderboardListener(this, corePlugin, serverType);
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
        subscribeToHubMapConfigUpdates();
        this.tablistTask = getServer().getScheduler().runTaskTimer(this, tablistService::updateAll, 20L, 20L);
        this.hubScoreboardTask = getServer().getScheduler().runTaskTimer(this,
                () -> hubScoreboardService.updateAll(getServer().getOnlinePlayers()),
                HUB_SCOREBOARD_INITIAL_DELAY_TICKS,
                HUB_SCOREBOARD_REFRESH_TICKS);
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
        getServer().getScheduler().runTask(this, this::reloadHubSpawnFromMapConfig);
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

    private String resolveHubGameKey() {
        if (corePlugin == null) {
            return MapConfigStore.DEFAULT_GAME_KEY;
        }
        String normalized = MapConfigStore.normalizeGameKey(corePlugin.getConfig().getString("server.group", ""));
        return normalized.isEmpty() ? MapConfigStore.DEFAULT_GAME_KEY : normalized;
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
            if (cosmeticsListener != null) {
                cosmeticsListener.giveMenuItem(player);
            }
        });
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
        changed |= ensureCounterExists(stats, counters, QUICKEST_DETECTIVE_WIN_SECONDS_KEY);
        changed |= ensureCounterExists(stats, counters, QUICKEST_MURDERER_WIN_SECONDS_KEY);
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
