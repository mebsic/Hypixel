package io.github.mebsic.proxy.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.github.mebsic.proxy.manager.MongoManager;
import org.bson.Document;

import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

public class ChatMessageService {
    private final MongoCollection<Document> chatMessages;
    private final MongoCollection<Document> profiles;
    private final RankResolver rankResolver;

    public ChatMessageService(MongoDatabase database, RankResolver rankResolver) {
        this.rankResolver = rankResolver;
        if (database == null) {
            this.chatMessages = null;
            this.profiles = null;
            return;
        }
        this.chatMessages = database.getCollection(MongoManager.CHAT_MESSAGES_COLLECTION);
        this.profiles = database.getCollection(MongoManager.PROFILES_COLLECTION);
    }

    public void storeMessage(UUID playerId,
                             String fallbackIgn,
                             String serverId,
                             ChatChannelService.ChatChannel chatType,
                             String message) {
        if (chatMessages == null || playerId == null || chatType == null || message == null) {
            return;
        }
        if (chatType != ChatChannelService.ChatChannel.ALL
                && chatType != ChatChannelService.ChatChannel.PARTY
                && chatType != ChatChannelService.ChatChannel.STAFF) {
            return;
        }
        if (message.trim().isEmpty()) {
            return;
        }
        try {
            ProfileReference profile = resolveProfileReference(playerId, fallbackIgn);
            Document doc = new Document("serverId", resolveServerId(serverId))
                    .append("type", chatType.name())
                    .append("rank", profile.rank)
                    .append("message", message)
                    .append("date", new Date())
                    .append("uuid", playerId.toString())
                    .append("ign", profile.ign);
            chatMessages.insertOne(doc);
        } catch (Exception ignored) {
        }
    }

    private ProfileReference resolveProfileReference(UUID playerId, String fallbackIgn) {
        String safeFallback = fallbackIgn == null ? "Unknown" : fallbackIgn.trim();
        if (safeFallback.isEmpty()) {
            safeFallback = "Unknown";
        }
        if (profiles == null || playerId == null) {
            return new ProfileReference(safeFallback, resolveRank(playerId, null));
        }
        try {
            Document profile = profiles.find(eq("uuid", playerId.toString()))
                    .projection(new Document("name", 1).append("rank", 1))
                    .first();
            if (profile == null) {
                return new ProfileReference(safeFallback, resolveRank(playerId, null));
            }
            String storedName = profile.getString("name");
            String resolvedIgn = storedName == null || storedName.trim().isEmpty()
                    ? safeFallback
                    : storedName;
            String storedRank = profile.getString("rank");
            return new ProfileReference(resolvedIgn, resolveRank(playerId, storedRank));
        } catch (Exception ignored) {
            return new ProfileReference(safeFallback, resolveRank(playerId, null));
        }
    }

    private String resolveRank(UUID playerId, String storedRank) {
        String normalizedStored = normalizeRank(storedRank);
        if (!"DEFAULT".equals(normalizedStored)) {
            return normalizedStored;
        }
        if (storedRank != null && storedRank.trim().equalsIgnoreCase("DEFAULT")) {
            return "DEFAULT";
        }
        if (playerId == null || rankResolver == null) {
            return "DEFAULT";
        }
        try {
            return normalizeRank(rankResolver.resolveRank(playerId));
        } catch (Throwable ignored) {
            return "DEFAULT";
        }
    }

    private String normalizeRank(String rank) {
        if (rank == null) {
            return "DEFAULT";
        }
        String normalized = rank.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "DEFAULT";
        }
        return normalized;
    }

    private String resolveServerId(String serverId) {
        if (serverId == null) {
            return "proxy";
        }
        String normalized = serverId.trim();
        if (normalized.isEmpty()) {
            return "proxy";
        }
        return normalized;
    }

    private static final class ProfileReference {
        private final String ign;
        private final String rank;

        private ProfileReference(String ign, String rank) {
            this.ign = ign == null || ign.trim().isEmpty() ? "Unknown" : ign;
            this.rank = rank == null || rank.trim().isEmpty() ? "DEFAULT" : rank;
        }
    }
}
