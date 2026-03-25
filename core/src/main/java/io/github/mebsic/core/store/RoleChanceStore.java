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

import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.eq;

public class RoleChanceStore {
    private final MongoCollection<Document> collection;

    public RoleChanceStore(MongoManager mongo) {
        this.collection = mongo.getRoleChances();
    }

    public Map<UUID, RoleChance> load(Collection<UUID> uuids, double defaultPrimaryChance, double defaultSecondaryChance) {
        Map<UUID, RoleChance> chances = new HashMap<>();
        if (uuids == null || uuids.isEmpty()) {
            return chances;
        }
        List<String> ids = new ArrayList<>();
        for (UUID uuid : uuids) {
            ids.add(uuid.toString());
        }
        for (Document doc : collection.find(in("uuid", ids))) {
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
            Number primaryValue = doc.get("primaryChance", Number.class);
            if (primaryValue == null) {
                primaryValue = doc.get("murdererChance", Number.class);
            }
            Number secondaryValue = doc.get("secondaryChance", Number.class);
            if (secondaryValue == null) {
                secondaryValue = doc.get("detectiveChance", Number.class);
            }
            double primaryChance = primaryValue != null ? primaryValue.doubleValue() : defaultPrimaryChance;
            double secondaryChance = secondaryValue != null ? secondaryValue.doubleValue() : defaultSecondaryChance;
            chances.put(uuid, new RoleChance(uuid, primaryChance, secondaryChance));
        }
        for (UUID uuid : uuids) {
            chances.putIfAbsent(uuid, new RoleChance(uuid, defaultPrimaryChance, defaultSecondaryChance));
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
                    .append("primaryChance", chance.getPrimaryChance())
                    .append("secondaryChance", chance.getSecondaryChance())
                    .append("updatedAt", now);
            writes.add(new UpdateOneModel<>(
                    eq("uuid", chance.getUuid().toString()),
                    new Document("$set", update),
                    new UpdateOptions().upsert(true)));
        }
        collection.bulkWrite(writes);
    }

    public void ensureDefault(UUID uuid, double defaultPrimaryChance, double defaultSecondaryChance) {
        if (uuid == null) {
            return;
        }
        ensureDefaults(Collections.singletonList(uuid), defaultPrimaryChance, defaultSecondaryChance);
    }

    public void ensureDefaults(Collection<UUID> uuids, double defaultPrimaryChance, double defaultSecondaryChance) {
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
                    .append("primaryChance", defaultPrimaryChance)
                    .append("secondaryChance", defaultSecondaryChance)
                    .append("murdererChance", defaultPrimaryChance)
                    .append("detectiveChance", defaultSecondaryChance)
                    .append("createdAt", now)
                    .append("updatedAt", now);
            writes.add(new UpdateOneModel<>(
                    eq("uuid", uuid.toString()),
                    new Document("$setOnInsert", defaults),
                    new UpdateOptions().upsert(true)));
        }
        if (!writes.isEmpty()) {
            collection.bulkWrite(writes);
        }
    }
}
