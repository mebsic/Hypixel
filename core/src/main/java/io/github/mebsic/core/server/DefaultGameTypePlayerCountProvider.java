package io.github.mebsic.core.server;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class DefaultGameTypePlayerCountProvider implements GameTypePlayerCountProvider {
    private final Map<ServerType, Integer> maxPlayersByType;

    public DefaultGameTypePlayerCountProvider() {
        Map<ServerType, Integer> values = new EnumMap<>(ServerType.class);
        values.put(ServerType.MURDER_MYSTERY, 16);
        this.maxPlayersByType = Collections.unmodifiableMap(values);
    }

    @Override
    public int getMaxPlayers(ServerType type) {
        if (type == null || !type.isGame()) {
            return 0;
        }
        Integer value = maxPlayersByType.get(type);
        return value == null ? 0 : value;
    }
}
