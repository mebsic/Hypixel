package io.github.mebsic.core.util;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.core.manager.MongoManager;
import org.bson.Document;

public final class DomainSettingsStore {
    private DomainSettingsStore() {
    }

    public static void ensureDomainDocument(MongoCollection<Document> collection) {
        if (collection == null) {
            return;
        }
        Document defaults = new Document(MongoManager.PROXY_SETTINGS_DOMAIN_FIELD, NetworkConstants.DEFAULT_DOMAIN);
        collection.updateOne(
                new Document("_id", MongoManager.PROXY_SETTINGS_DOMAIN_DOCUMENT_ID),
                new Document("$setOnInsert", defaults),
                new UpdateOptions().upsert(true)
        );
    }

    public static boolean refreshDomain(MongoCollection<Document> collection) {
        if (collection == null) {
            return false;
        }
        Document domainDoc = collection.find(new Document("_id", MongoManager.PROXY_SETTINGS_DOMAIN_DOCUMENT_ID))
                .projection(new Document(MongoManager.PROXY_SETTINGS_DOMAIN_FIELD, 1))
                .first();
        String resolved = readDomain(domainDoc);
        return NetworkConstants.setDomain(resolved);
    }

    private static String readDomain(Document domainDoc) {
        if (domainDoc == null) {
            return NetworkConstants.DEFAULT_DOMAIN;
        }
        Object raw = domainDoc.get(MongoManager.PROXY_SETTINGS_DOMAIN_FIELD);
        if (raw == null) {
            return NetworkConstants.DEFAULT_DOMAIN;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return NetworkConstants.DEFAULT_DOMAIN;
        }
        return text;
    }
}
