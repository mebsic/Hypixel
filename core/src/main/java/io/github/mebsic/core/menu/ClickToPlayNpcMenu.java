package io.github.mebsic.core.menu;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.QueueClient;
import io.github.mebsic.core.util.HubMessageUtil;
import org.bson.Document;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ClickToPlayNpcMenu extends Menu {
    private static final int SIZE = 36;
    private static final int PLAY_SLOT = 13;
    private static final int CLOSE_SLOT = 31;
    private static final String CHANNEL = "BungeeCord";
    private static final String MODE_NAME = "Classic";
    private static final int MURDER_MYSTERY_TOTAL_PLAYERS = 16;

    private final CorePlugin plugin;
    private final QueueClient queueClient;
    private final ServerType gameType;

    public ClickToPlayNpcMenu(CorePlugin plugin, QueueClient queueClient, ServerType gameType) {
        super(resolveTitle(gameType), SIZE);
        this.plugin = plugin;
        this.queueClient = queueClient;
        this.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, PLAY_SLOT, buildPlayItem());
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        if (click.getRawSlot() == CLOSE_SLOT) {
            click.getPlayer().closeInventory();
            return;
        }
        if (click.getRawSlot() != PLAY_SLOT) {
            return;
        }
        click.getPlayer().closeInventory();
        sendPlayerToGame(click.getPlayer());
    }

    private void sendPlayerToGame(Player player) {
        if (player == null) {
            return;
        }
        if (gameType == null || !gameType.isGame()) {
            player.sendMessage(ChatColor.RED + "This game is currently unavailable.");
            return;
        }
        if (queueClient != null) {
            try {
                queueClient.queuePlayer(player, gameType);
                return;
            } catch (Exception ex) {
                if (plugin != null) {
                    plugin.getLogger().warning("Failed to queue click-to-play request!\n" + ex.getMessage());
                }
            }
        }
        String currentServer = currentServerName();
        String targetServer = findAvailableGameServer(gameType, currentServer);
        if (targetServer.isEmpty()) {
            player.sendMessage(ChatColor.RED + "There are no games available! Please try again later.");
            return;
        }
        sendConnect(player, targetServer);
    }

    private String findAvailableGameServer(ServerType type, String excludedServer) {
        if (plugin == null || type == null || !type.isGame()) {
            return "";
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return "";
        }
        MongoCollection<Document> registry = mongo.getServerRegistry();
        if (registry == null) {
            return "";
        }
        String group = plugin.getConfig() == null ? "" : safeString(plugin.getConfig().getString("server.group", ""));
        int staleSeconds = plugin.getConfig() == null ? 20 : Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        long now = System.currentTimeMillis();
        Document best = null;
        for (Document doc : registry.find(Filters.eq("type", type.getId()))) {
            if (!isAvailableGameServer(doc, now, group, staleSeconds, excludedServer)) {
                continue;
            }
            if (best == null || safeInt(doc.get("players")) < safeInt(best.get("players"))) {
                best = doc;
            }
        }
        return safeString(best == null ? null : best.getString("_id"));
    }

    private boolean isAvailableGameServer(Document doc, long now, String group, int staleSeconds, String excludedServer) {
        if (doc == null) {
            return false;
        }
        String name = safeString(doc.getString("_id"));
        if (name.isEmpty()) {
            return false;
        }
        if (!safeString(excludedServer).isEmpty() && name.equalsIgnoreCase(safeString(excludedServer))) {
            return false;
        }
        if (!safeString(group).isEmpty()) {
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
        String state = safeString(doc.getString("state")).toUpperCase(Locale.ROOT);
        return !state.equals("IN_GAME")
                && !state.equals("ENDING")
                && !state.equals("RESTARTING")
                && !state.equals("LOCKED")
                && !state.equals("WAITING_RESTART");
    }

    private org.bukkit.inventory.ItemStack buildPlayItem() {
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.DARK_GRAY + "Solo");
        lore.add(ChatColor.DARK_GRAY + Integer.toString(resolveTotalPlayers()) + " Total Players");
        lore.add("");
        lore.add(ChatColor.GRAY + "The Classic " + HubMessageUtil.gameDisplayName(gameType));
        lore.add(ChatColor.GRAY + "experience - take on the role of");
        lore.add(ChatColor.RED + "Murderer" + ChatColor.GRAY + ", "
                + ChatColor.BLUE + "Detective" + ChatColor.GRAY + " or "
                + ChatColor.GREEN + "Innocent" + ChatColor.GRAY + ". The");
        lore.add(ChatColor.GRAY + "Murderer must try and kill without");
        lore.add(ChatColor.GRAY + "getting caught, while the others must");
        lore.add(ChatColor.GRAY + "try to figure out who they are!");
        lore.add("");
        lore.add(ChatColor.RED + "1 Murderer");
        lore.add(ChatColor.BLUE + "1 Detective");
        lore.add(ChatColor.GREEN + "14 Innocents");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to play!");
        return item(resolveClockMaterial(), ChatColor.GREEN + modeDisplayName(), lore);
    }

    private int resolveTotalPlayers() {
        if (gameType == ServerType.MURDER_MYSTERY) {
            return MURDER_MYSTERY_TOTAL_PLAYERS;
        }
        return 0;
    }

    private String modeDisplayName() {
        return HubMessageUtil.gameDisplayName(gameType) + " (" + MODE_NAME + ")";
    }

    private static String resolveTitle(ServerType gameType) {
        ServerType safe = gameType == null ? ServerType.UNKNOWN : gameType;
        return "Play " + HubMessageUtil.gameDisplayName(safe);
    }

    private Material resolveClockMaterial() {
        Material clock = Material.matchMaterial("CLOCK");
        if (clock != null) {
            return clock;
        }
        Material watch = Material.matchMaterial("WATCH");
        if (watch != null) {
            return watch;
        }
        return Material.COMPASS;
    }

    private String currentServerName() {
        if (plugin == null || plugin.getConfig() == null) {
            return "";
        }
        return safeString(plugin.getConfig().getString("server.id", ""));
    }

    private void sendConnect(Player player, String targetServer) {
        if (player == null || targetServer == null || targetServer.trim().isEmpty() || plugin == null) {
            return;
        }
        String destination = targetServer.trim();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(destination);
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "An error occurred when sending you to " + destination + "! Please try again later.");
            plugin.getLogger().warning("Failed to send click-to-play connect request!\n" + ex.getMessage());
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

    private String safeString(String value) {
        return value == null ? "" : value.trim();
    }
}
