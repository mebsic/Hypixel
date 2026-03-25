package io.github.mebsic.game.service;

import io.github.mebsic.game.manager.GameManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public class QueueService {
    private final Plugin plugin;
    private final GameManager gameManager;
    private final Set<UUID> pendingJoins;
    private final Set<UUID> pendingQuits;
    private BukkitTask task;

    public QueueService(Plugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.pendingJoins = new LinkedHashSet<>();
        this.pendingQuits = new LinkedHashSet<>();
    }

    public void start() {
        if (plugin == null || task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flush, 1L, 1L);
    }

    public void stop() {
        flush();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void handleJoin(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pendingQuits.remove(uuid);
        pendingJoins.add(uuid);
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pendingJoins.remove(uuid);
        pendingQuits.remove(uuid);
        gameManager.handleQuit(player);
    }

    public void flush() {
        if (gameManager == null) {
            pendingJoins.clear();
            pendingQuits.clear();
            return;
        }

        if (!pendingQuits.isEmpty()) {
            Set<UUID> snapshot = new LinkedHashSet<>(pendingQuits);
            pendingQuits.clear();
            for (UUID uuid : snapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    gameManager.handleQuit(player);
                }
            }
        }

        if (!pendingJoins.isEmpty()) {
            Set<UUID> snapshot = new LinkedHashSet<>(pendingJoins);
            pendingJoins.clear();
            for (UUID uuid : snapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    gameManager.handleJoin(player);
                }
            }
        }
    }
}
