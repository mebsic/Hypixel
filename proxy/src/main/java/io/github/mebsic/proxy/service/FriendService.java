package io.github.mebsic.proxy.service;

import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.manager.RedisManager;
import io.github.mebsic.proxy.util.Components;
import io.github.mebsic.proxy.util.FriendComponents;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bson.Document;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class FriendService {
    private static final long REQUEST_TTL_MILLIS = 5L * 60L * 1000L;
    private static final String FRIEND_VISIBILITY_UPDATE_CHANNEL = "friend_visibility_update";

    private final ProxyServer proxy;
    private final MongoCollection<Document> collection;
    private final RankResolver rankResolver;
    private final ServerRegistryService registryService;
    private final RedisManager redis;
    private final Map<UUID, Set<UUID>> friends = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastKnownName = new ConcurrentHashMap<>();
    private final Map<UUID, FriendPresence> presenceByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> friendSinceByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> bestFriendsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, String>> friendNicknamesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> friendJoinLeaveNotifications = new ConcurrentHashMap<>();

    public FriendService(ProxyServer proxy, MongoDatabase database, String collectionName) {
        this(proxy, database, collectionName, null, null, null);
    }

    public FriendService(ProxyServer proxy, MongoDatabase database, String collectionName, RankResolver rankResolver) {
        this(proxy, database, collectionName, rankResolver, null, null);
    }

    public FriendService(ProxyServer proxy,
                         MongoDatabase database,
                         String collectionName,
                         RankResolver rankResolver,
                         ServerRegistryService registryService) {
        this(proxy, database, collectionName, rankResolver, registryService, null);
    }

    public FriendService(ProxyServer proxy,
                         MongoDatabase database,
                         String collectionName,
                         RankResolver rankResolver,
                         ServerRegistryService registryService,
                         RedisManager redis) {
        this.proxy = proxy;
        this.rankResolver = rankResolver;
        this.registryService = registryService;
        this.redis = redis;
        if (database == null || collectionName == null || collectionName.trim().isEmpty()) {
            this.collection = null;
        } else {
            this.collection = database.getCollection(collectionName);
        }
    }

    public void track(Player player) {
        if (player != null) {
            ensurePlayerRecord(player.getUniqueId(), player.getUsername());
            rememberName(player.getUniqueId(), player.getUsername());
            loadFriends(player.getUniqueId());
            loadNotificationSettings(player.getUniqueId());
            FriendPresence stored = loadPresence(player.getUniqueId());
            if (stored != null) {
                presenceByPlayer.put(player.getUniqueId(), stored);
            }
            updatePresence(player);
        }
    }

    public boolean areFriends(UUID a, UUID b) {
        Set<UUID> list = friends.get(a);
        return list != null && list.contains(b);
    }

    public boolean hasPending(UUID target, UUID from) {
        cleanupExpiredPending(target);
        Map<UUID, Long> requests = pending.get(target);
        return requests != null && requests.containsKey(from);
    }

    public boolean request(UUID from, UUID to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        if (areFriends(from, to)) {
            return false;
        }
        cleanupExpiredPending(to);
        Map<UUID, Long> requests = pending.computeIfAbsent(to, key -> new ConcurrentHashMap<UUID, Long>());
        return requests.putIfAbsent(from, System.currentTimeMillis()) == null;
    }

    public boolean accept(UUID target, UUID from) {
        if (!removePending(target, from)) {
            return false;
        }
        addFriend(target, from);
        return true;
    }

    public boolean deny(UUID target, UUID from) {
        return removePending(target, from);
    }

    public Set<UUID> getFriends(UUID player) {
        Set<UUID> list = friends.get(player);
        return list == null ? Collections.emptySet() : Collections.unmodifiableSet(list);
    }

    public long getFriendSince(UUID ownerId, UUID friendId) {
        if (ownerId == null || friendId == null) {
            return 0L;
        }
        Map<UUID, Long> byOwner = friendSinceByPlayer.get(ownerId);
        if (byOwner == null) {
            return 0L;
        }
        Long since = byOwner.get(friendId);
        if (since == null) {
            return 0L;
        }
        return Math.max(0L, since.longValue());
    }

    public boolean isBestFriend(UUID ownerId, UUID friendId) {
        if (ownerId == null || friendId == null) {
            return false;
        }
        Set<UUID> best = bestFriendsByPlayer.get(ownerId);
        return best != null && best.contains(friendId);
    }

    public boolean toggleBestFriend(UUID ownerId, UUID friendId) {
        if (ownerId == null || friendId == null || ownerId.equals(friendId)) {
            return false;
        }
        if (!areFriends(ownerId, friendId)) {
            return false;
        }
        if (isBestFriend(ownerId, friendId)) {
            setBestFriend(ownerId, friendId, false);
            return false;
        }
        setBestFriend(ownerId, friendId, true);
        return true;
    }

    public boolean setFriendNickname(UUID ownerId, UUID friendId, String nickname) {
        if (ownerId == null || friendId == null || ownerId.equals(friendId)) {
            return false;
        }
        if (!areFriends(ownerId, friendId)) {
            return false;
        }
        String normalized = normalizeFriendNickname(nickname);
        if (normalized == null) {
            removeFriendNicknameLocal(ownerId, friendId);
            removeFriendNicknameEntry(ownerId, friendId);
            return true;
        }
        setFriendNicknameLocal(ownerId, friendId, normalized);
        persistFriendNicknameEntry(ownerId, friendId, normalized);
        return true;
    }

    public String getFriendNickname(UUID ownerId, UUID friendId) {
        if (ownerId == null || friendId == null) {
            return null;
        }
        Map<UUID, String> byOwner = friendNicknamesByPlayer.get(ownerId);
        if (byOwner == null) {
            return null;
        }
        return normalizeFriendNickname(byOwner.get(friendId));
    }

    public void onPlayerBlocked(UUID ownerId, UUID targetId) {
        if (ownerId == null || targetId == null || ownerId.equals(targetId)) {
            return;
        }
        setBestFriend(ownerId, targetId, false);
        removePending(ownerId, targetId);
        removePending(targetId, ownerId);
    }

    public void bootstrapOnlinePlayers() {
        if (proxy == null) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            track(player);
        }
    }

    public Set<UUID> getPending(UUID player) {
        cleanupExpiredPending(player);
        Map<UUID, Long> list = pending.get(player);
        if (list == null || list.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(list.keySet());
    }

    public List<UUID> getPendingOrdered(UUID player) {
        cleanupExpiredPending(player);
        Map<UUID, Long> list = pending.get(player);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(list.entrySet());
        entries.sort(Comparator.comparingLong(entry -> entry.getValue() == null ? 0L : entry.getValue()));
        List<UUID> ordered = new ArrayList<>(entries.size());
        for (Map.Entry<UUID, Long> entry : entries) {
            ordered.add(entry.getKey());
        }
        return Collections.unmodifiableList(ordered);
    }

    public List<UUID> getOutgoingPendingOrdered(UUID player) {
        if (player == null) {
            return Collections.emptyList();
        }
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Long>> targetEntry : pending.entrySet()) {
            UUID targetId = targetEntry.getKey();
            cleanupExpiredPending(targetId);
            Map<UUID, Long> requests = pending.get(targetId);
            if (requests == null || requests.isEmpty()) {
                continue;
            }
            Long createdAt = requests.get(player);
            if (createdAt != null) {
                entries.add(new AbstractMap.SimpleEntry<UUID, Long>(targetId, createdAt));
            }
        }
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        entries.sort(Comparator.comparingLong(entry -> entry.getValue() == null ? 0L : entry.getValue()));
        List<UUID> ordered = new ArrayList<>(entries.size());
        for (Map.Entry<UUID, Long> entry : entries) {
            ordered.add(entry.getKey());
        }
        return Collections.unmodifiableList(ordered);
    }

    public String getName(UUID uuid) {
        if (uuid == null) {
            return "Unknown";
        }
        String cached = lastKnownName.get(uuid);
        if (cached != null && !cached.trim().isEmpty()) {
            return cached;
        }
        if (collection == null) {
            return "Unknown";
        }
        Document doc = collection.find(eq("uuid", uuid.toString()))
                .projection(new Document("name", 1))
                .first();
        String loaded = doc == null ? null : doc.getString("name");
        if (loaded == null || loaded.trim().isEmpty()) {
            return "Unknown";
        }
        lastKnownName.put(uuid, loaded);
        return loaded;
    }

    public void rememberName(UUID uuid, String name) {
        if (uuid == null || name == null || name.trim().isEmpty()) {
            return;
        }
        lastKnownName.put(uuid, name);
    }

    public String formatNameWithRank(UUID uuid) {
        String fallback = getName(uuid);
        if (rankResolver == null) {
            return fallback;
        }
        try {
            return rankResolver.formatNameWithRank(uuid, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public String formatNameWithColor(UUID uuid) {
        String fallback = getName(uuid);
        if (rankResolver == null) {
            return fallback;
        }
        try {
            return rankResolver.formatNameWithColor(uuid, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public boolean isStaff(UUID uuid) {
        if (uuid == null || rankResolver == null) {
            return false;
        }
        try {
            return rankResolver.isStaff(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public FriendPresence getPresence(UUID uuid) {
        if (uuid == null) {
            return FriendPresence.offline(System.currentTimeMillis());
        }
        FriendPresence cached = presenceByPlayer.get(uuid);
        if (cached != null) {
            FriendPresence refreshed = refreshFromLiveProxyIfNeeded(uuid, cached);
            return refreshed == null ? cached : refreshed;
        }
        FriendPresence loaded = loadPresence(uuid);
        if (loaded != null) {
            presenceByPlayer.put(uuid, loaded);
            FriendPresence refreshed = refreshFromLiveProxyIfNeeded(uuid, loaded);
            return refreshed == null ? loaded : refreshed;
        }
        if (proxy != null) {
            proxy.getPlayer(uuid).ifPresent(this::updatePresence);
        }
        FriendPresence afterUpdate = presenceByPlayer.get(uuid);
        if (afterUpdate != null) {
            return afterUpdate;
        }
        FriendPresence offline = FriendPresence.offline(System.currentTimeMillis());
        presenceByPlayer.put(uuid, offline);
        return offline;
    }

    private FriendPresence refreshFromLiveProxyIfNeeded(UUID uuid, FriendPresence current) {
        if (!needsLiveRefresh(current) || proxy == null || uuid == null) {
            return null;
        }
        Optional<Player> online = proxy.getPlayer(uuid);
        if (!online.isPresent()) {
            return null;
        }
        updatePresence(online.get());
        return presenceByPlayer.get(uuid);
    }

    private boolean needsLiveRefresh(FriendPresence presence) {
        if (presence == null || !presence.isOnline()) {
            return false;
        }
        if (presence.getServerType() == null || presence.getServerType() == ServerType.UNKNOWN) {
            return true;
        }
        String serverName = presence.getServerName();
        if (serverName == null || serverName.trim().isEmpty()) {
            return true;
        }
        String location = presence.getLocation();
        return location == null
                || location.trim().isEmpty()
                || "a game".equalsIgnoreCase(location.trim());
    }

    public void updatePresence(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();
        rememberName(uuid, name);
        FriendPresence online = resolveOnlinePresence(player);
        presenceByPlayer.put(uuid, online);
        persistPresence(uuid, name, online);
    }

    public void markOffline(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getUsername();
        rememberName(uuid, name);
        FriendPresence offline = FriendPresence.offline(System.currentTimeMillis());
        presenceByPlayer.put(uuid, offline);
        persistPresence(uuid, name, offline);
    }

    public void notifyFriendStatus(UUID playerId, String name, boolean joined) {
        String fallbackName = name == null || name.trim().isEmpty() ? "Unknown" : name;
        String resolvedName;
        try {
            resolvedName = rankResolver == null
                    ? fallbackName
                    : rankResolver.formatNameWithRank(playerId, fallbackName);
        } catch (Throwable ignored) {
            resolvedName = fallbackName;
        }
        final String formattedName = resolvedName;
        for (UUID friendId : getFriends(playerId)) {
            if (!isFriendJoinLeaveNotificationsEnabled(friendId)) {
                continue;
            }
            try {
                proxy.getPlayer(friendId).ifPresent(friend ->
                        friend.sendMessage(Components.friendStatus(formattedName, joined))
                );
            } catch (Throwable ignored) {
                // Never let friend notification errors block other event handlers.
            }
        }
    }

    public boolean toggleFriendJoinLeaveNotifications(UUID playerId) {
        boolean enabled = !isFriendJoinLeaveNotificationsEnabled(playerId);
        setFriendJoinLeaveNotifications(playerId, enabled);
        return enabled;
    }

    public boolean isFriendJoinLeaveNotificationsEnabled(UUID playerId) {
        if (playerId == null) {
            return true;
        }
        Boolean enabled = friendJoinLeaveNotifications.get(playerId);
        return enabled == null || enabled.booleanValue();
    }

    public void setFriendJoinLeaveNotifications(UUID playerId, boolean enabled) {
        if (playerId == null) {
            return;
        }
        friendJoinLeaveNotifications.put(playerId, enabled);
        persistFriendJoinLeaveNotifications(playerId, enabled);
    }

    public UUID resolveByName(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<UUID, String> entry : lastKnownName.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void expirePendingRequests() {
        List<UUID> targets = new ArrayList<>(pending.keySet());
        for (UUID targetId : targets) {
            cleanupExpiredPending(targetId, true);
        }
    }

    private void addFriend(UUID a, UUID b) {
        long since = System.currentTimeMillis();
        friends.computeIfAbsent(a, key -> ConcurrentHashMap.newKeySet()).add(b);
        friends.computeIfAbsent(b, key -> ConcurrentHashMap.newKeySet()).add(a);
        setFriendSinceLocal(a, b, since);
        setFriendSinceLocal(b, a, since);
        if (collection != null) {
            addFriendEntry(a, b);
            addFriendEntry(b, a);
            persistFriendSince(a, b, since);
            persistFriendSince(b, a, since);
        }
    }

    private void setBestFriend(UUID ownerId, UUID friendId, boolean best) {
        if (ownerId == null || friendId == null) {
            return;
        }
        if (best) {
            bestFriendsByPlayer.computeIfAbsent(ownerId, key -> ConcurrentHashMap.newKeySet()).add(friendId);
            persistBestFriendEntry(ownerId, friendId);
            return;
        }
        Set<UUID> bestByOwner = bestFriendsByPlayer.get(ownerId);
        if (bestByOwner != null) {
            bestByOwner.remove(friendId);
            if (bestByOwner.isEmpty()) {
                bestFriendsByPlayer.remove(ownerId);
            }
        }
        removeBestFriendEntry(ownerId, friendId);
    }

    private boolean removePending(UUID target, UUID from) {
        cleanupExpiredPending(target);
        Map<UUID, Long> requests = pending.get(target);
        if (requests == null || requests.remove(from) == null) {
            return false;
        }
        if (requests.isEmpty()) {
            pending.remove(target);
        }
        return true;
    }

    private void cleanupExpiredPending(UUID target) {
        cleanupExpiredPending(target, false);
    }

    private void cleanupExpiredPending(UUID target, boolean notifySender) {
        if (target == null) {
            return;
        }
        Map<UUID, Long> requests = pending.get(target);
        if (requests == null || requests.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<UUID> expiredSenders = notifySender ? new ArrayList<UUID>() : null;
        requests.entrySet().removeIf(entry -> {
            Long createdAt = entry.getValue();
            boolean expired = createdAt == null || now - createdAt >= REQUEST_TTL_MILLIS;
            if (expired && expiredSenders != null && entry.getKey() != null) {
                expiredSenders.add(entry.getKey());
            }
            return expired;
        });
        if (requests.isEmpty()) {
            pending.remove(target);
        }
        if (expiredSenders == null || expiredSenders.isEmpty()) {
            return;
        }
        for (UUID senderId : expiredSenders) {
            notifyExpiredRequest(senderId, target);
        }
    }

    private void notifyExpiredRequest(UUID senderId, UUID targetId) {
        if (proxy == null || senderId == null || targetId == null) {
            return;
        }
        String formattedTargetName = formatNameWithRank(targetId);
        proxy.getPlayer(senderId).ifPresent(sender -> {
            sender.sendMessage(FriendComponents.longSeparator());
            sender.sendMessage(Components.friendRequestExpired(formattedTargetName));
            sender.sendMessage(FriendComponents.longSeparator());
        });
    }

    private void loadFriends(UUID uuid) {
        if (collection == null || uuid == null) {
            return;
        }
        Document doc = collection.find(eq("uuid", uuid.toString())).first();
        if (doc == null) {
            return;
        }
        Document friendNicknames = doc.get("friendNicknames", Document.class);
        loadFriendNicknames(uuid, friendNicknames);
        @SuppressWarnings("unchecked")
        List<String> stored = (List<String>) doc.get("friends");
        if (stored == null || stored.isEmpty()) {
            return;
        }
        Document friendSince = doc.get("friendSince", Document.class);
        @SuppressWarnings("unchecked")
        List<String> storedBest = (List<String>) doc.get("bestFriends");
        Set<UUID> bestSet = null;
        if (storedBest != null && !storedBest.isEmpty()) {
            bestSet = ConcurrentHashMap.newKeySet();
            for (String rawBest : storedBest) {
                if (rawBest == null || rawBest.trim().isEmpty()) {
                    continue;
                }
                try {
                    bestSet.add(UUID.fromString(rawBest));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        Set<UUID> list = friends.computeIfAbsent(uuid, key -> ConcurrentHashMap.newKeySet());
        for (String raw : stored) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                UUID friendId = UUID.fromString(raw);
                list.add(friendId);
                if (bestSet != null && bestSet.contains(friendId)) {
                    bestFriendsByPlayer.computeIfAbsent(uuid, key -> ConcurrentHashMap.newKeySet()).add(friendId);
                }
                if (friendSince != null) {
                    Long since = parseLong(friendSince.get(raw));
                    if (since != null) {
                        setFriendSinceLocal(uuid, friendId, since.longValue());
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private FriendPresence loadPresence(UUID uuid) {
        if (collection == null || uuid == null) {
            return null;
        }
        Document doc = collection.find(eq("uuid", uuid.toString()))
                .projection(new Document("name", 1).append("status", 1))
                .first();
        if (doc == null) {
            return null;
        }
        String storedName = doc.getString("name");
        if (storedName != null && !storedName.trim().isEmpty()) {
            rememberName(uuid, storedName);
        }
        Object raw = doc.get("status");
        if (!(raw instanceof Document)) {
            return null;
        }
        Document status = (Document) raw;
        boolean online = Boolean.TRUE.equals(status.getBoolean("online"));
        String serverName = status.getString("serverName");
        ServerType type = ServerType.fromString(status.getString("serverType"));
        if (type == ServerType.UNKNOWN) {
            type = resolveServerType(serverName);
        }
        String location = status.getString("location");
        String state = status.getString("state");
        Long updatedAt = status.getLong("updatedAt");
        if (location == null || location.trim().isEmpty()) {
            location = formatLocation(type, state, serverName);
        }
        return new FriendPresence(
                online,
                serverName,
                type,
                location,
                updatedAt == null ? 0L : updatedAt
        );
    }

    private void loadNotificationSettings(UUID uuid) {
        if (collection == null || uuid == null) {
            return;
        }
        Document doc = collection.find(eq("uuid", uuid.toString()))
                .projection(new Document("settings.friendJoinLeaveNotifications", 1))
                .first();
        if (doc == null) {
            return;
        }
        Object settingsRaw = doc.get("settings");
        if (!(settingsRaw instanceof Document)) {
            return;
        }
        Object enabledRaw = ((Document) settingsRaw).get("friendJoinLeaveNotifications");
        if (enabledRaw instanceof Boolean) {
            friendJoinLeaveNotifications.put(uuid, (Boolean) enabledRaw);
        }
    }

    private FriendPresence resolveOnlinePresence(Player player) {
        String serverName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
        ServerType type = resolveServerType(serverName);
        String state = null;
        if (registryService != null && serverName != null && !serverName.trim().isEmpty()) {
            Optional<ServerRegistryService.ServerDetails> details = registryService.findServerDetails(serverName);
            if (details.isPresent()) {
                ServerRegistryService.ServerDetails resolved = details.get();
                if (resolved.getType() != null && resolved.getType() != ServerType.UNKNOWN) {
                    type = resolved.getType();
                }
                state = resolved.getState();
            }
        }
        String location = formatLocation(type, state, serverName);
        return new FriendPresence(true, serverName, type, location, System.currentTimeMillis());
    }

    private ServerType resolveServerType(String serverName) {
        if (serverName == null || serverName.trim().isEmpty()) {
            return ServerType.UNKNOWN;
        }
        if (registryService != null) {
            ServerType resolved = registryService.findServerType(serverName).orElse(ServerType.UNKNOWN);
            if (resolved != ServerType.UNKNOWN) {
                return resolved;
            }
        }
        return inferServerTypeFromName(serverName);
    }

    private ServerType inferServerTypeFromName(String serverName) {
        if (serverName == null || serverName.trim().isEmpty()) {
            return ServerType.UNKNOWN;
        }
        String normalized = normalizeIdentifier(serverName);
        if (normalized.isEmpty()) {
            return ServerType.UNKNOWN;
        }
        for (ServerType candidate : ServerType.values()) {
            if (candidate == null || candidate == ServerType.UNKNOWN) {
                continue;
            }
            String key = candidate.name();
            if (normalized.equals(key)
                    || normalized.startsWith(key + "_")
                    || normalized.endsWith("_" + key)
                    || normalized.contains("_" + key + "_")) {
                return candidate;
            }
        }
        boolean maybeHub = normalized.contains("HUB") || normalized.contains("LOBBY");
        for (ServerType candidate : ServerType.values()) {
            if (candidate == null || candidate == ServerType.UNKNOWN) {
                continue;
            }
            String gameKey = candidate.name().replace("_HUB", "");
            if (gameKey.isEmpty() || !normalized.contains(gameKey)) {
                continue;
            }
            if (candidate.isHub() && maybeHub) {
                return candidate;
            }
            if (candidate.isGame() && !maybeHub) {
                return candidate;
            }
        }
        return ServerType.UNKNOWN;
    }

    private String normalizeIdentifier(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toUpperCase(ch));
                previousUnderscore = false;
                continue;
            }
            if (!previousUnderscore) {
                out.append('_');
                previousUnderscore = true;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '_') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '_') {
            end--;
        }
        return start >= end ? "" : out.substring(start, end);
    }

    private String formatLocation(ServerType type, String state, String serverName) {
        ServerType safeType = type == null ? ServerType.UNKNOWN : type;
        String suffix = formatServerSuffix(serverName);
        String gameName = toDisplayCase(safeType.getGameTypeDisplayName());
        if (safeType.isGame()) {
            return gameName + " " + gameStateLabel(state) + suffix;
        }
        if (safeType.isHub()) {
            return gameName + " Lobby" + suffix;
        }
        if (serverName != null && !serverName.trim().isEmpty()) {
            return serverName;
        }
        return "a game";
    }

    private String gameStateLabel(String state) {
        String normalized = normalizeState(state);
        if (normalized.isEmpty()) {
            return "Game";
        }
        if (normalized.equals("WAITING") || normalized.equals("STARTING")) {
            return "Lobby";
        }
        if (normalized.equals("IN_GAME")) {
            return "Game";
        }
        if (normalized.equals("ENDING")) {
            return "Ending";
        }
        if (normalized.equals("RESTARTING") || normalized.equals("WAITING_RESTART")) {
            return "Restarting";
        }
        if (normalized.equals("LOCKED")) {
            return "Locked";
        }
        return toDisplayCase(normalized.replace('_', ' '));
    }

    private String normalizeState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return "";
        }
        return state.trim().toUpperCase(Locale.ROOT);
    }

    private String formatServerSuffix(String serverName) {
        if (serverName == null || serverName.trim().isEmpty()) {
            return "";
        }
        return " (" + serverName.trim() + ")";
    }

    private String toDisplayCase(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Game";
        }
        String[] words = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(words[i].charAt(0)));
            if (words[i].length() > 1) {
                out.append(words[i].substring(1));
            }
        }
        return out.length() == 0 ? "Game" : out.toString();
    }

    private void ensurePlayerRecord(UUID uuid, String name) {
        if (collection == null || uuid == null) {
            return;
        }
        Document setOnInsert = new Document("uuid", uuid.toString())
                .append("friends", new ArrayList<String>())
                .append("bestFriends", new ArrayList<String>())
                .append("friendSince", new Document())
                .append("friendNicknames", new Document())
                .append("settings", new Document("friendJoinLeaveNotifications", true));
        Document update = new Document("$setOnInsert", setOnInsert);
        if (name != null && !name.trim().isEmpty()) {
            update.append("$set", new Document("name", name.trim()));
        }
        collection.updateOne(
                eq("uuid", uuid.toString()),
                update,
                new UpdateOptions().upsert(true)
        );
    }

    private void persistPresence(UUID uuid, String name, FriendPresence presence) {
        if (collection == null || uuid == null || presence == null) {
            return;
        }
        Document status = new Document("online", presence.isOnline())
                .append("serverName", presence.getServerName())
                .append("serverType", presence.getServerType().name())
                .append("serverKind", presence.getServerType().getKind().name())
                .append("location", presence.getLocation())
                .append("updatedAt", presence.getUpdatedAt());
        Document set = new Document("status", status);
        if (name != null && !name.trim().isEmpty()) {
            set.append("name", name);
        }
        collection.updateOne(
                eq("uuid", uuid.toString()),
                new Document("$set", set).append("$setOnInsert", new Document("friends", new ArrayList<String>())),
                new UpdateOptions().upsert(true)
        );
    }

    private void persistFriendJoinLeaveNotifications(UUID uuid, boolean enabled) {
        if (collection == null || uuid == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", uuid.toString()),
                new Document("$set", new Document("settings.friendJoinLeaveNotifications", enabled))
                        .append("$setOnInsert", new Document("friends", new ArrayList<String>())),
                new UpdateOptions().upsert(true)
        );
    }

    private void addFriendEntry(UUID owner, UUID friend) {
        if (collection == null) {
            return;
        }
        collection.updateOne(eq("uuid", owner.toString()),
                new Document("$addToSet", new Document("friends", friend.toString())),
                new UpdateOptions().upsert(true));
    }

    private void persistBestFriendEntry(UUID owner, UUID friend) {
        if (collection == null || owner == null || friend == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", owner.toString()),
                new Document("$addToSet", new Document("bestFriends", friend.toString()))
                        .append("$setOnInsert", new Document("friends", new ArrayList<String>())),
                new UpdateOptions().upsert(true)
        );
    }

    private void persistFriendSince(UUID owner, UUID friend, long since) {
        if (collection == null || owner == null || friend == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", owner.toString()),
                new Document("$set", new Document("friendSince." + friend.toString(), Math.max(0L, since)))
                        .append("$setOnInsert", new Document("friends", new ArrayList<String>())),
                new UpdateOptions().upsert(true)
        );
    }

    private void persistFriendNicknameEntry(UUID owner, UUID friend, String nickname) {
        if (collection == null || owner == null || friend == null) {
            return;
        }
        String normalized = normalizeFriendNickname(nickname);
        if (normalized == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", owner.toString()),
                new Document("$set", new Document("friendNicknames." + friend.toString(), normalized))
                        .append("$setOnInsert", new Document("friends", new ArrayList<String>())),
                new UpdateOptions().upsert(true)
        );
    }

    private void removeFriendEntry(UUID owner, UUID friend) {
        if (collection == null) {
            return;
        }
        collection.updateOne(eq("uuid", owner.toString()),
                new Document("$pull", new Document("friends", friend.toString())));
    }

    private void removeBestFriendEntry(UUID owner, UUID friend) {
        if (collection == null || owner == null || friend == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", owner.toString()),
                new Document("$pull", new Document("bestFriends", friend.toString()))
        );
    }

    private void removeFriendSinceEntry(UUID owner, UUID friend) {
        if (collection == null || owner == null || friend == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", owner.toString()),
                new Document("$unset", new Document("friendSince." + friend.toString(), ""))
        );
    }

    private void removeFriendNicknameEntry(UUID owner, UUID friend) {
        if (collection == null || owner == null || friend == null) {
            return;
        }
        collection.updateOne(
                eq("uuid", owner.toString()),
                new Document("$unset", new Document("friendNicknames." + friend.toString(), ""))
        );
    }

    public boolean removeFriend(UUID a, UUID b) {
        boolean removed = false;
        Set<UUID> left = friends.get(a);
        if (left != null) {
            removed = left.remove(b) || removed;
            if (left.isEmpty()) {
                friends.remove(a);
            }
        }
        removeFriendSinceLocal(a, b);
        setBestFriend(a, b, false);
        removeFriendNicknameLocal(a, b);
        Set<UUID> right = friends.get(b);
        if (right != null) {
            removed = right.remove(a) || removed;
            if (right.isEmpty()) {
                friends.remove(b);
            }
        }
        removeFriendSinceLocal(b, a);
        setBestFriend(b, a, false);
        removeFriendNicknameLocal(b, a);
        if (collection != null) {
            removeFriendEntry(a, b);
            removeFriendEntry(b, a);
            removeFriendSinceEntry(a, b);
            removeFriendSinceEntry(b, a);
            removeFriendNicknameEntry(a, b);
            removeFriendNicknameEntry(b, a);
        }
        if (removed) {
            publishFriendVisibilityUpdate(a, b);
        }
        return removed;
    }

    public int removeAllFriendsExceptBest(UUID ownerId) {
        if (ownerId == null) {
            return 0;
        }
        Set<UUID> ownerFriends = friends.get(ownerId);
        if (ownerFriends == null || ownerFriends.isEmpty()) {
            return 0;
        }
        Set<UUID> ownerBestFriends = bestFriendsByPlayer.get(ownerId);
        List<UUID> toRemove = new ArrayList<UUID>();
        for (UUID friendId : new ArrayList<UUID>(ownerFriends)) {
            if (friendId == null) {
                continue;
            }
            if (ownerBestFriends != null && ownerBestFriends.contains(friendId)) {
                continue;
            }
            toRemove.add(friendId);
        }
        if (toRemove.isEmpty()) {
            return 0;
        }

        int removedCount = 0;
        for (UUID friendId : toRemove) {
            boolean removed = false;

            Set<UUID> left = friends.get(ownerId);
            if (left != null) {
                removed = left.remove(friendId) || removed;
                if (left.isEmpty()) {
                    friends.remove(ownerId);
                }
            }
            removeFriendSinceLocal(ownerId, friendId);
            removeFriendNicknameLocal(ownerId, friendId);

            Set<UUID> right = friends.get(friendId);
            if (right != null) {
                removed = right.remove(ownerId) || removed;
                if (right.isEmpty()) {
                    friends.remove(friendId);
                }
            }
            removeFriendSinceLocal(friendId, ownerId);
            removeFriendNicknameLocal(friendId, ownerId);
            setBestFriend(friendId, ownerId, false);

            if (collection != null) {
                removeFriendEntry(ownerId, friendId);
                removeFriendEntry(friendId, ownerId);
                removeFriendSinceEntry(ownerId, friendId);
                removeFriendSinceEntry(friendId, ownerId);
                removeFriendNicknameEntry(ownerId, friendId);
                removeFriendNicknameEntry(friendId, ownerId);
            }
            if (removed) {
                publishFriendVisibilityUpdate(ownerId, friendId);
                removedCount++;
            }
        }
        return removedCount;
    }

    private void publishFriendVisibilityUpdate(UUID first, UUID second) {
        if (redis == null || first == null || second == null) {
            return;
        }
        try (Jedis jedis = redis.getPool().getResource()) {
            jedis.publish(FRIEND_VISIBILITY_UPDATE_CHANNEL, first.toString() + "," + second.toString());
        } catch (Exception ignored) {
            // Presence in DB is already authoritative; visibility will self-heal on refresh.
        }
    }

    private void setFriendSinceLocal(UUID owner, UUID friend, long since) {
        if (owner == null || friend == null) {
            return;
        }
        friendSinceByPlayer
                .computeIfAbsent(owner, key -> new ConcurrentHashMap<UUID, Long>())
                .put(friend, Math.max(0L, since));
    }

    private void loadFriendNicknames(UUID ownerId, Document friendNicknames) {
        if (ownerId == null || friendNicknames == null || friendNicknames.isEmpty()) {
            friendNicknamesByPlayer.remove(ownerId);
            return;
        }
        Map<UUID, String> parsed = new ConcurrentHashMap<UUID, String>();
        for (Map.Entry<String, Object> entry : friendNicknames.entrySet()) {
            if (entry == null) {
                continue;
            }
            String rawFriendId = entry.getKey();
            if (rawFriendId == null || rawFriendId.trim().isEmpty()) {
                continue;
            }
            UUID friendId;
            try {
                friendId = UUID.fromString(rawFriendId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String normalized = normalizeFriendNickname(entry.getValue() instanceof String ? (String) entry.getValue() : null);
            if (normalized == null) {
                continue;
            }
            parsed.put(friendId, normalized);
        }
        if (parsed.isEmpty()) {
            friendNicknamesByPlayer.remove(ownerId);
            return;
        }
        friendNicknamesByPlayer.put(ownerId, parsed);
    }

    private void setFriendNicknameLocal(UUID owner, UUID friend, String nickname) {
        String normalized = normalizeFriendNickname(nickname);
        if (owner == null || friend == null || normalized == null) {
            return;
        }
        friendNicknamesByPlayer
                .computeIfAbsent(owner, key -> new ConcurrentHashMap<UUID, String>())
                .put(friend, normalized);
    }

    private void removeFriendNicknameLocal(UUID owner, UUID friend) {
        if (owner == null || friend == null) {
            return;
        }
        Map<UUID, String> byOwner = friendNicknamesByPlayer.get(owner);
        if (byOwner == null) {
            return;
        }
        byOwner.remove(friend);
        if (byOwner.isEmpty()) {
            friendNicknamesByPlayer.remove(owner);
        }
    }

    private String normalizeFriendNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String trimmed = nickname.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void removeFriendSinceLocal(UUID owner, UUID friend) {
        if (owner == null || friend == null) {
            return;
        }
        Map<UUID, Long> byOwner = friendSinceByPlayer.get(owner);
        if (byOwner == null) {
            return;
        }
        byOwner.remove(friend);
        if (byOwner.isEmpty()) {
            friendSinceByPlayer.remove(owner);
        }
    }

    private Long parseLong(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (!(raw instanceof String)) {
            return null;
        }
        try {
            return Long.parseLong((String) raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class FriendPresence {
        private final boolean online;
        private final String serverName;
        private final ServerType serverType;
        private final String location;
        private final long updatedAt;

        private FriendPresence(boolean online,
                               String serverName,
                               ServerType serverType,
                               String location,
                               long updatedAt) {
            this.online = online;
            this.serverName = serverName;
            this.serverType = serverType == null ? ServerType.UNKNOWN : serverType;
            this.location = location == null ? "" : location;
            this.updatedAt = Math.max(0L, updatedAt);
        }

        public static FriendPresence offline(long updatedAt) {
            return new FriendPresence(false, null, ServerType.UNKNOWN, "", updatedAt);
        }

        public boolean isOnline() {
            return online;
        }

        public String getServerName() {
            return serverName;
        }

        public ServerType getServerType() {
            return serverType;
        }

        public String getLocation() {
            return location;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }
}
