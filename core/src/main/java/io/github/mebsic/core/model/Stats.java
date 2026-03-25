package io.github.mebsic.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Stats {
    private int wins;
    private int kills;
    private int games;
    private final Map<String, Integer> customCounters;

    public Stats() {
        this.customCounters = new HashMap<>();
    }

    public int getWins() {
        return wins;
    }

    public void addWin() {
        wins++;
    }

    public void addWins(int amount) {
        wins += amount;
    }

    public int getKills() {
        return kills;
    }

    public void addKills(int amount) {
        kills += amount;
    }

    public int getGames() {
        return games;
    }

    public void addGame() {
        games++;
    }

    public void addGames(int amount) {
        games += amount;
    }

    public int getCustomCounter(String key) {
        if (key == null || key.trim().isEmpty()) {
            return 0;
        }
        Integer value = customCounters.get(key);
        return value == null ? 0 : Math.max(0, value);
    }

    public void addCustomCounter(String key, int amount) {
        if (key == null || key.trim().isEmpty() || amount == 0) {
            return;
        }
        int current = getCustomCounter(key);
        int next = current + amount;
        if (next < 0) {
            next = 0;
        }
        customCounters.put(key, next);
    }

    public Map<String, Integer> getCustomCounters() {
        return Collections.unmodifiableMap(customCounters);
    }
}
