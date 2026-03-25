package io.github.mebsic.core.model;

import org.bukkit.ChatColor;

public enum Rank {
    DEFAULT(ChatColor.GRAY, "", 0),
    VIP(ChatColor.GREEN, ChatColor.GREEN + "[VIP] ", 1),
    VIP_PLUS(ChatColor.GREEN, ChatColor.GREEN + "[VIP" + ChatColor.GOLD + "+" + ChatColor.GREEN + "] ", 2),
    MVP(ChatColor.AQUA, ChatColor.AQUA + "[MVP] ", 3),
    MVP_PLUS(ChatColor.AQUA, ChatColor.AQUA + "[MVP" + ChatColor.RED + "+" + ChatColor.AQUA + "] ", 4),
    MVP_PLUS_PLUS(ChatColor.GOLD, ChatColor.GOLD + "[MVP" + ChatColor.RED + "++" + ChatColor.GOLD + "] ", 5),
    YOUTUBE(ChatColor.RED, ChatColor.RED + "[" + ChatColor.WHITE + "YOUTUBE" + ChatColor.RED + "] ", 6),
    STAFF(ChatColor.RED, ChatColor.RED + "[" + ChatColor.GOLD + "ዞ" + ChatColor.RED + "] ", 7);

    private final ChatColor color;
    private final String prefix;
    private final int weight;

    Rank(ChatColor color, String prefix, int weight) {
        this.color = color;
        this.prefix = prefix;
        this.weight = weight;
    }

    public ChatColor getColor() {
        return color;
    }

    public String getPrefix() {
        if (this == DEFAULT) {
            return "";
        }
        return prefix;
    }

    public int getWeight() {
        return weight;
    }

    public boolean isAtLeast(Rank other) {
        return this.weight >= other.weight;
    }

    public String formatName(String name) {
        return color + name;
    }
}
