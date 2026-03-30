package io.github.mebsic.core.store;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.model.Stats;
import io.github.mebsic.core.util.RankColorUtil;
import io.github.mebsic.core.util.HypixelExperienceUtil;
import io.github.mebsic.core.manager.MongoManager;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.eq;

public class ProfileStore {
    private static final Set<String> BASE_STATS_KEYS = new HashSet<String>();
    private static final String LEGACY_WINS_KEY = "wins";
    private static final String LEGACY_KILLS_KEY = "kills";
    private static final String LEGACY_GAMES_KEY = "games";
    private static final String MURDER_MYSTERY_WINS_KEY = "murderMysteryWins";
    private static final String MURDER_MYSTERY_KILLS_KEY = "murderMysteryKills";
    private static final String MURDER_MYSTERY_GAMES_KEY = "murderMysteryGames";
    private static final String MURDER_MYSTERY_WINS_AS_MURDERER_KEY = "murdermystery.winsAsMurderer";
    private static final String MURDER_MYSTERY_KILLS_AS_MURDERER_KEY = "murdermystery.killsAsMurderer";
    private static final String MIGRATIONS_COLLECTION = "core_migrations";
    private static final String SPECTATOR_DEFAULTS_MIGRATION_ID = "spectator_settings_defaults_v1";
    private static final String MURDER_MYSTERY_STATS_MIGRATION_ID = "murder_mystery_stats_keys_v2_cleanup_legacy";
    private static final String GIFTED_COUNTER_KEY = "ranks_gifted";
    private static final String GIFTED_COUNTER_CAMEL_KEY = "ranksGifted";
    private static final String GIFTED_COUNTER_FLAT_KEY = "ranksgifted";
    private static final String RANKS_GIFTED_TOP_LEVEL_KEY = "ranksGifted";

