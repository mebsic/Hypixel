package io.github.mebsic.game.listener;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.ActionBarUtil;
import io.github.mebsic.core.util.RankFormatUtil;
import io.github.mebsic.game.manager.GameManager;
import io.github.mebsic.game.menu.SpectatorSettingsMenu;
import io.github.mebsic.game.menu.SpectatorTeleporterMenu;
import io.github.mebsic.game.model.GamePlayer;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.util.SpectatorItems;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class SpectatorListener implements Listener {
    private static final String CHANNEL = "BungeeCord";
    private static final String PLAY_AGAIN_INTENT_CHANNEL = "hycopy:playagain";
    private static final String DEAD_CHAT_PREFIX = ChatColor.GRAY + "[DEAD CHAT] ";
    private static final long FOLLOW_ACTION_BAR_INTERVAL_TICKS = 1L;
    private static final long TARGET_LOST_ACTION_BAR_MILLIS = 3000L;
    private static final String ACTION_BAR_TARGET_PREFIX = ChatColor.GRAY + "Target: ";
    private static final String ACTION_BAR_TARGET_NAME_STYLE = ChatColor.GREEN.toString() + ChatColor.BOLD;
    private static final String ACTION_BAR_MENU_HINT = ChatColor.GREEN + "  LEFT CLICK for menu  ";
    private static final String ACTION_BAR_EXIT_HINT = ChatColor.RED + "SNEAK to exit";
    private static final String ACTION_BAR_TARGET_LOST =
            ChatColor.RED.toString() + ChatColor.BOLD + "Target Lost" + ChatColor.GRAY + " (Right Click)";
    private static final String SPECTATE_MESSAGE_PREFIX =
            ChatColor.GREEN + "Now spectating " + ChatColor.YELLOW;
    private static final String SPECTATE_MESSAGE_SUFFIX = ChatColor.GREEN + ".";
    private static final String NO_GAME_AVAILABLE_MESSAGE =
            ChatColor.RED + "No available servers were found for this game right now!";
    private static final String TRANSFER_FAILED_MESSAGE =
            ChatColor.RED + "Failed to send you to another game server right now!";

    private final CorePlugin plugin;
    private final GameManager gameManager;
    private final ServerType gameType;
    private final String group;
    private final String currentServerId;
    private final int staleSeconds;
    private final SpectatorTeleporterMenu teleporterMenu;
    private final SpectatorSettingsMenu settingsMenu;
    private final Map<UUID, FollowState> followStates;
    private BukkitTask followActionBarTask;

    public SpectatorListener(CorePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.gameType = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        this.group = plugin == null ? "" : plugin.getConfig().getString("server.group", "");
        this.currentServerId = plugin == null ? "" : plugin.getConfig().getString("server.id", "");
        this.staleSeconds = plugin == null ? 20 : Math.max(0, plugin.getConfig().getInt("registry.staleSeconds", 20));
        this.teleporterMenu = new SpectatorTeleporterMenu(gameManager, this::handleTeleporterSelection);
        this.settingsMenu = new SpectatorSettingsMenu(plugin, gameManager);
        this.followStates = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            ItemStack item = event.getItem();
            if (SpectatorItems.isPlayAgainItem(item) && canUsePlayAgain(player)) {
                event.setCancelled(true);
                notifyPlayAgainIntent(player);
                requestGameTransfer(player.getUniqueId());
                return;
            }
        }
        if (!isDeadSpectator(player)) {
            return;
        }
        if (isFollowingTarget(player)
                && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)) {
            event.setCancelled(true);
            teleporterMenu.open(player);
            return;
        }
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }

        if (SpectatorItems.isTeleporterItem(item)) {
            event.setCancelled(true);
            teleporterMenu.open(player);
            return;
        }
        if (SpectatorItems.isSettingsItem(item)) {
            event.setCancelled(true);
            settingsMenu.open(player);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player == null || !isDeadSpectator(player)) {
            return;
        }
        String message = event.getMessage();
        UUID sender = player.getUniqueId();
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> sendDeadChat(sender, message));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        UUID quitting = event.getPlayer().getUniqueId();
        removeFollowEntry(quitting);
        markTargetLostForFollowers(quitting);
        teleporterMenu.clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player spectator = event.getPlayer();
        if (!isDeadSpectator(spectator)) {
            return;
        }
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player)) {
            return;
        }
        Player target = (Player) clicked;
        if (!isAliveTarget(target)) {
            return;
        }
        event.setCancelled(true);
        if (!isFirstPersonEnabled(spectator)) {
            teleportToTarget(spectator, target);
            return;
        }
        startFollowingTarget(spectator, target);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event == null || !event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !isFollowingTarget(player)) {
            return;
        }
        event.setCancelled(true);
        stopFollowingTarget(player, true);
    }

    private void sendDeadChat(UUID senderId, String message) {
        if (senderId == null || message == null) {
            return;
        }
        if (message.trim().isEmpty()) {
            return;
        }
        Player sender = Bukkit.getPlayer(senderId);
        if (sender == null || !sender.isOnline() || !isDeadSpectator(sender)) {
            return;
        }
        storeSpectatorChatMessage(sender, message);
        DeadChatSenderStyle senderStyle = buildDeadChatSenderStyle(sender);
        String deadLine = DEAD_CHAT_PREFIX
                + senderStyle.formattedName
                + senderStyle.separatorColor + ": "
                + ChatColor.WHITE + message;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!isDeadSpectator(online)) {
                continue;
            }
            if (plugin != null && plugin.isChatBlocked(senderId, online.getUniqueId())) {
                continue;
            }
            online.sendMessage(deadLine);
        }
    }

    private void storeSpectatorChatMessage(Player sender, String message) {
        if (sender == null || message == null || message.trim().isEmpty() || plugin == null) {
            return;
        }
        UUID senderId = sender.getUniqueId();
        String fallbackIgn = sender.getName();
        String safeMessage = message;
        plugin.getServer().getScheduler().runTaskAsynchronously(
                plugin,
                () -> persistSpectatorChatMessage(senderId, fallbackIgn, safeMessage)
        );
    }

    private void persistSpectatorChatMessage(UUID senderId, String fallbackIgn, String message) {
        if (senderId == null || message == null || message.trim().isEmpty() || plugin == null) {
            return;
        }
        MongoManager mongo = plugin.getMongoManager();
        if (mongo == null) {
            return;
        }
        MongoCollection<Document> collection = mongo.getCollection(MongoManager.CHAT_MESSAGES_COLLECTION);
        if (collection == null) {
            return;
        }
        ChatProfileReference profile = resolveChatProfileReference(mongo, senderId, fallbackIgn);
        Document doc = new Document("serverId", resolveChatServerId())
                .append("type", "ALL")
                .append("rank", profile.rank)
                .append("message", message)
                .append("date", new Date())
                .append("uuid", senderId.toString())
                .append("ign", profile.ign);
        try {
            collection.insertOne(doc);
        } catch (Exception ignored) {
        }
    }

    private ChatProfileReference resolveChatProfileReference(MongoManager mongo, UUID senderId, String fallbackIgn) {
        String safeFallback = fallbackIgn == null ? "Unknown" : fallbackIgn.trim();
        if (safeFallback.isEmpty()) {
            safeFallback = "Unknown";
        }
        if (mongo == null || senderId == null) {
            return new ChatProfileReference(safeFallback, "DEFAULT");
        }
        try {
            MongoCollection<Document> profiles = mongo.getProfiles();
            if (profiles == null) {
                return new ChatProfileReference(safeFallback, "DEFAULT");
            }
            Document profile = profiles.find(Filters.eq("uuid", senderId.toString()))
                    .projection(new Document("name", 1).append("rank", 1))
                    .first();
            if (profile == null) {
                return new ChatProfileReference(safeFallback, "DEFAULT");
            }
            String storedIgn = safeString(profile.getString("name"));
            String ign = storedIgn.isEmpty() ? safeFallback : storedIgn;
            String rank = normalizeStoredRank(profile.getString("rank"));
            return new ChatProfileReference(ign, rank);
        } catch (Exception ignored) {
            return new ChatProfileReference(safeFallback, "DEFAULT");
        }
    }

    private String normalizeStoredRank(String rank) {
        if (rank == null) {
            return "DEFAULT";
        }
        String normalized = rank.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? "DEFAULT" : normalized;
    }

    private String resolveChatServerId() {
        String configured = safeString(currentServerId);
        if (!configured.isEmpty()) {
            return configured;
        }
        if (plugin == null) {
            return "unknown";
        }
        String dynamic = safeString(plugin.getConfig().getString("server.id", ""));
        return dynamic.isEmpty() ? "unknown" : dynamic;
    }

    private DeadChatSenderStyle buildDeadChatSenderStyle(Player player) {
        if (player == null) {
            return new DeadChatSenderStyle(ChatColor.GRAY + "Unknown", ChatColor.GRAY);
        }
        if (plugin == null) {
            return new DeadChatSenderStyle(ChatColor.GRAY + player.getName(), ChatColor.GRAY);
        }
        UUID uuid = player.getUniqueId();
        Profile profile = plugin.getProfile(uuid);
        Rank rank = profile == null || profile.getRank() == null
                ? plugin.getRank(uuid)
                : profile.getRank();
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        if (rank == Rank.DEFAULT) {
            return new DeadChatSenderStyle(ChatColor.GRAY + player.getName(), ChatColor.GRAY);
        }
        int networkLevel = Math.max(0, plugin.getNetworkLevel(uuid));
        String plusColor = profile == null ? null : profile.getPlusColor();
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        String prefix = RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
        ChatColor nameColor = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
        return new DeadChatSenderStyle(prefix + nameColor + player.getName(), ChatColor.WHITE);
    }

    private static final class DeadChatSenderStyle {
        private final String formattedName;
        private final ChatColor separatorColor;

        private DeadChatSenderStyle(String formattedName, ChatColor separatorColor) {
            this.formattedName = formattedName;
            this.separatorColor = separatorColor == null ? ChatColor.GRAY : separatorColor;
        }
    }

    private void handleTeleporterSelection(Player spectator, Player target) {
        if (spectator == null || target == null) {
            return;
        }
        if (!isFirstPersonEnabled(spectator)) {
            teleportToTarget(spectator, target);
            return;
        }
        startFollowingTarget(spectator, target);
    }

    private void teleportToTarget(Player spectator, Player target) {
        if (spectator == null || target == null) {
            return;
        }
        if (target.getLocation() != null) {
            spectator.teleport(target.getLocation());
        }
        spectator.sendMessage(SPECTATE_MESSAGE_PREFIX + target.getName() + SPECTATE_MESSAGE_SUFFIX);
    }

    private void startFollowingTarget(Player spectator, Player target) {
        if (spectator == null || target == null || !isAliveTarget(target)) {
            return;
        }
        spectator.closeInventory();
        teleporterMenu.clear(spectator);
        spectator.setGameMode(GameMode.SPECTATOR);
        setSpectatorTarget(spectator, target);
        followStates.put(spectator.getUniqueId(), FollowState.following(target.getUniqueId()));
        sendFollowActionBar(spectator, target);
        ensureFollowActionBarTask();
    }

    private void stopFollowingTarget(Player spectator, boolean restoreSpectatorState) {
        if (spectator == null) {
            return;
        }
        removeFollowEntry(spectator.getUniqueId());
        setSpectatorTarget(spectator, null);
        if (restoreSpectatorState) {
            gameManager.restoreDeadSpectatorState(spectator);
        }
    }

    private void ensureFollowActionBarTask() {
        if (plugin == null || followActionBarTask != null) {
            return;
        }
        followActionBarTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshFollowActionBars,
                0L,
                FOLLOW_ACTION_BAR_INTERVAL_TICKS
        );
    }

    private void refreshFollowActionBars() {
        if (followStates.isEmpty()) {
            cancelFollowActionBarTask();
            return;
        }
        long now = System.currentTimeMillis();
        List<UUID> spectators = new ArrayList<>(followStates.keySet());
        for (UUID spectatorId : spectators) {
            FollowState state = followStates.get(spectatorId);
            if (state == null) {
                continue;
            }
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator == null || !spectator.isOnline() || !isDeadSpectator(spectator)) {
                removeFollowEntry(spectatorId);
                continue;
            }
            if (spectator.getGameMode() != GameMode.SPECTATOR) {
                spectator.setGameMode(GameMode.SPECTATOR);
            }
            if (!state.hasTarget()) {
                if (tryAutoRetarget(spectator, state, null)) {
                    continue;
                }
                if (state.shouldShowTargetLost(now)) {
                    sendTargetLostActionBar(spectator);
                }
                continue;
            }
            Player target = Bukkit.getPlayer(state.targetId);
            if (!isAliveTarget(target)) {
                UUID previousTargetId = state.targetId;
                if (tryAutoRetarget(spectator, state, previousTargetId)) {
                    continue;
                }
                markTargetLost(spectator, state, now);
                continue;
            }
            setSpectatorTarget(spectator, target);
            sendFollowActionBar(spectator, target);
        }
        if (followStates.isEmpty()) {
            cancelFollowActionBarTask();
        }
    }

    private void sendFollowActionBar(Player spectator, Player target) {
        if (spectator == null || target == null) {
            return;
        }
        ActionBarUtil.send(
                spectator,
                ACTION_BAR_TARGET_PREFIX
                        + ACTION_BAR_TARGET_NAME_STYLE
                        + target.getName()
                        + ACTION_BAR_MENU_HINT
                        + ACTION_BAR_EXIT_HINT
        );
    }

    private void sendTargetLostActionBar(Player spectator) {
        if (spectator == null) {
            return;
        }
        ActionBarUtil.send(spectator, ACTION_BAR_TARGET_LOST);
    }

    private void markTargetLost(Player spectator, FollowState state, long now) {
        if (state == null) {
            return;
        }
        state.markTargetLost(now + TARGET_LOST_ACTION_BAR_MILLIS);
        if (spectator != null && spectator.isOnline()) {
            setSpectatorTarget(spectator, null);
            sendTargetLostActionBar(spectator);
        }
    }

    private void markTargetLostForFollowers(UUID targetId) {
        if (targetId == null || followStates.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, FollowState> entry : followStates.entrySet()) {
            FollowState state = entry.getValue();
            if (state == null || !state.hasTarget() || !targetId.equals(state.targetId)) {
                continue;
            }
            Player spectator = Bukkit.getPlayer(entry.getKey());
            if (tryAutoRetarget(spectator, state, targetId)) {
                continue;
            }
            markTargetLost(spectator, state, now);
        }
    }

    private boolean tryAutoRetarget(Player spectator, FollowState state, UUID excludedTargetId) {
        if (spectator == null || state == null || !shouldAutoTeleport(spectator)) {
            return false;
        }
        Player replacement = findReplacementTarget(excludedTargetId, spectator.getUniqueId());
        if (replacement == null) {
            return false;
        }
        state.setTarget(replacement.getUniqueId());
        setSpectatorTarget(spectator, replacement);
        sendFollowActionBar(spectator, replacement);
        return true;
    }

    private boolean isFollowingTarget(Player player) {
        return player != null && followStates.containsKey(player.getUniqueId());
    }

    private void removeFollowEntry(UUID spectatorId) {
        if (spectatorId == null) {
            return;
        }
        followStates.remove(spectatorId);
        if (followStates.isEmpty()) {
            cancelFollowActionBarTask();
        }
    }

    private void cancelFollowActionBarTask() {
        if (followActionBarTask != null) {
            followActionBarTask.cancel();
            followActionBarTask = null;
        }
    }

    private boolean isAliveTarget(Player player) {
        if (player == null || !player.isOnline() || gameManager == null) {
            return false;
        }
        GamePlayer gamePlayer = gameManager.getPlayer(player);
        return gamePlayer != null && gamePlayer.isAlive();
    }

    private Player findReplacementTarget(UUID excludedTargetId, UUID spectatorId) {
        List<Player> alive = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!isAliveTarget(online)) {
                continue;
            }
            if (spectatorId != null && spectatorId.equals(online.getUniqueId())) {
                continue;
            }
            if (excludedTargetId != null && excludedTargetId.equals(online.getUniqueId())) {
                continue;
            }
            alive.add(online);
        }
        if (alive.isEmpty()) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(alive.size());
        return alive.get(index);
    }

    private boolean shouldAutoTeleport(Player spectator) {
        Profile profile = spectator == null || plugin == null ? null : plugin.getProfile(spectator.getUniqueId());
        return profile != null && profile.isSpectatorAutoTeleportEnabled();
    }

    private boolean isFirstPersonEnabled(Player spectator) {
        Profile profile = spectator == null || plugin == null ? null : plugin.getProfile(spectator.getUniqueId());
        return profile != null && profile.isSpectatorFirstPersonEnabled();
    }

    private void setSpectatorTarget(Player spectator, Player target) {
        if (spectator == null) {
            return;
        }
        try {
            spectator.getClass()
                    .getMethod("setSpectatorTarget", org.bukkit.entity.Entity.class)
                    .invoke(spectator, target);
        } catch (Exception ignored) {
            if (target != null && target.getLocation() != null) {
                spectator.teleport(target.getLocation());
            }
        }
    }

    private void requestGameTransfer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String target = findBestGameServerName();
            plugin.getServer().getScheduler().runTask(plugin, () -> connectToGame(uuid, target));
        });
    }

    private void notifyPlayAgainIntent(Player player) {
        if (player == null || plugin == null) {
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(player.getUniqueId().toString());
            player.sendPluginMessage(plugin, PLAY_AGAIN_INTENT_CHANNEL, bytes.toByteArray());
        } catch (Exception ignored) {
        }
    }

    private void connectToGame(UUID uuid, String targetServer) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (targetServer == null || targetServer.trim().isEmpty()) {
            player.sendMessage(NO_GAME_AVAILABLE_MESSAGE);
            return;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF("Connect");
            out.writeUTF(targetServer.trim());
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (Exception ex) {
            player.sendMessage(TRANSFER_FAILED_MESSAGE);
        }
    }

    private String findBestGameServerName() {
        if (gameType == null || !gameType.isGame()) {
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
        for (Document doc : collection.find(Filters.eq("type", gameType.getId()))) {
            if (!isGameEntryValid(doc, now)) {
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

    private boolean isGameEntryValid(Document doc, long now) {
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
        String state = safeString(doc.getString("state")).toUpperCase(Locale.ROOT);
        return !state.equals("IN_GAME")
                && !state.equals("ENDING")
                && !state.equals("RESTARTING")
                && !state.equals("LOCKED")
                && !state.equals("WAITING_RESTART");
    }

    private boolean isDeadSpectator(Player player) {
        if (player == null || gameManager == null || gameManager.getState() != GameState.IN_GAME) {
            return false;
        }
        GamePlayer gamePlayer = gameManager.getPlayer(player);
        return gamePlayer != null && !gamePlayer.isAlive();
    }

    private boolean canUsePlayAgain(Player player) {
        if (player == null || gameManager == null) {
            return false;
        }
        GamePlayer gamePlayer = gameManager.getPlayer(player);
        if (gamePlayer == null) {
            return false;
        }
        GameState state = gameManager.getState();
        if (state == GameState.ENDING) {
            return true;
        }
        return state == GameState.IN_GAME && !gamePlayer.isAlive();
    }

    private static final class FollowState {
        private UUID targetId;
        private long targetLostUntilMillis;

        private FollowState(UUID targetId, long targetLostUntilMillis) {
            this.targetId = targetId;
            this.targetLostUntilMillis = targetLostUntilMillis;
        }

        private static FollowState following(UUID targetId) {
            return new FollowState(targetId, 0L);
        }

        private boolean hasTarget() {
            return targetId != null;
        }

        private void markTargetLost(long untilMillis) {
            this.targetId = null;
            this.targetLostUntilMillis = untilMillis;
        }

        private void setTarget(UUID targetId) {
            this.targetId = targetId;
            this.targetLostUntilMillis = 0L;
        }

        private boolean shouldShowTargetLost(long nowMillis) {
            return targetLostUntilMillis > nowMillis;
        }
    }

    private static final class ChatProfileReference {
        private final String ign;
        private final String rank;

        private ChatProfileReference(String ign, String rank) {
            this.ign = ign == null || ign.trim().isEmpty() ? "Unknown" : ign;
            this.rank = rank == null || rank.trim().isEmpty() ? "DEFAULT" : rank;
        }
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
