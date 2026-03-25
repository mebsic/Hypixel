package io.github.mebsic.game.menu;

import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import io.github.mebsic.game.manager.GameManager;
import io.github.mebsic.game.model.GamePlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpectatorTeleporterMenu extends Menu {
    private static final int SIZE = 45;
    private static final int CLOSE_SLOT = 40;
    private static final int EMPTY_SLOT = 22;
    private static final String CLOSE_NAME = ChatColor.RED + "Close";
    private static final String EMPTY_NAME = ChatColor.RED + "No Alive Players";
    private static final String EMPTY_LORE = ChatColor.GRAY + "There is nobody to spectate right now.";
    private static final String PLAYER_CLICK_LORE = ChatColor.YELLOW + "Click to spectate!";
    private static final String REPORTING_DISABLED_MESSAGE =
            ChatColor.RED + "Reporting is currently disabled!";
    private static final int[] PLAYER_SLOTS = new int[] {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final GameManager gameManager;
    private final SpectateSelectionHandler selectionHandler;
    private final Map<UUID, Map<Integer, UUID>> views;

    public SpectatorTeleporterMenu(GameManager gameManager, SpectateSelectionHandler selectionHandler) {
        super("Teleporter", SIZE);
        this.gameManager = gameManager;
        this.selectionHandler = selectionHandler;
        this.views = new ConcurrentHashMap<>();
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, CLOSE_NAME));
        if (player == null) {
            return;
        }
        List<Player> alivePlayers = findAlivePlayers();
        Map<Integer, UUID> slots = new HashMap<>();
        int visible = Math.min(PLAYER_SLOTS.length, alivePlayers.size());
        for (int i = 0; i < visible; i++) {
            Player target = alivePlayers.get(i);
            int slot = PLAYER_SLOTS[i];
            slots.put(slot, target.getUniqueId());
            set(inventory, slot, playerHeadItem(target));
        }
        if (visible <= 0) {
            set(inventory, EMPTY_SLOT, item(Material.PAPER, EMPTY_NAME, EMPTY_LORE));
        }
        views.put(player.getUniqueId(), slots);
    }

    @Override
    public void onClick(MenuClick click) {
        Player spectator = click.getPlayer();
        if (spectator == null) {
            return;
        }
        if (click.getRawSlot() == CLOSE_SLOT) {
            views.remove(spectator.getUniqueId());
            spectator.closeInventory();
            return;
        }
        Map<Integer, UUID> slots = views.get(spectator.getUniqueId());
        if (slots == null) {
            return;
        }
        UUID targetId = slots.get(click.getRawSlot());
        if (targetId == null) {
            return;
        }
        if (click.isRightClick()) {
            spectator.sendMessage(REPORTING_DISABLED_MESSAGE);
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (!isAliveTarget(target)) {
            open(spectator);
            return;
        }
        if (selectionHandler != null) {
            selectionHandler.onSpectateSelected(spectator, target);
        } else {
            Location location = target.getLocation();
            if (location != null) {
                spectator.teleport(location);
            }
        }
        views.remove(spectator.getUniqueId());
        spectator.closeInventory();
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        views.remove(player.getUniqueId());
    }

    private List<Player> findAlivePlayers() {
        List<Player> alive = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (isAliveTarget(online)) {
                alive.add(online);
            }
        }
        alive.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return alive;
    }

    private boolean isAliveTarget(Player player) {
        if (player == null || !player.isOnline() || gameManager == null) {
            return false;
        }
        GamePlayer gamePlayer = gameManager.getPlayer(player);
        return gamePlayer != null && gamePlayer.isAlive();
    }

    private ItemStack playerHeadItem(Player target) {
        Material head = resolveHeadMaterial();
        ItemStack stack;
        if (head != null && "SKULL_ITEM".equals(head.name())) {
            stack = new ItemStack(head, 1, (short) 3);
        } else if (head != null) {
            stack = new ItemStack(head, 1);
        } else {
            stack = new ItemStack(Material.PAPER, 1);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        if (meta instanceof SkullMeta) {
            ((SkullMeta) meta).setOwner(target.getName());
        }
        String displayName = target.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = ChatColor.GREEN + target.getName();
        }
        meta.setDisplayName(displayName);
        List<String> lore = new ArrayList<>(1);
        lore.add(PLAYER_CLICK_LORE);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private Material resolveHeadMaterial() {
        Material modern = Material.matchMaterial("PLAYER_HEAD");
        if (modern != null) {
            return modern;
        }
        Material legacy = Material.matchMaterial("SKULL_ITEM");
        if (legacy != null) {
            return legacy;
        }
        return null;
    }

    public interface SpectateSelectionHandler {
        void onSpectateSelected(Player spectator, Player target);
    }
}
