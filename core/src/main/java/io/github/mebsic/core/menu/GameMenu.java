package io.github.mebsic.core.menu;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.QueueClient;
import io.github.mebsic.core.service.ServerRegistrySnapshot;
import io.github.mebsic.core.util.HubMessageUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class GameMenu extends Menu {
    public static final String TITLE = "Game Menu";
    private static final int SIZE = 27;
    private static final int CENTER_SLOT = 13;
    private static final long DEFAULT_REFRESH_TICKS = 2L;
    private static final String CHANNEL = "BungeeCord";
    private static final String MURDER_MYSTERY_NAME = ChatColor.GREEN + "Murder Mystery";
    private static final String CONNECT_ARROW = "▸";

    private final CorePlugin plugin;
    private final QueueClient queueClient;
    private final ServerRegistrySnapshot registrySnapshot;
    private final BukkitTask refreshTask;

    public GameMenu(CorePlugin plugin, QueueClient queueClient, ServerRegistrySnapshot registrySnapshot) {
        super(TITLE, SIZE);
        this.plugin = plugin;
        this.queueClient = queueClient;
        this.registrySnapshot = registrySnapshot;
        long refreshTicks = plugin == null ? DEFAULT_REFRESH_TICKS
                : Math.max(1L, plugin.getConfig().getLong("menus.gameMenuRefreshTicks", DEFAULT_REFRESH_TICKS));
        this.refreshTask = plugin == null ? null
                : Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, refreshTicks, refreshTicks);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        int players = getTotalMurderMysteryPlayers();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Team Survival");
        lore.add("");
        lore.add(ChatColor.GRAY + "1 Murderer, 1 Detective, and a whole");
        lore.add(ChatColor.GRAY + "lot of Innocents. Can you survive");
        lore.add(ChatColor.GRAY + "this tense social game of betrayal");
        lore.add(ChatColor.GRAY + "and murder?");
        lore.add("");
        lore.add(ChatColor.GREEN + CONNECT_ARROW + " Click to Connect");
        lore.add(ChatColor.GRAY.toString() + players + " currently playing!");
        ItemStack item = item(Material.BOW, MURDER_MYSTERY_NAME, lore);
        set(inventory, CENTER_SLOT, item);
    }

    private int getTotalMurderMysteryPlayers() {
        if (registrySnapshot == null) {
            return 0;
        }
        int gamePlayers = registrySnapshot.getTotalPlayers(ServerType.MURDER_MYSTERY);
        int hubPlayers = registrySnapshot.getTotalPlayers(ServerType.MURDER_MYSTERY_HUB);
        return Math.max(0, gamePlayers + hubPlayers);
    }

    @Override
    public void onClick(MenuClick click) {
        ItemStack item = click.getItem();
        if (item == null || item.getType() != Material.BOW || !item.hasItemMeta()) {
            return;
        }
        ServerType gameType = resolveGameType(item);
        if (gameType == null || !gameType.isGame()) {
            return;
        }
        click.getPlayer().closeInventory();
        handleGameTypeClick(click.getPlayer(), gameType);
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
    }

    private void refreshOpenMenus() {
        if (plugin == null) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            Inventory top = player.getOpenInventory() == null ? null : player.getOpenInventory().getTopInventory();
            if (top == null || !(top.getHolder() instanceof MenuHolder)) {
                continue;
            }
            MenuHolder holder = (MenuHolder) top.getHolder();
            if (holder.getMenu() != this) {
                continue;
            }
            populate(player, top);
        }
    }

    private ServerType resolveGameType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return ServerType.UNKNOWN;
        }
        String rawDisplay = item.getItemMeta().getDisplayName();
        String stripped = ChatColor.stripColor(rawDisplay);
        if (stripped == null) {
            return ServerType.UNKNOWN;
        }
        String normalized = stripped.trim();
        if (normalized.isEmpty()) {
            return ServerType.UNKNOWN;
        }
        for (ServerType type : ServerType.values()) {
            if (!type.isGame()) {
                continue;
            }
            String displayName = HubMessageUtil.gameDisplayName(type);
            if (normalized.equalsIgnoreCase(displayName)) {
                return type;
            }
        }
        return ServerType.UNKNOWN;
    }

    private void handleGameTypeClick(Player player, ServerType gameType) {
        if (player == null || gameType == null || !gameType.isGame()) {
            return;
        }
        String gameLabel = HubMessageUtil.gameDisplayName(gameType);
        ServerType hubType = gameType.toHubType();
        if (hubType == null || !hubType.isHub()) {
            player.sendMessage(ChatColor.RED + "No " + gameLabel + " lobbies are available right now!");
            return;
        }
        List<String> lobbies = findAvailableLobbies(hubType);
        if (lobbies.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No " + gameLabel + " lobbies are available right now!");
            return;
        }
        String currentServer = currentServerName();
        boolean inCurrentLobby = isCurrentHubType(hubType) && containsIgnoreCase(lobbies, currentServer);
        if (lobbies.size() == 1 && inCurrentLobby) {
            player.sendMessage(ChatColor.RED + "You are already in a " + gameLabel + " lobby!");
            return;
        }
        String targetLobby = pickRandomLobby(lobbies, currentServer, lobbies.size() > 1);
        if (targetLobby == null || targetLobby.trim().isEmpty()) {
            player.sendMessage(ChatColor.RED + "No " + gameLabel + " lobbies are available right now!");
            return;
        }
        sendConnect(player, targetLobby);
    }

    private List<String> findAvailableLobbies(ServerType hubType) {
        if (plugin == null || hubType == null) {
            return java.util.Collections.emptyList();
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return java.util.Collections.emptyList();
        }
        MongoCollection<Document> registry = mongo.getServerRegistry();
        if (registry == null) {
            return java.util.Collections.emptyList();
        }
        String group = plugin.getConfig().getString("server.group", "");
        int staleSeconds = Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        long now = System.currentTimeMillis();
        List<String> candidates = new ArrayList<>();
        for (Document doc : registry.find(Filters.eq("type", hubType.getId()))) {
            if (!isAvailableLobby(doc, now, group, staleSeconds)) {
                continue;
            }
            String name = safeString(doc.getString("_id"));
            if (name.isEmpty()) {
                continue;
            }
            candidates.add(name);
        }
        return candidates;
    }

    private String pickRandomLobby(List<String> lobbies, String currentServer, boolean preferOtherLobby) {
        List<String> candidates = new ArrayList<>();
        if (lobbies != null) {
            for (String lobby : lobbies) {
                String safeLobby = safeString(lobby);
                if (!safeLobby.isEmpty()) {
                    candidates.add(safeLobby);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (preferOtherLobby) {
            String safeCurrent = safeString(currentServer);
            if (!safeCurrent.isEmpty()) {
                candidates.removeIf(name -> name.equalsIgnoreCase(safeCurrent));
                if (candidates.isEmpty() && lobbies != null) {
                    for (String lobby : lobbies) {
                        String safeLobby = safeString(lobby);
                        if (!safeLobby.isEmpty()) {
                            candidates.add(safeLobby);
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private boolean isCurrentHubType(ServerType hubType) {
        if (plugin == null || hubType == null) {
            return false;
        }
        return plugin.getServerType() == hubType;
    }

    private String currentServerName() {
        if (plugin == null) {
            return "";
        }
        return safeString(plugin.getConfig().getString("server.id", ""));
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String safeTarget = safeString(target);
        if (safeTarget.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (safeTarget.equalsIgnoreCase(safeString(value))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAvailableLobby(Document doc, long now, String group, int staleSeconds) {
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
        int players = safeInt(doc.get("players"));
        int maxPlayers = safeInt(doc.get("maxPlayers"));
        if (maxPlayers > 0 && players >= maxPlayers) {
            return false;
        }
        return true;
    }

    private int safeInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    private void sendConnect(Player player, String targetServer) {
        if (player == null || targetServer == null || targetServer.trim().isEmpty() || plugin == null) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(targetServer.trim());
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Unable to switch lobbies right now.");
            plugin.getLogger().warning("Failed to send game menu connect request: " + ex.getMessage());
        }
    }
}
