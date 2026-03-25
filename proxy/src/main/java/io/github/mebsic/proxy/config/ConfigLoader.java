package io.github.mebsic.proxy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ConfigLoader {
    private final Gson gson;

    public ConfigLoader() {
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public ProxyConfig load(Path dataDir) throws IOException {
        Path jsonFile = dataDir.resolve("config.json");
        ProxyConfig config = Files.exists(jsonFile) ? loadJson(jsonFile) : new ProxyConfig();
        applyEnvironment(config);
        return config;
    }

    private ProxyConfig loadJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                return new ProxyConfig();
            }
            JsonObject root = element.getAsJsonObject();
            if (looksLikeProxyConfig(root)) {
                ProxyConfig config = gson.fromJson(root, ProxyConfig.class);
                return config == null ? new ProxyConfig() : config;
            }
            ProxyConfig config = new ProxyConfig();
            applySharedConfig(config, root);
            return config;
        } catch (JsonParseException ex) {
            return new ProxyConfig();
        }
    }

    private boolean looksLikeProxyConfig(JsonObject root) {
        return root.has("mongoUri")
                || root.has("mongoDatabase")
                || root.has("redisHost")
                || root.has("registryCollection")
                || root.has("registryGroup")
                || root.has("hubServer")
                || root.has("friendsCollection");
    }

    private void applySharedConfig(ProxyConfig config, JsonObject root) {
        JsonObject mongo = object(root, "mongo");
        JsonObject redis = object(root, "redis");
        JsonObject registry = object(root, "registry");
        JsonObject proxy = object(root, "proxy");
        JsonObject motd = object(root, "motd");
        JsonObject hub = object(root, "hub");
        JsonObject friends = object(root, "friends");

        config.setMongoUri(string(mongo, "uri"));
        config.setMongoDatabase(string(mongo, "database"));

        config.setRedisHost(string(redis, "host"));
        config.setRedisPort(intValue(number(redis, "port"), config.getRedisPort()));
        String redisPassword = stringAllowEmpty(redis, "password");
        if (redisPassword != null) {
            config.setRedisPassword(redisPassword);
        }
        config.setRedisDatabase(intValue(number(redis, "database"), config.getRedisDatabase()));

        config.setRegistryCollection(firstNonBlank(
                string(proxy, "registryCollection"),
                string(registry, "collection")));

        String registryGroup = firstNonBlank(
                string(proxy, "registryGroup"),
                string(registry, "group"));
        if (registryGroup != null) {
            config.setRegistryGroup(registryGroup);
        }

        config.setRegistryRefreshSeconds(intValue(
                firstNonNull(number(proxy, "registryRefreshSeconds"), number(registry, "refreshSeconds")),
                config.getRegistryRefreshSeconds()));
        config.setRegistryStaleSeconds(intValue(
                firstNonNull(number(proxy, "registryStaleSeconds"), number(registry, "staleSeconds")),
                config.getRegistryStaleSeconds()));

        config.setFriendsCollection(firstNonBlank(
                string(proxy, "friendsCollection"),
                string(friends, "collection")));

        config.setMotdCollection(string(motd, "collection"));
        config.setMotdDocumentId(string(motd, "documentId"));
        config.setMotdField(string(motd, "field"));
        config.setCacheTtlSeconds(intValue(number(motd, "cacheTtlSeconds"), config.getCacheTtlSeconds()));

        String hubServer = firstNonBlank(
                string(proxy, "hubServer"),
                string(hub, "serverId"));
        if (hubServer != null) {
            config.setHubServer(hubServer);
        }

        config.setIconFile(firstNonBlank(
                string(proxy, "iconFile"),
                string(root, "iconFile")));

        JsonElement gameServersElement = firstNonNull(element(proxy, "gameServers"), element(root, "gameServers"));
        if (gameServersElement != null && gameServersElement.isJsonArray()) {
            List<String> gameServers = new ArrayList<>();
            for (JsonElement item : gameServersElement.getAsJsonArray()) {
                if (item == null || item.isJsonNull()) {
                    continue;
                }
                String server = item.getAsString();
                if (server != null && !server.trim().isEmpty()) {
                    gameServers.add(server.trim());
                }
            }
            config.setGameServers(gameServers);
        }
    }

    private void applyEnvironment(ProxyConfig config) {
        config.setMongoUri(firstNonBlank(env("PROXY_MONGO_URI"), env("MONGO_URI")));
        config.setMongoDatabase(firstNonBlank(env("PROXY_MONGO_DATABASE"), env("MONGO_DATABASE")));

        config.setRedisHost(firstNonBlank(env("PROXY_REDIS_HOST"), env("REDIS_HOST")));
        config.setRedisPort(intValue(firstNonBlank(env("PROXY_REDIS_PORT"), env("REDIS_PORT")), config.getRedisPort()));

        String redisPassword = firstRawNonNull("PROXY_REDIS_PASSWORD", "REDIS_PASSWORD");
        if (redisPassword != null) {
            config.setRedisPassword(redisPassword);
        }

        config.setRedisDatabase(intValue(firstNonBlank(env("PROXY_REDIS_DATABASE"), env("REDIS_DATABASE")), config.getRedisDatabase()));

        config.setRegistryCollection(env("PROXY_REGISTRY_COLLECTION"));
        String group = envRaw("PROXY_REGISTRY_GROUP");
        if (group != null) {
            config.setRegistryGroup(group);
        }

        config.setRegistryRefreshSeconds(intValue(env("PROXY_REGISTRY_REFRESH_SECONDS"), config.getRegistryRefreshSeconds()));
        config.setRegistryStaleSeconds(intValue(env("PROXY_REGISTRY_STALE_SECONDS"), config.getRegistryStaleSeconds()));
        config.setFriendsCollection(env("PROXY_FRIENDS_COLLECTION"));

        config.setMotdCollection(env("PROXY_MOTD_COLLECTION"));
        config.setMotdDocumentId(env("PROXY_MOTD_DOCUMENT_ID"));
        config.setMotdField(env("PROXY_MOTD_FIELD"));
        config.setCacheTtlSeconds(intValue(env("PROXY_CACHE_TTL_SECONDS"), config.getCacheTtlSeconds()));

        String hubServer = envRaw("PROXY_HUB_SERVER");
        if (hubServer != null) {
            config.setHubServer(hubServer);
        }
        config.setIconFile(env("PROXY_ICON_FILE"));
    }

    private JsonObject object(JsonObject parent, String key) {
        JsonElement value = element(parent, key);
        if (value != null && value.isJsonObject()) {
            return value.getAsJsonObject();
        }
        return null;
    }

    private JsonElement element(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key)) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() ? null : value;
    }

    private String string(JsonObject parent, String key) {
        JsonElement value = element(parent, key);
        if (value == null) {
            return null;
        }
        String text = value.getAsString();
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private String stringAllowEmpty(JsonObject parent, String key) {
        JsonElement value = element(parent, key);
        return value == null ? null : value.getAsString();
    }

    private Object number(JsonObject parent, String key) {
        JsonElement value = element(parent, key);
        if (value == null) {
            return null;
        }
        if (!value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (Exception ex) {
            return null;
        }
    }

    private int intValue(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(String.valueOf(raw).trim());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String env(String key) {
        String value = envRaw(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String envRaw(String key) {
        return System.getenv(key);
    }

    private String firstRawNonNull(String firstKey, String secondKey) {
        String first = envRaw(firstKey);
        if (first != null) {
            return first;
        }
        return envRaw(secondKey);
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }
}
