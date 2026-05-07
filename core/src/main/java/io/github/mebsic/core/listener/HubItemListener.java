package io.github.mebsic.core.listener;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.menu.CollectiblesMenu;
import io.github.mebsic.core.menu.GameMenu;
import io.github.mebsic.core.menu.LobbySelectorMenu;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.QueueClient;
import io.github.mebsic.core.service.ServerRegistrySnapshot;
import io.github.mebsic.core.util.CommonMessages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bson.Document;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HubItemListener implements Listener {
    private static final String FRIEND_VISIBILITY_UPDATE_CHANNEL = "friend_visibility_update";
    private static final String VISIBILITY_ENABLED_MESSAGE = ChatColor.GREEN + "Player visibility enabled!";
    private static final String VISIBILITY_DISABLED_MESSAGE = ChatColor.RED + "Player visibility disabled!";
    private static final String GAME_MENU_NAME = ChatColor.GREEN + "Game Menu " + ChatColor.GRAY + "(Right Click)";
    private static final String PLAYERS_SHOWN_NAME =
            ChatColor.WHITE + "Players: " + ChatColor.GREEN + "Shown " + ChatColor.GRAY + "(Right Click)";
    private static final String PLAYERS_HIDDEN_NAME =
            ChatColor.WHITE + "Players: " + ChatColor.RED + "Hidden " + ChatColor.GRAY + "(Right Click)";
    private static final String PROFILE_NAME = ChatColor.GREEN + "My Profile " + ChatColor.GRAY + "(Right Click)";
    private static final String COLLECTIBLES_NAME = ChatColor.GREEN + "Collectibles " + ChatColor.GRAY + "(Right Click)";
    private static final String LOBBY_SELECTOR_NAME = ChatColor.GREEN + "Lobby Selector " + ChatColor.GRAY + "(Right Click)";
    private static final long TOGGLE_COOLDOWN_MS = 3000L;
    private static final long FRIEND_REFRESH_MS = 10_000L;
    private static final int GAME_MENU_SLOT = 0;
    private static final int PROFILE_SLOT = 1;
    private static final int COLLECTIBLES_SLOT = 4;
    private static final int TOGGLE_SLOT = 7;
    private static final int LOBBY_SELECTOR_SLOT = 8;
    private static final int PORTAL_TRIGGER_RADIUS_BLOCKS = 2;
    private static final long PORTAL_MENU_REOPEN_COOLDOWN_MS = 500L;
    private static final String HUB_CONNECT_CHANNEL = "BungeeCord";
    private static final String NO_HUB_AVAILABLE_MESSAGE =
            ChatColor.RED + CommonMessages.NO_SERVERS_AVAILABLE;
    private static final String HUB_TRANSFER_FAILED_MESSAGE =
            ChatColor.RED + "Cannot send you to an available lobby! Please try again later.";
    private static final Material NETHER_PORTAL_MATERIAL = Material.matchMaterial("NETHER_PORTAL");
    private static final Material PORTAL_MATERIAL = Material.matchMaterial("PORTAL");
    private static final Material LEGACY_PORTAL_MATERIAL = Material.matchMaterial("LEGACY_PORTAL");

    private final CorePlugin plugin;
    private final GameMenu gameMenu;
    private final CollectiblesMenu collectiblesMenu;
    private final LobbySelectorMenu lobbySelectorMenu;
    private final NumberFormat numberFormat;
    private final Map<UUID, Boolean> visibility;
    private final Map<UUID, Long> toggleCooldown;
    private final Map<UUID, Set<UUID>> cachedFriendUuids;
    private final Map<UUID, Long> lastFriendRefreshAt;
    private final Set<UUID> friendRefreshInProgress;
    private final Set<UUID> playersInPortalZone;
    private final Map<UUID, Long> portalMenuReopenAllowedAt;
    private final ServerType hubType;
    private final String group;
    private final String currentServerId;
    private final int staleSeconds;

    public HubItemListener(CorePlugin plugin, QueueClient queueClient, ServerRegistrySnapshot registrySnapshot) {
        this.plugin = plugin;
        this.gameMenu = new GameMenu(plugin, queueClient, registrySnapshot);
        this.collectiblesMenu = new CollectiblesMenu(plugin);
        this.lobbySelectorMenu = new LobbySelectorMenu(plugin);
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.visibility = new ConcurrentHashMap<>();
        this.toggleCooldown = new ConcurrentHashMap<>();
        this.cachedFriendUuids = new ConcurrentHashMap<>();
        this.lastFriendRefreshAt = new ConcurrentHashMap<>();
        this.friendRefreshInProgress = ConcurrentHashMap.newKeySet();
        this.playersInPortalZone = ConcurrentHashMap.newKeySet();
        this.portalMenuReopenAllowedAt = new ConcurrentHashMap<>();
        ServerType currentType = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        this.hubType = currentType == null ? ServerType.UNKNOWN : currentType.toHubType();
        this.group = plugin == null ? "" : plugin.getConfig().getString("server.group", "");
        this.currentServerId = plugin == null ? "" : plugin.getConfig().getString("server.id", "");
        this.staleSeconds = plugin == null ? 20 : Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        subscribeToFriendVisibilityUpdates();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isNpcPlayer(player)) {
            return;
        }
        visibility.put(player.getUniqueId(), resolveInitialVisibility(player));
        refreshFriendUuidsIfNeeded(player.getUniqueId(), true);
        UUID uuid = player.getUniqueId();
        playersInPortalZone.remove(uuid);
        portalMenuReopenAllowedAt.remove(uuid);
        giveItems(player);
        applyExistingVisibility(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (isNpcPlayer(event.getPlayer())) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        visibility.remove(uuid);
        toggleCooldown.remove(uuid);
        cachedFriendUuids.remove(uuid);
        lastFriendRefreshAt.remove(uuid);
        friendRefreshInProgress.remove(uuid);
        playersInPortalZone.remove(uuid);
        portalMenuReopenAllowedAt.remove(uuid);
        lobbySelectorMenu.clear(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (isNpcPlayer(player)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean sameBlock = from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
        if (sameBlock) {
            return;
        }

        boolean nearPortal = isNearNetherPortal(to);
        if (!nearPortal) {
            playersInPortalZone.remove(uuid);
            return;
        }
        if (!playersInPortalZone.add(uuid)) {
            return;
        }

        triggerPortalHubTransfer(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        if (isNpcPlayer(player)) {
            return;
        }
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        Location from = event.getFrom();
        if (!isInsideNetherPortal(from) && !isNearNetherPortal(from)) {
            return;
        }
        event.setCancelled(true);
        triggerPortalHubTransfer(player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isNpcPlayer(event.getPlayer())) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) {
            return;
        }
        String name = meta.getDisplayName();
        if (GAME_MENU_NAME.equals(name) && item.getType() == Material.COMPASS) {
            event.setCancelled(true);
            gameMenu.open(event.getPlayer());
            return;
        }
        if (PROFILE_NAME.equals(name) && isProfileItem(item.getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Profiles are currently disabled!");
            return;
        }
        if (isCollectiblesItem(item)) {
            event.setCancelled(true);
            collectiblesMenu.open(event.getPlayer());
            return;
        }
        if (LOBBY_SELECTOR_NAME.equals(name) && isLobbySelectorItem(item.getType())) {
            event.setCancelled(true);
            lobbySelectorMenu.open(event.getPlayer());
            return;
        }
        if ((PLAYERS_SHOWN_NAME.equals(name) || PLAYERS_HIDDEN_NAME.equals(name)) && isDye(item.getType())) {
            event.setCancelled(true);
            toggleVisibility(event.getPlayer());
        }
    }

    private void giveItems(Player player) {
        player.getInventory().setItem(GAME_MENU_SLOT, buildGameMenuItem());
        player.getInventory().setItem(PROFILE_SLOT, buildProfileItem(player));
        player.getInventory().setItem(COLLECTIBLES_SLOT, buildCollectiblesItem(player));
        player.getInventory().setItem(TOGGLE_SLOT, buildVisibilityItem(isVisible(player)));
        player.getInventory().setItem(LOBBY_SELECTOR_SLOT, buildLobbySelectorItem());
    }

    private void applyExistingVisibility(Player joining) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.equals(joining)) {
                continue;
            }
            applyPairVisibility(viewer, joining);
            applyPairVisibility(joining, viewer);
        }
    }

    private boolean isVisible(Player player) {
        return visibility.getOrDefault(player.getUniqueId(), true);
    }

    private void toggleVisibility(Player player) {
        long now = System.currentTimeMillis();
        long last = toggleCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < TOGGLE_COOLDOWN_MS) {
            long remainingMs = TOGGLE_COOLDOWN_MS - (now - last);
            long remainingSeconds = Math.max(1, (long) Math.ceil(remainingMs / 1000.0));
            player.sendMessage(ChatColor.RED + "You must wait "
                    + ChatColor.YELLOW + remainingSeconds + "s"
                    + ChatColor.RED + " between uses!");
            return;
        }
        toggleCooldown.put(player.getUniqueId(), now);
        boolean currentlyVisible = isVisible(player);
        boolean nextVisible = !currentlyVisible;
        visibility.put(player.getUniqueId(), nextVisible);
        if (!nextVisible) {
            refreshFriendUuidsIfNeeded(player.getUniqueId(), true);
        }
        applyVisibilityState(player, nextVisible);
        plugin.setPlayerVisibilityEnabled(player.getUniqueId(), nextVisible);
        player.sendMessage(nextVisible ? VISIBILITY_ENABLED_MESSAGE : VISIBILITY_DISABLED_MESSAGE);
    }

    public void applyProfileVisibility(Profile profile) {
        if (profile == null) {
            return;
        }
        Player player = Bukkit.getPlayer(profile.getUuid());
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean visible = profile.isPlayerVisibilityEnabled();
        visibility.put(player.getUniqueId(), visible);
        applyVisibilityState(player, visible);
    }

    public void refreshCollectiblesItem(Player player) {
        if (player == null || isNpcPlayer(player)) {
            return;
        }
        ItemStack current = player.getInventory().getItem(COLLECTIBLES_SLOT);
        if (current != null && current.getType() != Material.AIR && !isCollectiblesItem(current)) {
            return;
        }
        player.getInventory().setItem(COLLECTIBLES_SLOT, buildCollectiblesItem(player));
        player.updateInventory();
    }

    public void shutdown() {
        visibility.clear();
        toggleCooldown.clear();
        cachedFriendUuids.clear();
        lastFriendRefreshAt.clear();
        friendRefreshInProgress.clear();
        playersInPortalZone.clear();
        portalMenuReopenAllowedAt.clear();
        gameMenu.shutdown();
        lobbySelectorMenu.shutdown();
    }

    private boolean isNearNetherPortal(Location center) {
        if (center == null) {
            return false;
        }
        World world = center.getWorld();
        if (world == null) {
            return false;
        }
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        int radius = PORTAL_TRIGGER_RADIUS_BLOCKS;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int y = centerY - 1; y <= centerY + 2; y++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (isNetherPortal(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isNetherPortal(Material material) {
        if (material == null) {
            return false;
        }
        return material == NETHER_PORTAL_MATERIAL
                || material == PORTAL_MATERIAL
                || material == LEGACY_PORTAL_MATERIAL;
    }

    private boolean isInsideNetherPortal(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (isNetherPortal(world.getBlockAt(x, y, z).getType())) {
            return true;
        }
        return isNetherPortal(world.getBlockAt(x, y + 1, z).getType());
    }

    private void triggerPortalHubTransfer(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < portalMenuReopenAllowedAt.getOrDefault(uuid, 0L)) {
            return;
        }
        portalMenuReopenAllowedAt.put(uuid, now + PORTAL_MENU_REOPEN_COOLDOWN_MS);
        playersInPortalZone.add(uuid);
        requestHubTransfer(uuid);
    }

    private void requestHubTransfer(UUID playerUuid) {
        if (plugin == null || playerUuid == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String targetHub = findBestHubServerName();
            Bukkit.getScheduler().runTask(plugin, () -> connectToHub(playerUuid, targetHub));
        });
    }

    private void connectToHub(UUID playerUuid, String targetHub) {
        if (plugin == null || playerUuid == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerUuid);
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
            player.sendPluginMessage(plugin, HUB_CONNECT_CHANNEL, bytes.toByteArray());
        } catch (Exception ex) {
            player.sendMessage(HUB_TRANSFER_FAILED_MESSAGE);
            plugin.getLogger().warning("Failed to send hub connect request from portal!\n" + ex.getMessage());
        }
    }

    private String findBestHubServerName() {
        if (plugin == null || hubType == null || !hubType.isHub()) {
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

    private boolean resolveInitialVisibility(Player player) {
        if (player == null) {
            return true;
        }
        Profile profile = plugin.getProfile(player.getUniqueId());
        return profile == null || profile.isPlayerVisibilityEnabled();
    }

    private void applyVisibilityState(Player player, boolean visible) {
        if (player == null) {
            return;
        }
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            applyPairVisibility(player, other);
        }
        player.getInventory().setItem(TOGGLE_SLOT, buildVisibilityItem(visible));
    }

    private void applyPairVisibility(Player viewer, Player target) {
        if (viewer == null || target == null || viewer.equals(target)
                || isNpcPlayer(viewer) || isNpcPlayer(target)) {
            return;
        }
        if (canViewerSee(viewer, target)) {
            viewer.showPlayer(target);
            return;
        }
        viewer.hidePlayer(target);
    }

    private boolean canViewerSee(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }
        if (isNpcPlayer(viewer) || isNpcPlayer(target)) {
            return true;
        }
        if (viewer.equals(target)) {
            return true;
        }
        if (isVisible(viewer)) {
            return true;
        }
        Set<UUID> friendUuids = getFriendUuids(viewer.getUniqueId());
        return friendUuids.contains(target.getUniqueId());
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
        if (!force && now - last < FRIEND_REFRESH_MS) {
            return;
        }
        if (!friendRefreshInProgress.add(playerUuid)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cachedFriendUuids.put(playerUuid, queryFriendUuids(playerUuid));
                lastFriendRefreshAt.put(playerUuid, System.currentTimeMillis());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player == null || !player.isOnline()) {
                        return;
                    }
                    applyVisibilityState(player, isVisible(player));
                });
            } finally {
                friendRefreshInProgress.remove(playerUuid);
            }
        });
    }

    private void subscribeToFriendVisibilityUpdates() {
        if (plugin == null || plugin.getPubSubService() == null) {
            return;
        }
        plugin.getPubSubService().subscribe(FRIEND_VISIBILITY_UPDATE_CHANNEL, this::handleFriendVisibilityUpdate);
    }

    private void handleFriendVisibilityUpdate(String payload) {
        if (plugin == null || payload == null || payload.trim().isEmpty()) {
            return;
        }
        String[] parts = payload.split(",", 2);
        if (parts.length != 2) {
            return;
        }
        UUID first = parseUuid(parts[0]);
        UUID second = parseUuid(parts[1]);
        if (first == null || second == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> applyFriendVisibilityUpdate(first, second));
    }

    private void applyFriendVisibilityUpdate(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) {
            return;
        }
        removeCachedFriendLink(first, second);
        removeCachedFriendLink(second, first);

        Player firstPlayer = Bukkit.getPlayer(first);
        Player secondPlayer = Bukkit.getPlayer(second);
        if (firstPlayer != null && secondPlayer != null && firstPlayer.isOnline() && secondPlayer.isOnline()) {
            applyPairVisibility(firstPlayer, secondPlayer);
            applyPairVisibility(secondPlayer, firstPlayer);
        }

        if (firstPlayer != null && firstPlayer.isOnline()) {
            refreshFriendUuidsIfNeeded(first, true);
        }
        if (secondPlayer != null && secondPlayer.isOnline()) {
            refreshFriendUuidsIfNeeded(second, true);
        }
    }

    private void removeCachedFriendLink(UUID owner, UUID removedFriend) {
        if (owner == null || removedFriend == null) {
            return;
        }
        Set<UUID> cached = cachedFriendUuids.get(owner);
        if (cached == null || cached.isEmpty() || !cached.contains(removedFriend)) {
            return;
        }
        Set<UUID> updated = new HashSet<>(cached);
        updated.remove(removedFriend);
        cachedFriendUuids.put(owner, Collections.unmodifiableSet(updated));
        lastFriendRefreshAt.put(owner, System.currentTimeMillis());
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

    private Set<UUID> queryFriendUuids(UUID playerUuid) {
        MongoManager mongo = plugin == null ? null : plugin.getMongoManager();
        if (mongo == null || playerUuid == null) {
            return Collections.emptySet();
        }
        MongoCollection<Document> friends = mongo.getCollection(MongoManager.FRIENDS_COLLECTION);
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
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private ItemStack buildGameMenuItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(GAME_MENU_NAME);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Right-Click to bring up the Game Menu!"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildProfileItem(Player player) {
        Material modern = Material.matchMaterial("PLAYER_HEAD");
        ItemStack item;
        if (modern != null) {
            item = new ItemStack(modern);
        } else {
            Material legacy = Material.matchMaterial("SKULL_ITEM");
            item = legacy == null ? new ItemStack(Material.BOOK) : new ItemStack(legacy, 1, (short) 3);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(PROFILE_NAME);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Right-click to browse quests, view achievements,",
                    ChatColor.GRAY + "activate Network Boosters and more!"
            ));
            if (meta instanceof SkullMeta && player != null) {
                applyProfileSkullOwner((SkullMeta) meta, player);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private void applyProfileSkullOwner(SkullMeta meta, Player player) {
        if (meta == null || player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (uuid != null) {
            try {
                java.lang.reflect.Method setOwningPlayer = meta.getClass()
                        .getMethod("setOwningPlayer", OfflinePlayer.class);
                setOwningPlayer.invoke(meta, Bukkit.getOfflinePlayer(uuid));
                return;
            } catch (Exception ignored) {
                // Legacy API fallback below.
            }
        }
        meta.setOwner(player.getName());
    }

    private ItemStack buildCollectiblesItem(Player player) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(COLLECTIBLES_NAME);
            meta.setLore(CollectiblesMenu.collectiblesLore(formatMysteryDust(player)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isCollectiblesItem(ItemStack item) {
        if (item == null || item.getType() != Material.CHEST || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) {
            return false;
        }
        return COLLECTIBLES_NAME.equals(meta.getDisplayName());
    }

    private String formatMysteryDust(Player player) {
        if (player == null || plugin == null) {
            return "0";
        }
        Profile profile = plugin.getProfile(player.getUniqueId());
        int mysteryDust = profile == null ? 0 : profile.getMysteryDust();
        return numberFormat.format(Math.max(0, mysteryDust));
    }

    private ItemStack buildLobbySelectorItem() {
        Material material = Material.matchMaterial("NETHER_STAR");
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(LOBBY_SELECTOR_NAME);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Right-click to switch between different lobbies!",
                    ChatColor.GRAY + "Use this to stay with your friends."
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack buildVisibilityItem(boolean visible) {
        Material modern = Material.matchMaterial(visible ? "LIME_DYE" : "GRAY_DYE");
        ItemStack item;
        if (modern != null) {
            item = new ItemStack(modern);
        } else {
            Material legacy = Material.matchMaterial("INK_SACK");
            short data = (short) (visible ? 10 : 8);
            item = legacy == null ? new ItemStack(Material.PAPER) : new ItemStack(legacy, 1, data);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(visible ? PLAYERS_SHOWN_NAME : PLAYERS_HIDDEN_NAME);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Right-click to toggle player visibility!"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isDye(Material material) {
        return material == Material.matchMaterial("LIME_DYE")
                || material == Material.matchMaterial("GREEN_DYE")
                || material == Material.matchMaterial("GRAY_DYE")
                || material == Material.matchMaterial("INK_SACK");
    }

    private boolean isProfileItem(Material material) {
        return material == Material.matchMaterial("PLAYER_HEAD")
                || material == Material.matchMaterial("SKULL_ITEM")
                || material == Material.BOOK;
    }

    private boolean isLobbySelectorItem(Material material) {
        return material == Material.matchMaterial("NETHER_STAR")
                || material == Material.PAPER;
    }

    private boolean isNpcPlayer(Player player) {
        return player != null && player.hasMetadata("NPC");
    }
}
