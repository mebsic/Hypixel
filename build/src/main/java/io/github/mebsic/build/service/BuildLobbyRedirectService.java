package io.github.mebsic.build.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoCollection;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.PubSubService;
import io.github.mebsic.core.util.CommonMessages;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BuildLobbyRedirectService {
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final String PROFILE_COMMAND_CHANNEL = "profile_command_action";
    private static final String SET_RANK_ACTION = "SET_RANK";
    private static final int MAX_STAFF_RESOLUTION_ATTEMPTS = 20;
    private static final long STAFF_RESOLUTION_RETRY_TICKS = 5L;

    private final JavaPlugin plugin;
    private final CorePlugin corePlugin;
    private final BuildAccessService accessService;
    private final String group;
    private final String currentServerId;
    private final int staleSeconds;
    private final Map<UUID, BukkitTask> pendingValidationTasks;
    private final Set<UUID> redirectInFlight;
    private volatile boolean shuttingDown;

    public BuildLobbyRedirectService(JavaPlugin plugin,
                                     CorePlugin corePlugin,
                                     BuildAccessService accessService,
                                     int staleSeconds) {
        this.plugin = plugin;
        this.corePlugin = corePlugin;
        this.accessService = accessService;
        this.group = corePlugin == null ? "" : safeString(corePlugin.getConfig().getString("server.group", ""));
        this.currentServerId = corePlugin == null ? "" : safeString(corePlugin.getConfig().getString("server.id", ""));
        this.staleSeconds = Math.max(0, staleSeconds);
        this.pendingValidationTasks = new ConcurrentHashMap<>();
        this.redirectInFlight = ConcurrentHashMap.newKeySet();
        this.shuttingDown = false;
    }

    public void subscribeToRankSync() {
        PubSubService pubSub = corePlugin == null ? null : corePlugin.getPubSubService();
        if (pubSub == null) {
            return;
        }
        pubSub.subscribe(PROFILE_COMMAND_CHANNEL, this::handleProfileCommandPayload);
    }

    public void validateStaffAccess(Player player, int attempt) {
        if (player == null || !player.isOnline() || shuttingDown) {
            return;
        }

        UUID uuid = player.getUniqueId();
        Rank rank = accessService == null ? null : accessService.resolveRank(player);

        if (rank == null && attempt < MAX_STAFF_RESOLUTION_ATTEMPTS) {
            scheduleValidationRetry(uuid, attempt + 1);
            return;
        }

        cancelValidation(uuid);
        if (rank != null && rank.isAtLeast(Rank.STAFF)) {
            if (accessService != null) {
                accessService.applyBuildDefaults(player);
            }
            return;
        }

        redirectNonStaff(uuid, true);
    }

    public void onQuit(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        cancelValidation(uuid);
        redirectInFlight.remove(uuid);
    }

    public void shutdown() {
        shuttingDown = true;
        for (BukkitTask task : pendingValidationTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        pendingValidationTasks.clear();
        redirectInFlight.clear();
    }

    private void handleProfileCommandPayload(String payload) {
        if (payload == null || payload.trim().isEmpty() || shuttingDown) {
            return;
        }

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (parsed == null || !parsed.isJsonObject()) {
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (Exception ignored) {
            return;
        }

        String action = safeJsonString(root, "action");
        if (!SET_RANK_ACTION.equalsIgnoreCase(action)) {
            return;
        }

        UUID uuid = parseUuid(safeJsonString(root, "targetUuid"));
        Rank rank = parseRank(safeJsonString(root, "rankName"));
        if (uuid == null || rank == null) {
            return;
        }

        runOnMainThread(() -> {
            if (shuttingDown) {
                return;
            }
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || !target.isOnline()) {
                return;
            }
            if (rank.isAtLeast(Rank.STAFF)) {
                if (accessService != null) {
                    accessService.applyBuildDefaults(target);
                }
                return;
            }
            redirectNonStaff(uuid, true);
        });
    }

    private void scheduleValidationRetry(UUID uuid, int attempt) {
        if (uuid == null || shuttingDown) {
            return;
        }
        cancelValidation(uuid);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    pendingValidationTasks.remove(uuid);
                    if (shuttingDown) {
                        return;
                    }
                    Player online = plugin.getServer().getPlayer(uuid);
                    if (online == null || !online.isOnline()) {
                        return;
                    }
                    validateStaffAccess(online, attempt);
                },
                STAFF_RESOLUTION_RETRY_TICKS
        );
        pendingValidationTasks.put(uuid, task);
    }

    private void cancelValidation(UUID uuid) {
        if (uuid == null) {
            return;
        }
        BukkitTask task = pendingValidationTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void redirectNonStaff(UUID uuid, boolean sendPermissionMessage) {
        if (uuid == null || shuttingDown) {
            return;
        }
        if (!redirectInFlight.add(uuid)) {
            return;
        }

        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            redirectInFlight.remove(uuid);
            return;
        }
        if (sendPermissionMessage) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String targetHub = findBestHubServerName();
            runOnMainThread(() -> {
                try {
                    sendToLobby(uuid, targetHub);
                } finally {
                    redirectInFlight.remove(uuid);
                }
            });
        });
    }

    private void sendToLobby(UUID uuid, String targetHub) {
        if (uuid == null || shuttingDown) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }

        String target = safeString(targetHub);
        if (target.isEmpty()) {
            player.kickPlayer(ChatColor.RED + CommonMessages.NO_SERVERS_AVAILABLE);
            return;
        }

        if (!sendConnect(player, target)) {
            player.kickPlayer(ChatColor.RED + CommonMessages.NO_SERVERS_AVAILABLE);
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
        Plugin channelPlugin = corePlugin != null ? corePlugin : plugin;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(target);
            player.sendPluginMessage(channelPlugin, BUNGEE_CHANNEL, bytes.toByteArray());
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to send lobby redirect for " + player.getName() + "!\n" + ex.getMessage());
            return false;
        }
    }

    private String findBestHubServerName() {
        MongoManager mongo = corePlugin == null ? null : corePlugin.getMongoManager();
        if (mongo == null) {
            return "";
        }
        MongoCollection<Document> collection = mongo.getServerRegistry();
        if (collection == null) {
            return "";
        }

        long now = System.currentTimeMillis();
        List<Document> scoped = bestHubCandidates(collection, now, true);
        if (scoped.isEmpty()) {
            scoped = bestHubCandidates(collection, now, false);
        }
        if (scoped.isEmpty()) {
            return "";
        }

        scoped.sort(Comparator
                .comparingInt((Document doc) -> safeInt(doc.get("players")))
                .thenComparing(doc -> safeString(doc.getString("_id")).toLowerCase(Locale.ROOT)));

        return safeString(scoped.get(0).getString("_id"));
    }

    private List<Document> bestHubCandidates(MongoCollection<Document> collection, long now, boolean requireGroup) {
        Map<String, Document> latestByServerId = new HashMap<>();
        Map<String, Long> heartbeatByServerId = new HashMap<>();

        for (Document doc : collection.find()) {
            if (!isHubCandidate(doc, now, requireGroup)) {
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
        return new ArrayList<>(latestByServerId.values());
    }

    private boolean isHubCandidate(Document doc, long now, boolean requireGroup) {
        if (doc == null) {
            return false;
        }

        String type = safeString(doc.getString("type"));
        if (type.isEmpty() || !type.toUpperCase(Locale.ROOT).endsWith("_HUB")) {
            return false;
        }

        String serverId = safeString(doc.getString("_id"));
        if (serverId.isEmpty()) {
            return false;
        }
        if (!currentServerId.isEmpty() && currentServerId.equalsIgnoreCase(serverId)) {
            return false;
        }

        if (requireGroup && !group.isEmpty()) {
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
        return true;
    }

    private void runOnMainThread(Runnable task) {
        if (task == null || shuttingDown) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    private Rank parseRank(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Rank.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeJsonString(JsonObject root, String key) {
        if (root == null || key == null || !root.has(key) || root.get(key).isJsonNull()) {
            return "";
        }
        try {
            return safeString(root.get(key).getAsString());
        } catch (Exception ignored) {
            return "";
        }
    }

    private String safeString(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
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
}
