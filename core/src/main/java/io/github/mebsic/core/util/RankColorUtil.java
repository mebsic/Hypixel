package io.github.mebsic.core.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RankColorUtil {
    public static final String MVP_PLUS_PLUS_PREFIX_GOLD = "gold";
    public static final String MVP_PLUS_PLUS_PREFIX_AQUA = "aqua";

    private RankColorUtil() {
    }

    private static final PlusColor[] PLUS_COLORS = new PlusColor[]{
            new PlusColor("red", "Red", 0, ChatColor.RED, false),
            new PlusColor("gold", "Gold", 35, ChatColor.GOLD, false),
            new PlusColor("green", "Green", 45, ChatColor.GREEN, false),
            new PlusColor("yellow", "Yellow", 55, ChatColor.YELLOW, false),
            new PlusColor("light_purple", "Light Purple", 65, ChatColor.LIGHT_PURPLE, false),
            new PlusColor("white", "White", 75, ChatColor.WHITE, false),
            new PlusColor("blue", "Blue", 85, ChatColor.BLUE, false),
            new PlusColor("dark_green", "Dark Green", 95, ChatColor.DARK_GREEN, false),
            new PlusColor("dark_red", "Dark Red", 150, ChatColor.DARK_RED, false),
            new PlusColor("dark_aqua", "Dark Aqua", 150, ChatColor.DARK_AQUA, false),
            new PlusColor("dark_purple", "Dark Purple", 200, ChatColor.DARK_PURPLE, false),
            new PlusColor("dark_gray", "Dark Gray", 200, ChatColor.DARK_GRAY, false),
            new PlusColor("black", "Black", 250, ChatColor.BLACK, false),
            new PlusColor("dark_blue", "Dark Blue", 0, ChatColor.DARK_BLUE, true)
    };

    public static ChatColor getPlusColor(int networkLevel) {
        return getPlusColor(networkLevel, null);
    }

    public static ChatColor getPlusColor(int networkLevel, String selectedId) {
        int level = Math.max(0, networkLevel);
        PlusColor best = PLUS_COLORS[0];
        for (PlusColor entry : PLUS_COLORS) {
            if (!entry.giftedReward && level >= entry.level) {
                best = entry;
            }
        }
        if (selectedId != null) {
            PlusColor selected = getPlusColorById(selectedId);
            if (selected != null && (selected.giftedReward || level >= selected.level)) {
                return selected.color;
            }
        }
        return best.color;
    }

    public static String getEffectivePlusColorId(int networkLevel, String selectedId) {
        int level = Math.max(0, networkLevel);
        PlusColor best = PLUS_COLORS[0];
        for (PlusColor entry : PLUS_COLORS) {
            if (!entry.giftedReward && level >= entry.level) {
                best = entry;
            }
        }
        if (selectedId != null) {
            PlusColor selected = getPlusColorById(selectedId);
            if (selected != null && (selected.giftedReward || level >= selected.level)) {
                return selected.id;
            }
        }
        return best.id;
    }

    public static PlusColor getPlusColorById(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase();
        for (PlusColor entry : PLUS_COLORS) {
            if (entry.id.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    public static boolean isPlusColorUnlocked(PlusColor color, int networkLevel, boolean giftedRewardUnlocked) {
        if (color == null) {
            return false;
        }
        if (color.giftedReward) {
            return giftedRewardUnlocked;
        }
        return Math.max(0, networkLevel) >= color.level;
    }

    public static List<PlusColor> getAllPlusColors() {
        List<PlusColor> colors = new ArrayList<>();
        for (PlusColor entry : PLUS_COLORS) {
            colors.add(entry);
        }
        return Collections.unmodifiableList(colors);
    }

    public static String getPlusColorCode(ChatColor color) {
        if (color == null) {
            return "r";
        }
        switch (color) {
            case RED:
                return "r";
            case GOLD:
                return "g";
            case GREEN:
                return "gn";
            case YELLOW:
                return "y";
            case LIGHT_PURPLE:
                return "lp";
            case WHITE:
                return "w";
            case BLUE:
                return "b";
            case DARK_AQUA:
                return "da";
            case DARK_GREEN:
                return "dg";
            case DARK_BLUE:
                return "db";
            case DARK_RED:
                return "dr";
            case DARK_PURPLE:
                return "dp";
            case DARK_GRAY:
                return "dgr";
            case BLACK:
                return "bk";
            default:
                return "r";
        }
    }

    public static String getEffectiveMvpPlusPlusPrefixColorId(String selectedId) {
        if (selectedId == null) {
            return MVP_PLUS_PLUS_PREFIX_GOLD;
        }
        String normalized = selectedId.trim().toLowerCase();
        if (MVP_PLUS_PLUS_PREFIX_AQUA.equals(normalized)) {
            return MVP_PLUS_PLUS_PREFIX_AQUA;
        }
        return MVP_PLUS_PLUS_PREFIX_GOLD;
    }

    public static ChatColor getMvpPlusPlusPrefixColor(String selectedId) {
        String effective = getEffectiveMvpPlusPlusPrefixColorId(selectedId);
        if (MVP_PLUS_PLUS_PREFIX_AQUA.equals(effective)) {
            return ChatColor.AQUA;
        }
        return ChatColor.GOLD;
    }

    public static String getMvpPlusPlusPrefixColorDisplayName(String selectedId) {
        String effective = getEffectiveMvpPlusPlusPrefixColorId(selectedId);
        if (MVP_PLUS_PLUS_PREFIX_AQUA.equals(effective)) {
            return "Aqua";
        }
        return "Gold";
    }

    public static final class PlusColor {
        private final String id;
        private final String displayName;
        private final int level;
        private final ChatColor color;
        private final boolean giftedReward;

        private PlusColor(String id, String displayName, int level, ChatColor color, boolean giftedReward) {
            this.id = id;
            this.displayName = displayName;
            this.level = level;
            this.color = color;
            this.giftedReward = giftedReward;
        }

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getLevel() {
            return level;
        }

        public ChatColor getColor() {
            return color;
        }

        public boolean isGiftedReward() {
            return giftedReward;
        }
    }
}
