package io.github.mebsic.core.menu;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.HubMessageUtil;
import io.github.mebsic.core.util.HycopyExperienceUtil;
import io.github.mebsic.core.util.RankFormatUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FindMenu extends Menu {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 21;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final long DEFAULT_REFRESH_TICKS = 20L;
    private static final String CHANNEL = "BungeeCord";
    private static final Map<UUID, BukkitTask> LIVE_REFRESH_TASKS = new ConcurrentHashMap<UUID, BukkitTask>();
    private static final int[] PLAYER_SLOTS = new int[] {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a z", Locale.US);

    private final CorePlugin plugin;
    private final Map<Integer, FindPlayer> slots;
    private List<FindPlayer> players;
    private int page;
    private int totalPages;

    private FindMenu(CorePlugin plugin, List<FindPlayer> players, int page, int totalPages) {
        super(formatTitle(page), SIZE);
        this.plugin = plugin;
        this.players = players == null ? Collections.emptyList() : players;
        this.page = Math.max(1, page);
        this.totalPages = Math.max(1, totalPages);
        this.slots = new HashMap<Integer, FindPlayer>();
    }

    public static void openFor(CorePlugin plugin, Player viewer, int requestedPage) {
        if (viewer == null) {
            return;
        }
        List<FindPlayer> players = loadPlayers(plugin);
        openWithPlayers(plugin, viewer, players, requestedPage);
    }

    private static void openWithPlayers(CorePlugin plugin, Player viewer, List<FindPlayer> players, int requestedPage) {
        int totalPages = totalPages(players);
        int page = clampPage(requestedPage, totalPages);
        openSnapshot(plugin, viewer, players, page, totalPages);
    }

    private static void openSnapshot(CorePlugin plugin,
                                     Player viewer,
                                     List<FindPlayer> players,
                                     int page,
                                     int totalPages) {
        if (viewer == null) {
            return;
        }
        FindMenu menu = new FindMenu(plugin, players, page, totalPages);
        menu.open(viewer);
        startLiveRefresh(plugin, viewer, menu);
    }

    private static void startLiveRefresh(CorePlugin plugin, Player viewer, FindMenu menu) {
        if (plugin == null || viewer == null || menu == null) {
            return;
        }
        final UUID viewerId = viewer.getUniqueId();
        cancelLiveRefresh(viewerId);
        long refreshTicks = Math.max(1L, plugin.getConfig().getLong("menus.findMenuRefreshTicks", DEFAULT_REFRESH_TICKS));
        final BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                BukkitTask task = taskRef[0];
                Player online = Bukkit.getPlayer(viewerId);
                if (online == null || !online.isOnline() || !isViewingMenu(online, menu)) {
                    cancelLiveRefresh(viewerId, task);
                    return;
                }
                Inventory top = online.getOpenInventory() == null ? null : online.getOpenInventory().getTopInventory();
                menu.refresh(online, top);
            }
        }, refreshTicks, refreshTicks);
        LIVE_REFRESH_TASKS.put(viewerId, taskRef[0]);
    }

    private static void cancelLiveRefresh(UUID viewerId) {
        if (viewerId == null) {
            return;
        }
        BukkitTask task = LIVE_REFRESH_TASKS.remove(viewerId);
        if (task != null) {
            task.cancel();
        }
    }

    private static void cancelLiveRefresh(UUID viewerId, BukkitTask expectedTask) {
        if (viewerId == null) {
            return;
        }
        if (expectedTask == null) {
            cancelLiveRefresh(viewerId);
            return;
        }
        LIVE_REFRESH_TASKS.remove(viewerId, expectedTask);
        expectedTask.cancel();
    }

    public static void shutdownLiveRefreshes() {
        for (BukkitTask task : LIVE_REFRESH_TASKS.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        LIVE_REFRESH_TASKS.clear();
    }

    private static boolean isViewingMenu(Player player, FindMenu menu) {
        if (player == null || menu == null || player.getOpenInventory() == null) {
            return false;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null || !(top.getHolder() instanceof MenuHolder)) {
            return false;
        }
        return ((MenuHolder) top.getHolder()).getMenu() == menu;
    }

    private void refresh(Player viewer, Inventory inventory) {
        if (viewer == null || inventory == null) {
            return;
        }
        List<FindPlayer> refreshedPlayers = loadPlayers(plugin);
        int refreshedTotalPages = totalPages(refreshedPlayers);
        int refreshedPage = clampPage(page, refreshedTotalPages);
        if (!formatTitle(refreshedPage).equals(getTitle())) {
            openSnapshot(plugin, viewer, refreshedPlayers, refreshedPage, refreshedTotalPages);
            return;
        }
        this.players = refreshedPlayers == null ? Collections.<FindPlayer>emptyList() : refreshedPlayers;
        this.page = refreshedPage;
        this.totalPages = refreshedTotalPages;
        populate(viewer, inventory);
    }

    private static int totalPages(List<FindPlayer> players) {
        int size = players == null ? 0 : players.size();
        return Math.max(1, (int) Math.ceil(size / (double) PAGE_SIZE));
    }

    private static int clampPage(int requestedPage, int totalPages) {
        return Math.max(1, Math.min(Math.max(1, requestedPage), Math.max(1, totalPages)));
    }

    private static String formatTitle(int page) {
        int safePage = Math.max(1, page);
        return safePage <= 1 ? "Players" : "Players (Page " + safePage + ")";
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        slots.clear();
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(players.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int slot = PLAYER_SLOTS[index - start];
            FindPlayer target = players.get(index);
            slots.put(slot, target);
            set(inventory, slot, playerHead(target, isCurrentServer(target.serverId)));
        }
        if (page > 1) {
            set(inventory, PREVIOUS_SLOT, item(Material.ARROW, ChatColor.GREEN + "Go Back"));
        }
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
        if (page < totalPages) {
            set(inventory, NEXT_SLOT, item(Material.ARROW, ChatColor.GREEN + "Next Page"));
        }
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player viewer = click.getPlayer();
        int slot = click.getRawSlot();
        if (slot == CLOSE_SLOT) {
            viewer.closeInventory();
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 1) {
            openFor(plugin, viewer, page - 1);
            return;
        }
        if (slot == NEXT_SLOT && page < totalPages) {
            openFor(plugin, viewer, page + 1);
            return;
        }
        FindPlayer target = slots.get(slot);
        if (target == null) {
            return;
        }
        if (isCurrentServer(target.serverId)) {
            viewer.sendMessage(ChatColor.RED + "You are already connected to that server!");
            return;
        }
        viewer.closeInventory();
        viewer.sendMessage(ChatColor.GREEN + HubMessageUtil.transferToServerMessage(target.serverId));
        sendConnect(viewer, target.serverId);
    }

    private ItemStack playerHead(FindPlayer target, boolean currentServer) {
        Material head = resolveHeadMaterial();
        ItemStack item;
        if (head == null) {
            item = new ItemStack(Material.PAPER);
        } else if ("SKULL_ITEM".equals(head.name())) {
            item = new ItemStack(head, 1, (short) 3);
        } else {
            item = new ItemStack(head, 1);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || target == null) {
            return item;
        }
        meta.setDisplayName(displayName(target));
        List<String> lore = new ArrayList<String>();
        lore.add("");
        lore.add(ChatColor.GRAY + "Server: " + ChatColor.GREEN + safeString(target.serverId));
        lore.add("");
        lore.add(currentServer ? ChatColor.RED + "Already connected!" : ChatColor.YELLOW + "Click to connect!");
        meta.setLore(lore);
        if (meta instanceof SkullMeta) {
            applySkullOwner((SkullMeta) meta, target.uuid, target.name);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String displayName(FindPlayer target) {
        if (target == null) {
            return ChatColor.GRAY + "Unknown";
        }
        Rank rank = target.rank == null ? Rank.DEFAULT : target.rank;
        if (rank == Rank.DEFAULT) {
            return ChatColor.GRAY + target.name;
        }
        String prefix = RankFormatUtil.buildPrefix(
                rank,
                target.networkLevel,
                target.plusColor,
                target.mvpPlusPlusPrefixColor
        );
        return prefix + RankFormatUtil.baseColor(rank, target.mvpPlusPlusPrefixColor) + target.name;
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
            player.sendMessage(ChatColor.RED + "Failed to connect right now!");
            plugin.getLogger().warning("Failed to send find menu connect request!\n" + ex.getMessage());
        }
    }

    private boolean isCurrentServer(String serverId) {
        String current = plugin == null ? "" : safeString(plugin.getConfig().getString("server.id", ""));
        String target = safeString(serverId);
        return !current.isEmpty() && current.equalsIgnoreCase(target);
    }

    private static List<FindPlayer> loadPlayers(CorePlugin plugin) {
        if (plugin == null || plugin.getMongoManager() == null) {
            return Collections.emptyList();
        }
        Map<UUID, FindPlayer> online = loadOnlinePlayers(plugin);
        if (online.isEmpty()) {
            return Collections.emptyList();
        }
        applyProfileData(plugin, online);
        List<FindPlayer> result = new ArrayList<FindPlayer>(online.values());
        Collections.sort(result, PLAYER_COMPARATOR);
        return result;
    }

    private static Map<UUID, FindPlayer> loadOnlinePlayers(CorePlugin plugin) {
        MongoCollection<Document> registry = plugin.getMongoManager().getServerRegistry();
        if (registry == null) {
            return Collections.emptyMap();
        }
        Map<UUID, FindPlayer> players = new LinkedHashMap<UUID, FindPlayer>();
        String group = safeString(plugin.getConfig().getString("server.group", ""));
        int staleSeconds = Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        long now = System.currentTimeMillis();
        for (Document doc : registry.find()) {
            if (!isUsableServerDocument(doc, group, staleSeconds, now)) {
                continue;
            }
            addPlayersFromServerDocument(players, doc);
        }
        addCurrentServerPlayers(plugin, players);
        return players;
    }

    private static boolean isUsableServerDocument(Document doc, String group, int staleSeconds, long now) {
        if (doc == null) {
            return false;
        }
        if (!group.isEmpty() && !group.equalsIgnoreCase(safeString(doc.getString("group")))) {
            return false;
        }
        if (!"online".equalsIgnoreCase(safeString(doc.getString("status")))) {
            return false;
        }
        if (staleSeconds > 0) {
            Long heartbeat = doc.getLong("lastHeartbeat");
            if (heartbeat == null || now - heartbeat > staleSeconds * 1000L) {
                return false;
            }
        }
        return true;
    }

    private static void addPlayersFromServerDocument(Map<UUID, FindPlayer> players, Document doc) {
        if (players == null || doc == null) {
            return;
        }
        String serverId = safeString(doc.getString("_id"));
        @SuppressWarnings("unchecked")
        List<String> uuidValues = (List<String>) doc.get("onlinePlayerUuids");
        @SuppressWarnings("unchecked")
        List<String> nameValues = (List<String>) doc.get("onlinePlayerNames");
        if (uuidValues == null || nameValues == null || uuidValues.isEmpty() || nameValues.isEmpty()) {
            return;
        }
        int length = Math.min(uuidValues.size(), nameValues.size());
        for (int i = 0; i < length; i++) {
            UUID uuid = parseUuid(uuidValues.get(i));
            String name = safeString(nameValues.get(i));
            if (uuid == null || name.isEmpty()) {
                continue;
            }
            players.put(uuid, new FindPlayer(uuid, name, serverId));
        }
    }

    private static void addCurrentServerPlayers(CorePlugin plugin, Map<UUID, FindPlayer> players) {
        if (plugin == null || players == null) {
            return;
        }
        String currentServerId = safeString(plugin.getConfig().getString("server.id", ""));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            players.put(player.getUniqueId(), new FindPlayer(player.getUniqueId(), player.getName(), currentServerId));
        }
    }

    private static void applyProfileData(CorePlugin plugin, Map<UUID, FindPlayer> players) {
        MongoCollection<Document> profiles = plugin.getMongoManager().getProfiles();
        if (profiles == null || players == null || players.isEmpty()) {
            return;
        }
        List<String> uuidValues = new ArrayList<String>();
        for (UUID uuid : players.keySet()) {
            if (uuid != null) {
                uuidValues.add(uuid.toString());
            }
        }
        if (uuidValues.isEmpty()) {
            return;
        }
        for (Document doc : profiles.find(Filters.in("uuid", uuidValues))) {
            if (doc == null) {
                continue;
            }
            UUID uuid = parseUuid(doc.getString("uuid"));
            FindPlayer player = uuid == null ? null : players.get(uuid);
            if (player == null) {
                continue;
            }
            player.rank = readRank(doc);
            player.networkLevel = readNetworkLevel(doc);
            player.plusColor = doc.getString("plusColor");
            player.mvpPlusPlusPrefixColor = doc.getString("mvpPlusPlusPrefixColor");
            player.firstLogin = doc.getString(MongoManager.PROFILE_FIRST_LOGIN_KEY);
            player.lastLogin = doc.getString(MongoManager.PROFILE_LAST_LOGIN_KEY);
        }
    }

    private static Rank readRank(Document doc) {
        String raw = doc == null ? null : doc.getString("rank");
        if (raw == null || raw.trim().isEmpty()) {
            return Rank.DEFAULT;
        }
        try {
            return Rank.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return Rank.DEFAULT;
        }
    }

    private static int readNetworkLevel(Document doc) {
        if (doc == null) {
            return 0;
        }
        Long hycopyExperience = doc.getLong("hycopyExperience");
        if (hycopyExperience != null) {
            return HycopyExperienceUtil.getLevel(hycopyExperience);
        }
        Integer networkLevel = doc.getInteger("networkLevel");
        return networkLevel == null ? 0 : Math.max(0, networkLevel);
    }

    private static String formatTimestamp(String raw) {
        String value = safeString(raw);
        if (value.isEmpty()) {
            return "Unknown";
        }
        try {
            return DISPLAY_DATE_FORMAT.format(ZonedDateTime.parse(value));
        } catch (Exception ignored) {
            return value;
        }
    }

    private static void applySkullOwner(SkullMeta meta, UUID uuid, String ownerName) {
        if (meta == null) {
            return;
        }
        if (uuid != null) {
            try {
                java.lang.reflect.Method setOwningPlayer = meta.getClass()
                        .getMethod("setOwningPlayer", OfflinePlayer.class);
                setOwningPlayer.invoke(meta, Bukkit.getOfflinePlayer(uuid));
                return;
            } catch (Exception ignored) {
            }
        }
        String safeOwner = safeString(ownerName);
        if (!safeOwner.isEmpty()) {
            meta.setOwner(safeOwner);
        }
    }

    private static Material resolveHeadMaterial() {
        Material modern = Material.matchMaterial("PLAYER_HEAD");
        if (modern != null) {
            return modern;
        }
        return Material.matchMaterial("SKULL_ITEM");
    }

    private static UUID parseUuid(String value) {
        String safe = safeString(value);
        if (safe.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(safe);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    private static final Comparator<FindPlayer> PLAYER_COMPARATOR = new Comparator<FindPlayer>() {
        @Override
        public int compare(FindPlayer first, FindPlayer second) {
            Rank firstRank = first == null || first.rank == null ? Rank.DEFAULT : first.rank;
            Rank secondRank = second == null || second.rank == null ? Rank.DEFAULT : second.rank;
            int rankCompare = Integer.compare(secondRank.getWeight(), firstRank.getWeight());
            if (rankCompare != 0) {
                return rankCompare;
            }
            String firstName = first == null ? "" : safeString(first.name);
            String secondName = second == null ? "" : safeString(second.name);
            return firstName.compareToIgnoreCase(secondName);
        }
    };

    private static final class FindPlayer {
        private final UUID uuid;
        private final String name;
        private final String serverId;
        private Rank rank;
        private int networkLevel;
        private String plusColor;
        private String mvpPlusPlusPrefixColor;
        private String firstLogin;
        private String lastLogin;

        private FindPlayer(UUID uuid, String name, String serverId) {
            this.uuid = uuid;
            this.name = safeString(name);
            this.serverId = safeString(serverId);
            this.rank = Rank.DEFAULT;
            this.networkLevel = 0;
        }
    }
}
