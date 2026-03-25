package io.github.mebsic.core.server;

import org.bukkit.configuration.file.FileConfiguration;

public final class ServerTypeResolver {
    private ServerTypeResolver() {
    }

    public static ServerType resolve(FileConfiguration config, ServerType fallback) {
        String configured = config.getString("server.type", "");
        if (configured != null && !configured.trim().isEmpty()) {
            ServerType explicit = ServerType.fromConfig(configured);
            if (explicit != ServerType.UNKNOWN) {
                return explicit;
            }
        }
        return fallback == null ? ServerType.UNKNOWN : fallback;
    }
}