    static {
        BASE_STATS_KEYS.add(LEGACY_WINS_KEY);
        BASE_STATS_KEYS.add(LEGACY_KILLS_KEY);
        BASE_STATS_KEYS.add(LEGACY_GAMES_KEY);
        BASE_STATS_KEYS.add(MURDER_MYSTERY_WINS_KEY);
        BASE_STATS_KEYS.add(MURDER_MYSTERY_KILLS_KEY);
        BASE_STATS_KEYS.add(MURDER_MYSTERY_GAMES_KEY);
        BASE_STATS_KEYS.add("custom");
    }
    private static final String DEFAULT_KNIFE_ID = "iron_sword";
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
                String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
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
        Long hypixelExperience = doc.getLong("hypixelExperience");
        if (hypixelExperience != null) {
            profile.setHypixelExperience(hypixelExperience);
            profile.setNetworkLevel(HypixelExperienceUtil.getLevel(hypixelExperience));
        } else {
            Integer networkLevel = doc.getInteger("networkLevel");
            if (networkLevel != null) {
                profile.setNetworkLevel(networkLevel);
                profile.setHypixelExperience(HypixelExperienceUtil.getTotalExpForLevel(networkLevel));
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
        Document stats = doc.get("stats", Document.class);
        if (stats != null) {
            Stats s = profile.getStats();
            s.addKills(readStatValue(stats, MURDER_MYSTERY_KILLS_KEY));
            s.addWins(readStatValue(stats, MURDER_MYSTERY_WINS_KEY));
            s.addGames(readStatValue(stats, MURDER_MYSTERY_GAMES_KEY));
            Document custom = stats.get("custom", Document.class);
            if (custom != null) {
                for (Map.Entry<String, Object> entry : custom.entrySet()) {
                    if (entry == null || entry.getKey() == null) {
                        continue;
                    }
                    Object value = entry.getValue();
                    if (!(value instanceof Number)) {
                        continue;
                    }
                    s.addCustomCounter(entry.getKey(), ((Number) value).intValue());
                }
            }
            // Backward compatibility: treat any extra numeric stats keys as custom counters.
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                if (BASE_STATS_KEYS.contains(entry.getKey())) {
                    continue;
                }
                Object value = entry.getValue();
                if (!(value instanceof Number)) {
                    continue;
                }
                s.addCustomCounter(entry.getKey(), ((Number) value).intValue());
            }
        }
        syncGiftedCounters(profile.getStats(), readStoredGiftedRanks(doc, profile.getStats()));
        Document cosmetics = doc.get("cosmetics", Document.class);
        if (cosmetics != null) {
            for (CosmeticType type : CosmeticType.values()) {
                Document typeDoc = cosmetics.get(type.name().toLowerCase(), Document.class);
                if (typeDoc == null) {
                    continue;
                }
                String selected = typeDoc.getString("selected");
                if (selected != null) {
                    String normalizedSelected = selected.trim().toLowerCase(Locale.ROOT);
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
                        String normalizedUnlocked = unlockedId.trim().toLowerCase(Locale.ROOT);
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
                        String normalized = favorite.trim().toLowerCase(Locale.ROOT);
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
        // legacy knife migration
        if (profile.getSelected().get(CosmeticType.KNIFE) != null
                && profile.getSelected().get(CosmeticType.KNIFE).equalsIgnoreCase("default")) {
            profile.getSelected().put(CosmeticType.KNIFE, "iron_sword");
        }
        if (profile.getSelected().get(CosmeticType.KNIFE) != null
                && profile.getSelected().get(CosmeticType.KNIFE).equalsIgnoreCase("mm_skin_02_chest")) {
            profile.getSelected().put(CosmeticType.KNIFE, "random");
        }
        if (profile.getSelected().get(CosmeticType.KNIFE) != null
                && profile.getSelected().get(CosmeticType.KNIFE).equalsIgnoreCase("mm_skin_03_ender_chest")) {
            profile.getSelected().put(CosmeticType.KNIFE, "random_favorite");
        }
        if (profile.getUnlocked().get(CosmeticType.KNIFE).contains("default")) {
            profile.getUnlocked().get(CosmeticType.KNIFE).add("iron_sword");
        }
        profile.getUnlocked().get(CosmeticType.KNIFE).remove("mm_skin_02_chest");
        profile.getUnlocked().get(CosmeticType.KNIFE).remove("mm_skin_03_ender_chest");
        if (profile.getFavorites().get(CosmeticType.KNIFE).contains("default")) {
            profile.getFavorites().get(CosmeticType.KNIFE).remove("default");
            profile.getFavorites().get(CosmeticType.KNIFE).add("iron_sword");
        }
        profile.getFavorites().get(CosmeticType.KNIFE).remove("mm_skin_02_chest");
        profile.getFavorites().get(CosmeticType.KNIFE).remove("mm_skin_03_ender_chest");
        profile.getFavorites().get(CosmeticType.KNIFE).retainAll(profile.getUnlocked().get(CosmeticType.KNIFE));
        return new LoadResult(profile, false);
    }

    public void save(Profile profile) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document stats = new Document(MURDER_MYSTERY_WINS_KEY, profile.getStats().getWins())
                .append(MURDER_MYSTERY_KILLS_KEY, profile.getStats().getKills())
                .append(MURDER_MYSTERY_GAMES_KEY, profile.getStats().getGames());
        Document custom = new Document();
        for (Map.Entry<String, Integer> entry : profile.getStats().getCustomCounters().entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (isGiftedCounterKey(entry.getKey())) {
                continue;
            }
            Integer value = entry.getValue();
            if (value == null) {
                continue;
            }
            custom.append(entry.getKey(), Math.max(0, value));
        }
        if (!custom.isEmpty()) {
            stats.append("custom", custom);
        }
        Document cosmetics = new Document();
        for (CosmeticType typeKey : CosmeticType.values()) {
            Document typeDoc = new Document();
            String selected = profile.getSelected().get(typeKey);
            if (selected != null) {
                selected = selected.trim().toLowerCase(Locale.ROOT);
            }
            if (selected != null && !selected.isEmpty()) {
                typeDoc.append("selected", selected);
            }
            typeDoc.append("unlocked", profile.getUnlocked().get(typeKey));
            java.util.Set<String> favorites = profile.getFavorites().get(typeKey);
            java.util.Set<String> validFavorites = new java.util.HashSet<String>();
            if (favorites != null) {
                for (String favorite : favorites) {
                    if (favorite == null) {
                        continue;
                    }
                    String normalized = favorite.trim().toLowerCase(Locale.ROOT);
                    if (normalized.isEmpty()) {
                        continue;
                    }
                    if (profile.getUnlocked().get(typeKey).contains(normalized)) {
                        validFavorites.add(normalized);
                    }
                }
            }
            typeDoc.append("favorites", validFavorites);
            if (typeKey == CosmeticType.KNIFE && !knifeSkins.isEmpty()) {
                typeDoc.append("selectedMeta", buildKnifeMeta(selected));
                java.util.List<Document> meta = new ArrayList<>();
                for (String id : profile.getUnlocked().get(typeKey)) {
                    Document knifeMeta = buildKnifeMeta(id);
                    if (knifeMeta != null) {
                        meta.add(knifeMeta);
                    }
                }
                typeDoc.append("meta", meta);
            }
            cosmetics.append(typeKey.name().toLowerCase(), typeDoc);
        }
        Rank rank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        String mvpPlusPlusPrefixColor = null;
        if (canUseMvpPlusPlusPrefixColor(rank)) {
            mvpPlusPlusPrefixColor = RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(profile.getMvpPlusPlusPrefixColor());
        }
        int giftedRanks = readGiftedRanks(profile.getStats());

        Document doc = new Document("uuid", profile.getUuid().toString())
                .append("name", profile.getName())
                .append("rank", rank.name())
                .append("networkLevel", profile.getNetworkLevel())
                .append("networkGold", profile.getNetworkGold())
                .append(RANKS_GIFTED_TOP_LEVEL_KEY, giftedRanks)
                .append("hypixelExperience", profile.getHypixelExperience())
                .append("plusColor", profile.getPlusColor())
                .append("mvpPlusPlusPrefixColor", mvpPlusPlusPrefixColor)
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

    public void applySpectatorDefaultsToAllProfilesOnce() {
        MongoCollection<Document> migrations = mongo.getCollection(MIGRATIONS_COLLECTION);
        if (migrations == null) {
            return;
        }
        if (migrations.find(eq("_id", SPECTATOR_DEFAULTS_MIGRATION_ID)).first() != null) {
            return;
        }
        Document defaults = spectatorDefaultsDocument();
        UpdateResult result = mongo.getProfiles().updateMany(new Document(), new Document("$set", defaults));
        Document marker = new Document("_id", SPECTATOR_DEFAULTS_MIGRATION_ID)
                .append("modifiedCount", result == null ? 0L : result.getModifiedCount())
                .append("completedAt", System.currentTimeMillis());
        migrations.updateOne(
                eq("_id", SPECTATOR_DEFAULTS_MIGRATION_ID),
                new Document("$setOnInsert", marker),
                new UpdateOptions().upsert(true)
        );
    }

    public void applyMurderMysteryStatsKeysMigrationOnce() {
        MongoCollection<Document> migrations = mongo.getCollection(MIGRATIONS_COLLECTION);
        if (migrations == null) {
            return;
        }
        if (migrations.find(eq("_id", MURDER_MYSTERY_STATS_MIGRATION_ID)).first() != null) {
            return;
        }

        MongoCollection<Document> profiles = mongo.getProfiles();
        long modified = 0L;
        try (MongoCursor<Document> cursor = profiles.find()
                .projection(Projections.include("uuid", "stats"))
                .iterator()) {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                if (doc == null) {
                    continue;
                }
                String uuid = doc.getString("uuid");
                if (uuid == null || uuid.trim().isEmpty()) {
                    continue;
                }
                Document stats = doc.get("stats", Document.class);
                if (stats == null) {
                    continue;
                }

                Document set = new Document();
                Document unset = new Document();
                if (!stats.containsKey(MURDER_MYSTERY_WINS_KEY) && stats.containsKey(LEGACY_WINS_KEY)) {
                    set.append("stats." + MURDER_MYSTERY_WINS_KEY, readStatValue(stats, LEGACY_WINS_KEY));
                }
                if (!stats.containsKey(MURDER_MYSTERY_KILLS_KEY) && stats.containsKey(LEGACY_KILLS_KEY)) {
                    set.append("stats." + MURDER_MYSTERY_KILLS_KEY, readStatValue(stats, LEGACY_KILLS_KEY));
                }
                if (!stats.containsKey(MURDER_MYSTERY_GAMES_KEY) && stats.containsKey(LEGACY_GAMES_KEY)) {
                    set.append("stats." + MURDER_MYSTERY_GAMES_KEY, readStatValue(stats, LEGACY_GAMES_KEY));
                }
                if (stats.containsKey(LEGACY_WINS_KEY)) {
                    unset.append("stats." + LEGACY_WINS_KEY, "");
                }
                if (stats.containsKey(LEGACY_KILLS_KEY)) {
                    unset.append("stats." + LEGACY_KILLS_KEY, "");
                }
                if (stats.containsKey(LEGACY_GAMES_KEY)) {
                    unset.append("stats." + LEGACY_GAMES_KEY, "");
                }

                if (!set.isEmpty() || !unset.isEmpty()) {
                    Document update = new Document();
                    if (!set.isEmpty()) {
                        update.append("$set", set);
                    }
                    if (!unset.isEmpty()) {
                        update.append("$unset", unset);
                    }
                    UpdateResult result = profiles.updateOne(eq("uuid", uuid), update);
                    if (result != null) {
                        modified += result.getModifiedCount();
                    }
                }
            }
        }

        Document marker = new Document("_id", MURDER_MYSTERY_STATS_MIGRATION_ID)
                .append("modifiedCount", modified)
                .append("completedAt", System.currentTimeMillis());
        migrations.updateOne(
                eq("_id", MURDER_MYSTERY_STATS_MIGRATION_ID),
                new Document("$setOnInsert", marker),
                new UpdateOptions().upsert(true)
        );
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
        KnifeSkinDefinition def = knifeSkins.get(id.trim().toLowerCase(Locale.ROOT));
        return def == null ? knifeSkins.get(DEFAULT_KNIFE_ID) : def;
    }

    public void updateRank(UUID uuid, String name, Rank rank) {
        MongoCollection<Document> collection = mongo.getProfiles();
        Document update = new Document("rank", rank.name());
        if (!canUseMvpPlusPlusPrefixColor(rank)) {
            update.append("mvpPlusPlusPrefixColor", null);
        }
        if (shouldEnableFlightOnRankGrant(rank)) {
            update.append("flightEnabled", true);
        }
        if (name != null && !name.trim().isEmpty()) {
            update.append("name", name);
        }
        collection.updateOne(eq("uuid", uuid.toString()),
                new Document("$set", update).append("$setOnInsert", spectatorDefaultsDocument()),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    public void updateNetworkLevel(UUID uuid, String name, int level) {
        MongoCollection<Document> collection = mongo.getProfiles();
        int safeLevel = Math.max(0, level);
        Document update = new Document("networkLevel", safeLevel)
                .append("hypixelExperience", HypixelExperienceUtil.getTotalExpForLevel(safeLevel));
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
                        RANKS_GIFTED_TOP_LEVEL_KEY,
                        "hypixelExperience",
                        "stats." + GIFTED_COUNTER_KEY,
                        "stats." + GIFTED_COUNTER_CAMEL_KEY,
                        "stats." + GIFTED_COUNTER_FLAT_KEY,
                        "stats.custom." + GIFTED_COUNTER_KEY,
                        "stats.custom." + GIFTED_COUNTER_CAMEL_KEY,
                        "stats.custom." + GIFTED_COUNTER_FLAT_KEY
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
        Long hypixelExperience = doc.getLong("hypixelExperience");
        if (hypixelExperience != null) {
            networkLevel = HypixelExperienceUtil.getLevel(hypixelExperience);
        } else {
            Integer level = doc.getInteger("networkLevel");
            if (level != null) {
                networkLevel = Math.max(0, level);
            }
        }
        Integer networkGoldRaw = doc.getInteger("networkGold");
        int networkGold = networkGoldRaw == null ? 0 : Math.max(0, networkGoldRaw);
        int giftedRanks = readStoredGiftedRanks(doc, doc.get("stats", Document.class));
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
                giftedRanks
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
                .append(RANKS_GIFTED_TOP_LEVEL_KEY, 0);
    }

    private static boolean canUseMvpPlusPlusPrefixColor(Rank rank) {
        return rank == Rank.MVP_PLUS_PLUS || rank == Rank.STAFF || rank == Rank.YOUTUBE;
    }

    private static boolean shouldEnableFlightOnRankGrant(Rank rank) {
        return rank == Rank.MVP_PLUS || rank == Rank.MVP_PLUS_PLUS;
    }

    private int readGiftedRanks(Document stats) {
        if (stats == null) {
            return 0;
        }
        Document custom = stats.get("custom", Document.class);
        int gifted = 0;
        gifted = Math.max(gifted, readGiftedCounter(custom, GIFTED_COUNTER_KEY));
        gifted = Math.max(gifted, readGiftedCounter(custom, GIFTED_COUNTER_CAMEL_KEY));
        gifted = Math.max(gifted, readGiftedCounter(custom, GIFTED_COUNTER_FLAT_KEY));
        gifted = Math.max(gifted, readGiftedCounter(stats, GIFTED_COUNTER_KEY));
        gifted = Math.max(gifted, readGiftedCounter(stats, GIFTED_COUNTER_CAMEL_KEY));
        gifted = Math.max(gifted, readGiftedCounter(stats, GIFTED_COUNTER_FLAT_KEY));
        return gifted;
    }

    private int readGiftedRanks(Stats stats) {
        if (stats == null) {
            return 0;
        }
        int gifted = 0;
        gifted = Math.max(gifted, Math.max(0, stats.getCustomCounter(GIFTED_COUNTER_KEY)));
        gifted = Math.max(gifted, Math.max(0, stats.getCustomCounter(GIFTED_COUNTER_CAMEL_KEY)));
        gifted = Math.max(gifted, Math.max(0, stats.getCustomCounter(GIFTED_COUNTER_FLAT_KEY)));
        return gifted;
    }

    private int readStoredGiftedRanks(Document profile, Document stats) {
        int gifted = readGiftedRanks(stats);
        if (profile != null) {
            Integer topLevel = profile.getInteger(RANKS_GIFTED_TOP_LEVEL_KEY);
            if (topLevel != null) {
                gifted = Math.max(gifted, Math.max(0, topLevel));
            }
        }
        return gifted;
    }

    private int readStoredGiftedRanks(Document profile, Stats stats) {
        int gifted = readGiftedRanks(stats);
        if (profile != null) {
            Integer topLevel = profile.getInteger(RANKS_GIFTED_TOP_LEVEL_KEY);
            if (topLevel != null) {
                gifted = Math.max(gifted, Math.max(0, topLevel));
            }
        }
        return gifted;
    }

    private void syncGiftedCounters(Stats stats, int value) {
        if (stats == null) {
            return;
        }
        int safeValue = Math.max(0, value);
        setGiftedCounter(stats, GIFTED_COUNTER_CAMEL_KEY, safeValue);
        clearGiftedCounter(stats, GIFTED_COUNTER_KEY);
        clearGiftedCounter(stats, GIFTED_COUNTER_FLAT_KEY);
    }

    private void setGiftedCounter(Stats stats, String key, int value) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return;
        }
        int current = stats.getCustomCounter(key);
        if (current == value) {
            return;
        }
        stats.addCustomCounter(key, value - current);
    }

    private void clearGiftedCounter(Stats stats, String key) {
        if (stats == null || key == null || key.trim().isEmpty()) {
            return;
        }
        int current = stats.getCustomCounter(key);
        if (current > 0) {
            stats.addCustomCounter(key, -current);
        }
    }

    private int readGiftedCounter(Document source, String key) {
        if (source == null || key == null || key.trim().isEmpty()) {
            return 0;
        }
        Object raw = source.get(key);
        if (raw instanceof Number) {
            return Math.max(0, ((Number) raw).intValue());
        }
        return 0;
    }

    private boolean isGiftedCounterKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return false;
        }
        String normalized = key.trim();
        return GIFTED_COUNTER_KEY.equals(normalized)
                || GIFTED_COUNTER_CAMEL_KEY.equals(normalized)
                || GIFTED_COUNTER_FLAT_KEY.equals(normalized);
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

        public ProfileMeta(String name,
                           Rank rank,
                           String plusColor,
                           String mvpPlusPlusPrefixColor,
                           boolean flightEnabled,
                           long buildModeExpiresAt,
                           boolean playerVisibilityEnabled,
                           int networkLevel,
                           int networkGold,
                           int giftedRanks) {
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

    public void storeLeaderboards(Map<UUID, Stats> cache) {
        MongoCollection<Document> collection = mongo.getLeaderboards();
        java.util.List<Document> entries = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Stats> entry : cache.entrySet()) {
            Stats stats = entry.getValue();
            Document doc = new Document("uuid", entry.getKey().toString())
                    .append(MURDER_MYSTERY_WINS_KEY, stats.getWins())
                    .append(MURDER_MYSTERY_KILLS_KEY, stats.getKills())
                    .append(MURDER_MYSTERY_GAMES_KEY, stats.getGames())
                    .append(MURDER_MYSTERY_WINS_AS_MURDERER_KEY, stats.getCustomCounter(MURDER_MYSTERY_WINS_AS_MURDERER_KEY))
                    .append(MURDER_MYSTERY_KILLS_AS_MURDERER_KEY, stats.getCustomCounter(MURDER_MYSTERY_KILLS_AS_MURDERER_KEY));
            entries.add(doc);
        }
        Document snapshot = new Document("type", "murdermystery")
                .append("createdAt", System.currentTimeMillis())
                .append("entries", entries);
        collection.insertOne(snapshot);
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
