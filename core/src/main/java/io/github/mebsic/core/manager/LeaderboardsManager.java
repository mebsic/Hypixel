package io.github.mebsic.core.manager;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Stats;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LeaderboardsManager {
    private final CorePlugin plugin;
    private final Map<UUID, Stats> cache;
    private BukkitRunnable task;

    public LeaderboardsManager(CorePlugin plugin) {
        this.plugin = plugin;
        this.cache = new ConcurrentHashMap<>();
    }

    public void start() {
        if (plugin.isMongoEnabled() && plugin.getMongoManager() != null) {
            plugin.getMongoManager().ensureCollection("leaderboards");
        }
        int seconds = plugin.getConfig().getInt("leaderboards.updateSeconds", 300);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                snapshot();
            }
        };
        task.runTaskTimerAsynchronously(plugin, 20L, seconds * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    public void update(UUID uuid, Stats stats) {
        cache.put(uuid, stats);
    }

    private void snapshot() {
        if (!plugin.isMongoEnabled() || plugin.getProfileStore() == null) {
            return;
        }
        plugin.getProfileStore().storeLeaderboards(cache);
    }
}
