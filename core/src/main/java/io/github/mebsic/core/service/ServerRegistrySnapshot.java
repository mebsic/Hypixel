package io.github.mebsic.core.service;

import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.server.ServerType;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ServerRegistrySnapshot {
    private final JavaPlugin plugin;
    private final MongoManager mongo;
    private final String group;
    private final int staleSeconds;
    private final long refreshMillis;
    private final Map<String, Integer> cachedTotals;
    private final Map<String, Long> lastRefreshByType;
    private final Set<String> refreshInProgress;

    public ServerRegistrySnapshot(JavaPlugin plugin, MongoManager mongo, String group, int staleSeconds, long refreshMillis) {
        this.plugin = plugin;
        this.mongo = mongo;
        this.group = group;
        this.staleSeconds = Math.max(0, staleSeconds);
        this.refreshMillis = Math.max(50L, refreshMillis);
        this.cachedTotals = new ConcurrentHashMap<>();
        this.lastRefreshByType = new ConcurrentHashMap<>();
        this.refreshInProgress = ConcurrentHashMap.newKeySet();
    }

    public int getTotalPlayers(ServerType type) {
        return getTotalPlayers(type, false);
    }

    public int getTotalPlayers(ServerType type, boolean inGameOnly) {
        if (mongo == null || type == null) {
            return 0;
        }
        String key = cacheKey(type, inGameOnly);
        Integer cached = cachedTotals.get(key);
        if (cached == null) {
            int loaded = loadTotalPlayers(type, inGameOnly);
            cachedTotals.put(key, loaded);
            lastRefreshByType.put(key, System.currentTimeMillis());
            return loaded;
        }
        long now = System.currentTimeMillis();
        long last = lastRefreshByType.getOrDefault(key, 0L);
        if (now - last >= refreshMillis) {
            refreshAsync(type, key, inGameOnly);
        }
        return Math.max(0, cached);
    }

    private void refreshAsync(ServerType type, String key, boolean inGameOnly) {
        if (type == null || key == null || key.trim().isEmpty()) {
            return;
        }
        if (!refreshInProgress.add(key)) {
            return;
        }
        Runnable refresh = () -> {
            try {
                int loaded = loadTotalPlayers(type, inGameOnly);
                cachedTotals.put(key, loaded);
                lastRefreshByType.put(key, System.currentTimeMillis());
            } finally {
                refreshInProgress.remove(key);
            }
        };
        if (plugin == null) {
            refresh.run();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, refresh);
    }

    private int loadTotalPlayers(ServerType type, boolean inGameOnly) {
        if (mongo == null || type == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int total = 0;
        MongoCollection<Document> collection = mongo.getServerRegistry();
        for (Document doc : collection.find(Filters.eq("type", type.getId()))) {
            if (!isValid(doc, now, inGameOnly)) {
                continue;
            }
            total += safeInt(doc.get("players"));
        }
        return total;
    }

    private boolean isValid(Document doc, long now, boolean inGameOnly) {
        if (doc == null) {
            return false;
        }
        if (group != null && !group.trim().isEmpty()) {
            String entryGroup = doc.getString("group");
            if (entryGroup == null || !group.equalsIgnoreCase(entryGroup)) {
                return false;
            }
        }
        String status = doc.getString("status");
        if (status != null && !status.equalsIgnoreCase("online")) {
            return false;
        }
        if (inGameOnly) {
            String state = doc.getString("state");
            if (state == null || !state.equalsIgnoreCase("IN_GAME")) {
                return false;
            }
        }
        if (staleSeconds > 0) {
            Long heartbeat = doc.getLong("lastHeartbeat");
            if (heartbeat != null && now - heartbeat > staleSeconds * 1000L) {
                return false;
            }
        }
        return true;
    }

    private String cacheKey(ServerType type, boolean inGameOnly) {
        if (type == null) {
            return "";
        }
        String base = type.getId().toLowerCase(Locale.ROOT);
        return inGameOnly ? base + ":ingame" : base + ":all";
    }

    private int safeInt(Object value) {
        if (value instanceof Number) {
            return Math.max(0, ((Number) value).intValue());
        }
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.toString().trim()));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
