package io.github.mebsic.core.listener;

import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import io.github.mebsic.core.menu.MenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class MenuListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory == null || !(inventory.getHolder() instanceof MenuHolder)) {
            return;
        }
        Menu menu = ((MenuHolder) inventory.getHolder()).getMenu();
        if (menu == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= inventory.getSize()) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        MenuClick click = new MenuClick((Player) event.getWhoClicked(), inventory, item, event.getRawSlot(), event.getClick());
        if (!menu.isAllowedClick(click)) {
            return;
        }
        if (item == null) {
            return;
        }
        menu.onClick(click);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory == null || !(inventory.getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
    }
}
