package io.github.mebsic.murdermystery.game;

import org.bukkit.ChatColor;

public enum MurderMysteryRole {
    MURDERER(ChatColor.RED, "Murderer"),
    DETECTIVE(ChatColor.AQUA, "Detective"),
    HERO(ChatColor.AQUA, "Hero"),
    INNOCENT(ChatColor.GREEN, "Innocent");

    private final ChatColor color;
    private final String displayName;

    MurderMysteryRole(ChatColor color, String displayName) {
        this.color = color;
        this.displayName = displayName;
    }

    public ChatColor getColor() {
        return color;
    }

    public String getDisplayName() {
        return displayName;
    }
}
