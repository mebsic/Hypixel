package io.github.mebsic.core.menu;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GiftMvpPlusPlusMenu extends Menu {
    private static final String TITLE = "Rank Gifting";
    private static final int SIZE = 36;
    private static final int DAYS_30_SLOT = 10;
    private static final int DAYS_90_SLOT = 12;
    private static final int DAYS_180_SLOT = 14;
    private static final int DAYS_365_SLOT = 16;
    private static final int BACK_SLOT = 30;
    private static final int CLOSE_SLOT = 31;
    private static final int WALLET_SLOT = 35;

    private static final int DEFAULT_COST_30_DAYS = 799;
    private static final int DEFAULT_COST_90_DAYS = 2199;
    private static final int DEFAULT_COST_180_DAYS = 3_999;
    private static final int DEFAULT_COST_365_DAYS = 7_099;

    private final CorePlugin plugin;
    private final UUID targetUuid;
    private final String targetName;

    public GiftMvpPlusPlusMenu(CorePlugin plugin, Player target) {
        super(TITLE, SIZE);
        this.plugin = plugin;
        this.targetUuid = target == null ? null : target.getUniqueId();
        this.targetName = target == null ? "" : target.getName();
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        int walletGold = resolveWalletGold(player);
        set(inventory, DAYS_30_SLOT, buildDurationItem(30, cost30Days(), walletGold));
        set(inventory, DAYS_90_SLOT, buildDurationItem(90, cost90Days(), walletGold));
        set(inventory, DAYS_180_SLOT, buildDurationItem(180, cost180Days(), walletGold));
        set(inventory, DAYS_365_SLOT, buildDurationItem(365, cost365Days(), walletGold));
        set(inventory, BACK_SLOT, item(Material.ARROW, ChatColor.GREEN + "Go Back"));
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
        set(inventory, WALLET_SLOT, GiftSupport.buildWalletItem(walletGold));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player sender = click.getPlayer();
        int slot = click.getRawSlot();
        if (slot == CLOSE_SLOT) {
            sender.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            Player target = resolveTargetOrMessage(sender);
            if (target == null) {
                sender.closeInventory();
                return;
            }
            new GiftSelectMenu(plugin, target).open(sender);
            return;
        }
        if (slot == WALLET_SLOT) {
            return;
        }
        if (slot == DAYS_30_SLOT) {
            openMvpPlusPlusConfirm(sender, 30, cost30Days());
            return;
        }
        if (slot == DAYS_90_SLOT) {
            openMvpPlusPlusConfirm(sender, 90, cost90Days());
            return;
        }
        if (slot == DAYS_180_SLOT) {
            openMvpPlusPlusConfirm(sender, 180, cost180Days());
            return;
        }
        if (slot == DAYS_365_SLOT) {
            openMvpPlusPlusConfirm(sender, 365, cost365Days());
        }
    }

    private void openMvpPlusPlusConfirm(Player sender, int days, int costGold) {
        if (sender == null || plugin == null) {
            return;
        }
        if (!isHubServer()) {
            sender.sendMessage(ChatColor.RED + "You can only gift players in a lobby!");
            return;
        }
        Player target = resolveTargetOrMessage(sender);
        if (target == null) {
            sender.closeInventory();
            return;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't send gifts to yourself!");
            sender.closeInventory();
            return;
        }

        Profile senderProfile = plugin.getProfile(sender.getUniqueId());
        if (senderProfile == null) {
            sender.sendMessage(GiftSupport.PROFILE_LOADING_SELF_MESSAGE);
            return;
        }
        Profile targetProfile = plugin.getProfile(target.getUniqueId());
        if (targetProfile == null) {
            sender.sendMessage(GiftSupport.PROFILE_LOADING_TARGET_MESSAGE);
            return;
        }

        Rank previousRank = GiftSupport.safeRank(targetProfile.getRank());
        boolean eligible = previousRank == Rank.MVP_PLUS || previousRank == Rank.MVP_PLUS_PLUS;
        if (!eligible) {
            sender.sendMessage(ChatColor.RED + "That player must be MVP+ or MVP++ to receive this gift!");
            return;
        }

        int safeCost = Math.max(0, costGold);
        int senderGold = Math.max(0, senderProfile.getNetworkGold());
        if (senderGold < safeCost) {
            return;
        }
        new GiftConfirmMenu(plugin, target, days, safeCost).open(sender);
    }

    private ItemStack buildDurationItem(int days, int costGold, int walletGold) {
        Material material = Material.GOLD_INGOT;
        int safeCost = Math.max(0, costGold);
        boolean canAfford = Math.max(0, walletGold) >= safeCost;
        ChatColor titleColor = canAfford ? ChatColor.GREEN : ChatColor.RED;
        String title = titleColor + "MVP++ " + days + " DAYS" + saveSuffix(days);
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.YELLOW + "This is a one time purchase for " + days);
        lore.add(ChatColor.YELLOW + "days of "
                + GiftSupport.displayRankWithColoredPlus(Rank.MVP_PLUS_PLUS, ChatColor.YELLOW)
                + ", You will go back to");
        lore.add(ChatColor.AQUA + "MVP" + ChatColor.RED + "+" + ChatColor.YELLOW + " after.");
        lore.add("");
        lore.add(ChatColor.GRAY + "Purchased days will accumulate, so if");
        lore.add(ChatColor.GRAY + "you buy 30 days and then decided to");
        lore.add(ChatColor.GRAY + "buy 90 days shortly after, you will");
        lore.add(ChatColor.GRAY + "have the rank for a total of 120");
        lore.add(ChatColor.GRAY + "days.");
        lore.add("");
        lore.add(ChatColor.GRAY + "Cost: " + ChatColor.GREEN + GiftSupport.formatGold(safeCost) + " Gold");
        lore.add("");
        lore.add(GiftSupport.giftActionLine(canAfford));
        return GiftSupport.addGlow(item(material, title, lore));
    }

    private Player resolveTargetOrMessage(Player sender) {
        if (targetUuid == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player '" + targetName + "'!");
            return null;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "That player is not in your lobby!");
            return null;
        }
        return target;
    }

    private int resolveWalletGold(Player sender) {
        if (sender == null || plugin == null) {
            return 0;
        }
        Profile profile = plugin.getProfile(sender.getUniqueId());
        return profile == null ? 0 : Math.max(0, profile.getNetworkGold());
    }

    private boolean isHubServer() {
        ServerType type = plugin == null ? null : plugin.getServerType();
        return type != null && type.isHub();
    }

    private int cost30Days() {
        return readCost("days30", DEFAULT_COST_30_DAYS);
    }

    private int cost90Days() {
        return readCost("days90", DEFAULT_COST_90_DAYS);
    }

    private int cost180Days() {
        return readCost("days180", DEFAULT_COST_180_DAYS);
    }

    private int cost365Days() {
        return readCost("days365", DEFAULT_COST_365_DAYS);
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

    private int readCost(String key, int fallback) {
        if (plugin == null || plugin.getConfig() == null || key == null || key.trim().isEmpty()) {
            return Math.max(0, fallback);
        }
        return Math.max(0, plugin.getConfig().getInt("gifts.mvpPlusPlusGiftCosts." + key, fallback));
    }
}
