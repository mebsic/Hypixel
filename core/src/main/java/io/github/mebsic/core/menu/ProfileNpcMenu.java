package io.github.mebsic.core.menu;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.store.ProfileStore;
import io.github.mebsic.core.util.HubMessageUtil;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.text.NumberFormat;
import java.util.concurrent.ConcurrentHashMap;

public class ProfileNpcMenu extends Menu {
    private static final int SIZE = 36;
    private static final int STATS_SLOT = 13;
    private static final int CLOSE_SLOT = 31;
    private static final String MODE_NAME = "Classic";

    private static final String BOW_KILLS_KEY = "murdermystery.bowKills";
    private static final String KNIFE_KILLS_KEY = "murdermystery.knifeKills";
    private static final String THROWN_KNIFE_KILLS_KEY = "murdermystery.thrownKnifeKills";
    private static final String WINS_AS_DETECTIVE_KEY = "murdermystery.winsAsDetective";
    private static final String WINS_AS_MURDERER_KEY = "murdermystery.winsAsMurderer";
    private static final String KILLS_AS_HERO_KEY = "murdermystery.killsAsHero";
    private static final String LIFETIME_KILLS_RANK_KEY = "murdermystery.lifetimeKillsRank";
    private static final String LIFETIME_WINS_RANK_KEY = "murdermystery.lifetimeWinsRank";
    private static final String QUICKEST_DETECTIVE_WIN_SECONDS_KEY = "murdermystery.quickestDetectiveWinSeconds";
    private static final String QUICKEST_MURDERER_WIN_SECONDS_KEY = "murdermystery.quickestMurdererWinSeconds";
    private static final long LIVE_PROFILE_CACHE_TTL_MILLIS = 30_000L;
    private static final int LIVE_PROFILE_CACHE_MAX_ENTRIES = 2_000;
    private static final long RANK_CACHE_TTL_MILLIS = 60_000L;
    private static final int RANK_CACHE_MAX_ENTRIES = 2_000;

    private final CorePlugin corePlugin;
    private final NumberFormat numberFormat;
    private final Map<UUID, CachedProfile> liveProfileCacheByPlayer;
    private final Set<UUID> liveProfileRefreshInFlight;
    private final Map<UUID, CachedRanks> rankCacheByPlayer;
    private final Set<UUID> rankRefreshInFlight;

    public ProfileNpcMenu(CorePlugin corePlugin, ServerType serverType) {
        super(resolveTitle(serverType), SIZE);
        this.corePlugin = corePlugin;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.liveProfileCacheByPlayer = new ConcurrentHashMap<UUID, CachedProfile>();
        this.liveProfileRefreshInFlight = ConcurrentHashMap.newKeySet();
        this.rankCacheByPlayer = new ConcurrentHashMap<UUID, CachedRanks>();
        this.rankRefreshInFlight = ConcurrentHashMap.newKeySet();
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (player == null || inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, STATS_SLOT, buildStatsItem(player));
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
        requestLiveProfileRefresh(player);
    }

