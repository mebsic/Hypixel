package io.github.mebsic.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HypixelExperienceUtilTest {
    @Test
    void levelThresholdsRoundTrip() {
        int[] levels = new int[] {1, 2, 3, 10, 25, 50, 100, 150, 200, 250, 300};
        for (int level : levels) {
            long exp = HypixelExperienceUtil.getTotalExpForLevel(level);
            assertEquals(level, HypixelExperienceUtil.getLevel(exp),
                    "Round-trip level mismatch for level " + level);
        }
    }

    @Test
    void experienceGainMultiplierScalesAtHighLevels() {
        assertEquals(1.0D, HypixelExperienceUtil.getExperienceGainMultiplier(1), 0.0001D);
        assertEquals(1.0D, HypixelExperienceUtil.getExperienceGainMultiplier(100), 0.0001D);
        assertEquals(1.75D, HypixelExperienceUtil.getExperienceGainMultiplier(250), 0.0001D);
        assertEquals(2.0D, HypixelExperienceUtil.getExperienceGainMultiplier(300), 0.0001D);
        assertEquals(2.0D, HypixelExperienceUtil.getExperienceGainMultiplier(500), 0.0001D);
    }

    @Test
    void scaleExperienceGainAppliesMultiplierWithoutReducingBaseExperience() {
        assertEquals(0L, HypixelExperienceUtil.scaleExperienceGain(0L, 250));
        assertEquals(100L, HypixelExperienceUtil.scaleExperienceGain(100L, 50));
        assertEquals(175L, HypixelExperienceUtil.scaleExperienceGain(100L, 250));
    }
}
