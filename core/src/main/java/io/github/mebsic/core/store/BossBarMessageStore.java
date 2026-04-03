package io.github.mebsic.core.store;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.BossBarMessage;
import io.github.mebsic.core.server.ServerType;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.mongodb.client.model.Filters.eq;

public class BossBarMessageStore {
    public static final String SCOPE_GAME = "GAME";
    public static final String SCOPE_HUB = "HUB";

    private static final float MIN_VALUE = 0.01f;
    private static final float MAX_VALUE = 1.0f;
    private static final double MIN_SECONDS = 0.0D;
    private static final double MAX_SECONDS = 4.0D;

    private final MongoCollection<Document> collection;

    public BossBarMessageStore(MongoManager mongo) {
        this.collection = mongo == null ? null : mongo.getBossBarMessages();
    }

    public void ensureDefaults() {
        if (collection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        upsertDefault(new Document("id", "game_default_banner")
                .append("scope", SCOPE_GAME)
                .append("serverType", "ANY")
                .append("text", "Playing {gameType} on {domain}")
                .append("value", 1.0)
                .append("enabled", true)
                .append("createdAt", now)
                .append("updatedAt", now));
        upsertDefault(new Document("id", "hub_news_1")
                .append("scope", SCOPE_HUB)
                .append("serverType", "ANY")
                .append("text", "")
                .append("value", 1.0)
                .append("animationType", "NONE")
                .append("startColor", "YELLOW")
                .append("animationColor", "GOLD")
                .append("endColor", "WHITE")
                .append("startSeconds", 0.0)
                .append("endSeconds", 4.0)
                .append("enabled", true)
                .append("createdAt", now)
                .append("updatedAt", now));
        upsertDefault(new Document("id", "hub_news_2")
                .append("scope", SCOPE_HUB)
                .append("serverType", "ANY")
                .append("text", "")
                .append("value", 1.0)
                .append("animationType", "NONE")
                .append("startColor", "YELLOW")
                .append("animationColor", "GOLD")
                .append("endColor", "WHITE")
                .append("startSeconds", 0.0)
                .append("endSeconds", 4.0)
                .append("enabled", true)
                .append("createdAt", now)
                .append("updatedAt", now));
        upsertDefault(new Document("id", "hub_news_3")
                .append("scope", SCOPE_HUB)
                .append("serverType", "ANY")
                .append("text", "")
                .append("value", 1.0)
                .append("animationType", "NONE")
                .append("startColor", "YELLOW")
                .append("animationColor", "GOLD")
                .append("endColor", "WHITE")
                .append("startSeconds", 0.0)
                .append("endSeconds", 4.0)
                .append("enabled", true)
                .append("createdAt", now)
                .append("updatedAt", now));
    }

    private void upsertDefault(Document defaults) {
        if (collection == null || defaults == null) {
            return;
        }
        String id = defaults.getString("id");
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        collection.updateOne(eq("id", id),
                new Document("$setOnInsert", defaults),
                new UpdateOptions().upsert(true));
    }

    public List<BossBarMessage> loadByScope(String scope, ServerType type) {
        List<BossBarMessage> messages = new ArrayList<>();
        if (collection == null) {
            return messages;
        }
        String normalizedScope = normalizeScope(scope);
        for (Document doc : collection.find(eq("scope", normalizedScope))) {
            if (doc == null) {
                continue;
            }
            Boolean enabled = doc.getBoolean("enabled", true);
            if (enabled != null && !enabled) {
                continue;
            }
            String targetServerType = doc.getString("serverType");
            if (!matchesServerType(targetServerType, type)) {
                continue;
            }
            String id = doc.getString("id");
            String text = doc.getString("text");
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            Number valueNumber = doc.get("value", Number.class);
            float value = sanitizeValue(valueNumber == null ? 1.0f : valueNumber.floatValue());
            String animationType = doc.getString("animationType");
            String startColor = doc.getString("startColor");
            String animationColor = doc.getString("animationColor");
            String endColor = doc.getString("endColor");
            Number startSecondsNumber = doc.get("startSeconds", Number.class);
            Number endSecondsNumber = doc.get("endSeconds", Number.class);
            double startSeconds = sanitizeSeconds(parseSeconds(startSecondsNumber, MIN_SECONDS));
            double endSeconds = sanitizeSeconds(parseSeconds(endSecondsNumber, MAX_SECONDS));
            if (endSeconds < startSeconds) {
                endSeconds = startSeconds;
            }
            messages.add(new BossBarMessage(
                    id,
                    text,
                    value,
                    normalizedScope,
                    targetServerType,
                    animationType,
                    startColor,
                    animationColor,
                    endColor,
                    startSeconds,
                    endSeconds
            ));
        }
        return messages;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return SCOPE_GAME;
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (SCOPE_HUB.equals(normalized)) {
            return SCOPE_HUB;
        }
        return SCOPE_GAME;
    }

    private boolean matchesServerType(String targetServerType, ServerType type) {
        if (targetServerType == null || targetServerType.trim().isEmpty()) {
            return true;
        }
        String normalized = targetServerType.trim().toUpperCase(Locale.ROOT);
        if ("ANY".equals(normalized) || "ALL".equals(normalized)) {
            return true;
        }
        if ("HUB".equals(normalized)) {
            return type != null && type.isHub();
        }
        if ("GAME".equals(normalized)) {
            return type != null && type.isGame();
        }
        return type != null && normalized.equals(type.name());
    }

    private float sanitizeValue(float raw) {
        if (Float.isNaN(raw) || Float.isInfinite(raw)) {
            return 1.0f;
        }
        return Math.max(MIN_VALUE, Math.min(MAX_VALUE, raw));
    }

    private double parseSeconds(Number number, double fallback) {
        if (number == null) {
            return fallback;
        }
        double raw = number.doubleValue();
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return fallback;
        }
        return raw;
    }

    private double sanitizeSeconds(double raw) {
        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return MIN_SECONDS;
        }
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, raw));
    }
}
