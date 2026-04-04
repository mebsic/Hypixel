package io.github.mebsic.hub.menu;

import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.service.CosmeticService;
import io.github.mebsic.core.store.KnifeSkinStore;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class KnifeMenu extends Menu {
    public static final String TITLE = "My Cosmetics";
    private static final int SIZE = 36;
    private static final int KNIFE_SKINS_SLOT = 13;
    private static final int BACK_SLOT = 30;
    private static final int TOKENS_SLOT = 31;

    private final CoreApi coreApi;
    private final KnifeSkinsMenu knifeSkinsMenu;
    private final NumberFormat numberFormat;

    public KnifeMenu(CoreApi coreApi, KnifeSkinsMenu knifeSkinsMenu) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
        this.knifeSkinsMenu = knifeSkinsMenu;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        if (coreApi == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            return;
        }
        List<String> options = coreApi.getAvailableCosmetics(CosmeticType.KNIFE);
        int unlocked = resolveUnlockedCount(profile, options);
        String selectedDisplay = resolveSelectedDisplayName(profile);
        int total = resolvePurchasableTotal(options);
        int percent = total <= 0 ? 0 : (unlocked * 100) / total;

        set(inventory, KNIFE_SKINS_SLOT, item(
                Material.DIAMOND_SWORD,
                ChatColor.GREEN + "Knife Skins",
                ChatColor.GRAY + "Select a melee weapon skin for when",
                ChatColor.GRAY + "you play as murderer.",
                "",
                ChatColor.GRAY + "Unlocked: " + ChatColor.GREEN + unlocked + "/" + total + " "
                        + ChatColor.DARK_GRAY + "(" + percent + "%)",
                ChatColor.GRAY + "Currently Selected:",
                ChatColor.GREEN + selectedDisplay,
                "",
                ChatColor.YELLOW + "Click to view!"
        ));
        set(inventory, BACK_SLOT, item(
                Material.ARROW,
                ChatColor.GREEN + "Go Back",
                ChatColor.GRAY + "To Murder Mystery Menu"
        ));
        set(inventory, TOKENS_SLOT, item(
                Material.EMERALD,
                ChatColor.GRAY + "Total Tokens: " + ChatColor.DARK_GREEN + formatTokens(profile),
                ChatColor.GOLD + NetworkConstants.storeUrl()
        ));
    }

    @Override
    public void onClick(MenuClick click) {
        Player player = click.getPlayer();
        if (player == null) {
            return;
        }
        int slot = click.getRawSlot();
        if (slot == BACK_SLOT) {
            new MurderMysteryMenu(coreApi, this).open(player);
            return;
        }
        if (slot == KNIFE_SKINS_SLOT && knifeSkinsMenu != null) {
            knifeSkinsMenu.firstPage().open(player);
        }
    }

    private int resolveUnlockedCount(Profile profile, List<String> options) {
        if (profile == null || options == null || options.isEmpty()) {
            return 0;
        }
        Set<String> unlocked = normalizeIdSet(profile.getUnlocked().get(CosmeticType.KNIFE));
        if (unlocked == null || unlocked.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String option : options) {
            String normalized = normalizeId(option);
            if (!isPurchasable(normalized)) {
                continue;
            }
            if (unlocked.contains(normalized)) {
                count++;
            }
        }
        return count;
    }

    private int resolvePurchasableTotal(List<String> options) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String option : options) {
            if (isPurchasable(normalizeId(option))) {
                total++;
            }
        }
        return total;
    }

    private String resolveSelectedDisplayName(Profile profile) {
        if (profile == null || coreApi == null) {
            return "None";
        }
        String selected = profile.getSelected().get(CosmeticType.KNIFE);
        if (selected == null || selected.trim().isEmpty()) {
            return "None";
        }
        String normalized = normalizeId(selected);
        if (CosmeticService.RANDOM_KNIFE_ID.equals(normalized)) {
            return "Random Knife Skin";
        }
        if (CosmeticService.RANDOM_FAVORITE_KNIFE_ID.equals(normalized)) {
            return "Random Favorite Knife Skin";
        }
        if (KnifeSkinStore.SKIN_02_CHEST_ID.equals(normalized)) {
            return "Random Knife Skin";
        }
        if (KnifeSkinStore.SKIN_03_ENDER_CHEST_ID.equals(normalized)) {
            return "Random Favorite Knife Skin";
        }
        KnifeSkinDefinition skin = coreApi.getKnifeSkins().get(normalized);
        if (skin == null) {
            return selected;
        }
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', skin.getDisplayName()));
    }

    private String formatTokens(Profile profile) {
        int tokens = profile == null ? 0 : MurderMysteryStats.getTokens(profile.getStats());
        return numberFormat.format(Math.max(0, tokens));
    }

    private boolean isPurchasable(String id) {
        return !id.isEmpty()
                && !CosmeticService.DEFAULT_KNIFE_ID.equals(id)
                && !CosmeticService.RANDOM_KNIFE_ID.equals(id)
                && !CosmeticService.RANDOM_FAVORITE_KNIFE_ID.equals(id);
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

    private Set<String> normalizeIdSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        Set<String> normalized = new java.util.HashSet<String>();
        for (String value : values) {
            String id = normalizeId(value);
            if (!id.isEmpty()) {
                normalized.add(id);
            }
        }
        return normalized;
    }
}
