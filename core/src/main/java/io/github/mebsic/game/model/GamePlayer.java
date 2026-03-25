package io.github.mebsic.game.model;

import java.util.UUID;

public class GamePlayer {
    private final UUID uuid;
    private boolean alive;
    private int kills;

    public GamePlayer(UUID uuid) {
        this.uuid = uuid;
        this.alive = true;
        this.kills = 0;
    }

    public UUID getUuid() {
        return uuid;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public int getKills() {
        return kills;
    }

    public void addKill() {
        kills++;
    }

    public void resetKills() {
        kills = 0;
    }
}
