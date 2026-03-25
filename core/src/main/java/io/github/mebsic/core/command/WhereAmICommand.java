package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Locale;

public class WhereAmICommand implements CommandExecutor {
    private final CorePlugin plugin;

    public WhereAmICommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String serverName = plugin.getConfig().getString("server.id", "");
        if (serverName == null || serverName.trim().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Cannot determine the current server! Please try again later.");
            return true;
        }
        String displayName = toDisplayServerName(serverName.trim(), plugin.getServerType());
        if (displayName.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Cannot determine the current server! Please try again later.");
            return true;
        }
        sender.sendMessage(ChatColor.AQUA + "You are currently connected to server " + ChatColor.GOLD + displayName);
        return true;
    }

    private String toDisplayServerName(String rawName, ServerType type) {
        if (rawName == null || rawName.isEmpty()) {
            return "";
        }
        ServerType serverType = type == null ? ServerType.UNKNOWN : type;
        if (serverType.isHub()) {
            return "lobby" + stripPrefix(rawName, "l", "lobby");
        }
        if (serverType.isGame()) {
            return "mini" + stripPrefix(rawName, "m", "mini");
        }
        return rawName;
    }

    private String stripPrefix(String value, String shortPrefix, String longPrefix) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(longPrefix)) {
            return trimmed.substring(longPrefix.length());
        }
        if (lower.startsWith(shortPrefix) && trimmed.length() > shortPrefix.length()) {
            return trimmed.substring(shortPrefix.length());
        }
        return trimmed;
    }
}
