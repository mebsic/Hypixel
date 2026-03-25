package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GamemodeCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public GamemodeCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!RankUtil.hasAtLeast(plugin, player, Rank.STAFF)) {
                player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
                return true;
            }
        }
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
            sender.sendMessage(ChatColor.RED + "/" + label + " <mode> [onlinePlayer]");
            return true;
        }
        GameMode mode = parseMode(args[0]);
        if (mode == null) {
            sender.sendMessage(ChatColor.RED + "Unknown gamemode. Use c, s, a, sp or creative, survival, adventure, spectator.");
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "That player must be online.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify a player.");
                return true;
            }
            target = (Player) sender;
        }
        target.setGameMode(mode);
        if (target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "Set your gamemode to " + formatMode(mode) + ".");
        } else {
            sender.sendMessage(ChatColor.GREEN + "Set " + target.getName() + "'s gamemode to " + formatMode(mode) + ".");
        }
        return true;
    }

    private GameMode parseMode(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        switch (value) {
            case "0":
            case "s":
            case "survival":
                return GameMode.SURVIVAL;
            case "1":
            case "c":
            case "creative":
                return GameMode.CREATIVE;
            case "2":
            case "a":
            case "adventure":
                return GameMode.ADVENTURE;
            case "3":
            case "sp":
            case "spec":
            case "spectator":
                return GameMode.SPECTATOR;
            default:
                return null;
        }
    }

    private String formatMode(GameMode mode) {
        return mode.name().toLowerCase();
    }
}
