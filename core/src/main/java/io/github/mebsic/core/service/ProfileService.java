package io.github.mebsic.core.service;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.store.ProfileStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ProfileService {
    private static final long PRELOGIN_PRELOAD_EXPIRY_TICKS = 20L * 30L;
    private static final long PRELOGIN_PRELOAD_EXPIRY_RETRY_TICKS = 20L * 5L;
    private static final ZoneId EASTERN_TIME_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter LOGIN_TIMESTAMP_FORMAT = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    private final CorePlugin plugin;
    private final ProfileStore store;
    private final CosmeticService cosmetics;
    private final Map<UUID, Profile> cache;
    private final Map<UUID, Boolean> activeSessions;
    private final Map<UUID, Boolean> inFlightLoads;
    private final Map<UUID, Long> pendingPreloads;
    private final Map<UUID, Rank> pendingRankOverrides;
    private final Map<UUID, Boolean> pendingVisibilityOverrides;
    private final AtomicLong preloadTokenSequence;
    private final AtomicBoolean refreshRunning;

    public ProfileService(CorePlugin plugin, ProfileStore store, CosmeticService cosmetics) {
        this.plugin = plugin;
        this.store = store;
        this.cosmetics = cosmetics;
        this.cache = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
        this.inFlightLoads = new ConcurrentHashMap<>();
        this.pendingPreloads = new ConcurrentHashMap<>();
        this.pendingRankOverrides = new ConcurrentHashMap<>();
        this.pendingVisibilityOverrides = new ConcurrentHashMap<>();
        this.preloadTokenSequence = new AtomicLong(0L);
        this.refreshRunning = new AtomicBoolean(false);
    }

    public Profile getProfile(UUID uuid) {
        return cache.get(uuid);
    }

    public void preloadDuringLogin(UUID uuid, String name) {
        if (uuid == null) {
            return;
        }
        long token = preloadTokenSequence.incrementAndGet();
        pendingPreloads.put(uuid, token);
        loadProfileAsync(uuid, name);
        schedulePreloadExpiry(uuid, token, PRELOGIN_PRELOAD_EXPIRY_TICKS);
    }

    public void loadProfileAsync(UUID uuid, String name) {
        if (uuid == null) {
            return;
        }
        Profile cached = cache.get(uuid);
        if (cached != null) {
            applyLatestName(cached, name);
            return;
        }
        if (inFlightLoads.putIfAbsent(uuid, Boolean.TRUE) != null) {
            return;
        }
        Runnable loadTask = () -> {
            try {
                Profile profile;
                boolean created = false;
                if (store == null) {
                    profile = new Profile(uuid, name);
                } else {
                    ProfileStore.LoadResult loadResult = store.loadWithState(uuid, name);
                    profile = loadResult.getProfile();
                    created = loadResult.isCreated();
                }
                applyLatestName(profile, name);
                cosmetics.grantDefaults(profile);
                applyPendingRankOverride(uuid, profile);
                applyPendingVisibilityOverride(uuid, profile);
                boolean saveAfterLoad = created;
                if (activeSessions.containsKey(uuid)) {
                    markProfileOnline(profile);
                    saveAfterLoad = true;
                }
                if (saveAfterLoad && store != null) {
                    store.save(profile);
                }
                cache.put(uuid, profile);
                plugin.handleProfileLoaded(profile);
            } finally {
                inFlightLoads.remove(uuid);
            }
        };
        if (store == null) {
            loadTask.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, loadTask);
        } catch (Exception ignored) {
            inFlightLoads.remove(uuid);
        }
    }

    private void applyLatestName(Profile profile, String name) {
        if (profile == null || name == null) {
            return;
        }
        String trimmed = name.trim();
        if (!trimmed.isEmpty()) {
            profile.setName(trimmed);
        }
    }

    private void schedulePreloadExpiry(UUID uuid, long token, long delayTicks) {
        if (uuid == null || !plugin.isEnabled()) {
            return;
        }
        long safeDelay = Math.max(1L, delayTicks);
        Bukkit.getScheduler().runTaskLater(plugin, () -> expirePreload(uuid, token), safeDelay);
    }

    private void expirePreload(UUID uuid, long token) {
        Long currentToken = pendingPreloads.get(uuid);
        if (currentToken == null || currentToken.longValue() != token) {
            return;
        }
        if (activeSessions.containsKey(uuid)) {
            pendingPreloads.remove(uuid, token);
            return;
        }
        if (inFlightLoads.containsKey(uuid)) {
            schedulePreloadExpiry(uuid, token, PRELOGIN_PRELOAD_EXPIRY_RETRY_TICKS);
            return;
        }
        pendingPreloads.remove(uuid, token);
        if (!activeSessions.containsKey(uuid)) {
            evictWithoutSave(uuid);
        }
    }

    private void evictWithoutSave(UUID uuid) {
        if (uuid == null) {
            return;
        }
        cache.remove(uuid);
        pendingRankOverrides.remove(uuid);
        pendingVisibilityOverrides.remove(uuid);
    }

    public void setRank(UUID uuid, Rank rank) {
        if (uuid == null || rank == null) {
            return;
        }
        pendingRankOverrides.put(uuid, rank);
        Profile profile = cache.get(uuid);
        if (profile != null) {
            profile.setRank(rank);
        }
    }

    public void setPlayerVisibilityEnabled(UUID uuid, boolean enabled) {
        if (uuid == null) {
            return;
        }
        pendingVisibilityOverrides.put(uuid, enabled);
        Profile profile = cache.get(uuid);
        if (profile != null) {
            profile.setPlayerVisibilityEnabled(enabled);
            pendingVisibilityOverrides.remove(uuid);
        }
    }

    public void saveProfile(Profile profile) {
        if (!plugin.isMongoEnabled() || store == null || profile == null) {
            return;
        }
        if (!plugin.isEnabled()) {
            saveProfileSync(profile);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> store.save(profile));
        } catch (Exception ex) {
            saveProfileSync(profile);
        }
    }

    public void saveAll() {
        for (Profile profile : cache.values()) {
            saveProfile(profile);
        }
    }

    public void saveAllSync() {
        if (!plugin.isMongoEnabled() || store == null) {
            return;
        }
        for (Profile profile : cache.values()) {
            markProfileOffline(profile);
            saveProfileSync(profile);
        }
    }

    public void unload(UUID uuid) {
        if (uuid == null) {
            return;
        }
        activeSessions.remove(uuid);
        pendingPreloads.remove(uuid);
        Profile profile = cache.remove(uuid);
        if (profile != null) {
            saveProfile(profile);
        }
        pendingRankOverrides.remove(uuid);
        pendingVisibilityOverrides.remove(uuid);
    }

    public void handleJoin(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        activeSessions.put(uuid, Boolean.TRUE);
        pendingPreloads.remove(uuid);
        Profile profile = cache.get(uuid);
        if (profile != null) {
            applyLatestName(profile, player.getName());
            markProfileOnline(profile);
            saveProfile(profile);
            plugin.handleProfileLoaded(profile);
            return;
        }
        loadProfileAsync(uuid, player.getName());
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        activeSessions.remove(uuid);
        pendingPreloads.remove(uuid);
        Profile profile = cache.get(uuid);
        if (profile != null) {
            markProfileOffline(profile);
        }
        unload(uuid);
    }

    public void refreshOnlineProfileMeta() {
        if (!plugin.isMongoEnabled() || store == null) {
            return;
        }
        List<PlayerSnapshot> online = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }
            online.add(new PlayerSnapshot(player.getUniqueId(), player.getName()));
        }
        if (online.isEmpty()) {
            return;
        }
        if (!refreshRunning.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<MetaUpdate> updates = new ArrayList<>();
                for (PlayerSnapshot snapshot : online) {
                    if (snapshot == null) {
                        continue;
                    }
                    Profile cached = cache.get(snapshot.uuid);
                    if (cached == null) {
                        continue;
                    }
                    ProfileStore.ProfileMeta meta = store.loadProfileMeta(snapshot.uuid, snapshot.name);
                    if (meta == null) {
                        continue;
                    }
                    if (!changed(cached, meta)) {
                        continue;
                    }
                    updates.add(new MetaUpdate(snapshot.uuid, meta));
                }
                if (updates.isEmpty()) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> applyMetaUpdates(updates));
            } finally {
                refreshRunning.set(false);
            }
        });
    }

    private void applyMetaUpdates(List<MetaUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (MetaUpdate update : updates) {
            if (update == null || update.meta == null) {
                continue;
            }
            Profile profile = cache.get(update.uuid);
            if (profile == null) {
                continue;
            }
            profile.setName(update.meta.getName());
            profile.setRank(update.meta.getRank());
            profile.setPlusColor(update.meta.getPlusColor());
            profile.setMvpPlusPlusPrefixColor(update.meta.getMvpPlusPlusPrefixColor());
            profile.setFlightEnabled(update.meta.isFlightEnabled());
            profile.setPlayerVisibilityEnabled(update.meta.isPlayerVisibilityEnabled());
            profile.setNetworkLevel(update.meta.getNetworkLevel());
            profile.setNetworkGold(update.meta.getNetworkGold());
            profile.setHasActiveSubscription(update.meta.hasActiveSubscription());
            profile.setSubscriptionExpiresAt(update.meta.getSubscriptionExpiresAt());
            boolean giftedChanged = setGiftedRanks(profile, update.meta.getGiftedRanks());
            if (giftedChanged) {
                dispatchGiftedCounterSync(update.uuid, readGiftedRanks(profile));
            }

            Player player = Bukkit.getPlayer(update.uuid);
            if (player != null && player.isOnline()) {
                plugin.handleProfileLoaded(profile);
            }
        }
    }

    private boolean changed(Profile profile, ProfileStore.ProfileMeta meta) {
        if (profile == null || meta == null) {
            return false;
        }
        Rank currentRank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (currentRank != meta.getRank()) {
            return true;
        }
        if (!Objects.equals(profile.getName(), meta.getName())) {
            return true;
        }
        if (!Objects.equals(profile.getPlusColor(), meta.getPlusColor())) {
            return true;
        }
        if (!Objects.equals(profile.getMvpPlusPlusPrefixColor(), meta.getMvpPlusPlusPrefixColor())) {
            return true;
        }
        if (profile.isFlightEnabled() != meta.isFlightEnabled()) {
            return true;
        }
        if (profile.isPlayerVisibilityEnabled() != meta.isPlayerVisibilityEnabled()) {
            return true;
        }
        if (profile.getNetworkLevel() != meta.getNetworkLevel()) {
            return true;
        }
        if (profile.getNetworkGold() != meta.getNetworkGold()) {
            return true;
        }
        if (profile.hasActiveSubscription() != meta.hasActiveSubscription()) {
            return true;
        }
        if (profile.getSubscriptionExpiresAt() != meta.getSubscriptionExpiresAt()) {
            return true;
        }
        return readGiftedRanks(profile) != Math.max(0, meta.getGiftedRanks());
    }

    private int readGiftedRanks(Profile profile) {
        if (profile == null) {
            return 0;
        }
        return Math.max(0, profile.getRanksGifted());
    }

    private boolean setGiftedRanks(Profile profile, int target) {
        if (profile == null) {
            return false;
        }
        int desired = Math.max(0, target);
        int current = Math.max(0, profile.getRanksGifted());
        if (current == desired) {
            return false;
        }
        profile.setRanksGifted(desired);
        return true;
    }

    private void dispatchGiftedCounterSync(UUID uuid, int value) {
        if (uuid == null) {
            return;
        }
        ProfileCommandSyncService sync = plugin.getProfileCommandSyncService();
        if (sync == null) {
            return;
        }
        sync.dispatchCounterSet(uuid, MongoManager.PROFILE_RANKS_GIFTED_KEY, Math.max(0, value), null, null);
    }

    private static final class PlayerSnapshot {
        private final UUID uuid;
        private final String name;

        private PlayerSnapshot(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private static final class MetaUpdate {
        private final UUID uuid;
        private final ProfileStore.ProfileMeta meta;

        private MetaUpdate(UUID uuid, ProfileStore.ProfileMeta meta) {
            this.uuid = uuid;
            this.meta = meta;
        }
    }

    private void applyPendingRankOverride(UUID uuid, Profile profile) {
        if (uuid == null || profile == null) {
            return;
        }
        Rank override = pendingRankOverrides.remove(uuid);
        if (override != null) {
            profile.setRank(override);
        }
    }

    private void applyPendingVisibilityOverride(UUID uuid, Profile profile) {
        if (uuid == null || profile == null) {
            return;
        }
        Boolean override = pendingVisibilityOverrides.remove(uuid);
        if (override != null) {
            profile.setPlayerVisibilityEnabled(override);
        }
    }

    private void saveProfileSync(Profile profile) {
        if (profile == null || store == null) {
            return;
        }
        try {
            store.save(profile);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save profile for " + profile.getUuid() + "!\n" + ex.getMessage());
        }
    }

    private void markProfileOnline(Profile profile) {
        if (profile == null) {
            return;
        }
        String timestamp = easternTimestampNow();
        if (profile.getFirstLogin() == null || profile.getFirstLogin().trim().isEmpty()) {
            profile.setFirstLogin(timestamp);
        }
        profile.setLastLogin(timestamp);
        profile.setOnline(true);
    }

    private void markProfileOffline(Profile profile) {
        if (profile == null) {
            return;
        }
        profile.setOnline(false);
    }

    private String easternTimestampNow() {
        return LOGIN_TIMESTAMP_FORMAT.format(ZonedDateTime.now(EASTERN_TIME_ZONE));
    }
}
