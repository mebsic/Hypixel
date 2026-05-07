package io.github.mebsic.core.menu;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class CollectiblesRankConfirmMenu extends Menu {
    private static final String TITLE = "Confirm";
    private static final int SIZE = 27;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;

    private final CoreApi coreApi;
    private final CollectiblesRanksMenu ranksMenu;
    private final Rank rank;
    private final int cost;
    private final Integer mvpPlusPlusDays;

    public CollectiblesRankConfirmMenu(CoreApi coreApi,
                                       CollectiblesRanksMenu ranksMenu,
                                       Rank rank,
                                       int cost,
                                       Integer mvpPlusPlusDays) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
        this.ranksMenu = ranksMenu;
        this.rank = rank == null ? Rank.DEFAULT : rank;
        this.cost = Math.max(0, cost);
        this.mvpPlusPlusDays = mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, CONFIRM_SLOT, clayButton(true, ChatColor.GREEN + "Confirm",
                ChatColor.GRAY + "Craft: " + purchaseRankText(),
                ChatColor.GRAY + "Cost: " + ChatColor.AQUA + CollectiblesRankSupport.formatDust(cost)
                        + ChatColor.GRAY + " Mystery Dust"));
        set(inventory, CANCEL_SLOT, clayButton(false, ChatColor.RED + "Cancel"));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player player = click.getPlayer();
        int slot = click.getRawSlot();
        if (slot == CANCEL_SLOT) {
            openPreviousMenu(player);
            return;
        }
        if (slot != CONFIRM_SLOT) {
            return;
        }
        confirmPurchase(player);
    }

    private void confirmPurchase(Player player) {
        if (player == null || coreApi == null || rank == Rank.DEFAULT) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + "Your profile is still loading!");
            return;
        }
        String rankId = CollectiblesRankSupport.idFromRank(rank);
        if (!CollectiblesRankSupport.isUnlocked(profile, rankId)) {
            int currentDust = Math.max(0, profile.getMysteryDust());
            if (currentDust < cost) {
                int needed = Math.max(0, cost - currentDust);
                player.closeInventory();
                player.sendMessage(ChatColor.RED + "You need " + ChatColor.AQUA
                        + CollectiblesRankSupport.formatDust(needed)
                        + ChatColor.RED + " more Mystery Dust to craft "
                        + purchaseRankText() + ChatColor.RED + "!");
                return;
            }
            coreApi.setMysteryDust(player.getUniqueId(), currentDust - cost);
            coreApi.unlockCosmetic(player.getUniqueId(), CosmeticType.RANK, rankId);
            player.sendMessage(ChatColor.GREEN + "You purchased "
                    + purchaseRankText()
                    + ChatColor.GREEN + " for "
                    + ChatColor.AQUA + CollectiblesRankSupport.formatDust(cost)
                    + ChatColor.GREEN + " Mystery Dust!");
        }
        applyRank(player);
        openPreviousMenu(player);
    }

    private void applyRank(Player player) {
        if (player == null || coreApi == null || rank == Rank.DEFAULT) {
            return;
        }
        if (rank == Rank.MVP_PLUS_PLUS && mvpPlusPlusDays != null && mvpPlusPlusDays > 0) {
            coreApi.setRank(player.getUniqueId(), rank, mvpPlusPlusDays, true);
            return;
        }
        coreApi.setRank(player.getUniqueId(), rank);
    }

    private void openPreviousMenu(Player player) {
        if (player == null) {
            return;
        }
        if (mvpPlusPlusDays != null && mvpPlusPlusDays > 0) {
            new CollectiblesMvpPlusPlusDurationMenu(coreApi, ranksMenu).open(player);
            return;
        }
        if (ranksMenu == null) {
            player.closeInventory();
            return;
        }
        ranksMenu.open(player);
    }

    private String purchaseRankText() {
        String text = CollectiblesRankSupport.formattedRank(rank);
        if (rank == Rank.MVP_PLUS_PLUS && mvpPlusPlusDays != null && mvpPlusPlusDays > 0) {
            return text + ChatColor.GOLD + " " + mvpPlusPlusDays + " Days";
        }
        return text;
    }

    private ItemStack clayButton(boolean green, String name, String... lore) {
        Material terracotta = Material.matchMaterial("STAINED_CLAY");
        Material modern = green ? Material.matchMaterial("GREEN_TERRACOTTA") : Material.matchMaterial("RED_TERRACOTTA");
        Material material = modern != null ? modern : (terracotta == null ? Material.WOOL : terracotta);
        ItemStack stack = item(material, name, lore);
        if (terracotta != null && stack != null && stack.getType() == terracotta) {
            stack.setDurability((short) (green ? 13 : 14));
        }
        return stack;
    }
}
