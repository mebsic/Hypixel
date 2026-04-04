package io.github.mebsic.game.service;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.core.manager.MongoManager;
import org.bson.conversions.Bson;
import io.github.mebsic.core.server.ServerIdentityResolver;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.server.ServerTypeResolver;
import io.github.mebsic.game.manager.GameManager;
import org.bson.Document;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;

public class ServerRegistryService {
    private static final long STALE_HEARTBEAT_MILLIS = 2 * 60 * 1000L;
    private static final long MONGO_CONNECT_TIMEOUT_SECONDS = 3L;
    private static final long MONGO_SOCKET_TIMEOUT_SECONDS = 5L;
    private static final long MONGO_SERVER_SELECTION_TIMEOUT_SECONDS = 3L;
    private final JavaPlugin plugin;
    private final GameManager gameManager;
    private final boolean mongoEnabled;
    private final String serverId;
    private final ServerType type;
    private final String group;
    private final String address;
    private final int port;
    private final int heartbeatSeconds;
    private final Integer forcedMaxPlayers;
    private final String mongoUri;
    private final String mongoDatabase;
    private MongoClient client;
    private MongoCollection<Document> collection;
    private BukkitTask task;

    public ServerRegistryService(JavaPlugin plugin, FileConfiguration config, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.mongoEnabled = config.getBoolean("mongo.enabled", false);
        String identity = ServerIdentityResolver.resolveIdentity(config, "mini1A");
        this.serverId = identity;
        this.type = ServerTypeResolver.resolve(config, ServerType.MURDER_MYSTERY);
        this.group = config.getString("server.group", MongoManager.MURDER_MYSTERY_COLLECTION);
        this.address = config.getString("server.address", "game");
        this.port = config.getInt("server.port", 25565);
        this.heartbeatSeconds = Math.max(1, config.getInt("server.heartbeatSeconds", 2));
        this.forcedMaxPlayers = parsePositiveInt(System.getenv("SERVER_MAX_PLAYERS"));
        this.mongoUri = config.getString("mongo.uri", "mongodb://mongo:27017");
        this.mongoDatabase = config.getString("mongo.database", "hypixel");
    }

    public void start() {
        if (!mongoEnabled) {
            return;
        }
        if (serverId == null || serverId.trim().isEmpty()) {
            return;
        }
        if (collection != null) {
            return;
        }
        this.client = createMongoClient(mongoUri);
        MongoDatabase db = client.getDatabase(mongoDatabase);
        this.collection = db.getCollection(MongoManager.SERVER_REGISTRY_COLLECTION);
        sendUpdate("online");
        this.task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                () -> sendUpdate("online"),
                20L,
                heartbeatSeconds * 20L
        );
    }

    public void stop() {
        sendUpdate("offline");
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        collection = null;
    }

    private MongoClient createMongoClient(String uri) {
        ConnectionString connectionString = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(MONGO_SERVER_SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .applyToSocketSettings(builder -> {
                    builder.connectTimeout((int) MONGO_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    builder.readTimeout((int) MONGO_SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                })
                .build();
        return MongoClients.create(settings);
    }

    private void sendUpdate(String status) {
        if (collection == null) {
            return;
        }
        cleanupStaleRecords();
        int players = gameManager == null ? plugin.getServer().getOnlinePlayers().size() : gameManager.getPlayerCount();
        int maxPlayers = gameManager == null ? plugin.getServer().getMaxPlayers() : gameManager.getMaxPlayers();
        if (forcedMaxPlayers != null) {
            maxPlayers = forcedMaxPlayers;
        }
        String state = gameManager == null ? "WAITING" : gameManager.getRegistryState();
        Document doc = new Document("_id", serverId)
                .append("type", type.getId())
                .append("group", group)
                .append("address", address)
                .append("port", port)
                .append("players", players)
                .append("maxPlayers", maxPlayers)
                .append("state", state)
                .append("status", status)
                .append("lastHeartbeat", System.currentTimeMillis());

        collection.updateOne(new Document("_id", serverId), new Document("$set", doc), new UpdateOptions().upsert(true));
    }

    private void cleanupStaleRecords() {
        long cutoff = System.currentTimeMillis() - STALE_HEARTBEAT_MILLIS;
        Bson staleFilter = Filters.or(
                Filters.exists("lastHeartbeat", false),
                Filters.lt("lastHeartbeat", cutoff)
        );
        Bson scopedFilter = (group == null || group.trim().isEmpty())
                ? staleFilter
                : Filters.and(Filters.eq("group", group), staleFilter);
        collection.updateMany(scopedFilter, new Document("$set", new Document("status", "offline")));
        collection.deleteMany(scopedFilter);
    }

    private Integer parsePositiveInt(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
