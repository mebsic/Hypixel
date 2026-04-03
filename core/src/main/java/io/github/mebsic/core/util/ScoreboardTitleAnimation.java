package io.github.mebsic.core.util;

import org.bukkit.ChatColor;

public final class ScoreboardTitleAnimation {
    private static final long TICK_MILLIS = 50L;
    private static final long SWEEP_STEP_TICKS = 2L;
    private static final long FLASH_STEP_TICKS = 6L; // 0.3 seconds per flash frame
    private static final long POST_SWEEP_WHITE_HOLD_TICKS = 20L; // 1.0 seconds
    private static final long CYCLE_DELAY_TICKS = 100L; // 5 seconds
    private static final long FLASH_FRAME_COUNT = 2L; // yellow -> white (single pulse)

    private final String gameType;
    private final String yellowTitle;
    private final String whiteTitle;
    private long animationStartedAtMillis;

    public ScoreboardTitleAnimation(String gameType) {
        String safe = gameType == null ? "" : gameType.trim();
        if (safe.isEmpty()) {
            safe = "GAME";
        }
        this.gameType = safe;
        this.yellowTitle = style(ChatColor.YELLOW, this.gameType);
        this.whiteTitle = style(ChatColor.WHITE, this.gameType);
        this.animationStartedAtMillis = -1L;
    }

    public synchronized String resolve(boolean animate) {
        if (!animate) {
            animationStartedAtMillis = -1L;
            return yellowTitle;
        }

        long now = System.currentTimeMillis();
        if (animationStartedAtMillis < 0L) {
            animationStartedAtMillis = now;
        }

        long elapsedTicks = Math.max(0L, (now - animationStartedAtMillis) / TICK_MILLIS);
        long cycleTicks = cycleLengthTicks();
        if (cycleTicks <= 0L) {
            return yellowTitle;
        }
        long tickInCycle = elapsedTicks % cycleTicks;
        long sweepTicks = sweepLengthTicks();
        if (tickInCycle < sweepTicks) {
            int sweepFrame = (int) (tickInCycle / SWEEP_STEP_TICKS);
            return buildSweepFrame(sweepFrame);
        }

        long flashOffset = tickInCycle - sweepTicks;
        if (flashOffset < POST_SWEEP_WHITE_HOLD_TICKS) {
            return whiteTitle;
        }

        flashOffset -= POST_SWEEP_WHITE_HOLD_TICKS;
        long flashTicks = FLASH_FRAME_COUNT * FLASH_STEP_TICKS;
        if (flashOffset < flashTicks) {
            long flashFrame = flashOffset / FLASH_STEP_TICKS;
            return flashFrame % 2L == 0L ? yellowTitle : whiteTitle;
        }

        return yellowTitle;
    }

    public synchronized void restart() {
        animationStartedAtMillis = -1L;
    }

    private long sweepLengthTicks() {
        return (gameType.length() + 2L) * SWEEP_STEP_TICKS;
    }

    private long cycleLengthTicks() {
        return sweepLengthTicks() + POST_SWEEP_WHITE_HOLD_TICKS + (FLASH_FRAME_COUNT * FLASH_STEP_TICKS) + CYCLE_DELAY_TICKS;
    }

    private String buildSweepFrame(int frame) {
        int length = gameType.length();
        if (frame <= 0) {
            return yellowTitle;
        }
        if (frame > length) {
            return whiteTitle;
        }

        int goldIndex = frame - 1;
        StringBuilder out = new StringBuilder(gameType.length() * 6);
        if (goldIndex > 0) {
            out.append(style(ChatColor.WHITE, gameType.substring(0, goldIndex)));
        }
        out.append(style(ChatColor.GOLD, String.valueOf(gameType.charAt(goldIndex))));
        if (goldIndex + 1 < length) {
            out.append(style(ChatColor.YELLOW, gameType.substring(goldIndex + 1)));
        }
        return out.toString();
    }

    private String style(ChatColor color, String text) {
        return color.toString() + ChatColor.BOLD + text;
    }
}
