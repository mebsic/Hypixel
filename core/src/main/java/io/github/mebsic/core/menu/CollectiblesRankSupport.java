package io.github.mebsic.core.menu;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CosmeticService;
import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CollectiblesRankSupport {
    static final int VIP_COST = 500;
    static final int VIP_PLUS_COST = 1_000;
    static final int MVP_COST = 1_500;
    static final int MVP_PLUS_COST = 2_000;
    static final int MVP_PLUS_PLUS_30_DAYS_COST = 2_500;
    static final int MVP_PLUS_PLUS_90_DAYS_COST = 5_000;
    static final int MVP_PLUS_PLUS_180_DAYS_COST = 8_000;
    static final int MVP_PLUS_PLUS_365_DAYS_COST = 12_000;

    private CollectiblesRankSupport() {
    }

    static Rank rankFromId(String id) {
        String normalized = normalize(id);
        if (CosmeticService.RANK_VIP_ID.equals(normalized)) {
            return Rank.VIP;
        }
        if (CosmeticService.RANK_VIP_PLUS_ID.equals(normalized)) {
            return Rank.VIP_PLUS;
        }
        if (CosmeticService.RANK_MVP_ID.equals(normalized)) {
            return Rank.MVP;
        }
        if (CosmeticService.RANK_MVP_PLUS_ID.equals(normalized)) {
            return Rank.MVP_PLUS;
        }
        if (CosmeticService.RANK_MVP_PLUS_PLUS_ID.equals(normalized)) {
            return Rank.MVP_PLUS_PLUS;
        }
        return Rank.DEFAULT;
    }

    static String idFromRank(Rank rank) {
        if (rank == Rank.VIP) {
            return CosmeticService.RANK_VIP_ID;
        }
        if (rank == Rank.VIP_PLUS) {
            return CosmeticService.RANK_VIP_PLUS_ID;
        }
        if (rank == Rank.MVP) {
            return CosmeticService.RANK_MVP_ID;
        }
        if (rank == Rank.MVP_PLUS) {
            return CosmeticService.RANK_MVP_PLUS_ID;
        }
        if (rank == Rank.MVP_PLUS_PLUS) {
            return CosmeticService.RANK_MVP_PLUS_PLUS_ID;
        }
        return CosmeticService.RANK_DEFAULT_ID;
    }

    static String formattedRank(Rank rank) {
        return GiftSupport.displayRank(rank);
    }

    static String rawRankName(Rank rank) {
        if (rank == Rank.VIP_PLUS) {
            return "VIP+";
        }
        if (rank == Rank.MVP_PLUS) {
            return "MVP+";
        }
        if (rank == Rank.MVP_PLUS_PLUS) {
            return "MVP++";
        }
        if (rank == Rank.VIP || rank == Rank.MVP) {
            return rank.name();
        }
        return "DEFAULT";
    }

    static Material rankMaterial(Rank rank) {
        if (rank == Rank.MVP_PLUS_PLUS) {
            return Material.GOLD_INGOT;
        }
        if (rank == Rank.MVP || rank == Rank.MVP_PLUS) {
            return Material.DIAMOND;
        }
        return Material.EMERALD;
    }

    static int rankCost(Rank rank) {
        if (rank == Rank.VIP) {
            return VIP_COST;
        }
        if (rank == Rank.VIP_PLUS) {
            return VIP_PLUS_COST;
        }
        if (rank == Rank.MVP) {
            return MVP_COST;
        }
        if (rank == Rank.MVP_PLUS) {
            return MVP_PLUS_COST;
        }
        return MVP_PLUS_PLUS_30_DAYS_COST;
    }

    static int mvpPlusPlusDurationCost(int days) {
        if (days == 90) {
            return MVP_PLUS_PLUS_90_DAYS_COST;
        }
        if (days == 180) {
            return MVP_PLUS_PLUS_180_DAYS_COST;
        }
        if (days == 365) {
            return MVP_PLUS_PLUS_365_DAYS_COST;
        }
        return MVP_PLUS_PLUS_30_DAYS_COST;
    }

    static boolean isUnlocked(Profile profile, String id) {
        if (profile == null || id == null) {
            return false;
        }
        if (isSelected(profile, id)) {
            return true;
        }
        Set<String> unlocked = profile.getUnlocked().get(CosmeticType.RANK);
        return containsIgnoreCase(unlocked, id);
    }

    static boolean isSelected(Profile profile, String id) {
        return id != null && id.equalsIgnoreCase(selectedRankId(profile));
    }

    static String selectedRankId(Profile profile) {
        return idFromRank(currentRank(profile));
    }

    private static Rank currentRank(Profile profile) {
        if (profile == null || profile.getRank() == null) {
            return Rank.DEFAULT;
        }
        return profile.getRank();
    }

    static List<String> rankDescription(Rank rank) {
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
        } else if (rank == Rank.MVP_PLUS_PLUS) {
            lore.add(ChatColor.GRAY + "MVP++ is an exclusive Rank Upgrade");
            lore.add(ChatColor.GRAY + "to your existing MVP++ Rank. MVP++");
            lore.add(ChatColor.GRAY + "allows access to some very useful");
            lore.add(ChatColor.GRAY + "commands and is the best way to");
            lore.add(ChatColor.GRAY + "support the Hycopy Server.");
        }
        lore.add(ChatColor.GRAY + "and more...");
        return lore;
    }

    static String formatDust(int amount) {
        return String.format(Locale.US, "%,d", Math.max(0, amount));
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(Set<String> values, String target) {
        if (values == null || values.isEmpty() || target == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && target.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String perkLine(String text) {
        return ChatColor.GREEN + "- " + ChatColor.GRAY + text;
    }
}
