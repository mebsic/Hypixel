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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BuildEditLocationsMenu extends Menu {
    private static final int SIZE = 54;
    private static final int PAGE_SIZE = 21;
    private static final int[] LOCATION_SLOTS = new int[] {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int EMPTY_SLOT = 22;
    private static final int MAIN_MENU_PAGE_ONE_SLOT = 45;
    private static final int MAIN_MENU_PAGED_SLOT = 48;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final BuildMapConfigService mapConfigService;
    private final ServerType gameType;
    private final String worldDirectory;
    private final Menu parentMenu;
    private final int page;

    public BuildEditLocationsMenu(BuildMapConfigService mapConfigService,
                                  ServerType gameType,
                                  String worldDirectory,
                                  Menu parentMenu,
                                  int page) {
        super(resolveTitle(worldDirectory, gameType), SIZE);
        this.mapConfigService = mapConfigService;
        this.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
        this.worldDirectory = safe(worldDirectory);
        this.parentMenu = parentMenu;
        this.page = Math.max(1, page);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        PagedView view = viewForPage();
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
        set(inventory, mainMenuSlot(view), mainMenuItem());
        boolean paginated = view.totalPages > 1;
        if (paginated && view.page > 1) {
            set(inventory, PREVIOUS_PAGE_SLOT, paginationItem(true, ChatColor.GREEN + "Page " + (view.page - 1)));
        }
        if (paginated && view.page < view.totalPages) {
            set(inventory, NEXT_SLOT, paginationItem(false, ChatColor.GREEN + "Page " + (view.page + 1)));
        }
        if (view.entries.isEmpty()) {
            set(inventory, EMPTY_SLOT, item(
                    Material.PAPER,
                    ChatColor.RED + "No Locations",
                    ChatColor.GRAY + "There are no player spawns or item drops yet."
            ));
            return;
        }
        for (int i = 0; i < LOCATION_SLOTS.length; i++) {
            int index = view.start + i;
            if (index < 0 || index >= view.entries.size()) {
                break;
            }
            BuildMapConfigService.MapLocationEntry entry = view.entries.get(index);
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.DARK_GRAY + "(" + entry.coordinates(mapConfigService) + ")");
            if (entry.getType() == BuildMapConfigService.MapLocationType.ITEM_DROP) {
                String itemName = entry.getItemName() == null ? "" : entry.getItemName().trim();
                if (!itemName.isEmpty()) {
                    lore.add(ChatColor.GOLD + itemName);
                }
            } else if (entry.getType() == BuildMapConfigService.MapLocationType.LEADERBOARD) {
                String metric = entry.getItemName() == null ? "" : entry.getItemName().trim();
                if (!metric.isEmpty()) {
                    lore.add(ChatColor.GOLD + "Leaderboard Type: " + leaderboardTypeLabel(metric));
                }
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to teleport!");
            lore.add(ChatColor.DARK_GRAY + "Right-click to delete!");
            set(inventory, LOCATION_SLOTS[i], buildLocationItem(entry, lore));
        }
        // Re-apply footer controls so they always stay visible in submenus.
        set(inventory, mainMenuSlot(view), mainMenuItem());
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
        PagedView view = viewForPage();
        int slot = click.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == mainMenuSlot(view)) {
            openMainMenu(player);
            return;
        }
        if (slot == PREVIOUS_PAGE_SLOT && view.page > 1) {
            openPage(player, view.page - 1);
            return;
        }
        if (slot == NEXT_SLOT) {
            if (view.page < view.totalPages) {
                openPage(player, view.page + 1);
            }
            return;
        }
        int indexOnPage = slotIndex(slot);
        if (indexOnPage < 0) {
            return;
        }
        int absoluteIndex = view.start + indexOnPage;
        if (absoluteIndex < 0 || absoluteIndex >= view.entries.size()) {
            return;
        }
        BuildMapConfigService.MapLocationEntry entry = view.entries.get(absoluteIndex);
        if (click.isRightClick()) {
            boolean removed = mapConfigService != null
                    && mapConfigService.deleteMapLocationFromMenu(player, gameType, worldDirectory, entry);
            if (!removed) {
                player.sendMessage(ChatColor.RED + "That location is no longer available!");
            }
            if (removed && mapConfigService != null) {
                List<BuildMapConfigService.MapLocationEntry> remaining = mapConfigService.loadMapLocations(gameType, worldDirectory);
                if (remaining == null || remaining.isEmpty()) {
                    openMainMenu(player);
                    return;
                }
            }
            openPage(player, view.page);
            return;
        }
        if (mapConfigService != null) {
            mapConfigService.teleportToMapLocation(player, entry, worldDirectory);
        }
        player.closeInventory();
    }

    private void openPage(Player player, int targetPage) {
        if (player == null) {
            return;
        }
        new BuildEditLocationsMenu(mapConfigService, gameType, worldDirectory, parentMenu, targetPage).open(player);
    }

    private void openMainMenu(Player player) {
        if (player == null) {
            return;
        }
        if (parentMenu != null) {
            parentMenu.open(player);
            return;
        }
        new BuildEditMenu(mapConfigService, gameType, worldDirectory).open(player);
    }

    private int slotIndex(int slot) {
        for (int i = 0; i < LOCATION_SLOTS.length; i++) {
            if (LOCATION_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private PagedView viewForPage() {
        List<BuildMapConfigService.MapLocationEntry> entries;
        if (mapConfigService == null) {
            entries = Collections.emptyList();
        } else {
            entries = mapConfigService.loadMapLocations(gameType, worldDirectory);
        }
        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int resolvedPage = Math.max(1, Math.min(page, totalPages));
        int start = (resolvedPage - 1) * PAGE_SIZE;
        return new PagedView(entries, totalPages, resolvedPage, start);
    }

    private int mainMenuSlot(PagedView view) {
        if (view == null) {
            return MAIN_MENU_PAGE_ONE_SLOT;
        }
        return view.page > 1 ? MAIN_MENU_PAGED_SLOT : MAIN_MENU_PAGE_ONE_SLOT;
    }

    private Material resolveLocationMaterial(BuildMapConfigService.MapLocationEntry entry) {
        if (entry == null) {
            return resolveArmorStandMaterial();
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.ITEM_DROP) {
            return resolveItemDropMaterial(entry);
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.HUB_SPAWN) {
            return resolveHubSpawnMaterial();
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.WAITING_SPAWN) {
            Material redBed = Material.matchMaterial("RED_BED");
            if (redBed != null) {
                return redBed;
            }
            Material bed = Material.matchMaterial("BED");
            if (bed != null) {
                return bed;
            }
            Material clock = Material.matchMaterial("CLOCK");
            if (clock != null) {
                return clock;
            }
            Material watch = Material.matchMaterial("WATCH");
            return watch == null ? Material.PAPER : watch;
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.HUB_NPC
                || entry.getType() == BuildMapConfigService.MapLocationType.PROFILE_NPC) {
            Material head = resolveHeadMaterial();
            if (head != null) {
                return head;
            }
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.HUB_IMAGE_DISPLAY) {
            return Material.MAP;
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.LEADERBOARD) {
            Material sign = Material.matchMaterial("SIGN");
            return sign == null ? Material.PAPER : sign;
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.PARKOUR_START) {
            Material plate = Material.matchMaterial("IRON_PRESSURE_PLATE");
            if (plate != null) {
                return plate;
            }
            Material legacyPlate = Material.matchMaterial("IRON_PLATE");
            return legacyPlate == null ? Material.PAPER : legacyPlate;
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.PARKOUR_END) {
            Material redstoneBlock = Material.matchMaterial("REDSTONE_BLOCK");
            if (redstoneBlock != null) {
                return redstoneBlock;
            }
            Material goldBlock = Material.matchMaterial("GOLD_BLOCK");
            return goldBlock == null ? Material.PAPER : goldBlock;
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.PARKOUR_CHECKPOINT) {
            Material stoneButton = Material.matchMaterial("STONE_BUTTON");
            if (stoneButton != null) {
                return stoneButton;
            }
            Material woodButton = Material.matchMaterial("WOOD_BUTTON");
            return woodButton == null ? Material.PAPER : woodButton;
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.PARKOUR_ROUTE) {
            Material plate = Material.matchMaterial("STONE_PRESSURE_PLATE");
            if (plate != null) {
                return plate;
            }
            Material legacyPlate = Material.matchMaterial("STONE_PLATE");
            return legacyPlate == null ? Material.PAPER : legacyPlate;
        }
        return resolveArmorStandMaterial();
    }

    private Material resolveItemDropMaterial(BuildMapConfigService.MapLocationEntry entry) {
        if (entry != null) {
            String itemName = entry.getItemName() == null ? "" : entry.getItemName().trim();
            if (!itemName.isEmpty()) {
                Material exact = Material.matchMaterial(itemName);
                if (exact != null) {
                    return exact;
                }
                Material normalized = Material.matchMaterial(itemName.toUpperCase(java.util.Locale.ROOT));
                if (normalized != null) {
                    return normalized;
                }
            }
        }
        return resolveDropperMaterial();
    }

    private ItemStack buildLocationItem(BuildMapConfigService.MapLocationEntry entry, List<String> lore) {
        Material material = resolveLocationMaterial(entry);
        ItemStack stack;
        if (entry != null && entry.getType() == BuildMapConfigService.MapLocationType.ITEM_DROP) {
            stack = new ItemStack(material, 1, (short) Math.max(0, entry.getItemData()));
        } else if (entry != null && usesLegacySkull(material)) {
            stack = new ItemStack(material, 1, (short) 3);
        } else {
            stack = new ItemStack(material);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (meta instanceof SkullMeta
                && entry != null
                && (entry.getType() == BuildMapConfigService.MapLocationType.HUB_NPC
                || entry.getType() == BuildMapConfigService.MapLocationType.PROFILE_NPC)) {
            String owner = entry.getItemName() == null ? "" : entry.getItemName().trim();
            if (!owner.isEmpty()) {
                ((SkullMeta) meta).setOwner(owner);
            }
        }
        meta.setDisplayName(ChatColor.GREEN + locationDisplayName(entry));
        if (lore != null && !lore.isEmpty()) {
            List<String> colored = new ArrayList<String>(lore.size());
            for (String line : lore) {
                colored.add(colorize(line));
            }
            meta.setLore(colored);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private String locationDisplayName(BuildMapConfigService.MapLocationEntry entry) {
        if (entry == null) {
            return "Location";
        }
        if (entry.getType() == BuildMapConfigService.MapLocationType.HUB_IMAGE_DISPLAY) {
            return "Information";
        }
        return entry.displayType();
    }

    private Material resolveArmorStandMaterial() {
        Material armorStand = Material.matchMaterial("ARMOR_STAND");
        return armorStand == null ? Material.PAPER : armorStand;
    }

    private Material resolveDropperMaterial() {
        Material dropper = Material.matchMaterial("DROPPER");
        return dropper == null ? Material.PAPER : dropper;
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

    private Material resolveHubSpawnMaterial() {
        Material star = Material.matchMaterial("NETHER_STAR");
        return star == null ? Material.PAPER : star;
    }

    private ItemStack paginationItem(boolean left, String displayName) {
        ItemStack stack = new ItemStack(Material.ARROW, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setDisplayName(colorize(displayName));
        stack.setItemMeta(meta);
        return stack;
    }

    private Material resolveHeadMaterial() {
        Material legacy = Material.matchMaterial("SKULL_ITEM");
        if (legacy != null) {
            return legacy;
        }
        Material modern = Material.matchMaterial("PLAYER_HEAD");
        if (modern != null) {
            return modern;
        }
        return Material.matchMaterial("SKULL");
    }

    private boolean usesLegacySkull(Material material) {
        return material != null && "SKULL_ITEM".equals(material.name());
    }

    private ItemStack mainMenuItem() {
        return item(Material.BOOK, ChatColor.GREEN + "Back to Main Menu");
    }

    private static String resolveTitle(String worldDirectory, ServerType gameType) {
        String world = safe(worldDirectory);
        if (world.isEmpty()) {
            world = "world";
        }
        String title = "Locations for " + world;
        return title.length() <= 32 ? title : title.substring(0, 32);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static final class PagedView {
        private final List<BuildMapConfigService.MapLocationEntry> entries;
        private final int totalPages;
        private final int page;
        private final int start;

        private PagedView(List<BuildMapConfigService.MapLocationEntry> entries,
                          int totalPages,
                          int page,
                          int start) {
            this.entries = entries == null ? Collections.emptyList() : entries;
            this.totalPages = Math.max(1, totalPages);
            this.page = Math.max(1, page);
            this.start = Math.max(0, start);
        }
    }
}
