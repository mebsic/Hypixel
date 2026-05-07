package io.github.mebsic.core.manager;

import com.mongodb.ConnectionString;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.concurrent.TimeUnit;

public class MongoManager {
    public static final String PROFILES_COLLECTION = "profiles";
    public static final String PUNISHMENTS_COLLECTION = "punishments";
    public static final String SERVER_REGISTRY_COLLECTION = "server_registry";
    public static final String BOSS_BAR_MESSAGES_COLLECTION = "boss_bar_messages";
    public static final String MAPS_COLLECTION = "maps";
    public static final String FRIENDS_COLLECTION = "friends";
    public static final String PROXY_SETTINGS_COLLECTION = "proxy_settings";
    public static final String RANK_GIFT_HISTORY_COLLECTION = "rank_gift_history";
    public static final String CHAT_MESSAGES_COLLECTION = "chat_messages";
    public static final String MURDER_MYSTERY_GAME_KEY = "murdermystery";

    public static final String MAP_CONFIG_DEFAULT_GAME_KEY = MURDER_MYSTERY_GAME_KEY;
    public static final String MAP_SPAWNS_KEY = "spawns";
    public static final String MAP_DROP_ITEMS_KEY = "dropItem";
    public static final String MAP_CREATED_AT_KEY = "createdAt";
    public static final String MAP_PREGAME_SPAWN_KEY = "pregameSpawn";
    public static final String MAP_HUB_SPAWN_KEY = "hubSpawn";
    public static final String MAP_LOBBY_SPAWN_KEY = "lobbySpawn";
    public static final String MAP_SERVER_TYPES_KEY = "serverTypes";
    public static final String MAP_INFORMATION_KEY = "information";
    public static final String MAP_INFORMATION_IMAGE_DISPLAY_KEY = "imageDisplay";
    public static final String MAP_NPCS_KEY = "npcs";
    public static final String MAP_PROFILE_NPCS_KEY = "profileNpcs";
    public static final String MAP_LEADERBOARDS_KEY = "leaderboards";
    public static final String MAP_PARKOURS_KEY = "parkours";
    public static final String MAP_CHECKPOINTS_KEY = "checkpoints";
    public static final String MAP_START_KEY = "start";
    public static final String MAP_END_KEY = "end";
    public static final String MAP_PARKOUR_TITLE_COLOR_KEY = "titleColor";
    public static final String MAP_PARKOUR_START_COLOR_KEY = "startColor";
    public static final String MAP_PARKOUR_CHECKPOINT_COLOR_KEY = "checkpointColor";
    public static final String MAP_PARKOUR_END_COLOR_KEY = "endColor";
    public static final String MAP_NPC_KIND_KEY = "npcKind";
    public static final String MAP_ENTITY_ID_KEY = "entityId";
    public static final String MAP_HOLOGRAM_COLOR_KEY = "hologramColor";
    public static final String MAP_SKIN_OWNER_KEY = "skinOwner";
    public static final String MAP_OWNER_NAME_KEY = "ownerName";
    public static final String MAP_OWNER_UUID_KEY = "ownerUuid";
    public static final String MAP_METRIC_KEY = "metric";
    public static final String LEADERBOARD_METRIC_KILLS = "kills";
    public static final String LEADERBOARD_METRIC_WINS = "wins";
    public static final String LEADERBOARD_METRIC_WINS_AS_MURDERER = "wins_as_murderer";
    public static final String MAP_LEADERBOARD_TITLE_COLOR_KEY = "titleColor";
    public static final String MAP_LEADERBOARD_MODE_COLOR_KEY = "modeColor";
    public static final String MAP_LEADERBOARD_RANK_COLOR_KEY = "rankColor";
    public static final String MAP_LEADERBOARD_SEPARATOR_COLOR_KEY = "separatorColor";
    public static final String MAP_LEADERBOARD_VALUE_COLOR_KEY = "valueColor";
    public static final String MAP_LEADERBOARD_EMPTY_COLOR_KEY = "emptyColor";
    public static final String MAP_LEADERBOARD_FOOTER_COLOR_KEY = "footerColor";

