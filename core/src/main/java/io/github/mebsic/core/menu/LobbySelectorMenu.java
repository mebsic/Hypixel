package io.github.mebsic.core.menu;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.HubMessageUtil;
import io.github.mebsic.core.util.RankFormatUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class LobbySelectorMenu extends Menu {
    private static final int DEFAULT_SIZE = 9;
    private static final int MAX_SIZE = 54;
    private static final String CHANNEL = "BungeeCord";
    private static final long DEFAULT_MENU_REFRESH_TICKS = 2L;
    private static final long DEFAULT_DATA_REFRESH_TICKS = 2L;

    private final CorePlugin plugin;
    private final ServerType hubType;
    private final String currentServerId;
    private final String group;
    private final int staleSeconds;
    private final long dataRefreshMillis;
    private final Map<UUID, Map<Integer, LobbyEntry>> views;
    private final AtomicBoolean registryRefreshInProgress;
    private final Map<UUID, Set<UUID>> cachedFriendUuids;
    private final Map<UUID, String> cachedFriendDisplayNames;
    private final Map<UUID, Long> lastFriendRefreshAt;
    private final Set<UUID> friendRefreshInProgress;
    private volatile long lastRegistryRefreshAt;
    private volatile List<Document> cachedLobbyDocs;
    private final BukkitTask refreshTask;

    public LobbySelectorMenu(CorePlugin plugin) {
        super(formatTitle(plugin), DEFAULT_SIZE);
        this.plugin = plugin;
        ServerType current = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        this.hubType = current == null ? ServerType.UNKNOWN : current.toHubType();
        this.currentServerId = plugin == null ? "" : plugin.getConfig().getString("server.id", "");
        this.group = plugin == null ? "" : plugin.getConfig().getString("server.group", "");
        this.staleSeconds = plugin == null ? 20 : Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        long dataRefreshTicks = plugin == null ? DEFAULT_DATA_REFRESH_TICKS
                : Math.max(1L, plugin.getConfig().getLong("menus.lobbySelectorDataRefreshTicks", DEFAULT_DATA_REFRESH_TICKS));
        this.dataRefreshMillis = dataRefreshTicks * 50L;
        this.views = new ConcurrentHashMap<>();
        this.registryRefreshInProgress = new AtomicBoolean(false);
        this.cachedFriendUuids = new ConcurrentHashMap<>();
        this.cachedFriendDisplayNames = new ConcurrentHashMap<>();
        this.lastFriendRefreshAt = new ConcurrentHashMap<>();
        this.friendRefreshInProgress = ConcurrentHashMap.newKeySet();
        this.lastRegistryRefreshAt = 0L;
        this.cachedLobbyDocs = Collections.emptyList();
        refreshLobbyDocsIfNeeded(true);
        long menuRefreshTicks = plugin == null ? DEFAULT_MENU_REFRESH_TICKS
                : Math.max(1L, plugin.getConfig().getLong("menus.lobbySelectorRefreshTicks", DEFAULT_MENU_REFRESH_TICKS));
        this.refreshTask = plugin == null ? null
                : Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, menuRefreshTicks, menuRefreshTicks);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        render(player, inventory, loadLobbies(player));
    }

    @Override
    public void onClick(MenuClick click) {
        Player player = click.getPlayer();
        Map<Integer, LobbyEntry> slots = views.get(player.getUniqueId());
        if (slots == null) {
            return;
        }
        LobbyEntry lobby = slots.get(click.getRawSlot());
        if (lobby == null) {
            return;
        }
        if (isCurrent(lobby.name)) {
            int lobbyNumber = Math.max(1, click.getRawSlot() + 1);
            String lobbyLabel = HubMessageUtil.gameDisplayName(hubType) + " Lobby";
            player.sendMessage(ChatColor.RED
                    + "You are already in "
                    + lobbyLabel
                    + " #"
                    + lobbyNumber);
            return;
        }
        if (lobby.maxPlayers > 0 && lobby.players >= lobby.maxPlayers) {
            player.sendMessage(ChatColor.RED + "That lobby is currently full!");
            return;
        }
        sendConnect(player, lobby.name);
        player.closeInventory();
    }

    @Override
    protected int resolveSize(Player player) {
        return sizeForLobbies(loadLobbies(player).size());
    }

    public void clear(Player player) {
        if (player != null) {
            UUID uuid = player.getUniqueId();
            views.remove(uuid);
            cachedFriendUuids.remove(uuid);
            lastFriendRefreshAt.remove(uuid);
            friendRefreshInProgress.remove(uuid);
        }
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        views.clear();
        cachedFriendUuids.clear();
        cachedFriendDisplayNames.clear();
        lastFriendRefreshAt.clear();
        friendRefreshInProgress.clear();
    }

    private void refreshOpenMenus() {
        if (plugin == null || views.isEmpty()) {
            return;
        }
        refreshLobbyDocsIfNeeded(false);
        for (UUID uuid : new ArrayList<>(views.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                views.remove(uuid);
                continue;
            }
            Inventory top = player.getOpenInventory() == null ? null : player.getOpenInventory().getTopInventory();
            if (top == null || !(top.getHolder() instanceof MenuHolder)) {
                views.remove(uuid);
                continue;
            }
            MenuHolder holder = (MenuHolder) top.getHolder();
            if (holder.getMenu() != this) {
                views.remove(uuid);
                continue;
            }
            List<LobbyEntry> lobbies = loadLobbies(player);
            int requiredSize = sizeForLobbies(lobbies.size());
            if (requiredSize != top.getSize()) {
                open(player);
                continue;
            }
            render(player, top, lobbies);
        }
    }

    private void render(Player player, Inventory inventory, List<LobbyEntry> lobbies) {
        if (player == null || inventory == null) {
            return;
        }
        inventory.clear();
        Map<Integer, LobbyEntry> slots = new HashMap<>();
        int visible = Math.min(inventory.getSize(), lobbies.size());
        for (int i = 0; i < visible; i++) {
            int slot = i;
            LobbyEntry lobby = lobbies.get(i);
            slots.put(slot, lobby);
            set(inventory, slot, buildLobbyItem(lobby, i + 1));
        }
        views.put(player.getUniqueId(), slots);

    }

    private int sizeForLobbies(int lobbyCount) {
        int slotsNeeded = Math.max(1, lobbyCount);
        int rows = (int) Math.ceil(slotsNeeded / 9.0);
        rows = Math.max(1, Math.min(6, rows));
        int size = rows * 9;
        return Math.max(DEFAULT_SIZE, Math.min(MAX_SIZE, size));
    }

    private ItemStack buildLobbyItem(LobbyEntry lobby, int number) {
        boolean current = isCurrent(lobby.name);
        boolean full = lobby.maxPlayers > 0 && lobby.players >= lobby.maxPlayers;
        boolean hasFriends = !lobby.onlineFriends.isEmpty();
        ItemStack item = lobbyBlockItem(current, full, hasFriends, number);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        String nameColor = (current || full) ? ChatColor.RED.toString() : ChatColor.GREEN.toString();
        int lobbyNumber = Math.max(1, number);
        String lobbyLabel = HubMessageUtil.gameDisplayName(hubType) + " Lobby";
        meta.setDisplayName(nameColor + lobbyLabel + " #" + lobbyNumber);

        List<String> lore = new ArrayList<>();
        String max = lobby.maxPlayers <= 0 ? "?" : String.valueOf(lobby.maxPlayers);
        lore.add(ChatColor.GRAY + "Players: " + Math.max(0, lobby.players) + "/" + max);
        if (!current && !lobby.onlineFriends.isEmpty()) {
            lore.add("");
            lore.add(ChatColor.GRAY + "Online Friends:");
            int shown = 0;
            for (String friend : lobby.onlineFriends) {
                if (friend == null || friend.trim().isEmpty()) {
                    continue;
                }
                lore.add(friend.trim());
                shown++;
                if (shown >= 3) {
                    break;
                }
            }
        }
        lore.add("");
        if (current) {
            lore.add(ChatColor.RED + "Already connected!");
        } else if (full) {
            lore.add(ChatColor.RED + "Full!");
        } else {
            lore.add(ChatColor.YELLOW + "Click to connect!");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack lobbyBlockItem(boolean current, boolean full, boolean hasFriends, int amount) {
        int safeAmount = Math.max(1, Math.min(64, amount));
        if (current) {
            return redHardenedClay(safeAmount);
        }
        if (full) {
            return quartzBlockItem(safeAmount);
        }
        if (hasFriends) {
            return lightBlueHardenedClay(safeAmount);
        }
        return quartzBlockItem(safeAmount);
    }

    private ItemStack quartzBlockItem(int amount) {
        Material quartz = Material.matchMaterial("QUARTZ_BLOCK");
        if (quartz != null) {
            return new ItemStack(quartz, amount);
        }
        Material fallback = Material.matchMaterial("QUARTZ");
        return new ItemStack(fallback == null ? Material.PAPER : fallback, amount);
    }

    private ItemStack redHardenedClay(int amount) {
        Material modern = Material.matchMaterial("RED_TERRACOTTA");
        if (modern != null) {
            return new ItemStack(modern, amount);
        }
        Material stainedClay = Material.matchMaterial("STAINED_CLAY");
        if (stainedClay != null) {
            return new ItemStack(stainedClay, amount, (short) 14);
        }
        Material hardenedClay = Material.matchMaterial("HARD_CLAY");
        return new ItemStack(hardenedClay == null ? Material.BRICK : hardenedClay, amount);
    }

    private ItemStack lightBlueHardenedClay(int amount) {
        Material modern = Material.matchMaterial("LIGHT_BLUE_TERRACOTTA");
        if (modern != null) {
            return new ItemStack(modern, amount);
        }
        Material stainedClay = Material.matchMaterial("STAINED_CLAY");
        if (stainedClay != null) {
            return new ItemStack(stainedClay, amount, (short) 3);
        }
        Material hardenedClay = Material.matchMaterial("HARD_CLAY");
        return new ItemStack(hardenedClay == null ? Material.BRICK : hardenedClay, amount);
    }

    private List<LobbyEntry> loadLobbies(Player player) {
        refreshLobbyDocsIfNeeded(false);
        ServerType targetType = hubType == null ? ServerType.UNKNOWN : hubType;
        if (!targetType.isHub()) {
            return Collections.emptyList();
        }
        UUID playerUuid = player == null ? null : player.getUniqueId();
        Set<UUID> friendUuids = getFriendUuids(playerUuid);
        ensureFriendDisplayNames(friendUuids);
        List<Document> docs = cachedLobbyDocs;
        if (docs == null || docs.isEmpty()) {
            refreshLobbyDocsIfNeeded(true);
            return Collections.emptyList();
        }
        List<LobbyEntry> lobbies = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Document doc : docs) {
            if (!isValid(doc, now)) {
                continue;
            }
            String name = safeString(doc.getString("_id"));
            if (name.isEmpty()) {
                continue;
            }
            lobbies.add(new LobbyEntry(
                    name,
                    safeInt(doc.get("players")),
                    safeInt(doc.get("maxPlayers")),
                    resolveOnlineFriends(doc, friendUuids)
            ));
        }
        lobbies.sort(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        return lobbies;
    }

    private void refreshLobbyDocsIfNeeded(boolean force) {
        if (plugin == null) {
            return;
        }
        if (!force && System.currentTimeMillis() - lastRegistryRefreshAt < dataRefreshMillis) {
            return;
        }
        if (!registryRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cachedLobbyDocs = queryLobbyDocs();
                lastRegistryRefreshAt = System.currentTimeMillis();
                refreshOpenMenusSoon();
            } finally {
                registryRefreshInProgress.set(false);
            }
        });
    }

    private void refreshOpenMenusSoon() {
        if (plugin == null || views.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, this::refreshOpenMenus);
    }

    private List<Document> queryLobbyDocs() {
        MongoManager mongo = plugin == null ? null : plugin.getMongoManager();
        if (mongo == null) {
            return Collections.emptyList();
        }
        ServerType targetType = hubType == null ? ServerType.UNKNOWN : hubType;
        if (!targetType.isHub()) {
            return Collections.emptyList();
        }
        MongoCollection<Document> collection = mongo.getServerRegistry();
        if (collection == null) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();
        Map<String, Document> latestByServerId = new HashMap<>();
        Map<String, Long> heartbeatByServerId = new HashMap<>();
        for (Document doc : collection.find(Filters.eq("type", targetType.getId()))) {
            if (!isValid(doc, now)) {
                continue;
            }
            String serverId = safeString(doc.getString("_id"));
            if (serverId.isEmpty()) {
                continue;
            }
            Long heartbeat = doc.getLong("lastHeartbeat");
            long ts = heartbeat == null ? Long.MIN_VALUE : heartbeat.longValue();
            String key = serverId.toLowerCase(Locale.ROOT);
            Long existing = heartbeatByServerId.get(key);
            if (existing != null && existing >= ts) {
                continue;
            }
            heartbeatByServerId.put(key, ts);
            latestByServerId.put(key, doc);
        }

        List<Document> docs = new ArrayList<>(latestByServerId.values());
        docs.sort(Comparator.comparing(doc -> safeString(doc.getString("_id")).toLowerCase(Locale.ROOT)));
        return docs;
    }

    private Set<UUID> getFriendUuids(UUID playerUuid) {
        if (playerUuid == null) {
            return Collections.emptySet();
        }
        Set<UUID> cached = cachedFriendUuids.get(playerUuid);
        refreshFriendUuidsIfNeeded(playerUuid, cached == null);
        return cached == null ? Collections.emptySet() : cached;
    }

    private void refreshFriendUuidsIfNeeded(UUID playerUuid, boolean force) {
        if (plugin == null || playerUuid == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastFriendRefreshAt.getOrDefault(playerUuid, 0L);
        if (!force && now - last < dataRefreshMillis) {
            return;
        }
        if (!friendRefreshInProgress.add(playerUuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Set<UUID> friends = queryFriendUuids(playerUuid);
                cachedFriendUuids.put(playerUuid, friends);
                refreshFriendDisplayNames(friends);
                lastFriendRefreshAt.put(playerUuid, System.currentTimeMillis());
                refreshOpenMenusSoon();
            } finally {
                friendRefreshInProgress.remove(playerUuid);
            }
        });
    }

    private void refreshFriendDisplayNames(Set<UUID> friendUuids) {
        if (plugin == null || friendUuids == null || friendUuids.isEmpty()) {
            return;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return;
        }
        MongoCollection<Document> profiles = mongo.getProfiles();
        if (profiles == null) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (UUID friendUuid : friendUuids) {
            if (friendUuid != null) {
                ids.add(friendUuid.toString());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        for (Document doc : profiles.find(Filters.in("uuid", ids))) {
            if (doc == null) {
                continue;
            }
            String rawUuid = safeString(doc.getString("uuid"));
            if (rawUuid.isEmpty()) {
                continue;
            }
            UUID friendUuid;
            try {
                friendUuid = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String name = safeString(doc.getString("name"));
            if (name.isEmpty()) {
                continue;
            }
            Rank rank = parseRank(doc.getString("rank"));
            String plusColor = safeString(doc.getString("plusColor"));
            String mvpPlusPlusPrefixColor = safeString(doc.getString("mvpPlusPlusPrefixColor"));
            String prefix = RankFormatUtil.buildPrefix(
                    rank,
                    safeInt(doc.get("networkLevel")),
                    plusColor.isEmpty() ? null : plusColor,
                    mvpPlusPlusPrefixColor.isEmpty() ? null : mvpPlusPlusPrefixColor
            );
            ChatColor color = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor.isEmpty() ? null : mvpPlusPlusPrefixColor);
            cachedFriendDisplayNames.put(friendUuid, prefix + color + name);
        }
    }

    private void ensureFriendDisplayNames(Set<UUID> friendUuids) {
        if (friendUuids == null || friendUuids.isEmpty()) {
            return;
        }
        for (UUID friendUuid : friendUuids) {
            if (friendUuid != null && !cachedFriendDisplayNames.containsKey(friendUuid)) {
                refreshFriendDisplayNames(friendUuids);
                return;
            }
        }
    }

    private Set<UUID> queryFriendUuids(UUID playerUuid) {
        MongoManager mongo = plugin == null ? null : plugin.getMongoManager();
        if (mongo == null || playerUuid == null) {
            return Collections.emptySet();
        }
        MongoCollection<Document> friends = mongo.getCollection("friends");
        if (friends == null) {
            return Collections.emptySet();
        }
        Document doc = friends.find(Filters.eq("uuid", playerUuid.toString())).first();
        if (doc == null) {
            return Collections.emptySet();
        }
        @SuppressWarnings("unchecked")
        List<String> stored = (List<String>) doc.get("friends");
        if (stored == null || stored.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> result = new HashSet<>();
        for (String raw : stored) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                result.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ignored) {
                // ignore malformed uuid
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private List<String> resolveOnlineFriends(Document doc, Set<UUID> friendUuids) {
        if (doc == null || friendUuids == null || friendUuids.isEmpty()) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<String> uuidValues = (List<String>) doc.get("onlinePlayerUuids");
        @SuppressWarnings("unchecked")
        List<String> nameValues = (List<String>) doc.get("onlinePlayerNames");
        if (uuidValues == null || nameValues == null || uuidValues.isEmpty() || nameValues.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        int length = Math.min(uuidValues.size(), nameValues.size());
        for (int i = 0; i < length; i++) {
            String rawUuid = uuidValues.get(i);
            String rawName = nameValues.get(i);
            if (rawUuid == null || rawName == null || rawName.trim().isEmpty()) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(rawUuid.trim());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (friendUuids.contains(uuid)) {
                names.add(formatRankColoredFriendName(uuid, rawName.trim()));
            }
        }
        return names;
    }

    private String formatRankColoredFriendName(UUID uuid, String name) {
        String safeName = safeString(name);
        if (safeName.isEmpty()) {
            return "";
        }
        String cachedFormatted = cachedFriendDisplayNames.get(uuid);
        if (cachedFormatted != null && !cachedFormatted.trim().isEmpty()) {
            return cachedFormatted;
        }
        String loadedFormatted = loadFriendDisplayName(uuid, safeName);
        if (loadedFormatted != null && !loadedFormatted.trim().isEmpty()) {
            return loadedFormatted;
        }
        if (plugin == null || uuid == null) {
            return ChatColor.GREEN + safeName;
        }
        try {
            Profile profile = plugin.getProfile(uuid);
            Rank rank = profile == null ? plugin.getRank(uuid) : profile.getRank();
            if (rank == null) {
                rank = Rank.DEFAULT;
            }
            int networkLevel = profile == null ? plugin.getNetworkLevel(uuid) : profile.getNetworkLevel();
            String plusColor = profile == null ? null : profile.getPlusColor();
            String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
            String prefix = RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
            ChatColor color = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
            return prefix + color + safeName;
        } catch (Exception ignored) {
            return ChatColor.GREEN + safeName;
        }
    }

    private String loadFriendDisplayName(UUID uuid, String fallbackName) {
        if (plugin == null || uuid == null) {
            return null;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return null;
        }
        MongoCollection<Document> profiles = mongo.getProfiles();
        if (profiles == null) {
            return null;
        }
        Document doc = profiles.find(Filters.eq("uuid", uuid.toString()))
                .projection(new Document("uuid", 1)
                        .append("name", 1)
                        .append("rank", 1)
                        .append("networkLevel", 1)
                        .append("plusColor", 1)
                        .append("mvpPlusPlusPrefixColor", 1))
                .first();
        if (doc == null) {
            return null;
        }
        String name = safeString(doc.getString("name"));
        if (name.isEmpty()) {
            name = fallbackName == null ? "" : fallbackName;
        }
        if (name.trim().isEmpty()) {
            return null;
        }
        Rank rank = parseRank(doc.getString("rank"));
        String plusColor = safeString(doc.getString("plusColor"));
        String mvpPlusPlusPrefixColor = safeString(doc.getString("mvpPlusPlusPrefixColor"));
        String prefix = RankFormatUtil.buildPrefix(
                rank,
                safeInt(doc.get("networkLevel")),
                plusColor.isEmpty() ? null : plusColor,
                mvpPlusPlusPrefixColor.isEmpty() ? null : mvpPlusPlusPrefixColor
        );
        ChatColor color = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor.isEmpty() ? null : mvpPlusPlusPrefixColor);
        String formatted = prefix + color + name;
        cachedFriendDisplayNames.put(uuid, formatted);
        return formatted;
    }

    private Rank parseRank(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Rank.DEFAULT;
        }
        try {
            return Rank.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Rank.DEFAULT;
        }
    }

    private boolean isValid(Document doc, long now) {
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
        Long heartbeat = doc.getLong("lastHeartbeat");
        return heartbeat == null || staleSeconds <= 0 || now - heartbeat <= staleSeconds * 1000L;
    }

    private boolean isCurrent(String name) {
        if (name == null || currentServerId == null) {
            return false;
        }
        return name.equalsIgnoreCase(currentServerId.trim());
    }

    private void sendConnect(Player player, String targetServer) {
        if (player == null || targetServer == null || targetServer.trim().isEmpty()) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(targetServer.trim());
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Failed to switch lobbies right now!");
            if (plugin != null) {
                plugin.getLogger().warning("Failed to send lobby connect request!\n" + ex.getMessage());
            }
        }
    }

    private static String formatTitle(CorePlugin plugin) {
        if (plugin == null || plugin.getServerType() == null) {
            return "Lobby Selector";
        }
        ServerType type = plugin.getServerType().toHubType();
        String gameName = HubMessageUtil.gameDisplayName(type);
        return gameName + " Lobby Selector";
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

    private static class LobbyEntry {
        private final String name;
        private final int players;
        private final int maxPlayers;
        private final List<String> onlineFriends;

        private LobbyEntry(String name, int players, int maxPlayers, List<String> onlineFriends) {
            this.name = name;
            this.players = players;
            this.maxPlayers = maxPlayers;
            this.onlineFriends = onlineFriends == null ? Collections.emptyList() : onlineFriends;
        }
    }
}
