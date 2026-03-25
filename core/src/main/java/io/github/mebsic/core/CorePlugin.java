package io.github.mebsic.core;

import io.github.mebsic.core.command.BanCommand;
import io.github.mebsic.core.command.ClearCommand;
import io.github.mebsic.core.command.FlyCommand;
import io.github.mebsic.core.command.GiveCommand;
import io.github.mebsic.core.command.GamemodeCommand;
import io.github.mebsic.core.command.HelpCommand;
import io.github.mebsic.core.command.KickCommand;
import io.github.mebsic.core.command.MapCommand;
import io.github.mebsic.core.command.MuteCommand;
import io.github.mebsic.core.command.NetworkLevelCommand;
import io.github.mebsic.core.command.ParkourCommand;
import io.github.mebsic.core.command.RankCommand;
import io.github.mebsic.core.command.RankColorCommand;
import io.github.mebsic.core.command.TeleportCommand;
import io.github.mebsic.core.command.UnbanCommand;
import io.github.mebsic.core.command.UnmuteCommand;
import io.github.mebsic.core.command.WhereAmICommand;
import io.github.mebsic.core.listener.ChatFormatListener;
import io.github.mebsic.core.listener.CommandBlockListener;
import io.github.mebsic.core.listener.GameJoinListener;
import io.github.mebsic.core.listener.GameplayRulesListener;
import io.github.mebsic.core.listener.HubItemListener;
import io.github.mebsic.core.listener.InventoryLockListener;
import io.github.mebsic.core.listener.MenuListener;
import io.github.mebsic.core.listener.PunishmentListener;
import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.GameResult;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.service.CosmeticService;
import io.github.mebsic.core.manager.LeaderboardsManager;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.service.ProfileService;
import io.github.mebsic.core.service.ProfileCommandSyncService;
import io.github.mebsic.core.service.PunishmentService;
import io.github.mebsic.core.service.QueueClient;
import io.github.mebsic.core.service.ServerRegistrySnapshot;
import io.github.mebsic.core.service.HubParkourCommandHandler;
import io.github.mebsic.core.store.ProfileStore;
import io.github.mebsic.core.store.PunishmentStore;
import io.github.mebsic.core.store.RoleChanceStore;
import io.github.mebsic.core.store.KnifeSkinStore;
import io.github.mebsic.core.store.MapConfigStore;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.server.DefaultGameTypePlayerCountProvider;
import io.github.mebsic.core.server.ServerIdentityResolver;
import io.github.mebsic.core.server.NetworkConfigResolver;
import io.github.mebsic.core.server.MapConfigResolver;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.server.ServerTypeResolver;
import io.github.mebsic.core.service.PubSubService;
import io.github.mebsic.core.manager.RedisManager;
import io.github.mebsic.core.util.GameRewardUtil;
import io.github.mebsic.core.util.HypixelExperienceUtil;
import io.github.mebsic.core.util.RankColorUtil;
import io.github.mebsic.game.listener.TablistListener;
import io.github.mebsic.game.listener.ReturnToLobbyListener;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.service.TablistService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Sound;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bson.Document;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;

import static com.mongodb.client.model.Filters.eq;

public class CorePlugin extends JavaPlugin implements CoreApi, Listener {
    private static final String FRIENDS_COLLECTION = "friends";
    private static final String FRIEND_VISIBILITY_UPDATE_CHANNEL = "friend_visibility_update";
    private static final long BLOCKED_CACHE_TTL_MILLIS = 5_000L;

