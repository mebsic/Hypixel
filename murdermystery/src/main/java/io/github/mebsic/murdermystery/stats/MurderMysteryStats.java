package io.github.mebsic.murdermystery.stats;

import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.service.CoreApi;

import java.util.UUID;

public final class MurderMysteryStats {
    private static final int HINTS_STATE_ENABLED = 1;
    private static final int HINTS_STATE_DISABLED = 2;

    private MurderMysteryStats() {
    }

    public static int getWinsAsDetective(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(MongoManager.MURDER_MYSTERY_WINS_AS_DETECTIVE_KEY);
    }

    public static int getWinsAsMurderer(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(MongoManager.MURDER_MYSTERY_WINS_AS_MURDERER_KEY);
    }

    public static void addDetectiveWin(Stats stats) {
        if (stats == null) {
            return;
        }
        stats.addCustomCounter(MongoManager.MURDER_MYSTERY_WINS_AS_DETECTIVE_KEY, 1);
    }

    public static void addMurdererWin(Stats stats) {
        if (stats == null) {
            return;
        }
        stats.addCustomCounter(MongoManager.MURDER_MYSTERY_WINS_AS_MURDERER_KEY, 1);
    }

    public static int getKillsAsMurderer(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(MongoManager.MURDER_MYSTERY_KILLS_AS_MURDERER_KEY);
    }

    public static void addMurdererKills(Stats stats, int amount) {
        if (stats == null || amount <= 0) {
            return;
        }
        stats.addCustomCounter(MongoManager.MURDER_MYSTERY_KILLS_AS_MURDERER_KEY, amount);
    }

    public static void addBowKills(Stats stats, int amount) {
        addPositiveCounter(stats, MongoManager.MURDER_MYSTERY_BOW_KILLS_KEY, amount);
    }

    public static void addKnifeKills(Stats stats, int amount) {
        addPositiveCounter(stats, MongoManager.MURDER_MYSTERY_KNIFE_KILLS_KEY, amount);
    }

    public static void addThrownKnifeKills(Stats stats, int amount) {
        addPositiveCounter(stats, MongoManager.MURDER_MYSTERY_THROWN_KNIFE_KILLS_KEY, amount);
    }

    public static void addHeroKills(Stats stats, int amount) {
        addPositiveCounter(stats, MongoManager.MURDER_MYSTERY_KILLS_AS_HERO_KEY, amount);
    }

    public static void updateQuickestDetectiveWinSeconds(Stats stats, int elapsedSeconds) {
        updateQuickestCounter(stats, MongoManager.MURDER_MYSTERY_QUICKEST_DETECTIVE_WIN_SECONDS_KEY, elapsedSeconds);
    }

    public static void updateQuickestMurdererWinSeconds(Stats stats, int elapsedSeconds) {
        updateQuickestCounter(stats, MongoManager.MURDER_MYSTERY_QUICKEST_MURDERER_WIN_SECONDS_KEY, elapsedSeconds);
    }

    public static int getTokens(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(MongoManager.MURDER_MYSTERY_TOKENS_KEY);
    }

    public static int getTokens(CoreApi coreApi, UUID uuid) {
        if (coreApi == null || uuid == null) {
            return 0;
        }
        return coreApi.getCounter(uuid, MongoManager.MURDER_MYSTERY_TOKENS_KEY);
    }

    public static void addTokens(Stats stats, int amount) {
        if (stats == null || amount <= 0) {
            return;
        }
        stats.addCustomCounter(MongoManager.MURDER_MYSTERY_TOKENS_KEY, amount);
    }

    public static void addTokens(CoreApi coreApi, UUID uuid, int amount) {
        if (coreApi == null || uuid == null || amount <= 0) {
            return;
        }
        coreApi.addCounter(uuid, MongoManager.MURDER_MYSTERY_TOKENS_KEY, amount);
    }

    public static boolean spendTokens(CoreApi coreApi, UUID uuid, int amount) {
        if (coreApi == null || uuid == null) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        return coreApi.spendCounter(uuid, MongoManager.MURDER_MYSTERY_TOKENS_KEY, amount);
    }

    public static boolean areHintsEnabled(Stats stats) {
        if (stats == null) {
            return true;
        }
        if (!hasCustomCounter(stats, MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY)) {
            return true;
        }
        return stats.getCustomCounter(MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY) != HINTS_STATE_DISABLED;
    }

    public static boolean setHintsEnabled(Stats stats, boolean enabled) {
        if (stats == null) {
            return false;
        }
        int desired = enabled ? HINTS_STATE_ENABLED : HINTS_STATE_DISABLED;
        if (!hasCustomCounter(stats, MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY)) {
            stats.addCustomCounter(MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY, desired);
            return true;
        }
        int current = Math.max(0, stats.getCustomCounter(MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY));
        if (current == desired) {
            return false;
        }
        stats.addCustomCounter(MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY, desired - current);
        return true;
    }

    public static boolean ensureHintsEnabledCounterExists(Stats stats) {
        if (stats == null || hasCustomCounter(stats, MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY)) {
            return false;
        }
        stats.addCustomCounter(MongoManager.MURDER_MYSTERY_HINTS_ENABLED_KEY, HINTS_STATE_ENABLED);
        return true;
    }

    private static void addPositiveCounter(Stats stats, String key, int amount) {
        if (stats == null || key == null || key.trim().isEmpty() || amount <= 0) {
            return;
        }
        stats.addCustomCounter(key, amount);
    }

    private static void updateQuickestCounter(Stats stats, String key, int elapsedSeconds) {
        if (stats == null || key == null || key.trim().isEmpty() || elapsedSeconds <= 0) {
            return;
        }
        int current = stats.getCustomCounter(key);
        if (current <= 1) {
            // Older builds could persist 0:01 from weapon-grant timing; treat that as unset and repair.
            stats.addCustomCounter(key, elapsedSeconds - current);
            return;
        }
        if (elapsedSeconds >= current) {
            return;
        }
        stats.addCustomCounter(key, elapsedSeconds - current);
    }

    private static boolean hasCustomCounter(Stats stats, String key) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return false;
        }
        return stats.getCustomCounters().containsKey(key);
    }
}
