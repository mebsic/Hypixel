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
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class BuildEditMenu extends Menu {
    private static final int MAX_SIZE = 54;
    private static final int GAME_SIZE = 36;
    private static final int HUB_SIZE = 54;

    private static final int TOP_LEFT_SLOT = 11;
    private static final int TOP_MIDDLE_SLOT = 13;
    private static final int TOP_RIGHT_SLOT = 15;
    private static final int BOTTOM_LEFT_SLOT = 29;
    private static final int BOTTOM_MIDDLE_SLOT = 31;
    private static final int BOTTOM_RIGHT_SLOT = 33;

    private static final int GAME_CLOSE_SLOT = 31;
    private static final int GAME_EXPORT_SLOT = 27;
    private static final int GAME_DELETE_SLOT = 35;

    private static final int HUB_CLOSE_SLOT = 49;
    private static final int HUB_EXPORT_SLOT = 45;
    private static final int HUB_DELETE_SLOT = 53;

    private final BuildMapConfigService mapConfigService;
    private final ServerType gameType;
    private final String worldDirectory;

    public BuildEditMenu(BuildMapConfigService mapConfigService,
                         ServerType gameType,
                         String worldDirectory) {
        super(resolveTitle(worldDirectory, gameType), MAX_SIZE);
        this.mapConfigService = mapConfigService;
        this.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
        this.worldDirectory = safe(worldDirectory);
    }

    @Override
    protected int resolveSize(Player player) {
        return isHubMode() ? HUB_SIZE : GAME_SIZE;
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();

        boolean hasLocations = hasLocations();
        set(inventory, closeSlot(), item(Material.BARRIER, ChatColor.RED + "Close"));
        if (hasLocations) {
            set(inventory, deleteSlot(), deleteMapItem());
        }

        set(inventory, TOP_LEFT_SLOT, primaryActionItem());
        set(inventory, TOP_MIDDLE_SLOT, secondaryActionItem());
        if (hasLocations) {
            set(inventory, TOP_RIGHT_SLOT, locationsItem());
        } else {
            set(inventory, TOP_RIGHT_SLOT, noLocationsItem());
        }
        set(inventory, exportSlot(), exportWorldItem());

        if (isHubMode()) {
            set(inventory, BOTTOM_LEFT_SLOT, parkourItem(player));
            set(inventory, BOTTOM_MIDDLE_SLOT, hubImageDisplayItem());
            set(inventory, BOTTOM_RIGHT_SLOT, leaderboardItem(player));
        }
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

        if (slot == closeSlot()) {
            player.closeInventory();
            return;
        }
        if (slot == deleteSlot()) {
            if (!hasLocations()) {
                return;
            }
            if (mapConfigService != null) {
                mapConfigService.clearMapLocationsFromMenu(player, gameType, worldDirectory);
            }
            player.closeInventory();
            return;
        }
        if (slot == TOP_LEFT_SLOT) {
            if (mapConfigService != null) {
                if (isHubMode()) {
                    mapConfigService.setHubSpawnFromMenu(player, gameType, worldDirectory);
                } else if (click.isRightClick()) {
                    mapConfigService.addPregameSpawnFromMenu(player, gameType, worldDirectory);
                } else {
                    mapConfigService.addSpawnFromMenu(player, gameType, worldDirectory);
                }
            }
            player.closeInventory();
            return;
        }
        if (slot == TOP_MIDDLE_SLOT) {
            if (mapConfigService != null) {
                if (isHubMode()) {
                    new BuildHubNpcMenu(mapConfigService, gameType, worldDirectory, this).open(player);
                    return;
                } else {
                    ItemStack held = player.getItemInHand();
                    Material heldType = held == null ? Material.AIR : held.getType();
                    if (heldType == null || heldType == Material.AIR) {
                        player.sendMessage(ChatColor.RED + "You have to hold an item!");
                        player.closeInventory();
                        return;
                    }
                    mapConfigService.addItemDropFromMenu(player, gameType, worldDirectory, held);
                }
            }
            player.closeInventory();
            return;
        }
        if (slot == TOP_RIGHT_SLOT) {
            if (!hasLocations()) {
                player.closeInventory();
                player.sendMessage(ChatColor.RED + "There are no locations for this map!");
                return;
            }
            new BuildEditLocationsMenu(mapConfigService, gameType, worldDirectory, this, 1).open(player);
            return;
        }
        if (slot == exportSlot()) {
            if (mapConfigService != null) {
                mapConfigService.exportWorldTemplateFromMenu(player, gameType, worldDirectory);
            }
            player.closeInventory();
            return;
        }
        if (!isHubMode()) {
            return;
        }
        if (slot == BOTTOM_LEFT_SLOT) {
            if (mapConfigService != null) {
                mapConfigService.handleParkourSetupFromMenu(player, gameType, worldDirectory, click.isRightClick());
            }
            player.closeInventory();
            return;
        }
        if (slot == BOTTOM_MIDDLE_SLOT) {
            if (mapConfigService != null) {
                mapConfigService.setHubImageDisplayFromMenu(player, gameType, worldDirectory);
            }
            player.closeInventory();
            return;
        }
        if (slot == BOTTOM_RIGHT_SLOT) {
            if (mapConfigService != null) {
                if (click.isRightClick()) {
                    mapConfigService.toggleLeaderboardMetricSelection(player);
                    open(player);
                    return;
                }
                mapConfigService.addLeaderboardHologramFromMenu(player, gameType, worldDirectory);
            }
            player.closeInventory();
        }
    }

    private int closeSlot() {
        return isHubMode() ? HUB_CLOSE_SLOT : GAME_CLOSE_SLOT;
    }

    private int deleteSlot() {
        return isHubMode() ? HUB_DELETE_SLOT : GAME_DELETE_SLOT;
    }

    private int exportSlot() {
        return isHubMode() ? HUB_EXPORT_SLOT : GAME_EXPORT_SLOT;
    }

    private boolean isHubMode() {
        return gameType != null && gameType.isHub();
    }

    private Material resolveArmorStandMaterial() {
        Material armorStand = Material.matchMaterial("ARMOR_STAND");
        return armorStand == null ? Material.PAPER : armorStand;
    }

    private Material resolveDropperMaterial() {
        Material dropper = Material.matchMaterial("DROPPER");
        return dropper == null ? Material.PAPER : dropper;
    }

    private Material resolveHubSpawnMaterial() {
        Material star = Material.matchMaterial("NETHER_STAR");
        return star == null ? Material.PAPER : star;
    }

    private Material resolveSignMaterial() {
        Material sign = Material.matchMaterial("SIGN");
        return sign == null ? Material.PAPER : sign;
    }

    private Material resolveExportMaterial() {
        Material enchantedBook = Material.matchMaterial("ENCHANTED_BOOK");
        if (enchantedBook != null) {
            return enchantedBook;
        }
        return Material.BOOK;
    }

    private ItemStack primaryActionItem() {
        if (isHubMode()) {
            return item(
                    resolveHubSpawnMaterial(),
                    ChatColor.GREEN + "Hub Spawn",
                    ChatColor.GRAY + "This will set the hub spawn.",
                    "",
                    ChatColor.YELLOW + "Click to set!"
            );
        }
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "This will add a new spawn for players.");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to add!");
        if (gameType != null && gameType.isGame()) {
            lore.add(ChatColor.DARK_GRAY + "Right-click to add waiting spawn");
        }
        return item(resolveArmorStandMaterial(), ChatColor.GREEN + "Player Spawn", lore);
    }

    private ItemStack secondaryActionItem() {
        if (isHubMode()) {
            return item(
                    resolveArmorStandMaterial(),
                    ChatColor.GREEN + "Hub NPC Menu",
                    ChatColor.GRAY + "Manage " + hubGameTypeLabel() + " hub NPCs.",
                    "",
                    ChatColor.YELLOW + "Click to view!"
            );
        }
        return item(
                resolveDropperMaterial(),
                ChatColor.GREEN + "Drop Item",
                ChatColor.GRAY + "This will add an item to drop on the ground",
                ChatColor.GRAY + "using the current item you are holding.",
                "",
                ChatColor.YELLOW + "Click to add!"
        );
    }

    private ItemStack locationsItem() {
        return item(
                Material.COMPASS,
                ChatColor.GREEN + "Locations",
                ChatColor.GRAY + "Manage saved map locations for this map.",
                "",
                ChatColor.YELLOW + "Click to view!"
        );
    }

    private ItemStack exportWorldItem() {
        String modeLabel = isHubMode() ? "Hub" : "Game";
        return item(
                resolveExportMaterial(),
                ChatColor.GREEN + "Save Map",
                ChatColor.GRAY + "This will save the world as a " + modeLabel + " map.",
                "",
                ChatColor.YELLOW + "Click to save!"
        );
    }

    private ItemStack parkourItem(Player player) {
        Material plate = Material.matchMaterial("IRON_PRESSURE_PLATE");
        if (plate == null) {
            plate = Material.matchMaterial("IRON_PLATE");
        }
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "This will add the parkour challenge");
        lore.add(ChatColor.GRAY + "start/end with checkpoints.");
        lore.add("");
        if (hasParkourCourseConfigured(player)) {
            lore.add(ChatColor.YELLOW + "Click to reset!");
        } else {
            lore.add(ChatColor.YELLOW + "Click to add!");
            lore.add(ChatColor.DARK_GRAY + "Right-click to add a checkpoint!");
        }
        return item(
                plate == null ? Material.PAPER : plate,
                ChatColor.GREEN + "Parkour",
                lore
        );
    }

    private ItemStack leaderboardItem(Player player) {
        String metric = "kills";
        if (mapConfigService != null) {
            metric = mapConfigService.getLeaderboardMetricSelection(player);
        }
        String gameLabel = hubGameTypeLabel();
        return item(
                resolveSignMaterial(),
                ChatColor.GREEN + "Leaderboards",
                ChatColor.GRAY + "This will add a Top 10 Leaderboard",
                ChatColor.GRAY + "hologram for " + gameLabel + ".",
                "",
                ChatColor.GOLD + "Leaderboard Type: " + leaderboardTypeLabel(metric),
                "",
                ChatColor.YELLOW + "Click to add!",
                ChatColor.DARK_GRAY + "Right-click to change leaderboard type!"
        );
    }

    private ItemStack hubImageDisplayItem() {
        return item(
                Material.MAP,
                ChatColor.GREEN + "Information",
                ChatColor.GRAY + "This will add an image which",
                ChatColor.GRAY + "displays in the hub.",
                "",
                ChatColor.YELLOW + "Click to add!"
        );
    }

    private String leaderboardTypeLabel(String metric) {
        String normalized = metric == null ? "" : metric.trim().toLowerCase(java.util.Locale.ROOT);
        if ("wins_as_murderer".equals(normalized)
                || "murderer_wins".equals(normalized)
                || "murdererwins".equals(normalized)
                || "winsasmurderer".equals(normalized)
                || "kills_as_murderer".equals(normalized)
                || "murderer_kills".equals(normalized)
                || "murdererkills".equals(normalized)
                || "killsasmurderer".equals(normalized)) {
            return "Wins as Murderer";
        }
        if ("wins".equals(normalized)) {
            return "Wins";
        }
        return "Kills";
    }

    private String hubGameTypeLabel() {
        if (gameType == null) {
            return "Game";
        }
        String label = safe(gameType.getGameTypeDisplayName());
        if (label.isEmpty()) {
            label = safe(gameType.name());
        }
        if (label.isEmpty()) {
            return "Game";
        }
        label = label.replace('_', ' ').trim();
        if (label.isEmpty()) {
            return "Game";
        }
        String[] words = label.split("\\s+");
        StringBuilder normalized = new StringBuilder();
        for (String word : words) {
            String part = safe(word);
            if (part.isEmpty()) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append(' ');
            }
            String lower = part.toLowerCase(Locale.ROOT);
            normalized.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                normalized.append(lower.substring(1));
            }
        }
        return normalized.length() == 0 ? "Game" : normalized.toString();
    }

    private ItemStack noLocationsItem() {
        Material redTerracotta = Material.matchMaterial("RED_TERRACOTTA");
        if (redTerracotta != null) {
            return item(
                    redTerracotta,
                    ChatColor.RED + "No Locations",
                    ChatColor.GRAY + "To manage saved map locations,",
                    ChatColor.GRAY + "create them first!"
            );
        }

        ItemStack legacy = new ItemStack(Material.STAINED_CLAY, 1, (short) 14);
        ItemMeta meta = legacy.getItemMeta();
        if (meta == null) {
            return legacy;
        }
        meta.setDisplayName(ChatColor.RED + "No Locations");
        List<String> lore = new ArrayList<String>(2);
        lore.add(ChatColor.GRAY + "To manage saved map locations,");
        lore.add(ChatColor.GRAY + "create them first!");
        meta.setLore(lore);
        legacy.setItemMeta(meta);
        return legacy;
    }

    private ItemStack deleteMapItem() {
        Material lavaBucket = Material.matchMaterial("LAVA_BUCKET");
        if (lavaBucket != null) {
            return item(
                    lavaBucket,
                    ChatColor.RED + "Delete All Locations",
                    "",
                    ChatColor.RED.toString() + ChatColor.BOLD + "WARNING",
                    ChatColor.GRAY + "This will delete all saved map locations!",
                    "",
                    ChatColor.GREEN + "Click to delete!"
            );
        }

        ItemStack legacy = new ItemStack(Material.BUCKET);
        ItemMeta meta = legacy.getItemMeta();
        if (meta == null) {
            return legacy;
        }
        meta.setDisplayName(ChatColor.RED + "Delete All Locations");
        List<String> lore = new ArrayList<String>(5);
        lore.add("");
        lore.add(ChatColor.RED.toString() + ChatColor.BOLD + "WARNING");
        lore.add(ChatColor.GRAY + "This will delete all saved map locations!");
        lore.add("");
        lore.add(ChatColor.GREEN + "Click to delete!");
        meta.setLore(lore);
        legacy.setItemMeta(meta);
        return legacy;
    }

    private boolean hasLocations() {
        if (mapConfigService == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return false;
        }
        return !mapConfigService.loadMapLocations(gameType, worldDirectory).isEmpty();
    }

    private boolean hasParkourCourseConfigured(Player player) {
        if (!isHubMode() || mapConfigService == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return false;
        }
        if (mapConfigService.hasParkourCourseConfigured(gameType, worldDirectory)) {
            return true;
        }
        if (player == null || player.getWorld() == null) {
            return false;
        }
        String currentWorld = safe(player.getWorld().getName());
        if (currentWorld.isEmpty() || currentWorld.equalsIgnoreCase(worldDirectory)) {
            return false;
        }
        return mapConfigService.hasParkourCourseConfigured(gameType, currentWorld);
    }

    private static String resolveTitle(String worldDirectory, ServerType gameType) {
        String world = safe(worldDirectory);
        if (world.isEmpty()) {
            world = "world";
        }
        String title = "Editing " + world;
        return title.length() <= 32 ? title : title.substring(0, 32);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
