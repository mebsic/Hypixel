package io.github.mebsic.core.server;

public interface GameTypePlayerCountProvider {
    int getMaxPlayers(ServerType type);
}