    private ItemStack buildStatsItem(Player player) {
        Profile profile = resolveProfileSnapshot(player);
        Stats stats = profile == null ? null : profile.getStats();

        int bowKills = counter(stats, BOW_KILLS_KEY, "bowKills");
        int knifeKills = counter(stats, KNIFE_KILLS_KEY, "knifeKills");
        int thrownKnifeKills = counter(stats, THROWN_KNIFE_KILLS_KEY, "thrownKnifeKills");
        int lifetimeKills = stats == null ? 0 : Math.max(0, stats.getKills());
        int lifetimeKillsRank = counter(stats, LIFETIME_KILLS_RANK_KEY, "lifetimeKillsRank");

        int games = stats == null ? 0 : Math.max(0, stats.getGames());
        int detectiveWins = counter(stats, WINS_AS_DETECTIVE_KEY, "winsAsDetective");
        int murdererWins = counter(stats, WINS_AS_MURDERER_KEY, "winsAsMurderer");
        int killsAsHero = counter(stats, KILLS_AS_HERO_KEY, "killsAsHero");
        int lifetimeWins = stats == null ? 0 : Math.max(0, stats.getWins());
        int lifetimeWinsRank = counter(stats, LIFETIME_WINS_RANK_KEY, "lifetimeWinsRank");

        if ((lifetimeKills > 0 && lifetimeKillsRank <= 0) || (lifetimeWins > 0 && lifetimeWinsRank <= 0)) {
            int[] resolvedRanks = resolveLifetimeRanksAsync(player.getUniqueId(), lifetimeKills, lifetimeWins);
            if (lifetimeKills > 0 && lifetimeKillsRank <= 0 && resolvedRanks[0] > 0) {
                lifetimeKillsRank = resolvedRanks[0];
            }
            if (lifetimeWins > 0 && lifetimeWinsRank <= 0 && resolvedRanks[1] > 0) {
                lifetimeWinsRank = resolvedRanks[1];
            }
        }

        int quickestDetectiveWinSeconds = quickestCounter(stats, QUICKEST_DETECTIVE_WIN_SECONDS_KEY, "quickestDetectiveWinSeconds");
        int quickestMurdererWinSeconds = quickestCounter(stats, QUICKEST_MURDERER_WIN_SECONDS_KEY, "quickestMurdererWinSeconds");

        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Bow Kills: " + ChatColor.GREEN + formatNumber(bowKills));
        lore.add(ChatColor.GRAY + "Knife Kills: " + ChatColor.GREEN + formatNumber(knifeKills));
        lore.add(ChatColor.GRAY + "Thrown Knife Kills: " + ChatColor.GREEN + formatNumber(thrownKnifeKills));
        lore.add(ChatColor.GRAY + "Lifetime Kills: " + ChatColor.GREEN + formatNumber(lifetimeKills)
                + " " + rankSuffix(lifetimeKillsRank, lifetimeKills));
        lore.add("");
        lore.add(ChatColor.GRAY + "Games: " + ChatColor.GREEN + formatNumber(games));
        lore.add(ChatColor.GRAY + "Detective Wins: " + ChatColor.GREEN + formatNumber(detectiveWins));
        lore.add(ChatColor.GRAY + "Murderer Wins: " + ChatColor.GREEN + formatNumber(murdererWins));
        lore.add(ChatColor.GRAY + "Kills as Hero: " + ChatColor.GREEN + formatNumber(killsAsHero));
        lore.add(ChatColor.GRAY + "Lifetime Wins: " + ChatColor.GREEN + formatNumber(lifetimeWins)
                + " " + rankSuffix(lifetimeWinsRank, lifetimeWins));
        lore.add("");
        lore.add(ChatColor.GRAY + "Quickest Detective Win Time: " + ChatColor.GREEN + formatSeconds(quickestDetectiveWinSeconds));
        lore.add(ChatColor.GRAY + "Quickest Murderer Win Time: " + ChatColor.GREEN + formatSeconds(quickestMurdererWinSeconds));

        return item(Material.PAPER, ChatColor.GREEN + MODE_NAME + " Mode Statistics", lore);
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        if (click.getRawSlot() == CLOSE_SLOT) {
            click.getPlayer().closeInventory();
        }
    }

