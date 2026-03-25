package io.github.mebsic.core.service;

import com.google.gson.Gson;
import io.github.mebsic.core.model.MuteReasonType;
import io.github.mebsic.core.model.Punishment;
import io.github.mebsic.core.model.PunishmentType;
import io.github.mebsic.core.store.PunishmentStore;
import io.github.mebsic.core.util.NetworkConstants;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

public class PunishmentService {
    private static final String PUNISHMENT_CHANNEL = "punishment_action";
    private static final String PUNISHMENT_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int PUNISHMENT_ID_LENGTH = 10;
    private static final String SUPPORT_HOST = "support." + NetworkConstants.DOMAIN;
    private static final String DEFAULT_SUPPORT_URL = "https://" + SUPPORT_HOST;
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+|www\\.\\S+)", Pattern.CASE_INSENSITIVE);
    private static final String BAN_BROADCAST_MESSAGE = ChatColor.RED.toString()
            + ChatColor.BOLD
            + "A player has been removed from your game for hacking or abuse. "
            + ChatColor.RESET
            + ChatColor.AQUA
            + "Thanks for reporting it!";
    private static final int MUTE_SEPARATOR_SPACES = 80;
    private static final String MUTE_SEPARATOR_LINE = buildMuteSeparatorLine();

    private final PunishmentStore store;
    private final Plugin plugin;
    private final PubSubService pubSub;
    private final Gson gson;
    private final String localServerId;

    public PunishmentService(PunishmentStore store) {
        this(store, null, null, null);
    }

    public PunishmentService(PunishmentStore store, Plugin plugin, PubSubService pubSub, String serverId) {
        this.store = store;
        this.plugin = plugin;
        this.pubSub = pubSub;
        this.gson = new Gson();
        this.localServerId = normalizeServerId(serverId);
        subscribeToPunishments();
    }

    public Punishment punish(PunishmentType type,
                             UUID targetUuid,
                             String targetName,
                             UUID actorUuid,
                             String actorName,
                             String reason) {
        return punish(type, targetUuid, targetName, actorUuid, actorName, reason, null);
    }

    public Punishment punish(PunishmentType type,
                             UUID targetUuid,
                             String targetName,
                             UUID actorUuid,
                             String actorName,
                             String reason,
                             Long durationMillis) {
        String id = generatePunishmentId(type);
        long createdAt = System.currentTimeMillis();
        Long expiresAt = null;
        if (durationMillis != null && durationMillis > 0L) {
            expiresAt = createdAt + durationMillis;
        }
        String storedReason = resolveStoredReason(type, reason);
        Punishment punishment = new Punishment(id, type, targetUuid, targetName, actorUuid, actorName, storedReason, createdAt, expiresAt, true);
        if (store != null) {
            store.save(punishment);
        }
        return punishment;
    }

    public boolean isBanned(UUID targetUuid) {
        return hasActive(targetUuid, PunishmentType.BAN);
    }

    public boolean isMuted(UUID targetUuid) {
        return hasActive(targetUuid, PunishmentType.MUTE);
    }

    public boolean hasActive(UUID targetUuid, PunishmentType type) {
        if (store == null) {
            return false;
        }
        return store.findActive(targetUuid, type) != null;
    }

    public Punishment getActivePunishment(UUID targetUuid, PunishmentType type) {
        if (store == null || targetUuid == null || type == null) {
            return null;
        }
        return store.findActive(targetUuid, type);
    }

    public Punishment getActivePunishmentByName(String targetName, PunishmentType type) {
        if (store == null || targetName == null || targetName.trim().isEmpty() || type == null) {
            return null;
        }
        return store.findActiveByName(targetName, type);
    }

    public Punishment getActiveMute(UUID targetUuid) {
        return getActivePunishment(targetUuid, PunishmentType.MUTE);
    }

    public boolean unpunish(PunishmentType type, UUID targetUuid) {
        if (store == null || targetUuid == null || type == null) {
            return false;
        }
        return store.deactivateActive(targetUuid, type) > 0;
    }

    public String formatMuteMessage(Punishment punishment) {
        String line = MUTE_SEPARATOR_LINE;
        String reason = punishment == null ? null : punishment.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            reason = "No reason provided.";
        }
        MuteReasonType reasonType = MuteReasonType.resolve(reason);
        if (reasonType != null) {
            reason = reasonType.getDescription();
        }
        Long expiresAt = punishment == null ? null : punishment.getExpiresAt();
        boolean permanent = expiresAt == null || expiresAt <= 0L;
        String reasonForHeader = formatMuteHeaderReason(reason);
        String header = permanent
                ? ChatColor.RED + "You are permanently muted on this server!\n"
                : ChatColor.RED + "You are currently muted for " + reasonForHeader + "\n";
        String time = "";
        if (!permanent) {
            String timeLeft = formatPrettyTimeLeft(expiresAt);
            time = ChatColor.GRAY + "Your mute will expire in " + ChatColor.RED + timeLeft + "\n";
        }
        String space = ChatColor.RESET + " " + "\n";
        String urlInfo = ChatColor.GRAY + "Find out more here: "
                + ChatColor.YELLOW + resolveMuteFindOutMoreUrl(reason) + "\n";
        String muteId = formatMuteId(punishment == null ? null : punishment.getId());
        String footer = ChatColor.GRAY + "Mute ID: " + ChatColor.WHITE + muteId;
        return line + header + time + space + urlInfo + footer + line;
    }

    public String formatBanMessage(Punishment punishment) {
        return formatBanMessage(punishment, true);
    }

    public String formatBanMessage(Punishment punishment, boolean includeBanIdDetails) {
        String reason = punishment == null ? null : punishment.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            reason = "No reason provided.";
        }
        Long expiresAt = punishment == null ? null : punishment.getExpiresAt();
        boolean permanent = expiresAt == null || expiresAt <= 0L;
        String timeLeft = permanent ? "" : formatPrettyTimeLeft(expiresAt);

        String header = permanent
                ? ChatColor.RED + "You are permanently banned from this server!\n"
                : ChatColor.RED + "You are temporarily banned for " + ChatColor.WHITE + timeLeft
                + ChatColor.RED + " from this server!\n";

        String findOutMore = ChatColor.GRAY + "Find out more: " + ChatColor.AQUA + resolveSupportUrl(reason) + "\n";
        String message = header
                + "\n"
                + ChatColor.GRAY + "Reason: " + ChatColor.WHITE + reason + "\n"
                + findOutMore;
        if (!includeBanIdDetails) {
            return message;
        }
        String banId = punishment == null || punishment.getId() == null || punishment.getId().trim().isEmpty()
                ? "unknown"
                : punishment.getId();
        String footer = ChatColor.GRAY + "Sharing your Ban ID may affect the processing of your appeal!";
        return message
                + "\n"
                + ChatColor.GRAY + "Ban ID: " + ChatColor.WHITE + banId + "\n"
                + footer;
    }

    public String formatKickMessage(Punishment punishment) {
        String reason = punishment == null ? null : punishment.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            reason = "No reason provided.";
        }
        String header = ChatColor.RED + "You have been kicked from this server!\n";
        String findOutMore = ChatColor.GRAY + "Find out more: " + ChatColor.AQUA + resolveSupportUrl(reason) + "\n";
        return header
                + "\n"
                + ChatColor.GRAY + "Reason: " + ChatColor.WHITE + reason + "\n"
                + findOutMore;
    }

    public void dispatchPunishment(PunishmentType type,
                                   UUID targetUuid,
                                   String message,
                                   boolean announceBanRemoval) {
        if (type == null || targetUuid == null) {
            return;
        }
        PunishmentAction action = new PunishmentAction(
                localServerId,
                type.name(),
                targetUuid.toString(),
                message,
                announceBanRemoval
        );
        runOnMainThread(() -> applyActionLocally(action));
        if (pubSub != null) {
            try {
                pubSub.publish(PUNISHMENT_CHANNEL, gson.toJson(action));
            } catch (Exception ignored) {
                // Local enforcement already ran; ignore pub/sub failures.
            }
        }
    }

    private void subscribeToPunishments() {
        if (pubSub == null || plugin == null) {
            return;
        }
        pubSub.subscribe(PUNISHMENT_CHANNEL, this::handleRemoteAction);
    }

    private void handleRemoteAction(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }
        PunishmentAction action;
        try {
            action = gson.fromJson(payload, PunishmentAction.class);
        } catch (Exception ignored) {
            return;
        }
        if (action == null || action.type == null || action.targetUuid == null) {
            return;
        }
        if (action.serverId != null && action.serverId.equalsIgnoreCase(localServerId)) {
            return;
        }
        runOnMainThread(() -> applyActionLocally(action));
    }

    private void runOnMainThread(Runnable task) {
        if (task == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private void applyActionLocally(PunishmentAction action) {
        if (action == null) {
            return;
        }
        UUID targetUuid = parseUuid(action.targetUuid);
        PunishmentType type = parseType(action.type);
        if (targetUuid == null || type == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            return;
        }
        switch (type) {
            case BAN:
                target.kickPlayer(resolveBanKickMessage(action, targetUuid));
                if (action.announceBanRemoval) {
                    broadcastBanRemoval(target);
                }
                break;
            case KICK:
                target.kickPlayer(resolveKickMessage(action, targetUuid));
                break;
            case MUTE:
                target.sendMessage(resolveMuteMessage(action, targetUuid));
                break;
            default:
                break;
        }
    }

    private void broadcastBanRemoval(Player target) {
        if (target == null) {
            return;
        }
        for (Player online : target.getServer().getOnlinePlayers()) {
            if (online == null || !online.isOnline()) {
                continue;
            }
            online.sendMessage(BAN_BROADCAST_MESSAGE);
        }
    }

    private String resolveMessage(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value;
    }

    private String resolveBanKickMessage(PunishmentAction action, UUID targetUuid) {
        if (action != null && action.message != null && !action.message.trim().isEmpty()) {
            return action.message;
        }
        Punishment activeBan = getActivePunishment(targetUuid, PunishmentType.BAN);
        return formatBanMessage(activeBan);
    }

    private String resolveMuteMessage(PunishmentAction action, UUID targetUuid) {
        if (action != null && action.message != null && !action.message.trim().isEmpty()) {
            return action.message;
        }
        Punishment activeMute = getActivePunishment(targetUuid, PunishmentType.MUTE);
        return formatMuteMessage(activeMute);
    }

    private String resolveKickMessage(PunishmentAction action, UUID targetUuid) {
        if (action != null && action.message != null && !action.message.trim().isEmpty()) {
            return action.message;
        }
        Punishment activeKick = getActivePunishment(targetUuid, PunishmentType.KICK);
        return formatKickMessage(activeKick);
    }

    private String resolveStoredReason(PunishmentType type, String reason) {
        if (reason != null && !reason.trim().isEmpty()) {
            return reason;
        }
        if (type == PunishmentType.KICK) {
            return "Kicked by staff.";
        }
        return "No reason provided.";
    }

    private String generatePunishmentId(PunishmentType type) {
        if (type == PunishmentType.MUTE) {
            return Long.toString(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        }
        return generateAlphabeticPunishmentId();
    }

    private String generateAlphabeticPunishmentId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(PUNISHMENT_ID_LENGTH);
        for (int i = 0; i < PUNISHMENT_ID_LENGTH; i++) {
            int index = random.nextInt(PUNISHMENT_ID_ALPHABET.length());
            builder.append(PUNISHMENT_ID_ALPHABET.charAt(index));
        }
        return builder.toString();
    }

    private String resolveMuteFindOutMoreUrl(String reason) {
        MuteReasonType reasonType = MuteReasonType.resolve(reason);
        if (reasonType != null) {
            String url = reasonType.getFindOutMoreUrl();
            if (url != null && !url.trim().isEmpty()) {
                return url.trim();
            }
        }
        return NetworkConstants.WEBSITE + "/mutes";
    }

    private String resolveSupportUrl(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return DEFAULT_SUPPORT_URL;
        }
        Matcher matcher = URL_PATTERN.matcher(reason);
        while (matcher.find()) {
            String candidate = sanitizeUrl(matcher.group());
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (normalized.contains(SUPPORT_HOST)) {
                if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                    return candidate;
                }
                return "https://" + candidate;
            }
        }
        return DEFAULT_SUPPORT_URL;
    }

    private String sanitizeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.trim();
        while (!sanitized.isEmpty()) {
            char end = sanitized.charAt(sanitized.length() - 1);
            if (end == '.' || end == ',' || end == ')' || end == '(') {
                sanitized = sanitized.substring(0, sanitized.length() - 1);
                continue;
            }
            break;
        }
        return sanitized;
    }

    private String formatMuteHeaderReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return "an unspecified reason.";
        }
        String normalized = reason.trim();
        String verbosePrefix = "you have been muted for ";
        if (normalized.regionMatches(true, 0, verbosePrefix, 0, verbosePrefix.length())) {
            normalized = normalized.substring(verbosePrefix.length()).trim();
        }
        if (!normalized.isEmpty()
                && Character.isUpperCase(normalized.charAt(0))
                && (normalized.length() == 1 || Character.isLowerCase(normalized.charAt(1)))) {
            normalized = Character.toLowerCase(normalized.charAt(0)) + normalized.substring(1);
        }
        char end = normalized.charAt(normalized.length() - 1);
        if (end != '.' && end != '!' && end != '?') {
            normalized += ".";
        }
        return normalized;
    }

    private String formatMuteId(String storedId) {
        if (storedId == null || storedId.trim().isEmpty()) {
            return "unknown";
        }
        String normalized = storedId.trim();
        if (normalized.startsWith("#")) {
            return normalized;
        }
        return "#" + normalized;
    }

    private static String buildMuteSeparatorLine() {
        return "\n" + ChatColor.RED + ChatColor.STRIKETHROUGH
                + repeatSpaces(MUTE_SEPARATOR_SPACES)
                + ChatColor.RESET + "\n";
    }

    private static String repeatSpaces(int amount) {
        int safeAmount = Math.max(0, amount);
        StringBuilder builder = new StringBuilder(safeAmount);
        for (int i = 0; i < safeAmount; i++) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private String formatPrettyTimeLeft(Long expiresAt) {
        if (expiresAt == null || expiresAt <= 0L) {
            return "Permanent";
        }
        long remainingMillis = expiresAt - System.currentTimeMillis();
        long totalSeconds = Math.max(1L, remainingMillis / 1000L);
        long totalMinutes = totalSeconds / 60L;

        long days = totalMinutes / (24L * 60L);
        totalMinutes %= (24L * 60L);
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;

        StringBuilder out = new StringBuilder();
        if (days > 0L) {
            out.append(days).append("d");
        }
        if (hours > 0L) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(hours).append("h");
        }
        if (minutes > 0L || out.length() > 0) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(minutes).append("m");
        }
        if (out.length() > 0) {
            return out.toString();
        }
        return totalSeconds + "s";
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private PunishmentType parseType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return PunishmentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeServerId(String serverId) {
        if (serverId != null && !serverId.trim().isEmpty()) {
            return serverId.trim();
        }
        return "server-" + UUID.randomUUID();
    }

    private static class PunishmentAction {
        private String serverId;
        private String type;
        private String targetUuid;
        private String message;
        private boolean announceBanRemoval;

        private PunishmentAction() {
        }

        private PunishmentAction(String serverId,
                                 String type,
                                 String targetUuid,
                                 String message,
                                 boolean announceBanRemoval) {
            this.serverId = serverId;
            this.type = type;
            this.targetUuid = targetUuid;
            this.message = message;
            this.announceBanRemoval = announceBanRemoval;
        }
    }
}
