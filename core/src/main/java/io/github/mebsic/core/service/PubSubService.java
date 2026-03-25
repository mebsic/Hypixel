package io.github.mebsic.core.service;

import io.github.mebsic.core.manager.RedisManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class PubSubService {
    private final RedisManager redis;
    private final Map<String, CopyOnWriteArrayList<Consumer<String>>> handlersByChannel;
    private final Set<String> activeChannels;

    public PubSubService(RedisManager redis) {
        this.redis = redis;
        this.handlersByChannel = new ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>>();
        this.activeChannels = ConcurrentHashMap.newKeySet();
    }

    public void publish(String channel, String message) {
        try (Jedis jedis = redis.getPool().getResource()) {
            jedis.publish(channel, message);
        }
    }

    public void subscribe(String channel, Consumer<String> handler) {
        if (channel == null || channel.trim().isEmpty() || handler == null) {
            return;
        }
        String normalizedChannel = channel.trim();
        handlersByChannel
                .computeIfAbsent(normalizedChannel, key -> new CopyOnWriteArrayList<Consumer<String>>())
                .add(handler);
        if (!activeChannels.add(normalizedChannel)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try (Jedis jedis = redis.createSubscriberClient()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        CopyOnWriteArrayList<Consumer<String>> handlers = handlersByChannel.get(ch);
                        if (handlers == null || handlers.isEmpty()) {
                            return;
                        }
                        for (Consumer<String> consumer : handlers) {
                            try {
                                consumer.accept(message);
                            } catch (Exception ignored) {
                                // Keep dispatching to remaining handlers.
                            }
                        }
                    }
                }, normalizedChannel);
            } finally {
                activeChannels.remove(normalizedChannel);
            }
        }, "Redis-Sub-" + normalizedChannel);
        thread.setDaemon(true);
        thread.start();
    }
}
