package io.github.mebsic.murdermystery.registry;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.store.KnifeSkinStore;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class KnifeSkinRegistry {
    private static final String DEFAULT_KNIFE_ID = KnifeSkinStore.DEFAULT_KNIFE_ID;
    private static final KnifeSkinDefinition DEFAULT_KNIFE = new KnifeSkinDefinition(
            DEFAULT_KNIFE_ID,
            "IRON_SWORD",
            "",
            "",
            0
    );

    private final Map<String, KnifeSkinDefinition> skins;

    public KnifeSkinRegistry(Map<String, KnifeSkinDefinition> skins) {
        this.skins = new HashMap<>();
        if (skins != null) {
            for (Map.Entry<String, KnifeSkinDefinition> entry : skins.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = KnifeSkinStore.normalizeKnifeSkinId(entry.getKey());
                if (key.isEmpty()) {
                    continue;
                }
                this.skins.put(key, entry.getValue());
            }
        }
        this.skins.putIfAbsent(DEFAULT_KNIFE_ID, DEFAULT_KNIFE);
    }

    public static KnifeSkinRegistry fromCoreApi(CoreApi coreApi) {
        Map<String, KnifeSkinDefinition> source = coreApi == null ? Collections.<String, KnifeSkinDefinition>emptyMap() : coreApi.getKnifeSkins();
        return new KnifeSkinRegistry(source);
    }

    public Map<String, KnifeSkinDefinition> getSkins() {
        return Collections.unmodifiableMap(skins);
    }

    public ItemStack createKnife(Profile profile) {
        String selected = DEFAULT_KNIFE_ID;
        if (profile != null) {
            String value = profile.getSelected().get(CosmeticType.KNIFE);
            if (value != null && !value.trim().isEmpty()) {
                selected = normalizeId(value);
            }
        }
        KnifeSkinDefinition skin = resolve(selected);
        ItemStack item = new ItemStack(resolveMaterial(skin));
        applyLegacyVariantData(item, skin == null ? null : skin.getId());
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !DEFAULT_KNIFE_ID.equals(normalizeId(selected))) {
            meta.setDisplayName(colorize(skin.getDisplayName()));
            meta.setLore(Collections.singletonList(colorize(skin.getDescription())));
            item.setItemMeta(meta);
        }
        return item;
    }

    public KnifeSkinDefinition resolve(String id) {
        String normalized = normalizeId(id);
        if (normalized.isEmpty()) {
            return skins.get(DEFAULT_KNIFE_ID);
        }
        KnifeSkinDefinition skin = skins.get(normalized);
        return skin == null ? skins.get(DEFAULT_KNIFE_ID) : skin;
    }

    public Material resolveMaterial(KnifeSkinDefinition definition) {
        if (definition == null || definition.getMaterial() == null) {
            return Material.IRON_SWORD;
        }
        String materialName = definition.getMaterial().trim().toUpperCase(Locale.ROOT);
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

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (KnifeSkinStore.skinNumber(normalized) > 0) {
            return KnifeSkinStore.normalizeKnifeSkinId(normalized);
        }
        return normalized;
    }

    public String colorize(String value) {
        if (value == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
