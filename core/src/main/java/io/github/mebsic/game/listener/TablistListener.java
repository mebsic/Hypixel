package io.github.mebsic.game.listener;

import io.github.mebsic.game.service.TablistService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public class TablistListener implements Listener {
    private final Plugin plugin;
    private final TablistService tablist;

    public TablistListener(Plugin plugin, TablistService tablist) {
        this.plugin = plugin;
        this.tablist = tablist;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, tablist::updateAll, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tablist.updateAll();
    }
}
