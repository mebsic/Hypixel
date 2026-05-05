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

public class GiftSelectMenu extends Menu {
    private static final String TITLE = "Select a Gift";
    private static final int SIZE = 54;
    private static final int TARGET_SLOT = 13;
    private static final int VIP_SLOT = 27;
    private static final int VIP_PLUS_SLOT = 29;
    private static final int MVP_SLOT = 31;
    private static final int MVP_PLUS_SLOT = 33;
    private static final int MVP_PLUS_PLUS_SLOT = 35;
    private static final int BACK_SLOT = 48;
    private static final int CLOSE_SLOT = 49;
    private static final int WALLET_SLOT = 53;

    private static final int DEFAULT_VIP_COST = 699;
    private static final int DEFAULT_VIP_PLUS_COST = 1_499;
    private static final int DEFAULT_MVP_COST = 2_999;
    private static final int DEFAULT_MVP_PLUS_COST = 4_499;

    private final CorePlugin plugin;
    private final UUID targetUuid;
    private final String targetName;

    public GiftSelectMenu(CorePlugin plugin, Player target) {
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
        Profile targetProfile = plugin == null || targetUuid == null ? null : plugin.getProfile(targetUuid);
        Rank targetRank = targetProfile == null ? Rank.DEFAULT : GiftSupport.safeRank(targetProfile.getRank());
        set(inventory, TARGET_SLOT, buildTargetItem());
        set(inventory, VIP_SLOT, buildRankItem(Rank.VIP, false, vipCost(), walletGold, targetRank));
        set(inventory, VIP_PLUS_SLOT, buildRankItem(Rank.VIP_PLUS, true, vipPlusCost(), walletGold, targetRank));
        set(inventory, MVP_SLOT, buildRankItem(Rank.MVP, false, mvpCost(), walletGold, targetRank));
        set(inventory, MVP_PLUS_SLOT, buildRankItem(Rank.MVP_PLUS, true, mvpPlusCost(), walletGold, targetRank));
        set(inventory, MVP_PLUS_PLUS_SLOT, buildMvpPlusPlusItem());
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
            new GiftMenu(plugin, target).open(sender);
            return;
        }
        if (slot == WALLET_SLOT) {
            return;
        }
        if (slot == MVP_PLUS_PLUS_SLOT) {
            Player target = resolveTargetOrMessage(sender);
            if (target == null) {
                sender.closeInventory();
                return;
            }
            new GiftMvpPlusPlusMenu(plugin, target).open(sender);
            return;
        }
        if (slot == VIP_SLOT) {
            openStandardRankConfirm(sender, Rank.VIP, vipCost());
            return;
        }
        if (slot == VIP_PLUS_SLOT) {
            openStandardRankConfirm(sender, Rank.VIP_PLUS, vipPlusCost());
            return;
        }
        if (slot == MVP_SLOT) {
            openStandardRankConfirm(sender, Rank.MVP, mvpCost());
            return;
        }
        if (slot == MVP_PLUS_SLOT) {
            openStandardRankConfirm(sender, Rank.MVP_PLUS, mvpPlusCost());
        }
    }

    private void openStandardRankConfirm(Player sender, Rank giftedRank, int costGold) {
        if (sender == null || giftedRank == null || plugin == null) {
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
        Rank currentRank = GiftSupport.safeRank(targetProfile.getRank());
        if (currentRank.isAtLeast(giftedRank)) {
            return;
        }
        int gold = Math.max(0, senderProfile.getNetworkGold());
        int safeCost = Math.max(0, costGold);
        if (gold < safeCost) {
            return;
        }
        new GiftConfirmMenu(plugin, target, giftedRank, safeCost).open(sender);
    }

    private ItemStack buildTargetItem() {
        String safeTargetName = GiftSupport.safeString(targetName);
        if (safeTargetName.isEmpty()) {
            safeTargetName = "Player";
        }
        Profile targetProfile = plugin == null || targetUuid == null ? null : plugin.getProfile(targetUuid);
        String display = GiftSupport.buildTargetDisplayName(targetProfile, safeTargetName);
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "You are sending a gift to this player!");
        return GiftSupport.createHeadItem(safeTargetName, display, lore);
    }

    private ItemStack buildRankItem(Rank rank, boolean enchanted, int costGold, int walletGold, Rank targetRank) {
        Material material = (rank == Rank.VIP || rank == Rank.VIP_PLUS) ? Material.EMERALD : Material.DIAMOND;
        ChatColor rankColor = (rank == Rank.VIP || rank == Rank.VIP_PLUS) ? ChatColor.GREEN : ChatColor.AQUA;
        int safeCost = Math.max(0, costGold);
        boolean canAfford = Math.max(0, walletGold) >= safeCost;
        Rank safeTargetRank = GiftSupport.safeRank(targetRank);
        boolean alreadyOwned = safeTargetRank.isAtLeast(rank);
        List<String> lore = buildRankLore(rank, safeCost, canAfford, alreadyOwned);
        ItemStack item = item(material, GiftSupport.displayRank(rank) + rankColor + " Rank", lore);
        if (!enchanted) {
            return item;
        }
        return GiftSupport.addGlow(item);
    }

    private List<String> buildRankLore(Rank rank, int costGold, boolean canAfford, boolean alreadyOwned) {
        List<String> lore = new ArrayList<String>();
        if (rank == Rank.VIP) {
            lore.add(ChatColor.GRAY + "VIP provides basic perks which");
            lore.add(ChatColor.GRAY + "improve the Hycopy experience!");
            lore.add("");
            lore.add(perkLine("Chat Prefix: " + ChatColor.GREEN + "[VIP]"));
            lore.add(perkLine("Name Color: " + ChatColor.GREEN + "GREEN"));
            lore.add(perkLine("90+ Exclusive Cosmetics"));
            lore.add(perkLine("Game Replays"));
            lore.add(perkLine("Housing Mailbox"));
            lore.add(perkLine("/fw Command"));
            lore.add(perkLine("[Website] Avatar Frame"));
        } else if (rank == Rank.VIP_PLUS) {
            lore.add(ChatColor.GRAY + "VIP+ contains every perk from VIP");
            lore.add(ChatColor.GRAY + "and then more!");
            lore.add("");
            lore.add(perkLine("Chat Prefix: " + ChatColor.GREEN + "[VIP" + ChatColor.GOLD + "+" + ChatColor.GREEN + "]"));
            lore.add(perkLine("Name Color: " + ChatColor.GREEN + "GREEN"));
            lore.add(perkLine("100+ Exclusive Cosmetics"));
            lore.add(perkLine("Create Guilds"));
            lore.add(perkLine("Baby Wild Ocelot Pet"));
            lore.add(perkLine("Game Replays"));
            lore.add(perkLine("Housing Mailbox"));
        } else if (rank == Rank.MVP) {
            lore.add(ChatColor.GRAY + "MVP contains every perk from VIP");
            lore.add(ChatColor.GRAY + "and VIP+, and then more!");
            lore.add("");
            lore.add(perkLine("Chat Prefix: " + ChatColor.AQUA + "[MVP]"));
            lore.add(perkLine("Name Color: " + ChatColor.AQUA + "AQUA"));
            lore.add(perkLine("200+ Exclusive Cosmetics"));
            lore.add(perkLine("Create Guilds"));
            lore.add(perkLine("Baby Wild Ocelot Pet"));
            lore.add(perkLine("Game Replays"));
            lore.add(perkLine("Housing Mailbox"));
        } else if (rank == Rank.MVP_PLUS) {
            lore.add(ChatColor.GRAY + "MVP+ contains every perk from VIP,");
            lore.add(ChatColor.GRAY + "VIP+ and MVP, and then more!");
            lore.add("");
            lore.add(perkLine("Chat Prefix: " + ChatColor.AQUA + "[MVP" + ChatColor.RED + "+" + ChatColor.AQUA + "]"));
            lore.add(perkLine("Name Color: " + ChatColor.AQUA + "AQUA"));
            lore.add(perkLine("200+ Exclusive Cosmetics"));
            lore.add(perkLine("Map Selectors"));
            lore.add(perkLine("Achievement Tracking"));
            lore.add(perkLine("Exclusive Particle Packs"));
            lore.add(perkLine("Ride and Control Lobby Pets"));
        }
        lore.add(ChatColor.GRAY + "and more...");
        lore.add("");
        lore.add(ChatColor.GRAY + "Cost: " + ChatColor.GREEN + GiftSupport.formatGold(costGold) + " Gold");
        lore.add("");
        if (alreadyOwned) {
            lore.add(ChatColor.RED + "Already owned!");
        } else {
            lore.add(GiftSupport.giftActionLine(canAfford));
        }
        return lore;
    }

    private ItemStack buildMvpPlusPlusItem() {
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "MVP++ is an exclusive Rank Upgrade");
        lore.add(ChatColor.GRAY + "to your existing MVP++ Rank. MVP++");
        lore.add(ChatColor.GRAY + "allows access to some very useful");
        lore.add(ChatColor.GRAY + "commands and is the best way to");
        lore.add(ChatColor.GRAY + "support the Hycopy Server.");
        lore.add(ChatColor.GRAY + "Purchased days will accumulate; If");
        lore.add(ChatColor.GRAY + "you buy 30 days, then 90 days, you");
        lore.add(ChatColor.GRAY + "would have a total of 120 days.");
        lore.add(ChatColor.YELLOW + "Click to browse perks and durations!");
        return GiftSupport.addGlow(item(Material.GOLD_INGOT, ChatColor.GOLD + "MVP" + ChatColor.RED + "++", lore));
    }

    private String perkLine(String text) {
        return ChatColor.GREEN + "✓ " + ChatColor.GRAY + text;
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

    private int vipCost() {
        return DEFAULT_VIP_COST;
    }

    private int vipPlusCost() {
        return DEFAULT_VIP_PLUS_COST;
    }

    private int mvpCost() {
        return DEFAULT_MVP_COST;
    }

    private int mvpPlusCost() {
        return DEFAULT_MVP_PLUS_COST;
    }
}
