package io.github.mebsic.proxy.cache;

import io.github.mebsic.proxy.config.ProxyConfig;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MotdCache {
    private final MongoDatabase database;
    private final ProxyConfig config;
    private final AtomicReference<String> motdFirstLine;
    private final AtomicReference<String> motdSecondLine;
    private final AtomicReference<String> maintenanceMotdFirstLine;
    private final AtomicReference<String> maintenanceMotdSecondLine;
    private final AtomicReference<Integer> maxPlayers;
    private final AtomicReference<Boolean> maintenanceEnabled;
    private final AtomicLong lastRefresh;
    private final AtomicBoolean refreshing;

    public MotdCache(MongoDatabase database, ProxyConfig config) {
        this.database = database;
        this.config = config;
        this.motdFirstLine = new AtomicReference<>("");
        this.motdSecondLine = new AtomicReference<>("");
        this.maintenanceMotdFirstLine = new AtomicReference<>("");
        this.maintenanceMotdSecondLine = new AtomicReference<>("");
        this.maxPlayers = new AtomicReference<>(null);
        this.maintenanceEnabled = new AtomicReference<>(false);
        this.lastRefresh = new AtomicLong(0L);
        this.refreshing = new AtomicBoolean(false);
    }

    public String getMotdFirstLine() {
        return motdFirstLine.get();
    }

    public String getMotdSecondLine() {
        return motdSecondLine.get();
    }

    public String getMaintenanceMotdFirstLine() {
        return maintenanceMotdFirstLine.get();
    }

    public String getMaintenanceMotdSecondLine() {
        return maintenanceMotdSecondLine.get();
    }

    public Integer getMaxPlayers() {
        return maxPlayers.get();
    }

    public boolean isMaintenanceEnabled() {
        Boolean value = maintenanceEnabled.get();
        return value != null && value;
    }

    public boolean shouldRefresh(long now) {
        long ttlMillis = Math.max(5, config.getCacheTtlSeconds()) * 1000L;
        return now - lastRefresh.get() >= ttlMillis;
    }

    public boolean hasRefreshed() {
        return lastRefresh.get() > 0L;
    }

    public boolean startRefresh() {
        return refreshing.compareAndSet(false, true);
    }

    public void finishRefresh() {
        refreshing.set(false);
        lastRefresh.set(System.currentTimeMillis());
    }

    public void refresh() {
        if (database == null) {
            motdFirstLine.set("§aHycopy Network");
            motdSecondLine.set("");
            maintenanceMotdFirstLine.set("§cMaintenance mode");
            maintenanceMotdSecondLine.set("");
            maxPlayers.set(200000);
            return;
        }
        MongoCollection<Document> collection = database.getCollection(config.getMotdCollection());
        Document doc = collection.find(new Document("_id", config.getMotdDocumentId())).first();
        if (doc != null) {
            String first = nonBlank(doc.getString("motdFirstLine"));
            String second = nonBlank(doc.getString("motdSecondLine"));
            String maintFirst = nonBlank(doc.getString("maintenanceMotdFirstLine"));
            String maintSecond = nonBlank(doc.getString("maintenanceMotdSecondLine"));
            String fallback = nonBlank(doc.getString(config.getMotdField()));
            Integer configuredMaxPlayers = positiveInt(doc.get("playerCount"));
            if (configuredMaxPlayers == null) {
                configuredMaxPlayers = positiveInt(doc.get("maxPlayers"));
            }
            Boolean enabled = doc.getBoolean("maintenanceEnabled");

            if (fallback != null) {
                String[] lines = fallback.split("\\R", 2);
                if (first == null && lines.length > 0) {
                    first = nonBlank(lines[0]);
                }
                if (second == null && lines.length > 1) {
                    second = nonBlank(lines[1]);
                }
            }

            if (first != null) {
                motdFirstLine.set(first);
            }
            motdSecondLine.set(second == null ? "" : second);
            if (maintFirst != null) {
                maintenanceMotdFirstLine.set(maintFirst);
            }
            maintenanceMotdSecondLine.set(maintSecond == null ? "" : maintSecond);
            if (enabled != null) {
                maintenanceEnabled.set(enabled);
            }
            maxPlayers.set(configuredMaxPlayers);
            return;
        }
        motdFirstLine.set("§aHycopy Network");
        motdSecondLine.set("");
        maintenanceMotdFirstLine.set("§cMaintenance mode");
        maintenanceMotdSecondLine.set("");
        maxPlayers.set(null);
    }

    private String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer positiveInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            int intValue = ((Number) value).intValue();
            return intValue > 0 ? intValue : null;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
