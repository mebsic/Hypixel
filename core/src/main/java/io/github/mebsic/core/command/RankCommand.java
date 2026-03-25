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

import java.util.Locale;
import java.util.UUID;

public class RankCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public RankCommand(CorePlugin plugin) {
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
            sender.sendMessage(ChatColor.RED + "/rank <player> <rank>");
            return true;
        }
        Rank rank = parseRank(args[1]);
        if (rank == null) {
            sendAvailableRanks(sender);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID uuid = target != null ? target.getUniqueId() : MojangApi.lookupUuid(args[0]);
        String name = target != null ? target.getName() : args[0];
        if (uuid == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        boolean selfTarget = sender instanceof Player
                && uuid.equals(((Player) sender).getUniqueId());
        String targetMessage = formatRankSetMessage(rank);
        if (target != null) {
            plugin.setRank(uuid, rank);
            target.sendMessage(targetMessage);
        } else {
            if (!plugin.isMongoEnabled() || plugin.getProfileStore() == null) {
                sender.sendMessage(ChatColor.RED + "MongoDB is not enabled.");
                return true;
            }
            plugin.getProfileStore().updateRank(uuid, name, rank);
        }
        ProfileCommandSyncService sync = plugin.getProfileCommandSyncService();
        if (sync != null) {
            sync.dispatchRankUpdate(uuid, rank, targetMessage);
        }
        if (!selfTarget) {
            sender.sendMessage(ChatColor.GREEN + "Set rank for " + name + " to " + toDisplayRank(rank) + ".");
        }
        return true;
    }

    private String formatRankSetMessage(Rank rank) {
        return ChatColor.GREEN + "You are now " + toDisplayRank(rank) + ".";
    }

    private Rank parseRank(String value) {
        if (value == null) {
            return null;
        }
        String raw = value.trim().toUpperCase(Locale.ROOT).replace("-", "_");
        if (raw.equals("MVP++") || raw.equals("MVP_PLUS_PLUS")) {
            return Rank.MVP_PLUS_PLUS;
        }
        if (raw.equals("MVP+") || raw.equals("MVP_PLUS")) {
            return Rank.MVP_PLUS;
        }
        if (raw.equals("VIP+") || raw.equals("VIP_PLUS")) {
            return Rank.VIP_PLUS;
        }
        try {
            return Rank.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void sendAvailableRanks(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Unknown rank! Available ranks:");
        for (Rank rank : Rank.values()) {
            sender.sendMessage(ChatColor.RED + toDisplayRank(rank));
        }
    }

    private String toDisplayRank(Rank rank) {
        if (rank == null) {
            return "";
        }
        switch (rank) {
            case VIP_PLUS:
                return "VIP+";
            case MVP_PLUS:
                return "MVP+";
            case MVP_PLUS_PLUS:
                return "MVP++";
            default:
                return rank.name();
        }
    }
}
