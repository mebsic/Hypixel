package io.github.mebsic.game.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;

public final class ReturnToLobbyItem {
    public static final int HOTBAR_SLOT = 8;

    private static final String DISPLAY_NAME =
            ChatColor.RED.toString() + ChatColor.BOLD + "Return to Lobby"
                    + ChatColor.GRAY + " (Right Click)";
    private static final String LORE_LINE = ChatColor.GRAY + "Right-click to leave to the lobby!";

    private ReturnToLobbyItem() {
    }

    public static ItemStack create() {
        Material material = resolveBedMaterial();
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DISPLAY_NAME);
            meta.setLore(Collections.singletonList(LORE_LINE));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isReturnToLobbyItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        Material bed = resolveBedMaterial();
        if (bed != null && item.getType() != bed) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && DISPLAY_NAME.equals(meta.getDisplayName());
    }

    private static Material resolveBedMaterial() {
        Material bed = Material.matchMaterial("BED");
        if (bed != null) {
            return bed;
        }
        Material bedItem = Material.matchMaterial("BED_ITEM");
        if (bedItem != null) {
            return bedItem;
        }
        return Material.matchMaterial("RED_BED");
    }
}
