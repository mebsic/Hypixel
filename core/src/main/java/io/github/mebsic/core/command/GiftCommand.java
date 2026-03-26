package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.menu.GiftMenu;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GiftCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public GiftCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command!");
            return true;
        }
        Player player = (Player) sender;
        ServerType serverType = plugin == null ? null : plugin.getServerType();
        if (serverType == null || !serverType.isHub()) {
            player.sendMessage(ChatColor.RED + "You can only gift players in a lobby!");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Usage: /gift <player>");
            return true;
        }

        String targetName = args[0] == null ? "" : args[0].trim();
        Player target = findLocalPlayer(targetName);
        if (target == null || !target.isOnline()) {
            if (plugin != null && plugin.isKnownPlayerName(targetName)) {
                player.sendMessage(ChatColor.RED + "That player is not in your lobby!");
            } else {
                player.sendMessage(ChatColor.RED + "Unknown player '" + targetName + "'!");
            }
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You can't send gifts to yourself!");
            return true;
        }

        new GiftMenu(plugin, target).open(player);
        return true;
    }

    private Player findLocalPlayer(String targetName) {
        if (targetName == null || targetName.trim().isEmpty()) {
            return null;
        }
        String trimmed = targetName.trim();
        Player exact = Bukkit.getPlayerExact(trimmed);
        if (exact != null) {
            return exact;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null || online.getName() == null) {
                continue;
            }
            if (online.getName().equalsIgnoreCase(trimmed)) {
                return online;
            }
        }
        return null;
    }
}
