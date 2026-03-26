package io.github.mebsic.core.service;

import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.GameResult;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface CoreApi {
    Profile getProfile(UUID uuid);

    void loadProfileAsync(UUID uuid, String name);

    void recordGameResult(GameResult result);

    Rank getRank(UUID uuid);

    void setRank(UUID uuid, Rank rank);

    int getNetworkLevel(UUID uuid);

    void setNetworkLevel(UUID uuid, int level);

    int getNetworkGold(UUID uuid);

    void setNetworkGold(UUID uuid, int amount);

    void setPlusColor(UUID uuid, String colorId);

    void setMvpPlusPlusPrefixColor(UUID uuid, String colorId);

    long getHypixelExperience(UUID uuid);

    void addHypixelExperience(UUID uuid, long amount);

    ItemStack createKnife(UUID uuid);

    ItemStack createBow(UUID uuid);

    List<String> getAvailableCosmetics(CosmeticType type);

    boolean unlockCosmetic(UUID uuid, CosmeticType type, String id);

    boolean selectCosmetic(UUID uuid, CosmeticType type, String id);

    boolean toggleFavoriteCosmetic(UUID uuid, CosmeticType type, String id);

    boolean isFavoriteCosmetic(UUID uuid, CosmeticType type, String id);

    int getCounter(UUID uuid, String key);

    boolean spendCounter(UUID uuid, String key, int amount);

    void addCounter(UUID uuid, String key, int amount);

    java.util.Map<String, io.github.mebsic.core.model.KnifeSkinDefinition> getKnifeSkins();
}
