package io.github.mebsic.game.map;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public class GameMap {
    private final String name;
    private final String displayName;
    private final List<Location> spawnPoints;
    private final List<Location> dropItemSpawns;
    private final boolean nightTime;

    public GameMap(String name, boolean nightTime) {
        this(name, name, nightTime);
    }

    public GameMap(String name, String displayName, boolean nightTime) {
        this.name = safe(name);
        this.displayName = safe(displayName).isEmpty() ? safe(name) : safe(displayName);
        this.nightTime = nightTime;
        this.spawnPoints = new ArrayList<>();
        this.dropItemSpawns = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Location> getSpawnPoints() {
        return spawnPoints;
    }

    public List<Location> getDropItemSpawns() {
        return dropItemSpawns;
    }

    public boolean isNightTime() {
        return nightTime;
    }

    private String safe(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
