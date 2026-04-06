package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Punishment;
import io.github.mebsic.core.model.PunishmentType;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.PunishmentService;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PunishmentHistoryCommand implements CommandExecutor {
    private static final int PAGE_SIZE = 10;
    private static final ZoneId EASTERN_TIME_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter EASTERN_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a z", Locale.US);

    private final CorePlugin plugin;
    private final PunishmentService punishments;
    private final PunishmentType type;
    private final String commandName;
    private final String historyTitle;
    private final String emptyLabel;
    private final String temporaryLabel;
    private final String permanentLabel;

    public PunishmentHistoryCommand(CorePlugin plugin, PunishmentService punishments, PunishmentType type) {
        this.plugin = plugin;
        this.punishments = punishments;
        this.type = type;
        if (type == PunishmentType.BAN) {
            this.commandName = "bans";
            this.historyTitle = "Bans";
            this.emptyLabel = "bans";
            this.temporaryLabel = "Temp Ban";
            this.permanentLabel = "Ban";
            return;
        }
        if (type == PunishmentType.MUTE) {
            this.commandName = "mutes";
            this.historyTitle = "Mutes";
            this.emptyLabel = "mutes";
            this.temporaryLabel = "Temp Mute";
            this.permanentLabel = "Mute";
            return;
        }
        throw new IllegalArgumentException("Unsupported punishment history type: " + type);
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
            sendInvalidUsage(sender);
            return true;
        }

        String targetArgument = args[0] == null ? "" : args[0].trim();
        if (targetArgument.isEmpty()) {
            sendInvalidUsage(sender);
            return true;
        }

        int page = 1;
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                sendInvalidUsage(sender);
                return true;
            }
            if (page <= 0) {
                sendInvalidUsage(sender);
                return true;
            }
        }

        Player targetPlayer = Bukkit.getPlayerExact(targetArgument);
        UUID targetUuid = targetPlayer != null ? targetPlayer.getUniqueId() : MojangApi.lookupUuid(targetArgument);
        String displayTargetName = targetPlayer != null ? targetPlayer.getName() : targetArgument;

        PunishmentService.PunishmentHistoryPage history = punishments.getPunishmentHistory(
                targetUuid,
                targetArgument,
                type,
                page,
                PAGE_SIZE
        );
        if (history.getTotalEntries() <= 0L) {
            sender.sendMessage(ChatColor.RED + "That player has no " + emptyLabel + "!");
            return true;
        }
        if (page > history.getTotalPages()) {
            sender.sendMessage(ChatColor.RED + "That page does not exist!");
            return true;
        }

        String storedTargetName = firstStoredTargetName(history.getEntries());
        if (storedTargetName != null) {
            displayTargetName = storedTargetName;
        }

        sender.sendMessage(ChatColor.GOLD + "----- " + historyTitle + " for " + displayTargetName
                + " (Page " + history.getPage() + " of " + history.getTotalPages() + ") -----");

        int index = 1;
        List<Punishment> entries = history.getEntries();
        for (Punishment punishment : entries) {
            sender.sendMessage(formatEntry(index, punishment));
            index++;
        }
        return true;
    }

    private String formatEntry(int index, Punishment punishment) {
        String reason = trimTrailingPeriods(punishment == null ? null : punishment.getReason());
        if (reason == null || reason.trim().isEmpty()) {
            reason = "No reason provided";
        }

        String actorName = punishment == null ? null : punishment.getActorName();
        if (actorName == null || actorName.trim().isEmpty()) {
            actorName = "Console";
        }

        boolean temporary = isTemporary(punishment);
        String statusSuffix = formatStatusSuffix(punishment, temporary);
        String label = temporary ? temporaryLabel : permanentLabel;
        long createdAt = punishment == null ? 0L : punishment.getCreatedAt();

        return ChatColor.GOLD + Integer.toString(index) + ". "
                + ChatColor.YELLOW + label + " - " + formatEasternDate(createdAt)
                + ChatColor.WHITE + ChatColor.ITALIC + " " + reason
                + ChatColor.YELLOW + " by " + actorName + statusSuffix;
    }

    private String formatEasternDate(long epochMillis) {
        long safeEpochMillis = Math.max(0L, epochMillis);
        return EASTERN_DATE_FORMAT.format(Instant.ofEpochMilli(safeEpochMillis).atZone(EASTERN_TIME_ZONE));
    }

    private boolean isTemporary(Punishment punishment) {
        if (punishment == null) {
            return false;
        }
        Long expiresAt = punishment.getExpiresAt();
        return expiresAt != null && expiresAt > 0L;
    }

    private String formatDurationSuffix(Punishment punishment) {
        if (punishment == null || punishment.getExpiresAt() == null) {
            return "";
        }
        long expiresAt = punishment.getExpiresAt();
        long durationMillis = expiresAt - punishment.getCreatedAt();
        if (durationMillis <= 0L) {
            return "";
        }
        long remainingMillis = expiresAt - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            return " for " + formatDuration(durationMillis) + " Expired";
        }
        return " for " + formatDuration(durationMillis) + " expires in " + formatDuration(remainingMillis);
    }

    private String formatStatusSuffix(Punishment punishment, boolean temporary) {
        String deactivationSuffix = formatDeactivationSuffix(punishment);
        if (!deactivationSuffix.isEmpty()) {
            return deactivationSuffix;
        }
        return temporary ? formatDurationSuffix(punishment) : "";
    }

    private String formatDeactivationSuffix(Punishment punishment) {
        if (punishment == null || punishment.isActive()) {
            return "";
        }
        String deactivatedByName = punishment.getDeactivatedByName();
        Long deactivatedAt = punishment.getDeactivatedAt();
        if (deactivatedByName == null || deactivatedByName.trim().isEmpty() || deactivatedAt == null || deactivatedAt <= 0L) {
            return "";
        }
        String normalizedName = deactivatedByName.trim();
        if (type == PunishmentType.BAN) {
            return " Unbanned by " + normalizedName;
        }
        if (type == PunishmentType.MUTE) {
            return " Unmuted by " + normalizedName;
        }
        return "";
    }

    private String formatDuration(long durationMillis) {
        long totalMinutes = Math.max(1L, durationMillis / 60000L);
        long days = totalMinutes / (24L * 60L);
        totalMinutes %= (24L * 60L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;

        StringBuilder builder = new StringBuilder();
        appendDurationPart(builder, days, "Day");
        appendDurationPart(builder, hours, "Hour");
        appendDurationPart(builder, minutes, "Minute");
        if (builder.length() == 0) {
            return "1 Minute";
        }
        return builder.toString();
    }

    private void appendDurationPart(StringBuilder builder, long value, String unit) {
        if (value <= 0L) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value).append(' ').append(unit);
        if (value != 1L) {
            builder.append('s');
        }
    }

    private String firstStoredTargetName(List<Punishment> punishmentsList) {
        if (punishmentsList == null) {
            return null;
        }
        for (Punishment punishment : punishmentsList) {
            if (punishment == null || punishment.getTargetName() == null) {
                continue;
            }
            String name = punishment.getTargetName().trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    private String trimTrailingPeriods(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private void sendInvalidUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        sender.sendMessage(ChatColor.RED + "/" + commandName + " <player> [page]");
    }
}
