package io.github.mebsic.proxy.manager;

public final class MongoManager {
    public static final String PROFILES_COLLECTION = "profiles";
    public static final String PUNISHMENTS_COLLECTION = "punishments";
    public static final String FRIENDS_COLLECTION = "friends";
    public static final String PROXY_SETTINGS_COLLECTION = "proxy_settings";
    public static final String SERVER_REGISTRY_COLLECTION = "server_registry";
    public static final String MAPS_COLLECTION = "maps";
    public static final String AUTOSCALE_COLLECTION = "autoscale";

    public static final String PROXY_SETTINGS_MOTD_DOCUMENT_ID = "motd";
    public static final String PROXY_SETTINGS_MOTD_TEXT_FIELD = "text";
    public static final String PROXY_SETTINGS_DOMAIN_FIELD = "domain";
    public static final String PROFILE_CHAT_TYPE_FIELD = "chatType";

    private MongoManager() {
    }
}
