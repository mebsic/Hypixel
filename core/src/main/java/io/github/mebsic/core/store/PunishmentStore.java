package io.github.mebsic.core.store;

import io.github.mebsic.core.model.Punishment;
import io.github.mebsic.core.model.PunishmentType;
import io.github.mebsic.core.manager.MongoManager;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;

import java.util.UUID;
import java.util.regex.Pattern;

public class PunishmentStore {
    private final MongoManager mongo;

    public PunishmentStore(MongoManager mongo) {
        this.mongo = mongo;
    }

    public void save(Punishment punishment) {
        MongoCollection<Document> collection = mongo.getPunishments();
        Document doc = new Document("_id", punishment.getId())
                .append("type", punishment.getType().name())
                .append("targetUuid", punishment.getTargetUuid().toString())
                .append("targetName", punishment.getTargetName())
                .append("actorUuid", punishment.getActorUuid() == null ? null : punishment.getActorUuid().toString())
                .append("actorName", punishment.getActorName())
                .append("reason", punishment.getReason())
                .append("createdAt", punishment.getCreatedAt())
                .append("expiresAt", punishment.getExpiresAt())
                .append("active", punishment.isActive());
        collection.insertOne(doc);
    }

    public Punishment findActive(UUID targetUuid, PunishmentType type) {
        MongoCollection<Document> collection = mongo.getPunishments();
        Document doc = collection.find(Filters.and(
                Filters.eq("targetUuid", targetUuid.toString()),
                Filters.eq("type", type.name()),
                Filters.eq("active", true)
        )).sort(new Document("createdAt", -1)).first();
        if (doc == null) {
            return null;
        }
        Long expiresAt = doc.getLong("expiresAt");
        if (expiresAt != null && expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            return null;
        }
        return fromDocument(doc);
    }

    public Punishment findActiveByName(String targetName, PunishmentType type) {
        if (targetName == null || targetName.trim().isEmpty() || type == null) {
            return null;
        }
        String trimmed = targetName.trim();
        MongoCollection<Document> collection = mongo.getPunishments();
        Document doc = collection.find(Filters.and(
                Filters.regex("targetName", "^" + Pattern.quote(trimmed) + "$", "i"),
                Filters.eq("type", type.name()),
                Filters.eq("active", true)
        )).sort(new Document("createdAt", -1)).first();
        if (doc == null) {
            return null;
        }
        Long expiresAt = doc.getLong("expiresAt");
        if (expiresAt != null && expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            return null;
        }
        return fromDocument(doc);
    }

    public long deactivateActive(UUID targetUuid, PunishmentType type) {
        MongoCollection<Document> collection = mongo.getPunishments();
        UpdateResult result = collection.updateMany(Filters.and(
                Filters.eq("targetUuid", targetUuid.toString()),
                Filters.eq("type", type.name()),
                Filters.eq("active", true)
        ), new Document("$set", new Document("active", false)));
        return result == null ? 0L : result.getModifiedCount();
    }

    private Punishment fromDocument(Document doc) {
        String id = doc.getString("_id");
        PunishmentType type = PunishmentType.valueOf(doc.getString("type"));
        UUID targetUuid = UUID.fromString(doc.getString("targetUuid"));
        String targetName = doc.getString("targetName");
        String actorUuidRaw = doc.getString("actorUuid");
        UUID actorUuid = actorUuidRaw == null ? null : UUID.fromString(actorUuidRaw);
        String actorName = doc.getString("actorName");
        String reason = doc.getString("reason");
        long createdAt = doc.getLong("createdAt");
        Long expiresAt = doc.getLong("expiresAt");
        boolean active = doc.getBoolean("active", true);
        return new Punishment(id, type, targetUuid, targetName, actorUuid, actorName, reason, createdAt, expiresAt, active);
    }
}
