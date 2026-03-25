package io.github.mebsic.core.listener;

import com.mongodb.client.MongoCollection;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.HubParkourCommandHandler;
import io.github.mebsic.core.store.MapConfigStore;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class HubParkourListener implements Listener, HubParkourCommandHandler {
    private static final String MAPS_COLLECTION = "maps";
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    private static final String MAP_PARKOUR_TITLE_COLOR_KEY = "titleColor";
    private static final String MAP_PARKOUR_START_COLOR_KEY = "startColor";
    private static final String MAP_PARKOUR_CHECKPOINT_COLOR_KEY = "checkpointColor";
    private static final String MAP_PARKOUR_END_COLOR_KEY = "endColor";
    private static final String PARKOUR_BEST_COUNTER_PREFIX = "parkour.best_ms.";
    private static final String PARKOUR_LAST_COUNTER_PREFIX = "parkour.last_ms.";
    private static final String PARKOUR_COMPLETION_COUNTER_PREFIX = "parkour.completions.";
    private static final double HOLOGRAM_LINE_SPACING = 0.30d;
    private static final double PARKOUR_HOLOGRAM_BASE_Y_OFFSET = -0.80d;
    private static final long FINISH_LINE_HINT_COOLDOWN_MS = 1500L;
    private static final long START_TOUCH_SUPPRESS_MS = 750L;
    private static final String START_MESSAGE = ChatColor.GREEN.toString() + ChatColor.BOLD + "Parkour challenge started!";
    private static final String RESET_MESSAGE_PREFIX = ChatColor.GREEN.toString() + ChatColor.BOLD + "Reset your timer to ";
    private static final String RESET_MESSAGE_SUFFIX = ChatColor.GREEN.toString() + ChatColor.BOLD + "! Get to the finish line!";
    private static final String CANCEL_MESSAGE = ChatColor.RED.toString() + ChatColor.BOLD + "Parkour challenge cancelled!";
    private static final String FLY_FAIL_MESSAGE = ChatColor.RED.toString() + ChatColor.BOLD + "Parkour challenge failed! Do not fly!";
    private static final String FINISH_LINE_HINT_MESSAGE = ChatColor.GREEN.toString() + ChatColor.BOLD
            + "This is the finish line for the parkour! Get to the start line and climb back up here!";
    private static final String NOT_IN_RACE_MESSAGE =
            ChatColor.RED + "You are currently not in a parkour race. Use /parkour start";
    private static final String START_HINT_MESSAGE = ChatColor.GREEN + "Use "
            + ChatColor.YELLOW + "/parkour checkpoint"
            + ChatColor.GREEN + " to teleport to the last checkpoint or "
            + ChatColor.YELLOW + "/parkour cancel"
            + ChatColor.GREEN + " to cancel!";
    private static final String CHECKPOINT_ITEM_NAME = ChatColor.GREEN + "Teleport to Last Checkpoint";
    private static final String RESET_ITEM_NAME = ChatColor.RED + "Reset";
    private static final String CANCEL_ITEM_NAME = ChatColor.RED + "Cancel";
    private static final int CHECKPOINT_ITEM_SLOT = 3;
    private static final int RESET_ITEM_SLOT = 4;
    private static final int CANCEL_ITEM_SLOT = 5;

    private final Plugin plugin;
    private final CorePlugin corePlugin;
    private final ServerType serverType;
    private volatile List<ParkourRoute> routes;
    private volatile Map<String, ParkourRoute> routesById;
    private final Map<UUID, ActiveRun> activeRuns;
    private final Map<UUID, Long> finishLineHintAtByPlayer;
    private final Map<UUID, Long> startTouchSuppressUntilByPlayer;
    private final List<ParkourMarkerBlock> markerBlocks;
    private final List<UUID> markerHologramUuids;
    private final String bestTimeCounterKey;
    private final String lastTimeCounterKey;
    private final String completionCounterKey;
    private volatile String activeGameKey;

    public HubParkourListener(Plugin plugin, CorePlugin corePlugin, ServerType serverType) {
        this.plugin = plugin;
        this.corePlugin = corePlugin;
        this.serverType = serverType == null ? ServerType.UNKNOWN : serverType;
        this.activeRuns = new ConcurrentHashMap<UUID, ActiveRun>();
        this.finishLineHintAtByPlayer = new ConcurrentHashMap<UUID, Long>();
        this.startTouchSuppressUntilByPlayer = new ConcurrentHashMap<UUID, Long>();
        this.markerBlocks = new ArrayList<ParkourMarkerBlock>();
        this.markerHologramUuids = new ArrayList<UUID>();

        String typeKey = this.serverType.name().toLowerCase(Locale.ROOT);
        this.bestTimeCounterKey = PARKOUR_BEST_COUNTER_PREFIX + typeKey;
        this.lastTimeCounterKey = PARKOUR_LAST_COUNTER_PREFIX + typeKey;
        this.completionCounterKey = PARKOUR_COMPLETION_COUNTER_PREFIX + typeKey;
        this.activeGameKey = MapConfigStore.DEFAULT_GAME_KEY;

        this.routes = Collections.emptyList();
        this.routesById = Collections.emptyMap();
        reloadRoutes();
        subscribeToMapConfigUpdates();
    }

    public void shutdown() {
        cancelAllActiveRuns(false);
        finishLineHintAtByPlayer.clear();
        startTouchSuppressUntilByPlayer.clear();
        despawnVisualMarkers();
    }

    public void reloadRoutes() {
        List<ParkourRoute> loaded = loadParkourRoutes();
        if (loaded.isEmpty() && !this.routes.isEmpty()) {
            plugin.getLogger().warning("Hub parkour reload produced no routes; keeping existing routes.");
            return;
        }
        Map<String, ParkourRoute> mapped = new ConcurrentHashMap<String, ParkourRoute>();
        for (ParkourRoute route : loaded) {
            if (route == null || route.id == null || route.id.trim().isEmpty()) {
                continue;
            }
            mapped.put(route.id, route);
        }
        this.routes = Collections.unmodifiableList(new ArrayList<ParkourRoute>(loaded));
        this.routesById = Collections.unmodifiableMap(mapped);
        cancelAllActiveRuns(true);
        finishLineHintAtByPlayer.clear();
        startTouchSuppressUntilByPlayer.clear();
        respawnVisualMarkers(loaded);
        plugin.getLogger().info("Loaded " + this.routes.size() + " hub parkour route(s) for " + this.serverType.name() + ".");
    }

    private void respawnVisualMarkers(List<ParkourRoute> loaded) {
        despawnVisualMarkers();
        if (loaded == null || loaded.isEmpty()) {
            return;
        }
        for (ParkourRoute route : loaded) {
            if (route == null) {
                continue;
            }
            spawnMarker(route.start, parkourStartHologramLines(route), false);
            for (int i = 0; i < route.checkpoints.size(); i++) {
                ParkourPoint checkpoint = route.checkpoints.get(i);
                spawnMarker(checkpoint, parkourCheckpointHologramLines(route, i + 1), true);
            }
            spawnMarker(route.end, parkourEndHologramLines(route), false);
        }
    }

    private void spawnMarker(ParkourPoint point, List<String> hologramLines, boolean checkpoint) {
        if (point == null) {
            return;
        }
        World world = resolveWorldForPoint(point);
        if (world == null) {
            return;
        }
        Location blockLocation = new Location(world, point.blockX, point.blockY, point.blockZ);
        blockLocation.getBlock().setType(pressurePlateMaterial(checkpoint));
        markerBlocks.add(new ParkourMarkerBlock(world.getName(), point.blockX, point.blockY, point.blockZ));

        List<String> lines = hologramLines == null ? Collections.<String>emptyList() : hologramLines;
        Location current = blockLocation.clone().add(0.5d, PARKOUR_HOLOGRAM_BASE_Y_OFFSET, 0.5d);
        for (String line : lines) {
            UUID lineUuid = spawnHologramLine(current, line);
            if (lineUuid != null) {
                markerHologramUuids.add(lineUuid);
            }
            current.subtract(0.0d, HOLOGRAM_LINE_SPACING, 0.0d);
        }
    }

    private void despawnVisualMarkers() {
        for (UUID hologramUuid : new ArrayList<UUID>(markerHologramUuids)) {
            removeEntity(hologramUuid);
        }
        markerHologramUuids.clear();

        for (ParkourMarkerBlock markerBlock : new ArrayList<ParkourMarkerBlock>(markerBlocks)) {
            if (markerBlock == null) {
                continue;
            }
            World world = resolveWorldByName(markerBlock.world);
            if (world == null) {
                continue;
            }
            Material current = world.getBlockAt(markerBlock.x, markerBlock.y, markerBlock.z).getType();
            if (isManagedMarkerMaterial(current)) {
                world.getBlockAt(markerBlock.x, markerBlock.y, markerBlock.z).setType(Material.AIR);
            }
        }
        markerBlocks.clear();
    }

    private UUID spawnHologramLine(Location location, String line) {
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

    private World resolveWorldForPoint(ParkourPoint point) {
        if (point == null) {
            return null;
        }
        String expected = safeText(point.world);
        if (!expected.isEmpty()) {
            World direct = Bukkit.getWorld(expected);
            if (direct != null) {
                return direct;
            }
            for (World world : Bukkit.getWorlds()) {
                if (world != null && expected.equalsIgnoreCase(world.getName())) {
                    return world;
                }
            }
        }
        World defaultWorld = Bukkit.getWorld("world");
        if (defaultWorld != null) {
            return defaultWorld;
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    private World resolveWorldByName(String worldName) {
        String target = safeText(worldName);
        if (target.isEmpty()) {
            return null;
        }
        World direct = Bukkit.getWorld(target);
        if (direct != null) {
            return direct;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world != null && target.equalsIgnoreCase(world.getName())) {
                return world;
            }
        }
        return null;
    }

    private List<String> parkourStartHologramLines(ParkourRoute route) {
        List<String> lines = new ArrayList<String>(2);
        ChatColor titleColor = route == null ? ChatColor.YELLOW : route.titleColor;
        ChatColor startColor = route == null ? ChatColor.GREEN : route.startColor;
        lines.add(titleColor.toString() + ChatColor.BOLD + "Parkour Challenge");
        lines.add(startColor.toString() + ChatColor.BOLD + "Start");
        return lines;
    }

    private List<String> parkourCheckpointHologramLines(ParkourRoute route, int checkpointNumber) {
        List<String> lines = new ArrayList<String>(2);
        ChatColor titleColor = route == null ? ChatColor.YELLOW : route.titleColor;
        ChatColor checkpointColor = route == null ? ChatColor.AQUA : route.checkpointColor;
        lines.add(titleColor.toString() + ChatColor.BOLD + "Checkpoint");
        int index = Math.max(1, checkpointNumber);
        lines.add(checkpointColor.toString() + ChatColor.BOLD + "#" + index);
        return lines;
    }

    private List<String> parkourEndHologramLines(ParkourRoute route) {
        List<String> lines = new ArrayList<String>(2);
        ChatColor titleColor = route == null ? ChatColor.YELLOW : route.titleColor;
        ChatColor endColor = route == null ? ChatColor.RED : route.endColor;
        lines.add(titleColor.toString() + ChatColor.BOLD + "Parkour Challenge");
        lines.add(endColor.toString() + ChatColor.BOLD + "End");
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

    private boolean isManagedMarkerMaterial(Material type) {
        if (type == null) {
            return false;
        }
        String name = type.name();
        return "LIGHT_WEIGHTED_PRESSURE_PLATE".equals(name)
                || "GOLD_PLATE".equals(name)
                || "HEAVY_WEIGHTED_PRESSURE_PLATE".equals(name)
                || "IRON_PLATE".equals(name)
                || "GOLD_BLOCK".equals(name)
                || "IRON_BLOCK".equals(name);
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
        plugin.getServer().getScheduler().runTask(plugin, this::reloadRoutes);
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event == null || routes.isEmpty()) {
            return;
        }
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || sameBlock(from, to)) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null || !player.isOnline() || !serverType.isHub()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        ActiveRun active = activeRuns.get(uuid);
        if (active != null && to.getY() < 0.0d) {
            ParkourRoute route = activeRoute(active);
            if (route == null) {
                cancelRun(player, CANCEL_MESSAGE);
                return;
            }
            ParkourPoint recoveryPoint = voidRecoveryPoint(active, route);
            Location recovery = exactLocation(recoveryPoint);
            if (recovery != null) {
                event.setTo(recovery);
                player.setFallDistance(0.0f);
                return;
            }
        }

        long now = System.currentTimeMillis();
        Long suppressUntil = startTouchSuppressUntilByPlayer.get(uuid);
        if (suppressUntil != null && suppressUntil <= now) {
            startTouchSuppressUntilByPlayer.remove(uuid);
            suppressUntil = null;
        }

        ParkourRoute touchedStart = touchedRouteStart(to);
        if (touchedStart != null) {
            if (suppressUntil != null && suppressUntil > now) {
                return;
            }
            startRun(player, touchedStart, active != null);
            return;
        }

        if (active == null) {
            ParkourRoute touchedEnd = touchedRouteEnd(to);
            if (touchedEnd != null) {
                sendFinishLineHint(player);
            }
            return;
        }
        ParkourRoute route = activeRoute(active);
        if (route == null) {
            cancelRun(player, CANCEL_MESSAGE);
            return;
        }
        if (player.isFlying()) {
            failRun(player);
            return;
        }

        if (active.nextCheckpointIndex < route.checkpoints.size()) {
            ParkourPoint checkpoint = route.checkpoints.get(active.nextCheckpointIndex);
            if (isOnPoint(to, checkpoint)) {
                handleCheckpointReached(player, active);
                return;
            }
        }

        if (!isOnPoint(to, route.end)) {
            return;
        }
        if (active.nextCheckpointIndex < route.checkpoints.size()) {
            int remaining = route.checkpoints.size() - active.nextCheckpointIndex;
            player.sendMessage(ChatColor.RED + "You still need " + remaining + " checkpoint" + (remaining == 1 ? "" : "s") + ".");
            return;
        }

        completeRun(player, active);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ActiveRun run = activeRuns.remove(uuid);
        finishLineHintAtByPlayer.remove(uuid);
        startTouchSuppressUntilByPlayer.remove(uuid);
        restorePostRunState(player, uuid, run);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (event == null || event.getPlayer() == null || !event.isFlying()) {
            return;
        }
        if (!activeRuns.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        failRun(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            return;
        }
        Player player = event.getPlayer();
        if (!activeRuns.containsKey(player.getUniqueId())) {
            return;
        }

        ItemStack item = event.getItem();
        if (isRunItemTrigger(player, item, CHECKPOINT_ITEM_NAME, CHECKPOINT_ITEM_SLOT, runCheckpointItemMaterial())) {
            event.setCancelled(true);
            handleParkourCheckpoint(player);
            return;
        }
        if (isRunItemTrigger(player, item, RESET_ITEM_NAME, RESET_ITEM_SLOT, runResetItemMaterial())) {
            event.setCancelled(true);
            handleParkourReset(player);
            return;
        }
        if (isRunItemTrigger(player, item, CANCEL_ITEM_NAME, CANCEL_ITEM_SLOT, runCancelItemMaterial())) {
            event.setCancelled(true);
            handleParkourCancel(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        String raw = event.getMessage();
        String label = commandLabel(raw);
        if (label.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        if (!serverType.isHub()) {
            return;
        }
        if (isFlyCommandLabel(label) && activeRuns.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            failRun(player);
        }
    }

    private void startRun(Player player, ParkourRoute route, boolean reset) {
        if (player == null || route == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ActiveRun run = activeRuns.get(uuid);
        boolean freshRun = run == null;
        if (freshRun) {
            run = new ActiveRun();
            activeRuns.put(uuid, run);
            applyParkourStartState(player, run);
        }

        long now = System.currentTimeMillis();
        run.routeId = route.id;
        run.startedAtMillis = now;
        run.lastSplitAtMillis = now;
        run.nextCheckpointIndex = 0;

        if (!freshRun || reset) {
            player.sendMessage(RESET_MESSAGE_PREFIX + "00:00" + RESET_MESSAGE_SUFFIX);
            return;
        }
        player.sendMessage(START_MESSAGE);
        player.sendMessage(START_HINT_MESSAGE);
    }

    private void handleCheckpointReached(Player player, ActiveRun run) {
        if (player == null || run == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int elapsedMillis = toMillis(now - run.startedAtMillis);
        int splitMillis = toMillis(now - run.lastSplitAtMillis);
        run.lastSplitAtMillis = now;
        run.nextCheckpointIndex++;

        int reached = run.nextCheckpointIndex;
        player.sendMessage(ChatColor.GREEN.toString() + ChatColor.BOLD + "You reached "
                + ChatColor.YELLOW + ChatColor.BOLD + "Checkpoint #" + reached
                + ChatColor.GREEN + ChatColor.BOLD + " after "
                + ChatColor.YELLOW + ChatColor.BOLD + formatDuration(elapsedMillis)
                + ChatColor.GREEN + ChatColor.BOLD + ".");
        player.sendMessage(ChatColor.GRAY + "You finished this part of the parkour in "
                + ChatColor.GOLD + formatDuration(splitMillis) + ChatColor.GRAY + ".");
    }

    private void completeRun(Player player, ActiveRun run) {
        if (player == null || run == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ActiveRun removed = activeRuns.remove(uuid);
        if (removed != null) {
            run = removed;
        }

        long now = System.currentTimeMillis();
        int elapsedMillis = toMillis(now - run.startedAtMillis);
        int splitMillis = toMillis(now - run.lastSplitAtMillis);
        boolean persisted = false;

        Profile profile = corePlugin == null ? null : corePlugin.getProfile(uuid);
        if (profile != null) {
            Stats stats = profile.getStats();
            int previousBest = stats.getCustomCounter(bestTimeCounterKey);
            if (previousBest <= 0 || elapsedMillis < previousBest) {
                setCounter(stats, bestTimeCounterKey, elapsedMillis);
            }
            setCounter(stats, lastTimeCounterKey, elapsedMillis);
            stats.addCustomCounter(completionCounterKey, 1);
            corePlugin.saveProfile(profile);
            persisted = true;
        }

        player.sendMessage(ChatColor.GREEN.toString() + ChatColor.BOLD + "Congratulations on completing the parkour!");
        player.sendMessage(ChatColor.GREEN.toString() + ChatColor.BOLD + "You finished in "
                + ChatColor.YELLOW + ChatColor.BOLD + formatDuration(elapsedMillis)
                + ChatColor.GREEN + ChatColor.BOLD + "! Try again to get an even better record!");
        player.sendMessage(ChatColor.GRAY + "You finished this part of the parkour in "
                + ChatColor.GOLD + formatDuration(splitMillis) + ChatColor.GRAY + ".");
        if (!persisted) {
            player.sendMessage(ChatColor.RED + "Profile is still loading, parkour stats were not saved yet.");
        }
        restorePostRunState(player, uuid, run);
    }

    private void cancelRun(Player player, String message) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ActiveRun run = activeRuns.remove(uuid);
        if (run == null) {
            return;
        }
        restorePostRunState(player, uuid, run);
        if (message != null && !message.trim().isEmpty()) {
            player.sendMessage(message);
        }
    }

    private void failRun(Player player) {
        cancelRun(player, FLY_FAIL_MESSAGE);
    }

    private void cancelAllActiveRuns(boolean notifyPlayers) {
        for (Map.Entry<UUID, ActiveRun> entry : new ArrayList<Map.Entry<UUID, ActiveRun>>(activeRuns.entrySet())) {
            UUID uuid = entry.getKey();
            ActiveRun run = activeRuns.remove(uuid);
            if (run == null) {
                continue;
            }
            Player online = Bukkit.getPlayer(uuid);
            restorePostRunState(online, uuid, run);
            if (notifyPlayers && online != null && online.isOnline()) {
                online.sendMessage(CANCEL_MESSAGE);
            }
        }
    }

    private void applyParkourStartState(Player player, ActiveRun run) {
        if (player == null || run == null) {
            return;
        }
        run.previousAllowFlight = player.getAllowFlight();
        run.previousFlying = player.isFlying();

        Profile profile = corePlugin == null ? null : corePlugin.getProfile(player.getUniqueId());
        run.previousRank = resolveRankForRestore(player.getUniqueId(), profile);
        if (profile != null) {
            run.hadProfileFlightState = true;
            run.previousProfileFlightEnabled = profile.isFlightEnabled();
        }

        clearPotionEffects(player);

        if (run.hadProfileFlightState && corePlugin != null) {
            corePlugin.setFlightEnabled(player.getUniqueId(), false);
        } else {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        giveRunItems(player, run);
    }

    private void restorePostRunState(Player player, UUID uuid, ActiveRun run) {
        if (run == null || uuid == null) {
            return;
        }
        if (player != null) {
            restoreRunItems(player, run);
        }
        boolean restoredProfileFlight = false;
        if (run.hadProfileFlightState && corePlugin != null) {
            restoredProfileFlight = corePlugin.setFlightEnabled(uuid, run.previousProfileFlightEnabled);
        }
        if (!restoredProfileFlight && player != null) {
            player.setAllowFlight(run.previousAllowFlight);
            player.setFlying(run.previousAllowFlight && run.previousFlying);
        }
        if (player != null) {
            restoreHubSpeedState(player, uuid, run);
        }
    }

    private void clearPotionEffects(Player player) {
        if (player == null) {
            return;
        }
        for (PotionEffect effect : new ArrayList<PotionEffect>(player.getActivePotionEffects())) {
            if (effect == null || effect.getType() == null) {
                continue;
            }
            player.removePotionEffect(effect.getType());
        }
    }

    private void giveRunItems(Player player, ActiveRun run) {
        if (player == null || run == null) {
            return;
        }
        run.previousCheckpointItem = cloneItem(player.getInventory().getItem(CHECKPOINT_ITEM_SLOT));
        run.previousResetItem = cloneItem(player.getInventory().getItem(RESET_ITEM_SLOT));
        run.previousCancelItem = cloneItem(player.getInventory().getItem(CANCEL_ITEM_SLOT));

        player.getInventory().setItem(CHECKPOINT_ITEM_SLOT, runItem(runCheckpointItemMaterial(), CHECKPOINT_ITEM_NAME));
        player.getInventory().setItem(RESET_ITEM_SLOT, runItem(runResetItemMaterial(), RESET_ITEM_NAME));
        player.getInventory().setItem(CANCEL_ITEM_SLOT, runItem(runCancelItemMaterial(), CANCEL_ITEM_NAME));
        player.updateInventory();
    }

    private void restoreRunItems(Player player, ActiveRun run) {
        if (player == null || run == null) {
            return;
        }

        ItemStack currentCheckpoint = player.getInventory().getItem(CHECKPOINT_ITEM_SLOT);
        if (run.previousCheckpointItem != null || isRunItem(currentCheckpoint, CHECKPOINT_ITEM_NAME)) {
            player.getInventory().setItem(CHECKPOINT_ITEM_SLOT, cloneItem(run.previousCheckpointItem));
        }
        ItemStack currentReset = player.getInventory().getItem(RESET_ITEM_SLOT);
        if (run.previousResetItem != null || isRunItem(currentReset, RESET_ITEM_NAME)) {
            player.getInventory().setItem(RESET_ITEM_SLOT, cloneItem(run.previousResetItem));
        }
        ItemStack currentCancel = player.getInventory().getItem(CANCEL_ITEM_SLOT);
        if (run.previousCancelItem != null || isRunItem(currentCancel, CANCEL_ITEM_NAME)) {
            player.getInventory().setItem(CANCEL_ITEM_SLOT, cloneItem(run.previousCancelItem));
        }
        player.updateInventory();
    }

    private ItemStack cloneItem(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.clone();
    }

    private ItemStack runItem(Material material, String displayName) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private Material runCheckpointItemMaterial() {
        return firstMaterial("HEAVY_WEIGHTED_PRESSURE_PLATE", "IRON_PLATE", "IRON_BLOCK");
    }

    private Material runResetItemMaterial() {
        return firstMaterial(
                "WOOD_DOOR",
                "OAK_DOOR",
                "SPRUCE_DOOR",
                "BIRCH_DOOR",
                "JUNGLE_DOOR",
                "ACACIA_DOOR",
                "DARK_OAK_DOOR",
                "IRON_DOOR",
                "IRON_DOOR_ITEM",
                "WOOD");
    }

    private Material runCancelItemMaterial() {
        return firstMaterial("BED", "RED_BED", "WHITE_BED", "WOOL");
    }

    private Material firstMaterial(String... names) {
        if (names != null) {
            for (String name : names) {
                Material found = Material.matchMaterial(name);
                if (found != null) {
                    return found;
                }
            }
        }
        Material fallback = Material.matchMaterial("STONE");
        return fallback == null ? Material.AIR : fallback;
    }

    private boolean isRunItem(ItemStack item, String expectedName) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) {
            return false;
        }
        return expectedName.equals(meta.getDisplayName());
    }

    private boolean isRunItemTrigger(Player player,
                                     ItemStack item,
                                     String expectedName,
                                     int expectedSlot,
                                     Material expectedType) {
        if (isRunItem(item, expectedName)) {
            return true;
        }
        if (player == null || item == null || expectedType == null) {
            return false;
        }
        if (player.getInventory().getHeldItemSlot() != expectedSlot) {
            return false;
        }
        return item.getType() == expectedType;
    }

    private boolean isFlyCommandLabel(String commandLabel) {
        if (commandLabel == null || commandLabel.isEmpty()) {
            return false;
        }
        return "/fly".equals(commandLabel) || commandLabel.endsWith(":fly");
    }

    private String commandLabel(String message) {
        String raw = safeText(message);
        if (raw.isEmpty() || raw.charAt(0) != '/') {
            return "";
        }
        int split = raw.indexOf(' ');
        if (split <= 0) {
            return raw.toLowerCase(Locale.ROOT);
        }
        return raw.substring(0, split).toLowerCase(Locale.ROOT);
    }

    @Override
    public void handleParkourCommand(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (!serverType.isHub()) {
            return;
        }
        String subcommand = args != null && args.length > 0
                ? safeText(args[0]).toLowerCase(Locale.ROOT)
                : "";
        if ("start".equals(subcommand)) {
            handleParkourStart(player);
            return;
        }
        if ("reset".equals(subcommand)) {
            handleParkourReset(player);
            return;
        }
        if ("checkpoint".equals(subcommand)) {
            handleParkourCheckpoint(player);
            return;
        }
        if ("cancel".equals(subcommand)) {
            handleParkourCancel(player);
            return;
        }
        sendParkourCommandHelp(player);
    }

    private void handleParkourStart(Player player) {
        if (player == null) {
            return;
        }
        ActiveRun active = activeRuns.get(player.getUniqueId());
        ParkourRoute route = active == null ? resolveRouteForPlayer(player) : activeRoute(active);
        if (route == null) {
            player.sendMessage(ChatColor.RED + "No parkour route is currently available.");
            return;
        }
        if (active != null) {
            teleportToPoint(player, route.start);
            startRun(player, route, true);
            return;
        }
        if (!isOnPoint(player.getLocation(), route.start)) {
            teleportToPoint(player, route.start);
        }
        startRun(player, route, false);
    }

    private void handleParkourReset(Player player) {
        if (player == null) {
            return;
        }
        ActiveRun active = activeRuns.get(player.getUniqueId());
        if (active == null) {
            player.sendMessage(NOT_IN_RACE_MESSAGE);
            return;
        }
        ParkourRoute route = activeRoute(active);
        if (route == null) {
            cancelRun(player, CANCEL_MESSAGE);
            return;
        }
        suppressStartTouch(player.getUniqueId());
        teleportToPointExact(player, route.start);
        startRun(player, route, true);
    }

    private void handleParkourCheckpoint(Player player) {
        if (player == null) {
            return;
        }
        ActiveRun active = activeRuns.get(player.getUniqueId());
        if (active == null) {
            player.sendMessage(NOT_IN_RACE_MESSAGE);
            return;
        }
        ParkourRoute route = activeRoute(active);
        if (route == null) {
            cancelRun(player, CANCEL_MESSAGE);
            return;
        }
        int checkpointIndex = active.nextCheckpointIndex - 1;
        if (checkpointIndex < 0 || checkpointIndex >= route.checkpoints.size()) {
            teleportToPointExact(player, route.start);
            player.sendMessage(ChatColor.GREEN + "Teleported to the parkour start.");
            return;
        }
        teleportToPointExact(player, route.checkpoints.get(checkpointIndex));
        player.sendMessage(ChatColor.GREEN + "Teleported to " + ChatColor.YELLOW + "Checkpoint #"
                + (checkpointIndex + 1) + ChatColor.GREEN + ".");
    }

    private void handleParkourCancel(Player player) {
        if (player == null) {
            return;
        }
        ActiveRun active = activeRuns.get(player.getUniqueId());
        if (active == null) {
            player.sendMessage(NOT_IN_RACE_MESSAGE);
            return;
        }
        cancelRun(player, CANCEL_MESSAGE);
    }

    private void sendParkourCommandHelp(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.RED + "/parkour start");
        player.sendMessage(ChatColor.RED + "/parkour reset");
        player.sendMessage(ChatColor.RED + "/parkour checkpoint");
        player.sendMessage(ChatColor.RED + "/parkour cancel");
    }

    private void suppressStartTouch(UUID uuid) {
        if (uuid == null) {
            return;
        }
        startTouchSuppressUntilByPlayer.put(uuid, System.currentTimeMillis() + START_TOUCH_SUPPRESS_MS);
    }

    private ParkourRoute resolveRouteForPlayer(Player player) {
        if (player == null || routes.isEmpty()) {
            return null;
        }
        ParkourRoute touched = touchedRouteStart(player.getLocation());
        if (touched != null) {
            return touched;
        }
        if (routes.size() == 1) {
            return routes.get(0);
        }
        for (ParkourRoute route : routes) {
            if (route != null && worldMatches(route.start.world, player.getWorld())) {
                return route;
            }
        }
        return routes.get(0);
    }

    private ParkourRoute activeRoute(ActiveRun run) {
        if (run == null || run.routeId == null || run.routeId.trim().isEmpty()) {
            return null;
        }
        return routesById.get(run.routeId);
    }

    private void teleportToPoint(Player player, ParkourPoint point) {
        if (player == null || point == null) {
            return;
        }
        World world = resolveWorldForPoint(point);
        if (world == null) {
            return;
        }
        Location current = player.getLocation();
        float yaw = current == null ? 0.0f : current.getYaw();
        float pitch = current == null ? 0.0f : current.getPitch();
        Location target = new Location(world, point.blockX + 0.5d, point.blockY + 1.0d, point.blockZ + 0.5d, yaw, pitch);
        player.teleport(target);
    }

    private void teleportToPointExact(Player player, ParkourPoint point) {
        if (player == null || point == null) {
            return;
        }
        Location target = exactLocation(point);
        if (target == null) {
            return;
        }
        player.teleport(target);
    }

    private Location exactLocation(ParkourPoint point) {
        if (point == null) {
            return null;
        }
        World world = resolveWorldForPoint(point);
        if (world == null) {
            return null;
        }
        return new Location(world, point.x, point.y, point.z, 0.0f, 0.0f);
    }

    private ParkourPoint voidRecoveryPoint(ActiveRun active, ParkourRoute route) {
        if (active == null || route == null) {
            return null;
        }
        int checkpointIndex = active.nextCheckpointIndex - 1;
        if (checkpointIndex >= 0 && checkpointIndex < route.checkpoints.size()) {
            return route.checkpoints.get(checkpointIndex);
        }
        return route.start;
    }

    private Rank resolveRankForRestore(UUID uuid, Profile profile) {
        if (profile != null && profile.getRank() != null) {
            return profile.getRank();
        }
        if (corePlugin != null && uuid != null) {
            Rank rank = corePlugin.getRank(uuid);
            if (rank != null) {
                return rank;
            }
        }
        return Rank.DEFAULT;
    }

    private void restoreHubSpeedState(Player player, UUID uuid, ActiveRun run) {
        if (player == null || !serverType.isHub()) {
            return;
        }
        Rank rank = run == null ? null : run.previousRank;
        if (rank == null && corePlugin != null && uuid != null) {
            Profile profile = corePlugin.getProfile(uuid);
            if (profile != null && profile.getRank() != null) {
                rank = profile.getRank();
            }
        }
        if (rank == null) {
            rank = corePlugin == null || uuid == null ? Rank.DEFAULT : corePlugin.getRank(uuid);
        }
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        player.removePotionEffect(PotionEffectType.SPEED);
        int amplifier = rank.isAtLeast(Rank.MVP_PLUS_PLUS) ? 1 : 0;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, amplifier, false, false), true);
    }

    private int toMillis(long rawMillis) {
        if (rawMillis <= 0L) {
            return 0;
        }
        if (rawMillis > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) rawMillis;
    }

    private void setCounter(Stats stats, String key, int value) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return;
        }
        int current = stats.getCustomCounter(key);
        if (current > 0) {
            stats.addCustomCounter(key, -current);
        }
        if (value > 0) {
            stats.addCustomCounter(key, value);
        }
    }

    private String formatDuration(int millis) {
        long total = Math.max(0, millis);
        long minutes = total / 60000L;
        long seconds = (total % 60000L) / 1000L;
        long ms = total % 1000L;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, ms);
    }

    private ParkourRoute touchedRouteStart(Location location) {
        if (location == null) {
            return null;
        }
        for (ParkourRoute route : routes) {
            if (route == null) {
                continue;
            }
            if (isOnPoint(location, route.start)) {
                return route;
            }
        }
        return null;
    }

    private ParkourRoute touchedRouteEnd(Location location) {
        if (location == null) {
            return null;
        }
        for (ParkourRoute route : routes) {
            if (route == null) {
                continue;
            }
            if (isOnPoint(location, route.end)) {
                return route;
            }
        }
        return null;
    }

    private void sendFinishLineHint(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastHintAt = finishLineHintAtByPlayer.getOrDefault(uuid, 0L);
        if (now - lastHintAt < FINISH_LINE_HINT_COOLDOWN_MS) {
            return;
        }
        finishLineHintAtByPlayer.put(uuid, now);
        player.sendMessage(FINISH_LINE_HINT_MESSAGE);
    }

    private boolean sameBlock(Location left, Location right) {
        if (left == null || right == null || left.getWorld() == null || right.getWorld() == null) {
            return false;
        }
        if (!left.getWorld().getUID().equals(right.getWorld().getUID())) {
            return false;
        }
        return left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }

    private boolean isOnPoint(Location location, ParkourPoint point) {
        if (location == null || point == null || location.getWorld() == null) {
            return false;
        }
        if (!worldMatches(point.world, location.getWorld())) {
            return false;
        }
        if (location.getBlockX() != point.blockX || location.getBlockZ() != point.blockZ) {
            return false;
        }
        return Math.abs(location.getBlockY() - point.blockY) <= 1;
    }

    private boolean worldMatches(String expectedWorld, World actualWorld) {
        if (actualWorld == null) {
            return false;
        }
        String actual = safeText(actualWorld.getName()).toLowerCase(Locale.ROOT);
        String expected = safeText(expectedWorld).toLowerCase(Locale.ROOT);
        if (expected.isEmpty() || actual.isEmpty()) {
            return true;
        }
        if (expected.equals(actual)) {
            return true;
        }
        // Build maps may be edited under a different world folder name and copied into `world`.
        return "world".equals(expected) || "world".equals(actual);
    }

    private List<ParkourRoute> loadParkourRoutes() {
        if (corePlugin == null || corePlugin.getMongoManager() == null || !serverType.isHub()) {
            return Collections.emptyList();
        }
        ResolvedHubMap resolvedMap = resolveHubMap();
        if (resolvedMap == null || resolvedMap.map == null) {
            return Collections.emptyList();
        }
        this.activeGameKey = resolvedMap.gameKey;
        return parseRoutes(resolvedMap.map);
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
        addGameKeyCandidate(candidates, MapConfigStore.DEFAULT_GAME_KEY);
        if (candidates.isEmpty()) {
            candidates.add(MapConfigStore.DEFAULT_GAME_KEY);
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
            Document root = loadRootDocument(gameKey);
            if (root == null) {
                continue;
            }
            Document gameSection = resolveGameSection(root, gameKey);
            if (gameSection == null && !MapConfigStore.DEFAULT_GAME_KEY.equals(gameKey)) {
                gameSection = resolveGameSection(root, MapConfigStore.DEFAULT_GAME_KEY);
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
        int parsedRoutes = countParsedParkourRoutes(map);
        if (parsedRoutes > 0) {
            score += 1000 + parsedRoutes;
        } else if (hasParkourData(map)) {
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

    private int countParsedParkourRoutes(Document map) {
        if (map == null) {
            return 0;
        }
        return parseRoutes(map).size();
    }

    private Document loadRootDocument(String gameKey) {
        MongoCollection<Document> maps = corePlugin.getMongoManager().getCollection(MAPS_COLLECTION);
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
            section = asDocument(gameTypes.get(MapConfigStore.DEFAULT_GAME_KEY));
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
        for (Object rotationEntry : asObjectList(gameSection == null ? null : gameSection.get("rotation"))) {
            addCandidate(candidates, rotationEntry);
        }

        for (String candidate : candidates) {
            Document match = findHubMapByName(maps, candidate);
            if (match != null) {
                return match;
            }
        }

        for (Document map : maps) {
            if (hasParkourData(map)) {
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

    private boolean hasParkourData(Document map) {
        if (map == null) {
            return false;
        }
        return !asObjectList(map.get("parkours")).isEmpty();
    }

    private boolean hasHubLocationData(Document map) {
        if (map == null) {
            return false;
        }
        return map.get("hubSpawn") != null || map.get("lobbySpawn") != null;
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

    private void addCandidate(List<String> candidates, Object raw) {
        String value = safeText(raw);
        if (value.isEmpty() || candidates.contains(value)) {
            return;
        }
        candidates.add(value);
    }

    private Document findHubMapByName(List<Document> maps, String candidate) {
        String needle = safeText(candidate);
        if (needle.isEmpty() || maps == null) {
            return null;
        }
        for (Document map : maps) {
            if (map == null || (!isHubMap(map) && !hasParkourData(map) && !hasHubLocationData(map))) {
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

    private List<ParkourRoute> parseRoutes(Document map) {
        List<Document> rawRoutes = asDocumentList(map == null ? null : map.get("parkours"));
        if (rawRoutes.isEmpty()) {
            return Collections.emptyList();
        }
        List<ParkourRoute> parsed = new ArrayList<ParkourRoute>();
        for (int i = 0; i < rawRoutes.size(); i++) {
            Document rawRoute = rawRoutes.get(i);
            if (rawRoute == null) {
                continue;
            }
            ParkourPoint start = parsePoint(rawRoute.get("start"));
            ParkourPoint end = parsePoint(rawRoute.get("end"));
            if (start == null || end == null) {
                continue;
            }

            List<ParkourPoint> checkpoints = new ArrayList<ParkourPoint>();
            for (Object rawCheckpoint : asObjectList(rawRoute.get("checkpoints"))) {
                ParkourPoint checkpoint = parsePoint(rawCheckpoint);
                if (checkpoint != null) {
                    checkpoints.add(checkpoint);
                }
            }

            String routeId = safeText(rawRoute.get("entityId"));
            if (routeId.isEmpty()) {
                routeId = "route-" + i;
            }
            ChatColor titleColor = parseColor(rawRoute.get(MAP_PARKOUR_TITLE_COLOR_KEY), ChatColor.YELLOW);
            ChatColor startColor = parseColor(rawRoute.get(MAP_PARKOUR_START_COLOR_KEY), ChatColor.GREEN);
            ChatColor checkpointColor = parseColor(rawRoute.get(MAP_PARKOUR_CHECKPOINT_COLOR_KEY), ChatColor.AQUA);
            ChatColor endColor = parseColor(rawRoute.get(MAP_PARKOUR_END_COLOR_KEY), ChatColor.RED);
            parsed.add(new ParkourRoute(routeId, start, end, checkpoints, titleColor, startColor, checkpointColor, endColor));
        }
        return parsed;
    }

    private ChatColor parseColor(Object raw, ChatColor fallback) {
        String text = safeText(raw);
        if (text.isEmpty()) {
            return fallback;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        try {
            return ChatColor.valueOf(upper);
        } catch (Exception ignored) {
            // Fallback to legacy color code formats like "&a", "§a", or "a".
        }
        if (text.length() >= 2) {
            char first = text.charAt(0);
            if (first == '&' || first == ChatColor.COLOR_CHAR) {
                ChatColor byChar = ChatColor.getByChar(text.charAt(1));
                if (byChar != null) {
                    return byChar;
                }
            }
        }
        if (text.length() == 1) {
            ChatColor byChar = ChatColor.getByChar(text.charAt(0));
            if (byChar != null) {
                return byChar;
            }
        }
        return fallback;
    }

    private ParkourPoint parsePoint(Object raw) {
        Document doc = asDocument(raw);
        if (doc != null) {
            Double x = readDouble(doc.get("x"));
            Double y = readDouble(doc.get("y"));
            Double z = readDouble(doc.get("z"));
            if (x == null || y == null || z == null) {
                return null;
            }
            return new ParkourPoint(safeText(doc.get("world")), x, y, z);
        }
        String serialized = safeText(raw);
        if (serialized.isEmpty()) {
            return null;
        }
        String[] parts = serialized.split(",");
        if (parts.length < 4) {
            return null;
        }
        Double x = readDouble(parts[1]);
        Double y = readDouble(parts[2]);
        Double z = readDouble(parts[3]);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new ParkourPoint(safeText(parts[0]), x, y, z);
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
            Document doc = asDocument(value);
            if (doc != null) {
                documents.add(doc);
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

    private static final class ParkourRoute {
        private final String id;
        private final ParkourPoint start;
        private final ParkourPoint end;
        private final List<ParkourPoint> checkpoints;
        private final ChatColor titleColor;
        private final ChatColor startColor;
        private final ChatColor checkpointColor;
        private final ChatColor endColor;

        private ParkourRoute(String id,
                             ParkourPoint start,
                             ParkourPoint end,
                             List<ParkourPoint> checkpoints,
                             ChatColor titleColor,
                             ChatColor startColor,
                             ChatColor checkpointColor,
                             ChatColor endColor) {
            this.id = id;
            this.start = start;
            this.end = end;
            this.checkpoints = checkpoints == null ? Collections.<ParkourPoint>emptyList() : checkpoints;
            this.titleColor = titleColor == null ? ChatColor.YELLOW : titleColor;
            this.startColor = startColor == null ? ChatColor.GREEN : startColor;
            this.checkpointColor = checkpointColor == null ? ChatColor.AQUA : checkpointColor;
            this.endColor = endColor == null ? ChatColor.RED : endColor;
        }
    }

    private static final class ParkourPoint {
        private final String world;
        private final double x;
        private final double y;
        private final double z;
        private final int blockX;
        private final int blockY;
        private final int blockZ;

        private ParkourPoint(String world, double x, double y, double z) {
            this.world = world == null ? "" : world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockX = (int) Math.floor(x);
            this.blockY = (int) Math.floor(y);
            this.blockZ = (int) Math.floor(z);
        }
    }

    private static final class ActiveRun {
        private String routeId;
        private long startedAtMillis;
        private long lastSplitAtMillis;
        private int nextCheckpointIndex;
        private boolean previousAllowFlight;
        private boolean previousFlying;
        private boolean hadProfileFlightState;
        private boolean previousProfileFlightEnabled;
        private Rank previousRank;
        private ItemStack previousCheckpointItem;
        private ItemStack previousResetItem;
        private ItemStack previousCancelItem;
    }

    private static final class ParkourMarkerBlock {
        private final String world;
        private final int x;
        private final int y;
        private final int z;

        private ParkourMarkerBlock(String world, int x, int y, int z) {
            this.world = world == null ? "" : world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class ResolvedHubMap {
        private final String gameKey;
        private final Document map;

        private ResolvedHubMap(String gameKey, Document map) {
            this.gameKey = gameKey == null ? MapConfigStore.DEFAULT_GAME_KEY : gameKey;
            this.map = map;
        }
    }
}
