package io.github.mebsic.core.listener;

import com.mongodb.client.MongoCollection;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.store.MapConfigStore;
import io.github.mebsic.core.util.RankFormatUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongodb.client.model.Filters.eq;

public class HubLeaderboardListener implements Listener {
    private static final String MAPS_COLLECTION = "maps";
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    private static final String LEADERBOARD_METRIC_KILLS = "kills";
    private static final String LEADERBOARD_METRIC_WINS = "wins";
    private static final String LEADERBOARD_METRIC_WINS_AS_MURDERER = "wins_as_murderer";
    private static final String LEGACY_LEADERBOARD_METRIC_KILLS_AS_MURDERER = "kills_as_murderer";
    private static final String MURDER_MYSTERY_WINS_AS_MURDERER_KEY = "murdermystery.winsAsMurderer";
    private static final double HOLOGRAM_LINE_SPACING = 0.40d;
    private static final double LEADERBOARD_HOLOGRAM_BASE_Y_OFFSET = 2.4d;
    private static final double LEADERBOARD_HOLOGRAM_COLUMN_RADIUS_SQUARED = 0.04d;
    private static final double LEADERBOARD_HOLOGRAM_Y_TOLERANCE = 0.15d;
    private static final long LEADERBOARD_INITIAL_REFRESH_DELAY_TICKS = 1L;
    private static final long LEADERBOARD_REFRESH_INTERVAL_TICKS = 200L;
    private static final long LEADERBOARD_LOAD_RETRY_INTERVAL_TICKS = 5L;
    private static final int LEADERBOARD_LOAD_RETRY_MAX_ATTEMPTS = 40;
    private static final long LEADERBOARD_POST_JOIN_REFRESH_DELAY_TICKS = 2L;

    private final Plugin plugin;
    private final CorePlugin corePlugin;
    private final ServerType serverType;
    private final Map<UUID, RuntimeLeaderboard> leaderboardsByAnchor;
    private final AtomicBoolean refreshInFlight;
    private final BukkitTask refreshTask;
    private volatile String activeGameKey;
    private BukkitTask loadRetryTask;
    private BukkitTask joinRefreshTask;

    public HubLeaderboardListener(Plugin plugin, CorePlugin corePlugin, ServerType serverType) {
        this.plugin = plugin;
        this.corePlugin = corePlugin;
        this.serverType = serverType == null ? ServerType.UNKNOWN : serverType;
        this.leaderboardsByAnchor = new ConcurrentHashMap<UUID, RuntimeLeaderboard>();
        this.refreshInFlight = new AtomicBoolean(false);
        this.activeGameKey = MapConfigStore.DEFAULT_GAME_KEY;
        loadAndSpawn();
        subscribeToMapConfigUpdates();
        this.refreshTask = startRefreshTask();
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        cancelLoadRetryTask();
        cancelJoinRefreshTask();
        despawnAll();
    }

    private void loadAndSpawn() {
        if (corePlugin == null || corePlugin.getMongoManager() == null || !serverType.isHub()) {
            return;
        }

        ResolvedHubMap resolvedMap = resolveHubMap();
        if (resolvedMap == null || resolvedMap.map == null) {
            scheduleLoadRetryTask();
            return;
        }
        this.activeGameKey = resolvedMap.gameKey;
        Document map = resolvedMap.map;

        despawnAll();

        List<Object> rawBoards = asObjectList(map.get("leaderboards"));
        List<LeaderboardRefreshSnapshot> configuredBoards = new ArrayList<LeaderboardRefreshSnapshot>();
        for (Object rawBoard : rawBoards) {
            LeaderboardRefreshSnapshot board = parseConfiguredLeaderboard(rawBoard);
            if (board == null || board.location == null || board.location.getWorld() == null || board.entityId.isEmpty()) {
                continue;
            }
            configuredBoards.add(board);
        }

        int spawned = 0;
        for (LeaderboardRefreshSnapshot board : configuredBoards) {
            spawnRuntimeLeaderboard(
                    board.entityId,
                    board.location,
                    board.metric,
                    Collections.<LeaderboardEntry>emptyList()
            );
            spawned++;
        }
        plugin.getLogger().info("Loaded " + spawned + " hub leaderboard hologram(s) for " + serverType.name() + ".");
        if (spawned > 0) {
            cancelLoadRetryTask();
            scheduleImmediateRefresh();
            return;
        }
        if (!rawBoards.isEmpty()) {
            scheduleLoadRetryTask();
        }
    }

