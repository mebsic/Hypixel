package io.github.mebsic.core.model;

import io.github.mebsic.core.server.ServerType;

import java.util.UUID;

public class GameResult {
    private final UUID uuid;
    private final ServerType gameType;
    private final boolean win;
    private final int kills;

    public GameResult(UUID uuid, ServerType gameType, boolean win, int kills) {
        this.uuid = uuid;
        this.gameType = gameType == null ? ServerType.UNKNOWN : gameType;
        this.win = win;
        this.kills = kills;
    }

    public UUID getUuid() {
        return uuid;
    }

    public ServerType getGameType() {
        return gameType;
    }

    public boolean isWin() {
        return win;
    }

    public int getKills() {
        return kills;
    }
}