    public void refreshIfOpen(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        InventoryView view = player.getOpenInventory();
        if (view == null) {
            return;
        }
        Inventory top = view.getTopInventory();
        if (top == null || !(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        Menu holderMenu = ((MenuHolder) top.getHolder()).getMenu();
        if (holderMenu != this) {
            return;
        }
        set(top, STATS_SLOT, buildStatsItem(player));
    }

    private Profile resolveProfileSnapshot(Player player) {
        if (player == null || player.getUniqueId() == null) {
            return null;
        }
        UUID playerId = player.getUniqueId();
        CachedProfile cached = liveProfileCacheByPlayer.get(playerId);
        long now = System.currentTimeMillis();
        if (cached != null) {
            if (now - cached.loadedAtMillis <= LIVE_PROFILE_CACHE_TTL_MILLIS) {
                return cached.profile;
            }
            liveProfileCacheByPlayer.remove(playerId, cached);
        }
        return corePlugin == null ? null : corePlugin.getProfile(playerId);
    }

    private void requestLiveProfileRefresh(Player player) {
        if (player == null || !player.isOnline() || player.getUniqueId() == null) {
            return;
        }
        if (corePlugin == null || !corePlugin.isEnabled() || !corePlugin.isMongoEnabled()) {
            return;
        }
        ProfileStore store = corePlugin.getProfileStore();
        if (store == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!liveProfileRefreshInFlight.add(playerId)) {
            return;
        }
        String playerName = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(corePlugin, () -> {
            Profile loaded = null;
            try {
                loaded = store.load(playerId, playerName);
            } catch (Exception ignored) {
                loaded = null;
            }
            final Profile refreshed = loaded;
            try {
                Bukkit.getScheduler().runTask(corePlugin, () -> {
                    liveProfileRefreshInFlight.remove(playerId);
                    if (refreshed == null) {
                        return;
                    }
                    if (liveProfileCacheByPlayer.size() >= LIVE_PROFILE_CACHE_MAX_ENTRIES) {
                        liveProfileCacheByPlayer.clear();
                    }
                    liveProfileCacheByPlayer.put(playerId, new CachedProfile(refreshed, System.currentTimeMillis()));
                    // Pull fresh rank positions on menu-open after profile stats refresh.
                    rankCacheByPlayer.remove(playerId);
                    Player viewer = Bukkit.getPlayer(playerId);
                    if (viewer != null && viewer.isOnline()) {
                        refreshIfOpen(viewer);
                    }
                });
            } catch (Exception ex) {
                liveProfileRefreshInFlight.remove(playerId);
            }
        });
    }

    private static String resolveTitle(ServerType serverType) {
        ServerType safe = serverType == null ? ServerType.UNKNOWN : serverType;
        return HubMessageUtil.gameDisplayName(safe) + " Statistics";
    }

    private int counter(Stats stats, String key) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return 0;
        }
        return Math.max(0, stats.getCustomCounter(key));
    }

    private int counter(Stats stats, String primaryKey, String fallbackKey) {
        if (stats == null) {
            return 0;
        }
        int value = counter(stats, primaryKey);
        if (fallbackKey == null || fallbackKey.trim().isEmpty()) {
            return value;
        }
        return Math.max(value, counter(stats, fallbackKey));
    }

    private int quickestCounter(Stats stats, String primaryKey, String fallbackKey) {
        if (stats == null) {
            return 0;
        }
        int primary = counter(stats, primaryKey);
        int fallback = counter(stats, fallbackKey);
        if (primary <= 0) {
            return fallback;
        }
        if (fallback <= 0) {
            return primary;
        }
        return Math.min(primary, fallback);
    }

    private int[] resolveLifetimeRanksAsync(UUID playerId, int lifetimeKills, int lifetimeWins) {
        if (playerId == null) {
            return new int[] {0, 0};
        }
        long now = System.currentTimeMillis();
        CachedRanks cached = rankCacheByPlayer.get(playerId);
        if (cached != null
                && cached.matches(lifetimeKills, lifetimeWins)
                && now - cached.loadedAtMillis <= RANK_CACHE_TTL_MILLIS) {
            return new int[] {cached.killsRank, cached.winsRank};
        }
        if (cached != null && now - cached.loadedAtMillis > RANK_CACHE_TTL_MILLIS) {
            rankCacheByPlayer.remove(playerId, cached);
            cached = null;
        }
        scheduleLifetimeRankRefresh(playerId, lifetimeKills, lifetimeWins);
        if (cached != null && cached.matches(lifetimeKills, lifetimeWins)) {
            return new int[] {cached.killsRank, cached.winsRank};
        }
        return new int[] {0, 0};
    }

