package io.github.mebsic.core.util;

import io.github.mebsic.core.model.GameResult;

public final class GameRewardUtil {
    private static final long END_OF_GAME_EXPERIENCE = 100L;
    private static final long WIN_EXPERIENCE = 150L;
    private static final long KILL_EXPERIENCE = 25L;
    private static final long NOT_WINNING_EXPERIENCE = 50L;

    private GameRewardUtil() {
    }

    public static ExperienceBreakdown calculateExperienceBreakdown(GameResult result) {
        if (result == null) {
            return new ExperienceBreakdown(0L, 0L, 0L, 0L);
        }
        long endOfGame = END_OF_GAME_EXPERIENCE;
        long win = result.isWin() ? WIN_EXPERIENCE : 0L;
        long kill = Math.max(0, result.getKills()) * KILL_EXPERIENCE;
        long notWinning = result.isWin() ? 0L : NOT_WINNING_EXPERIENCE;
        return new ExperienceBreakdown(endOfGame, win, kill, notWinning);
    }

    public static long calculateTotalExperience(GameResult result) {
        return calculateExperienceBreakdown(result).getTotalExperience();
    }

    public static final class ExperienceBreakdown {
        private final long endOfGameExperience;
        private final long winExperience;
        private final long killExperience;
        private final long consolationExperience;

        public ExperienceBreakdown(long endOfGameExperience,
                                   long winExperience,
                                   long killExperience,
                                   long consolationExperience) {
            this.endOfGameExperience = Math.max(0L, endOfGameExperience);
            this.winExperience = Math.max(0L, winExperience);
            this.killExperience = Math.max(0L, killExperience);
            this.consolationExperience = Math.max(0L, consolationExperience);
        }

        public long getEndOfGameExperience() {
            return endOfGameExperience;
        }

        public long getWinExperience() {
            return winExperience;
        }

        public long getKillExperience() {
            return killExperience;
        }

        public long getConsolationExperience() {
            return consolationExperience;
        }

        public long getTotalExperience() {
            return endOfGameExperience + winExperience + killExperience + consolationExperience;
        }
    }
}
