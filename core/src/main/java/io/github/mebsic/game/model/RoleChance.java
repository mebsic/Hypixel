package io.github.mebsic.game.model;

import java.util.UUID;

public class RoleChance {
    private final UUID uuid;
    private double primaryChance;
    private double secondaryChance;

    public RoleChance(UUID uuid, double primaryChance, double secondaryChance) {
        this.uuid = uuid;
        this.primaryChance = primaryChance;
        this.secondaryChance = secondaryChance;
    }

    public UUID getUuid() {
        return uuid;
    }

    public double getPrimaryChance() {
        return primaryChance;
    }

    public double getSecondaryChance() {
        return secondaryChance;
    }

    public void adjustPrimaryChance(double delta, double min, double max) {
        primaryChance = clamp(primaryChance + delta, min, max);
    }

    public void adjustSecondaryChance(double delta, double min, double max) {
        secondaryChance = clamp(secondaryChance + delta, min, max);
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
