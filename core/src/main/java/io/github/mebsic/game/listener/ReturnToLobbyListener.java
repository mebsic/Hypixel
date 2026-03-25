package io.github.mebsic.game.listener;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.game.util.ReturnToLobbyItem;
import org.bson.Document;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReturnToLobbyListener implements Listener {
    private static final String CHANNEL = "BungeeCord";
    private static final long TELEPORT_DELAY_TICKS = 60L; // 3 seconds
    private static final String START_TELEPORT_MESSAGE =
            ChatColor.GREEN.toString() + ChatColor.BOLD
                    + "Teleporting you to the lobby in 3 seconds..."
                    + "\n"
                    + ChatColor.GREEN + ChatColor.BOLD
                    + "Right-click again to cancel the teleport!";
    private static final String CANCEL_TELEPORT_MESSAGE =
            ChatColor.RED.toString() + ChatColor.BOLD + "Teleport cancelled!";
    private static final String NO_HUB_AVAILABLE_MESSAGE =
            ChatColor.RED + CommonMessages.NO_SERVERS_AVAILABLE;
    private static final String TRANSFER_FAILED_MESSAGE =
            ChatColor.RED + "Failed to switch to a lobby right now!";

    private final CorePlugin plugin;
    private final ServerType hubType;
    private final String group;
    private final String currentServerId;
    private final int staleSeconds;
    private final Map<UUID, BukkitTask> pendingTeleports = new ConcurrentHashMap<>();

    public ReturnToLobbyListener(CorePlugin plugin) {
        this.plugin = plugin;
        ServerType currentType = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        this.hubType = currentType == null ? ServerType.UNKNOWN : currentType.toHubType();
        this.group = plugin == null ? "" : plugin.getConfig().getString("server.group", "");
        this.currentServerId = plugin == null ? "" : plugin.getConfig().getString("server.id", "");
        this.staleSeconds = plugin == null ? 20 : Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!ReturnToLobbyItem.isReturnToLobbyItem(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        BukkitTask existing = pendingTeleports.remove(uuid);
        if (existing != null) {
            existing.cancel();
            player.sendMessage(CANCEL_TELEPORT_MESSAGE);
            return;
        }

        player.sendMessage(START_TELEPORT_MESSAGE);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingTeleports.remove(uuid);
            Player online = plugin.getServer().getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            requestHubTransfer(online.getUniqueId());
        }, TELEPORT_DELAY_TICKS);
        pendingTeleports.put(uuid, task);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        BukkitTask task = pendingTeleports.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void requestHubTransfer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String targetHub = findBestHubServerName();
            plugin.getServer().getScheduler().runTask(plugin, () -> connectToHub(uuid, targetHub));
        });
    }

    private void connectToHub(UUID uuid, String targetHub) {
        if (uuid == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (targetHub == null || targetHub.trim().isEmpty()) {
            player.sendMessage(NO_HUB_AVAILABLE_MESSAGE);
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(targetHub.trim());
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (Exception ex) {
            player.sendMessage(TRANSFER_FAILED_MESSAGE);
            plugin.getLogger().warning("Failed to send hub connect request!\n" + ex.getMessage());
        }
    }

    private String findBestHubServerName() {
        if (hubType == null || !hubType.isHub()) {
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
        long now = System.currentTimeMillis();
        Map<String, Document> latestByServerId = new HashMap<>();
        Map<String, Long> heartbeatByServerId = new HashMap<>();
        for (Document doc : collection.find(Filters.eq("type", hubType.getId()))) {
            if (!isHubEntryValid(doc, now)) {
                continue;
            }
            String serverId = safeString(doc.getString("_id"));
            if (serverId.isEmpty()) {
                continue;
            }
            if (!currentServerId.isEmpty() && currentServerId.equalsIgnoreCase(serverId)) {
                continue;
            }
            String key = serverId.toLowerCase(Locale.ROOT);
            long heartbeat = doc.getLong("lastHeartbeat") == null ? Long.MIN_VALUE : doc.getLong("lastHeartbeat");
            Long currentHeartbeat = heartbeatByServerId.get(key);
            if (currentHeartbeat != null && currentHeartbeat >= heartbeat) {
                continue;
            }
            heartbeatByServerId.put(key, heartbeat);
            latestByServerId.put(key, doc);
        }
        if (latestByServerId.isEmpty()) {
            return null;
        }
        List<Document> candidates = new ArrayList<>(latestByServerId.values());
        candidates.sort(Comparator
                .comparingInt((Document doc) -> safeInt(doc.get("players")))
                .thenComparing(doc -> safeString(doc.getString("_id")).toLowerCase(Locale.ROOT)));
        return safeString(candidates.get(0).getString("_id"));
    }

    private boolean isHubEntryValid(Document doc, long now) {
        if (doc == null) {
            return false;
        }
        if (group != null && !group.trim().isEmpty()) {
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
}
