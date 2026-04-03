package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.proxy.HypixelProxyPlugin;
import io.github.mebsic.proxy.service.RankResolver;
import net.kyori.adventure.text.Component;
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
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class UpdateCommand implements SimpleCommand {
    private static final DurationOption[] DURATION_OPTIONS = new DurationOption[] {
            new DurationOption("10m", "10 minutes", 10),
            new DurationOption("5m", "5 minutes", 5),
            new DurationOption("1m", "1 minute", 1)
    };
    private static final String ROLLOUT_WEBHOOK_URL_ENV = "ROLLOUT_WEBHOOK_URL";
    private static final String ROLLOUT_WEBHOOK_TOKEN_ENV = "ROLLOUT_WEBHOOK_TOKEN";
    private static final String ROLLOUT_RESTART_MODE_ENV = "ROLLOUT_RESTART_MODE";
    private static final String ROLLOUT_UPDATE_SERVICES_ENV = "ROLLOUT_UPDATE_SERVICES";
    private static final long ROLLOUT_WEBHOOK_CONNECT_TIMEOUT_SECONDS = 5L;
    private static final long ROLLOUT_WEBHOOK_REQUEST_TIMEOUT_SECONDS = 180L;

    private final ProxyServer proxy;
    private final Object plugin;
    private final RankResolver rankResolver;
    private final Logger logger;
    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);
    private final AtomicBoolean joinLockActive = new AtomicBoolean(false);
    private final List<ScheduledTask> scheduledTasks = new CopyOnWriteArrayList<ScheduledTask>();

    public UpdateCommand(ProxyServer proxy,
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
            invocation.source().sendMessage(Component.text(CommonMessages.ONLY_PLAYERS_COMMAND, NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        if (rankResolver == null || !rankResolver.isStaff(player.getUniqueId())) {
            player.sendMessage(Component.text(CommonMessages.NO_PERMISSION_COMMAND, NamedTextColor.RED));
            return;
        }

        DurationOption duration = parseDuration(invocation.arguments());
        if (duration == null) {
            sendInvalidUsage(player);
            return;
        }

        if (!updateInProgress.compareAndSet(false, true)) {
            player.sendMessage(Component.text("An update has already started!", NamedTextColor.RED));
            return;
        }
        joinLockActive.set(false);

        cancelScheduledTasks();
        player.sendMessage(Component.text("Done! ", NamedTextColor.GREEN)
                .append(Component.text("(" + duration.display + ")", NamedTextColor.DARK_GRAY)));
        scheduleMinuteBroadcasts(duration.minutes);
        scheduleSecondCountdown(duration.minutes);
        scheduleFinalShutdown(duration.minutes);
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
        return List.of();
    }

    private DurationOption parseDuration(String[] args) {
        if (args == null || args.length != 1) {
            return null;
        }
        String normalizedDuration = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
        for (DurationOption option : DURATION_OPTIONS) {
            if (option.token.equals(normalizedDuration) || String.valueOf(option.minutes).equals(normalizedDuration)) {
                return option;
            }
        }
        return null;
    }

    private void sendInvalidUsage(Player player) {
        player.sendMessage(Component.text("Invalid usage! Correct usage:", NamedTextColor.RED));
        player.sendMessage(Component.text("/update <duration>", NamedTextColor.RED));
        player.sendMessage(Component.text("Available durations:", NamedTextColor.RED));
        for (DurationOption option : DURATION_OPTIONS) {
            player.sendMessage(Component.text(option.token, NamedTextColor.RED));
        }
    }

    private void scheduleMinuteBroadcasts(int totalMinutes) {
        for (int elapsed = 0; elapsed < totalMinutes; elapsed++) {
            long delaySeconds = elapsed * 60L;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, this::broadcastRestartSoon)
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
    }

    private void scheduleSecondCountdown(int totalMinutes) {
        long totalSeconds = totalMinutes * 60L;
        if (totalSeconds >= 30L) {
            long delaySeconds = totalSeconds - 30L;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> broadcastFinalSeconds(30))
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
        if (totalSeconds >= 20L) {
            long delaySeconds = totalSeconds - 20L;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> broadcastFinalSeconds(20))
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
        if (totalSeconds >= 10L) {
            long delaySeconds = totalSeconds - 10L;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> broadcastFinalSeconds(10))
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
        for (int secondsRemaining = 5; secondsRemaining >= 1; secondsRemaining--) {
            long delaySeconds = Math.max(0L, totalSeconds - secondsRemaining);
            final int remaining = secondsRemaining;
            ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> broadcastFinalSeconds(remaining))
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
            scheduledTasks.add(task);
        }
    }

    private void broadcastRestartSoon() {
        Component firstLine = withGoldObfuscatedPrefix(Component.text(HypixelProxyPlugin.PROXY_RESTARTING_SOON_MESSAGE, NamedTextColor.RED));
        Component secondLine = withGoldObfuscatedPrefix(Component.text(HypixelProxyPlugin.PROXY_RECONNECT_TO_MESSAGE, NamedTextColor.RED)
                .append(Component.text(NetworkConstants.mcHost(), NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.RED)));
        broadcastFramed(firstLine.append(Component.newline()).append(secondLine));
    }

    private void broadcastFinalSeconds(int secondsRemaining) {
        String unit = secondsRemaining == 1 ? " second" : " seconds";
        Component firstLine = withGoldObfuscatedPrefix(Component.text("This proxy is restarting in ", NamedTextColor.RED)
                .append(Component.text(String.valueOf(secondsRemaining), NamedTextColor.GOLD))
                .append(Component.text(unit + ".", NamedTextColor.RED)));
        Component secondLine = withGoldObfuscatedPrefix(Component.text(HypixelProxyPlugin.PROXY_RECONNECT_TO_MESSAGE, NamedTextColor.RED)
                .append(Component.text(NetworkConstants.mcHost(), NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.RED)));
        broadcastFramed(firstLine.append(Component.newline()).append(secondLine));
    }

    private Component withGoldObfuscatedPrefix(Component content) {
        return Component.text("z", NamedTextColor.GOLD, TextDecoration.OBFUSCATED)
                .append(Component.space().decoration(TextDecoration.OBFUSCATED, false))
                .append(content.decoration(TextDecoration.OBFUSCATED, false));
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

    private void scheduleFinalShutdown(int delayMinutes) {
        ScheduledTask task = proxy.getScheduler().buildTask(plugin, this::shutdownNow)
                .delay(delayMinutes, TimeUnit.MINUTES)
                .schedule();
        scheduledTasks.add(task);
    }

    private void shutdownNow() {
        joinLockActive.set(true);
        // Avoid cancelling the currently executing shutdown task; that can interrupt
        // the rollout webhook HTTP call before it completes.
        scheduledTasks.clear();

        if (!triggerRolloutRestart()) {
            updateInProgress.set(false);
            joinLockActive.set(false);
            return;
        }
        updateInProgress.set(false);
        joinLockActive.set(false);
    }

    private boolean triggerRolloutRestart() {
        String webhookUrl = safeTrim(System.getenv(ROLLOUT_WEBHOOK_URL_ENV));
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.info("No rollout webhook URL configured; skipping rollout trigger.");
            return false;
        }

        String token = safeTrim(System.getenv(ROLLOUT_WEBHOOK_TOKEN_ENV));
        String mode = resolveRolloutMode();
        List<String> services = resolveRolloutServices();
        logger.info(
                "Triggering rollout webhook with mode={} and services={}.",
                mode,
                services.isEmpty() ? "<all>" : services
        );
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(ROLLOUT_WEBHOOK_CONNECT_TIMEOUT_SECONDS))
                    .build();
            String payload = buildRolloutPayload(mode, services);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(ROLLOUT_WEBHOOK_REQUEST_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (token != null && !token.isEmpty()) {
                builder.header("X-Rollout-Token", token);
            }

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                logger.info("Triggered rollout restart via webhook (status {}).", status);
                return true;
            } else {
                logger.warn("Rollout webhook responded with status {}: {}", status, response.body());
                return false;
            }
        } catch (InterruptedException ex) {
            logger.info("Rollout webhook request interrupted (proxy likely restarting).");
            return false;
        } catch (Exception ex) {
            logger.warn("Failed to trigger rollout webhook!", ex);
            return false;
        }
    }

    private List<String> resolveRolloutServices() {
        String configured = safeTrim(System.getenv(ROLLOUT_UPDATE_SERVICES_ENV));
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ordered = new LinkedHashSet<String>();
        String[] tokens = configured.split(",");
        for (String token : tokens) {
            String service = safeTrim(token);
            if (service != null && !service.isEmpty()) {
                ordered.add(service);
            }
        }
        return new ArrayList<String>(ordered);
    }

    private String buildRolloutPayload(String mode, List<String> services) {
        boolean rebuild = "rebuild".equals(mode);
        boolean recreate = "recreate".equals(mode);
        StringBuilder payload = new StringBuilder(128);
        payload.append("{\"mode\":\"").append(escapeJson(mode)).append("\",");
        payload.append("\"rebuild\":").append(rebuild).append(',');
        payload.append("\"recreate\":").append(recreate);
        if (!services.isEmpty()) {
            payload.append(",\"services\":[");
            for (int i = 0; i < services.size(); i++) {
                if (i > 0) {
                    payload.append(',');
                }
                payload.append('"').append(escapeJson(services.get(i))).append('"');
            }
            payload.append(']');
        }
        payload.append('}');
        return payload.toString();
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
            }
        }
        return escaped.toString();
    }

    private String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    private String resolveRolloutMode() {
        String configured = safeTrim(System.getenv(ROLLOUT_RESTART_MODE_ENV));
        if (configured == null || configured.isEmpty()) {
            return "rebuild";
        }
        String normalized = configured.toLowerCase();
        if ("restart".equals(normalized) || "recreate".equals(normalized) || "rebuild".equals(normalized)) {
            return normalized;
        }
        logger.warn("Invalid {} value '{}'; falling back to rebuild.", ROLLOUT_RESTART_MODE_ENV, configured);
        return "rebuild";
    }

    public boolean cancelUpdate() {
        boolean hadActiveUpdate = updateInProgress.getAndSet(false);
        joinLockActive.set(false);
        cancelScheduledTasks();
        return hadActiveUpdate;
    }

    public boolean isJoinLockActive() {
        return joinLockActive.get();
    }

    public Component restartDisconnectReason() {
        return HypixelProxyPlugin.proxyRestartReconnectReason();
    }

    private void cancelScheduledTasks() {
        for (ScheduledTask task : scheduledTasks) {
            if (task == null) {
                continue;
            }
            try {
                task.cancel();
            } catch (Exception ex) {
                logger.warn("Failed to cancel scheduled /update task!", ex);
            }
        }
        scheduledTasks.clear();
    }

    private String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static final class DurationOption {
        private final String token;
        private final String display;
        private final int minutes;

        private DurationOption(String token, String display, int minutes) {
            this.token = token;
            this.display = display;
            this.minutes = minutes;
        }
    }
}