    private void scheduleImmediateRefresh() {
        if (plugin == null || !serverType.isHub()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::refreshLeaderboards);
    }

    private void scheduleLoadRetryTask() {
        if (plugin == null || !serverType.isHub() || loadRetryTask != null) {
            return;
        }
        final int[] attemptsRemaining = {LEADERBOARD_LOAD_RETRY_MAX_ATTEMPTS};
        loadRetryTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> {
                    if (!leaderboardsByAnchor.isEmpty()) {
                        cancelLoadRetryTask();
                        return;
                    }
                    if (attemptsRemaining[0] <= 0) {
                        cancelLoadRetryTask();
                        return;
                    }
                    attemptsRemaining[0]--;
                    loadAndSpawn();
                },
                LEADERBOARD_LOAD_RETRY_INTERVAL_TICKS,
                LEADERBOARD_LOAD_RETRY_INTERVAL_TICKS
        );
    }

    private void cancelLoadRetryTask() {
        if (loadRetryTask == null) {
            return;
        }
        loadRetryTask.cancel();
        loadRetryTask = null;
    }

    private void scheduleJoinRefreshTask() {
        if (plugin == null || !serverType.isHub() || joinRefreshTask != null) {
            return;
        }
        joinRefreshTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    joinRefreshTask = null;
                    if (leaderboardsByAnchor.isEmpty()) {
                        loadAndSpawn();
                        if (leaderboardsByAnchor.isEmpty()) {
                            scheduleLoadRetryTask();
                            return;
                        }
                    }
                    refreshLeaderboards();
                },
                LEADERBOARD_POST_JOIN_REFRESH_DELAY_TICKS
        );
    }

    private void cancelJoinRefreshTask() {
        if (joinRefreshTask == null) {
            return;
        }
        joinRefreshTask.cancel();
        joinRefreshTask = null;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (event == null || plugin == null || !serverType.isHub()) {
            return;
        }
        scheduleJoinRefreshTask();
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

    private LeaderboardRefreshSnapshot parseConfiguredLeaderboard(Object raw) {
        Document board = asDocument(raw);
        Location location = parseLocation(board == null ? raw : board);
        if (location == null || location.getWorld() == null) {
            return null;
        }
        String metric = normalizeMetric(safeText(board == null ? null : board.get("metric")));
        String entityId = safeText(board == null ? null : board.get("entityId"));
        if (entityId.isEmpty()) {
            entityId = UUID.randomUUID().toString();
        }
        return new LeaderboardRefreshSnapshot(entityId, location, metric);
    }

    private void spawnRuntimeLeaderboard(String entityId, Location location, String metric) {
        spawnRuntimeLeaderboard(entityId, location, metric, Collections.<LeaderboardEntry>emptyList());
    }

    private void spawnRuntimeLeaderboard(String entityId,
                                         Location location,
                                         String metric,
                                         List<LeaderboardEntry> rankedEntries) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        despawnLeaderboardByEntityId(entityId);
        RuntimeLeaderboard runtime = new RuntimeLeaderboard();
        runtime.entityId = safeText(entityId);
        runtime.metric = normalizeMetric(metric);
        runtime.lineUuids = new ArrayList<UUID>();
        runtime.baseLocation = location.clone();

        List<String> lines = buildLeaderboardLines(runtime, rankedEntries);
        clearLegacyLeaderboardLines(location, lines.size());
        Location current = location.clone().add(0.0d, LEADERBOARD_HOLOGRAM_BASE_Y_OFFSET, 0.0d);
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

    private List<String> buildLeaderboardLines(RuntimeLeaderboard runtime, List<LeaderboardEntry> rankedEntries) {
        String normalizedMetric = normalizeMetric(runtime == null ? "" : runtime.metric);
        List<String> lines = new ArrayList<String>();
        lines.add(ChatColor.AQUA.toString() + ChatColor.BOLD + ChatColor.UNDERLINE + leaderboardMetricLabel(normalizedMetric));
        lines.add(ChatColor.GRAY + modeNameForType(serverType));

        List<LeaderboardEntry> ranked = rankedEntries == null
                ? Collections.<LeaderboardEntry>emptyList()
                : rankedEntries;
        int rankIndex = 1;
        for (LeaderboardEntry entry : ranked) {
            if (rankIndex > 10) {
                break;
            }
            if (entry == null) {
                rankIndex++;
                continue;
            }
            lines.add(ChatColor.YELLOW + "" + rankIndex + ". "
                    + entry.nameColor + entry.name
                    + ChatColor.GRAY + " - "
                    + ChatColor.YELLOW + formatNumber(entry.score));
            rankIndex++;
        }
        while (rankIndex <= 10) {
            lines.add(ChatColor.YELLOW + "" + rankIndex + ". " + ChatColor.DARK_GRAY + "-");
            rankIndex++;
        }
        ViewerRecord viewerRecord = resolveViewerRecord(
                ranked,
                runtime == null ? null : runtime.baseLocation
        );
        if (viewerRecord != null && viewerRecord.position > 10) {
            lines.add(ChatColor.YELLOW + "" + viewerRecord.position + ". "
                    + viewerRecord.nameColor + ChatColor.UNDERLINE + viewerRecord.name
                    + ChatColor.GRAY + " - "
                    + ChatColor.YELLOW + formatNumber(viewerRecord.score));
        }
        lines.add(ChatColor.GRAY + "Never resets.");
        return lines;
    }

    private String modeNameForType(ServerType type) {
        String normalized = gameTypeToken(type);
        if ("murdermystery".equals(normalized)) {
            return "Classic";
        }
        return "Default";
    }

    private ViewerRecord resolveViewerRecord(List<LeaderboardEntry> ranked, Location boardLocation) {
        if (ranked == null || ranked.isEmpty()) {
            return null;
        }
        Player viewer = selectLeaderboardViewer(boardLocation);
        if (viewer == null || viewer.getUniqueId() == null) {
            return null;
        }
        UUID viewerUuid = viewer.getUniqueId();
        String viewerName = safeText(viewer.getName());
        int position = 1;
        for (LeaderboardEntry entry : ranked) {
            if (entry == null) {
                position++;
                continue;
            }
            boolean uuidMatch = entry.uuid != null && viewerUuid.equals(entry.uuid);
            boolean nameMatch = !viewerName.isEmpty() && viewerName.equalsIgnoreCase(entry.name);
            if (uuidMatch || nameMatch) {
                return new ViewerRecord(position, entry.name, entry.score, entry.nameColor);
            }
            position++;
        }
        return null;
    }

    private Player selectLeaderboardViewer(Location boardLocation) {
        if (plugin == null || plugin.getServer() == null) {
            return null;
        }
        if (boardLocation == null || boardLocation.getWorld() == null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player != null && player.isOnline()) {
                    return player;
                }
            }
            return null;
        }
        Player nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == null || !player.isOnline() || player.getLocation() == null || player.getWorld() == null) {
                continue;
            }
            if (!boardLocation.getWorld().equals(player.getWorld())) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(boardLocation);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private Map<String, List<LeaderboardEntry>> fetchLeaderboardEntriesByMetrics(Set<String> metrics) {
        if (metrics == null || metrics.isEmpty() || corePlugin == null || corePlugin.getMongoManager() == null) {
            return Collections.emptyMap();
        }
        MongoCollection<Document> profiles = corePlugin.getMongoManager().getProfiles();
        if (profiles == null) {
            return Collections.emptyMap();
        }

        Set<String> normalizedMetrics = new HashSet<String>();
        for (String metric : metrics) {
            normalizedMetrics.add(normalizeMetric(metric));
        }

        boolean includeKills = normalizedMetrics.contains(LEADERBOARD_METRIC_KILLS);
        boolean includeWins = normalizedMetrics.contains(LEADERBOARD_METRIC_WINS);
        boolean includeMurdererWins = normalizedMetrics.contains(LEADERBOARD_METRIC_WINS_AS_MURDERER);
        if (!includeKills && !includeWins && !includeMurdererWins) {
            return Collections.emptyMap();
        }

        Map<String, List<LeaderboardEntry>> entriesByMetric = new HashMap<String, List<LeaderboardEntry>>();
        if (includeKills) {
            entriesByMetric.put(LEADERBOARD_METRIC_KILLS, new ArrayList<LeaderboardEntry>());
        }
        if (includeWins) {
            entriesByMetric.put(LEADERBOARD_METRIC_WINS, new ArrayList<LeaderboardEntry>());
        }
        if (includeMurdererWins) {
            entriesByMetric.put(LEADERBOARD_METRIC_WINS_AS_MURDERER, new ArrayList<LeaderboardEntry>());
        }

        for (Document doc : profiles.find()) {
            if (doc == null) {
                continue;
            }
            UUID uuid = parseUuid(safeText(doc.get("uuid")));
            String name = safeText(doc.getString("name"));
            if (name.isEmpty()) {
                continue;
            }
            Document stats = doc.get("stats", Document.class);
            Rank rank = parseRank(doc.getString("rank"));
            String mvpPlusPlusPrefixColor = safeText(doc.getString("mvpPlusPlusPrefixColor"));
            ChatColor nameColor = RankFormatUtil.baseColor(
                    rank,
                    mvpPlusPlusPrefixColor.isEmpty() ? null : mvpPlusPlusPrefixColor
            );

            if (includeKills) {
                int kills = readKills(stats);
                if (kills > 0) {
                    entriesByMetric.get(LEADERBOARD_METRIC_KILLS).add(new LeaderboardEntry(uuid, name, kills, nameColor));
                }
            }
            if (includeWins) {
                int wins = readWins(stats);
                if (wins > 0) {
                    entriesByMetric.get(LEADERBOARD_METRIC_WINS).add(new LeaderboardEntry(uuid, name, wins, nameColor));
                }
            }
            if (includeMurdererWins) {
                int murdererWins = readMurdererWins(stats);
                if (murdererWins > 0) {
                    entriesByMetric.get(LEADERBOARD_METRIC_WINS_AS_MURDERER)
                            .add(new LeaderboardEntry(uuid, name, murdererWins, nameColor));
                }
            }
        }

        for (List<LeaderboardEntry> entries : entriesByMetric.values()) {
            entries.sort(Comparator
                    .comparingInt((LeaderboardEntry entry) -> entry.score)
                    .reversed()
                    .thenComparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        }
        return entriesByMetric;
    }

    private List<LeaderboardEntry> entriesForMetric(Map<String, List<LeaderboardEntry>> entriesByMetric, String metric) {
        if (entriesByMetric == null || entriesByMetric.isEmpty()) {
            return Collections.emptyList();
        }
        List<LeaderboardEntry> entries = entriesByMetric.get(normalizeMetric(metric));
        if (entries == null) {
            return Collections.emptyList();
        }
        return entries;
    }

    private UUID parseUuid(String raw) {
        String value = safeText(raw);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int readWins(Document stats) {
        if (stats == null) {
            return 0;
        }
        Object modern = stats.get("murderMysteryWins");
        if (modern instanceof Number) {
            return Math.max(0, ((Number) modern).intValue());
        }
        Object legacy = stats.get("wins");
        if (legacy instanceof Number) {
            return Math.max(0, ((Number) legacy).intValue());
        }
        return 0;
    }

    private int readKills(Document stats) {
        if (stats == null) {
            return 0;
        }
        Object modern = stats.get("murderMysteryKills");
        if (modern instanceof Number) {
            return Math.max(0, ((Number) modern).intValue());
        }
        Object legacy = stats.get("kills");
        if (legacy instanceof Number) {
            return Math.max(0, ((Number) legacy).intValue());
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

    private Rank parseRank(String raw) {
        String normalized = safeText(raw).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Rank.DEFAULT;
        }
        try {
            return Rank.valueOf(normalized);
        } catch (Exception ignored) {
            return Rank.DEFAULT;
        }
    }

    private String leaderboardMetricLabel(String metric) {
        String resolved = normalizeMetric(metric);
        if (LEADERBOARD_METRIC_WINS_AS_MURDERER.equals(resolved)) {
            return "Lifetime Wins as Murderer";
        }
        if (LEADERBOARD_METRIC_WINS.equals(resolved)) {
            return "Lifetime Wins";
        }
        return "Lifetime Kills";
    }

    private String normalizeMetric(String metric) {
        String normalized = safeText(metric).toLowerCase(Locale.ROOT);
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

    private String gameTypeToken(ServerType type) {
        String label = type == null ? "" : safeText(type.getGameTypeDisplayName());
        if (label.isEmpty() && type != null) {
            label = safeText(type.getId());
        }
        return label.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
    }

    private String formatNumber(int value) {
        return String.format(Locale.US, "%,d", Math.max(0, value));
    }

    private BukkitTask startRefreshTask() {
        if (plugin == null || !serverType.isHub()) {
            return null;
        }
        return plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshLeaderboards,
                LEADERBOARD_INITIAL_REFRESH_DELAY_TICKS,
                LEADERBOARD_REFRESH_INTERVAL_TICKS
        );
    }

    private void refreshLeaderboards() {
        if (leaderboardsByAnchor.isEmpty()) {
            return;
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        List<LeaderboardRefreshSnapshot> snapshot = new ArrayList<LeaderboardRefreshSnapshot>();
        Set<String> seenEntityIds = new HashSet<String>();
        for (RuntimeLeaderboard runtime : leaderboardsByAnchor.values()) {
            if (runtime == null || runtime.baseLocation == null) {
                continue;
            }
            String entityId = safeText(runtime.entityId);
            if (entityId.isEmpty() || !seenEntityIds.add(entityId.toLowerCase(Locale.ROOT))) {
                continue;
            }
            snapshot.add(new LeaderboardRefreshSnapshot(
                    entityId,
                    runtime.baseLocation.clone(),
                    normalizeMetric(runtime.metric)
            ));
        }
        if (snapshot.isEmpty()) {
            refreshInFlight.set(false);
            return;
        }
        Set<String> metrics = new HashSet<String>();
        for (LeaderboardRefreshSnapshot entry : snapshot) {
            if (entry == null) {
                continue;
            }
            metrics.add(normalizeMetric(entry.metric));
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, List<LeaderboardEntry>> entriesByMetric = Collections.emptyMap();
            try {
                entriesByMetric = fetchLeaderboardEntriesByMetrics(metrics);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to refresh hub leaderboards!\n" + ex.getMessage());
            }
            Map<String, List<LeaderboardEntry>> resolvedEntriesByMetric = entriesByMetric;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        for (LeaderboardRefreshSnapshot entry : snapshot) {
                            if (entry == null
                                    || entry.entityId.isEmpty()
                                    || entry.location == null
                                    || entry.location.getWorld() == null) {
                                continue;
                            }
                            spawnRuntimeLeaderboard(
                                    entry.entityId,
                                    entry.location,
                                    entry.metric,
                                    entriesForMetric(resolvedEntriesByMetric, entry.metric)
                            );
                        }
                    } finally {
                        refreshInFlight.set(false);
                    }
                });
            } catch (Exception ex) {
                refreshInFlight.set(false);
                plugin.getLogger().warning("Failed to apply hub leaderboard refresh!\n" + ex.getMessage());
            }
        });
    }

    private void despawnLeaderboardByEntityId(String entityId) {
        String target = safeText(entityId);
        if (target.isEmpty()) {
            return;
        }
        List<RuntimeLeaderboard> matched = new ArrayList<RuntimeLeaderboard>();
        for (RuntimeLeaderboard runtime : leaderboardsByAnchor.values()) {
            if (runtime == null) {
                continue;
            }
            if (target.equalsIgnoreCase(safeText(runtime.entityId))) {
                matched.add(runtime);
            }
        }
        for (RuntimeLeaderboard runtime : matched) {
            despawnLeaderboard(runtime);
        }
    }

    private void clearLegacyLeaderboardLines(Location boardLocation, int lineCount) {
        if (boardLocation == null || boardLocation.getWorld() == null) {
            return;
        }
        World world = boardLocation.getWorld();
        int totalLines = Math.max(1, lineCount);
        double targetX = boardLocation.getX();
        double targetZ = boardLocation.getZ();
        double topY = boardLocation.getY() + LEADERBOARD_HOLOGRAM_BASE_Y_OFFSET + LEADERBOARD_HOLOGRAM_Y_TOLERANCE;
        double bottomY = boardLocation.getY()
                + LEADERBOARD_HOLOGRAM_BASE_Y_OFFSET
                - ((totalLines - 1) * HOLOGRAM_LINE_SPACING)
                - LEADERBOARD_HOLOGRAM_Y_TOLERANCE;
        for (Entity entity : new ArrayList<Entity>(world.getEntities())) {
            if (!(entity instanceof ArmorStand) || entity.getUniqueId() == null || entity.getLocation() == null) {
                continue;
            }
            ArmorStand stand = (ArmorStand) entity;
            Location standLocation = stand.getLocation();
            if (standLocation.getWorld() == null || !world.equals(standLocation.getWorld())) {
                continue;
            }
            double dx = standLocation.getX() - targetX;
            double dz = standLocation.getZ() - targetZ;
            if ((dx * dx + dz * dz) > LEADERBOARD_HOLOGRAM_COLUMN_RADIUS_SQUARED) {
                continue;
            }
            double y = standLocation.getY();
            if (y < bottomY || y > topY) {
                continue;
            }
            if (!isLikelyHologramStand(stand)) {
                continue;
            }
            stand.remove();
        }
    }

    private boolean isLikelyHologramStand(ArmorStand stand) {
        if (stand == null) {
            return false;
        }
        String customName = safeText(stand.getCustomName());
        if (customName.isEmpty() || !stand.isCustomNameVisible()) {
            return false;
        }
        return !stand.isVisible();
    }

    private void despawnAll() {
        List<RuntimeLeaderboard> snapshot = new ArrayList<RuntimeLeaderboard>(leaderboardsByAnchor.values());
        for (RuntimeLeaderboard runtime : snapshot) {
            despawnLeaderboard(runtime);
        }
        leaderboardsByAnchor.clear();
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
            Document root = loadRoot(gameKey);
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
        int parsedBoards = countParsedLeaderboardEntries(map);
        if (parsedBoards > 0) {
            score += 1000 + parsedBoards;
        } else if (hasLeaderboardData(map)) {
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

    private int countParsedLeaderboardEntries(Document map) {
        if (map == null) {
            return 0;
        }
        int parsed = 0;
        for (Object rawBoard : asObjectList(map.get("leaderboards"))) {
            LeaderboardRefreshSnapshot snapshot = parseConfiguredLeaderboard(rawBoard);
            if (snapshot == null || snapshot.location == null || snapshot.location.getWorld() == null) {
                continue;
            }
            parsed++;
        }
        return parsed;
    }

    private Document loadRoot(String gameKey) {
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
            if (hasLeaderboardData(map)) {
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

    private boolean hasLeaderboardData(Document map) {
        if (map == null) {
            return false;
        }
        return !asObjectList(map.get("leaderboards")).isEmpty();
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
            if (map == null || (!isHubMap(map) && !hasLeaderboardData(map) && !hasHubLocationData(map))) {
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

    private static final class LeaderboardEntry {
        private final UUID uuid;
        private final String name;
        private final int score;
        private final ChatColor nameColor;

        private LeaderboardEntry(UUID uuid, String name, int score, ChatColor nameColor) {
            this.uuid = uuid;
            this.name = name == null ? "" : name;
            this.score = Math.max(0, score);
            this.nameColor = nameColor == null ? ChatColor.WHITE : nameColor;
        }
    }

    private static final class ViewerRecord {
        private final int position;
        private final String name;
        private final int score;
        private final ChatColor nameColor;

        private ViewerRecord(int position, String name, int score, ChatColor nameColor) {
            this.position = Math.max(1, position);
            this.name = name == null ? "" : name;
            this.score = Math.max(0, score);
            this.nameColor = nameColor == null ? ChatColor.WHITE : nameColor;
        }
    }

    private static final class LeaderboardRefreshSnapshot {
        private final String entityId;
        private final Location location;
        private final String metric;

        private LeaderboardRefreshSnapshot(String entityId,
                                           Location location,
                                           String metric) {
            this.entityId = entityId == null ? "" : entityId;
            this.location = location;
            this.metric = metric == null ? "" : metric;
        }
    }

    private static final class RuntimeLeaderboard {
        private String entityId;
        private String metric;
        private Location baseLocation;
        private UUID anchorUuid;
        private List<UUID> lineUuids;
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
