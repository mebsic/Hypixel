package io.github.mebsic.core.service;

import com.google.gson.Gson;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;

import java.util.Locale;

public class QueueClient {
    private final io.github.mebsic.core.manager.RedisManager redis;
    private final Gson gson;

    public QueueClient(io.github.mebsic.core.manager.RedisManager redis) {
        this.redis = redis;
        this.gson = new Gson();
    }

    public void queuePlayer(Player player, ServerType type) {
        if (player == null || type == null || redis == null) {
            return;
        }
        QueueRequest request = new QueueRequest(
                player.getUniqueId().toString(),
                type.getId());
        String payload = gson.toJson(request);
        try (Jedis jedis = redis.getPool().getResource()) {
            jedis.rpush(requestKey(type), payload);
        }
    }

    private String requestKey(ServerType type) {
        return "queue:request:" + type.getId().toLowerCase(Locale.ROOT);
    }

    private static class QueueRequest {
        private final String uuid;
        private final String gameType;

        private QueueRequest(String uuid, String gameType) {
            this.uuid = uuid;
            this.gameType = gameType;
        }
    }
}
