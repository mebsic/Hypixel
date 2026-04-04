package io.github.mebsic.proxy.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.proxy.manager.MongoManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bson.Document;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class ChatChannelService {
    public enum ChatChannel {
        ALL,
        PARTY;

        public static ChatChannel fromInput(String input) {
            if (input == null) {
                return null;
            }
            String normalized = input.trim().toLowerCase(Locale.ROOT);
            switch (normalized) {
                case "all":
                case "a":
                    return ALL;
                case "party":
                case "p":
                    return PARTY;
                default:
                    return null;
            }
        }
    }

    private final MongoCollection<Document> collection;
    private final Map<UUID, ChatChannel> channelByPlayer = new ConcurrentHashMap<UUID, ChatChannel>();

    public ChatChannelService() {
        this(null);
    }

    public ChatChannelService(MongoDatabase database) {
        this.collection = database == null ? null : database.getCollection(MongoManager.PROFILES_COLLECTION);
    }

    public void track(UUID playerId) {
        if (playerId == null) {
            return;
        }
        ensurePlayerRecord(playerId);
        loadChannel(playerId);
    }

    public void bootstrapOnlinePlayers(ProxyServer proxy) {
        if (proxy == null) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            if (player == null) {
                continue;
            }
            track(player.getUniqueId());
        }
    }

    public ChatChannel getChannel(UUID playerId) {
        if (playerId == null) {
            return ChatChannel.ALL;
        }
        ChatChannel current = channelByPlayer.get(playerId);
        return current == null ? ChatChannel.ALL : current;
    }

    public void setChannel(UUID playerId, ChatChannel channel) {
        if (playerId == null || channel == null) {
            clear(playerId);
            return;
        }
        if (channel == ChatChannel.ALL) {
            channelByPlayer.remove(playerId);
        } else {
            channelByPlayer.put(playerId, channel);
        }
        persistChannel(playerId, channel);
    }

    public void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        channelByPlayer.remove(playerId);
    }

    private void ensurePlayerRecord(UUID playerId) {
        if (collection == null || playerId == null) {
            return;
        }
        try {
            collection.updateOne(
                    eq("uuid", playerId.toString()),
                    new Document("$setOnInsert", new Document(MongoManager.PROFILE_CHAT_TYPE_FIELD, ChatChannel.ALL.name())),
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception ignored) {
        }
    }

    private void loadChannel(UUID playerId) {
        if (collection == null || playerId == null) {
            return;
        }
        try {
            Document doc = collection.find(eq("uuid", playerId.toString()))
                    .projection(new Document(MongoManager.PROFILE_CHAT_TYPE_FIELD, 1))
                    .first();
            if (doc == null) {
                channelByPlayer.remove(playerId);
                return;
            }
            String stored = doc.getString(MongoManager.PROFILE_CHAT_TYPE_FIELD);
            ChatChannel resolved = ChatChannel.fromInput(stored);
            if (resolved == null || resolved == ChatChannel.ALL) {
                channelByPlayer.remove(playerId);
                return;
            }
            channelByPlayer.put(playerId, resolved);
        } catch (Exception ignored) {
        }
    }

    private void persistChannel(UUID playerId, ChatChannel channel) {
        if (collection == null || playerId == null || channel == null) {
            return;
        }
        try {
            collection.updateOne(
                    eq("uuid", playerId.toString()),
                    new Document("$set", new Document(MongoManager.PROFILE_CHAT_TYPE_FIELD, channel.name()))
                            .append("$setOnInsert", new Document(MongoManager.PROFILE_CHAT_TYPE_FIELD, ChatChannel.ALL.name())),
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception ignored) {
        }
    }
}
