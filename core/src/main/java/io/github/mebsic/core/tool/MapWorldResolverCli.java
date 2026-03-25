package io.github.mebsic.core.tool;

import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.store.MapConfigStore;

import java.util.Locale;

public final class MapWorldResolverCli {
    private MapWorldResolverCli() {
    }

    public static void main(String[] args) {
        String mongoUri = arg(args, 0);
        String mongoDatabase = arg(args, 1);
        String gameType = arg(args, 2);
        String serverKind = arg(args, 3);

        if (mongoUri.isEmpty() || mongoDatabase.isEmpty()) {
            return;
        }

        String gameKey = MapConfigStore.normalizeGameKey(gameType);
        if (gameKey.isEmpty()) {
            gameKey = MapConfigStore.DEFAULT_GAME_KEY;
        }
        boolean hubServer = serverKind.toLowerCase(Locale.ROOT).contains("hub");

        MongoManager mongo = null;
        try {
            mongo = new MongoManager(mongoUri, mongoDatabase);
            MapConfigStore store = new MapConfigStore(mongo);
            store.ensureDefaults(gameKey);

            String worldDirectory = store.resolveWorldDirectory(gameKey, hubServer);
            if (worldDirectory.isEmpty() && !MapConfigStore.DEFAULT_GAME_KEY.equals(gameKey)) {
                store.ensureDefaults(MapConfigStore.DEFAULT_GAME_KEY);
                worldDirectory = store.resolveWorldDirectory(MapConfigStore.DEFAULT_GAME_KEY, hubServer);
            }
            printIfNotBlank(worldDirectory);
        } catch (Exception ignored) {
            // Resolver intentionally emits no fallback when Mongo is unavailable.
        } finally {
            if (mongo != null) {
                mongo.close();
            }
        }
    }

    private static String arg(String[] args, int index) {
        if (args == null || index < 0 || index >= args.length || args[index] == null) {
            return "";
        }
        String value = args[index].trim();
        return value.isEmpty() ? "" : value;
    }

    private static void printIfNotBlank(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        System.out.print(value.trim());
    }
}
