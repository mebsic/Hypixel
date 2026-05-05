package io.github.mebsic.core.util;

public final class HycopyExperienceUtil {
    private HycopyExperienceUtil() {
    }

    public static final long BASE = 10000L;
    public static final long GROWTH = 2500L;
    private static final int BALANCE_START_LEVEL = 100;
    private static final int BALANCE_MAX_LEVEL = 300;
    private static final double MAX_EXPERIENCE_GAIN_MULTIPLIER = 2.0D;

    public static int getLevel(long exp) {
        if (exp <= 0) {
            return 1;
        }
        // Inverse of:
        // exp = n * BASE + (n * (n - 1) * GROWTH) / 2
        // where n = level - 1
        double b = BASE - (GROWTH / 2.0D);
        double n = Math.floor((-b + Math.sqrt((b * b) + (2.0D * GROWTH * exp))) / GROWTH);
        return Math.max(1, (int) n + 1);
    }

    public static long getTotalExpForLevel(int level) {
        int lvl = Math.max(1, level);
        long n = lvl - 1L;
        return n * BASE + (n * (n - 1) * GROWTH) / 2L;
    }

    public static long getExpToNext(int level) {
        int lvl = Math.max(1, level);
        return BASE + (long) (lvl - 1) * GROWTH;
    }

    public static float getProgressToNext(long exp) {
        int level = getLevel(exp);
        long currentLevelExp = getTotalExpForLevel(level);
        long next = getExpToNext(level);
        long progress = exp - currentLevelExp;
        if (next <= 0) {
            return 0f;
        }
        float value = progress / (float) next;
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    public static double getExperienceGainMultiplier(int level) {
        int safeLevel = Math.max(1, level);
        if (safeLevel <= BALANCE_START_LEVEL) {
            return 1.0D;
        }
        if (safeLevel >= BALANCE_MAX_LEVEL) {
            return MAX_EXPERIENCE_GAIN_MULTIPLIER;
        }
        double progress = (safeLevel - BALANCE_START_LEVEL) / (double) (BALANCE_MAX_LEVEL - BALANCE_START_LEVEL);
        return 1.0D + ((MAX_EXPERIENCE_GAIN_MULTIPLIER - 1.0D) * progress);
    }

    public static long scaleExperienceGain(long experience, int level) {
        long safeExperience = Math.max(0L, experience);
        if (safeExperience == 0L) {
            return 0L;
        }
        double multiplier = getExperienceGainMultiplier(level);
        long scaled = Math.round(safeExperience * multiplier);
        return Math.max(safeExperience, scaled);
    }
}
