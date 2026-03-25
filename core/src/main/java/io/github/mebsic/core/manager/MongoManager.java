package io.github.mebsic.core.manager;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

public class MongoManager {
    private static final long CONNECT_TIMEOUT_SECONDS = 3L;
    private static final long SOCKET_TIMEOUT_SECONDS = 5L;
    private static final long SERVER_SELECTION_TIMEOUT_SECONDS = 3L;

    private final MongoClient client;
    private final MongoDatabase database;

    public MongoManager(String uri, String databaseName) {
        this.client = createClient(uri);
        this.database = client.getDatabase(databaseName);
    }

    private MongoClient createClient(String uri) {
        ConnectionString connectionString = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(SERVER_SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .applyToSocketSettings(builder -> {
                    builder.connectTimeout((int) CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    builder.readTimeout((int) SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                })
                .build();
        return MongoClients.create(settings);
    }

    public MongoCollection<Document> getProfiles() {
        return database.getCollection("profiles");
    }

    public MongoCollection<Document> getLeaderboards() {
        return database.getCollection("leaderboards");
    }

    public MongoCollection<Document> getPunishments() {
        return database.getCollection("punishments");
    }

    public MongoCollection<Document> getRoleChances() {
        return database.getCollection("murdermystery_role_chances");
    }

    public MongoCollection<Document> getKnifeSkins() {
        return database.getCollection("knife_skins");
    }

    public MongoCollection<Document> getServerRegistry() {
        return database.getCollection("server_registry");
    }

    public MongoCollection<Document> getBossBarMessages() {
        return database.getCollection("boss_bar_messages");
    }

    public MongoCollection<Document> getCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return database.getCollection(name.trim());
    }

    public void ensureCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String target = name.trim();
        for (String existing : database.listCollectionNames()) {
            if (target.equals(existing)) {
                return;
            }
        }
        try {
            database.createCollection(target);
        } catch (MongoCommandException ex) {
            // Another process may create it concurrently.
            if (ex.getErrorCode() != 48) {
                throw ex;
            }
        }
    }

    public void close() {
        client.close();
    }
}
