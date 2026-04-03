package io.github.mebsic.core.store;

import io.github.mebsic.core.model.Punishment;
import io.github.mebsic.core.model.PunishmentType;
import io.github.mebsic.core.manager.MongoManager;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
                .append("active", punishment.isActive())
                .append("deactivatedByUuid", punishment.getDeactivatedByUuid() == null ? null : punishment.getDeactivatedByUuid().toString())
                .append("deactivatedByName", punishment.getDeactivatedByName())
                .append("deactivatedAt", punishment.getDeactivatedAt());
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

    public long deactivateActive(UUID targetUuid,
                                 PunishmentType type,
                                 UUID deactivatedByUuid,
                                 String deactivatedByName,
                                 Long deactivatedAt) {
        MongoCollection<Document> collection = mongo.getPunishments();
        Document updates = new Document("active", false)
                .append("deactivatedByUuid", deactivatedByUuid == null ? null : deactivatedByUuid.toString())
                .append("deactivatedByName", deactivatedByName)
                .append("deactivatedAt", deactivatedAt);
        UpdateResult result = collection.updateMany(Filters.and(
                Filters.eq("targetUuid", targetUuid.toString()),
                Filters.eq("type", type.name()),
                Filters.eq("active", true)
        ), new Document("$set", updates));
        return result == null ? 0L : result.getModifiedCount();
    }

    public long countByTargetUuid(UUID targetUuid, PunishmentType type) {
        if (targetUuid == null || type == null) {
            return 0L;
        }
        MongoCollection<Document> collection = mongo.getPunishments();
        return collection.countDocuments(Filters.and(
                Filters.eq("targetUuid", targetUuid.toString()),
                Filters.eq("type", type.name())
        ));
    }

    public long countByTargetName(String targetName, PunishmentType type) {
        if (targetName == null || targetName.trim().isEmpty() || type == null) {
            return 0L;
        }
        MongoCollection<Document> collection = mongo.getPunishments();
        String trimmed = targetName.trim();
        return collection.countDocuments(Filters.and(
                Filters.regex("targetName", "^" + Pattern.quote(trimmed) + "$", "i"),
                Filters.eq("type", type.name())
        ));
    }

    public List<Punishment> findByTargetUuid(UUID targetUuid, PunishmentType type, int skip, int limit) {
        if (targetUuid == null || type == null || limit <= 0) {
            return Collections.emptyList();
        }
        MongoCollection<Document> collection = mongo.getPunishments();
        int safeSkip = Math.max(0, skip);
        int safeLimit = Math.max(1, limit);
        List<Punishment> punishments = new ArrayList<Punishment>();
        for (Document doc : collection.find(Filters.and(
                Filters.eq("targetUuid", targetUuid.toString()),
                Filters.eq("type", type.name())
        )).sort(new Document("createdAt", -1)).skip(safeSkip).limit(safeLimit)) {
            punishments.add(fromDocument(doc));
        }
        return punishments;
    }

    public List<Punishment> findByTargetName(String targetName, PunishmentType type, int skip, int limit) {
        if (targetName == null || targetName.trim().isEmpty() || type == null || limit <= 0) {
            return Collections.emptyList();
        }
        String trimmed = targetName.trim();
        MongoCollection<Document> collection = mongo.getPunishments();
        int safeSkip = Math.max(0, skip);
        int safeLimit = Math.max(1, limit);
        List<Punishment> punishments = new ArrayList<Punishment>();
        for (Document doc : collection.find(Filters.and(
                Filters.regex("targetName", "^" + Pattern.quote(trimmed) + "$", "i"),
                Filters.eq("type", type.name())
        )).sort(new Document("createdAt", -1)).skip(safeSkip).limit(safeLimit)) {
            punishments.add(fromDocument(doc));
        }
        return punishments;
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
        String deactivatedByUuidRaw = doc.getString("deactivatedByUuid");
        UUID deactivatedByUuid = deactivatedByUuidRaw == null ? null : UUID.fromString(deactivatedByUuidRaw);
        String deactivatedByName = doc.getString("deactivatedByName");
        Long deactivatedAt = doc.getLong("deactivatedAt");
        return new Punishment(
                id,
                type,
                targetUuid,
                targetName,
                actorUuid,
                actorName,
                reason,
                createdAt,
                expiresAt,
                active,
                deactivatedByUuid,
                deactivatedByName,
                deactivatedAt
        );
    }
}
