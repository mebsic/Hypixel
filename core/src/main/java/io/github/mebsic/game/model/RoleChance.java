package io.github.mebsic.game.model;

import java.util.UUID;

public class RoleChance {
    private final UUID uuid;
    private double murdererChance;
    private double detectiveChance;

    public RoleChance(UUID uuid, double murdererChance, double detectiveChance) {
        this.uuid = uuid;
        this.murdererChance = murdererChance;
        this.detectiveChance = detectiveChance;
    }

    public UUID getUuid() {
        return uuid;
    }

    public double getMurdererChance() {
        return murdererChance;
    }

    public double getDetectiveChance() {
        return detectiveChance;
    }

    public void adjustMurdererChance(double delta, double min, double max) {
        murdererChance = clamp(murdererChance + delta, min, max);
    }

    public void adjustDetectiveChance(double delta, double min, double max) {
        detectiveChance = clamp(detectiveChance + delta, min, max);
    }

    private double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
