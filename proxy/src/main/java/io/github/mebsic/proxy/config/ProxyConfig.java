package io.github.mebsic.proxy.config;

import io.github.mebsic.proxy.manager.MongoManager;

import java.util.ArrayList;
import java.util.List;

public class ProxyConfig {
    private String mongoUri = "mongodb://mongo:27017";
    private String mongoDatabase = "hypixel";
    private String motdCollection = MongoManager.PROXY_SETTINGS_COLLECTION;
    private String motdDocumentId = MongoManager.PROXY_SETTINGS_MOTD_DOCUMENT_ID;
    private String motdField = MongoManager.PROXY_SETTINGS_MOTD_TEXT_FIELD;
    private int cacheTtlSeconds = 5;
    private String iconFile = "server-icon.png";
    private String hubServer = "";
    private String registryCollection = MongoManager.SERVER_REGISTRY_COLLECTION;
    private String registryGroup = "";
    private String friendsCollection = MongoManager.FRIENDS_COLLECTION;
    private int registryRefreshSeconds = 1;
    private int registryStaleSeconds = 20;
    private String redisHost = "redis";
    private int redisPort = 6379;
    private String redisPassword = "";
    private int redisDatabase = 0;
    private List<String> gameServers = new ArrayList<>();

    public ProxyConfig() {
    }

    public String getMongoUri() {
        return mongoUri;
    }

    public String getMongoDatabase() {
        return mongoDatabase;
    }

    public String getMotdCollection() {
        return motdCollection;
    }

    public String getMotdDocumentId() {
        return motdDocumentId;
    }

    public String getMotdField() {
        return motdField;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public String getIconFile() {
        return iconFile;
    }

    public String getHubServer() {
        return hubServer;
    }

    public List<String> getGameServers() {
        return gameServers;
    }

    public String getRegistryCollection() {
        return registryCollection;
    }

    public String getRegistryGroup() {
        return registryGroup;
    }

    public String getFriendsCollection() {
        return friendsCollection;
    }

    public int getRegistryRefreshSeconds() {
        return registryRefreshSeconds;
    }

    public int getRegistryStaleSeconds() {
        return registryStaleSeconds;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public int getRedisDatabase() {
        return redisDatabase;
    }

    public void setMongoUri(String mongoUri) {
        if (mongoUri != null && !mongoUri.trim().isEmpty()) {
            this.mongoUri = mongoUri.trim();
        }
    }

    public void setMongoDatabase(String mongoDatabase) {
        if (mongoDatabase != null && !mongoDatabase.trim().isEmpty()) {
            this.mongoDatabase = mongoDatabase.trim();
        }
    }

    public void setMotdCollection(String motdCollection) {
        if (motdCollection != null && !motdCollection.trim().isEmpty()) {
            this.motdCollection = motdCollection.trim();
        }
    }

    public void setMotdDocumentId(String motdDocumentId) {
        if (motdDocumentId != null && !motdDocumentId.trim().isEmpty()) {
            this.motdDocumentId = motdDocumentId.trim();
        }
    }

    public void setMotdField(String motdField) {
        if (motdField != null && !motdField.trim().isEmpty()) {
            this.motdField = motdField.trim();
        }
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        if (cacheTtlSeconds > 0) {
            this.cacheTtlSeconds = cacheTtlSeconds;
        }
    }

    public void setIconFile(String iconFile) {
        if (iconFile != null && !iconFile.trim().isEmpty()) {
            this.iconFile = iconFile.trim();
        }
    }

    public void setHubServer(String hubServer) {
        this.hubServer = hubServer == null ? "" : hubServer.trim();
    }

    public void setGameServers(List<String> gameServers) {
        this.gameServers = new ArrayList<>();
        if (gameServers == null) {
            return;
        }
        for (String server : gameServers) {
            if (server != null && !server.trim().isEmpty()) {
                this.gameServers.add(server.trim());
            }
        }
    }

    public void setRegistryCollection(String registryCollection) {
        if (registryCollection != null && !registryCollection.trim().isEmpty()) {
            this.registryCollection = registryCollection.trim();
        }
    }

    public void setRegistryGroup(String registryGroup) {
        if (registryGroup == null) {
            this.registryGroup = "";
            return;
        }
        this.registryGroup = registryGroup.trim();
    }

    public void setFriendsCollection(String friendsCollection) {
        if (friendsCollection != null && !friendsCollection.trim().isEmpty()) {
            this.friendsCollection = friendsCollection.trim();
        }
    }

    public void setRegistryRefreshSeconds(int registryRefreshSeconds) {
        if (registryRefreshSeconds > 0) {
            this.registryRefreshSeconds = registryRefreshSeconds;
        }
    }

    public void setRegistryStaleSeconds(int registryStaleSeconds) {
        if (registryStaleSeconds > 0) {
            this.registryStaleSeconds = registryStaleSeconds;
        }
    }

    public void setRedisHost(String redisHost) {
        if (redisHost != null && !redisHost.trim().isEmpty()) {
            this.redisHost = redisHost.trim();
        }
    }

    public void setRedisPort(int redisPort) {
        if (redisPort > 0) {
            this.redisPort = redisPort;
        }
    }

    public void setRedisPassword(String redisPassword) {
        this.redisPassword = redisPassword == null ? "" : redisPassword;
    }

    public void setRedisDatabase(int redisDatabase) {
        if (redisDatabase >= 0) {
            this.redisDatabase = redisDatabase;
        }
    }
}
