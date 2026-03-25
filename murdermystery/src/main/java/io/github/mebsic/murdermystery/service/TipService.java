package io.github.mebsic.murdermystery.service;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.store.RoleChanceStore;
import io.github.mebsic.game.manager.GameManager;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.model.RoleChance;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import io.github.mebsic.murdermystery.util.ActionBarUtil;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class TipService {
    private static final int TIP_VISIBLE_SECONDS = 3;
    private static final int TIP_HIDDEN_SECONDS = 5;
    private static final int TOKEN_ACTION_BAR_SECONDS = 3;
    private static final int HINTS_PROFILE_INIT_MAX_ATTEMPTS = 20;
    private static final long HINTS_PROFILE_INIT_RETRY_TICKS = 10L;
    private static final long ACTION_BAR_MIN_RESEND_MILLIS = 250L;
    private static final long ACTION_BAR_LOCK_BUFFER_MILLIS = 200L;

    private final Plugin plugin;
    private final GameManager gameManager;
    private final CorePlugin corePlugin;
    private final RoleChanceStore roleChanceStore;
    private final double defaultMurdererChance;
    private final double defaultDetectiveChance;
    private final List<String> tips;
    private final Map<UUID, RoleChance> chanceCache;
    private final Map<UUID, TemporaryActionBar> temporaryActionBars;
    private final Map<UUID, ActionBarDispatchState> actionBarDispatchStates;
    private final AtomicBoolean cacheRefreshInProgress;
    private Set<UUID> lastSnapshot;
    private int inGamePhaseSecondsRemaining;
    private boolean inGameTipPhaseVisible;
    private String currentInGameTip;
    private final List<String> inGameTipOrder;
    private int inGameTipOrderIndex;
    private BukkitTask task;

    public TipService(Plugin plugin,
                      GameManager gameManager,
                      CorePlugin corePlugin,
                      RoleChanceStore roleChanceStore,
                      double defaultMurdererChance,
                      double defaultDetectiveChance) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.corePlugin = corePlugin;
        this.roleChanceStore = roleChanceStore;
        this.defaultMurdererChance = defaultMurdererChance;
        this.defaultDetectiveChance = defaultDetectiveChance;
        this.tips = Arrays.asList(
                ChatColor.GOLD + "Gold can be spent on items on most maps!",
                ChatColor.GOLD + "Collect Gold to buy items or earn a Bow!",
                ChatColor.GOLD + "Collect 10 Gold to earn a Bow and Arrow!",
                ChatColor.DARK_GREEN + "Knife Skins" + ChatColor.GREEN + " can change the look of the Knife!",
                ChatColor.YELLOW + "Keep your eye out for the " + ChatColor.RED + "Murderer" + ChatColor.YELLOW + "!",
                ChatColor.YELLOW + "Don't let the " + ChatColor.RED + "Murderer" + ChatColor.YELLOW + " catch you!",
                ChatColor.GREEN + "Pancakes are better than waffles!",
                ChatColor.YELLOW + "Knives and Arrows can collide in midair!",
                ChatColor.YELLOW + "Use your Minimap to find your way around!",
                ChatColor.YELLOW + "Use " + ChatColor.RED + "/togglehints" + ChatColor.YELLOW + " to disable these tips!"
        );
        this.chanceCache = new ConcurrentHashMap<>();
        this.temporaryActionBars = new ConcurrentHashMap<>();
        this.actionBarDispatchStates = new ConcurrentHashMap<>();
        this.cacheRefreshInProgress = new AtomicBoolean(false);
        this.lastSnapshot = new HashSet<>();
        this.inGamePhaseSecondsRemaining = 0;
        this.inGameTipPhaseVisible = false;
        this.currentInGameTip = null;
        this.inGameTipOrder = new ArrayList<>();
        this.inGameTipOrderIndex = 0;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        chanceCache.clear();
        temporaryActionBars.clear();
        actionBarDispatchStates.clear();
        lastSnapshot = new HashSet<>();
        cacheRefreshInProgress.set(false);
        resetInGameTipCycle();
    }

    public void showTokenReward(Player player, int amount) {
        if (player == null || amount <= 0) {
            return;
        }
        String message = ChatColor.DARK_GREEN + "+" + amount + " tokens";
        setTemporaryActionBar(player.getUniqueId(), message, TOKEN_ACTION_BAR_SECONDS);
        sendActionBar(player, message, ActionBarPriority.HIGH, (TOKEN_ACTION_BAR_SECONDS * 1000L) + ACTION_BAR_LOCK_BUFFER_MILLIS);
    }

    public void handlePlayerJoin(Player player) {
        if (player == null) {
            return;
        }
        ensureHintsPreferenceInitialized(player.getUniqueId(), 0);
    }

    public void handleHintsToggled(Player player, boolean enabled) {
        if (player == null || enabled) {
            return;
        }
        // Intentionally avoid force-clearing so clients can naturally fade the last action bar message.
    }

    private void tick() {
        if (gameManager == null) {
            return;
        }
        GameState state = gameManager.getState();
        if (state == GameState.WAITING || state == GameState.STARTING) {
            handlePregameActionBar();
            return;
        }
        if (state == GameState.IN_GAME) {
            handleInGameTips();
        }
    }

    private void handlePregameActionBar() {
        List<Player> participants = getInGamePlayers();
        if (participants.isEmpty()) {
            return;
        }
        resetInGameTipCycle();
        refreshChanceCacheIfNeeded(participants);
        ChanceTotals totals = calculateTotals(participants);
        for (Player player : participants) {
            if (sendTemporaryIfActive(player)) {
                continue;
            }
            RoleChance roleChance = chanceCache.get(player.getUniqueId());
            double murdererWeight = resolvePrimaryChance(roleChance);
            double detectiveWeight = resolveSecondaryChance(roleChance);
            double murdererPercent = toPercent(murdererWeight, totals.primaryTotal);
            double detectivePercent = toPercent(detectiveWeight, totals.secondaryTotal);
            sendActionBar(player, buildPregameActionBar(murdererPercent, detectivePercent), ActionBarPriority.LOW, 0L);
        }
    }

    private void handleInGameTips() {
        List<Player> participants = getInGamePlayers();
        if (participants.isEmpty()) {
            resetInGameTipCycle();
            return;
        }

        if (inGamePhaseSecondsRemaining <= 0) {
            if (inGameTipPhaseVisible) {
                inGameTipPhaseVisible = false;
                inGamePhaseSecondsRemaining = TIP_HIDDEN_SECONDS;
            } else {
                inGameTipPhaseVisible = true;
                inGamePhaseSecondsRemaining = TIP_VISIBLE_SECONDS;
                currentInGameTip = nextInGameTip();
            }
        }

        for (Player player : participants) {
            if (sendTemporaryIfActive(player)) {
                continue;
            }
            if (!areHintsEnabled(player)) {
                continue;
            }
            if (inGameTipPhaseVisible && currentInGameTip != null) {
                sendActionBar(player, currentInGameTip, ActionBarPriority.LOW, 0L);
            }
        }

        inGamePhaseSecondsRemaining--;
    }

    private List<Player> getInGamePlayers() {
        List<Player> participants = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (gameManager.isInGame(player)) {
                participants.add(player);
            }
        }
        return participants;
    }

    private void refreshChanceCacheIfNeeded(List<Player> participants) {
        if (roleChanceStore == null || participants == null || participants.isEmpty()) {
            return;
        }
        Set<UUID> snapshot = new HashSet<>();
        for (Player participant : participants) {
            snapshot.add(participant.getUniqueId());
        }
        boolean changed = !snapshot.equals(lastSnapshot);
        boolean incomplete = chanceCache.size() < snapshot.size();
        if (!changed && !incomplete) {
            return;
        }
        if (!cacheRefreshInProgress.compareAndSet(false, true)) {
            return;
        }
        final Set<UUID> request = new HashSet<>(snapshot);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, RoleChance> loaded;
            try {
                loaded = roleChanceStore.load(request, defaultMurdererChance, defaultDetectiveChance);
            } catch (Exception ignored) {
                loaded = new HashMap<>();
            }
            final Map<UUID, RoleChance> resolved = loaded;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                chanceCache.clear();
                chanceCache.putAll(resolved);
                lastSnapshot = new HashSet<>(request);
                cacheRefreshInProgress.set(false);
            });
        });
    }

    private ChanceTotals calculateTotals(List<Player> participants) {
        double primary = 0.0;
        double secondary = 0.0;
        for (Player participant : participants) {
            RoleChance chance = chanceCache.get(participant.getUniqueId());
            primary += resolvePrimaryChance(chance);
            secondary += resolveSecondaryChance(chance);
        }
        return new ChanceTotals(primary, secondary);
    }

    private double resolvePrimaryChance(RoleChance chance) {
        if (chance == null) {
            return Math.max(0.0, defaultMurdererChance);
        }
        return Math.max(0.0, chance.getPrimaryChance());
    }

    private double resolveSecondaryChance(RoleChance chance) {
        if (chance == null) {
            return Math.max(0.0, defaultDetectiveChance);
        }
        return Math.max(0.0, chance.getSecondaryChance());
    }

    private double toPercent(double weight, double total) {
        if (total <= 0.0 || weight <= 0.0) {
            return 0.0;
        }
        double raw = (weight / total) * 100.0;
        if (raw < 0.0) {
            return 0.0;
        }
        if (raw > 100.0) {
            return 100.0;
        }
        return raw;
    }

    private String buildPregameActionBar(double murdererPercent, double detectivePercent) {
        int murdererRounded = Math.max(0, (int) Math.round(murdererPercent));
        int detectiveRounded = Math.max(0, (int) Math.round(detectivePercent));
        return ChatColor.RED + "Murderer Chance: " + murdererRounded + "%"
                + ChatColor.GRAY + " - "
                + ChatColor.AQUA + "Detective Chance: " + detectiveRounded + "%";
    }

    private boolean sendTemporaryIfActive(Player player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        TemporaryActionBar temporary = temporaryActionBars.get(uuid);
        if (temporary == null) {
            return false;
        }
        if (System.currentTimeMillis() >= temporary.expiresAtMillis) {
            temporaryActionBars.remove(uuid, temporary);
            return false;
        }
        long remainingMillis = Math.max(0L, temporary.expiresAtMillis - System.currentTimeMillis());
        sendActionBar(player, temporary.message, ActionBarPriority.HIGH, remainingMillis + ACTION_BAR_LOCK_BUFFER_MILLIS);
        return true;
    }

    private void setTemporaryActionBar(UUID uuid, String message, int seconds) {
        if (uuid == null || message == null || message.trim().isEmpty() || seconds <= 0) {
            return;
        }
        long expiresAtMillis = System.currentTimeMillis() + (seconds * 1000L);
        temporaryActionBars.put(uuid, new TemporaryActionBar(message, expiresAtMillis));
    }

    private void ensureHintsPreferenceInitialized(UUID uuid, int attempt) {
        if (uuid == null || corePlugin == null) {
            return;
        }
        Profile profile = corePlugin.getProfile(uuid);
        if (profile != null && profile.getStats() != null) {
            if (MurderMysteryStats.ensureHintsEnabledCounterExists(profile.getStats())) {
                corePlugin.saveProfile(profile);
            }
            return;
        }
        if (attempt >= HINTS_PROFILE_INIT_MAX_ATTEMPTS) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> ensureHintsPreferenceInitialized(uuid, attempt + 1),
                HINTS_PROFILE_INIT_RETRY_TICKS
        );
    }

    private boolean areHintsEnabled(Player player) {
        if (player == null || corePlugin == null) {
            return true;
        }
        Profile profile = corePlugin.getProfile(player.getUniqueId());
        if (profile == null || profile.getStats() == null) {
            return true;
        }
        return MurderMysteryStats.areHintsEnabled(profile.getStats());
    }

    private String nextInGameTip() {
        if (tips.isEmpty()) {
            return null;
        }
        if (inGameTipOrderIndex >= inGameTipOrder.size()) {
            rebuildInGameTipOrder();
        }
        return inGameTipOrder.get(inGameTipOrderIndex++);
    }

    private void rebuildInGameTipOrder() {
        inGameTipOrder.clear();
        inGameTipOrder.addAll(tips);
        Collections.shuffle(inGameTipOrder);
        if (inGameTipOrder.size() > 1
                && currentInGameTip != null
                && currentInGameTip.equals(inGameTipOrder.get(0))) {
            Collections.swap(inGameTipOrder, 0, 1);
        }
        inGameTipOrderIndex = 0;
    }

    private void resetInGameTipCycle() {
        inGamePhaseSecondsRemaining = 0;
        inGameTipPhaseVisible = false;
        currentInGameTip = null;
        inGameTipOrder.clear();
        inGameTipOrderIndex = 0;
    }

    private boolean sendActionBar(Player player, String message, ActionBarPriority priority, long lockDurationMillis) {
        if (player == null || message == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        ActionBarDispatchState state = actionBarDispatchStates.get(uuid);

        if (state != null && now < state.lockedUntilMillis && priority.weight < state.lockPriorityWeight) {
            return false;
        }
        if (state != null
                && message.equals(state.lastMessage)
                && (now - state.lastSentMillis) < ACTION_BAR_MIN_RESEND_MILLIS) {
            if (lockDurationMillis > 0L) {
                state.lockPriorityWeight = Math.max(state.lockPriorityWeight, priority.weight);
                state.lockedUntilMillis = Math.max(state.lockedUntilMillis, now + lockDurationMillis);
            }
            return true;
        }

        ActionBarUtil.send(player, message);

        ActionBarDispatchState next = state == null ? new ActionBarDispatchState() : state;
        next.lastMessage = message;
        next.lastSentMillis = now;
        if (lockDurationMillis > 0L) {
            next.lockPriorityWeight = Math.max(next.lockPriorityWeight, priority.weight);
            next.lockedUntilMillis = Math.max(next.lockedUntilMillis, now + lockDurationMillis);
        } else if (next.lockedUntilMillis <= now) {
            next.lockPriorityWeight = priority.weight;
        }
        actionBarDispatchStates.put(uuid, next);
        return true;
    }

    private static class ChanceTotals {
        private final double primaryTotal;
        private final double secondaryTotal;

        private ChanceTotals(double primaryTotal, double secondaryTotal) {
            this.primaryTotal = primaryTotal;
            this.secondaryTotal = secondaryTotal;
        }
    }

    private static class TemporaryActionBar {
        private final String message;
        private final long expiresAtMillis;

        private TemporaryActionBar(String message, long expiresAtMillis) {
            this.message = message;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private enum ActionBarPriority {
        LOW(0),
        HIGH(1);

        private final int weight;

        ActionBarPriority(int weight) {
            this.weight = weight;
        }
    }

    private static class ActionBarDispatchState {
        private String lastMessage;
        private long lastSentMillis;
        private long lockedUntilMillis;
        private int lockPriorityWeight;
    }
}
