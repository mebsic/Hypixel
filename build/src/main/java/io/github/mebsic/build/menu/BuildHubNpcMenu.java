package io.github.mebsic.build.menu;

import io.github.mebsic.build.service.BuildMapConfigService;
import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

public class BuildHubNpcMenu extends Menu {
    private static final int SIZE = 36;
    private static final int CLICK_TO_PLAY_SLOT = 11;
    private static final int PROFILE_SLOT = 15;
    private static final int BACK_SLOT = 27;
    private static final int CLOSE_SLOT = 31;

    private final BuildMapConfigService mapConfigService;
    private final ServerType gameType;
    private final String worldDirectory;
    private final BuildEditMenu parentMenu;

    public BuildHubNpcMenu(BuildMapConfigService mapConfigService,
                           ServerType gameType,
                           String worldDirectory,
                           BuildEditMenu parentMenu) {
        super("Hub NPC Menu", SIZE);
        this.mapConfigService = mapConfigService;
        this.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
        this.worldDirectory = safe(worldDirectory);
        this.parentMenu = parentMenu;
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();

        set(inventory, CLICK_TO_PLAY_SLOT, clickToPlayItem());
        set(inventory, PROFILE_SLOT, profileNpcItem());
        set(inventory, BACK_SLOT, backItem());
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
        if (slot == BACK_SLOT) {
            if (parentMenu != null) {
                parentMenu.open(player);
            } else {
                new BuildEditMenu(mapConfigService, gameType, worldDirectory).open(player);
            }
            return;
        }
        if (mapConfigService == null) {
            player.sendMessage(ChatColor.RED + "Map config service is unavailable.");
            player.closeInventory();
            return;
        }
        if (slot == CLICK_TO_PLAY_SLOT) {
            mapConfigService.addClickToPlayNpcFromMenu(player, gameType, worldDirectory);
            player.closeInventory();
            return;
        }
        if (slot == PROFILE_SLOT) {
            mapConfigService.addProfileNpcFromMenu(player, gameType, worldDirectory);
            player.closeInventory();
        }
    }

    private ItemStack clickToPlayItem() {
        return item(
                npcMenuIconMaterial(),
                ChatColor.GREEN + "Click to Play NPC",
                ChatColor.GRAY + "This will add the " + gameTypeLabel(),
                ChatColor.GRAY + "Click to Play NPC with a unique skin.",
                "",
                ChatColor.YELLOW + "Click to add!"
        );
    }

    private ItemStack profileNpcItem() {
        return item(
                npcMenuIconMaterial(),
                ChatColor.GREEN + "Profile NPC",
                ChatColor.GRAY + "This will add a Profile NPC that shows",
                ChatColor.GRAY + "player stats for " + gameTypeLabel() + ".",
                "",
                ChatColor.YELLOW + "Click to add!"
        );
    }

    private ItemStack backItem() {
        return item(Material.BOOK, ChatColor.GREEN + "Back to Main Menu");
    }

    private Material npcMenuIconMaterial() {
        Material armorStand = Material.matchMaterial("ARMOR_STAND");
        return armorStand == null ? Material.PAPER : armorStand;
    }

    private String gameTypeLabel() {
        String label = gameType == null ? "" : safe(gameType.getGameTypeDisplayName());
        if (label.isEmpty() && gameType != null) {
            label = safe(gameType.name());
        }
        label = label.replace('_', ' ').trim();
        if (label.isEmpty()) {
            return "Game";
        }
        String[] words = label.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            String part = safe(word).toLowerCase(Locale.ROOT);
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.length() == 0 ? "Game" : out.toString();
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
