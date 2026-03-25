package io.github.mebsic.hub.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.core.server.ServerIdentityResolver;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.server.ServerTypeResolver;
import org.bson.conversions.Bson;
import org.bson.Document;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

public class ServerRegistryService {
    private static final long STALE_HEARTBEAT_MILLIS = 2 * 60 * 1000L;
    private final JavaPlugin plugin;
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

    public ServerRegistryService(JavaPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        String identity = ServerIdentityResolver.resolveIdentity(config, "lobby1A");
        this.serverId = identity;
        this.type = ServerTypeResolver.resolve(config, ServerType.MURDER_MYSTERY_HUB);
        this.group = config.getString("server.group", "murdermystery");
        this.address = config.getString("server.address", "hub");
        this.port = config.getInt("server.port", 25565);
        this.heartbeatSeconds = Math.max(1, config.getInt("server.heartbeatSeconds", 2));
        this.forcedMaxPlayers = parsePositiveInt(System.getenv("SERVER_MAX_PLAYERS"));
        this.mongoUri = config.getString("mongo.uri", "mongodb://mongo:27017");
        this.mongoDatabase = config.getString("mongo.database", "hypixel");
    }

    public void start() {
        if (serverId == null || serverId.trim().isEmpty()) {
            return;
        }
        this.client = MongoClients.create(mongoUri);
        MongoDatabase db = client.getDatabase(mongoDatabase);
        this.collection = db.getCollection("server_registry");
        sendUpdate("online");
        this.task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                () -> sendUpdate("online"),
                20L,
                heartbeatSeconds * 20L);
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
    }

    private void sendUpdate(String status) {
        if (collection == null) {
            return;
        }
        cleanupStaleRecords();
        int players = plugin.getServer().getOnlinePlayers().size();
        int maxPlayers = plugin.getServer().getMaxPlayers();
        if (forcedMaxPlayers != null) {
            maxPlayers = forcedMaxPlayers;
        }
        List<String> onlinePlayerUuids = new ArrayList<>();
        List<String> onlinePlayerNames = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == null) {
                continue;
            }
            onlinePlayerUuids.add(player.getUniqueId().toString());
            onlinePlayerNames.add(player.getName());
        }
        Document doc = new Document("_id", serverId)
                .append("type", type.getId())
                .append("group", group)
                .append("address", address)
                .append("port", port)
                .append("players", players)
                .append("maxPlayers", maxPlayers)
                .append("onlinePlayerUuids", onlinePlayerUuids)
                .append("onlinePlayerNames", onlinePlayerNames)
                .append("state", "HUB")
                .append("status", status)
                .append("lastHeartbeat", System.currentTimeMillis());

        collection.updateOne(new Document("_id", serverId),
                new Document("$set", doc),
                new UpdateOptions().upsert(true));
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