    public static final String MURDER_MYSTERY_KNIFE_SKINS_COLLECTION = "murdermystery_knife_skins";
    public static final String MURDER_MYSTERY_ROLE_CHANCES_COLLECTION = "murdermystery_role_chances";
    public static final String MURDER_MYSTERY_KNIFE_MENU_STATE_COLLECTION = "murdermystery_knife_menu_state";
    public static final String MURDER_MYSTERY_INFORMATION_COLLECTION = "murdermystery_information";
    public static final String MURDER_MYSTERY_GAME_TYPE = MURDER_MYSTERY_GAME_KEY;
    public static final String MURDER_MYSTERY_RECORD_TYPE_FIELD = "recordType";
    public static final String MURDER_MYSTERY_GAME_TYPE_FIELD = "gameType";
    public static final String MURDER_MYSTERY_RARITY_FIELD = "rarity";
    public static final String MURDER_MYSTERY_KNIFE_SKIN_RECORD_TYPE = "knife_skin";
    public static final String MURDER_MYSTERY_ROLE_CHANCE_RECORD_TYPE = "role_chance";
    public static final String MURDER_MYSTERY_KNIFE_MENU_STATE_RECORD_TYPE = "knife_menu_state";
    public static final String MURDER_MYSTERY_INFORMATION_DOCUMENT_ID = MURDER_MYSTERY_GAME_KEY;
    public static final String MURDER_MYSTERY_TOKENS_KEY = "tokens";
    public static final String MURDER_MYSTERY_BOW_KILLS_KEY = "bowKills";
    public static final String MURDER_MYSTERY_KNIFE_KILLS_KEY = "knifeKills";
    public static final String MURDER_MYSTERY_THROWN_KNIFE_KILLS_KEY = "thrownKnifeKills";
    public static final String MURDER_MYSTERY_WINS_AS_DETECTIVE_KEY = "winsAsDetective";
    public static final String MURDER_MYSTERY_WINS_AS_MURDERER_KEY = "lifetimeWinsAsMurderer";
    public static final String MURDER_MYSTERY_KILLS_AS_MURDERER_KEY = "killsAsMurderer";
    public static final String MURDER_MYSTERY_KILLS_AS_HERO_KEY = "killsAsHero";
    public static final String MURDER_MYSTERY_LIFETIME_KILLS_RANK_KEY = "lifetimeKillsRank";
    public static final String MURDER_MYSTERY_LIFETIME_WINS_RANK_KEY = "lifetimeWinsRank";
    public static final String MURDER_MYSTERY_QUICKEST_DETECTIVE_WIN_SECONDS_KEY = "quickestDetectiveWinSeconds";
    public static final String MURDER_MYSTERY_QUICKEST_MURDERER_WIN_SECONDS_KEY = "quickestMurdererWinSeconds";
    public static final String MURDER_MYSTERY_HINTS_ENABLED_KEY = "hintsEnabled";
    public static final String MURDER_MYSTERY_LIFETIME_WINS_KEY = "lifetimeWins";
    public static final String MURDER_MYSTERY_LIFETIME_KILLS_KEY = "lifetimeKills";
    public static final String MURDER_MYSTERY_LIFETIME_GAMES_KEY = "lifetimeGames";

    public static final String PROFILE_RANKS_GIFTED_KEY = "ranksGifted";
    public static final String PROFILE_MYSTERY_DUST_KEY = "mysteryDust";
    public static final String PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY = "hasActiveSubscription";
    public static final String PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY = "subscriptionExpiresAt";
    public static final String PROFILE_FIRST_LOGIN_KEY = "firstLogin";
    public static final String PROFILE_LAST_LOGIN_KEY = "lastLogin";
    public static final String PROFILE_ONLINE_KEY = "online";

    public static final String PROXY_SETTINGS_DOMAIN_FIELD = "domain";
    public static final String PROXY_SETTINGS_DOMAIN_DOCUMENT_ID = PROXY_SETTINGS_DOMAIN_FIELD;
    public static final String PARKOUR_BEST_COUNTER_PREFIX = "parkour.best_ms.";
    public static final String PARKOUR_LAST_COUNTER_PREFIX = "parkour.last_ms.";
    public static final String PARKOUR_COMPLETION_COUNTER_PREFIX = "parkour.completions.";

    private static final long CONNECT_TIMEOUT_SECONDS = 3L;
    private static final long SOCKET_TIMEOUT_SECONDS = 5L;
    private static final long SERVER_SELECTION_TIMEOUT_SECONDS = 3L;

    private final MongoClient client;
    private final MongoDatabase database;

    public MongoManager(String uri, String databaseName) {
        this.client = createClient(uri);
        this.database = client.getDatabase(databaseName);
    }

    private MongoClient createClient(String uri) {
        ConnectionString connectionString = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(SERVER_SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .applyToSocketSettings(builder -> {
                    builder.connectTimeout((int) CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    builder.readTimeout((int) SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                })
                .build();
        return MongoClients.create(settings);
    }

    public MongoCollection<Document> getProfiles() {
        return database.getCollection(PROFILES_COLLECTION);
    }

    public MongoCollection<Document> getPunishments() {
        return database.getCollection(PUNISHMENTS_COLLECTION);
    }

    public MongoCollection<Document> getRoleChances() {
        return database.getCollection(MURDER_MYSTERY_ROLE_CHANCES_COLLECTION);
    }

    public MongoCollection<Document> getKnifeSkins() {
        return database.getCollection(MURDER_MYSTERY_KNIFE_SKINS_COLLECTION);
    }

    public MongoCollection<Document> getServerRegistry() {
        return database.getCollection(SERVER_REGISTRY_COLLECTION);
    }

    public MongoCollection<Document> getBossBarMessages() {
        return database.getCollection(BOSS_BAR_MESSAGES_COLLECTION);
    }

    public MongoCollection<Document> getCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return database.getCollection(name.trim());
    }

    public void ensureCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String target = name.trim();
        for (String existing : database.listCollectionNames()) {
            if (target.equals(existing)) {
                return;
            }
        }
        try {
            database.createCollection(target);
        } catch (MongoCommandException ex) {
            // Another process may create it concurrently.
            if (ex.getErrorCode() != 48) {
                throw ex;
            }
        }
    }

    public void close() {
        client.close();
    }
}
