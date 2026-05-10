package io.github.mebsic.core.menu;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import org.bukkit.ChatColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class CollectiblesRankSupport {
    static final int VIP_COST = 5;
    static final int VIP_PLUS_COST = 10;
    static final int MVP_COST = 15;
    static final int MVP_PLUS_COST = 20;
    static final int MVP_PLUS_PLUS_30_DAYS_COST = 25;
    static final int MVP_PLUS_PLUS_90_DAYS_COST = 30;
    static final int MVP_PLUS_PLUS_180_DAYS_COST = 35;
    static final int MVP_PLUS_PLUS_365_DAYS_COST = 40;
    private static final List<Rank> PURCHASABLE_RANKS = Collections.unmodifiableList(Arrays.asList(
            Rank.VIP,
            Rank.VIP_PLUS,
            Rank.MVP,
            Rank.MVP_PLUS,
            Rank.MVP_PLUS_PLUS
    ));

    private CollectiblesRankSupport() {
    }

    static List<Rank> rankOptions() {
        return PURCHASABLE_RANKS;
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

    static boolean usesEnchantedVariant(Rank rank) {
        return rank == Rank.VIP_PLUS || rank == Rank.MVP_PLUS || rank == Rank.MVP_PLUS_PLUS;
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

    static boolean isUnlocked(Profile profile, Rank rank) {
        if (profile == null || rank == null || rank == Rank.DEFAULT) {
            return false;
        }
        if (rank == Rank.MVP_PLUS_PLUS && !hasActiveMvpPlusPlusSubscription(profile)) {
            return false;
        }
        for (Rank unlockedRank : profile.getUnlockedRanks()) {
            if (unlockedRank != null && unlockedRank.isAtLeast(rank)) {
                return true;
            }
        }
        return currentRank(profile).isAtLeast(rank);
    }

    static boolean isSelected(Profile profile, Rank rank) {
        return rank != null && currentRank(profile) == rank;
    }

    static boolean hasMvpPlusBase(Profile profile) {
        return isUnlocked(profile, Rank.MVP_PLUS);
    }

    private static boolean hasActiveMvpPlusPlusSubscription(Profile profile) {
        return profile != null
                && profile.hasActiveSubscription()
                && profile.getSubscriptionExpiresAt() > System.currentTimeMillis();
    }

    static String mvpPlusRequirementLore() {
        return ChatColor.RED + "You need " + ChatColor.AQUA + "MVP" + ChatColor.RED + "+"
                + ChatColor.RED + " rank!";
    }

    static String mvpPlusRequirementMessage() {
        return ChatColor.RED + "You need to purchase " + ChatColor.AQUA + "MVP" + ChatColor.RED + "+"
                + ChatColor.RED + " rank!";
    }

    static boolean hasEnoughMysteryDust(Profile profile, int cost) {
        int mysteryDust = profile == null ? 0 : Math.max(0, profile.getMysteryDust());
        return mysteryDust >= Math.max(0, cost);
    }

    static String missingMysteryDustLore(Profile profile, int cost) {
        int mysteryDust = profile == null ? 0 : Math.max(0, profile.getMysteryDust());
        int missing = Math.max(0, Math.max(0, cost) - mysteryDust);
        return ChatColor.RED + "You need " + ChatColor.AQUA + formatDust(missing)
                + ChatColor.RED + " more Mystery Dust!";
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
            lore.add(ChatColor.GRAY + "to your existing MVP+ rank.");
        }
        if (rank != Rank.MVP_PLUS_PLUS) {
            lore.add(ChatColor.GRAY + "and more...");
        }
        return lore;
    }

    static String formatDust(int amount) {
        return String.format(Locale.US, "%,d", Math.max(0, amount));
    }

    private static String perkLine(String text) {
        return ChatColor.GREEN + "- " + ChatColor.GRAY + text;
    }
}
