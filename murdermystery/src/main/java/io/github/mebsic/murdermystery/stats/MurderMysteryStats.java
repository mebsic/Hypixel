package io.github.mebsic.murdermystery.stats;

import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.service.CoreApi;

import java.util.UUID;

public final class MurderMysteryStats {
    public static final String TOKENS = "murdermystery.tokens";
    private static final String LEGACY_TOKENS = "tokens";
    public static final String BOW_KILLS = "murdermystery.bowKills";
    public static final String KNIFE_KILLS = "murdermystery.knifeKills";
    public static final String THROWN_KNIFE_KILLS = "murdermystery.thrownKnifeKills";
    public static final String WINS_AS_DETECTIVE = "murdermystery.winsAsDetective";
    public static final String WINS_AS_MURDERER = "murdermystery.winsAsMurderer";
    public static final String KILLS_AS_MURDERER = "murdermystery.killsAsMurderer";
    public static final String KILLS_AS_HERO = "murdermystery.killsAsHero";
    public static final String QUICKEST_DETECTIVE_WIN_SECONDS = "murdermystery.quickestDetectiveWinSeconds";
    public static final String QUICKEST_MURDERER_WIN_SECONDS = "murdermystery.quickestMurdererWinSeconds";
    public static final String HINTS_ENABLED = "murdermystery.hintsEnabled";
    private static final int HINTS_STATE_ENABLED = 1;
    private static final int HINTS_STATE_DISABLED = 2;

    private MurderMysteryStats() {
    }

    public static int getWinsAsDetective(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(WINS_AS_DETECTIVE);
    }

    public static int getWinsAsMurderer(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(WINS_AS_MURDERER);
    }

    public static void addDetectiveWin(Stats stats) {
        if (stats == null) {
            return;
        }
        stats.addCustomCounter(WINS_AS_DETECTIVE, 1);
    }

    public static void addMurdererWin(Stats stats) {
        if (stats == null) {
            return;
        }
        stats.addCustomCounter(WINS_AS_MURDERER, 1);
    }

    public static int getKillsAsMurderer(Stats stats) {
        if (stats == null) {
            return 0;
        }
        return stats.getCustomCounter(KILLS_AS_MURDERER);
    }

    public static void addMurdererKills(Stats stats, int amount) {
        if (stats == null || amount <= 0) {
            return;
        }
        stats.addCustomCounter(KILLS_AS_MURDERER, amount);
    }

    public static void addBowKills(Stats stats, int amount) {
        addPositiveCounter(stats, BOW_KILLS, amount);
    }

    public static void addKnifeKills(Stats stats, int amount) {
        addPositiveCounter(stats, KNIFE_KILLS, amount);
    }

    public static void addThrownKnifeKills(Stats stats, int amount) {
        addPositiveCounter(stats, THROWN_KNIFE_KILLS, amount);
    }

    public static void addHeroKills(Stats stats, int amount) {
        addPositiveCounter(stats, KILLS_AS_HERO, amount);
    }

    public static void updateQuickestDetectiveWinSeconds(Stats stats, int elapsedSeconds) {
        updateQuickestCounter(stats, QUICKEST_DETECTIVE_WIN_SECONDS, elapsedSeconds);
    }

    public static void updateQuickestMurdererWinSeconds(Stats stats, int elapsedSeconds) {
        updateQuickestCounter(stats, QUICKEST_MURDERER_WIN_SECONDS, elapsedSeconds);
    }

    public static int getTokens(Stats stats) {
        if (stats == null) {
            return 0;
        }
        int namespaced = stats.getCustomCounter(TOKENS);
        if (namespaced > 0) {
            return namespaced;
        }
        return stats.getCustomCounter(LEGACY_TOKENS);
    }

    public static int getTokens(CoreApi coreApi, UUID uuid) {
        if (coreApi == null || uuid == null) {
            return 0;
        }
        int namespaced = coreApi.getCounter(uuid, TOKENS);
        if (namespaced > 0) {
            return namespaced;
        }
        return coreApi.getCounter(uuid, LEGACY_TOKENS);
    }

    public static void addTokens(Stats stats, int amount) {
        if (stats == null || amount <= 0) {
            return;
        }
        migrateLegacyTokens(stats);
        stats.addCustomCounter(TOKENS, amount);
    }

    public static void addTokens(CoreApi coreApi, UUID uuid, int amount) {
        if (coreApi == null || uuid == null || amount <= 0) {
            return;
        }
        migrateLegacyTokens(coreApi, uuid);
        coreApi.addCounter(uuid, TOKENS, amount);
    }

    public static boolean spendTokens(CoreApi coreApi, UUID uuid, int amount) {
        if (coreApi == null || uuid == null) {
            return false;
        }
        if (amount <= 0) {
            return true;
        }
        migrateLegacyTokens(coreApi, uuid);
        return coreApi.spendCounter(uuid, TOKENS, amount);
    }

    public static boolean areHintsEnabled(Stats stats) {
        if (stats == null) {
            return true;
        }
        if (!hasCustomCounter(stats, HINTS_ENABLED)) {
            return true;
        }
        return stats.getCustomCounter(HINTS_ENABLED) != HINTS_STATE_DISABLED;
    }

    public static boolean setHintsEnabled(Stats stats, boolean enabled) {
        if (stats == null) {
            return false;
        }
        int desired = enabled ? HINTS_STATE_ENABLED : HINTS_STATE_DISABLED;
        if (!hasCustomCounter(stats, HINTS_ENABLED)) {
            stats.addCustomCounter(HINTS_ENABLED, desired);
            return true;
        }
        int current = Math.max(0, stats.getCustomCounter(HINTS_ENABLED));
        if (current == desired) {
            return false;
        }
        stats.addCustomCounter(HINTS_ENABLED, desired - current);
        return true;
    }

    public static boolean ensureHintsEnabledCounterExists(Stats stats) {
        if (stats == null || hasCustomCounter(stats, HINTS_ENABLED)) {
            return false;
        }
        stats.addCustomCounter(HINTS_ENABLED, HINTS_STATE_ENABLED);
        return true;
    }

    private static void migrateLegacyTokens(Stats stats) {
        if (stats == null) {
            return;
        }
        int legacy = stats.getCustomCounter(LEGACY_TOKENS);
        if (legacy <= 0) {
            return;
        }
        stats.addCustomCounter(TOKENS, legacy);
        stats.addCustomCounter(LEGACY_TOKENS, -legacy);
    }

    private static void migrateLegacyTokens(CoreApi coreApi, UUID uuid) {
        if (coreApi == null || uuid == null) {
            return;
        }
        int legacy = coreApi.getCounter(uuid, LEGACY_TOKENS);
        if (legacy <= 0) {
            return;
        }
        coreApi.addCounter(uuid, TOKENS, legacy);
        coreApi.spendCounter(uuid, LEGACY_TOKENS, legacy);
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
