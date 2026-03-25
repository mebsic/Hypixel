package io.github.mebsic.game.util;

import io.github.mebsic.core.server.ServerType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SpectatorItems {
    public static final int TELEPORTER_SLOT = 0;
    public static final int SETTINGS_SLOT = 4;
    public static final int PLAY_AGAIN_SLOT = 7;

    private static final String TELEPORTER_NAME =
            ChatColor.GREEN.toString() + ChatColor.BOLD  + "Teleporter" + ChatColor.GRAY + " (Right Click)";
    private static final String TELEPORTER_LORE =
            ChatColor.GRAY + "Right-click to spectate players!";

    private static final String SETTINGS_NAME =
            ChatColor.AQUA.toString() + ChatColor.BOLD  + "Spectator Settings" + ChatColor.GRAY + " (Right Click)";
    private static final String SETTINGS_LORE =
            ChatColor.GRAY + "Right-click to change your spectator settings!";

    private static final String PLAY_AGAIN_NAME =
            ChatColor.AQUA.toString() + ChatColor.BOLD + "Play Again" + ChatColor.GRAY + " (Right Click)";
    private static final String PLAY_AGAIN_LORE =
            ChatColor.GRAY + "Right-click to play another game!";

    private SpectatorItems() {
    }

    public static void applyTo(Player player, ServerType gameType) {
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItem(TELEPORTER_SLOT, createTeleporterItem());
        player.getInventory().setItem(SETTINGS_SLOT, createSettingsItem());
        player.getInventory().setItem(PLAY_AGAIN_SLOT, createPlayAgainItem(gameType));
        player.getInventory().setItem(ReturnToLobbyItem.HOTBAR_SLOT, ReturnToLobbyItem.create());
    }

    public static void applyPostGameTo(Player player, ServerType gameType) {
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItem(PLAY_AGAIN_SLOT, createPlayAgainItem(gameType));
        player.getInventory().setItem(ReturnToLobbyItem.HOTBAR_SLOT, ReturnToLobbyItem.create());
    }

    public static boolean isTeleporterItem(ItemStack item) {
        return hasDisplayName(item, resolveCompassMaterial(), TELEPORTER_NAME);
    }

    public static boolean isSettingsItem(ItemStack item) {
        return hasDisplayName(item, resolveRepeaterMaterial(), SETTINGS_NAME);
    }

    public static boolean isPlayAgainItem(ItemStack item) {
        return hasDisplayName(item, Material.PAPER, PLAY_AGAIN_NAME);
    }

    private static ItemStack createTeleporterItem() {
        Material material = resolveCompassMaterial();
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material, 1);
        applyMeta(item, TELEPORTER_NAME, Collections.singletonList(TELEPORTER_LORE));
        return item;
    }

    private static ItemStack createSettingsItem() {
        Material material = resolveRepeaterMaterial();
        ItemStack item = new ItemStack(material == null ? Material.REDSTONE : material, 1);
        applyMeta(item, SETTINGS_NAME, Collections.singletonList(SETTINGS_LORE));
        return item;
    }

    private static ItemStack createPlayAgainItem(ServerType gameType) {
        ItemStack item = new ItemStack(Material.PAPER, 1);
        List<String> lore = new ArrayList<>();
        lore.add(PLAY_AGAIN_LORE);
        String gameName = gameDisplayName(gameType);
        if (!gameName.isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "Mode: " + gameName);
        }
        applyMeta(item, PLAY_AGAIN_NAME, lore);
        return item;
    }

    private static void applyMeta(ItemStack item, String displayName, List<String> lore) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(displayName);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    private static boolean hasDisplayName(ItemStack item, Material expectedType, String expectedName) {
        if (item == null || expectedName == null) {
            return false;
        }
        if (expectedType != null && item.getType() != expectedType) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && expectedName.equals(meta.getDisplayName());
    }

    private static Material resolveCompassMaterial() {
        Material compass = Material.matchMaterial("COMPASS");
        return compass == null ? Material.WATCH : compass;
    }

    private static Material resolveRepeaterMaterial() {
        Material material = Material.matchMaterial("DIODE");
        if (material != null) {
            return material;
        }
        material = Material.matchMaterial("REPEATER");
        if (material != null) {
            return material;
        }
        material = Material.matchMaterial("REDSTONE_REPEATER");
        if (material != null) {
            return material;
        }
        material = Material.matchMaterial("DIODE_BLOCK_OFF");
        return material;
    }

    private static String gameDisplayName(ServerType type) {
        if (type == null || !type.isGame()) {
            return "";
        }
        String raw = type.getGameTypeDisplayName();
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (normalized.isEmpty()) {
            return "";
        }
        String[] parts = normalized.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
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
        return out.toString();
    }
}
