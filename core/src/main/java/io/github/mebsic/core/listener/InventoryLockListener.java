package io.github.mebsic.core.listener;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

import java.util.Locale;

public class InventoryLockListener implements Listener {
    private final CorePlugin plugin;
    private final boolean inventoryMoveLocked;

    public InventoryLockListener(CorePlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        ServerType serverType = plugin.getServerType() == null ? ServerType.UNKNOWN : plugin.getServerType();
        String serverName = config.getString("server.id", "");
        this.inventoryMoveLocked = resolveToggle(
                config,
                "gameplay.inventoryMoveLocked",
                serverType,
                serverName == null ? "" : serverName.trim(),
                false
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!inventoryMoveLocked || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (canMoveInventory(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!inventoryMoveLocked || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (canMoveInventory(player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!inventoryMoveLocked) {
            return;
        }
        if (canMoveInventory(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean canMoveInventory(Player player) {
        if (player == null || plugin == null) {
            return false;
        }
        return plugin.isBuildModeActive(player.getUniqueId());
    }

    private boolean resolveToggle(FileConfiguration config,
                                  String path,
                                  ServerType serverType,
                                  String serverName,
                                  boolean fallbackDefault) {
        boolean enabled = config.getBoolean(path + ".defaultEnabled", fallbackDefault);

        if (serverType != null) {
            enabled = override(enabled, readBoolean(config, path + ".byServerType." + serverType.name()));
            if (serverType.isHub()) {
                enabled = override(enabled, readBoolean(config, path + ".byServerType.HUB"));
            } else if (serverType.isGame()) {
                enabled = override(enabled, readBoolean(config, path + ".byServerType.GAME"));
            }
        }

        if (serverName != null && !serverName.isEmpty()) {
            enabled = override(enabled, readBoolean(config, path + ".byServerId." + serverName));
            enabled = override(enabled, readBoolean(config, path + ".byServerId." + serverName.toLowerCase(Locale.ROOT)));
        }

        return enabled;
    }

    private boolean override(boolean current, Boolean override) {
        return override == null ? current : override;
    }

    private Boolean readBoolean(FileConfiguration config, String path) {
        if (config == null || path == null || path.trim().isEmpty()) {
            return null;
        }
        if (!config.isSet(path)) {
            return null;
        }
        Object raw = config.get(path);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw == null) {
            return null;
        }
        String normalized = raw.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
