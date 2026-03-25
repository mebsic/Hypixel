package io.github.mebsic.hub.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import org.bson.Document;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mongodb.client.model.Filters.eq;

public class KnifeMenuStateService {
    public static final String COLLECTION_NAME = "murdermystery_knife_menu_state";
    private static final String GAME_TYPE = "murdermystery";

    private static final String SORT_A_Z = "A_Z";
    private static final String SORT_Z_A = "Z_A";
    private static final String SORT_LOWEST_RARITY = "LOWEST_RARITY_FIRST";
    private static final String SORT_HIGHEST_RARITY = "HIGHEST_RARITY_FIRST";
    private static final List<String> SORT_OPTIONS = java.util.Arrays.asList(
            SORT_A_Z,
            SORT_Z_A,
            SORT_LOWEST_RARITY,
            SORT_HIGHEST_RARITY
    );

    private final CoreApi coreApi;
    private final MongoCollection<Document> collection;
    private final Map<UUID, SortState> sortCache;

    public KnifeMenuStateService(CoreApi coreApi, MongoManager mongoManager) {
        this.coreApi = coreApi;
        this.sortCache = new ConcurrentHashMap<UUID, SortState>();
        if (mongoManager == null) {
            this.collection = null;
            return;
        }
        mongoManager.ensureCollection(COLLECTION_NAME);
        this.collection = mongoManager.getCollection(COLLECTION_NAME);
    }

    public void initialize(Collection<? extends Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        for (Player player : players) {
            if (player == null) {
                continue;
            }
            syncPlayer(player);
        }
    }

    public void syncPlayer(Player player) {
        if (player == null) {
            return;
        }
        syncPlayer(player.getUniqueId(), player.getName());
    }

