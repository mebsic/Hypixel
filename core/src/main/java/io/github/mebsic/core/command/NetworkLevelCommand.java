package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.ProfileCommandSyncService;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class NetworkLevelCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public NetworkLevelCommand(CorePlugin plugin) {
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
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
            sender.sendMessage(ChatColor.RED + "/networklevel <player> <level>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID uuid = target != null ? target.getUniqueId() : MojangApi.lookupUuid(args[0]);
        String name = target != null ? target.getName() : args[0];
        if (uuid == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        int level;
        try {
            level = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Network level must be a number.");
            return true;
        }
        if (level < 0) {
            sender.sendMessage(ChatColor.RED + "Network level must be 0 or higher.");
            return true;
        }
        boolean selfTarget = sender instanceof Player
                && uuid.equals(((Player) sender).getUniqueId());
        String targetMessage = formatNetworkLevelSetMessage(level);
        if (target != null) {
            plugin.setNetworkLevel(uuid, level);
            target.sendMessage(targetMessage);
        } else {
            if (!plugin.isMongoEnabled() || plugin.getProfileStore() == null) {
                sender.sendMessage(ChatColor.RED + "MongoDB is not enabled.");
                return true;
            }
            plugin.getProfileStore().updateNetworkLevel(uuid, name, level);
            if (selfTarget && sender instanceof Player) {
                ((Player) sender).sendMessage(targetMessage);
            }
        }
        ProfileCommandSyncService sync = plugin.getProfileCommandSyncService();
        if (sync != null) {
            sync.dispatchNetworkLevelUpdate(uuid, level, targetMessage);
        }
        if (!selfTarget) {
            sender.sendMessage(ChatColor.GREEN + "Set network level for " + name + " to " + level + ".");
        }
        return true;
    }

    private String formatNetworkLevelSetMessage(int level) {
        return ChatColor.GREEN + "You are now Hypixel level " + level + "!";
    }
}
