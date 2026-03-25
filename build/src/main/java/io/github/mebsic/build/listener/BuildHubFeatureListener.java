package io.github.mebsic.build.listener;

import io.github.mebsic.build.service.BuildMapConfigService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

public class BuildHubFeatureListener implements Listener {
    private final BuildMapConfigService mapConfigService;

    public BuildHubFeatureListener(BuildMapConfigService mapConfigService) {
        this.mapConfigService = mapConfigService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcInteract(PlayerInteractAtEntityEvent event) {
        if (event == null || mapConfigService == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!mapConfigService.handleEntityInteract(player, event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcInteractFallback(PlayerInteractEntityEvent event) {
        if (event == null || mapConfigService == null) {
            return;
        }
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        if (!mapConfigService.handleEntityInteract(player, event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
    }
}
