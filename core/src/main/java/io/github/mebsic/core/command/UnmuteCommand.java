package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Punishment;
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

public class UnmuteCommand implements CommandExecutor {
    private final CorePlugin plugin;
    private final PunishmentService punishments;

    public UnmuteCommand(CorePlugin plugin, PunishmentService punishments) {
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
            sender.sendMessage(ChatColor.RED + "/unmute <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetUuid = target != null ? target.getUniqueId() : MojangApi.lookupUuid(args[0]);
        if (targetUuid == null) {
            Punishment active = punishments.getActivePunishmentByName(args[0], PunishmentType.MUTE);
            if (active != null) {
                targetUuid = active.getTargetUuid();
            }
        }
        if (targetUuid == null) {
            sender.sendMessage(ChatColor.RED + CommonMessages.PLAYER_NOT_FOUND_COMMAND);
            return true;
        }
        UUID actorUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        String actorName = sender.getName();
        boolean removed = punishments.unpunish(PunishmentType.MUTE, targetUuid, actorUuid, actorName);
        if (!removed) {
            sender.sendMessage(ChatColor.RED + "That player is not muted!");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }
}
