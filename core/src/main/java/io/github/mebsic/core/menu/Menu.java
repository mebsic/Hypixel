package io.github.mebsic.core.menu;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public abstract class Menu {
    private final String title;
    private final int size;

    protected Menu(String title, int size) {
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("Menu title is required.");
        }
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("Menu size must be a multiple of 9 between 9 and 54.");
        }
        String plainTitle = ChatColor.stripColor(title);
        if (plainTitle == null || plainTitle.trim().isEmpty()) {
            throw new IllegalArgumentException("Menu title must contain visible text.");
        }
        this.title = plainTitle;
        this.size = size;
    }

    public final Inventory build(Player player) {
        int resolvedSize = resolveSize(player);
        if (resolvedSize < 9 || resolvedSize > 54 || resolvedSize % 9 != 0) {
            resolvedSize = size;
        }
        Inventory inventory = Bukkit.createInventory(new MenuHolder(this), resolvedSize, title);
        populate(player, inventory);
        return inventory;
    }

    public final void open(Player player) {
        player.openInventory(build(player));
    }

    protected abstract void populate(Player player, Inventory inventory);

    public void onClick(MenuClick click) {
        // Default no-op.
    }

    public boolean isAllowedClick(MenuClick click) {
        return click.isLeftClick() || click.isRightClick();
    }

    public String getTitle() {
        return title;
    }

    public int getSize() {
        return size;
    }

    protected int resolveSize(Player player) {
        return size;
    }

    protected ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (name != null) {
            meta.setDisplayName(colorize(name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<String> colored = new ArrayList<>(lore.size());
            for (String line : lore) {
                colored.add(colorize(line));
            }
            meta.setLore(colored);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    protected ItemStack item(Material material, String name, String... lore) {
        List<String> lines = new ArrayList<>();
        if (lore != null) {
            java.util.Collections.addAll(lines, lore);
        }
        return item(material, name, lines);
    }

    protected void set(Inventory inventory, int slot, ItemStack item) {
        if (inventory == null) {
            return;
        }
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
    }

    protected void fill(Inventory inventory, ItemStack item) {
        if (inventory == null) {
            return;
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
    }

    protected void fillBorder(Inventory inventory, ItemStack item) {
        if (inventory == null) {
            return;
        }
        int size = inventory.getSize();
        int rows = size / 9;
        for (int col = 0; col < 9; col++) {
            set(inventory, col, item);
            set(inventory, (rows - 1) * 9 + col, item);
        }
        for (int row = 1; row < rows - 1; row++) {
            set(inventory, row * 9, item);
            set(inventory, row * 9 + 8, item);
        }
    }

    protected String colorize(String text) {
        return text == null ? null : ChatColor.translateAlternateColorCodes('&', text);
    }
}
