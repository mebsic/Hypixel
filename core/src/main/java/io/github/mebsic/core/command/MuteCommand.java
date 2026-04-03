package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.MuteReasonType;
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

public class MuteCommand implements CommandExecutor {
    private static final DurationOption[] DURATION_OPTIONS = new DurationOption[] {
            new DurationOption("1d", "1 day", 1L * 24L * 60L * 60L * 1000L),
            new DurationOption("7d", "7 days", 7L * 24L * 60L * 60L * 1000L),
            new DurationOption("30d", "30 days", 30L * 24L * 60L * 60L * 1000L)
    };

    private final CorePlugin plugin;
    private final PunishmentService punishments;

    public MuteCommand(CorePlugin plugin, PunishmentService punishments) {
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
        if (args.length < 2) {
            sendInvalidUsage(sender);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetUuid = target != null ? target.getUniqueId() : MojangApi.lookupUuid(args[0]);
        String targetName = target != null ? target.getName() : args[0];
        if (targetUuid == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return true;
        }
        if (sender instanceof Player && targetUuid.equals(((Player) sender).getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You cannot mute yourself!");
            return true;
        }
        if (punishments.isMuted(targetUuid)) {
            sender.sendMessage(ChatColor.RED + "That player is already muted!");
            return true;
        }
        DurationOption duration = null;
        int reasonIndex = 1;
        if (args.length > 1) {
            duration = parseDuration(args[1]);
            if (duration != null) {
                reasonIndex = 2;
            } else if (looksLikeDuration(args[1])) {
                sendAvailableDurations(sender);
                return true;
            }
        }
        if (args.length <= reasonIndex || args.length > reasonIndex + 1) {
            sendInvalidUsage(sender);
            return true;
        }
        MuteReasonType reasonType = MuteReasonType.fromCode(args[reasonIndex]);
        if (reasonType == null) {
            sendAvailableReasonTypes(sender);
            return true;
        }
        String reason = reasonType.getDescription();
        UUID actorUuid = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        String actorName = sender.getName();
        Punishment punishment = punishments.punish(
                PunishmentType.MUTE,
                targetUuid,
                targetName,
                actorUuid,
                actorName,
                reason,
                duration == null ? null : duration.durationMillis
        );
        String targetMuteMessage = punishments.formatMuteMessage(punishment);
        punishments.dispatchPunishment(PunishmentType.MUTE, targetUuid, targetMuteMessage, false);
        sender.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }

    private DurationOption parseDuration(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        for (DurationOption option : DURATION_OPTIONS) {
            if (option.token.equals(normalized)) {
                return option;
            }
        }
        return null;
    }

    private boolean looksLikeDuration(String value) {
        if (value == null) {
            return false;
        }
        return value.trim().matches("(?i)^\\d+d$");
    }

    private void sendAvailableDurations(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Unknown duration! Available durations:");
        for (DurationOption option : DURATION_OPTIONS) {
            sender.sendMessage(ChatColor.RED + option.token);
        }
    }

    private void sendInvalidUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        sender.sendMessage(ChatColor.RED + "/mute <player> [duration] <reasonCode>");
        sender.sendMessage(ChatColor.RED + "Available durations:");
        for (DurationOption option : DURATION_OPTIONS) {
            sender.sendMessage(ChatColor.RED + option.token);
        }
        sender.sendMessage(ChatColor.RED + "Available reasons:");
        for (MuteReasonType type : MuteReasonType.values()) {
            sender.sendMessage(ChatColor.RED + type.getCode() + " (" + type.name() + ")");
        }
    }

    private void sendAvailableReasonTypes(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Unknown reason! Available reasons:");
        for (MuteReasonType type : MuteReasonType.values()) {
            sender.sendMessage(ChatColor.RED + type.getCode() + " (" + type.name() + ")");
        }
    }

    private static class DurationOption {
        private final String token;
        private final String display;
        private final long durationMillis;

        private DurationOption(String token, String display, long durationMillis) {
            this.token = token;
            this.display = display;
            this.durationMillis = durationMillis;
        }
    }
}
