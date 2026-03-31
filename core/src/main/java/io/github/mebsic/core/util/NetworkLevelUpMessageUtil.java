package io.github.mebsic.core.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public final class NetworkLevelUpMessageUtil {
    private static final String FRAME_LINE = "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";
    private static final int LEVEL_UP_TITLE_LEFT_PADDING = 28;
    private static final int LEVEL_UP_SUBTITLE_LEFT_PADDING = 16;
    private static final int CLAIM_REWARD_LEFT_PADDING = 15;

    public static List<String> buildLines(int level) {
        int safeLevel = Math.max(0, level);
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(ChatColor.WHITE.toString() + ChatColor.BOLD + FRAME_LINE);
        lines.add("");
        lines.add(spaces(LEVEL_UP_TITLE_LEFT_PADDING)
                + ChatColor.GREEN + ChatColor.MAGIC + "z"
                + ChatColor.GOLD + " LEVEL UP! "
                + ChatColor.GREEN + ChatColor.MAGIC + "z");
        lines.add("");
        String levelSubtitle = formatLevelSubtitleLine(safeLevel);
        lines.add(spaces(LEVEL_UP_SUBTITLE_LEFT_PADDING) + levelSubtitle);
        lines.add("");
        List<RankColorUtil.PlusColor> unlockedColors = getRankColorsUnlockedAtLevel(safeLevel);
        if (!unlockedColors.isEmpty()) {
            String unlockLine = formatUnlockedRankColorLine(unlockedColors);
            if (!unlockLine.trim().isEmpty()) {
                lines.add(spaces(resolveUnlockLinePadding(unlockLine, safeLevel, unlockedColors)) + unlockLine);
                lines.add("");
            }
            String claimLine = ChatColor.YELLOW + "Claim your reward in the lobby!";
            lines.add(spaces(CLAIM_REWARD_LEFT_PADDING) + claimLine);
            lines.add("");
        }
        lines.add(ChatColor.WHITE.toString() + ChatColor.BOLD + FRAME_LINE);
        lines.add("");
        return lines;
    }

    public static String buildMessage(int level) {
        List<String> lines = buildLines(level);
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                message.append('\n');
            }
            message.append(lines.get(i));
        }
        return message.toString();
    }

    private static String spaces(int count) {
        int safeCount = Math.max(0, count);
        StringBuilder padding = new StringBuilder(safeCount);
        for (int i = 0; i < safeCount; i++) {
            padding.append(' ');
        }
        return padding.toString();
    }

    private static int resolveUnlockLinePadding(String line, int level, List<RankColorUtil.PlusColor> unlockedColors) {
        int base = resolveDynamicUnlockLinePadding(line, level);
        int extra = resolveUnlockLineExtraPadding(unlockedColors);
        return Math.max(0, base + extra);
    }

    private static int resolveDynamicUnlockLinePadding(String line, int level) {
        int visible = visibleLength(line);
        int referenceVisible = visibleLength(formatLevelSubtitleLine(level));
        int rightEdge = LEVEL_UP_SUBTITLE_LEFT_PADDING + referenceVisible;
        return Math.max(0, rightEdge - visible);
    }

    private static int resolveUnlockLineExtraPadding(List<RankColorUtil.PlusColor> colors) {
        if (colors == null || colors.isEmpty()) {
            return 0;
        }
        boolean hasLightPurple = false;
        boolean hasDarkRed = false;
        boolean hasDarkAqua = false;
        boolean hasDarkPurple = false;
        boolean hasDarkGray = false;
        for (RankColorUtil.PlusColor color : colors) {
            if (color == null || color.getId() == null) {
                continue;
            }
            String id = color.getId().trim().toLowerCase();
            if ("light_purple".equals(id)) {
                hasLightPurple = true;
            } else if ("dark_red".equals(id)) {
                hasDarkRed = true;
            } else if ("dark_aqua".equals(id)) {
                hasDarkAqua = true;
            } else if ("dark_purple".equals(id)) {
                hasDarkPurple = true;
            } else if ("dark_gray".equals(id)) {
                hasDarkGray = true;
            }
        }
        if (hasDarkRed && hasDarkAqua) {
            return 4;
        }
        if (hasDarkPurple && hasDarkGray) {
            return 4;
        }
        if (hasLightPurple) {
            return 2;
        }
        return 0;
    }

    private static String formatLevelSubtitleLine(int level) {
        int safeLevel = Math.max(0, level);
        return ChatColor.GRAY + "You are now "
                + ChatColor.DARK_AQUA + "Hypixel Level "
                + ChatColor.GREEN + safeLevel
                + ChatColor.GRAY + "!";
    }

    private static int visibleLength(String line) {
        if (line == null) {
            return 0;
        }
        String stripped = ChatColor.stripColor(line);
        return stripped == null ? 0 : stripped.length();
    }

    private static List<RankColorUtil.PlusColor> getRankColorsUnlockedAtLevel(int level) {
        int safeLevel = Math.max(0, level);
        List<RankColorUtil.PlusColor> unlocked = new ArrayList<>();
        if (safeLevel <= 0) {
            return unlocked;
        }
        for (RankColorUtil.PlusColor color : RankColorUtil.getAllPlusColors()) {
            if (color == null || color.isGiftedReward()) {
                continue;
            }
            if (color.getLevel() <= 0) {
                continue;
            }
            if (color.getLevel() == safeLevel) {
                unlocked.add(color);
            }
        }
        return unlocked;
    }

    private static String formatUnlockedRankColorLine(List<RankColorUtil.PlusColor> colors) {
        if (colors == null || colors.isEmpty()) {
            return "";
        }
        List<RankColorUtil.PlusColor> valid = new ArrayList<>();
        for (RankColorUtil.PlusColor color : colors) {
            if (color != null) {
                valid.add(color);
            }
        }
        if (valid.isEmpty()) {
            return "";
        }
        boolean plural = valid.size() > 1;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < valid.size(); i++) {
            RankColorUtil.PlusColor color = valid.get(i);
            if (i > 0) {
                if (i == valid.size() - 1) {
                    line.append(ChatColor.GRAY).append(" and ");
                } else {
                    line.append(ChatColor.GRAY).append(", ");
                }
            }
            line.append(color.getColor()).append(color.getDisplayName());
        }
        line.append(ChatColor.GRAY).append(plural ? " rank colors " : " rank color ")
                .append(ChatColor.GREEN).append("Unlocked")
                .append(ChatColor.GRAY).append("!");
        return line.toString();
    }
}
