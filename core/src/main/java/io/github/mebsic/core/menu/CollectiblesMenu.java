package io.github.mebsic.core.menu;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.NumberFormat;
import java.util.Locale;

public class CollectiblesMenu extends Menu {
    public static final String TITLE = "Collectibles";
    private static final int SIZE = 36;
    private static final int RANKS_SLOT = 13;
    private static final int COLLECTIBLES_SLOT = 31;

    private final CoreApi coreApi;
    private final CollectiblesRanksMenu ranksMenu;
    private final NumberFormat numberFormat;

    public CollectiblesMenu(CoreApi coreApi) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
        this.ranksMenu = new CollectiblesRanksMenu(coreApi, this);
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, RANKS_SLOT, item(
                resolveSignMaterial(),
                ChatColor.GREEN + "Ranks",
                ranksLore(player)
        ));
        set(inventory, COLLECTIBLES_SLOT, item(
                Material.CHEST,
                ChatColor.GREEN + "Collectibles",
                collectiblesLore(formatMysteryDust(player))
        ));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        if (click.getRawSlot() == RANKS_SLOT) {
            ranksMenu.open(click.getPlayer());
        }
    }

    private java.util.List<String> ranksLore(Player player) {
        java.util.List<String> lore = new java.util.ArrayList<String>();
        int total = resolveRankTotal();
        int unlocked = resolveRankUnlocked(player);
        int percent = total <= 0 ? 0 : (unlocked * 100) / total;
        lore.add(ChatColor.GRAY + "You can select a rank and change it here!");
        lore.add(ChatColor.GRAY + "Ranks display in lobbies and games.");
        lore.add("");
        lore.add(ChatColor.GRAY + "Unlocked: " + ChatColor.RED + unlocked + "/" + total + " "
                + ChatColor.DARK_GRAY + "(" + percent + "%)");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to browse!");
        return lore;
    }

    public static java.util.List<String> collectiblesLore(String mysteryDust) {
        java.util.List<String> lore = new java.util.ArrayList<String>();
        lore.add(ChatColor.GRAY + "Mystery Dust: " + ChatColor.AQUA + safeAmount(mysteryDust));
        lore.add("");
        lore.add(ChatColor.GRAY + "Collect fun cosmetic items! Unlock new items");
        lore.add(ChatColor.GRAY + "using " + ChatColor.AQUA + "Mystery Dust"
                + ChatColor.GRAY + " or hitting milestone");
        lore.add(ChatColor.GRAY + "rewards.");
        lore.add("");
        lore.add(ChatColor.AQUA + "Mystery Dust" + ChatColor.GRAY + " is randomly given after playing");
        lore.add(ChatColor.GRAY + "games.");
        return lore;
    }

    private String formatMysteryDust(Player player) {
        if (player == null || coreApi == null) {
            return "0";
        }
        return numberFormat.format(Math.max(0, coreApi.getMysteryDust(player.getUniqueId())));
    }

    private int resolveRankTotal() {
        return coreApi == null ? 5 : Math.max(0, coreApi.getAvailableCosmetics(CosmeticType.RANK).size());
    }

    private int resolveRankUnlocked(Player player) {
        if (player == null || coreApi == null) {
            return 0;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            return 0;
        }
        int count = 0;
        for (String option : coreApi.getAvailableCosmetics(CosmeticType.RANK)) {
            if (CollectiblesRankSupport.isUnlocked(profile, option)) {
                count++;
            }
        }
        return count;
    }

    private Material resolveSignMaterial() {
        Material modern = Material.matchMaterial("OAK_SIGN");
        if (modern != null) {
            return modern;
        }
        Material legacy = Material.matchMaterial("SIGN");
        if (legacy != null) {
            return legacy;
        }
        Material legacyItem = Material.matchMaterial("SIGN_ITEM");
        return legacyItem == null ? Material.PAPER : legacyItem;
    }

    private static String safeAmount(String mysteryDust) {
        if (mysteryDust == null || mysteryDust.trim().isEmpty()) {
            return "0";
        }
        return mysteryDust;
    }
}
