package io.github.mebsic.core.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class MenuClick {
    private final Player player;
    private final Inventory inventory;
    private final ItemStack item;
    private final int rawSlot;
    private final ClickType clickType;

    public MenuClick(Player player, Inventory inventory, ItemStack item, int rawSlot, ClickType clickType) {
        this.player = player;
        this.inventory = inventory;
        this.item = item;
        this.rawSlot = rawSlot;
        this.clickType = clickType;
    }

    public Player getPlayer() {
        return player;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public ItemStack getItem() {
        return item;
    }

    public int getRawSlot() {
        return rawSlot;
    }

    public boolean isLeftClick() {
        return clickType == ClickType.LEFT || clickType == ClickType.SHIFT_LEFT;
    }

    public boolean isRightClick() {
        return clickType == ClickType.RIGHT || clickType == ClickType.SHIFT_RIGHT;
    }

    public boolean isShiftClick() {
        return clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT;
    }
}
