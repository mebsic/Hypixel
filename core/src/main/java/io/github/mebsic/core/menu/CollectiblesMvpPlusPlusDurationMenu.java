package io.github.mebsic.core.menu;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.NumberFormat;
import java.util.Locale;

public class CollectiblesMvpPlusPlusDurationMenu extends Menu {
    public static final String TITLE = "MVP++ Duration";
    private static final int SIZE = 54;
    private static final int DAYS_30_SLOT = 10;
    private static final int DAYS_90_SLOT = 12;
    private static final int DAYS_180_SLOT = 14;
    private static final int DAYS_365_SLOT = 16;
    private static final int RESET_SLOT = 40;
    private static final int BACK_SLOT = 48;
    private static final int COLLECTIBLES_SLOT = 49;

    private final CoreApi coreApi;
    private final CollectiblesRanksMenu parent;
    private final NumberFormat numberFormat;

    public CollectiblesMvpPlusPlusDurationMenu(CoreApi coreApi, CollectiblesRanksMenu parent) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
        this.parent = parent;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        Profile profile = player == null || coreApi == null ? null : coreApi.getProfile(player.getUniqueId());
        set(inventory, DAYS_30_SLOT, durationItem(30, profile));
        set(inventory, DAYS_90_SLOT, durationItem(90, profile));
        set(inventory, DAYS_180_SLOT, durationItem(180, profile));
        set(inventory, DAYS_365_SLOT, durationItem(365, profile));
        set(inventory, RESET_SLOT, item(Material.BARRIER, ChatColor.RED + "Click to reset!"));
        set(inventory, BACK_SLOT, item(Material.ARROW, ChatColor.GREEN + "Go Back", ChatColor.GRAY + "To Ranks"));
        set(inventory, COLLECTIBLES_SLOT, item(
                Material.CHEST,
                ChatColor.GREEN + "Collectibles",
                CollectiblesMenu.collectiblesLore(formatMysteryDust(profile))
        ));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player player = click.getPlayer();
        int slot = click.getRawSlot();
        if (slot == BACK_SLOT) {
            openParent(player);
            return;
        }
        if (slot == RESET_SLOT) {
            resetRank(player);
            return;
        }
        if (slot == COLLECTIBLES_SLOT) {
            return;
        }
        int days = daysForSlot(slot);
        if (days <= 0) {
            return;
        }
        Profile profile = coreApi == null ? null : coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + "Your profile is still loading!");
            return;
        }
        String rankId = CollectiblesRankSupport.idFromRank(Rank.MVP_PLUS_PLUS);
        if (CollectiblesRankSupport.isUnlocked(profile, rankId)) {
            if (parent != null) {
                parent.selectRank(player, Rank.MVP_PLUS_PLUS);
            }
            return;
        }
        int cost = CollectiblesRankSupport.mvpPlusPlusDurationCost(days);
        new CollectiblesRankConfirmMenu(coreApi, parent, Rank.MVP_PLUS_PLUS, cost, days).open(player);
    }

    private ItemStack durationItem(int days, Profile profile) {
        int cost = CollectiblesRankSupport.mvpPlusPlusDurationCost(days);
        String rankId = CollectiblesRankSupport.idFromRank(Rank.MVP_PLUS_PLUS);
        boolean unlocked = CollectiblesRankSupport.isUnlocked(profile, rankId);
        boolean selected = CollectiblesRankSupport.isSelected(profile, rankId);
        java.util.List<String> lore = new java.util.ArrayList<String>();
        lore.add(ChatColor.YELLOW + "This crafts " + days + " days of "
                + CollectiblesRankSupport.formattedRank(Rank.MVP_PLUS_PLUS)
                + ChatColor.YELLOW + " rank.");
        lore.add("");
        lore.add(ChatColor.GRAY + "Purchased days will accumulate, so if");
        lore.add(ChatColor.GRAY + "you craft 30 days and then decide to");
        lore.add(ChatColor.GRAY + "craft 90 days shortly after, you will");
        lore.add(ChatColor.GRAY + "have the rank for a total of 120");
        lore.add(ChatColor.GRAY + "days.");
        lore.add("");
        if (selected) {
            lore.add(ChatColor.GREEN + "Currently selected!");
        } else if (unlocked) {
            lore.add(ChatColor.YELLOW + "Click to select!");
        } else {
            lore.add(ChatColor.YELLOW + "Click to craft for " + ChatColor.AQUA
                    + CollectiblesRankSupport.formatDust(cost)
                    + ChatColor.YELLOW + " Mystery Dust!");
        }
        String title = CollectiblesRankSupport.formattedRank(Rank.MVP_PLUS_PLUS)
                + rankTitleColor(unlocked, selected)
                + " " + days + " DAYS"
                + saveSuffix(days);
        ItemStack stack = item(Material.GOLD_INGOT, title, lore);
        return selected ? GiftSupport.addGlow(stack) : stack;
    }

    private int daysForSlot(int slot) {
        if (slot == DAYS_30_SLOT) {
            return 30;
        }
        if (slot == DAYS_90_SLOT) {
            return 90;
        }
        if (slot == DAYS_180_SLOT) {
            return 180;
        }
        if (slot == DAYS_365_SLOT) {
            return 365;
        }
        return 0;
    }

    private void resetRank(Player player) {
        if (coreApi == null || player == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + "Your profile is still loading!");
            return;
        }
        if (CollectiblesRankSupport.isSelected(profile, CollectiblesRankSupport.idFromRank(Rank.DEFAULT))) {
            player.sendMessage(ChatColor.RED + "You already have that selected!");
            return;
        }
        coreApi.setRank(player.getUniqueId(), Rank.DEFAULT);
        player.sendMessage(ChatColor.GREEN + "You are now DEFAULT");
        open(player);
    }

    private void openParent(Player player) {
        if (player == null) {
            return;
        }
        if (parent == null) {
            player.closeInventory();
            return;
        }
        parent.open(player);
    }

    private ChatColor rankTitleColor(boolean unlocked, boolean selected) {
        if (selected || unlocked) {
            return ChatColor.GREEN;
        }
        return ChatColor.RED;
    }

    private String saveSuffix(int days) {
        if (days == 90) {
            return " (SAVE 7.5%)";
        }
        if (days == 180) {
            return " (SAVE 15.0%)";
        }
        if (days == 365) {
            return " (SAVE 25.0%)";
        }
        return "";
    }

    private String formatMysteryDust(Profile profile) {
        int mysteryDust = profile == null ? 0 : Math.max(0, profile.getMysteryDust());
        return numberFormat.format(mysteryDust);
    }
}