    private void scheduleLifetimeRankRefresh(UUID playerId, int lifetimeKills, int lifetimeWins) {
        if (playerId == null || (lifetimeKills <= 0 && lifetimeWins <= 0)) {
            return;
        }
        if (corePlugin == null
                || !corePlugin.isMongoEnabled()
                || corePlugin.getMongoManager() == null
                || !corePlugin.isEnabled()) {
            return;
        }
        if (!rankRefreshInFlight.add(playerId)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(corePlugin, () -> {
            int[] resolvedRanks = queryLifetimeRanksFromMongo(lifetimeKills, lifetimeWins);
            try {
                Bukkit.getScheduler().runTask(corePlugin, () -> {
                    rankRefreshInFlight.remove(playerId);
                    if ((lifetimeKills > 0 && resolvedRanks[0] <= 0)
                            || (lifetimeWins > 0 && resolvedRanks[1] <= 0)) {
                        return;
                    }
                    if (rankCacheByPlayer.size() >= RANK_CACHE_MAX_ENTRIES) {
                        rankCacheByPlayer.clear();
                    }
                    rankCacheByPlayer.put(playerId, new CachedRanks(
                            lifetimeKills,
                            lifetimeWins,
                            resolvedRanks[0],
                            resolvedRanks[1],
                            System.currentTimeMillis()
                    ));
                    Player viewer = Bukkit.getPlayer(playerId);
                    if (viewer != null && viewer.isOnline()) {
                        refreshIfOpen(viewer);
                    }
                });
            } catch (Exception ex) {
                rankRefreshInFlight.remove(playerId);
            }
        });
    }

    private int[] queryLifetimeRanksFromMongo(int lifetimeKills, int lifetimeWins) {
        int killsRank = lifetimeKills > 0 ? 1 : 0;
        int winsRank = lifetimeWins > 0 ? 1 : 0;
        if (killsRank == 0 && winsRank == 0) {
            return new int[] {0, 0};
        }
        if (corePlugin == null || !corePlugin.isMongoEnabled() || corePlugin.getMongoManager() == null) {
            return new int[] {0, 0};
        }
        MongoCollection<Document> profiles = corePlugin.getMongoManager().getProfiles();
        if (profiles == null) {
            return new int[] {0, 0};
        }
        try {
            if (killsRank > 0) {
                killsRank += countHigherStats(profiles, "stats.murderMysteryKills", "stats.kills", lifetimeKills);
            }
            if (winsRank > 0) {
                winsRank += countHigherStats(profiles, "stats.murderMysteryWins", "stats.wins", lifetimeWins);
            }
        } catch (Exception ignored) {
            // Fall back to placeholder rank values when rank query is unavailable.
            return new int[] {0, 0};
        }
        return new int[] {killsRank, winsRank};
    }

    private int countHigherStats(MongoCollection<Document> profiles,
                                 String primaryField,
                                 String legacyField,
                                 int threshold) {
        if (profiles == null || threshold < 0) {
            return 0;
        }
        long modernCount = profiles.countDocuments(Filters.gt(primaryField, threshold));
        long legacyCount = profiles.countDocuments(Filters.and(
                Filters.exists(primaryField, false),
                Filters.gt(legacyField, threshold)
        ));
        long total = modernCount + legacyCount;
        if (total <= 0L) {
            return 0;
        }
        if (total > Integer.MAX_VALUE - 1L) {
            return Integer.MAX_VALUE - 1;
        }
        return (int) total;
    }

    private String formatNumber(int value) {
        return numberFormat.format(Math.max(0, value));
    }

    private String rankSuffix(int rank, int statValue) {
        if (rank > 0) {
            return ChatColor.GREEN + "(#" + formatNumber(rank) + ")";
        }
        if (statValue <= 0) {
            return ChatColor.DARK_GRAY + "(Unranked)";
        }
        return ChatColor.GREEN + "(#-)";
    }

    private String formatSeconds(int totalSeconds) {
        int safeSeconds = Math.max(0, totalSeconds);
        int minutes = safeSeconds / 60;
        int seconds = safeSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static final class CachedProfile {
        private final Profile profile;
        private final long loadedAtMillis;

        private CachedProfile(Profile profile, long loadedAtMillis) {
            this.profile = profile;
            this.loadedAtMillis = Math.max(0L, loadedAtMillis);
        }
    }

    private static final class CachedRanks {
        private final int lifetimeKills;
        private final int lifetimeWins;
        private final int killsRank;
        private final int winsRank;
        private final long loadedAtMillis;

        private CachedRanks(int lifetimeKills, int lifetimeWins, int killsRank, int winsRank, long loadedAtMillis) {
            this.lifetimeKills = Math.max(0, lifetimeKills);
            this.lifetimeWins = Math.max(0, lifetimeWins);
            this.killsRank = Math.max(0, killsRank);
            this.winsRank = Math.max(0, winsRank);
            this.loadedAtMillis = Math.max(0L, loadedAtMillis);
        }

        private boolean matches(int kills, int wins) {
            return this.lifetimeKills == Math.max(0, kills)
                    && this.lifetimeWins == Math.max(0, wins);
        }
    }
}
