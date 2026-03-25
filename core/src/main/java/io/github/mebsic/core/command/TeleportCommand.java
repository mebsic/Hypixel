package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TeleportCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public TeleportCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        if (!RankUtil.hasAtLeast(plugin, player, Rank.STAFF)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("all")) {
            return teleportAllToSelf(player);
        }

        if (args.length == 1) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            player.teleport(target.getLocation());
            player.sendMessage(ChatColor.GREEN + "Teleported to " + target.getName() + ".");
            return true;
        }

        if (args.length == 2) {
            Player from = Bukkit.getPlayerExact(args[0]);
            Player to = Bukkit.getPlayerExact(args[1]);
            if (from == null || to == null) {
                player.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            from.teleport(to.getLocation());
            player.sendMessage(ChatColor.GREEN + "Teleported " + from.getName() + " to " + to.getName() + ".");
            return true;
        }

        player.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        player.sendMessage(ChatColor.RED + "/" + label + " <player>");
        player.sendMessage(ChatColor.RED + "/" + label + " <from> <to>");
        player.sendMessage(ChatColor.RED + "/" + label + " all");
        return true;
    }

    private boolean teleportAllToSelf(Player sender) {
        Location target = sender.getLocation();
        int moved = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(sender)) {
                continue;
            }
            online.teleport(target);
            moved++;
        }
        sender.sendMessage(ChatColor.GREEN + "Teleported " + moved + " " + (moved == 1 ? "player" : "players") + " to you.");
        return true;
    }
}
