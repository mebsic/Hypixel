package io.github.mebsic.proxy.command;

import io.github.mebsic.core.util.CommonMessages;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.proxy.cache.MotdCache;
import io.github.mebsic.proxy.config.ProxyConfig;
import io.github.mebsic.proxy.service.RankResolver;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bson.Document;

public class MaintenanceCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final Object plugin;
    private final ProxyConfig config;
    private final MongoDatabase database;
    private final MotdCache motdCache;
    private final RankResolver rankResolver;

    public MaintenanceCommand(ProxyServer proxy,
                              Object plugin,
                              ProxyConfig config,
                              MongoDatabase database,
                              MotdCache motdCache,
                              RankResolver rankResolver) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.motdCache = motdCache;
        this.rankResolver = rankResolver;
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.source() instanceof Player) {
            Player player = (Player) invocation.source();
            if (rankResolver == null || !rankResolver.isStaff(player.getUniqueId())) {
                player.sendMessage(Component.text(CommonMessages.NO_PERMISSION_COMMAND, NamedTextColor.RED));
                return;
            }
        }
        if (database == null) {
            invocation.source().sendMessage(Component.text("MongoDB is not configured.", NamedTextColor.RED));
            return;
        }
        MongoCollection<Document> collection = database.getCollection(config.getMotdCollection());
        Document query = new Document("_id", config.getMotdDocumentId());
        Document existing = collection.find(query).first();
        boolean enabled = existing != null && Boolean.TRUE.equals(existing.getBoolean("maintenanceEnabled"));
        boolean next = !enabled;
        collection.updateOne(query,
                new Document("$set", new Document("maintenanceEnabled", next)),
                new UpdateOptions().upsert(true));
        if (motdCache != null && plugin != null) {
            proxy.getScheduler().buildTask(plugin, () -> {
                if (motdCache.startRefresh()) {
                    try {
                        motdCache.refresh();
                    } finally {
                        motdCache.finishRefresh();
                    }
                }
            }).schedule();
        } else if (motdCache != null) {
            if (motdCache.startRefresh()) {
                try {
                    motdCache.refresh();
                } finally {
                    motdCache.finishRefresh();
                }
            }
        }
        if (next) {
            kickNonStaffPlayers();
        }
        invocation.source().sendMessage(Component.text(
                next ? "Maintenance mode is now enabled!" : "Maintenance mode is now disabled!",
                next ? NamedTextColor.GOLD : NamedTextColor.RED));
    }

    private void kickNonStaffPlayers() {
        Component reason = Component.text("Maintenance mode is now enabled!", NamedTextColor.GOLD);
        for (Player online : proxy.getAllPlayers()) {
            if (online == null) {
                continue;
            }
            if (rankResolver != null && rankResolver.isStaff(online.getUniqueId())) {
                continue;
            }
            online.disconnect(reason);
        }
    }
}
