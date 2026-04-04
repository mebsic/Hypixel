package io.github.mebsic.proxy.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.github.mebsic.core.util.HypixelExperienceUtil;
import io.github.mebsic.proxy.manager.MongoManager;
import org.bson.Document;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RankResolver {
    private static final String COLOR_GRAY = "\u00A77";
    private static final String COLOR_GREEN = "\u00A7a";
    private static final String COLOR_GOLD = "\u00A76";
    private static final String COLOR_AQUA = "\u00A7b";
    private static final String COLOR_RED = "\u00A7c";
    private static final String COLOR_WHITE = "\u00A7f";
    private static final String COLOR_YELLOW = "\u00A7e";
    private static final String COLOR_LIGHT_PURPLE = "\u00A7d";
    private static final String COLOR_BLUE = "\u00A79";
    private static final String COLOR_DARK_GREEN = "\u00A72";
    private static final String COLOR_DARK_RED = "\u00A74";
    private static final String COLOR_DARK_AQUA = "\u00A73";
    private static final String COLOR_DARK_PURPLE = "\u00A75";
    private static final String COLOR_DARK_GRAY = "\u00A78";
    private static final String COLOR_BLACK = "\u00A70";
    private static final String COLOR_DARK_BLUE = "\u00A71";

    private static final String MVP_PLUS_PLUS_PREFIX_GOLD = "gold";
    private static final String MVP_PLUS_PLUS_PREFIX_AQUA = "aqua";
    private static final long PROFILE_CACHE_TTL_MILLIS = 5_000L;
    private static final int PROFILE_CACHE_MAX_ENTRIES = 5_000;

    private final MongoDatabase database;
    private final Map<String, Integer> weights;
    private final Map<UUID, CachedProfile> profileCache;

    public RankResolver(MongoDatabase database) {
        this.database = database;
        this.weights = new HashMap<>();
        this.profileCache = new ConcurrentHashMap<UUID, CachedProfile>();
        weights.put("DEFAULT", 0);
        weights.put("VIP", 1);
        weights.put("VIP_PLUS", 2);
        weights.put("MVP", 3);
        weights.put("MVP_PLUS", 4);
        weights.put("MVP_PLUS_PLUS", 5);
        weights.put("YOUTUBE", 6);
        weights.put("STAFF", 7);
    }

    public boolean isStaff(UUID uuid) {
        return hasAtLeast(uuid, "STAFF");
    }

    public boolean hasAtLeast(UUID uuid, String required) {
        if (database == null || uuid == null) {
            return false;
        }
        String rank = resolveRankName(loadProfile(uuid));
        String requiredRank = required == null ? "DEFAULT" : required.trim().toUpperCase(Locale.ROOT);
        int current = weights.getOrDefault(rank, 0);
        int needed = weights.getOrDefault(requiredRank, 0);
        return current >= needed;
    }

    public String resolveRank(UUID uuid) {
        if (database == null || uuid == null) {
            return "DEFAULT";
        }
        return resolveRankName(loadProfile(uuid));
    }

    public String formatNameWithRank(UUID uuid, String fallbackName) {
        if (uuid == null) {
            return fallbackName == null ? "" : fallbackName;
        }
        Document profile = loadProfile(uuid);
        String name = resolveName(profile, fallbackName);
        String rankName = resolveRankName(profile);
        return fallbackPrefix(rankName, profile)
                + fallbackBaseColor(rankName, profile)
                + name;
    }

    public String formatNameWithColor(UUID uuid, String fallbackName) {
        if (uuid == null) {
            return fallbackName == null ? "" : fallbackName;
        }
        Document profile = loadProfile(uuid);
        String name = resolveName(profile, fallbackName);
        return fallbackBaseColor(resolveRankName(profile), profile) + name;
    }

    private Document loadProfile(UUID uuid) {
        if (database == null || uuid == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedProfile cached = profileCache.get(uuid);
        if (cached != null && now - cached.loadedAtMillis <= PROFILE_CACHE_TTL_MILLIS) {
            return cached.profile;
        }
        if (cached != null) {
            profileCache.remove(uuid, cached);
        }
        MongoCollection<Document> collection = database.getCollection(MongoManager.PROFILES_COLLECTION);
        try {
            Document loaded = collection.find(new Document("uuid", uuid.toString())).first();
            if (loaded == null) {
                profileCache.remove(uuid);
                return null;
            }
            if (profileCache.size() >= PROFILE_CACHE_MAX_ENTRIES) {
                profileCache.clear();
            }
            profileCache.put(uuid, new CachedProfile(loaded, now));
            return loaded;
        } catch (Exception ex) {
            return cached == null ? null : cached.profile;
        }
    }

    private String resolveRankName(Document doc) {
        String rank = doc == null ? "DEFAULT" : doc.getString("rank");
        if (rank == null || rank.trim().isEmpty()) {
            return "DEFAULT";
        }
        return rank.trim().toUpperCase(Locale.ROOT);
    }

    private int resolveNetworkLevel(Document profile) {
        if (profile == null) {
            return 0;
        }
        Long hypixelExperience = profile.getLong("hypixelExperience");
        if (hypixelExperience != null) {
            return HypixelExperienceUtil.getLevel(hypixelExperience);
        }
        Integer networkLevel = profile.getInteger("networkLevel");
        return networkLevel == null ? 0 : Math.max(0, networkLevel);
    }

    private String resolveName(Document profile, String fallbackName) {
        String fallback = fallbackName == null ? "Unknown" : fallbackName;
        if (profile == null) {
            return fallback;
        }
        String stored = profile.getString("name");
        if (stored == null || stored.trim().isEmpty()) {
            return fallback;
        }
        return stored;
    }

    private String fallbackPrefix(String rankName, Document profile) {
        String rank = rankName == null ? "DEFAULT" : rankName;
        String plusColor = resolvePlusColorCode(profile);
        String mvpPlusPlusColor = resolveMvpPlusPlusPrefixColorCode(profile);
        switch (rank) {
            case "VIP":
                return COLOR_GREEN + "[VIP] ";
            case "VIP_PLUS":
                return COLOR_GREEN + "[VIP" + COLOR_GOLD + "+" + COLOR_GREEN + "] ";
            case "MVP":
                return COLOR_AQUA + "[MVP] ";
            case "MVP_PLUS":
                return COLOR_AQUA + "[MVP" + plusColor + "+" + COLOR_AQUA + "] ";
            case "MVP_PLUS_PLUS":
                return mvpPlusPlusColor + "[MVP" + plusColor + "++" + mvpPlusPlusColor + "] ";
            case "YOUTUBE":
                return COLOR_RED + "[" + COLOR_WHITE + "YOUTUBE" + COLOR_RED + "] ";
            case "STAFF":
                return COLOR_RED + "[" + COLOR_GOLD + "ዞ" + COLOR_RED + "] ";
            default:
                return "";
        }
    }

    private String fallbackBaseColor(String rankName, Document profile) {
        String rank = rankName == null ? "DEFAULT" : rankName;
        if ("MVP_PLUS_PLUS".equals(rank)) {
            return resolveMvpPlusPlusPrefixColorCode(profile);
        }
        switch (rank) {
            case "VIP":
            case "VIP_PLUS":
                return COLOR_GREEN;
            case "MVP":
            case "MVP_PLUS":
                return COLOR_AQUA;
            case "YOUTUBE":
            case "STAFF":
                return COLOR_RED;
            default:
                return COLOR_GRAY;
        }
    }

    private String resolveMvpPlusPlusPrefixColorCode(Document profile) {
        String selected = profile == null ? null : profile.getString("mvpPlusPlusPrefixColor");
        if (selected == null || selected.trim().isEmpty()) {
            return COLOR_GOLD;
        }
        return MVP_PLUS_PLUS_PREFIX_AQUA.equalsIgnoreCase(selected.trim())
                ? COLOR_AQUA
                : COLOR_GOLD;
    }

    private String resolvePlusColorCode(Document profile) {
        int networkLevel = resolveNetworkLevel(profile);
        String selected = profile == null ? null : profile.getString("plusColor");
        return resolvePlusColorCode(networkLevel, selected);
    }

    private String resolvePlusColorCode(int networkLevel, String selectedId) {
        String unlockedColor = COLOR_RED;
        int level = Math.max(0, networkLevel);
        for (PlusColorEntry entry : PlusColorEntry.values()) {
            if (entry.giftedReward) {
                continue;
            }
            if (level >= entry.level) {
                unlockedColor = entry.code;
            }
        }
        if (selectedId == null || selectedId.trim().isEmpty()) {
            return unlockedColor;
        }
        String normalized = selectedId.trim().toLowerCase(Locale.ROOT);
        for (PlusColorEntry entry : PlusColorEntry.values()) {
            if (!entry.id.equals(normalized)) {
                continue;
            }
            if (entry.giftedReward || level >= entry.level) {
                return entry.code;
            }
            break;
        }
        return unlockedColor;
    }

    private enum PlusColorEntry {
        RED("red", 0, COLOR_RED, false),
        GOLD("gold", 35, COLOR_GOLD, false),
        GREEN("green", 45, COLOR_GREEN, false),
        YELLOW("yellow", 55, COLOR_YELLOW, false),
        LIGHT_PURPLE("light_purple", 65, COLOR_LIGHT_PURPLE, false),
        WHITE("white", 75, COLOR_WHITE, false),
        BLUE("blue", 85, COLOR_BLUE, false),
        DARK_GREEN("dark_green", 95, COLOR_DARK_GREEN, false),
        DARK_RED("dark_red", 150, COLOR_DARK_RED, false),
        DARK_AQUA("dark_aqua", 150, COLOR_DARK_AQUA, false),
        DARK_PURPLE("dark_purple", 200, COLOR_DARK_PURPLE, false),
        DARK_GRAY("dark_gray", 200, COLOR_DARK_GRAY, false),
        BLACK("black", 250, COLOR_BLACK, false),
        DARK_BLUE("dark_blue", 0, COLOR_DARK_BLUE, true);

        private final String id;
        private final int level;
        private final String code;
        private final boolean giftedReward;

        PlusColorEntry(String id, int level, String code, boolean giftedReward) {
            this.id = id;
            this.level = level;
            this.code = code;
            this.giftedReward = giftedReward;
        }
    }

    private static final class CachedProfile {
        private final Document profile;
        private final long loadedAtMillis;

        private CachedProfile(Document profile, long loadedAtMillis) {
            this.profile = profile;
            this.loadedAtMillis = Math.max(0L, loadedAtMillis);
        }
    }
}