    private MongoManager mongo;
    private RedisManager redis;
    private ProfileStore profileStore;
    private PunishmentStore punishmentStore;
    private RoleChanceStore roleChanceStore;
    private ProfileService profileService;
    private CosmeticService cosmetics;
    private LeaderboardsManager leaderboards;
    private PubSubService pubSub;
    private PunishmentService punishments;
    private ProfileCommandSyncService profileCommandSync;
    private ServerType serverType;
    private QueueClient queueClient;
    private ServerRegistrySnapshot registrySnapshot;
    private HubItemListener hubItemListener;
    private HubParkourCommandHandler hubParkourCommandHandler;
    private TablistService buildTablistService;
    private BukkitTask buildTablistTask;
    private BukkitTask profileRefreshTask;
    private volatile GameState currentGameState = GameState.WAITING;
    private final Map<UUID, BlockedCacheEntry> blockedCache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        NetworkConfigResolver.apply(this, getConfig());
        ServerIdentityResolver.apply(this, getConfig());
        setupServices();
        MapConfigResolver.apply(this, getConfig(), mongo);
        if (pubSub != null) {
            pubSub.subscribe(FRIEND_VISIBILITY_UPDATE_CHANNEL, this::handleFriendVisibilityUpdate);
        }
        this.serverType = ServerTypeResolver.resolve(getConfig(), ServerType.MURDER_MYSTERY);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        applyWorldDefaults();
        getServer().getServicesManager().register(CoreApi.class, this, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new GameplayRulesListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryLockListener(this), this);
        getServer().getPluginManager().registerEvents(new PunishmentListener(punishments), this);
        getServer().getPluginManager().registerEvents(new ChatFormatListener(this, this), this);
        getServer().getPluginManager().registerEvents(new CommandBlockListener(serverType), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        if (serverType != null && serverType.isGame()) {
            getServer().getPluginManager().registerEvents(
                    new GameJoinListener(this, this, serverType, new DefaultGameTypePlayerCountProvider()),
                    this
            );
            getServer().getPluginManager().registerEvents(new ReturnToLobbyListener(this), this);
        }
        if (getCommand("ban") != null) {
            getCommand("ban").setExecutor(new BanCommand(this, punishments));
        }
        if (getCommand("mute") != null) {
            getCommand("mute").setExecutor(new MuteCommand(this, punishments));
        }
        if (getCommand("unmute") != null) {
            getCommand("unmute").setExecutor(new UnmuteCommand(this, punishments));
        }
        if (getCommand("unban") != null) {
            getCommand("unban").setExecutor(new UnbanCommand(this, punishments));
        }
        if (getCommand("kick") != null) {
            getCommand("kick").setExecutor(new KickCommand(this, punishments));
        }
        if (getCommand("rank") != null) {
            getCommand("rank").setExecutor(new RankCommand(this));
        }
        if (getCommand("rankcolor") != null) {
            getCommand("rankcolor").setExecutor(new RankColorCommand(this));
        }
        if (getCommand("networklevel") != null) {
            getCommand("networklevel").setExecutor(new NetworkLevelCommand(this));
        }
        if (getCommand("gamemode") != null) {
            getCommand("gamemode").setExecutor(new GamemodeCommand(this));
        }
        if (getCommand("give") != null) {
            getCommand("give").setExecutor(new GiveCommand(this));
        }
        if (getCommand("clear") != null) {
            getCommand("clear").setExecutor(new ClearCommand(this));
        }
        if (getCommand("teleport") != null) {
            getCommand("teleport").setExecutor(new TeleportCommand(this));
        }
        if (getCommand("fly") != null) {
            getCommand("fly").setExecutor(new FlyCommand(this));
        }
        if (getCommand("parkour") != null) {
            getCommand("parkour").setExecutor(new ParkourCommand(this));
        }
        if (getCommand("help") != null) {
            getCommand("help").setExecutor(new HelpCommand());
        }
        if (getCommand("whereami") != null) {
            getCommand("whereami").setExecutor(new WhereAmICommand(this));
        }
        if (getCommand("map") != null) {
            getCommand("map").setExecutor(new MapCommand(this));
        }
        if (serverType != null && serverType.isHub()) {
            String group = getConfig().getString("server.group", "");
            int staleSeconds = getConfig().getInt("registry.staleSeconds", 20);
            long registryDataRefreshTicks = Math.max(1L, getConfig().getLong("menus.registryDataRefreshTicks", 2L));
            this.registrySnapshot = new ServerRegistrySnapshot(this, mongo, group, staleSeconds, registryDataRefreshTicks * 50L);
            if (redis != null) {
                this.queueClient = new QueueClient(redis);
            }
            this.hubItemListener = new HubItemListener(this, queueClient, registrySnapshot);
            getServer().getPluginManager().registerEvents(hubItemListener, this);
        }
        if (serverType != null && serverType.isBuild()) {
            this.buildTablistService = new TablistService(this, serverType);
            this.buildTablistService.setShowRankPrefix(true);
            getServer().getPluginManager().registerEvents(new TablistListener(this, buildTablistService), this);
            this.buildTablistTask = Bukkit.getScheduler().runTaskTimer(this, buildTablistService::updateAll, 20L, 20L);
            Bukkit.getScheduler().runTaskLater(this, buildTablistService::updateAll, 1L);
        }
        if (leaderboards != null) {
            leaderboards.start();
        }
        ensureFriendDocumentsForOnlinePlayers();
        this.profileRefreshTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    if (profileService != null) {
                        profileService.refreshOnlineProfileMeta();
                    }
                },
                20L,
                20L
        );
    }

    private void setupServices() {
        Map<String, KnifeSkinDefinition> knifeSkins = new HashMap<>();
        if (isMongoEnabled()) {
            this.mongo = new MongoManager(getConfig().getString("mongo.uri"), getConfig().getString("mongo.database"));
            ensureCoreCollections();
            ensureMapConfigDefaults();
            knifeSkins = loadKnifeSkins();
            this.profileStore = new ProfileStore(mongo, knifeSkins);
            this.profileStore.applySpectatorDefaultsToAllProfilesOnce();
            this.profileStore.applyMurderMysteryStatsKeysMigrationOnce();
            this.punishmentStore = new PunishmentStore(mongo);
            this.roleChanceStore = new RoleChanceStore(mongo);
        } else {
            this.profileStore = null;
            this.punishmentStore = null;
            this.roleChanceStore = null;
            knifeSkins.put("iron_sword", new KnifeSkinDefinition(
                    "iron_sword",
                    "IRON_SWORD",
                    "",
                    "",
                    0
            ));
        }
        this.cosmetics = new CosmeticService(knifeSkins);
        this.profileService = new ProfileService(this, profileStore, cosmetics);
        this.leaderboards = new LeaderboardsManager(this);
        if (isRedisEnabled()) {
            this.redis = new RedisManager(
                    getConfig().getString("redis.host"),
                    getConfig().getInt("redis.port"),
                    getConfig().getString("redis.password"),
                    getConfig().getInt("redis.database"));
            this.pubSub = new PubSubService(redis);
        }
        this.punishments = new PunishmentService(
                punishmentStore,
                this,
                pubSub,
                getConfig().getString("server.id")
        );
        this.profileCommandSync = new ProfileCommandSyncService(this, pubSub, getConfig().getString("server.id"));
    }

    private void ensureCoreCollections() {
        if (mongo == null) {
            return;
        }
        mongo.ensureCollection("profiles");
        mongo.ensureCollection("leaderboards");
        mongo.ensureCollection("punishments");
        mongo.ensureCollection("murdermystery_role_chances");
        mongo.ensureCollection("knife_skins");
        mongo.ensureCollection("server_registry");
        mongo.ensureCollection("boss_bar_messages");
        mongo.ensureCollection("core_migrations");
        mongo.ensureCollection(MapConfigStore.COLLECTION_NAME);
    }

    private void ensureMapConfigDefaults() {
        if (mongo == null) {
            return;
        }
        MapConfigStore mapConfigs = new MapConfigStore(mongo);
        mapConfigs.ensureDefaults(MapConfigStore.DEFAULT_GAME_KEY);

        String group = MapConfigStore.normalizeGameKey(getConfig().getString("server.group", ""));
        if (!group.isEmpty() && !MapConfigStore.DEFAULT_GAME_KEY.equals(group)) {
            mapConfigs.ensureDefaults(group);
        }
    }

    private Map<String, KnifeSkinDefinition> loadKnifeSkins() {
        Map<String, KnifeSkinDefinition> map = new HashMap<>();
        if (mongo != null) {
            KnifeSkinStore store = new KnifeSkinStore(mongo);
            store.ensureDefault();
            for (KnifeSkinDefinition definition : store.loadAll()) {
                if (definition == null || definition.getId() == null) {
                    continue;
                }
                map.put(definition.getId().toLowerCase(Locale.ROOT), definition);
            }
        }
        map.putIfAbsent("iron_sword", new KnifeSkinDefinition(
                "iron_sword",
                "IRON_SWORD",
                "",
                "",
                0
        ));
        return map;
    }

    public boolean isMongoEnabled() {
        return getConfig().getBoolean("mongo.enabled", false);
    }

    public boolean isRedisEnabled() {
        return getConfig().getBoolean("redis.enabled", false);
    }

    public ProfileStore getProfileStore() {
        return profileStore;
    }

    public RoleChanceStore getRoleChanceStore() {
        return roleChanceStore;
    }

    public PunishmentService getPunishments() {
        return punishments;
    }

    public ProfileCommandSyncService getProfileCommandSyncService() {
        return profileCommandSync;
    }

    public CoreApi getCoreApi() {
        return this;
    }

    public ServerType getServerType() {
        return serverType;
    }

    public HubParkourCommandHandler getHubParkourCommandHandler() {
        return hubParkourCommandHandler;
    }

    public void setHubParkourCommandHandler(HubParkourCommandHandler hubParkourCommandHandler) {
        this.hubParkourCommandHandler = hubParkourCommandHandler;
    }

    public GameState getCurrentGameState() {
        return currentGameState == null ? GameState.WAITING : currentGameState;
    }

    public void setCurrentGameState(GameState state) {
        this.currentGameState = state == null ? GameState.WAITING : state;
    }

    public MongoManager getMongoManager() {
        return mongo;
    }

    public RedisManager getRedisManager() {
        return redis;
    }

    public QueueClient getQueueClient() {
        return queueClient;
    }

    public PubSubService getPubSubService() {
        return pubSub;
    }

    @Override
    public void onDisable() {
        if (buildTablistTask != null) {
            buildTablistTask.cancel();
            buildTablistTask = null;
        }
        buildTablistService = null;
        if (profileRefreshTask != null) {
            profileRefreshTask.cancel();
            profileRefreshTask = null;
        }
        if (leaderboards != null) {
            leaderboards.stop();
        }
        if (hubItemListener != null) {
            hubItemListener.shutdown();
            hubItemListener = null;
        }
        hubParkourCommandHandler = null;
        if (profileService != null) {
            profileService.saveAllSync();
        }
        if (mongo != null) {
            mongo.close();
        }
        if (redis != null) {
            redis.close();
        }
        blockedCache.clear();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        profileService.handleJoin(event.getPlayer());
        ensureFriendDocumentAsync(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        enforceAdventureMode(event.getPlayer().getGameMode(), event.getPlayer());
        applyHubFlightState(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        profileService.handleQuit(event.getPlayer());
        if (event != null && event.getPlayer() != null) {
            blockedCache.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        enforceAdventureMode(event.getNewGameMode(), event.getPlayer());
    }

    private void enforceAdventureMode(GameMode requested, org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        if (serverType != ServerType.MURDER_MYSTERY && serverType != ServerType.MURDER_MYSTERY_HUB) {
            return;
        }
        if (requested != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    private void applyWorldDefaults() {
        boolean night = serverType == ServerType.MURDER_MYSTERY_HUB;
        long defaultTime = night ? 18000L : 1000L;
        boolean keepInventory = resolveGameplayToggle("gameplay.keepInventory", false);
        boolean weatherCycle = resolveGameplayToggle("gameplay.weatherCycle", true);
        boolean vanillaAchievements = resolveGameplayToggle("gameplay.vanillaAchievements", false);
        boolean allowAnimals = resolveGameplayToggle("gameplay.allowAnimals", true);
        boolean allowMonsters = resolveGameplayToggle("gameplay.allowMonsters", true);
        if (serverType == ServerType.MURDER_MYSTERY || serverType == ServerType.MURDER_MYSTERY_HUB) {
            weatherCycle = false;
        }
        Map<String, String> configuredGameRules = resolveConfiguredGameRules();
        for (World world : getServer().getWorlds()) {
            if (world == null) {
                continue;
            }
            if (!weatherCycle) {
                world.setStorm(false);
                world.setThundering(false);
            }
            world.setGameRuleValue("doWeatherCycle", Boolean.toString(weatherCycle));
            world.setGameRuleValue("announceAdvancements", Boolean.toString(vanillaAchievements));
            world.setGameRuleValue("announceAchievements", Boolean.toString(vanillaAchievements));
            world.setTime(defaultTime);
            world.setGameRuleValue("doDaylightCycle", "false");
            world.setGameRuleValue("keepInventory", Boolean.toString(keepInventory));
            world.setSpawnFlags(allowMonsters, allowAnimals);
            applyConfiguredGameRules(world, configuredGameRules);
        }
    }

    private Map<String, String> resolveConfiguredGameRules() {
        Map<String, String> resolved = new LinkedHashMap<>();
        mergeGameRuleSection(resolved, getConfig().getConfigurationSection("gameplay.gamerules.default"));
        ServerType type = serverType == null ? ServerType.UNKNOWN : serverType;
        mergeGameRuleSection(resolved, getConfig().getConfigurationSection("gameplay.gamerules.byServerType." + type.name()));
        if (type.isHub()) {
            mergeGameRuleSection(resolved, getConfig().getConfigurationSection("gameplay.gamerules.byServerType.HUB"));
        } else if (type.isGame()) {
            mergeGameRuleSection(resolved, getConfig().getConfigurationSection("gameplay.gamerules.byServerType.GAME"));
        }
        String serverId = getConfig().getString("server.id", "");
        if (serverId != null) {
            String trimmed = serverId.trim();
            if (!trimmed.isEmpty()) {
                mergeGameRuleSection(resolved, getConfig().getConfigurationSection("gameplay.gamerules.byServerId." + trimmed));
                mergeGameRuleSection(resolved, getConfig().getConfigurationSection("gameplay.gamerules.byServerId." + trimmed.toLowerCase(Locale.ROOT)));
            }
        }
        return resolved;
    }

    private void mergeGameRuleSection(Map<String, String> target, ConfigurationSection section) {
        if (target == null || section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            Object raw = section.get(key);
            if (raw == null) {
                continue;
            }
            String value = String.valueOf(raw).trim();
            if (value.isEmpty()) {
                continue;
            }
            target.put(key, value);
        }
    }

    private void applyConfiguredGameRules(World world, Map<String, String> rules) {
        if (world == null || rules == null || rules.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String rule = entry.getKey().trim();
            String value = entry.getValue().trim();
            if (rule.isEmpty() || value.isEmpty()) {
                continue;
            }
            world.setGameRuleValue(rule, value);
        }
    }

    private boolean resolveGameplayToggle(String path, boolean fallbackDefault) {
        if (path == null || path.trim().isEmpty()) {
            return fallbackDefault;
        }
        boolean enabled = getConfig().getBoolean(path + ".defaultEnabled", fallbackDefault);
        ServerType type = serverType == null ? ServerType.UNKNOWN : serverType;
        enabled = override(enabled, readBoolean(path + ".byServerType." + type.name()));
        if (type.isHub()) {
            enabled = override(enabled, readBoolean(path + ".byServerType.HUB"));
        } else if (type.isGame()) {
            enabled = override(enabled, readBoolean(path + ".byServerType.GAME"));
        }
        String serverId = getConfig().getString("server.id", "");
        if (serverId != null) {
            String trimmed = serverId.trim();
            if (!trimmed.isEmpty()) {
                enabled = override(enabled, readBoolean(path + ".byServerId." + trimmed));
                enabled = override(enabled, readBoolean(path + ".byServerId." + trimmed.toLowerCase(Locale.ROOT)));
            }
        }
        return enabled;
    }

    private boolean override(boolean current, Boolean override) {
        return override == null ? current : override;
    }

    private Boolean readBoolean(String path) {
        if (path == null || path.trim().isEmpty() || !getConfig().isSet(path)) {
            return null;
        }
        Object raw = getConfig().get(path);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw == null) {
            return null;
        }
        String normalized = raw.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    @Override
    public Profile getProfile(UUID uuid) {
        return profileService.getProfile(uuid);
    }

    public void saveProfile(Profile profile) {
        if (profile == null) {
            return;
        }
        profileService.saveProfile(profile);
    }

    @Override
    public void loadProfileAsync(UUID uuid, String name) {
        profileService.loadProfileAsync(uuid, name);
    }

    @Override
    public void recordGameResult(GameResult result) {
        Profile profile = profileService.getProfile(result.getUuid());
        if (profile == null) {
            return;
        }
        int previousLevel = profile.getNetworkLevel();
        profile.getStats().addGame();
        if (result.isWin()) {
            profile.getStats().addWin();
        }
        if (result.getKills() > 0) {
            profile.getStats().addKills(result.getKills());
        }
        addHypixelExperienceInternal(profile, calculateHypixelExperience(result, previousLevel), true, previousLevel);
        profileService.saveProfile(profile);
        if (leaderboards != null) {
            leaderboards.update(result.getUuid(), profile.getStats());
        }
        if (pubSub != null) {
            pubSub.publish("game_result", result.getUuid().toString());
        }
    }

    @Override
    public Rank getRank(UUID uuid) {
        Profile profile = profileService.getProfile(uuid);
        return profile == null ? Rank.DEFAULT : profile.getRank();
    }

    @Override
    public void setRank(UUID uuid, Rank rank) {
        if (uuid == null || rank == null) {
            return;
        }
        boolean grantHubPerks = shouldEnableHubPerks(rank);
        Profile profile = profileService.getProfile(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (profile != null || player != null) {
            profileService.setRank(uuid, rank);
            profile = profileService.getProfile(uuid);
            if (profile != null) {
                if (!canUseMvpPlusPlusPrefixColor(rank)) {
                    profile.setMvpPlusPlusPrefixColor(null);
                }
                if (grantHubPerks) {
                    profile.setFlightEnabled(true);
                }
            }
        }

        String name = profile == null ? null : profile.getName();
        if ((name == null || name.trim().isEmpty()) && player != null) {
            name = player.getName();
        }

        if (isMongoEnabled() && profileStore != null) {
            profileStore.updateRank(uuid, name, rank);
        } else if (profile != null) {
            profileService.saveProfile(profile);
        }

        if (player != null) {
            Profile refreshedProfile = profileService.getProfile(uuid);
            applyHubFlightState(player, refreshedProfile);
            applyHubSpeedState(player, rank);
        }
        refreshBuildTablist();
    }

    public boolean setFlightEnabled(UUID uuid, boolean enabled) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return false;
        }
        profile.setFlightEnabled(enabled);
        profileService.saveProfile(profile);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            applyHubFlightState(player, profile);
        }
        return true;
    }

    public boolean setPlayerVisibilityEnabled(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return false;
        }
        profileService.setPlayerVisibilityEnabled(uuid, enabled);
        Profile profile = profileService.getProfile(uuid);
        Player player = Bukkit.getPlayer(uuid);
        String name = profile == null ? null : profile.getName();
        if ((name == null || name.trim().isEmpty()) && player != null) {
            name = player.getName();
        }
        if (isMongoEnabled() && profileStore != null) {
            profileStore.updatePlayerVisibility(uuid, name, enabled);
            return true;
        }
        if (profile != null) {
            profileService.saveProfile(profile);
            return true;
        }
        return false;
    }

    public void handleProfileLoaded(Profile profile) {
        if (profile == null) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(profile.getUuid());
            if (player == null) {
                return;
            }
            applyHubFlightState(player, profile);
            applyHubSpeedState(player, profile.getRank());
            if (hubItemListener != null) {
                hubItemListener.applyProfileVisibility(profile);
            }
            refreshBuildTablist();
        });
    }

    @Override
    public int getNetworkLevel(UUID uuid) {
        Profile profile = profileService.getProfile(uuid);
        return profile == null ? 0 : profile.getNetworkLevel();
    }

    @Override
    public void setNetworkLevel(UUID uuid, int level) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return;
        }
        int previousLevel = profile.getNetworkLevel();
        profile.setNetworkLevel(level);
        profile.setHypixelExperience(HypixelExperienceUtil.getTotalExpForLevel(level));
        profileService.saveProfile(profile);
        if (level > previousLevel) {
            playLevelUpSound(uuid);
        }
        refreshBuildTablist();
    }

    @Override
    public void setPlusColor(UUID uuid, String colorId) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return;
        }
        profile.setPlusColor(colorId);
        profileService.saveProfile(profile);
        refreshBuildTablist();
    }

    @Override
    public void setMvpPlusPlusPrefixColor(UUID uuid, String colorId) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return;
        }
        Rank rank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (!canUseMvpPlusPlusPrefixColor(rank)) {
            return;
        }
        profile.setMvpPlusPlusPrefixColor(RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(colorId));
        profileService.saveProfile(profile);
        refreshBuildTablist();
    }

    private void refreshBuildTablist() {
        TablistService tablist = buildTablistService;
        if (tablist == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            tablist.updateAll();
            return;
        }
        Bukkit.getScheduler().runTask(this, tablist::updateAll);
    }

    private boolean canUseMvpPlusPlusPrefixColor(Rank rank) {
        return rank == Rank.MVP_PLUS_PLUS || rank == Rank.STAFF || rank == Rank.YOUTUBE;
    }

    @Override
    public long getHypixelExperience(UUID uuid) {
        Profile profile = profileService.getProfile(uuid);
        return profile == null ? 0L : profile.getHypixelExperience();
    }

    @Override
    public void addHypixelExperience(UUID uuid, long amount) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return;
        }
        int previousLevel = profile.getNetworkLevel();
        addHypixelExperienceInternal(profile, amount, true, previousLevel);
        profileService.saveProfile(profile);
    }

    private void addHypixelExperienceInternal(Profile profile, long amount, boolean allowSound, int previousLevel) {
        if (amount <= 0 || profile == null) {
            return;
        }
        long total = profile.getHypixelExperience() + amount;
        profile.setHypixelExperience(total);
        int newLevel = HypixelExperienceUtil.getLevel(total);
        profile.setNetworkLevel(newLevel);
        if (allowSound && newLevel > previousLevel) {
            playLevelUpSound(profile.getUuid());
        }
    }

    private void playLevelUpSound(UUID uuid) {
        if (uuid == null) {
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            player.playSound(player.getLocation(), Sound.LEVEL_UP, 1.0f, 1.0f);
        });
    }

    private long calculateHypixelExperience(GameResult result, int networkLevel) {
        long baseExperience = GameRewardUtil.calculateTotalExperience(result);
        return HypixelExperienceUtil.scaleExperienceGain(baseExperience, networkLevel);
    }

    private void applyHubFlightState(Player player) {
        if (player == null) {
            return;
        }
        applyHubFlightState(player, profileService.getProfile(player.getUniqueId()));
    }

    private void applyHubFlightState(Player player, Profile profile) {
        if (player == null || !isHubServer()) {
            return;
        }
        Rank rank = profile == null ? Rank.DEFAULT : profile.getRank();
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        boolean enabled = profile != null && profile.isFlightEnabled() && rank.isAtLeast(Rank.VIP);
        player.setAllowFlight(enabled);
        if (!enabled) {
            player.setFlying(false);
            return;
        }
        player.setFlying(true);
    }

    private void applyHubSpeedState(Player player, Rank rank) {
        if (player == null || !isHubServer()) {
            return;
        }
        Rank effectiveRank = rank == null ? Rank.DEFAULT : rank;
        player.removePotionEffect(PotionEffectType.SPEED);
        if (effectiveRank.isAtLeast(Rank.MVP_PLUS_PLUS)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
    }

    private boolean shouldEnableHubPerks(Rank rank) {
        return rank == Rank.MVP_PLUS || rank == Rank.MVP_PLUS_PLUS;
    }

    private boolean isHubServer() {
        return serverType != null && serverType.isHub();
    }

    @Override
    public ItemStack createKnife(UUID uuid) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return cosmetics.createKnife(new Profile(uuid, "Player"));
        }
        return cosmetics.createKnife(profile);
    }

    @Override
    public ItemStack createBow(UUID uuid) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return cosmetics.createBow(new Profile(uuid, "Player"));
        }
        return cosmetics.createBow(profile);
    }

    @Override
    public java.util.List<String> getAvailableCosmetics(CosmeticType type) {
        return cosmetics.getOptions(type);
    }

    @Override
    public boolean unlockCosmetic(UUID uuid, CosmeticType type, String id) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return false;
        }
        boolean result = cosmetics.unlock(profile, type, id);
        if (result) {
            profileService.saveProfile(profile);
        }
        return result;
    }

    @Override
    public boolean selectCosmetic(UUID uuid, CosmeticType type, String id) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return false;
        }
        boolean result = cosmetics.select(profile, type, id);
        if (result) {
            profileService.saveProfile(profile);
        }
        return result;
    }

    @Override
    public boolean toggleFavoriteCosmetic(UUID uuid, CosmeticType type, String id) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return false;
        }
        boolean result = cosmetics.toggleFavorite(profile, type, id);
        if (result) {
            profileService.saveProfile(profile);
        }
        return result;
    }

    @Override
    public boolean isFavoriteCosmetic(UUID uuid, CosmeticType type, String id) {
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return false;
        }
        return cosmetics.isFavorite(profile, type, id);
    }

    @Override
    public int getCounter(UUID uuid, String key) {
        if (key == null || key.trim().isEmpty()) {
            return 0;
        }
        Profile profile = profileService.getProfile(uuid);
        return profile == null ? 0 : profile.getStats().getCustomCounter(key);
    }

    @Override
    public boolean spendCounter(UUID uuid, String key, int amount) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return false;
        }
        int current = profile.getStats().getCustomCounter(key);
        if (current < amount) {
            return false;
        }
        profile.getStats().addCustomCounter(key, -amount);
        profileService.saveProfile(profile);
        return true;
    }

    @Override
    public void addCounter(UUID uuid, String key, int amount) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        if (amount <= 0) {
            return;
        }
        Profile profile = profileService.getProfile(uuid);
        if (profile == null) {
            return;
        }
        profile.getStats().addCustomCounter(key, amount);
        profileService.saveProfile(profile);
    }

    @Override
    public java.util.Map<String, io.github.mebsic.core.model.KnifeSkinDefinition> getKnifeSkins() {
        return cosmetics == null ? java.util.Collections.emptyMap() : cosmetics.getKnifeSkins();
    }

    public void ensureServerIdentity() {
        ServerIdentityResolver.apply(this, getConfig());
    }

    public boolean isChatBlocked(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) {
            return false;
        }
        if (isStaff(first) || isStaff(second)) {
            return false;
        }
        if (resolveBlockedPlayers(first).contains(second)) {
            return true;
        }
        return resolveBlockedPlayers(second).contains(first);
    }

    private boolean isStaff(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Rank rank = getRank(uuid);
        return rank != null && rank.isAtLeast(Rank.STAFF);
    }

    private void handleFriendVisibilityUpdate(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }
        String[] parts = payload.split(",", 2);
        UUID first = parseUuid(parts[0]);
        if (first != null) {
            blockedCache.remove(first);
        }
        if (parts.length < 2) {
            return;
        }
        UUID second = parseUuid(parts[1]);
        if (second != null) {
            blockedCache.remove(second);
        }
    }

    public void ensureFriendDocument(UUID uuid, String name) {
        if (uuid == null || mongo == null) {
            return;
        }
        MongoCollection<Document> friends = mongo.getCollection(FRIENDS_COLLECTION);
        if (friends == null) {
            return;
        }
        Document setOnInsert = new Document("uuid", uuid.toString())
                .append("friends", new java.util.ArrayList<String>())
                .append("bestFriends", new java.util.ArrayList<String>())
                .append("blockedPlayers", new java.util.ArrayList<String>())
                .append("friendSince", new Document())
                .append("friendNicknames", new Document())
                .append("settings", new Document("friendJoinLeaveNotifications", true));
        Document update = new Document("$setOnInsert", setOnInsert);
        if (name != null && !name.trim().isEmpty()) {
            update.append("$set", new Document("name", name.trim()));
        }
        friends.updateOne(eq("uuid", uuid.toString()), update, new UpdateOptions().upsert(true));
    }

    private void ensureFriendDocumentAsync(UUID uuid, String name) {
        if (uuid == null || mongo == null || !isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> ensureFriendDocument(uuid, name));
    }

    private void ensureFriendDocumentsForOnlinePlayers() {
        if (mongo == null || !isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null) {
                    continue;
                }
                ensureFriendDocument(player.getUniqueId(), player.getName());
            }
        });
    }

    private Set<UUID> resolveBlockedPlayers(UUID ownerId) {
        if (ownerId == null) {
            return Collections.emptySet();
        }
        long now = System.currentTimeMillis();
        BlockedCacheEntry cached = blockedCache.get(ownerId);
        if (cached != null && now - cached.loadedAt <= BLOCKED_CACHE_TTL_MILLIS) {
            return cached.blocked;
        }
        Set<UUID> loaded = loadBlockedPlayers(ownerId);
        blockedCache.put(ownerId, new BlockedCacheEntry(loaded, now));
        return loaded;
    }

    private Set<UUID> loadBlockedPlayers(UUID ownerId) {
        if (ownerId == null || mongo == null) {
            return Collections.emptySet();
        }
        MongoCollection<Document> friends = mongo.getCollection(FRIENDS_COLLECTION);
        if (friends == null) {
            return Collections.emptySet();
        }
        Document doc = friends.find(eq("uuid", ownerId.toString()))
                .projection(new Document("blockedPlayers", 1))
                .first();
        if (doc == null) {
            return Collections.emptySet();
        }
        @SuppressWarnings("unchecked")
        List<String> blockedRaw = (List<String>) doc.get("blockedPlayers");
        if (blockedRaw == null || blockedRaw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> blocked = new HashSet<UUID>();
        for (String raw : blockedRaw) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                blocked.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (blocked.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(blocked);
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class BlockedCacheEntry {
        private final Set<UUID> blocked;
        private final long loadedAt;

        private BlockedCacheEntry(Set<UUID> blocked, long loadedAt) {
            this.blocked = blocked == null ? Collections.<UUID>emptySet() : blocked;
            this.loadedAt = loadedAt;
        }
    }
}
