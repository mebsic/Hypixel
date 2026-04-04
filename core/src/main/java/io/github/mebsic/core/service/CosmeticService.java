package io.github.mebsic.core.service;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.store.KnifeSkinStore;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class CosmeticService {
    public static final String DEFAULT_KNIFE_ID = KnifeSkinStore.DEFAULT_KNIFE_ID;
    public static final String RANDOM_KNIFE_ID = "random";
    public static final String RANDOM_FAVORITE_KNIFE_ID = "random_favorite";
    private static final KnifeSkinDefinition DEFAULT_KNIFE = new KnifeSkinDefinition(
            DEFAULT_KNIFE_ID,
            "IRON_SWORD",
            "",
            "",
            0
    );
    private final Map<String, KnifeSkinDefinition> knifeSkins;

    public CosmeticService(Map<String, KnifeSkinDefinition> knifeSkins) {
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
                if (key.equals(KnifeSkinStore.SKIN_02_CHEST_ID)
                        || key.equals(KnifeSkinStore.SKIN_03_ENDER_CHEST_ID)) {
                    continue;
                }
                this.knifeSkins.put(key, entry.getValue());
            }
        }
        this.knifeSkins.putIfAbsent(DEFAULT_KNIFE_ID, DEFAULT_KNIFE);
    }

    public List<String> getOptions(CosmeticType type) {
        if (type != CosmeticType.KNIFE) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>();
        options.add(DEFAULT_KNIFE_ID);
        options.add(RANDOM_KNIFE_ID);
        options.add(RANDOM_FAVORITE_KNIFE_ID);
        List<String> remaining = new ArrayList<>();
        for (String key : knifeSkins.keySet()) {
            if (key == null) {
                continue;
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || normalized.equals(DEFAULT_KNIFE_ID)) {
                continue;
            }
            remaining.add(normalized);
        }
        Collections.sort(remaining);
        options.addAll(remaining);
        return Collections.unmodifiableList(options);
    }

    public ItemStack createKnife(Profile profile) {
        String selected = resolveSelectedKnifeId(profile);
        KnifeSkinDefinition skin = resolveKnife(selected);
        ItemStack item = new ItemStack(resolveMaterial(skin));
        applyLegacyVariantData(item, selected);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !DEFAULT_KNIFE_ID.equals(normalizeId(selected))) {
            meta.setDisplayName(colorize(skin.getDisplayName()));
            meta.setLore(Collections.singletonList(colorize(skin.getDescription())));
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createBow(Profile profile) {
        return new ItemStack(Material.BOW);
    }

    public boolean unlock(Profile profile, CosmeticType type, String id) {
        if (profile == null || type != CosmeticType.KNIFE) {
            return false;
        }
        String normalized = normalizeId(id);
        if (normalized.isEmpty() || isSpecialKnifeId(normalized)) {
            return false;
        }
        if (!getOptions(type).contains(normalized)) {
            return false;
        }
        return profile.getUnlocked().get(type).add(normalized);
    }

    public boolean select(Profile profile, CosmeticType type, String id) {
        if (profile == null || type != CosmeticType.KNIFE) {
            return false;
        }
        String normalized = normalizeId(id);
        if (normalized.equals(KnifeSkinStore.SKIN_02_CHEST_ID)) {
            normalized = RANDOM_KNIFE_ID;
        } else if (normalized.equals(KnifeSkinStore.SKIN_03_ENDER_CHEST_ID)) {
            normalized = RANDOM_FAVORITE_KNIFE_ID;
        }
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.equals(RANDOM_KNIFE_ID) || normalized.equals(RANDOM_FAVORITE_KNIFE_ID)) {
            profile.getSelected().put(type, normalized);
            return true;
        }
        if (!getOptions(type).contains(normalized)) {
            return false;
        }
        if (!containsNormalized(profile.getUnlocked().get(type), normalized)) {
            return false;
        }
        profile.getSelected().put(type, normalized);
        return true;
    }

    public boolean toggleFavorite(Profile profile, CosmeticType type, String id) {
        if (profile == null || type != CosmeticType.KNIFE) {
            return false;
        }
        String normalized = normalizeId(id);
        if (normalized.isEmpty() || isSpecialKnifeId(normalized)) {
            return false;
        }
        if (!containsNormalized(profile.getUnlocked().get(type), normalized)) {
            return false;
        }
        Set<String> favorites = profile.getFavorites().get(type);
        if (containsNormalized(favorites, normalized)) {
            removeNormalized(favorites, normalized);
            return true;
        }
        favorites.add(normalized);
        return true;
    }

    public boolean isFavorite(Profile profile, CosmeticType type, String id) {
        if (profile == null || type != CosmeticType.KNIFE) {
            return false;
        }
        String normalized = normalizeId(id);
        if (normalized.isEmpty()) {
            return false;
        }
        return containsNormalized(profile.getFavorites().get(type), normalized);
    }

    public void grantDefaults(Profile profile) {
        profile.getUnlocked().get(CosmeticType.KNIFE).add(DEFAULT_KNIFE_ID);
        profile.getSelected().putIfAbsent(CosmeticType.KNIFE, DEFAULT_KNIFE_ID);
        profile.getFavorites().get(CosmeticType.KNIFE).retainAll(profile.getUnlocked().get(CosmeticType.KNIFE));
    }

    public Map<String, KnifeSkinDefinition> getKnifeSkins() {
        return Collections.unmodifiableMap(knifeSkins);
    }

    private KnifeSkinDefinition resolveKnife(String id) {
        String normalized = normalizeId(id);
        if (normalized.isEmpty()) {
            return knifeSkins.get(DEFAULT_KNIFE_ID);
        }
        KnifeSkinDefinition definition = knifeSkins.get(normalized);
        return definition == null ? knifeSkins.get(DEFAULT_KNIFE_ID) : definition;
    }

    private String resolveSelectedKnifeId(Profile profile) {
        if (profile == null) {
            return DEFAULT_KNIFE_ID;
        }
        String selected = normalizeId(profile.getSelected().getOrDefault(CosmeticType.KNIFE, DEFAULT_KNIFE_ID));
        if (selected.equals(KnifeSkinStore.SKIN_02_CHEST_ID)) {
            selected = RANDOM_KNIFE_ID;
        } else if (selected.equals(KnifeSkinStore.SKIN_03_ENDER_CHEST_ID)) {
            selected = RANDOM_FAVORITE_KNIFE_ID;
        }
        if (selected.equals(RANDOM_KNIFE_ID)) {
            return pickRandomKnife(profile, false);
        }
        if (selected.equals(RANDOM_FAVORITE_KNIFE_ID)) {
            return pickRandomKnife(profile, true);
        }
        if (selected.isEmpty()) {
            return DEFAULT_KNIFE_ID;
        }
        if (!containsNormalized(profile.getUnlocked().get(CosmeticType.KNIFE), selected)) {
            return DEFAULT_KNIFE_ID;
        }
        if (!knifeSkins.containsKey(selected)) {
            return DEFAULT_KNIFE_ID;
        }
        return selected;
    }

    private String pickRandomKnife(Profile profile, boolean favoritesOnly) {
        Set<String> unlocked = normalizeIdSet(profile.getUnlocked().get(CosmeticType.KNIFE));
        if (unlocked == null || unlocked.isEmpty()) {
            return DEFAULT_KNIFE_ID;
        }
        List<String> candidates = new ArrayList<>();
        if (favoritesOnly) {
            Set<String> favorites = profile.getFavorites().get(CosmeticType.KNIFE);
            if (favorites != null) {
                for (String id : favorites) {
                    String normalized = normalizeId(id);
                    if (normalized.isEmpty() || isSpecialKnifeId(normalized)) {
                        continue;
                    }
                    if (unlocked.contains(normalized)) {
                        candidates.add(normalized);
                    }
                }
            }
            if (candidates.isEmpty()) {
                return pickRandomKnife(profile, false);
            }
        } else {
            for (String id : unlocked) {
                String normalized = normalizeId(id);
                if (normalized.isEmpty() || isSpecialKnifeId(normalized)) {
                    continue;
                }
                if (!knifeSkins.containsKey(normalized)) {
                    continue;
                }
                candidates.add(normalized);
            }
        }
        if (candidates.isEmpty()) {
            return DEFAULT_KNIFE_ID;
        }
        int index = ThreadLocalRandom.current().nextInt(candidates.size());
        return candidates.get(index);
    }

    private boolean containsNormalized(Set<String> values, String targetNormalized) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String normalizedTarget = normalizeId(targetNormalized);
        if (normalizedTarget.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (normalizedTarget.equals(normalizeId(value))) {
                return true;
            }
        }
        return false;
    }

    private void removeNormalized(Set<String> values, String targetNormalized) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String normalizedTarget = normalizeId(targetNormalized);
        if (normalizedTarget.isEmpty()) {
            return;
        }
        java.util.Iterator<String> iterator = values.iterator();
        while (iterator.hasNext()) {
            if (normalizedTarget.equals(normalizeId(iterator.next()))) {
                iterator.remove();
            }
        }
    }

    private Set<String> normalizeIdSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<String>();
        for (String value : values) {
            String normalizedValue = normalizeId(value);
            if (!normalizedValue.isEmpty()) {
                normalized.add(normalizedValue);
            }
        }
        return normalized;
    }

    private boolean isSpecialKnifeId(String id) {
        String normalized = normalizeId(id);
        return normalized.equals(RANDOM_KNIFE_ID)
                || normalized.equals(RANDOM_FAVORITE_KNIFE_ID)
                || normalized.equals(KnifeSkinStore.SKIN_02_CHEST_ID)
                || normalized.equals(KnifeSkinStore.SKIN_03_ENDER_CHEST_ID);
    }

    private String normalizeId(String id) {
        return KnifeSkinStore.normalizeKnifeSkinId(id);
    }

    private Material resolveMaterial(KnifeSkinDefinition skin) {
        if (skin == null || skin.getMaterial() == null) {
            return Material.IRON_SWORD;
        }
        String materialName = skin.getMaterial().trim().toUpperCase(Locale.ROOT);
        if (materialName.isEmpty()) {
            return Material.IRON_SWORD;
        }
        try {
            return Material.valueOf(materialName);
        } catch (IllegalArgumentException ignored) {
            if (materialName.endsWith("_SHOVEL")) {
                try {
                    return Material.valueOf(materialName.substring(0, materialName.length() - "_SHOVEL".length()) + "_SPADE");
                } catch (IllegalArgumentException ignoredAgain) {
                    return Material.IRON_SWORD;
                }
            }
            return Material.IRON_SWORD;
        }
    }

    private void applyLegacyVariantData(ItemStack item, String knifeId) {
        if (item == null) {
            return;
        }
        String normalizedId = normalizeId(knifeId);
        if (KnifeSkinStore.SKIN_44_SAPLING_ID.equals(normalizedId) && item.getType() == Material.SAPLING) {
            item.setDurability((short) 3);
            return;
        }
        if (KnifeSkinStore.SKIN_19_COAL_ID.equals(normalizedId) && item.getType() == Material.COAL) {
            item.setDurability((short) 1);
            return;
        }
        if (KnifeSkinStore.SKIN_26_ROSE_ID.equals(normalizedId) && item.getType() == Material.DOUBLE_PLANT) {
            item.setDurability((short) 4);
            return;
        }
        if (KnifeSkinStore.SKIN_39_NETHER_WART_ID.equals(normalizedId) && item.getType() == Material.INK_SACK) {
            item.setDurability((short) 1);
            return;
        }
        if (KnifeSkinStore.SKIN_33_PRISMARINE_SHARD_ID.equals(normalizedId) && item.getType() == Material.INK_SACK) {
            item.setDurability((short) 4);
            return;
        }
        if (KnifeSkinStore.SKIN_38_RAW_FISH_ID.equals(normalizedId) && item.getType() == Material.RAW_FISH) {
            item.setDurability((short) 1);
        }
    }

    private String colorize(String text) {
        if (text == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
