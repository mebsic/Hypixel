package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.proxy.service.RankResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RestartCommand implements SimpleCommand {
    private static final DurationOption[] DURATION_OPTIONS = new DurationOption[]{
            new DurationOption("1m", "1 minute", 60,
                    new String[]{"1m", "1", "1min", "1mins", "1minute", "1minutes", "60", "60s", "60sec", "60secs", "60second", "60seconds"}),
            new DurationOption("30s", "30 seconds", 30,
                    new String[]{"30", "30s", "30sec", "30secs", "30second", "30seconds"}),
            new DurationOption("10s", "10 seconds", 10,
                    new String[]{"10", "10s", "10sec", "10secs", "10second", "10seconds"})
    };
    private static final int[] BROADCAST_INTERVALS_SECONDS = new int[]{60, 30, 10, 5, 4, 3, 2, 1};
    private static final long SHUTDOWN_DELAY_SECONDS = 2L;
    private static final String ROLLOUT_WEBHOOK_URL_ENV = "ROLLOUT_WEBHOOK_URL";
    private static final String ROLLOUT_WEBHOOK_TOKEN_ENV = "ROLLOUT_WEBHOOK_TOKEN";
    private static final String TARGET_SERVICE = "velocity";
    private static final String WARP_COMMAND = "/hub";
    private final ProxyServer proxy;
    private final Object plugin;
    private final RankResolver rankResolver;
    private final Logger logger;
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    private final List<ScheduledTask> scheduledTasks = new CopyOnWriteArrayList<ScheduledTask>();

    public RestartCommand(ProxyServer proxy,
                          Object plugin,
                          RankResolver rankResolver,
                          Logger logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.rankResolver = rankResolver;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        if (rankResolver == null || !rankResolver.isStaff(player.getUniqueId())) {
            player.sendMessage(Component.text(CommonMessages.NO_PERMISSION_COMMAND, NamedTextColor.RED));
            return;
        }

        ParsedInput parsedInput = parseInput(invocation.arguments());
        if (parsedInput == null) {
            sendInvalidUsage(player);
            return;
        }
        DurationOption duration = parsedInput.duration;
        RestartReason reason = parsedInput.reason;

        if (!restartInProgress.compareAndSet(false, true)) {
            player.sendMessage(Component.text("A restart has already started!", NamedTextColor.RED));
            return;
        }

        cancelScheduledTasks();
        player.sendMessage(Component.text("Done! ", NamedTextColor.GREEN)
                .append(Component.text("(" + duration.display + ", " + reason.name() + ")", NamedTextColor.DARK_GRAY)));
        scheduleBroadcasts(duration.totalSeconds, reason);
        scheduleFinalRestart(duration.totalSeconds, reason);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args == null || args.length <= 1) {
            String prefix = normalizeToken(args == null || args.length == 0 ? "" : args[0]);
            List<String> suggestions = new ArrayList<String>();
            for (DurationOption option : DURATION_OPTIONS) {
                if (prefix.isEmpty() || option.token.startsWith(prefix)) {
                    suggestions.add(option.token);
                }
            }
            return suggestions;
        }

        String reasonPrefix = normalizeReasonToken(joinArgs(args, 1));
        String reasonCompactPrefix = compactReasonToken(reasonPrefix);
        List<String> suggestions = new ArrayList<String>();
        for (RestartReason reason : RestartReason.values()) {
            if (reasonPrefix.isEmpty()
                    || reason.token.startsWith(reasonPrefix)
                    || reason.compactToken.startsWith(reasonCompactPrefix)) {
                suggestions.add(reason.token);
            }
        }
        return suggestions;
    }

    private ParsedInput parseInput(String[] args) {
        ParsedDuration parsedDuration = parseDurationAndReasonIndex(args);
        if (parsedDuration == null) {
            return null;
        }

        String joinedReason = joinArgs(args, parsedDuration.reasonStartIndex);
        RestartReason reason = RestartReason.fromInput(joinedReason);
        if (reason == null) {
            return null;
        }
        return new ParsedInput(parsedDuration.duration, reason);
    }

    private ParsedDuration parseDurationAndReasonIndex(String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        if (args.length >= 3) {
            DurationOption twoTokenDuration = findDurationOption(args[0] + " " + args[1]);
            if (twoTokenDuration != null) {
                return new ParsedDuration(twoTokenDuration, 2);
            }
        }
        DurationOption oneTokenDuration = findDurationOption(args[0]);
        if (oneTokenDuration == null) {
            return null;
        }
        return new ParsedDuration(oneTokenDuration, 1);
    }

    private DurationOption findDurationOption(String rawDuration) {
        String normalized = normalizeDurationToken(rawDuration);
        if (normalized.isEmpty()) {
            return null;
        }
        for (DurationOption option : DURATION_OPTIONS) {
            if (option.matches(normalized)) {
                return option;
            }
        }
        return null;
    }

    private void sendInvalidUsage(Player player) {
        player.sendMessage(Component.text("Invalid usage! Correct usage:", NamedTextColor.RED));
        player.sendMessage(Component.text("/restart <duration> <reason>", NamedTextColor.RED));
        player.sendMessage(Component.text("Available durations:", NamedTextColor.RED));
        for (DurationOption option : DURATION_OPTIONS) {
            player.sendMessage(Component.text(option.token, NamedTextColor.RED));
        }
        player.sendMessage(Component.text("Available reasons:", NamedTextColor.RED));
        for (RestartReason reason : RestartReason.values()) {
            player.sendMessage(Component.text(reason.token, NamedTextColor.RED));
        }
    }

    private void scheduleBroadcasts(int totalSeconds, RestartReason reason) {
        for (int interval : BROADCAST_INTERVALS_SECONDS) {
            if (interval > totalSeconds) {
                continue;
            }
            long delaySeconds = Math.max(0L, totalSeconds - interval);
            final int secondsRemaining = interval;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> broadcastRestartSoon(secondsRemaining, reason))
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
    }

    private void broadcastRestartSoon(int secondsRemaining, RestartReason reason) {
        Component firstLine = Component.text("[Important] ", NamedTextColor.RED)
                .append(Component.text("This server will restart soon: ", NamedTextColor.YELLOW))
                .append(Component.text(reason.broadcastReason, NamedTextColor.AQUA));
        Component clickableWarp = Component.text("CLICK", NamedTextColor.GREEN, TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(WARP_COMMAND))
                .hoverEvent(HoverEvent.showText(Component.text("Click to warp to a hub now.", NamedTextColor.YELLOW)));
        Component secondLine = Component.text("You have ", NamedTextColor.YELLOW)
                .append(Component.text(formatTimeRemaining(secondsRemaining), NamedTextColor.GREEN))
                .append(Component.text(" to warp out! ", NamedTextColor.YELLOW))
                .append(clickableWarp)
                .append(Component.text(" to warp now!", NamedTextColor.YELLOW));
        broadcastFramed(firstLine.append(Component.newline()).append(secondLine));
    }

    private String formatTimeRemaining(int secondsRemaining) {
        if (secondsRemaining >= 60) {
            int minutes = secondsRemaining / 60;
            if (minutes == 1) {
                return "1 minute";
            }
            return minutes + " minutes";
        }
        return secondsRemaining + (secondsRemaining == 1 ? " second" : " seconds");
    }

    private void broadcastFramed(Component line) {
        Component empty = Component.text("");
        for (Player online : proxy.getAllPlayers()) {
            if (online == null) {
                continue;
            }
            online.sendMessage(empty);
            online.sendMessage(line);
            online.sendMessage(empty);
        }
    }

    private void scheduleFinalRestart(int totalSeconds, RestartReason reason) {
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> restartNow(reason))
                .delay(totalSeconds, TimeUnit.SECONDS)
                .schedule();
        scheduledTasks.add(task);
    }

    private void restartNow(RestartReason reason) {
        restartInProgress.set(false);
        // Avoid cancelling the currently executing restart task; that can interrupt
        // the rollout webhook HTTP call before it completes.
        scheduledTasks.clear();
        Component disconnectReason = Component.text("This server is restarting!", NamedTextColor.RED);
        for (Player online : proxy.getAllPlayers()) {
            try {
                online.disconnect(disconnectReason);
            } catch (Exception ex) {
                logger.warn("Failed to disconnect {} during /restart shutdown!", online.getUsername(), ex);
            }
        }
        triggerContainerRestart();
        try {
            Thread.sleep(SHUTDOWN_DELAY_SECONDS * 1000L);
        } catch (InterruptedException ignored) {
            // Expected when the container is already stopping due to external restart.
        }
        try {
            proxy.shutdown();
        } catch (Exception ex) {
            logger.warn("Failed to shutdown proxy cleanly after /restart!", ex);
        }
    }

    private void triggerContainerRestart() {
        String webhookUrl = safeTrim(System.getenv(ROLLOUT_WEBHOOK_URL_ENV));
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.info("No rollout webhook URL configured; skipping scoped restart trigger.");
            return;
        }

        String token = safeTrim(System.getenv(ROLLOUT_WEBHOOK_TOKEN_ENV));
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            String payload = "{\"mode\":\"rebuild\",\"services\":[\"" + TARGET_SERVICE + "\"]}";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (token != null && !token.isEmpty()) {
                builder.header("X-Rollout-Token", token);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                logger.info("Triggered scoped rollout restart for {} via webhook (status {}).", TARGET_SERVICE, status);
            } else {
                logger.warn("Scoped rollout webhook responded with status {}: {}", status, response.body());
            }
        } catch (InterruptedException ex) {
            logger.info("Scoped rollout webhook request interrupted (proxy likely restarting).");
        } catch (Exception ex) {
            logger.warn("Failed to trigger scoped rollout webhook for /restart!", ex);
        }
    }

    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    public boolean cancelRestart() {
        boolean hadActiveRestart = restartInProgress.getAndSet(false);
        cancelScheduledTasks();
        return hadActiveRestart;
    }

    private void cancelScheduledTasks() {
        for (ScheduledTask task : scheduledTasks) {
            if (task == null) {
                continue;
            }
            try {
                task.cancel();
            } catch (Exception ex) {
                logger.warn("Failed to cancel scheduled /restart task!", ex);
            }
        }
        scheduledTasks.clear();
    }

    private String joinArgs(String[] args, int startIndex) {
        if (args == null || args.length <= startIndex) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            String trimmed = arg.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(trimmed);
        }
        return joined.toString();
    }

    private String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDurationToken(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                normalized.append(Character.toLowerCase(c));
            }
        }
        return normalized.toString();
    }

    private static String normalizeReasonToken(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        boolean previousSeparator = true;
        char previousRaw = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (Character.isUpperCase(c)
                        && normalized.length() > 0
                        && !previousSeparator
                        && Character.isLowerCase(previousRaw)) {
                    normalized.append('_');
                }
                normalized.append(Character.toLowerCase(c));
                previousSeparator = false;
            } else {
                if (!previousSeparator && normalized.length() > 0) {
                    normalized.append('_');
                }
                previousSeparator = true;
            }
            previousRaw = c;
        }
        while (normalized.length() > 0 && normalized.charAt(normalized.length() - 1) == '_') {
            normalized.deleteCharAt(normalized.length() - 1);
        }
        while (normalized.length() > 0 && normalized.charAt(0) == '_') {
            normalized.deleteCharAt(0);
        }
        return normalized.toString();
    }

    private static String compactReasonToken(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        return token.replace("_", "");
    }

    private static final class DurationOption {
        private final String token;
        private final String display;
        private final int totalSeconds;
        private final String[] aliases;

        private DurationOption(String token, String display, int totalSeconds, String[] aliases) {
            this.token = token;
            this.display = display;
            this.totalSeconds = totalSeconds;
            this.aliases = aliases == null ? new String[0] : aliases;
        }

        private boolean matches(String normalized) {
            if (normalized == null || normalized.isEmpty()) {
                return false;
            }
            if (token.equals(normalized)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias != null && alias.equalsIgnoreCase(normalized)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class ParsedDuration {
        private final DurationOption duration;
        private final int reasonStartIndex;

        private ParsedDuration(DurationOption duration, int reasonStartIndex) {
            this.duration = duration;
            this.reasonStartIndex = reasonStartIndex;
        }
    }

    private static final class ParsedInput {
        private final DurationOption duration;
        private final RestartReason reason;

        private ParsedInput(DurationOption duration, RestartReason reason) {
            this.duration = duration;
            this.reason = reason;
        }
    }

    public enum RestartReason {
        GAME_UPDATE("game_update", "For a game update"),
        BUG_FIX("bug_fix", "For a bug fix"),
        SECURITY_PATCH("security_patch", "For a security patch"),
        EMERGENCY_RESTART("emergency_restart", "For an emergency restart");

        private final String token;
        private final String compactToken;
        private final String broadcastReason;

        RestartReason(String token, String broadcastReason) {
            this.token = token;
            this.compactToken = compactReasonToken(token);
            this.broadcastReason = broadcastReason;
        }

        public static RestartReason fromInput(String rawInput) {
            String normalized = normalizeReasonToken(rawInput);
            if (normalized.isEmpty()) {
                return null;
            }
            String compact = compactReasonToken(normalized);
            for (RestartReason reason : values()) {
                if (reason.token.equals(normalized) || reason.compactToken.equals(compact)) {
                    return reason;
                }
            }
            return null;
        }
    }
}
