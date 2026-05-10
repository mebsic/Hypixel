package io.github.mebsic.core.menu;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.CommonMessages;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CollectiblesRanksMenu extends Menu {
    public static final String TITLE = "Ranks";
    private static final int SIZE = 45;
    private static final int VIP_SLOT = 9;
    private static final int VIP_PLUS_SLOT = 11;
    private static final int MVP_SLOT = 13;
    private static final int MVP_PLUS_SLOT = 15;
    private static final int MVP_PLUS_PLUS_SLOT = 17;
    private static final int RESET_SLOT = 31;
    private static final int BACK_SLOT = 39;
    private static final int COLLECTIBLES_SLOT = 40;

    private final CoreApi coreApi;
    private final CollectiblesMenu parent;
    private final NumberFormat numberFormat;
    private final Map<Integer, Rank> rankBySlot;

    public CollectiblesRanksMenu(CoreApi coreApi, CollectiblesMenu parent) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
        this.parent = parent;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.rankBySlot = new HashMap<Integer, Rank>();
        rankBySlot.put(VIP_SLOT, Rank.VIP);
        rankBySlot.put(VIP_PLUS_SLOT, Rank.VIP_PLUS);
        rankBySlot.put(MVP_SLOT, Rank.MVP);
        rankBySlot.put(MVP_PLUS_SLOT, Rank.MVP_PLUS);
        rankBySlot.put(MVP_PLUS_PLUS_SLOT, Rank.MVP_PLUS_PLUS);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        Profile profile = player == null || coreApi == null ? null : coreApi.getProfile(player.getUniqueId());
        for (Map.Entry<Integer, Rank> entry : rankBySlot.entrySet()) {
            set(inventory, entry.getKey(), rankItem(entry.getValue(), profile));
        }
        set(inventory, RESET_SLOT, item(Material.BARRIER, ChatColor.RED + "Click to reset!"));
        set(inventory, BACK_SLOT, item(Material.ARROW, ChatColor.GREEN + "Go Back", ChatColor.GRAY + "To Collectibles"));
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
            selectDefaultRank(player);
            return;
        }
        if (slot == COLLECTIBLES_SLOT) {
            return;
        }
        Rank rank = rankBySlot.get(slot);
        if (rank == null) {
            return;
        }
        if (rank == Rank.MVP_PLUS_PLUS) {
            Profile profile = coreApi == null ? null : coreApi.getProfile(player.getUniqueId());
            if (profile == null) {
                player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
                return;
            }
            if (!CollectiblesRankSupport.hasMvpPlusBase(profile)) {
                player.sendMessage(CollectiblesRankSupport.mvpPlusRequirementMessage());
                return;
            }
            new CollectiblesMvpPlusPlusDurationMenu(coreApi, this).open(player);
            return;
        }
        handleRankClick(player, rank, CollectiblesRankSupport.rankCost(rank));
    }

    void handleRankClick(Player player, Rank rank, int cost) {
        if (player == null || rank == null || coreApi == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
            return;
        }
        if (CollectiblesRankSupport.isUnlocked(profile, rank)) {
            selectRank(player, rank);
            return;
        }
        new CollectiblesRankConfirmMenu(coreApi, this, rank, Math.max(0, cost), null).open(player);
    }

    void selectRank(Player player, Rank rank) {
        if (player == null || rank == null || coreApi == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
            return;
        }
        if (!CollectiblesRankSupport.isUnlocked(profile, rank)) {
            player.sendMessage(ChatColor.RED + "You haven't unlocked that rank yet!");
            return;
        }
        if (CollectiblesRankSupport.isSelected(profile, rank)) {
            player.sendMessage(ChatColor.RED + "You already have that selected!");
            return;
        }
        coreApi.setRank(player.getUniqueId(), rank, true);
        player.sendMessage(ChatColor.GREEN + "You are now " + CollectiblesRankSupport.rawRankName(rank));
        open(player);
    }

    private ItemStack rankItem(Rank rank, Profile profile) {
        boolean unlocked = CollectiblesRankSupport.isUnlocked(profile, rank);
        boolean selected = CollectiblesRankSupport.isSelected(profile, rank);
        int cost = CollectiblesRankSupport.rankCost(rank);
        List<String> lore = CollectiblesRankSupport.rankDescription(rank);
        lore.add("");
        if (selected) {
            lore.add(ChatColor.GREEN + "Currently selected!");
        } else if (unlocked) {
            lore.add(ChatColor.YELLOW + "Click to select!");
        } else if (rank == Rank.MVP_PLUS_PLUS) {
            if (CollectiblesRankSupport.hasMvpPlusBase(profile)) {
                lore.add(ChatColor.YELLOW + "Click to browse durations!");
            } else {
                lore.add(CollectiblesRankSupport.mvpPlusRequirementLore());
            }
        } else if (!CollectiblesRankSupport.hasEnoughMysteryDust(profile, cost)) {
            lore.add(CollectiblesRankSupport.missingMysteryDustLore(profile, cost));
        } else {
            lore.add(ChatColor.YELLOW + "Click to purchase for " + ChatColor.AQUA
                    + CollectiblesRankSupport.formatDust(cost)
                    + ChatColor.YELLOW + " Mystery Dust!");
        }
        ItemStack stack = item(
                CollectiblesRankSupport.rankMaterial(rank),
                CollectiblesRankSupport.formattedRank(rank),
                lore
        );
        return selected || CollectiblesRankSupport.usesEnchantedVariant(rank)
                ? GiftSupport.addGlow(stack)
                : stack;
    }

    private void selectDefaultRank(Player player) {
        if (player == null || coreApi == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
            return;
        }
        if (CollectiblesRankSupport.isSelected(profile, Rank.DEFAULT)) {
            player.sendMessage(ChatColor.RED + "You already have that selected!");
            return;
        }
        coreApi.setRank(player.getUniqueId(), Rank.DEFAULT, true);
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

    private String formatMysteryDust(Profile profile) {
        int mysteryDust = profile == null ? 0 : Math.max(0, profile.getMysteryDust());
        return numberFormat.format(mysteryDust);
    }
}
