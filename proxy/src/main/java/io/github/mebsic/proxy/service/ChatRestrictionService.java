package io.github.mebsic.proxy.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatRestrictionService {
    private static final long MUTE_CACHE_MILLIS = 2_000L;
    private final MongoCollection<Document> punishments;
    private final Map<UUID, CachedMuteState> muteCache = new ConcurrentHashMap<>();

    public ChatRestrictionService(MongoDatabase database) {
        this.punishments = database == null ? null : database.getCollection("punishments");
    }

    public boolean isMuted(UUID playerId) {
        if (playerId == null || punishments == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CachedMuteState cached = muteCache.get(playerId);
        if (cached != null && cached.expiresAt > now) {
            return cached.muted;
        }

        Document doc = punishments.find(Filters.and(
                        Filters.eq("targetUuid", playerId.toString()),
                        Filters.eq("type", "MUTE"),
                        Filters.eq("active", true)
                ))
                .sort(new Document("createdAt", -1))
                .projection(new Document("expiresAt", 1))
                .first();
        boolean muted = false;
        if (doc != null) {
            Long expiresAt = doc.getLong("expiresAt");
            muted = expiresAt == null || expiresAt <= 0L || now <= expiresAt.longValue();
        }
        muteCache.put(playerId, new CachedMuteState(muted, now + MUTE_CACHE_MILLIS));
        return muted;
    }

    private static final class CachedMuteState {
        private final boolean muted;
        private final long expiresAt;

        private CachedMuteState(boolean muted, long expiresAt) {
            this.muted = muted;
            this.expiresAt = expiresAt;
        }
    }
}