    public void syncPlayer(UUID uuid, String playerName) {
        if (uuid == null || coreApi == null) {
            return;
        }
        Profile profile = coreApi.getProfile(uuid);
        if (profile == null) {
            return;
        }
        Set<String> unlocked = normalizeIdSet(profile.getUnlocked().get(CosmeticType.KNIFE));
        List<String> unlockedOrdered = new ArrayList<String>(unlocked);
        Collections.sort(unlockedOrdered);
        Set<String> favorites = new LinkedHashSet<String>(normalizeIdSet(profile.getFavorites().get(CosmeticType.KNIFE)));
        favorites.retainAll(unlocked);
        favorites.remove("random");
        favorites.remove("random_favorite");
        favorites.remove("mm_skin_02_chest");
        favorites.remove("mm_skin_03_ender_chest");
        List<String> favoriteOrdered = new ArrayList<String>(favorites);
        Collections.sort(favoriteOrdered);
        String selected = normalize(profile.getSelected().get(CosmeticType.KNIFE));
        if (selected.isEmpty()) {
            selected = "iron_sword";
        }

        SortState current = getSortState(uuid);
        String sortMode = normalizeSortMode(current.getSortMode());
        boolean hasOwnedSkins = hasOwnedPurchasableSkins(unlocked);
        boolean ownedFirst = hasOwnedSkins && current.isOwnedItemsFirst();

        sortCache.put(uuid, new SortState(sortMode, ownedFirst));
        if (collection == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Document set = new Document("uuid", uuid.toString())
                .append("gameType", GAME_TYPE)
                .append("playerName", safe(playerName))
                .append("selectedKnifeSkin", selected)
                .append("unlockedKnifeSkinIds", unlockedOrdered)
                .append("favoriteKnifeSkinIds", favoriteOrdered)
                .append("unlockedKnifeSkins", buildUnlockedKnifeDocs(unlockedOrdered, favorites))
                .append("ownedKnifeSkinsCount", unlocked.size())
                .append("favoriteKnifeSkinsCount", favoriteOrdered.size())
                .append("hasOwnedKnifeSkins", hasOwnedSkins)
                .append("sortMode", sortMode)
                .append("ownedItemsFirst", ownedFirst)
                .append("sortOptions", SORT_OPTIONS)
                .append("updatedAt", now);
        collection.updateOne(
                eq("uuid", uuid.toString()),
                new Document("$set", set).append("$setOnInsert", new Document("createdAt", now)),
                new UpdateOptions().upsert(true)
        );
    }

    public SortState getSortState(UUID uuid) {
        if (uuid == null) {
            return new SortState(SORT_LOWEST_RARITY, false);
        }
        SortState cached = sortCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        if (collection == null) {
            return new SortState(SORT_LOWEST_RARITY, false);
        }
        Document doc = collection.find(eq("uuid", uuid.toString())).first();
        if (doc == null) {
            return new SortState(SORT_LOWEST_RARITY, false);
        }
        String sortMode = normalizeSortMode(doc.getString("sortMode"));
        Boolean ownedFirst = doc.getBoolean("ownedItemsFirst");
        SortState state = new SortState(sortMode, ownedFirst != null && ownedFirst);
        sortCache.put(uuid, state);
        return state;
    }

    public void updateSortState(UUID uuid, String playerName, String sortMode, boolean ownedItemsFirst) {
        if (uuid == null) {
            return;
        }
        String safeSortMode = normalizeSortMode(sortMode);
        SortState state = new SortState(safeSortMode, ownedItemsFirst);
        sortCache.put(uuid, state);
        if (collection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Document set = new Document("sortMode", safeSortMode)
                .append("gameType", GAME_TYPE)
                .append("ownedItemsFirst", ownedItemsFirst)
                .append("playerName", safe(playerName))
                .append("sortOptions", SORT_OPTIONS)
                .append("updatedAt", now);
        collection.updateOne(
                eq("uuid", uuid.toString()),
                new Document("$set", set).append("$setOnInsert", new Document("createdAt", now).append("uuid", uuid.toString())),
                new UpdateOptions().upsert(true)
        );
    }

    private List<Document> buildUnlockedKnifeDocs(List<String> unlocked, Set<String> favorites) {
        if (unlocked == null || unlocked.isEmpty() || coreApi == null) {
            return Collections.emptyList();
        }
        Map<String, KnifeSkinDefinition> skinMap = coreApi.getKnifeSkins();
        if (skinMap == null || skinMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> docs = new ArrayList<Document>();
        for (String id : unlocked) {
            String normalized = normalize(id);
            if (normalized.isEmpty()) {
                continue;
            }
            KnifeSkinDefinition skin = skinMap.get(normalized);
            if (skin == null) {
                continue;
            }
            docs.add(new Document("id", normalized)
                    .append("displayName", safe(skin.getDisplayName()))
                    .append("cost", Math.max(0, skin.getCost()))
                    .append("rarity", normalize(skin.getRarity()))
                    .append("favorite", favorites != null && favorites.contains(normalized)));
        }
        return docs;
    }

    private Set<String> normalizeIdSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<String>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private boolean hasOwnedPurchasableSkins(Set<String> unlocked) {
        if (unlocked == null || unlocked.isEmpty()) {
            return false;
        }
        for (String value : unlocked) {
            String normalized = normalize(value);
            if (normalized.isEmpty()) {
                continue;
            }
            if ("iron_sword".equals(normalized)
                    || "random".equals(normalized)
                    || "random_favorite".equals(normalized)
                    || "mm_skin_02_chest".equals(normalized)
                    || "mm_skin_03_ender_chest".equals(normalized)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String normalizeSortMode(String value) {
        String mode = normalize(value).toUpperCase(Locale.ROOT);
        if (SORT_OPTIONS.contains(mode)) {
            return mode;
        }
        return SORT_LOWEST_RARITY;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class SortState {
        private final String sortMode;
        private final boolean ownedItemsFirst;

        public SortState(String sortMode, boolean ownedItemsFirst) {
            this.sortMode = sortMode == null ? SORT_A_Z : sortMode;
            this.ownedItemsFirst = ownedItemsFirst;
        }

        public String getSortMode() {
            return sortMode;
        }

        public boolean isOwnedItemsFirst() {
            return ownedItemsFirst;
        }
    }
}
