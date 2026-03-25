package io.github.mebsic.build.listener;

import io.github.mebsic.build.service.BuildAccessService;
import io.github.mebsic.build.service.BuildLobbyRedirectService;
import io.github.mebsic.build.service.BuildMapConfigService;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class BuildPlayerListener implements Listener {
    private static final String JOIN_WORLD_NAME = "world";

    private final JavaPlugin plugin;
    private final BuildAccessService accessService;
    private final BuildLobbyRedirectService lobbyRedirectService;
    private final BuildMapConfigService mapConfigService;

    public BuildPlayerListener(JavaPlugin plugin,
                               BuildAccessService accessService,
                               BuildLobbyRedirectService lobbyRedirectService,
                               BuildMapConfigService mapConfigService) {
        this.plugin = plugin;
        this.accessService = accessService;
        this.lobbyRedirectService = lobbyRedirectService;
        this.mapConfigService = mapConfigService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        teleportToJoinWorld(player);
        if (lobbyRedirectService != null) {
            lobbyRedirectService.validateStaffAccess(player, 0);
        } else if (accessService != null) {
            accessService.applyBuildDefaults(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player == null || !player.isOnline()) {
                return;
            }
            if (lobbyRedirectService != null) {
                lobbyRedirectService.validateStaffAccess(player, 0);
            } else if (accessService != null) {
                accessService.applyBuildDefaults(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        handleDisconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        handleDisconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (event == null || event.getPlayer() == null || accessService == null) {
            return;
        }
        if (!accessService.shouldBlockCoreCommand(event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "That command is disabled on the build server!");
    }

    private void handleDisconnect(Player player) {
        if (player == null) {
            return;
        }
        if (lobbyRedirectService != null) {
            lobbyRedirectService.onQuit(player);
        }
        if (mapConfigService != null) {
            mapConfigService.clearPlayerStateOnDisconnect(player);
        }
    }

    private void teleportToJoinWorld(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        World world = plugin.getServer().getWorld(JOIN_WORLD_NAME);
        if (world == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(world.getSpawnLocation());
            }
        });
    }
}
