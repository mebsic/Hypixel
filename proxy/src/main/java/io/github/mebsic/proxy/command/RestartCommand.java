package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.proxy.HycopyProxyPlugin;
import io.github.mebsic.proxy.service.RankResolver;
import io.github.mebsic.proxy.service.ServerRegistryService;
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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
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
    private static final String ROLLOUT_WEBHOOK_URL_ENV = "ROLLOUT_WEBHOOK_URL";
    private static final String ROLLOUT_WEBHOOK_TOKEN_ENV = "ROLLOUT_WEBHOOK_TOKEN";
    private static final long ROLLOUT_WEBHOOK_CONNECT_TIMEOUT_SECONDS = 5L;
    private static final long ROLLOUT_WEBHOOK_REQUEST_TIMEOUT_SECONDS = 180L;
    private static final String WARP_COMMAND = "/hub";
    private final ProxyServer proxy;
    private final Object plugin;
    private final RankResolver rankResolver;
    private final ServerRegistryService registryService;
    private final Logger logger;
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    private final List<ScheduledTask> scheduledTasks = new CopyOnWriteArrayList<ScheduledTask>();

    public RestartCommand(ProxyServer proxy,
                          Object plugin,
                          RankResolver rankResolver,
                          ServerRegistryService registryService,
                          Logger logger) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.rankResolver = rankResolver;
        this.registryService = registryService;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text(CommonMessages.ONLY_PLAYERS_COMMAND, NamedTextColor.RED));
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
        String targetServer = player.getCurrentServer()
                .map(ServerConnection::getServer)
                .map(registered -> registered.getServerInfo().getName())
                .orElse("");
        String targetService = resolveTargetService(targetServer);
        if (targetService.isEmpty()) {
            player.sendMessage(Component.text("Couldn't resolve a restart target for your current server.", NamedTextColor.RED));
            return;
        }

        if (!restartInProgress.compareAndSet(false, true)) {
            player.sendMessage(Component.text("A restart has already started!", NamedTextColor.RED));
            return;
        }

        cancelScheduledTasks();
        player.sendMessage(Component.text(CommonMessages.DONE + " ", NamedTextColor.GREEN)
                .append(Component.text("(" + duration.display + ", " + reason.name() + ")", NamedTextColor.DARK_GRAY)));
        scheduleBroadcasts(duration.totalSeconds, reason, targetServer);
        scheduleFinalRestart(duration.totalSeconds, targetServer, targetService);
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
                suggestions.add(reason.name());
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
            player.sendMessage(Component.text(reason.name(), NamedTextColor.RED));
        }
    }

    private void scheduleBroadcasts(int totalSeconds, RestartReason reason, String targetServer) {
        for (int interval : BROADCAST_INTERVALS_SECONDS) {
            if (interval > totalSeconds) {
                continue;
            }
            long delaySeconds = Math.max(0L, totalSeconds - interval);
            final int secondsRemaining = interval;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> broadcastRestartSoon(secondsRemaining, reason, targetServer))
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
    }

    private void broadcastRestartSoon(int secondsRemaining, RestartReason reason, String targetServer) {
        Component firstLine = Component.text("[Important] ", NamedTextColor.RED)
                .append(Component.text("This server will restart soon: ", NamedTextColor.YELLOW))
                .append(Component.text(reason.broadcastReason, NamedTextColor.AQUA));
        Component clickableWarp = Component.text("CLICK", NamedTextColor.GREEN, TextDecoration.BOLD, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(WARP_COMMAND))
                .hoverEvent(HoverEvent.showText(Component.text("Click to warp now!", NamedTextColor.YELLOW)));
        Component secondLine = Component.text("You have ", NamedTextColor.YELLOW)
                .append(Component.text(formatTimeRemaining(secondsRemaining), NamedTextColor.GREEN))
                .append(Component.text(" to warp out! ", NamedTextColor.YELLOW))
                .append(clickableWarp)
                .append(Component.text(" to warp now!", NamedTextColor.YELLOW));
        broadcastFramed(firstLine.append(Component.newline()).append(secondLine), targetServer);
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

    private void broadcastFramed(Component line, String targetServer) {
        Component empty = Component.text("");
        for (Player online : proxy.getAllPlayers()) {
            if (online == null) {
                continue;
            }
            if (!isPlayerOnServer(online, targetServer)) {
                continue;
            }
            online.sendMessage(empty);
            online.sendMessage(line);
            online.sendMessage(empty);
        }
    }

    private boolean isPlayerOnServer(Player player, String targetServer) {
        String expected = safeTrim(targetServer);
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        String playerServer = player.getCurrentServer()
                .map(ServerConnection::getServer)
                .map(registered -> registered.getServerInfo().getName())
                .orElse("");
        return expected.equalsIgnoreCase(playerServer);
    }

    private void scheduleFinalRestart(int totalSeconds, String targetServer, String targetService) {
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> restartNow(targetServer, targetService))
                .delay(totalSeconds, TimeUnit.SECONDS)
                .schedule();
        scheduledTasks.add(task);
    }

    private void restartNow(String targetServer, String targetService) {
        restartInProgress.set(false);
        // Keep this lightweight/non-blocking so restart scheduling never stalls proxy work.
        scheduledTasks.clear();
        if (registryService != null) {
            registryService.markServerRestarting(targetServer);
        }
        Component disconnectReason = restartDisconnectReason();
        for (Player online : proxy.getAllPlayers()) {
            if (online == null) {
                continue;
            }
            if (!isPlayerOnServer(online, targetServer)) {
                continue;
            }
            try {
                online.disconnect(disconnectReason);
            } catch (Exception ex) {
                logger.warn("Failed to disconnect {} during /restart shutdown!", online.getUsername(), ex);
            }
        }
        triggerContainerRestart(targetService, targetServer);
    }

    private Component restartDisconnectReason() {
        return Component.text("This server is restarting. Please reconnect to ", NamedTextColor.RED)
                .append(Component.text(HycopyProxyPlugin.domainToConnectHost(), NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.RED));
    }

    private void triggerContainerRestart(String targetService, String targetServer) {
        String webhookUrl = safeTrim(System.getenv(ROLLOUT_WEBHOOK_URL_ENV));
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.info("No rollout webhook URL configured; skipping scoped restart trigger.");
            return;
        }
        String service = safeTrim(targetService);
        if (service == null || service.isEmpty()) {
            logger.warn("No restart service target resolved; skipping scoped restart trigger.");
            return;
        }
        String serverId = safeTrim(targetServer);
        if (serverId == null || serverId.isEmpty()) {
            logger.warn("No restart server target resolved; skipping scoped restart trigger.");
            return;
        }

        String token = safeTrim(System.getenv(ROLLOUT_WEBHOOK_TOKEN_ENV));
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(ROLLOUT_WEBHOOK_CONNECT_TIMEOUT_SECONDS))
                    .build();
            String payload = "{\"mode\":\"restart\",\"services\":[\"" + escapeJson(service) + "\"],\"serverId\":\""
                    + escapeJson(serverId) + "\"}";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(ROLLOUT_WEBHOOK_REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (token != null && !token.isEmpty()) {
                builder.header("X-Rollout-Token", token);
            }

            client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            Throwable root = unwrapCompletionError(error);
                            if (root instanceof HttpTimeoutException) {
                                logger.warn(
                                        "Scoped rollout webhook for /restart timed out after {} seconds (service={}, server={}); restart may still be in progress.",
                                        ROLLOUT_WEBHOOK_REQUEST_TIMEOUT_SECONDS,
                                        service,
                                        serverId,
                                        root);
                                return;
                            }
                            logger.warn(
                                    "Failed to trigger scoped rollout webhook for /restart (service={}, server={})!",
                                    service,
                                    serverId,
                                    root);
                            return;
                        }
                        int status = response.statusCode();
                        if (status >= 200 && status < 300) {
                            logger.info("Triggered scoped rollout restart for {} ({}) via webhook (status {}).",
                                    service,
                                    serverId,
                                    status);
                        } else {
                            logger.warn("Scoped rollout webhook responded with status {}: {}", status, response.body());
                        }
                    });
        } catch (Exception ex) {
            logger.warn("Failed to trigger scoped rollout webhook for /restart!", ex);
        }
    }

    private Throwable unwrapCompletionError(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String escapeJson(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private String resolveTargetService(String targetServer) {
        String serverName = safeTrim(targetServer);
        if (serverName == null || serverName.isEmpty()) {
            return "";
        }
        if (registryService != null) {
            ServerRegistryService.ServerDetails details = registryService.findServerDetails(serverName).orElse(null);
            if (details != null) {
                if (details.getType().isHub()) {
                    return "hub";
                }
                if (details.getType().isGame()) {
                    return "game";
                }
                if (details.getType().isBuild()) {
                    return "build";
                }
            }
        }
        String normalized = serverName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("hub")) {
            return "hub";
        }
        if (normalized.startsWith("game")) {
            return "game";
        }
        if (normalized.startsWith("build")) {
            return "build";
        }
        return "";
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
