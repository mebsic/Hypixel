package io.github.mebsic.proxy.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.mebsic.core.manager.RedisManager;
import org.bson.Document;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class BlockService {
    private static final String FRIEND_VISIBILITY_UPDATE_CHANNEL = "friend_visibility_update";

    private final MongoCollection<Document> collection;
    private final RedisManager redis;
    private final RankResolver rankResolver;
    private final Map<UUID, Set<UUID>> blockedPlayersByPlayer = new ConcurrentHashMap<>();

    public BlockService(MongoDatabase database, String collectionName, RedisManager redis) {
        this(database, collectionName, redis, null);
    }

    public BlockService(MongoDatabase database, String collectionName, RedisManager redis, RankResolver rankResolver) {
        this.redis = redis;
        this.rankResolver = rankResolver;
        if (database == null || collectionName == null || collectionName.trim().isEmpty()) {
            this.collection = null;
        } else {
            this.collection = database.getCollection(collectionName);
        }
    }

    public void bootstrapOnlinePlayers(ProxyServer proxy) {
        if (proxy == null) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            if (player == null) {
                continue;
            }
            ensurePlayerDocument(player.getUniqueId(), player.getUsername());
        }
    }

    public void ensurePlayerDocument(UUID uuid, String name) {
        if (collection == null || uuid == null) {
            return;
        }
        try {
            Document setOnInsert = new Document("uuid", uuid.toString())
                    .append("blockedPlayers", new ArrayList<String>());
            Document update = new Document("$setOnInsert", setOnInsert);
            if (name != null && !name.trim().isEmpty()) {
                update.append("$set", new Document("name", name.trim()));
            }
            collection.updateOne(
                    eq("uuid", uuid.toString()),
                    update,
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception ignored) {
        }
    }

    public boolean isBlocked(UUID ownerId, UUID targetId) {
        if (ownerId == null || targetId == null) {
            return false;
        }
        if (isStaff(ownerId) || isStaff(targetId)) {
            return false;
        }
        Set<UUID> blocked = blockedPlayersByPlayer.get(ownerId);
        if (blocked == null) {
            loadBlockedPlayers(ownerId);
            blocked = blockedPlayersByPlayer.get(ownerId);
        }
        return blocked != null && blocked.contains(targetId);
    }

    public boolean isEitherBlocked(UUID first, UUID second) {
        if (first == null || second == null) {
            return false;
        }
        return isBlocked(first, second) || isBlocked(second, first);
    }

    public boolean blockPlayer(UUID ownerId, UUID targetId) {
        if (ownerId == null || targetId == null || ownerId.equals(targetId)) {
            return false;
        }
        if (isStaff(ownerId) || isStaff(targetId)) {
            return false;
        }
        Set<UUID> blocked = mutableBlockedSet(ownerId);
        if (!blocked.add(targetId)) {
            return false;
        }
        persistBlockedPlayerEntry(ownerId, targetId);
        publishFriendVisibilityUpdate(ownerId, targetId);
        return true;
    }

    public boolean unblockPlayer(UUID ownerId, UUID targetId) {
        if (ownerId == null || targetId == null || ownerId.equals(targetId)) {
            return false;
        }
        Set<UUID> blocked = blockedPlayersByPlayer.get(ownerId);
        if (blocked == null || !blocked.remove(targetId)) {
            return false;
        }
        if (blocked.isEmpty()) {
            blockedPlayersByPlayer.remove(ownerId);
        }
        removeBlockedPlayerEntry(ownerId, targetId);
        publishFriendVisibilityUpdate(ownerId, targetId);
        return true;
    }

    public Set<UUID> getBlockedPlayers(UUID ownerId) {
        if (ownerId == null) {
            return Collections.emptySet();
        }
        Set<UUID> blocked = blockedPlayersByPlayer.get(ownerId);
        if (blocked == null) {
            loadBlockedPlayers(ownerId);
            blocked = blockedPlayersByPlayer.get(ownerId);
        }
        if (blocked == null || blocked.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<UUID>(blocked));
    }

    public int unblockAllPlayers(UUID ownerId) {
        if (ownerId == null) {
            return 0;
        }
        Set<UUID> blocked = blockedPlayersByPlayer.remove(ownerId);
        if (blocked == null || blocked.isEmpty()) {
            return 0;
        }
        int removed = blocked.size();
        if (collection != null) {
            try {
                collection.updateOne(
                        eq("uuid", ownerId.toString()),
                        new Document("$set", new Document("blockedPlayers", new ArrayList<String>()))
                                .append("$setOnInsert", new Document("uuid", ownerId.toString())
                                        .append("blockedPlayers", new ArrayList<String>())),
                        new UpdateOptions().upsert(true)
                );
            } catch (Exception ignored) {
            }
        }
        for (UUID blockedId : blocked) {
            if (blockedId == null) {
                continue;
            }
            publishFriendVisibilityUpdate(ownerId, blockedId);
        }
        return removed;
    }

    private Set<UUID> mutableBlockedSet(UUID ownerId) {
        Set<UUID> existing = blockedPlayersByPlayer.get(ownerId);
        if (existing != null) {
            return existing;
        }
        loadBlockedPlayers(ownerId);
        return blockedPlayersByPlayer.computeIfAbsent(ownerId, key -> ConcurrentHashMap.newKeySet());
    }

    private void loadBlockedPlayers(UUID uuid) {
        if (collection == null || uuid == null) {
            return;
        }
        try {
            Document doc = collection.find(eq("uuid", uuid.toString()))
                    .projection(new Document("blockedPlayers", 1))
                    .first();
            if (doc == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> storedBlocked = (List<String>) doc.get("blockedPlayers");
            if (storedBlocked == null || storedBlocked.isEmpty()) {
                return;
            }
            Set<UUID> blocked = blockedPlayersByPlayer.computeIfAbsent(uuid, key -> ConcurrentHashMap.newKeySet());
            for (String raw : storedBlocked) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                try {
                    blocked.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistBlockedPlayerEntry(UUID owner, UUID blocked) {
        if (collection == null || owner == null || blocked == null) {
            return;
        }
        try {
            collection.updateOne(
                    eq("uuid", owner.toString()),
                    new Document("$addToSet", new Document("blockedPlayers", blocked.toString()))
                            .append("$setOnInsert", new Document("uuid", owner.toString())
                                    .append("blockedPlayers", new ArrayList<String>())),
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception ignored) {
        }
    }

    private void removeBlockedPlayerEntry(UUID owner, UUID blocked) {
        if (collection == null || owner == null || blocked == null) {
            return;
        }
        try {
            collection.updateOne(
                    eq("uuid", owner.toString()),
                    new Document("$pull", new Document("blockedPlayers", blocked.toString()))
            );
        } catch (Exception ignored) {
        }
    }

    private void publishFriendVisibilityUpdate(UUID first, UUID second) {
        if (redis == null || first == null || second == null) {
            return;
        }
        try (Jedis jedis = redis.getPool().getResource()) {
            jedis.publish(FRIEND_VISIBILITY_UPDATE_CHANNEL, first.toString() + "," + second.toString());
        } catch (Exception ignored) {
            // Database state remains authoritative even if pubsub publish fails.
        }
    }

    private boolean isStaff(UUID uuid) {
        if (uuid == null || rankResolver == null) {
            return false;
        }
        try {
            return rankResolver.isStaff(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
