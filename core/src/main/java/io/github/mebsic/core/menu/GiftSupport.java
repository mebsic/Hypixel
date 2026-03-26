package io.github.mebsic.core.menu;

import com.mongodb.client.MongoCollection;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.core.util.RankFormatUtil;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class GiftSupport {
    static final String PRESENT_HEAD_OWNER = "MHF_Present";
    static final String GIFT_HISTORY_COLLECTION = "rank_gift_history";
    static final String GIFTED_COUNTER_KEY = "ranksGifted";
    public static final String PROFILE_LOADING_SELF_MESSAGE = ChatColor.RED + "Your profile is still loading! Try again in a moment.";
    public static final String PROFILE_LOADING_TARGET_MESSAGE = ChatColor.RED + "That player's profile is still loading! Try again in a moment.";
    public static final String GIFT_LOADING_MESSAGE = ChatColor.RED + "That gift is still loading! Try again in a moment.";

    private GiftSupport() {
    }

    public static Rank safeRank(Rank rank) {
        return rank == null ? Rank.DEFAULT : rank;
    }

    public static String displayRank(Rank rank) {
        if (rank == null) {
            return "";
        }
        switch (rank) {
            case VIP_PLUS:
                return "VIP+";
            case MVP_PLUS:
                return "MVP+";
            case MVP_PLUS_PLUS:
                return "MVP++";
            default:
                return rank.name();
        }
    }

    public static String displayRankWithColoredPlus(Rank rank, ChatColor trailingColor) {
        Rank safeRank = safeRank(rank);
        String text;
        switch (safeRank) {
            case VIP:
                text = ChatColor.GREEN + "VIP";
                break;
            case VIP_PLUS:
                text = ChatColor.GREEN + "VIP" + ChatColor.GOLD + "+" + ChatColor.GREEN;
                break;
            case MVP:
                text = ChatColor.AQUA + "MVP";
                break;
            case MVP_PLUS:
                text = ChatColor.AQUA + "MVP" + ChatColor.RED + "+" + ChatColor.AQUA;
                break;
            case MVP_PLUS_PLUS:
                text = ChatColor.GOLD + "MVP" + ChatColor.RED + "++" + ChatColor.GOLD;
                break;
            default:
                text = safeRank.getColor() + displayRank(safeRank);
                break;
        }
        if (trailingColor == null) {
            return text;
        }
        return text + trailingColor;
    }

    public static String buildTargetDisplayName(Profile profile, String fallbackName) {
        String name = safeString(fallbackName);
        if (name.isEmpty()) {
            name = "Player";
        }
        if (profile == null) {
            return ChatColor.WHITE + name;
        }
        Rank rank = safeRank(profile.getRank());
        String prefix = RankFormatUtil.buildPrefix(
                rank,
                Math.max(0, profile.getNetworkLevel()),
                profile.getPlusColor(),
                profile.getMvpPlusPlusPrefixColor()
        );
        if (prefix == null || prefix.isEmpty()) {
            return rank.getColor() + name;
        }
        ChatColor baseColor = RankFormatUtil.baseColor(rank, profile.getMvpPlusPlusPrefixColor());
        if (baseColor == null) {
            baseColor = ChatColor.WHITE;
        }
        return prefix + baseColor + name;
    }

    public static String buildTargetNameWithRankColor(Profile profile, String fallbackName) {
        String name = safeString(fallbackName);
        if (name.isEmpty()) {
            name = "Player";
        }
        if (profile == null) {
            return ChatColor.WHITE + name;
        }
        Rank rank = safeRank(profile.getRank());
        ChatColor baseColor = RankFormatUtil.baseColor(rank, profile.getMvpPlusPlusPrefixColor());
        if (baseColor == null) {
            baseColor = ChatColor.WHITE;
        }
        return baseColor + name;
    }

    public static String buildGiftRankText(Rank rank, Integer mvpPlusPlusDays) {
        Rank safe = safeRank(rank);
        String base;
        switch (safe) {
            case VIP:
                base = ChatColor.GREEN + "VIP";
                break;
            case VIP_PLUS:
                base = ChatColor.GREEN + "VIP" + ChatColor.GOLD + "+";
                break;
            case MVP:
                base = ChatColor.AQUA + "MVP";
                break;
            case MVP_PLUS:
                base = ChatColor.AQUA + "MVP" + ChatColor.RED + "+";
                break;
            case MVP_PLUS_PLUS:
                base = ChatColor.GOLD + "MVP" + ChatColor.RED + "++";
                break;
            default:
                base = safe.getColor() + safe.name();
                break;
        }
        Integer days = mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays);
        if (safe != Rank.MVP_PLUS_PLUS || days == null || days <= 0) {
            return base;
        }
        return base + ChatColor.GOLD + " " + days + " Days";
    }

    public static String buildGiftRankName(Rank rank) {
        return buildGiftRankText(rank, null);
    }

    public static ItemStack createHeadItem(String ownerName, String displayName, List<String> lore) {
        Material material = resolveHeadMaterial();
        ItemStack item;
        if (material == null) {
            item = new ItemStack(Material.PAPER);
        } else if ("SKULL_ITEM".equals(material.name())) {
            item = new ItemStack(material, 1, (short) 3);
        } else {
            item = new ItemStack(material, 1);
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            if (meta != null) {
                meta.setDisplayName(displayName);
                if (lore != null) {
                    meta.setLore(lore);
                }
                item.setItemMeta(meta);
            }
            return item;
        }
        SkullMeta skull = (SkullMeta) meta;
        applySkullOwner(skull, ownerName);
        skull.setDisplayName(displayName);
        if (lore != null) {
            skull.setLore(lore);
        }
        item.setItemMeta(skull);
        return item;
    }

    public static ItemStack addGlow(ItemStack item) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        try {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
        } catch (Exception ignored) {
            // Older/newer API differences should not break menu rendering.
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack buildWalletItem(int gold) {
        int safeGold = Math.max(0, gold);
        String formattedGold = formatGold(safeGold);
        String url = "https://store." + NetworkConstants.DOMAIN;
        ItemStack item = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.removeEnchant(Enchantment.DURABILITY);
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setDisplayName(ChatColor.GOLD + "Gold Wallet: " + formattedGold + " Gold");
        java.util.List<String> lore = new java.util.ArrayList<String>();
        lore.add(ChatColor.GRAY + "The Gold Wallet contains currency");
        lore.add(ChatColor.GRAY + "you can use in this menu to");
        lore.add(ChatColor.GRAY + "purchase items. You can purchase");
        lore.add(ChatColor.GRAY + "currency for your Gold Wallet at");
        lore.add(ChatColor.GREEN + url);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static String formatGold(int gold) {
        return String.format(Locale.US, "%,d", Math.max(0, gold));
    }

    public static String giftActionLine(boolean canAfford) {
        if (canAfford) {
            return ChatColor.GREEN + "Click to gift!";
        }
        return ChatColor.RED + "Not enough money in your wallet!";
    }

    public static ItemStack buildHardenedClayButton(boolean green, String displayName, List<String> lore) {
        ItemStack item = buildHardenedClay(green);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (displayName != null) {
            meta.setDisplayName(displayName);
        }
        if (lore != null) {
            meta.setLore(lore);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public static void incrementGiftedRanks(Profile profile) {
        if (profile == null || profile.getStats() == null) {
            return;
        }
        int current = readGiftedRanks(profile);
        int updated = Math.max(0, current + 1);
        int value = profile.getStats().getCustomCounter(GIFTED_COUNTER_KEY);
        if (value != updated) {
            profile.getStats().addCustomCounter(GIFTED_COUNTER_KEY, updated - value);
        }
    }

    public static int readGiftedRanks(Profile profile) {
        if (profile == null || profile.getStats() == null) {
            return 0;
        }
        return Math.max(0, profile.getStats().getCustomCounter(GIFTED_COUNTER_KEY));
    }

    public static void recordGiftHistoryAsync(CorePlugin plugin,
                                              org.bukkit.entity.Player sender,
                                              org.bukkit.entity.Player target,
                                              Rank previousRank,
                                              Rank giftedRank,
                                              int costGold,
                                              Integer mvpPlusPlusDays) {
        if (plugin == null || !plugin.isMongoEnabled() || plugin.getMongoManager() == null || !plugin.isEnabled()) {
            return;
        }
        if (sender == null || target == null || giftedRank == null) {
            return;
        }
        String serverId = plugin.getConfig() == null
                ? ""
                : safeString(plugin.getConfig().getString("server.id", ""));
        UUID senderUuid = sender.getUniqueId();
        UUID targetUuid = target.getUniqueId();
        Rank safePreviousRank = safeRank(previousRank);
        int safeCost = Math.max(0, costGold);
        Integer safeDays = mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MongoCollection<Document> collection = plugin.getMongoManager().getCollection(GIFT_HISTORY_COLLECTION);
                if (collection == null) {
                    return;
                }
                Document gift = new Document("gifterUuid", senderUuid == null ? "" : senderUuid.toString())
                        .append("gifterName", safeString(sender.getName()))
                        .append("targetUuid", targetUuid == null ? "" : targetUuid.toString())
                        .append("targetName", safeString(target.getName()))
                        .append("previousRank", safePreviousRank.name())
                        .append("giftedRank", giftedRank.name())
                        .append("costGold", safeCost)
                        .append("serverId", serverId)
                        .append("createdAt", System.currentTimeMillis());
                if (safeDays != null && safeDays > 0) {
                    gift.append("mvpPlusPlusDays", safeDays);
                }
                collection.insertOne(gift);
            } catch (Exception ignored) {
                // Gift has already been applied; history writes are best effort.
            }
        });
    }

    public static String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    public static void playGiftAnnouncementSound(org.bukkit.entity.Player player) {
        if (player == null) {
            return;
        }
        Sound sound = resolveGiftAnnouncementSound();
        if (sound == null) {
            return;
        }
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private static Material resolveHeadMaterial() {
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

    private static void applySkullOwner(SkullMeta meta, String ownerName) {
        if (meta == null || ownerName == null || ownerName.trim().isEmpty()) {
            return;
        }
        String safeOwner = ownerName.trim();
        try {
            Method setOwningPlayer = meta.getClass().getMethod("setOwningPlayer", OfflinePlayer.class);
            setOwningPlayer.invoke(meta, Bukkit.getOfflinePlayer(safeOwner));
            return;
        } catch (Exception ignored) {
            // Legacy API fallback below.
        }
        meta.setOwner(safeOwner);
    }

    private static Sound resolveGiftAnnouncementSound() {
        Sound legacy = trySound("LEVEL_UP");
        if (legacy != null) {
            return legacy;
        }
        return trySound("ENTITY_PLAYER_LEVELUP");
    }

    private static Sound trySound(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        try {
            return Sound.valueOf(key.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ItemStack buildHardenedClay(boolean green) {
        Material modern = Material.matchMaterial(green ? "GREEN_TERRACOTTA" : "RED_TERRACOTTA");
        if (modern != null) {
            return new ItemStack(modern, 1);
        }
        Material stainedClay = Material.matchMaterial("STAINED_CLAY");
        if (stainedClay != null) {
            return new ItemStack(stainedClay, 1, green ? (short) 5 : (short) 14);
        }
        Material hardenedClay = Material.matchMaterial("HARD_CLAY");
        return new ItemStack(hardenedClay == null ? Material.BRICK : hardenedClay, 1);
    }
}
