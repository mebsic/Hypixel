package io.github.mebsic.core.store;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.util.RankColorUtil;
import io.github.mebsic.core.util.HycopyExperienceUtil;
import io.github.mebsic.core.manager.MongoManager;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Projections;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.eq;

public class ProfileStore {
    private static final Set<String> BASE_STATS_KEYS = new HashSet<String>();

    static {
        BASE_STATS_KEYS.add(MongoManager.MURDER_MYSTERY_LIFETIME_WINS_KEY);
        BASE_STATS_KEYS.add(MongoManager.MURDER_MYSTERY_LIFETIME_KILLS_KEY);
        BASE_STATS_KEYS.add(MongoManager.MURDER_MYSTERY_LIFETIME_GAMES_KEY);
    }
    private static final String DEFAULT_KNIFE_ID = KnifeSkinStore.DEFAULT_KNIFE_ID;
    private static final KnifeSkinDefinition DEFAULT_KNIFE = new KnifeSkinDefinition(
            DEFAULT_KNIFE_ID,
            "IRON_SWORD",
            "",
            "",
            0
    );

    private final MongoManager mongo;
    private final Map<String, KnifeSkinDefinition> knifeSkins;

    public ProfileStore(MongoManager mongo, Map<String, KnifeSkinDefinition> knifeSkins) {
        this.mongo = mongo;
        this.knifeSkins = new HashMap<>();
        if (knifeSkins != null) {
            for (Map.Entry<String, KnifeSkinDefinition> entry : knifeSkins.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = KnifeSkinStore.normalizeKnifeSkinId(entry.getKey());
                if (key.isEmpty()) {
                    continue;
                }
                this.knifeSkins.put(key, entry.getValue());
            }
        }
        this.knifeSkins.putIfAbsent(DEFAULT_KNIFE_ID, DEFAULT_KNIFE);
    }

    public Profile load(UUID uuid, String name) {
        return loadWithState(uuid, name).getProfile();
    }

