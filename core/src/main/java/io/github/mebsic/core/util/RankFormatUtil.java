package io.github.mebsic.core.util;

import io.github.mebsic.core.model.Rank;
import org.bukkit.ChatColor;

public final class RankFormatUtil {
    private RankFormatUtil() {
    }

    public static String buildPrefix(Rank rank, int networkLevel) {
        return buildPrefix(rank, networkLevel, null);
    }

    public static String buildPrefix(Rank rank, int networkLevel, String selectedPlusColor) {
        return buildPrefix(rank, networkLevel, selectedPlusColor, null);
    }

    public static String buildPrefix(Rank rank,
                                     int networkLevel,
                                     String selectedPlusColor,
                                     String mvpPlusPlusPrefixColor) {
        ChatColor plusColor = RankColorUtil.getPlusColor(networkLevel, selectedPlusColor);
        ChatColor mvpPlusPlusColor = RankColorUtil.getMvpPlusPlusPrefixColor(mvpPlusPlusPrefixColor);
        return buildPrefix(rank, plusColor, mvpPlusPlusColor);
    }

    public static String buildPrefix(Rank rank, ChatColor plusColor) {
        return buildPrefix(rank, plusColor, RankColorUtil.getMvpPlusPlusPrefixColor(null));
    }

    public static String buildPrefix(Rank rank, ChatColor plusColor, ChatColor mvpPlusPlusColor) {
        if (rank == null || rank == Rank.DEFAULT) {
            return "";
        }
        ChatColor safePlus = plusColor == null ? RankColorUtil.getPlusColor(0) : plusColor;
        ChatColor mvpPlusPlusBase = mvpPlusPlusColor == null
                ? RankColorUtil.getMvpPlusPlusPrefixColor(null)
                : mvpPlusPlusColor;
        switch (rank) {
            case MVP_PLUS:
                return ChatColor.AQUA + "[MVP" + safePlus + "+" + ChatColor.AQUA + "] ";
            case MVP_PLUS_PLUS:
                return mvpPlusPlusBase + "[MVP" + safePlus + "++" + mvpPlusPlusBase + "] ";
            default:
                return rank.getPrefix();
        }
    }

    public static String buildTeamPrefix(Rank rank, int networkLevel) {
        return buildTeamPrefix(rank, RankColorUtil.getPlusColor(networkLevel));
    }

    public static String buildTeamPrefix(Rank rank, ChatColor plusColor) {
        if (rank == null) {
            return "";
        }
        ChatColor base = baseColor(rank);
        return buildPrefix(rank, plusColor) + base;
    }

    public static ChatColor baseColor(Rank rank) {
        return baseColor(rank, null);
    }

    public static ChatColor baseColor(Rank rank, String mvpPlusPlusPrefixColor) {
        if (rank == Rank.MVP_PLUS_PLUS) {
            return RankColorUtil.getMvpPlusPlusPrefixColor(mvpPlusPlusPrefixColor);
        }
        return rank == null ? ChatColor.GRAY : rank.getColor();
    }
}
