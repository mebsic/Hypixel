package io.github.mebsic.core.menu;

import io.github.mebsic.core.service.NewsEditService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;

public class EditMessageColorMenu extends Menu {
    private static final ChatColor[] SUPPORTED_COLORS = new ChatColor[] {
            ChatColor.AQUA,
            ChatColor.BLACK,
            ChatColor.BLUE,
            ChatColor.GOLD,
            ChatColor.GRAY,
            ChatColor.GREEN,
            ChatColor.LIGHT_PURPLE,
            ChatColor.RED,
            ChatColor.WHITE,
            ChatColor.YELLOW,
            ChatColor.DARK_AQUA,
            ChatColor.DARK_BLUE,
            ChatColor.DARK_GRAY,
            ChatColor.DARK_GREEN,
            ChatColor.DARK_PURPLE,
            ChatColor.DARK_RED
    };
    private static final int SIZE = 36;
    private static final int SWEEP_START_COLOR_SLOT = 11;
    private static final int MIDDLE_COLOR_SLOT = 13;
    private static final int SWEEP_END_COLOR_SLOT = 15;
    private static final int FLASH_START_COLOR_SLOT = 11;
    private static final int FLASH_COLOR_SLOT = 13;
    private static final int FLASH_END_COLOR_SLOT = 15;
    private static final int BACK_SLOT = 31;

    private final NewsEditService newsEditService;

    public EditMessageColorMenu(NewsEditService newsEditService) {
        super("Edit Message Color", SIZE);
        this.newsEditService = newsEditService;
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        NewsEditService.EditSessionSnapshot snapshot = newsEditService == null
                ? null
                : newsEditService.getEditSessionSnapshot(player);
        NewsEditService.MessageType type = resolveType(snapshot);
        if (type == NewsEditService.MessageType.SWEEP) {
            set(inventory, SWEEP_START_COLOR_SLOT, colorItem("Start Color", snapshot == null ? ChatColor.WHITE : snapshot.getStartColor()));
            set(inventory, MIDDLE_COLOR_SLOT, colorItem("Sweep Color", snapshot == null ? ChatColor.WHITE : snapshot.getSweepColor()));
            set(inventory, SWEEP_END_COLOR_SLOT, colorItem("End Color", snapshot == null ? ChatColor.WHITE : snapshot.getEndColor()));
        } else {
            set(inventory, FLASH_START_COLOR_SLOT, colorItem("Start Color", snapshot == null ? ChatColor.WHITE : snapshot.getStartColor()));
            set(inventory, FLASH_COLOR_SLOT, colorItem("Flash Color", snapshot == null ? ChatColor.WHITE : snapshot.getSweepColor()));
            set(inventory, FLASH_END_COLOR_SLOT, colorItem("End Color", snapshot == null ? ChatColor.WHITE : snapshot.getEndColor()));
        }
        set(inventory, BACK_SLOT, item(Material.ARROW, ChatColor.GREEN + "Go Back"));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player player = click.getPlayer();
        if (newsEditService == null) {
            player.closeInventory();
            return;
        }
        int slot = click.getRawSlot();
        if (slot == BACK_SLOT) {
            newsEditService.openEditMenu(player);
            return;
        }
        NewsEditService.EditSessionSnapshot snapshot = newsEditService.getEditSessionSnapshot(player);
        NewsEditService.MessageType type = resolveType(snapshot);
        if (type == NewsEditService.MessageType.SWEEP) {
            if (slot == SWEEP_START_COLOR_SLOT) {
                newsEditService.cycleColor(player, NewsEditService.ColorSlot.START, click.isRightClick());
                open(player);
                return;
            }
            if (slot == MIDDLE_COLOR_SLOT) {
                newsEditService.cycleColor(player, NewsEditService.ColorSlot.SWEEP, click.isRightClick());
                open(player);
                return;
            }
            if (slot == SWEEP_END_COLOR_SLOT) {
                newsEditService.cycleColor(player, NewsEditService.ColorSlot.END, click.isRightClick());
                open(player);
            }
            return;
        }
        if (slot == FLASH_START_COLOR_SLOT) {
            newsEditService.cycleColor(player, NewsEditService.ColorSlot.START, click.isRightClick());
            open(player);
            return;
        }
        if (slot == FLASH_COLOR_SLOT) {
            newsEditService.cycleColor(player, NewsEditService.ColorSlot.SWEEP, click.isRightClick());
            open(player);
            return;
        }
        if (slot == FLASH_END_COLOR_SLOT) {
            newsEditService.cycleColor(player, NewsEditService.ColorSlot.END, click.isRightClick());
            open(player);
        }
    }

    private NewsEditService.MessageType resolveType(NewsEditService.EditSessionSnapshot snapshot) {
        if (snapshot == null || snapshot.getMessageType() == null) {
            return NewsEditService.MessageType.FLASH;
        }
        return snapshot.getMessageType();
    }

    private org.bukkit.inventory.ItemStack colorItem(String label, ChatColor color) {
        ChatColor resolved = color == null ? ChatColor.WHITE : color;
        List<String> lore = new ArrayList<String>();
        for (ChatColor supported : SUPPORTED_COLORS) {
            lore.add(colorLine(supported, supported == resolved));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click for next color!");
        lore.add(ChatColor.DARK_GRAY + "Right-click for previous color!");
        return item(
                Material.SIGN,
                ChatColor.GREEN + label,
                lore
        );
    }

    private String colorLine(ChatColor color, boolean selected) {
        ChatColor resolved = color == null ? ChatColor.WHITE : color;
        String prefix = selected
                ? ChatColor.GRAY + "➜ "
                : ChatColor.DARK_GRAY + "   ";
        ChatColor loreColor = resolved == ChatColor.BLACK ? ChatColor.GRAY : resolved;
        return prefix + loreColor + "&" + loreColor.getChar() + " " + colorName(resolved);
    }

    private String colorName(ChatColor color) {
        if (color == null) {
            return "WHITE";
        }
        String normalized = color.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        if (normalized.isEmpty()) {
            return "WHITE";
        }
        String[] words = normalized.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1));
            }
        }
        return out.length() == 0 ? "WHITE" : out.toString();
    }
}
