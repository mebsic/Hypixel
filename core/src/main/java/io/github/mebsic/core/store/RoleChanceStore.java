package io.github.mebsic.core.store;

import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.game.model.RoleChance;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.eq;

public class RoleChanceStore {

    private final MongoCollection<Document> collection;

    public RoleChanceStore(MongoManager mongo) {
        this.collection = mongo.getRoleChances();
    }

    public Map<UUID, RoleChance> load(Collection<UUID> uuids, double defaultMurdererChance, double defaultDetectiveChance) {
        Map<UUID, RoleChance> chances = new HashMap<>();
        if (uuids == null || uuids.isEmpty()) {
            return chances;
        }
        List<String> ids = new ArrayList<>();
        for (UUID uuid : uuids) {
            ids.add(uuid.toString());
        }
        for (Document doc : collection.find(and(eq(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_ROLE_CHANCE_RECORD_TYPE), in("uuid", ids)))) {
            String id = doc.getString("uuid");
            if (id == null) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Number murdererValue = doc.get("murdererChance", Number.class);
            Number detectiveValue = doc.get("detectiveChance", Number.class);
            double murdererChance = murdererValue != null ? murdererValue.doubleValue() : defaultMurdererChance;
            double detectiveChance = detectiveValue != null ? detectiveValue.doubleValue() : defaultDetectiveChance;
            chances.put(uuid, new RoleChance(uuid, murdererChance, detectiveChance));
        }
        for (UUID uuid : uuids) {
            chances.putIfAbsent(uuid, new RoleChance(uuid, defaultMurdererChance, defaultDetectiveChance));
        }
        return chances;
    }

    public void save(Collection<RoleChance> chances) {
        if (chances == null || chances.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> writes = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (RoleChance chance : chances) {
            Document update = new Document("uuid", chance.getUuid().toString())
                    .append(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_ROLE_CHANCE_RECORD_TYPE)
                    .append("murdererChance", chance.getMurdererChance())
                    .append("detectiveChance", chance.getDetectiveChance())
                    .append("updatedAt", now);
            writes.add(new UpdateOneModel<>(
                    and(eq("uuid", chance.getUuid().toString()), eq(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_ROLE_CHANCE_RECORD_TYPE)),
                    new Document("$set", update),
                    new UpdateOptions().upsert(true)));
        }
        collection.bulkWrite(writes);
    }

    public void ensureDefault(UUID uuid, double defaultMurdererChance, double defaultDetectiveChance) {
        if (uuid == null) {
            return;
        }
        ensureDefaults(Collections.singletonList(uuid), defaultMurdererChance, defaultDetectiveChance);
    }

    public void ensureDefaults(Collection<UUID> uuids, double defaultMurdererChance, double defaultDetectiveChance) {
        if (uuids == null || uuids.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> writes = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (UUID uuid : uuids) {
            if (uuid == null) {
                continue;
            }
            Document defaults = new Document("uuid", uuid.toString())
                    .append(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_ROLE_CHANCE_RECORD_TYPE)
                    .append("murdererChance", defaultMurdererChance)
                    .append("detectiveChance", defaultDetectiveChance)
                    .append("createdAt", now)
                    .append("updatedAt", now);
            writes.add(new UpdateOneModel<>(
                    and(eq("uuid", uuid.toString()), eq(MongoManager.MURDER_MYSTERY_RECORD_TYPE_FIELD, MongoManager.MURDER_MYSTERY_ROLE_CHANCE_RECORD_TYPE)),
                    new Document("$setOnInsert", defaults),
                    new UpdateOptions().upsert(true)));
        }
        if (!writes.isEmpty()) {
            collection.bulkWrite(writes);
        }
    }
}