    public boolean existsByName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        MongoCollection<Document> collection = mongo.getProfiles();
        Document doc = collection.find(Filters.regex("name", "^" + Pattern.quote(trimmed) + "$", "i"))
                .projection(Projections.include("uuid"))
                .first();
        return doc != null;
    }

    public LoadResult loadWithState(UUID uuid, String name) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document doc = collection.find(eq("uuid", uuid.toString())).first();
        if (doc == null) {
            return new LoadResult(new Profile(uuid, name), true);
        }
        if (!doc.containsKey("buildModeExpiresAt")) {
            collection.updateOne(
                    eq("uuid", uuid.toString()),
                    new Document("$set", new Document("buildModeExpiresAt", 0L))
            );
        }
        String storedName = doc.getString("name");
        if (storedName == null || storedName.trim().isEmpty()) {
            storedName = name;
        }
        Profile profile = new Profile(uuid, storedName);
        if (!profile.getName().equals(name)) {
            profile.setName(name);
        }
        String rank = doc.getString("rank");
        if (rank != null) {
            try {
                profile.setRank(Rank.valueOf(rank));
            } catch (IllegalArgumentException ignored) {
                profile.setRank(Rank.DEFAULT);
            }
        }
        Long hycopyExperience = doc.getLong("hycopyExperience");
        if (hycopyExperience != null) {
            profile.setHycopyExperience(hycopyExperience);
            profile.setNetworkLevel(HycopyExperienceUtil.getLevel(hycopyExperience));
        } else {
            Integer networkLevel = doc.getInteger("networkLevel");
            if (networkLevel != null) {
                profile.setNetworkLevel(networkLevel);
                profile.setHycopyExperience(HycopyExperienceUtil.getTotalExpForLevel(networkLevel));
            }
        }
        Integer networkGold = doc.getInteger("networkGold");
        if (networkGold != null) {
            profile.setNetworkGold(networkGold);
        }
        String plusColor = doc.getString("plusColor");
        if (plusColor != null && !plusColor.trim().isEmpty()) {
            profile.setPlusColor(plusColor);
        }
        String mvpPlusPlusPrefixColor = doc.getString("mvpPlusPlusPrefixColor");
        if (mvpPlusPlusPrefixColor != null && !mvpPlusPlusPrefixColor.trim().isEmpty()) {
            profile.setMvpPlusPlusPrefixColor(RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(mvpPlusPlusPrefixColor));
        }
        if (!canUseMvpPlusPlusPrefixColor(profile.getRank())) {
            profile.setMvpPlusPlusPrefixColor(null);
        }
        profile.setFirstLogin(doc.getString(MongoManager.PROFILE_FIRST_LOGIN_KEY));
        profile.setLastLogin(doc.getString(MongoManager.PROFILE_LAST_LOGIN_KEY));
        Boolean online = doc.getBoolean(MongoManager.PROFILE_ONLINE_KEY);
        profile.setOnline(online != null && online);
        Boolean flightEnabled = doc.getBoolean("flightEnabled");
        if (flightEnabled != null) {
            profile.setFlightEnabled(flightEnabled);
        }
        Object buildModeExpiresAtRaw = doc.get("buildModeExpiresAt");
        if (buildModeExpiresAtRaw instanceof Number) {
            profile.setBuildModeExpiresAt(((Number) buildModeExpiresAtRaw).longValue());
        }
        Boolean playerVisibilityEnabled = doc.getBoolean("playerVisibilityEnabled");
        if (playerVisibilityEnabled != null) {
            profile.setPlayerVisibilityEnabled(playerVisibilityEnabled);
        }
        Integer spectatorSpeedLevel = doc.getInteger("spectatorSpeedLevel");
        if (spectatorSpeedLevel != null) {
            profile.setSpectatorSpeedLevel(spectatorSpeedLevel);
        }
        Boolean spectatorAutoTeleportEnabled = doc.getBoolean("spectatorAutoTeleportEnabled");
        if (spectatorAutoTeleportEnabled != null) {
            profile.setSpectatorAutoTeleportEnabled(spectatorAutoTeleportEnabled);
        }
        Boolean spectatorNightVisionEnabled = doc.getBoolean("spectatorNightVisionEnabled");
        if (spectatorNightVisionEnabled != null) {
            profile.setSpectatorNightVisionEnabled(spectatorNightVisionEnabled);
        }
        Boolean spectatorHideOtherSpectatorsEnabled = doc.getBoolean("spectatorHideOtherSpectatorsEnabled");
        if (spectatorHideOtherSpectatorsEnabled != null) {
            profile.setSpectatorHideOtherSpectatorsEnabled(spectatorHideOtherSpectatorsEnabled);
        }
        Boolean spectatorFirstPersonEnabled = doc.getBoolean("spectatorFirstPersonEnabled");
        if (spectatorFirstPersonEnabled != null) {
            profile.setSpectatorFirstPersonEnabled(spectatorFirstPersonEnabled);
        }
        Document statsRoot = doc.get("stats", Document.class);
        Document stats = statsRoot == null ? null : statsRoot.get(MongoManager.MURDER_MYSTERY_GAME_KEY, Document.class);
        if (stats != null) {
            Stats s = profile.getStats();
            s.addKills(readStatValue(stats, MongoManager.MURDER_MYSTERY_LIFETIME_KILLS_KEY));
            s.addWins(readStatValue(stats, MongoManager.MURDER_MYSTERY_LIFETIME_WINS_KEY));
            s.addGames(readStatValue(stats, MongoManager.MURDER_MYSTERY_LIFETIME_GAMES_KEY));
            // Treat any extra numeric stats keys as custom counters for the Murder Mystery scope.
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                if (BASE_STATS_KEYS.contains(entry.getKey())) {
                    continue;
                }
                if (isGiftedCounterKey(entry.getKey())) {
                    continue;
                }
                Object value = entry.getValue();
                if (!(value instanceof Number)) {
                    continue;
                }
                s.addCustomCounter(entry.getKey(), ((Number) value).intValue());
            }
        }
        profile.setRanksGifted(readGiftedRanks(doc));
        profile.setHasActiveSubscription(readActiveSubscription(doc));
        profile.setSubscriptionExpiresAt(readSubscriptionExpiresAt(doc));
        Document cosmeticsRoot = doc.get("cosmetics", Document.class);
        Document cosmetics = cosmeticsRoot == null ? null : cosmeticsRoot.get(MongoManager.MURDER_MYSTERY_GAME_KEY, Document.class);
        if (cosmetics != null) {
            for (CosmeticType type : CosmeticType.values()) {
                Document typeDoc = cosmetics.get(type.name().toLowerCase(), Document.class);
                if (typeDoc == null) {
                    continue;
                }
                String selected = typeDoc.getString("selected");
                if (selected != null) {
                    String normalizedSelected = normalizeCosmeticId(type, selected);
                    if (!normalizedSelected.isEmpty()) {
                        profile.getSelected().put(type, normalizedSelected);
                    }
                }
                @SuppressWarnings("unchecked")
                java.util.List<String> unlocked = (java.util.List<String>) typeDoc.get("unlocked");
                if (unlocked != null) {
                    for (String unlockedId : unlocked) {
                        if (unlockedId == null) {
                            continue;
                        }
                        String normalizedUnlocked = normalizeCosmeticId(type, unlockedId);
                        if (!normalizedUnlocked.isEmpty()) {
                            profile.getUnlocked().get(type).add(normalizedUnlocked);
                        }
                    }
                }
                @SuppressWarnings("unchecked")
                java.util.List<String> favorites = (java.util.List<String>) typeDoc.get("favorites");
                if (favorites != null) {
                    for (String favorite : favorites) {
                        if (favorite == null) {
                            continue;
                        }
                        String normalized = normalizeCosmeticId(type, favorite);
                        if (normalized.isEmpty()) {
                            continue;
                        }
                        if (profile.getUnlocked().get(type).contains(normalized)) {
                            profile.getFavorites().get(type).add(normalized);
                        }
                    }
                }
            }
        }
        profile.getFavorites().get(CosmeticType.KNIFE).retainAll(profile.getUnlocked().get(CosmeticType.KNIFE));
        return new LoadResult(profile, false);
    }

    public void save(Profile profile) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document murderMysteryStats = new Document(MongoManager.MURDER_MYSTERY_LIFETIME_WINS_KEY, profile.getStats().getWins())
                .append(MongoManager.MURDER_MYSTERY_LIFETIME_KILLS_KEY, profile.getStats().getKills())
                .append(MongoManager.MURDER_MYSTERY_LIFETIME_GAMES_KEY, profile.getStats().getGames());
        for (Map.Entry<String, Integer> entry : profile.getStats().getCustomCounters().entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (BASE_STATS_KEYS.contains(entry.getKey())) {
                continue;
            }
            if (isGiftedCounterKey(entry.getKey())) {
                continue;
            }
            Integer value = entry.getValue();
            if (value == null) {
                continue;
            }
            murderMysteryStats.append(entry.getKey(), Math.max(0, value));
        }
        Document stats = new Document(MongoManager.MURDER_MYSTERY_GAME_KEY, murderMysteryStats);
        Document cosmeticsByType = new Document();
        for (CosmeticType typeKey : CosmeticType.values()) {
            Document typeDoc = new Document();
            String selected = profile.getSelected().get(typeKey);
            if (selected != null) {
                selected = normalizeCosmeticId(typeKey, selected);
            }
            if (selected != null && !selected.isEmpty()) {
                typeDoc.append("selected", selected);
            }
            java.util.Set<String> unlockedNormalized = new LinkedHashSet<String>();
            java.util.Set<String> unlockedSource = profile.getUnlocked().get(typeKey);
            if (unlockedSource != null) {
                for (String unlockedId : unlockedSource) {
                    String normalizedUnlocked = normalizeCosmeticId(typeKey, unlockedId);
                    if (!normalizedUnlocked.isEmpty()) {
                        unlockedNormalized.add(normalizedUnlocked);
                    }
                }
            }
            typeDoc.append("unlocked", unlockedNormalized);
            java.util.Set<String> favorites = profile.getFavorites().get(typeKey);
            java.util.Set<String> validFavorites = new java.util.HashSet<String>();
            if (favorites != null) {
                for (String favorite : favorites) {
                    if (favorite == null) {
                        continue;
                    }
                    String normalized = normalizeCosmeticId(typeKey, favorite);
                    if (normalized.isEmpty()) {
                        continue;
                    }
                    if (unlockedNormalized.contains(normalized)) {
                        validFavorites.add(normalized);
                    }
                }
            }
            typeDoc.append("favorites", validFavorites);
            if (typeKey == CosmeticType.KNIFE && !knifeSkins.isEmpty()) {
                typeDoc.append("selectedMeta", buildKnifeMeta(selected));
                java.util.List<Document> meta = new ArrayList<>();
                for (String id : unlockedNormalized) {
                    Document knifeMeta = buildKnifeMeta(id);
                    if (knifeMeta != null) {
                        meta.add(knifeMeta);
                    }
                }
                typeDoc.append("meta", meta);
            }
            cosmeticsByType.append(typeKey.name().toLowerCase(), typeDoc);
        }
        Document cosmetics = new Document(MongoManager.MURDER_MYSTERY_GAME_KEY, cosmeticsByType);
        Rank rank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        String mvpPlusPlusPrefixColor = null;
        if (canUseMvpPlusPlusPrefixColor(rank)) {
            mvpPlusPlusPrefixColor = RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(profile.getMvpPlusPlusPrefixColor());
        }
        Document doc = new Document("uuid", profile.getUuid().toString())
                .append("name", profile.getName())
                .append("rank", rank.name())
                .append("networkLevel", profile.getNetworkLevel())
                .append("networkGold", profile.getNetworkGold())
                .append(MongoManager.PROFILE_RANKS_GIFTED_KEY, Math.max(0, profile.getRanksGifted()))
                .append(MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY, profile.hasActiveSubscription())
                .append(MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY, profile.getSubscriptionExpiresAt())
                .append("hycopyExperience", profile.getHycopyExperience())
                .append("plusColor", profile.getPlusColor())
                .append("mvpPlusPlusPrefixColor", mvpPlusPlusPrefixColor)
                .append(MongoManager.PROFILE_FIRST_LOGIN_KEY, profile.getFirstLogin())
                .append(MongoManager.PROFILE_LAST_LOGIN_KEY, profile.getLastLogin())
                .append(MongoManager.PROFILE_ONLINE_KEY, profile.isOnline())
                .append("flightEnabled", profile.isFlightEnabled())
                .append("buildModeExpiresAt", profile.getBuildModeExpiresAt())
                .append("playerVisibilityEnabled", profile.isPlayerVisibilityEnabled())
                .append("spectatorSpeedLevel", profile.getSpectatorSpeedLevel())
                .append("spectatorAutoTeleportEnabled", profile.isSpectatorAutoTeleportEnabled())
                .append("spectatorNightVisionEnabled", profile.isSpectatorNightVisionEnabled())
                .append("spectatorHideOtherSpectatorsEnabled", profile.isSpectatorHideOtherSpectatorsEnabled())
                .append("spectatorFirstPersonEnabled", profile.isSpectatorFirstPersonEnabled())
                .append("stats", stats)
                .append("cosmetics", cosmetics);
        collection.replaceOne(eq("uuid", profile.getUuid().toString()), doc, new ReplaceOptions().upsert(true));
    }

    private Document buildKnifeMeta(String id) {
        KnifeSkinDefinition def = resolveKnife(id);
        if (def == null) {
            return null;
        }
        return new Document("id", def.getId())
                .append("material", def.getMaterial())
                .append("displayName", def.getDisplayName())
                .append("description", def.getDescription())
                .append("cost", def.getCost())
                .append("rarity", def.getRarity());
    }

    private KnifeSkinDefinition resolveKnife(String id) {
        if (id == null || id.trim().isEmpty()) {
            return knifeSkins.get(DEFAULT_KNIFE_ID);
        }
        KnifeSkinDefinition def = knifeSkins.get(normalizeCosmeticId(CosmeticType.KNIFE, id));
        return def == null ? knifeSkins.get(DEFAULT_KNIFE_ID) : def;
    }

    private String normalizeCosmeticId(CosmeticType type, String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (type == CosmeticType.KNIFE) {
            return KnifeSkinStore.normalizeKnifeSkinId(normalized);
        }
        return normalized;
    }

    public void updateRank(UUID uuid, String name, Rank rank) {
        updateRank(uuid, name, rank, null, null);
    }

    public void updateRank(UUID uuid, String name, Rank rank, Boolean hasActiveSubscription, Long subscriptionExpiresAt) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document update = new Document("rank", rank.name());
        if (!canUseMvpPlusPlusPrefixColor(rank)) {
            update.append("mvpPlusPlusPrefixColor", null);
        }
        if (shouldEnableFlightOnRankGrant(rank)) {
            update.append("flightEnabled", true);
        }
        if (hasActiveSubscription != null) {
            update.append(MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY, hasActiveSubscription);
        }
        if (subscriptionExpiresAt != null) {
            update.append(MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY, Math.max(0L, subscriptionExpiresAt));
        }
        if (name != null && !name.trim().isEmpty()) {
            update.append("name", name);
        }
        Document setOnInsert = spectatorDefaultsDocument();
        if (update.containsKey(MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY)) {
            setOnInsert.remove(MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY);
        }
        if (update.containsKey(MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY)) {
            setOnInsert.remove(MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY);
        }
        collection.updateOne(eq("uuid", uuid.toString()),
                new Document("$set", update).append("$setOnInsert", setOnInsert),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    public void updateNetworkLevel(UUID uuid, String name, int level) {
        MongoCollection<Document> collection = mongo.getProfiles();
        int safeLevel = Math.max(0, level);
        Document update = new Document("networkLevel", safeLevel)
                .append("hycopyExperience", HycopyExperienceUtil.getTotalExpForLevel(safeLevel));
        if (name != null && !name.trim().isEmpty()) {
            update.append("name", name);
        }
        collection.updateOne(eq("uuid", uuid.toString()),
                new Document("$set", update).append("$setOnInsert", spectatorDefaultsDocument()),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    public void updateNetworkGold(UUID uuid, String name, int amount) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document update = new Document("networkGold", Math.max(0, amount));
        if (name != null && !name.trim().isEmpty()) {
            update.append("name", name);
        }
        collection.updateOne(eq("uuid", uuid.toString()),
                new Document("$set", update).append("$setOnInsert", spectatorDefaultsDocument()),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    public ProfileMeta loadProfileMeta(UUID uuid, String fallbackName) {
        if (uuid == null) {
            return null;
        }
        MongoCollection<Document> collection = mongo.getProfiles();
        Document doc = collection.find(eq("uuid", uuid.toString()))
                .projection(Projections.include(
                        "name",
                        "rank",
                        "plusColor",
                        "mvpPlusPlusPrefixColor",
                        "flightEnabled",
                        "buildModeExpiresAt",
                        "playerVisibilityEnabled",
                        "networkLevel",
                        "networkGold",
                        "hycopyExperience",
                        MongoManager.PROFILE_RANKS_GIFTED_KEY,
                        MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY,
                        MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY
                ))
                .first();
        if (doc == null) {
            return null;
        }
        String name = doc.getString("name");
        if (name == null || name.trim().isEmpty()) {
            name = fallbackName;
        }
        Rank rank = Rank.DEFAULT;
        String rankRaw = doc.getString("rank");
        if (rankRaw != null) {
            try {
                rank = Rank.valueOf(rankRaw);
            } catch (IllegalArgumentException ignored) {
                rank = Rank.DEFAULT;
            }
        }
        String plusColor = doc.getString("plusColor");
        String mvpPlusPlusPrefixColor = doc.getString("mvpPlusPlusPrefixColor");
        if (mvpPlusPlusPrefixColor != null && !mvpPlusPlusPrefixColor.trim().isEmpty()) {
            mvpPlusPlusPrefixColor = RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(mvpPlusPlusPrefixColor);
        } else {
            mvpPlusPlusPrefixColor = null;
        }
        if (!canUseMvpPlusPlusPrefixColor(rank)) {
            mvpPlusPlusPrefixColor = null;
        }
        Boolean flightEnabled = doc.getBoolean("flightEnabled");
        long buildModeExpiresAt = 0L;
        Object buildModeExpiresAtRaw = doc.get("buildModeExpiresAt");
        if (buildModeExpiresAtRaw instanceof Number) {
            buildModeExpiresAt = Math.max(0L, ((Number) buildModeExpiresAtRaw).longValue());
        }
        Boolean playerVisibilityEnabled = doc.getBoolean("playerVisibilityEnabled");
        int networkLevel = 0;
        Long hycopyExperience = doc.getLong("hycopyExperience");
        if (hycopyExperience != null) {
            networkLevel = HycopyExperienceUtil.getLevel(hycopyExperience);
        } else {
            Integer level = doc.getInteger("networkLevel");
            if (level != null) {
                networkLevel = Math.max(0, level);
            }
        }
        Integer networkGoldRaw = doc.getInteger("networkGold");
        int networkGold = networkGoldRaw == null ? 0 : Math.max(0, networkGoldRaw);
        int giftedRanks = readGiftedRanks(doc);
        boolean hasActiveSubscription = readActiveSubscription(doc);
        long subscriptionExpiresAt = readSubscriptionExpiresAt(doc);
        return new ProfileMeta(
                name == null ? fallbackName : name,
                rank,
                plusColor,
                mvpPlusPlusPrefixColor,
                flightEnabled != null && flightEnabled,
                buildModeExpiresAt,
                playerVisibilityEnabled == null || playerVisibilityEnabled,
                networkLevel,
                networkGold,
                giftedRanks,
                hasActiveSubscription,
                subscriptionExpiresAt
        );
    }

    public void updatePlayerVisibility(UUID uuid, String name, boolean playerVisibilityEnabled) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document update = new Document("playerVisibilityEnabled", playerVisibilityEnabled);
        if (name != null && !name.trim().isEmpty()) {
            update.append("name", name);
        }
        collection.updateOne(eq("uuid", uuid.toString()),
                new Document("$set", update).append("$setOnInsert", spectatorDefaultsDocument()),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    private Document spectatorDefaultsDocument() {
        return new Document("spectatorSpeedLevel", 0)
                .append("spectatorAutoTeleportEnabled", false)
                .append("spectatorNightVisionEnabled", true)
                .append("spectatorHideOtherSpectatorsEnabled", false)
                .append("spectatorFirstPersonEnabled", false)
                .append("buildModeExpiresAt", 0L)
                .append(MongoManager.PROFILE_RANKS_GIFTED_KEY, 0)
                .append(MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY, false)
                .append(MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY, 0L)
                .append(MongoManager.PROFILE_FIRST_LOGIN_KEY, null)
                .append(MongoManager.PROFILE_LAST_LOGIN_KEY, null)
                .append(MongoManager.PROFILE_ONLINE_KEY, false);
    }

    private static boolean canUseMvpPlusPlusPrefixColor(Rank rank) {
        return rank == Rank.MVP_PLUS_PLUS || rank == Rank.STAFF || rank == Rank.YOUTUBE;
    }

    private static boolean shouldEnableFlightOnRankGrant(Rank rank) {
        return rank == Rank.MVP_PLUS || rank == Rank.MVP_PLUS_PLUS;
    }

    private int readGiftedRanks(Document profile) {
        if (profile == null) {
            return 0;
        }
        Object raw = profile.get(MongoManager.PROFILE_RANKS_GIFTED_KEY);
        if (raw instanceof Number) {
            return Math.max(0, ((Number) raw).intValue());
        }
        return 0;
    }

    private boolean readActiveSubscription(Document profile) {
        if (profile == null) {
            return false;
        }
        Boolean raw = profile.getBoolean(MongoManager.PROFILE_HAS_ACTIVE_SUBSCRIPTION_KEY);
        return raw != null && raw;
    }

    private long readSubscriptionExpiresAt(Document profile) {
        if (profile == null) {
            return 0L;
        }
        Object raw = profile.get(MongoManager.PROFILE_SUBSCRIPTION_EXPIRES_AT_KEY);
        if (raw instanceof Number) {
            return Math.max(0L, ((Number) raw).longValue());
        }
        return 0L;
    }

    private boolean isGiftedCounterKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        return MongoManager.PROFILE_RANKS_GIFTED_KEY.equals(key.trim());
    }

    public static final class ProfileMeta {
        private final String name;
        private final Rank rank;
        private final String plusColor;
        private final String mvpPlusPlusPrefixColor;
        private final boolean flightEnabled;
        private final long buildModeExpiresAt;
        private final boolean playerVisibilityEnabled;
        private final int networkLevel;
        private final int networkGold;
        private final int giftedRanks;
        private final boolean hasActiveSubscription;
        private final long subscriptionExpiresAt;

        public ProfileMeta(String name,
                           Rank rank,
                           String plusColor,
                           String mvpPlusPlusPrefixColor,
                           boolean flightEnabled,
                           long buildModeExpiresAt,
                           boolean playerVisibilityEnabled,
                           int networkLevel,
                           int networkGold,
                           int giftedRanks,
                           boolean hasActiveSubscription,
                           long subscriptionExpiresAt) {
            this.name = name;
            this.rank = rank == null ? Rank.DEFAULT : rank;
            this.plusColor = plusColor;
            this.mvpPlusPlusPrefixColor = mvpPlusPlusPrefixColor;
            this.flightEnabled = flightEnabled;
            this.buildModeExpiresAt = Math.max(0L, buildModeExpiresAt);
            this.playerVisibilityEnabled = playerVisibilityEnabled;
            this.networkLevel = Math.max(0, networkLevel);
            this.networkGold = Math.max(0, networkGold);
            this.giftedRanks = Math.max(0, giftedRanks);
            this.hasActiveSubscription = hasActiveSubscription;
            this.subscriptionExpiresAt = Math.max(0L, subscriptionExpiresAt);
        }

        public String getName() {
            return name;
        }

        public Rank getRank() {
            return rank;
        }

        public String getPlusColor() {
            return plusColor;
        }

        public String getMvpPlusPlusPrefixColor() {
            return mvpPlusPlusPrefixColor;
        }

        public boolean isFlightEnabled() {
            return flightEnabled;
        }

        public long getBuildModeExpiresAt() {
            return buildModeExpiresAt;
        }

        public boolean isPlayerVisibilityEnabled() {
            return playerVisibilityEnabled;
        }

        public int getNetworkLevel() {
            return networkLevel;
        }

        public int getNetworkGold() {
            return networkGold;
        }

        public int getGiftedRanks() {
            return giftedRanks;
        }

        public boolean hasActiveSubscription() {
            return hasActiveSubscription;
        }

        public long getSubscriptionExpiresAt() {
            return subscriptionExpiresAt;
        }
    }

    public static final class LoadResult {
        private final Profile profile;
        private final boolean created;

        public LoadResult(Profile profile, boolean created) {
            this.profile = profile;
            this.created = created;
        }

        public Profile getProfile() {
            return profile;
        }

        public boolean isCreated() {
            return created;
        }
    }

    private int readStatValue(Document stats, String key) {
        if (stats == null) {
            return 0;
        }
        Integer value = readNumericStat(stats, key);
        if (value != null) {
            return value;
        }
        return 0;
    }

    private Integer readNumericStat(Document stats, String key) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return null;
        }
        Object raw = stats.get(key);
        if (raw instanceof Number) {
            return Math.max(0, ((Number) raw).intValue());
        }
        return null;
    }
}
