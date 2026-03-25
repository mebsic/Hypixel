package io.github.mebsic.core.server;

import java.util.Locale;

public enum ServerType {
    MURDER_MYSTERY_HUB(ServerKind.HUB),
    MURDER_MYSTERY(ServerKind.GAME),
    BUILD(ServerKind.BUILD),
    UNKNOWN(ServerKind.UNKNOWN);

    private final ServerKind kind;

    ServerType(ServerKind kind) {
        this.kind = kind;
    }

    public ServerKind getKind() {
        return kind;
    }

    public boolean isHub() {
        return kind == ServerKind.HUB;
    }

    public boolean isGame() {
        return kind == ServerKind.GAME;
    }

    public boolean isBuild() {
        return kind == ServerKind.BUILD;
    }

    public ServerType toHubType() {
        switch (this) {
            case MURDER_MYSTERY:
            case MURDER_MYSTERY_HUB:
                return MURDER_MYSTERY_HUB;
            default:
                return UNKNOWN;
        }
    }

    public String getId() {
        return name();
    }

    public String getGameTypeDisplayName() {
        String raw = name();
        if (raw.endsWith("_HUB")) {
            raw = raw.substring(0, raw.length() - "_HUB".length());
        }
        if (raw.equals("UNKNOWN")) {
            return "GAME";
        }
        return raw.replace('_', ' ');
    }

    public static ServerType fromConfig(String raw) {
        return fromString(raw);
    }

    public static ServerType fromString(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return UNKNOWN;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (normalized.equals("HUB")) {
            return MURDER_MYSTERY_HUB;
        }
        if (normalized.equals("GAME")) {
            return MURDER_MYSTERY;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }

    public enum ServerKind {
        HUB,
        GAME,
        BUILD,
        UNKNOWN
    }
}
