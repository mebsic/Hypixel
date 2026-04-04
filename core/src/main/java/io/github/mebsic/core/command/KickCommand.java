package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.PunishmentType;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.PunishmentService;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class KickCommand implements CommandExecutor {
    private final CorePlugin plugin;
    private final PunishmentService punishments;

    public KickCommand(CorePlugin plugin, PunishmentService punishments) {
        this.plugin = plugin;
        this.punishments = punishments;
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
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
            sender.sendMessage(ChatColor.RED + "/kick <player> [reason]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetUuid = target != null ? target.getUniqueId() : MojangApi.lookupUuid(args[0]);
        String targetName = target != null ? target.getName() : args[0];
        if (targetUuid == null) {
            sender.sendMessage(ChatColor.RED + CommonMessages.PLAYER_NOT_FOUND_COMMAND);
            return true;
        }
        if (sender instanceof Player && targetUuid.equals(((Player) sender).getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You cannot kick yourself!");
            return true;
        }
        String reason = args.length > 1 ? join(args, 1) : "Kicked by Staff!";
        UUID actorUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        String actorName = sender.getName();
        io.github.mebsic.core.model.Punishment punishment = punishments.punish(
                PunishmentType.KICK,
                targetUuid,
                targetName,
                actorUuid,
                actorName,
                reason
        );
        String targetKickMessage = punishments.formatKickMessage(punishment);
        punishments.dispatchPunishment(PunishmentType.KICK, targetUuid, targetKickMessage, false);
        sender.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
