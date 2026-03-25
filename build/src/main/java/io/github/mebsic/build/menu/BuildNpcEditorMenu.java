package io.github.mebsic.build.menu;

import io.github.mebsic.build.service.BuildMapConfigService;
import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

public class BuildNpcEditorMenu extends Menu {
    private static final int SIZE = 27;
    private static final int CLOSE_SLOT = 26;

    private final BuildMapConfigService mapConfigService;
    private final UUID npcEntityUuid;

    public BuildNpcEditorMenu(BuildMapConfigService mapConfigService, UUID npcEntityUuid) {
        super("NPC Editor", SIZE);
        this.mapConfigService = mapConfigService;
        this.npcEntityUuid = npcEntityUuid;
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();

        set(inventory, 10, headItem("Steve", ChatColor.GREEN + "Steve Skin"));
        set(inventory, 11, headItem("Alex", ChatColor.GREEN + "Alex Skin"));
        set(inventory, 12, headItem("Herobrine", ChatColor.GREEN + "Herobrine Skin"));
        set(inventory, 13, headItem("Zombie", ChatColor.GREEN + "Zombie Skin"));
        set(inventory, 14, headItem("Skeleton", ChatColor.GREEN + "Skeleton Skin"));

        set(inventory, 19, colorItem(ChatColor.RED, "Red Hologram"));
        set(inventory, 20, colorItem(ChatColor.GOLD, "Gold Hologram"));
        set(inventory, 21, colorItem(ChatColor.GREEN, "Green Hologram"));
        set(inventory, 22, colorItem(ChatColor.AQUA, "Aqua Hologram"));
        set(inventory, 23, colorItem(ChatColor.BLUE, "Blue Hologram"));
        set(inventory, 24, colorItem(ChatColor.LIGHT_PURPLE, "Purple Hologram"));

        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null) {
            return;
        }
        Player player = click.getPlayer();
        if (player == null) {
            return;
        }
        int slot = click.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (mapConfigService == null || npcEntityUuid == null) {
            player.sendMessage(ChatColor.RED + "That NPC is no longer available.");
            player.closeInventory();
            return;
        }
        if (slot == 10) {
            mapConfigService.updateNpcSkinFromMenu(player, npcEntityUuid, "Steve");
            open(player);
            return;
        }
        if (slot == 11) {
            mapConfigService.updateNpcSkinFromMenu(player, npcEntityUuid, "Alex");
            open(player);
            return;
        }
        if (slot == 12) {
            mapConfigService.updateNpcSkinFromMenu(player, npcEntityUuid, "Herobrine");
            open(player);
            return;
        }
        if (slot == 13) {
            mapConfigService.updateNpcSkinFromMenu(player, npcEntityUuid, "Zombie");
            open(player);
            return;
        }
        if (slot == 14) {
            mapConfigService.updateNpcSkinFromMenu(player, npcEntityUuid, "Skeleton");
            open(player);
            return;
        }
        if (slot == 19) {
            mapConfigService.updateNpcHologramColorFromMenu(player, npcEntityUuid, ChatColor.RED);
            open(player);
            return;
        }
        if (slot == 20) {
            mapConfigService.updateNpcHologramColorFromMenu(player, npcEntityUuid, ChatColor.GOLD);
            open(player);
            return;
        }
        if (slot == 21) {
            mapConfigService.updateNpcHologramColorFromMenu(player, npcEntityUuid, ChatColor.GREEN);
            open(player);
            return;
        }
        if (slot == 22) {
            mapConfigService.updateNpcHologramColorFromMenu(player, npcEntityUuid, ChatColor.AQUA);
            open(player);
            return;
        }
        if (slot == 23) {
            mapConfigService.updateNpcHologramColorFromMenu(player, npcEntityUuid, ChatColor.BLUE);
            open(player);
            return;
        }
        if (slot == 24) {
            mapConfigService.updateNpcHologramColorFromMenu(player, npcEntityUuid, ChatColor.LIGHT_PURPLE);
            open(player);
        }
    }

    private ItemStack colorItem(ChatColor color, String text) {
        Material wool = Material.matchMaterial("WOOL");
        if (wool != null) {
            short data = woolData(color);
            ItemStack item = new ItemStack(wool, 1, data);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(color + text);
                item.setItemMeta(meta);
            }
            return item;
        }
        return item(Material.PAPER, color + text);
    }

    private short woolData(ChatColor color) {
        if (color == ChatColor.RED) {
            return 14;
        }
        if (color == ChatColor.GOLD) {
            return 1;
        }
        if (color == ChatColor.GREEN) {
            return 5;
        }
        if (color == ChatColor.AQUA) {
            return 3;
        }
        if (color == ChatColor.BLUE) {
            return 11;
        }
        if (color == ChatColor.LIGHT_PURPLE) {
            return 2;
        }
        return 0;
    }

    private ItemStack headItem(String owner, String name) {
        Material head = resolveHeadMaterial();
        if (head == null) {
            return item(Material.PAPER, name);
        }
        ItemStack item;
        if ("SKULL_ITEM".equals(head.name())) {
            item = new ItemStack(head, 1, (short) 3);
        } else {
            item = new ItemStack(head, 1);
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(name);
        if (meta instanceof SkullMeta && owner != null && !owner.trim().isEmpty()) {
            ((SkullMeta) meta).setOwner(owner);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Material resolveHeadMaterial() {
        Material modern = Material.matchMaterial("PLAYER_HEAD");
        if (modern != null) {
            return modern;
        }
        return Material.matchMaterial("SKULL_ITEM");
    }
}
