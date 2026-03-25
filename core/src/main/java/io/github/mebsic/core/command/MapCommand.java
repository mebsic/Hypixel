package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class MapCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public MapCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        if (plugin.getServerType() != null && plugin.getServerType().isHub()) {
            sender.sendMessage(ChatColor.RED + "That command is only available during games!");
            return true;
        }
        Player player = (Player) sender;
        if (player.getWorld() == null || player.getWorld().getName() == null) {
            sender.sendMessage(ChatColor.RED + "Cannot determine the current map! Please try again later.");
            return true;
        }
        String rawWorldName = player.getWorld().getName();
        String worldName = plugin.getServerType() != null && plugin.getServerType().isBuild()
                ? rawWorldName
                : formatWorldName(rawWorldName);
        if (worldName.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Cannot determine the current map! Please try again later.");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "You are currently playing on " + ChatColor.YELLOW + worldName);
        return true;
    }

    private String formatWorldName(String rawWorldName) {
        if (rawWorldName == null || rawWorldName.trim().isEmpty()) {
            return "";
        }
        String spaced = rawWorldName.trim()
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("\\s+", " ")
                .trim();
        if (spaced.isEmpty()) {
            return "";
        }

        String[] parts = spaced.split(" ");
        StringBuilder formatted = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (formatted.length() > 0) {
                formatted.append(' ');
            }
            formatted.append(capitalize(part));
        }
        return formatted.toString();
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
