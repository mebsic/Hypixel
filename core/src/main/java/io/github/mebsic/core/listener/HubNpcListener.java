package io.github.mebsic.core.listener;

import com.mongodb.client.MongoCollection;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.menu.ClickToPlayNpcMenu;
import io.github.mebsic.core.menu.ProfileNpcMenu;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.ServerRegistrySnapshot;
import io.github.mebsic.core.store.MapConfigStore;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class HubNpcListener implements Listener {
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    private static final String CLICK_TO_PLAY_LABEL = "CLICK TO PLAY";
    private static final String PROFILE_CLICK_LABEL = "CLICK FOR STATS";
    private static final String DEFAULT_CLICK_TO_PLAY_SKIN = "MurderMytsery";
    private static final double HOLOGRAM_LINE_SPACING = 0.30d;
    private static final double CLICK_TO_PLAY_HOLOGRAM_BOTTOM_Y_OFFSET = 0.05d;
    private static final double PROFILE_HOLOGRAM_BOTTOM_Y_OFFSET = 0.05d;
    private static final long CLICK_TO_PLAY_REFRESH_INTERVAL_TICKS = 40L;
    private static final long CLICK_TO_PLAY_REFRESH_MILLIS = 2_000L;
    private static final int PROFILE_STATS_REFRESH_ATTEMPTS = 6;
    private static final long PROFILE_STATS_REFRESH_INTERVAL_TICKS = 4L;
    private static final long PROFILE_HOLOGRAM_REFRESH_INTERVAL_TICKS = 5L;
    private static final String CITIZENS_PLUGIN_NAME = "Citizens";
    private static final String CITIZENS_NAMEPLATE_VISIBLE_KEY = "nameplate-visible";
    private static final String CITIZENS_DEFAULT_NAME = "NPC";

    private final JavaPlugin plugin;
    private final CorePlugin corePlugin;
    private final ServerType serverType;
    private final ServerType clickToPlayGameServerType;
    private final ClickToPlayNpcMenu clickToPlayMenu;
    private final ProfileNpcMenu profileMenu;
    private final ServerRegistrySnapshot registrySnapshot;
    private final BukkitTask clickToPlayRefreshTask;
    private final BukkitTask profileHologramRefreshTask;
    private final NumberFormat numberFormat;
    private final NPCRegistry npcRegistry;
    private final boolean citizensEnabled;
    private final Map<Integer, RuntimeNpc> npcsById;
    private final Map<UUID, RuntimeNpc> npcsByEntityUuid;
    private final Map<UUID, UUID> linkedEntityToAnchor;
    private final List<ProfileNpcTemplate> profileNpcTemplates;
    private final Map<UUID, List<RuntimeNpc>> profileNpcsByViewer;
    private volatile String activeGameKey;

    public HubNpcListener(JavaPlugin plugin, CorePlugin corePlugin, ServerType serverType) {
        this.plugin = plugin;
        this.corePlugin = corePlugin;
        this.serverType = serverType == null ? ServerType.UNKNOWN : serverType;
        this.clickToPlayGameServerType = resolveClickToPlayGameServerType(this.serverType);
        this.clickToPlayMenu = new ClickToPlayNpcMenu(
                corePlugin,
                corePlugin == null ? null : corePlugin.getQueueClient(),
                this.clickToPlayGameServerType
        );
        this.profileMenu = new ProfileNpcMenu(corePlugin, this.serverType);
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.npcRegistry = resolveNpcRegistry();
        this.citizensEnabled = this.npcRegistry != null;
        this.npcsById = new ConcurrentHashMap<Integer, RuntimeNpc>();
        this.npcsByEntityUuid = new ConcurrentHashMap<UUID, RuntimeNpc>();
        this.linkedEntityToAnchor = new ConcurrentHashMap<UUID, UUID>();
        this.profileNpcTemplates = new ArrayList<ProfileNpcTemplate>();
        this.profileNpcsByViewer = new ConcurrentHashMap<UUID, List<RuntimeNpc>>();
        this.activeGameKey = MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
        this.registrySnapshot = createRegistrySnapshot();
        if (!citizensEnabled) {
            plugin.getLogger().warning("Citizens is missing or not initialized; hub NPC spawning is disabled.");
        }
        loadAndSpawn();
        subscribeToMapConfigUpdates();
        this.clickToPlayRefreshTask = startClickToPlayRefreshTask();
        this.profileHologramRefreshTask = startProfileHologramRefreshTask();
    }

    public void shutdown() {
        despawnAll();
        if (clickToPlayRefreshTask != null) {
            clickToPlayRefreshTask.cancel();
        }
        if (profileHologramRefreshTask != null) {
            profileHologramRefreshTask.cancel();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!handleNpcClick(player, event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractFallback(PlayerInteractEntityEvent event) {
        if (event == null) {
            return;
        }
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!handleNpcClick(player, event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null || plugin == null || !serverType.isHub()) {
            return;
        }
        Player player = event.getPlayer();
        UUID viewerUuid = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = viewerUuid == null ? null : Bukkit.getPlayer(viewerUuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            spawnProfileNpcsForPlayer(online);
        });
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player online = viewerUuid == null ? null : Bukkit.getPlayer(viewerUuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            refreshProfileNpcsForViewer(online);
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null || !serverType.isHub()) {
            return;
        }
        despawnProfileNpcsForViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onNpcDamage(EntityDamageEvent event) {
        if (event == null || event.getEntity() == null || event.getEntity().getUniqueId() == null) {
            return;
        }
        RuntimeNpc runtime = resolveRuntime(event.getEntity().getUniqueId());
        if (runtime == null) {
            runtime = resolveRuntimeFromCitizens(event.getEntity());
        }
        if (runtime == null) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            if (damager instanceof Player) {
                handleNpcClick((Player) damager, event.getEntity());
            }
        }
        event.setCancelled(true);
    }

    private boolean handleNpcClick(Player player, Entity clicked) {
        if (player == null || clicked == null) {
            return false;
        }
        RuntimeNpc runtime = resolveRuntime(clicked.getUniqueId());
        if (runtime == null) {
            runtime = resolveRuntimeFromCitizens(clicked);
        }
        if (runtime == null) {
            return false;
        }
        if (runtime.kind == NpcKind.PROFILE) {
            if (runtime.profileViewerUuid != null && !runtime.profileViewerUuid.equals(player.getUniqueId())) {
                return false;
            }
            applyProfileNpcViewerHologram(runtime, player);
            scheduleProfileNpcViewerRefresh(runtime, player);
            profileMenu.open(player);
            return true;
        }
        clickToPlayMenu.open(player);
        return true;
    }

    private RuntimeNpc resolveRuntime(UUID entityUuid) {
        if (entityUuid == null) {
            return null;
        }
        RuntimeNpc direct = npcsByEntityUuid.get(entityUuid);
        if (direct != null) {
            return direct;
        }
        UUID anchor = linkedEntityToAnchor.get(entityUuid);
        if (anchor == null) {
            return null;
        }
        return npcsByEntityUuid.get(anchor);
    }

    private RuntimeNpc resolveRuntimeFromCitizens(Entity entity) {
        if (!citizensEnabled || npcRegistry == null || entity == null) {
            return null;
        }
        NPC npc;
        try {
            npc = npcRegistry.getNPC(entity);
        } catch (Exception ignored) {
            return null;
        }
        if (npc == null) {
            return null;
        }
        RuntimeNpc runtime = npcsById.get(npc.getId());
        if (runtime == null) {
            return null;
        }
        runtime.npc = npc;
        UUID entityUuid = entity.getUniqueId();
        hideNpcIdentity(npc, entity);
        if (entityUuid != null && !entityUuid.equals(runtime.anchorUuid)) {
            if (runtime.anchorUuid != null) {
                npcsByEntityUuid.remove(runtime.anchorUuid);
            }
            runtime.anchorUuid = entityUuid;
            npcsByEntityUuid.put(entityUuid, runtime);
        }
        return runtime;
    }

    private void loadAndSpawn() {
        if (!citizensEnabled || corePlugin == null || corePlugin.getMongoManager() == null || !serverType.isHub()) {
            return;
        }

        ResolvedHubMap resolvedMap = resolveHubMap();
        if (resolvedMap == null || resolvedMap.map == null) {
            return;
        }
        this.activeGameKey = resolvedMap.gameKey;
        Document map = resolvedMap.map;

        despawnAll();

        int spawned = 0;
        int profileTemplateCount = 0;
        for (Object rawNpc : asObjectList(map.get("npcs"))) {
            if (spawnConfiguredNpc(rawNpc, NpcKind.CLICK_TO_PLAY)) {
                spawned++;
            }
        }
        for (Object rawNpc : asObjectList(map.get("profileNpcs"))) {
            if (spawnConfiguredNpc(rawNpc, NpcKind.PROFILE)) {
                profileTemplateCount++;
            }
        }
        spawned += spawnProfileNpcsForOnlinePlayers();
        plugin.getLogger().info("Loaded " + spawned + " hub NPC(s) for " + serverType.name()
                + " with " + profileTemplateCount + " profile template(s).");
    }

    private void subscribeToMapConfigUpdates() {
        if (corePlugin == null || !serverType.isHub()) {
            return;
        }
        if (corePlugin.getPubSubService() == null) {
            return;
        }
        corePlugin.getPubSubService().subscribe(MAP_CONFIG_UPDATE_CHANNEL, this::handleMapConfigUpdateMessage);
    }

    private void handleMapConfigUpdateMessage(String message) {
        String updatedKey = parseUpdatedGameKey(message);
        if (updatedKey.isEmpty()) {
            return;
        }
        if (!shouldReloadForGameKey(updatedKey)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::loadAndSpawn);
    }

    private String parseUpdatedGameKey(String message) {
        String raw = safeText(message);
        if (raw.isEmpty()) {
            return "";
        }
        String key = raw;
        if (raw.toLowerCase(Locale.ROOT).startsWith(MAP_CONFIG_UPDATE_PREFIX)) {
            key = raw.substring(MAP_CONFIG_UPDATE_PREFIX.length());
        }
        key = MapConfigStore.normalizeGameKey(key);
        if (key.isEmpty()) {
            return "";
        }
        return key;
    }

    private boolean spawnConfiguredNpc(Object rawNpc, NpcKind defaultKind) {
        Document npc = asDocument(rawNpc);
        NpcKind kind = defaultKind;
        String profileOwner = "Steve";
        if (npc != null) {
            String configuredKind = safeText(npc.get("npcKind"));
            if ("CLICK_TO_PLAY".equalsIgnoreCase(configuredKind)) {
                kind = NpcKind.CLICK_TO_PLAY;
            } else if ("PROFILE".equalsIgnoreCase(configuredKind)) {
                kind = NpcKind.PROFILE;
            }
            String configuredOwner = safeText(npc.get("ownerName"));
            if (!configuredOwner.isEmpty()) {
                profileOwner = configuredOwner;
            }
        }

        Location location = parseLocation(npc == null ? rawNpc : npc);
        if (location == null || location.getWorld() == null) {
            return false;
        }
        location = normalizeNpcRotation(location);
        String skinOwner;
        if (kind == NpcKind.CLICK_TO_PLAY) {
            skinOwner = DEFAULT_CLICK_TO_PLAY_SKIN;
        } else {
            skinOwner = profileOwner;
        }
        if (kind == NpcKind.PROFILE) {
            return registerProfileTemplate(location, skinOwner);
        }
        return spawnRuntimeNpc(kind, location, skinOwner, null) != null;
    }

    private boolean registerProfileTemplate(Location location, String fallbackSkinOwner) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        ProfileNpcTemplate template = new ProfileNpcTemplate(
                location.clone(),
                resolveProfileSkinOwner(null, safeText(fallbackSkinOwner))
        );
        profileNpcTemplates.add(template);
        return true;
    }

    private int spawnProfileNpcsForOnlinePlayers() {
        if (profileNpcTemplates.isEmpty()) {
            return 0;
        }
        int spawned = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            spawned += spawnProfileNpcsForPlayer(player);
        }
        return spawned;
    }

    private int spawnProfileNpcsForPlayer(Player viewer) {
        if (!citizensEnabled || viewer == null || !viewer.isOnline() || profileNpcTemplates.isEmpty()) {
            return 0;
        }
        UUID viewerUuid = viewer.getUniqueId();
        if (viewerUuid == null) {
            return 0;
        }
        despawnProfileNpcsForViewer(viewerUuid);

        List<RuntimeNpc> created = new ArrayList<RuntimeNpc>();
        for (ProfileNpcTemplate template : profileNpcTemplates) {
            if (template == null || template.location == null || template.location.getWorld() == null) {
                continue;
            }
            String skinOwner = resolveProfileSkinOwner(viewer, template.fallbackSkinOwner);
            RuntimeNpc runtime = spawnRuntimeNpc(
                    NpcKind.PROFILE,
                    template.location.clone(),
                    skinOwner,
                    viewerUuid
            );
            if (runtime != null) {
                created.add(runtime);
                scheduleProfileNpcViewerRefresh(runtime, viewer);
            }
        }
        if (!created.isEmpty()) {
            profileNpcsByViewer.put(viewerUuid, created);
        }
        return created.size();
    }

    private void refreshProfileNpcsForViewer(Player viewer) {
        if (!citizensEnabled || viewer == null || !viewer.isOnline() || viewer.getUniqueId() == null) {
            return;
        }
        UUID viewerUuid = viewer.getUniqueId();
        List<RuntimeNpc> runtimes = profileNpcsByViewer.get(viewerUuid);
        if (runtimes == null || runtimes.isEmpty()) {
            return;
        }
        String owner = resolveProfileSkinOwner(viewer, safeText(viewer.getName()));
        List<RuntimeNpc> snapshot = new ArrayList<RuntimeNpc>(runtimes);
        for (RuntimeNpc runtime : snapshot) {
            if (runtime == null || runtime.kind != NpcKind.PROFILE) {
                continue;
            }
            if (runtime.profileViewerUuid != null && !runtime.profileViewerUuid.equals(viewerUuid)) {
                continue;
            }
            runtime.skinOwner = owner;
            NPC npc = runtime.npc;
            if (npc == null && runtime.npcId > 0) {
                npc = resolveNpcById(runtime.npcId);
                runtime.npc = npc;
            }
            if (npc != null) {
                applyCitizensSkin(npc, NpcKind.PROFILE, owner);
                hideNpcIdentity(npc, npc.getEntity());
            }
            ensureRuntimeAnchor(runtime);
            syncProfileNpcViewerVisibility(runtime);
            applyProfileNpcViewerHologram(runtime, viewer);
        }
    }

    private void despawnProfileNpcsForViewer(UUID viewerUuid) {
        if (viewerUuid == null) {
            return;
        }
        List<RuntimeNpc> existing = profileNpcsByViewer.remove(viewerUuid);
        if (existing == null || existing.isEmpty()) {
            return;
        }
        for (RuntimeNpc runtime : existing) {
            if (runtime == null) {
                continue;
            }
            clearNpcHologramLines(runtime);
            if (runtime.npcId > 0) {
                npcsById.remove(runtime.npcId);
            }
            if (runtime.anchorUuid != null) {
                npcsByEntityUuid.remove(runtime.anchorUuid);
            }
            removeRuntimeNpc(runtime);
        }
    }

    private RuntimeNpc spawnRuntimeNpc(NpcKind kind, Location location, String skinOwner, UUID profileViewerUuid) {
        if (!citizensEnabled || location == null || location.getWorld() == null) {
            return null;
        }
        Location base = location.clone();
        NPC npc = spawnCitizensNpc(kind, base, skinOwner);
        if (npc == null) {
            return null;
        }
        Entity anchor = npc.getEntity();
        if (anchor == null || anchor.getUniqueId() == null) {
            return null;
        }
        if (kind == NpcKind.PROFILE) {
            applyProfileNpcHeldItem(anchor);
        }

        RuntimeNpc runtime = new RuntimeNpc();
        runtime.kind = kind;
        runtime.skinOwner = safeText(skinOwner);
        runtime.spawnLocation = base.clone();
        runtime.npcId = npc.getId();
        runtime.npc = npc;
        runtime.anchorUuid = anchor.getUniqueId();
        runtime.profileViewerUuid = profileViewerUuid;
        npcsById.put(runtime.npcId, runtime);
        npcsByEntityUuid.put(runtime.anchorUuid, runtime);
        if (kind == NpcKind.PROFILE) {
            Player viewer = profileViewerUuid == null ? null : Bukkit.getPlayer(profileViewerUuid);
            applyNpcHologramLines(runtime, profileHologramLines(viewer));
            syncProfileNpcViewerVisibility(runtime);
            return runtime;
        }
        int players = currentClickToPlayPlayerCount();
        runtime.lastClickToPlayPlayers = players;
        applyNpcHologramLines(runtime, clickToPlayHologramLines(players));
        return runtime;
    }

    private void applyProfileNpcViewerHologram(RuntimeNpc runtime, Player viewer) {
        if (runtime == null || runtime.kind != NpcKind.PROFILE) {
            return;
        }
        if (runtime.profileViewerUuid != null && viewer != null
                && !runtime.profileViewerUuid.equals(viewer.getUniqueId())) {
            return;
        }
        applyNpcHologramLines(runtime, profileHologramLines(viewer));
    }

    private String resolveProfileSkinOwner(Player viewer, String fallback) {
        String owner = safeText(viewer == null ? null : viewer.getName()).replaceAll("\\s+", "");
        if (owner.isEmpty()) {
            owner = safeText(fallback).replaceAll("\\s+", "");
        }
        if (owner.isEmpty()) {
            owner = "Steve";
        }
        if (owner.length() > 16) {
            owner = owner.substring(0, 16);
        }
        return owner.isEmpty() ? "Steve" : owner;
    }

    private void scheduleProfileNpcViewerRefresh(RuntimeNpc runtime, Player viewer) {
        if (plugin == null || runtime == null || viewer == null || !viewer.isOnline()) {
            return;
        }
        final int targetNpcId = runtime.npcId;
        final UUID viewerUuid = viewer.getUniqueId();
        for (int attempt = 1; attempt <= PROFILE_STATS_REFRESH_ATTEMPTS; attempt++) {
            long delay = attempt * PROFILE_STATS_REFRESH_INTERVAL_TICKS;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player onlineViewer = Bukkit.getPlayer(viewerUuid);
                if (onlineViewer == null || !onlineViewer.isOnline()) {
                    return;
                }
                RuntimeNpc latest = targetNpcId > 0 ? npcsById.get(targetNpcId) : runtime;
                if (latest == null || latest.kind != NpcKind.PROFILE) {
                    return;
                }
                applyProfileNpcViewerHologram(latest, onlineViewer);
                profileMenu.refreshIfOpen(onlineViewer);
            }, delay);
        }
    }

    private List<String> clickToPlayHologramLines(int players) {
        int currentPlayers = Math.max(0, players);
        List<String> lines = new ArrayList<String>(3);
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + CLICK_TO_PLAY_LABEL);
        lines.add(ChatColor.AQUA + "Classic");
        lines.add(clickToPlayPlayersLine(currentPlayers));
        return lines;
    }

    private String clickToPlayPlayersLine(int players) {
        int currentPlayers = Math.max(0, players);
        String label = currentPlayers == 1 ? "Player" : "Players";
        return ChatColor.YELLOW.toString() + ChatColor.BOLD + formatNumber(currentPlayers) + " " + label;
    }

    private List<String> profileHologramLines(Player viewer) {
        Profile profile = null;
        if (corePlugin != null && viewer != null && viewer.getUniqueId() != null) {
            profile = corePlugin.getProfile(viewer.getUniqueId());
        }
        Stats stats = profile == null ? null : profile.getStats();
        int wins = stats == null ? 0 : Math.max(0, stats.getWins());
        int winsAsMurderer = stats == null ? 0 : Math.max(0, stats.getCustomCounter(MongoManager.MURDER_MYSTERY_WINS_AS_MURDERER_KEY));

        List<String> lines = new ArrayList<String>(4);
        lines.add(ChatColor.GOLD.toString() + ChatColor.BOLD + "Your " + gameTypeLabel() + " Profile");
        lines.add(ChatColor.WHITE + "Wins as Murderer: " + ChatColor.GREEN + formatNumber(winsAsMurderer));
        lines.add(ChatColor.WHITE + "Total Wins: " + ChatColor.GREEN + formatNumber(wins));
        lines.add(ChatColor.YELLOW.toString() + ChatColor.BOLD + PROFILE_CLICK_LABEL);
        return lines;
    }

    private String gameTypeLabel() {
        String name = safeText(serverType == null ? null : serverType.getGameTypeDisplayName());
        if (name.isEmpty()) {
            name = "Murder Mystery";
        }
        return toDisplayCase(name);
    }

    private String toDisplayCase(String text) {
        String normalized = safeText(text).replace('_', ' ');
        if (normalized.isEmpty()) {
            return "Murder Mystery";
        }
        String[] words = normalized.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            String token = safeText(word).toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(token.charAt(0)));
            if (token.length() > 1) {
                out.append(token.substring(1));
            }
        }
        return out.length() == 0 ? "Murder Mystery" : out.toString();
    }

    private String formatNumber(int value) {
        return numberFormat.format(Math.max(0, value));
    }

    private void refreshClickToPlayPlayerCounts() {
        int players = currentClickToPlayPlayerCount();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            maintainNpcAnchors();
            applyClickToPlayPlayerCount(players);
        });
    }

    private void maintainNpcAnchors() {
        List<RuntimeNpc> snapshot = new ArrayList<RuntimeNpc>(npcsById.values());
        for (RuntimeNpc runtime : snapshot) {
            ensureRuntimeAnchor(runtime);
        }
    }

    private void ensureRuntimeAnchor(RuntimeNpc runtime) {
        if (runtime == null || runtime.spawnLocation == null || runtime.spawnLocation.getWorld() == null) {
            return;
        }
        NPC npc = runtime.npc;
        if (npc == null) {
            respawnRuntimeAnchor(runtime);
            return;
        }
        Entity anchor;
        try {
            if (!npc.isSpawned()) {
                npc.spawn(runtime.spawnLocation.clone());
            }
            anchor = npc.getEntity();
        } catch (Exception ignored) {
            anchor = null;
        }
        if (anchor == null || anchor.getUniqueId() == null) {
            respawnRuntimeAnchor(runtime);
            return;
        }
        hideNpcIdentity(npc, anchor);
        UUID currentAnchorUuid = anchor.getUniqueId();
        if (!currentAnchorUuid.equals(runtime.anchorUuid)) {
            if (runtime.anchorUuid != null) {
                npcsByEntityUuid.remove(runtime.anchorUuid);
            }
            runtime.anchorUuid = currentAnchorUuid;
            npcsByEntityUuid.put(currentAnchorUuid, runtime);
        }
        Location target = runtime.spawnLocation.clone();
        Location current = anchor.getLocation();
        if (current == null || current.getWorld() == null
                || !current.getWorld().equals(target.getWorld())
                || current.distanceSquared(target) > 0.25d
                || shouldSyncRotation(current, target)) {
            anchor.teleport(target);
        }
        if (runtime.kind == NpcKind.PROFILE) {
            applyProfileNpcHeldItem(anchor);
        }
    }

    private void respawnRuntimeAnchor(RuntimeNpc runtime) {
        if (runtime == null || runtime.spawnLocation == null || runtime.spawnLocation.getWorld() == null) {
            return;
        }
        int previousNpcId = runtime.npcId;
        if (runtime.anchorUuid != null) {
            npcsByEntityUuid.remove(runtime.anchorUuid);
        }
        NPC npc = runtime.npc;
        if (npc == null) {
            npc = spawnCitizensNpc(
                    runtime.kind,
                    runtime.spawnLocation.clone(),
                    runtime.skinOwner
            );
            if (npc == null) {
                return;
            }
            runtime.npc = npc;
            runtime.npcId = npc.getId();
            if (previousNpcId > 0 && previousNpcId != runtime.npcId) {
                npcsById.remove(previousNpcId);
            }
            npcsById.put(runtime.npcId, runtime);
        } else {
            try {
                if (!npc.isSpawned()) {
                    npc.spawn(runtime.spawnLocation.clone());
                }
            } catch (Exception ignored) {
                deregisterNpc(npc);
                if (runtime.npcId > 0) {
                    npcsById.remove(runtime.npcId);
                }
                runtime.npc = null;
                runtime.npcId = -1;
                npc = spawnCitizensNpc(
                        runtime.kind,
                        runtime.spawnLocation.clone(),
                        runtime.skinOwner
                );
                if (npc == null) {
                    return;
                }
                runtime.npc = npc;
                runtime.npcId = npc.getId();
                if (previousNpcId > 0 && previousNpcId != runtime.npcId) {
                    npcsById.remove(previousNpcId);
                }
                npcsById.put(runtime.npcId, runtime);
            }
        }
        Entity anchor = npc.getEntity();
        if (anchor == null || anchor.getUniqueId() == null) {
            return;
        }
        hideNpcIdentity(npc, anchor);
        anchor.teleport(runtime.spawnLocation.clone());
        runtime.anchorUuid = anchor.getUniqueId();
        npcsByEntityUuid.put(runtime.anchorUuid, runtime);
        if (runtime.kind == NpcKind.PROFILE) {
            applyProfileNpcHeldItem(anchor);
            Player viewer = runtime.profileViewerUuid == null ? null : Bukkit.getPlayer(runtime.profileViewerUuid);
            applyNpcHologramLines(runtime, profileHologramLines(viewer));
            syncProfileNpcViewerVisibility(runtime);
            return;
        }
        int players = runtime.lastClickToPlayPlayers < 0 ? currentClickToPlayPlayerCount() : runtime.lastClickToPlayPlayers;
        runtime.lastClickToPlayPlayers = players;
        applyNpcHologramLines(runtime, clickToPlayHologramLines(players));
    }

    private void applyClickToPlayPlayerCount(int players) {
        int currentPlayers = Math.max(0, players);
        List<RuntimeNpc> snapshot = new ArrayList<RuntimeNpc>(npcsByEntityUuid.values());
        for (RuntimeNpc runtime : snapshot) {
            if (runtime == null || runtime.kind != NpcKind.CLICK_TO_PLAY) {
                continue;
            }
            if (runtime.lastClickToPlayPlayers == currentPlayers
                    && runtime.hologramLineUuids != null
                    && !runtime.hologramLineUuids.isEmpty()) {
                continue;
            }
            runtime.lastClickToPlayPlayers = currentPlayers;
            if (updateClickToPlayPlayerLine(runtime, currentPlayers)) {
                continue;
            }
            applyNpcHologramLines(runtime, clickToPlayHologramLines(currentPlayers));
        }
    }

    private boolean updateClickToPlayPlayerLine(RuntimeNpc runtime, int players) {
        if (runtime == null || runtime.hologramLineUuids == null || runtime.hologramLineUuids.size() < 3) {
            return false;
        }
        UUID countLineUuid = runtime.hologramLineUuids.get(2);
        Entity entity = resolveEntity(countLineUuid);
        if (!(entity instanceof ArmorStand)) {
            return false;
        }
        ArmorStand stand = (ArmorStand) entity;
        stand.setCustomName(clickToPlayPlayersLine(players));
        return true;
    }

    private int currentClickToPlayPlayerCount() {
        if (registrySnapshot == null || clickToPlayGameServerType == null || !clickToPlayGameServerType.isGame()) {
            return 0;
        }
        return Math.max(0, registrySnapshot.getTotalPlayers(clickToPlayGameServerType));
    }

    private BukkitTask startClickToPlayRefreshTask() {
        if (plugin == null) {
            return null;
        }
        return plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::refreshClickToPlayPlayerCounts,
                CLICK_TO_PLAY_REFRESH_INTERVAL_TICKS,
                CLICK_TO_PLAY_REFRESH_INTERVAL_TICKS
        );
    }

    private BukkitTask startProfileHologramRefreshTask() {
        if (plugin == null || !serverType.isHub()) {
            return null;
        }
        return plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshProfileHolograms,
                PROFILE_HOLOGRAM_REFRESH_INTERVAL_TICKS,
                PROFILE_HOLOGRAM_REFRESH_INTERVAL_TICKS
        );
    }

    private void refreshProfileHolograms() {
        if (!serverType.isHub()) {
            return;
        }
        List<Player> onlinePlayers = new ArrayList<Player>(Bukkit.getOnlinePlayers());
        if (onlinePlayers.isEmpty()) {
            return;
        }
        for (Player viewer : onlinePlayers) {
            if (viewer == null || !viewer.isOnline() || viewer.getUniqueId() == null) {
                continue;
            }
            UUID viewerUuid = viewer.getUniqueId();
            List<RuntimeNpc> runtimes = profileNpcsByViewer.get(viewerUuid);
            if (runtimes == null || runtimes.isEmpty()) {
                spawnProfileNpcsForPlayer(viewer);
                runtimes = profileNpcsByViewer.get(viewerUuid);
            }
            if (runtimes == null || runtimes.isEmpty()) {
                continue;
            }
            List<RuntimeNpc> snapshot = new ArrayList<RuntimeNpc>(runtimes);
            for (RuntimeNpc runtime : snapshot) {
                if (runtime == null || runtime.kind != NpcKind.PROFILE) {
                    continue;
                }
                ensureRuntimeAnchor(runtime);
                syncProfileNpcViewerVisibility(runtime);
                applyProfileNpcViewerHologram(runtime, viewer);
            }
        }
        List<UUID> trackedViewers = new ArrayList<UUID>(profileNpcsByViewer.keySet());
        for (UUID tracked : trackedViewers) {
            if (tracked == null) {
                continue;
            }
            Player online = Bukkit.getPlayer(tracked);
            if (online == null || !online.isOnline()) {
                despawnProfileNpcsForViewer(tracked);
            }
        }
    }

    private void syncProfileNpcViewerVisibility(RuntimeNpc runtime) {
        if (runtime == null || runtime.kind != NpcKind.PROFILE || runtime.profileViewerUuid == null) {
            return;
        }
        Entity anchor = resolveEntity(runtime.anchorUuid);
        if (!(anchor instanceof Player)) {
            return;
        }
        Player npcPlayer = (Player) anchor;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null || !online.isOnline() || online.getUniqueId() == null) {
                continue;
            }
            setPlayerEntityVisible(online, npcPlayer, runtime.profileViewerUuid.equals(online.getUniqueId()));
        }
        syncProfileHologramViewerVisibility(runtime);
    }

    private void syncProfileHologramViewerVisibility(RuntimeNpc runtime) {
        if (runtime == null || runtime.kind != NpcKind.PROFILE || runtime.profileViewerUuid == null) {
            return;
        }
        if (runtime.hologramLineUuids == null || runtime.hologramLineUuids.isEmpty()) {
            return;
        }
        List<Entity> lineEntities = new ArrayList<Entity>();
        for (UUID lineUuid : runtime.hologramLineUuids) {
            Entity entity = resolveEntity(lineUuid);
            if (entity != null && entity.isValid()) {
                lineEntities.add(entity);
            }
        }
        if (lineEntities.isEmpty()) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null || !online.isOnline() || online.getUniqueId() == null) {
                continue;
            }
            if (runtime.profileViewerUuid.equals(online.getUniqueId())) {
                continue;
            }
            for (Entity line : lineEntities) {
                hideEntityFromViewer(online, line);
            }
        }
    }

    private boolean hideEntityFromViewer(Player viewer, Entity entity) {
        if (viewer == null || entity == null) {
            return false;
        }
        int entityId = entity.getEntityId();
        if (entityId <= 0) {
            return false;
        }
        String version = resolveNmsVersion();
        if (version.isEmpty()) {
            return false;
        }
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Class<?> destroyPacketClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutEntityDestroy");
            Object craftPlayer = craftPlayerClass.cast(viewer);
            Method getHandle = craftPlayerClass.getMethod("getHandle");
            Object handle = getHandle.invoke(craftPlayer);
            if (handle == null) {
                return false;
            }
            Object connection = handle.getClass().getField("playerConnection").get(handle);
            if (connection == null) {
                return false;
            }
            Object packet = destroyPacketClass.getConstructor(int[].class).newInstance(new int[]{entityId});
            Method sendPacket = connection.getClass().getMethod("sendPacket", packetClass);
            sendPacket.invoke(connection, packet);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String resolveNmsVersion() {
        String packageName = safeText(Bukkit.getServer() == null ? null : Bukkit.getServer().getClass().getPackage().getName());
        if (packageName.isEmpty()) {
            return "";
        }
        int lastDot = packageName.lastIndexOf('.');
        if (lastDot < 0 || lastDot + 1 >= packageName.length()) {
            return "";
        }
        return packageName.substring(lastDot + 1);
    }

    private void setPlayerEntityVisible(Player viewer, Player target, boolean visible) {
        if (viewer == null || target == null) {
            return;
        }
        String methodName = visible ? "showPlayer" : "hidePlayer";
        if (invokePlayerVisibilityMethod(viewer, methodName, target)) {
            return;
        }
        invokePlayerVisibilityMethodWithPlugin(viewer, methodName, target);
    }

    private boolean invokePlayerVisibilityMethod(Player viewer, String methodName, Player target) {
        if (viewer == null || methodName == null || methodName.isEmpty() || target == null) {
            return false;
        }
        try {
            Method method = viewer.getClass().getMethod(methodName, Player.class);
            method.invoke(viewer, target);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean invokePlayerVisibilityMethodWithPlugin(Player viewer, String methodName, Player target) {
        if (viewer == null || methodName == null || methodName.isEmpty() || target == null || plugin == null) {
            return false;
        }
        Method[] methods = viewer.getClass().getMethods();
        for (Method method : methods) {
            if (method == null || !methodName.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || !Player.class.isAssignableFrom(params[1])) {
                continue;
            }
            if (params[0] == null || !params[0].isAssignableFrom(plugin.getClass())) {
                continue;
            }
            try {
                method.invoke(viewer, plugin, target);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private ServerRegistrySnapshot createRegistrySnapshot() {
        if (plugin == null || corePlugin == null || corePlugin.getMongoManager() == null) {
            return null;
        }
        String group = corePlugin.getConfig() == null
                ? ""
                : corePlugin.getConfig().getString("server.group", "");
        int staleSeconds = corePlugin.getConfig() == null
                ? 20
                : Math.max(0, corePlugin.getConfig().getInt("registry.staleSeconds", 20));
        return new ServerRegistrySnapshot(
                plugin,
                corePlugin.getMongoManager(),
                group,
                staleSeconds,
                CLICK_TO_PLAY_REFRESH_MILLIS
        );
    }

    private ServerType resolveClickToPlayGameServerType(ServerType currentType) {
        ServerType resolved = currentType == null ? ServerType.UNKNOWN : currentType;
        if (resolved.isGame()) {
            return resolved;
        }
        if (resolved == ServerType.MURDER_MYSTERY_HUB) {
            return ServerType.MURDER_MYSTERY;
        }
        return ServerType.UNKNOWN;
    }

    private NPCRegistry resolveNpcRegistry() {
        if (plugin == null || plugin.getServer() == null || plugin.getServer().getPluginManager() == null) {
            return null;
        }
        Plugin citizensPlugin = plugin.getServer().getPluginManager().getPlugin(CITIZENS_PLUGIN_NAME);
        if (citizensPlugin == null || !citizensPlugin.isEnabled()) {
            return null;
        }
        try {
            return CitizensAPI.getNPCRegistry();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private NPC spawnCitizensNpc(NpcKind kind, Location location, String skinOwner) {
        if (!citizensEnabled || npcRegistry == null || location == null || location.getWorld() == null) {
            return null;
        }
        String npcName = resolveCitizensNpcName(kind);
        NPC npc;
        try {
            npc = npcRegistry.createNPC(org.bukkit.entity.EntityType.PLAYER, npcName);
        } catch (Exception ignored) {
            return null;
        }
        if (npc == null) {
            return null;
        }
        try {
            npc.setProtected(true);
        } catch (Exception ignored) {
            // Keep spawning even if protected metadata cannot be set.
        }
        try {
            Method setShouldSave = npc.getClass().getMethod("setShouldSave", boolean.class);
            setShouldSave.invoke(npc, Boolean.FALSE);
        } catch (Exception ignored) {
            // Citizens versions differ; best-effort to avoid persistence.
        }
        hideNpcIdentity(npc, null);
        applyCitizensSkin(npc, kind, skinOwner);
        try {
            if (!npc.spawn(location.clone())) {
                deregisterNpc(npc);
                return null;
            }
            hideNpcIdentity(npc, npc.getEntity());
            return npc;
        } catch (Exception ignored) {
            deregisterNpc(npc);
            return null;
        }
    }

    private boolean applyProfileViewerFilter(NPC npc, UUID profileViewerUuid) {
        if (npc == null || profileViewerUuid == null) {
            return false;
        }
        try {
            Object filter = resolvePlayerFilterTrait(npc);
            if (filter == null) {
                return false;
            }
            invokeNoArgMethod(filter, "clear", "reset", "clearPlayers");

            Player viewer = Bukkit.getPlayer(profileViewerUuid);
            boolean applied = false;
            applied = invokeSingleArgMethod(filter, profileViewerUuid, "only", "addPlayer", "add", "include", "showTo")
                    || applied;
            if (viewer != null) {
                applied = invokeSingleArgMethod(filter, viewer, "only", "addPlayer", "add", "include", "showTo")
                        || applied;
                applied = invokeSingleArgMethod(filter, viewer.getUniqueId(), "only", "addPlayer", "add", "include", "showTo")
                        || applied;
            }
            if (!applied) {
                java.util.function.Function<Player, Boolean> hideAllExceptViewer = player ->
                        player == null || !profileViewerUuid.equals(player.getUniqueId());
                applied = invokeSingleArgMethod(filter, hideAllExceptViewer, "setPlayerFilter");
            }
            return applied;
        } catch (Throwable ignored) {
            // Visibility filtering is best-effort across Citizens versions.
            return false;
        }
    }

    private Object resolvePlayerFilterTrait(NPC npc) {
        if (npc == null) {
            return null;
        }
        Object filter = resolveTraitByClassName(npc, "net.citizensnpcs.api.trait.trait.PlayerFilter");
        if (filter != null) {
            return filter;
        }
        return resolveTraitByClassName(npc, "net.citizensnpcs.trait.PlayerFilter");
    }

    private Object resolveTraitByClassName(NPC npc, String traitClassName) {
        if (npc == null || traitClassName == null || traitClassName.isEmpty()) {
            return null;
        }
        try {
            Class<?> traitClass = Class.forName(traitClassName);
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            return getOrAddTrait.invoke(npc, traitClass);
        } catch (Exception ignored) {
            return null;
        }
    }

    private synchronized String resolveCitizensNpcName(NpcKind kind) {
        String uuidPart = UUID.randomUUID().toString().replace("-", "");
        String name = ChatColor.DARK_GRAY + "[NPC] " + uuidPart;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        return name.isEmpty() ? CITIZENS_DEFAULT_NAME : name;
    }

    private void applyCitizensSkin(NPC npc, NpcKind kind, String skinOwner) {
        if (npc == null) {
            return;
        }
        String owner = resolveCitizensSkinOwner(kind, skinOwner);
        Object skinTrait = resolveSkinTrait(npc);
        if (kind == NpcKind.CLICK_TO_PLAY && skinTrait != null) {
            disableSkinTraitAutoUpdates(skinTrait);
        }
        if (skinTrait != null) {
            applySkinTraitName(skinTrait, owner, kind == NpcKind.PROFILE);
        }
        if (kind == NpcKind.CLICK_TO_PLAY) {
            setNpcMetadataPersistent(npc, NPC.Metadata.PLAYER_SKIN_USE_LATEST, Boolean.FALSE);
        } else {
            setNpcMetadataPersistent(npc, NPC.Metadata.PLAYER_SKIN_USE_LATEST, Boolean.TRUE);
        }
        setNpcMetadataPersistent(npc, NPC.Metadata.PLAYER_SKIN_UUID, owner);
        setNpcDataPersistent(npc, "cached-skin-uuid-name", owner);
        setNpcDataPersistent(npc, "player-skin-name", owner);
        // Clear any stale texture snapshot metadata from older implementations.
        setNpcDataPersistent(npc, "cached-texture", "");
        setNpcDataPersistent(npc, "cached-signature", "");
    }

    private String resolveCitizensSkinOwner(NpcKind kind, String skinOwner) {
        String owner = safeText(skinOwner).replaceAll("\\s+", "");
        if (kind == NpcKind.CLICK_TO_PLAY) {
            owner = DEFAULT_CLICK_TO_PLAY_SKIN;
        }
        if (owner.isEmpty()) {
            owner = kind == NpcKind.CLICK_TO_PLAY ? DEFAULT_CLICK_TO_PLAY_SKIN : "Steve";
        }
        if (owner.length() > 16) {
            owner = owner.substring(0, 16);
        }
        return owner.isEmpty() ? "Steve" : owner;
    }

    private Object resolveSkinTrait(NPC npc) {
        if (npc == null) {
            return null;
        }
        try {
            Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            return getOrAddTrait.invoke(npc, skinTraitClass);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean applySkinTraitName(Object skinTrait, String owner, boolean forceRefresh) {
        if (skinTrait == null) {
            return false;
        }
        if (forceRefresh) {
            try {
                Method setSkinName = skinTrait.getClass().getMethod("setSkinName", String.class, boolean.class);
                setSkinName.invoke(skinTrait, owner, Boolean.TRUE);
                return true;
            } catch (Exception ignored) {
                // Fall through to legacy variants.
            }
        }
        try {
            Method setSkinName = skinTrait.getClass().getMethod("setSkinName", String.class);
            setSkinName.invoke(skinTrait, owner);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void disableSkinTraitAutoUpdates(Object skinTrait) {
        if (skinTrait == null) {
            return;
        }
        setSkinTraitBoolean(skinTrait, false,
                "setShouldUpdateSkins",
                "setUpdateSkins",
                "setUseLatestSkin");
    }

    private void setSkinTraitBoolean(Object skinTrait, boolean value, String... methodNames) {
        if (skinTrait == null || methodNames == null) {
            return;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isEmpty()) {
                continue;
            }
            try {
                Method method = skinTrait.getClass().getMethod(methodName, boolean.class);
                method.invoke(skinTrait, Boolean.valueOf(value));
                return;
            } catch (Exception ignored) {
                // Try next method variant.
            }
        }
    }

    private void hideNpcIdentity(NPC npc, Entity entity) {
        if (npc != null) {
            setNpcMetadataPersistent(npc, NPC.Metadata.NAMEPLATE_VISIBLE, Boolean.FALSE);
            setNpcMetadataPersistent(npc, NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, Boolean.FALSE);
            setNpcDataPersistent(npc, CITIZENS_NAMEPLATE_VISIBLE_KEY, Boolean.FALSE);
            setNpcDataPersistent(npc, "nameplateVisible", Boolean.FALSE);
            setNpcDataPersistent(npc, "nametag-visible", Boolean.FALSE);
            applyNameplateTraitVisibility(npc, false);
            try {
                npc.setAlwaysUseNameHologram(false);
                npc.updateCustomName();
            } catch (Exception ignored) {
                // Citizens API behavior differs by server version.
            }
        }
        if (entity != null) {
            entity.setCustomNameVisible(false);
            entity.setCustomName("");
        }
    }

    private void applyNameplateTraitVisibility(NPC npc, boolean visible) {
        if (npc == null) {
            return;
        }
        applyTraitBoolean(npc, "net.citizensnpcs.trait.NameplateTrait", visible,
                "setVisible",
                "setNameVisible",
                "setNameplateVisible");
        applyTraitBoolean(npc, "net.citizensnpcs.trait.HologramTrait", visible,
                "setNameVisible",
                "setVisible",
                "setUseNameHologram");
    }

    private void applyTraitBoolean(NPC npc, String traitClassName, boolean value, String... methodNames) {
        if (npc == null || traitClassName == null || traitClassName.isEmpty() || methodNames == null) {
            return;
        }
        try {
            Class<?> traitClass = Class.forName(traitClassName);
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            Object trait = getOrAddTrait.invoke(npc, traitClass);
            if (trait == null) {
                return;
            }
            for (String methodName : methodNames) {
                if (methodName == null || methodName.isEmpty()) {
                    continue;
                }
                try {
                    Method method = traitClass.getMethod(methodName, boolean.class);
                    method.invoke(trait, Boolean.valueOf(value));
                    return;
                } catch (Exception ignored) {
                    // Try next method variant.
                }
            }
        } catch (Exception ignored) {
            // Trait class is optional across Citizens versions.
        }
    }

    private void setNpcDataPersistent(NPC npc, String key, Object value) {
        if (npc == null || key == null || key.isEmpty()) {
            return;
        }
        Object data;
        try {
            data = npc.data();
        } catch (Exception ignored) {
            return;
        }
        if (data == null) {
            return;
        }
        Method target = findDataSetter(data.getClass(), value);
        if (target == null) {
            return;
        }
        try {
            target.invoke(data, key, value);
        } catch (Exception ignored) {
            // Metadata setter is best-effort only.
        }
    }

    private void setNpcMetadataPersistent(NPC npc, NPC.Metadata metadata, Object value) {
        if (npc == null || metadata == null) {
            return;
        }
        try {
            npc.data().setPersistent(metadata, value);
            return;
        } catch (Exception ignored) {
            // Fallback to string-key metadata for compatibility.
        }
        setNpcDataPersistent(npc, metadata.getKey(), value);
    }

    private Method findDataSetter(Class<?> dataClass, Object value) {
        if (dataClass == null) {
            return null;
        }
        for (Method method : dataClass.getMethods()) {
            if (method == null) {
                continue;
            }
            String name = method.getName();
            if (!"setPersistent".equals(name) && !"set".equals(name)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 || params[0] != String.class) {
                continue;
            }
            if (!supportsValueType(params[1], value)) {
                continue;
            }
            return method;
        }
        return null;
    }

    private boolean supportsValueType(Class<?> parameterType, Object value) {
        if (parameterType == null) {
            return false;
        }
        if (value == null) {
            return !parameterType.isPrimitive();
        }
        Class<?> valueType = value.getClass();
        if (parameterType.isAssignableFrom(valueType)) {
            return true;
        }
        if (!parameterType.isPrimitive()) {
            return false;
        }
        Class<?> wrapped = wrapPrimitive(parameterType);
        return wrapped != null && wrapped.isAssignableFrom(valueType);
    }

    private Class<?> wrapPrimitive(Class<?> primitive) {
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return null;
    }

    private void deregisterNpc(NPC npc) {
        if (npcRegistry == null || npc == null) {
            return;
        }
        Method[] methods = npcRegistry.getClass().getMethods();
        for (Method method : methods) {
            if (method == null || !"deregister".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].isAssignableFrom(npc.getClass())) {
                continue;
            }
            try {
                method.invoke(npcRegistry, npc);
            } catch (Exception ignored) {
                // Ignore and let entity-removal cleanup happen.
            }
            return;
        }
    }

    private void applyNpcHologramLines(RuntimeNpc runtime, List<String> lines) {
        if (runtime == null || runtime.anchorUuid == null) {
            return;
        }
        List<String> resolved = lines == null ? Collections.<String>emptyList() : lines;
        if (resolved.isEmpty()) {
            clearNpcHologramLines(runtime);
            return;
        }
        if (updateNpcHologramLines(runtime, resolved)) {
            return;
        }
        Entity anchor = resolveEntity(runtime.anchorUuid);
        if (anchor == null || anchor.getLocation() == null || anchor.getLocation().getWorld() == null) {
            return;
        }

        List<UUID> freshLineUuids = new ArrayList<UUID>();
        Map<UUID, Integer> freshLineNpcIds = new HashMap<UUID, Integer>();
        UUID freshPrimary = null;
        Location anchorLocation = anchor.getLocation().clone();
        for (int index = 0; index < resolved.size(); index++) {
            String line = resolved.get(index);
            Location lineLocation = resolveHologramLineLocation(runtime, anchorLocation, resolved.size(), index);
            if (lineLocation == null) {
                clearSpawnedHologramLines(freshLineUuids, freshLineNpcIds);
                return;
            }
            HologramLineSpawn spawnedLine = spawnHologramLine(runtime, lineLocation, line);
            if (spawnedLine == null || spawnedLine.entityUuid == null) {
                clearSpawnedHologramLines(freshLineUuids, freshLineNpcIds);
                return;
            }
            freshLineUuids.add(spawnedLine.entityUuid);
            if (spawnedLine.npcId > 0) {
                freshLineNpcIds.put(spawnedLine.entityUuid, Integer.valueOf(spawnedLine.npcId));
            }
            if (freshPrimary == null) {
                freshPrimary = spawnedLine.entityUuid;
            }
        }

        clearNpcHologramLines(runtime);
        runtime.hologramLineUuids = freshLineUuids;
        runtime.hologramLineNpcIds = freshLineNpcIds;
        runtime.hologramUuid = freshPrimary;
        for (UUID lineUuid : freshLineUuids) {
            linkedEntityToAnchor.put(lineUuid, runtime.anchorUuid);
        }
    }

    private void clearSpawnedHologramLines(List<UUID> lineUuids, Map<UUID, Integer> lineNpcIds) {
        if (lineUuids == null || lineUuids.isEmpty()) {
            return;
        }
        for (UUID lineUuid : new ArrayList<UUID>(lineUuids)) {
            Integer npcId = lineNpcIds == null ? null : lineNpcIds.get(lineUuid);
            if (npcId != null && npcId.intValue() > 0) {
                removeNpcById(npcId.intValue());
            } else {
                removeEntity(lineUuid);
            }
            linkedEntityToAnchor.remove(lineUuid);
        }
        lineUuids.clear();
        if (lineNpcIds != null) {
            lineNpcIds.clear();
        }
    }

    private boolean updateNpcHologramLines(RuntimeNpc runtime, List<String> lines) {
        if (runtime == null || lines == null || runtime.hologramLineUuids == null) {
            return false;
        }
        if (runtime.hologramLineUuids.size() != lines.size()) {
            return false;
        }
        Entity anchor = resolveEntity(runtime.anchorUuid);
        if (anchor == null || anchor.getLocation() == null || anchor.getLocation().getWorld() == null) {
            return false;
        }
        Location anchorLocation = anchor.getLocation().clone();
        for (int index = 0; index < lines.size(); index++) {
            String text = lines.get(index) == null ? "" : lines.get(index);
            UUID lineUuid = runtime.hologramLineUuids.get(index);
            Integer npcId = runtime.hologramLineNpcIds == null ? null : runtime.hologramLineNpcIds.get(lineUuid);
            Entity entity = resolveEntity(lineUuid);
            NPC lineNpc = npcId != null && npcId.intValue() > 0 ? resolveNpcById(npcId.intValue()) : null;
            if (lineNpc != null) {
                updateViewerScopedHologramNpcName(lineNpc, text);
                configureViewerScopedHologramNpc(lineNpc);
                if (runtime.profileViewerUuid != null) {
                    applyProfileViewerFilter(lineNpc, runtime.profileViewerUuid);
                }
            }
            if (!(entity instanceof ArmorStand) && lineNpc != null) {
                Entity npcEntity = lineNpc.getEntity();
                if (npcEntity instanceof ArmorStand && npcEntity.getUniqueId() != null) {
                    UUID freshUuid = npcEntity.getUniqueId();
                    if (!freshUuid.equals(lineUuid)) {
                        runtime.hologramLineUuids.set(index, freshUuid);
                        runtime.hologramLineNpcIds.remove(lineUuid);
                        runtime.hologramLineNpcIds.put(freshUuid, npcId);
                        linkedEntityToAnchor.remove(lineUuid);
                        linkedEntityToAnchor.put(freshUuid, runtime.anchorUuid);
                        if (runtime.hologramUuid != null && runtime.hologramUuid.equals(lineUuid)) {
                            runtime.hologramUuid = freshUuid;
                        }
                        lineUuid = freshUuid;
                    }
                    entity = npcEntity;
                }
            }
            if (!(entity instanceof ArmorStand)) {
                return false;
            }
            ArmorStand stand = (ArmorStand) entity;
            configureHologramStand(stand, text);
            Location target = resolveHologramLineLocation(runtime, anchorLocation, lines.size(), index);
            if (target != null && shouldSyncHologramLocation(stand.getLocation(), target)) {
                stand.teleport(target);
            }
            linkedEntityToAnchor.put(lineUuid, runtime.anchorUuid);
        }
        runtime.hologramUuid = runtime.hologramLineUuids.isEmpty() ? null : runtime.hologramLineUuids.get(0);
        return true;
    }

    private HologramLineSpawn spawnHologramLine(RuntimeNpc runtime, Location location, String line) {
        if (runtime != null && runtime.kind == NpcKind.PROFILE && runtime.profileViewerUuid != null) {
            HologramLineSpawn scoped = spawnViewerScopedHologramLine(location, line, runtime.profileViewerUuid);
            return scoped;
        }
        UUID uuid = spawnGlobalHologramLine(location, line);
        if (uuid == null) {
            return null;
        }
        return new HologramLineSpawn(uuid, -1);
    }

    private UUID spawnGlobalHologramLine(Location location, String line) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class);
        configureHologramStand(stand, line == null ? "" : line);
        return stand.getUniqueId();
    }

    private HologramLineSpawn spawnViewerScopedHologramLine(Location location, String line, UUID viewerUuid) {
        if (location == null || location.getWorld() == null || viewerUuid == null || npcRegistry == null) {
            return null;
        }
        NPC hologramNpc;
        String hologramName = resolveViewerScopedHologramName(line);
        try {
            hologramNpc = npcRegistry.createNPC(org.bukkit.entity.EntityType.ARMOR_STAND, hologramName);
        } catch (Exception ignored) {
            return null;
        }
        if (hologramNpc == null) {
            return null;
        }
        try {
            hologramNpc.setProtected(true);
        } catch (Exception ignored) {
            // Keep spawning even if metadata cannot be applied.
        }
        try {
            Method setShouldSave = hologramNpc.getClass().getMethod("setShouldSave", boolean.class);
            setShouldSave.invoke(hologramNpc, Boolean.FALSE);
        } catch (Exception ignored) {
            // Citizens versions differ; best-effort only.
        }
        configureViewerScopedHologramNpc(hologramNpc);
        updateViewerScopedHologramNpcName(hologramNpc, line);
        applyProfileViewerFilter(hologramNpc, viewerUuid);
        try {
            if (!hologramNpc.spawn(location.clone())) {
                deregisterNpc(hologramNpc);
                return null;
            }
            updateViewerScopedHologramNpcName(hologramNpc, line);
            applyProfileViewerFilter(hologramNpc, viewerUuid);
            configureViewerScopedHologramNpc(hologramNpc);
        } catch (Exception ignored) {
            deregisterNpc(hologramNpc);
            return null;
        }
        Entity entity = hologramNpc.getEntity();
        if (!(entity instanceof ArmorStand) || entity.getUniqueId() == null) {
            deregisterNpc(hologramNpc);
            return null;
        }
        ArmorStand stand = (ArmorStand) entity;
        configureHologramStand(stand, line == null ? "" : line);
        return new HologramLineSpawn(stand.getUniqueId(), hologramNpc.getId());
    }

    private void configureViewerScopedHologramNpc(NPC npc) {
        if (npc == null) {
            return;
        }
        setNpcDataPersistent(npc, "gravity", Boolean.FALSE);
        setNpcDataPersistent(npc, "visible", Boolean.FALSE);
        setNpcDataPersistent(npc, "baseplate", Boolean.FALSE);
        setNpcDataPersistent(npc, "basePlate", Boolean.FALSE);
        setNpcDataPersistent(npc, "arms", Boolean.FALSE);
        applyArmorStandTraitBoolean(npc, false, "setVisible");
        applyArmorStandTraitBoolean(npc, false, "setGravity", "setHasGravity", "setUseGravity");
        applyArmorStandTraitBoolean(npc, false, "setHasBaseplate", "setBaseplate", "setBasePlate");
        applyArmorStandTraitBoolean(npc, false, "setHasArms", "setArms");
    }

    private String resolveViewerScopedHologramName(String line) {
        String name = safeText(line);
        return name.isEmpty() ? CITIZENS_DEFAULT_NAME : name;
    }

    private void updateViewerScopedHologramNpcName(NPC npc, String line) {
        if (npc == null) {
            return;
        }
        String name = resolveViewerScopedHologramName(line);
        try {
            npc.setName(name);
        } catch (Exception ignored) {
            // Citizens versions differ; keep rendering best-effort.
        }
        try {
            npc.updateCustomName();
        } catch (Exception ignored) {
            // Available on newer Citizens builds only.
        }
    }

    private void invokeNoArgMethod(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isEmpty()) {
                continue;
            }
            try {
                Method method = target.getClass().getMethod(methodName);
                method.invoke(target);
            } catch (Exception ignored) {
                // Try next method variant.
            }
        }
    }

    private boolean invokeSingleArgMethod(Object target, Object arg, String... methodNames) {
        if (target == null || arg == null || methodNames == null) {
            return false;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isEmpty()) {
                continue;
            }
            Method[] methods = target.getClass().getMethods();
            for (Method method : methods) {
                if (method == null || !methodName.equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !supportsValueType(params[0], arg)) {
                    continue;
                }
                try {
                    method.invoke(target, arg);
                    return true;
                } catch (Exception ignored) {
                    // Try next method variant.
                }
            }
        }
        return false;
    }

    private void applyArmorStandTraitBoolean(NPC npc, boolean value, String... methodNames) {
        applyTraitBoolean(npc, "net.citizensnpcs.trait.ArmorStandTrait", value, methodNames);
        applyTraitBoolean(npc, "net.citizensnpcs.trait.versioned.ArmorStandTrait", value, methodNames);
    }

    private void configureHologramStand(ArmorStand stand, String text) {
        if (stand == null) {
            return;
        }
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setBasePlate(false);
        stand.setCanPickupItems(false);
        stand.setCustomNameVisible(true);
        String value = text == null ? "" : text;
        if (!value.equals(stand.getCustomName())) {
            stand.setCustomName(value);
        }
    }

    private Location resolveHologramLineLocation(RuntimeNpc runtime, Location anchorLocation, int lineCount, int index) {
        if (runtime == null || anchorLocation == null || anchorLocation.getWorld() == null || lineCount <= 0) {
            return null;
        }
        if (index < 0 || index >= lineCount) {
            return null;
        }
        double bottomOffset = runtime.kind == NpcKind.PROFILE
                ? PROFILE_HOLOGRAM_BOTTOM_Y_OFFSET
                : CLICK_TO_PLAY_HOLOGRAM_BOTTOM_Y_OFFSET;
        double firstLineOffset = bottomOffset + ((lineCount - 1) * HOLOGRAM_LINE_SPACING);
        double lineOffset = firstLineOffset - (index * HOLOGRAM_LINE_SPACING);
        return anchorLocation.clone().add(0.0d, lineOffset, 0.0d);
    }

    private boolean shouldSyncHologramLocation(Location current, Location target) {
        if (current == null || target == null) {
            return true;
        }
        if (current.getWorld() == null || target.getWorld() == null) {
            return true;
        }
        if (!current.getWorld().equals(target.getWorld())) {
            return true;
        }
        return current.distanceSquared(target) > 0.04d;
    }

    private void clearNpcHologramLines(RuntimeNpc runtime) {
        if (runtime == null) {
            return;
        }
        if (runtime.hologramLineUuids != null) {
            for (UUID lineUuid : runtime.hologramLineUuids) {
                Integer npcId = runtime.hologramLineNpcIds == null ? null : runtime.hologramLineNpcIds.remove(lineUuid);
                if (npcId != null && npcId.intValue() > 0) {
                    removeNpcById(npcId.intValue());
                } else {
                    removeEntity(lineUuid);
                }
                linkedEntityToAnchor.remove(lineUuid);
            }
            runtime.hologramLineUuids.clear();
        }
        if (runtime.hologramUuid != null) {
            Integer npcId = runtime.hologramLineNpcIds == null ? null : runtime.hologramLineNpcIds.get(runtime.hologramUuid);
            if (npcId != null && npcId.intValue() > 0) {
                removeNpcById(npcId.intValue());
            } else {
                removeEntity(runtime.hologramUuid);
            }
            linkedEntityToAnchor.remove(runtime.hologramUuid);
            runtime.hologramUuid = null;
        }
        if (runtime.hologramLineNpcIds != null) {
            runtime.hologramLineNpcIds.clear();
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

    private void despawnAll() {
        List<RuntimeNpc> snapshot = new ArrayList<RuntimeNpc>(npcsById.values());
        if (snapshot.isEmpty()) {
            snapshot = new ArrayList<RuntimeNpc>(npcsByEntityUuid.values());
        }
        for (RuntimeNpc runtime : snapshot) {
            if (runtime == null) {
                continue;
            }
            clearNpcHologramLines(runtime);
            removeRuntimeNpc(runtime);
        }
        npcsById.clear();
        npcsByEntityUuid.clear();
        linkedEntityToAnchor.clear();
        profileNpcsByViewer.clear();
        profileNpcTemplates.clear();
    }

    private void removeRuntimeNpc(RuntimeNpc runtime) {
        if (runtime == null) {
            return;
        }
        NPC npc = runtime.npc;
        if (npc != null) {
            Entity entity = npc.getEntity();
            if (entity != null) {
                entity.remove();
            }
            deregisterNpc(npc);
        }
        removeEntity(runtime.anchorUuid);
    }

    private void removeEntity(UUID uuid) {
        if (uuid == null) {
            return;
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
                    entity.remove();
                    return;
                }
            }
        }
    }

    private void removeNpcById(int npcId) {
        if (npcId <= 0 || npcRegistry == null) {
            return;
        }
        NPC npc = resolveNpcById(npcId);
        if (npc == null) {
            return;
        }
        Entity entity = npc.getEntity();
        if (entity != null) {
            entity.remove();
        }
        deregisterNpc(npc);
    }

    private NPC resolveNpcById(int npcId) {
        if (npcId <= 0 || npcRegistry == null) {
            return null;
        }
        try {
            return npcRegistry.getById(npcId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Location parseLocation(Object rawLocation) {
        Document locationDoc = asDocument(rawLocation);
        if (locationDoc != null) {
            World world = resolveWorld(safeText(locationDoc.get("world")));
            Double x = readDouble(locationDoc.get("x"));
            Double y = readDouble(locationDoc.get("y"));
            Double z = readDouble(locationDoc.get("z"));
            if (world != null && x != null && y != null && z != null) {
                float yaw = readFloat(locationDoc.get("yaw"), 0.0f);
                float pitch = readFloat(locationDoc.get("pitch"), 0.0f);
                return new Location(world, x, y, z, yaw, pitch);
            }
            Object nested = locationDoc.get("location");
            if (nested != null && nested != rawLocation) {
                Location nestedLocation = parseLocation(nested);
                if (nestedLocation != null) {
                    return nestedLocation;
                }
            }
            return null;
        }

        String serialized = rawLocation instanceof String ? safeText(rawLocation) : "";
        if (serialized.isEmpty()) {
            return null;
        }
        String[] parts = serialized.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = resolveWorld(parts[0]);
        if (world == null) {
            return null;
        }
        Double x = readDouble(parts[1]);
        Double y = readDouble(parts[2]);
        Double z = readDouble(parts[3]);
        if (x == null || y == null || z == null) {
            return null;
        }
        float yaw = parts.length > 4 ? readFloat(parts[4], 0.0f) : 0.0f;
        float pitch = parts.length > 5 ? readFloat(parts[5], 0.0f) : 0.0f;
        return new Location(world, x, y, z, yaw, pitch);
    }

    private Location normalizeNpcRotation(Location location) {
        if (location == null) {
            return null;
        }
        Location normalized = location.clone();
        // Keep configured facing direction (yaw) and force level head pitch.
        normalized.setPitch(0.0f);
        return normalized;
    }

    private boolean shouldSyncRotation(Location current, Location target) {
        if (current == null || target == null) {
            return false;
        }
        float yawDelta = Math.abs(wrapDegrees(current.getYaw() - target.getYaw()));
        float pitchDelta = Math.abs(current.getPitch() - target.getPitch());
        return yawDelta > 1.0f || pitchDelta > 1.0f;
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    private World resolveWorld(String worldName) {
        String target = safeText(worldName);
        if (!target.isEmpty()) {
            World world = Bukkit.getWorld(target);
            if (world != null) {
                return world;
            }
            for (World current : Bukkit.getWorlds()) {
                if (current != null && target.equalsIgnoreCase(current.getName())) {
                    return current;
                }
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    private List<String> resolveGameKeyCandidates() {
        List<String> candidates = new ArrayList<String>();
        String configured = corePlugin == null || corePlugin.getConfig() == null
                ? ""
                : corePlugin.getConfig().getString("server.group", "");
        addGameKeyCandidate(candidates, configured);
        if (serverType != null) {
            addGameKeyCandidate(candidates, serverType.getGameTypeDisplayName());
            String typeName = safeText(serverType.name());
            if (typeName.endsWith("_HUB")) {
                typeName = typeName.substring(0, typeName.length() - "_HUB".length());
            }
            addGameKeyCandidate(candidates, typeName);
        }
        addGameKeyCandidate(candidates, MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY);
        if (candidates.isEmpty()) {
            candidates.add(MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY);
        }
        return candidates;
    }

    private void addGameKeyCandidate(List<String> candidates, String raw) {
        if (candidates == null) {
            return;
        }
        String normalized = MapConfigStore.normalizeGameKey(raw);
        if (normalized.isEmpty() || containsIgnoreCase(candidates, normalized)) {
            return;
        }
        candidates.add(normalized);
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

    private boolean shouldReloadForGameKey(String updatedKey) {
        String normalized = MapConfigStore.normalizeGameKey(updatedKey);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.equalsIgnoreCase(safeText(activeGameKey))) {
            return true;
        }
        return containsIgnoreCase(resolveGameKeyCandidates(), normalized);
    }

    private ResolvedHubMap resolveHubMap() {
        ResolvedHubMap best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String gameKey : resolveGameKeyCandidates()) {
            if (gameKey == null || gameKey.trim().isEmpty()) {
                continue;
            }
            Document root = loadRoot(gameKey);
            if (root == null) {
                continue;
            }
            Document gameSection = resolveGameSection(root, gameKey);
            if (gameSection == null && !MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY.equals(gameKey)) {
                gameSection = resolveGameSection(root, MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY);
            }
            if (gameSection == null) {
                continue;
            }
            Document map = selectHubMap(gameSection);
            if (map == null) {
                continue;
            }
            int score = mapPreferenceScore(map);
            if (best == null || score > bestScore) {
                best = new ResolvedHubMap(gameKey, map);
                bestScore = score;
            }
            if (score >= 1000) {
                break;
            }
        }
        return best;
    }

    private int mapPreferenceScore(Document map) {
        if (map == null) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        int parsedNpcs = countParsedNpcEntries(map);
        if (parsedNpcs > 0) {
            score += 1000 + parsedNpcs;
        } else if (hasNpcData(map)) {
            score += 100;
        }
        if (hasHubLocationData(map)) {
            score += 10;
        }
        if (isHubMap(map)) {
            score += 1;
        }
        return score;
    }

    private int countParsedNpcEntries(Document map) {
        if (map == null) {
            return 0;
        }
        int parsed = 0;
        for (Object rawNpc : asObjectList(map.get("npcs"))) {
            Location location = parseLocation(rawNpc);
            if (location != null && location.getWorld() != null) {
                parsed++;
            }
        }
        for (Object rawNpc : asObjectList(map.get("profileNpcs"))) {
            Location location = parseLocation(rawNpc);
            if (location != null && location.getWorld() != null) {
                parsed++;
            }
        }
        return parsed;
    }

    private Document loadRoot(String gameKey) {
        MongoCollection<Document> maps = corePlugin.getMongoManager().getCollection(MongoManager.MAPS_COLLECTION);
        if (maps == null || gameKey == null || gameKey.trim().isEmpty()) {
            return null;
        }
        return maps.find(eq("_id", gameKey)).first();
    }

    private Document resolveGameSection(Document root, String gameKey) {
        if (root == null || gameKey == null || gameKey.trim().isEmpty()) {
            return null;
        }
        Document gameTypes = asDocument(root.get("gameTypes"));
        Document section = asDocument(gameTypes == null ? null : gameTypes.get(gameKey));
        if (section != null) {
            return section;
        }
        section = asDocument(root.get(gameKey));
        if (section != null) {
            return section;
        }
        if (gameTypes != null) {
            section = asDocument(gameTypes.get(MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY));
            if (section != null) {
                return section;
            }
        }
        if (root.get("maps") instanceof List<?>) {
            return root;
        }
        return null;
    }

    private Document selectHubMap(Document gameSection) {
        List<Document> maps = asDocumentList(gameSection == null ? null : gameSection.get("maps"));
        if (maps.isEmpty()) {
            return null;
        }

        List<String> candidates = new ArrayList<String>();
        addRuntimeMapCandidates(candidates);
        addCandidate(candidates, gameSection == null ? null : gameSection.get("activeMap"));
        for (Object raw : asObjectList(gameSection == null ? null : gameSection.get("rotation"))) {
            addCandidate(candidates, raw);
        }

        for (String candidate : candidates) {
            Document match = findHubMapByName(maps, candidate);
            if (match != null) {
                return match;
            }
        }
        for (Document map : maps) {
            if (hasNpcData(map)) {
                return map;
            }
        }
        for (Document map : maps) {
            if (hasHubLocationData(map)) {
                return map;
            }
        }
        for (Document map : maps) {
            if (isHubMap(map)) {
                return map;
            }
        }
        return maps.get(0);
    }

    private boolean hasNpcData(Document map) {
        if (map == null) {
            return false;
        }
        return !asObjectList(map.get("npcs")).isEmpty()
                || !asObjectList(map.get("profileNpcs")).isEmpty();
    }

    private boolean hasHubLocationData(Document map) {
        if (map == null) {
            return false;
        }
        return map.get("hubSpawn") != null;
    }

    private void addRuntimeMapCandidates(List<String> target) {
        if (target == null) {
            return;
        }
        String markerMap = readMapNameFromWorldMarker();
        if (!markerMap.isEmpty()) {
            addCandidate(target, markerMap);
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null || world.getName() == null) {
                continue;
            }
            addCandidate(target, world.getName());
        }
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
                // Best-effort marker read only.
            }
        }
        return "";
    }

    private void addCandidate(List<String> target, Object raw) {
        String value = safeText(raw);
        if (value.isEmpty() || target.contains(value)) {
            return;
        }
        target.add(value);
    }

    private Document findHubMapByName(List<Document> maps, String candidate) {
        String needle = safeText(candidate);
        if (needle.isEmpty() || maps == null) {
            return null;
        }
        for (Document map : maps) {
            if (map == null || (!isHubMap(map) && !hasNpcData(map) && !hasHubLocationData(map))) {
                continue;
            }
            String worldDirectory = safeText(map.get("worldDirectory"));
            String displayName = safeText(map.get("name"));
            if (needle.equalsIgnoreCase(worldDirectory) || needle.equalsIgnoreCase(displayName)) {
                return map;
            }
        }
        return null;
    }

    private boolean isHubMap(Document map) {
        if (map == null) {
            return false;
        }
        String worldDirectory = safeText(map.get("worldDirectory"));
        if (!worldDirectory.isEmpty()) {
            return MapConfigStore.isHubMapName(worldDirectory);
        }
        return MapConfigStore.isHubMapName(safeText(map.get("name")));
    }

    private Double readDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        String text = safeText(raw);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private float readFloat(Object raw, float fallback) {
        Double value = readDouble(raw);
        return value == null ? fallback : value.floatValue();
    }

    private String safeText(Object raw) {
        if (raw == null) {
            return "";
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? "" : value;
    }

    private List<Object> asObjectList(Object raw) {
        if (!(raw instanceof List<?>)) {
            return Collections.emptyList();
        }
        return new ArrayList<Object>((List<?>) raw);
    }

    private List<Document> asDocumentList(Object raw) {
        List<Object> values = asObjectList(raw);
        if (values.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> documents = new ArrayList<Document>();
        for (Object value : values) {
            Document document = asDocument(value);
            if (document != null) {
                documents.add(document);
            }
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private Document asDocument(Object raw) {
        if (raw instanceof Document) {
            return (Document) raw;
        }
        if (raw instanceof Map<?, ?>) {
            return new Document((Map<String, Object>) raw);
        }
        return null;
    }

    private enum NpcKind {
        CLICK_TO_PLAY,
        PROFILE
    }

    private static final class RuntimeNpc {
        private NpcKind kind;
        private String skinOwner;
        private Location spawnLocation;
        private int npcId = -1;
        private NPC npc;
        private UUID anchorUuid;
        private UUID profileViewerUuid;
        private UUID hologramUuid;
        private List<UUID> hologramLineUuids;
        private Map<UUID, Integer> hologramLineNpcIds;
        private int lastClickToPlayPlayers = -1;
    }

    private static final class HologramLineSpawn {
        private final UUID entityUuid;
        private final int npcId;

        private HologramLineSpawn(UUID entityUuid, int npcId) {
            this.entityUuid = entityUuid;
            this.npcId = npcId;
        }
    }

    private static final class ProfileNpcTemplate {
        private final Location location;
        private final String fallbackSkinOwner;

        private ProfileNpcTemplate(Location location, String fallbackSkinOwner) {
            this.location = location;
            this.fallbackSkinOwner = fallbackSkinOwner;
        }
    }

    private static final class ResolvedHubMap {
        private final String gameKey;
        private final Document map;

        private ResolvedHubMap(String gameKey, Document map) {
            this.gameKey = gameKey == null ? MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY : gameKey;
            this.map = map;
        }
    }
}
