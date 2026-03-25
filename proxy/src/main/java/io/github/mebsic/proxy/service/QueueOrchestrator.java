package io.github.mebsic.proxy.service;

import com.google.gson.Gson;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.manager.RedisManager;
import io.github.mebsic.proxy.util.Components;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import redis.clients.jedis.Jedis;

import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class QueueOrchestrator {
    private final ProxyServer proxy;
    private final ServerRegistryService registryService;
    private final RedisManager redis;
    private final PartyService partyService;
    private final Gson gson;
    private final Queue<QueueRequest> pending;
    private final Set<UUID> queued;
    private final String[] requestKeys;
    private volatile boolean running;
    private Thread worker;

    public QueueOrchestrator(ProxyServer proxy, ServerRegistryService registryService, RedisManager redis, PartyService partyService) {
        this.proxy = proxy;
        this.registryService = registryService;
        this.redis = redis;
        this.partyService = partyService;
        this.gson = new Gson();
        this.pending = new ConcurrentLinkedQueue<>();
        this.queued = ConcurrentHashMap.newKeySet();
        this.requestKeys = buildKeys();
    }

    public void start() {
        if (proxy == null || redis == null || registryService == null || requestKeys.length == 0) {
            return;
        }
        running = true;
        worker = new Thread(this::runQueueReader, "Queue-Orchestrator");
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        pending.clear();
        queued.clear();
    }

    public void remove(UUID uuid) {
        if (uuid == null) {
            return;
        }
        queued.remove(uuid);
        pending.removeIf(req -> uuid.toString().equals(req.uuid));
    }

    public boolean requestQueue(UUID uuid, ServerType type) {
        if (uuid == null || type == null || !type.isGame()) {
            return false;
        }
        if (!queued.add(uuid)) {
            return false;
        }
        QueueRequest request = new QueueRequest();
        request.uuid = uuid.toString();
        request.gameType = type.getId();
        pending.add(request);
        return true;
    }

    public void processAssignments() {
        if (proxy == null || registryService == null) {
            return;
        }
        int size = pending.size();
        if (size <= 0) {
            return;
        }
        registryService.refreshIfStale(0L);
        for (int i = 0; i < size; i++) {
            QueueRequest request = pending.poll();
            if (request == null) {
                continue;
            }
            UUID uuid = request.uuidAsUuid();
            if (uuid == null) {
                continue;
            }
            ServerType type = request.type();
            if (type == null || !type.isGame()) {
                queued.remove(uuid);
                continue;
            }
            java.util.Optional<Player> playerOpt = proxy.getPlayer(uuid);
            if (!playerOpt.isPresent()) {
                queued.remove(uuid);
                continue;
            }
            Player player = playerOpt.get();
            java.util.Optional<String> targetName = registryService.findAvailableGameServerName(type);
            if (targetName.isPresent()) {
                java.util.Optional<RegisteredServer> target = proxy.getServer(targetName.get());
                if (target.isPresent()) {
                    boolean suppressTransferMessage = partyService != null
                            && partyService.isInParty(player.getUniqueId())
                            && !partyService.isLeader(player.getUniqueId());
                    if (!suppressTransferMessage) {
                        player.sendMessage(Components.transferToServer(targetName.get()));
                    }
                    player.createConnectionRequest(target.get()).fireAndForget();
                } else {
                    player.sendMessage(Component.text(
                            "No available servers for that game right now.",
                            NamedTextColor.RED));
                }
                queued.remove(uuid);
                continue;
            }
            player.sendMessage(Component.text(
                    "No available servers for that game right now.",
                    NamedTextColor.RED));
            queued.remove(uuid);
        }
    }

    private void runQueueReader() {
        while (running) {
            try (Jedis jedis = redis.getPool().getResource()) {
                java.util.List<String> result = jedis.brpop(1, requestKeys);
                if (result == null || result.size() < 2) {
                    continue;
                }
                String payload = result.get(1);
                QueueRequest request = gson.fromJson(payload, QueueRequest.class);
                if (request == null || request.uuid == null) {
                    continue;
                }
                UUID uuid = request.uuidAsUuid();
                if (uuid == null) {
                    continue;
                }
                if (!queued.add(uuid)) {
                    continue;
                }
                pending.add(request);
            } catch (Exception ignored) {
                // Keep loop alive if Redis blips.
            }
        }
    }

    private String[] buildKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (ServerType type : ServerType.values()) {
            if (type.isGame()) {
                keys.add(requestKey(type));
            }
        }
        return keys.toArray(new String[0]);
    }

    private String requestKey(ServerType type) {
        return "queue:request:" + type.getId().toLowerCase(Locale.ROOT);
    }

    private static class QueueRequest {
        private String uuid;
        private String gameType;

        private UUID uuidAsUuid() {
            if (uuid == null) {
                return null;
            }
            try {
                return UUID.fromString(uuid);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private ServerType type() {
            return ServerType.fromString(gameType);
        }
    }
}
