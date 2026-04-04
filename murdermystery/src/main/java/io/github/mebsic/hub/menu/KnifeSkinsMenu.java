package io.github.mebsic.hub.menu;

import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import io.github.mebsic.core.model.CosmeticType;
import io.github.mebsic.core.model.KnifeSkinDefinition;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.service.CosmeticService;
import io.github.mebsic.core.store.KnifeSkinStore;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.hub.service.KnifeMenuStateService;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class KnifeSkinsMenu extends Menu {
    public static final String TITLE = "Knife Skins";
    private static final String SHIFT_FAVORITE_TEXT = ChatColor.YELLOW + "Shift-click to toggle favorite!";
    private static final int SIZE = 54;
    private static final int BACK_SLOT = 48;
    private static final int TOKENS_SLOT = 49;
    private static final int SORT_SLOT = 50;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int PAGE_SIZE = 21;
    private static final int[] COSMETIC_SLOTS = new int[] {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final CoreApi coreApi;
    private final Map<String, Integer> costs;
    private final KnifeMenuStateService menuStateService;
    private final NumberFormat numberFormat;
    private final int page;

    public KnifeSkinsMenu(CoreApi coreApi, Map<String, Integer> costs, KnifeMenuStateService menuStateService) {
        this(coreApi, costs, 1, menuStateService);
    }

    public KnifeSkinsMenu firstPage() {
        return new KnifeSkinsMenu(coreApi, costs, 1, menuStateService);
    }

    private KnifeSkinsMenu(CoreApi coreApi, Map<String, Integer> costs, int page, KnifeMenuStateService menuStateService) {
        super(resolveTitle(page), SIZE);
        this.coreApi = coreApi;
        this.costs = new HashMap<String, Integer>();
        if (costs != null) {
            this.costs.putAll(costs);
        }
        this.menuStateService = menuStateService;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.page = Math.max(1, page);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null || coreApi == null || player == null) {
            return;
        }
        inventory.clear();
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            return;
        }
        int availableTokens = Math.max(0, MurderMysteryStats.getTokens(profile.getStats()));
        Set<String> unlockedOptions = normalizeIdSet(profile.getUnlocked().get(CosmeticType.KNIFE));
        Set<String> favoriteOptions = normalizeIdSet(profile.getFavorites().get(CosmeticType.KNIFE));
        String selectedId = normalizeSelectedKnifeId(profile.getSelected().get(CosmeticType.KNIFE));
        SortMode sortMode = resolveSortMode(player.getUniqueId());
        boolean ownedFirst = resolveOwnedFirst(player.getUniqueId());
        List<KnifeEntry> entries = sortEntries(
                buildEntries(unlockedOptions, favoriteOptions, selectedId),
                sortMode,
                ownedFirst
        );
        boolean canToggleOwnedFirst = hasUnlockedPurchasable(unlockedOptions);

        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        int start = (currentPage - 1) * PAGE_SIZE;

        for (int i = 0; i < COSMETIC_SLOTS.length; i++) {
            int absoluteIndex = start + i;
            if (absoluteIndex < 0 || absoluteIndex >= entries.size()) {
                break;
            }
            KnifeEntry entry = entries.get(absoluteIndex);
            set(inventory, COSMETIC_SLOTS[i], buildKnifeItem(entry, availableTokens));
        }

        if (totalPages > 1 && currentPage > 1) {
            set(inventory, PREVIOUS_PAGE_SLOT, item(
                    Material.ARROW,
                    ChatColor.GREEN + "Previous Page",
                    ChatColor.YELLOW + "Left-click for previous page!",
                    ChatColor.AQUA + "Right-click for first page!"
            ));
        }
        if (totalPages > 1 && currentPage < totalPages) {
            set(inventory, NEXT_PAGE_SLOT, item(
                    Material.ARROW,
                    ChatColor.GREEN + "Next Page",
                    ChatColor.YELLOW + "Left-click for next page!",
                    ChatColor.AQUA + "Right-click for last page!"
            ));
        }
        set(inventory, SORT_SLOT, buildSortItem(sortMode, ownedFirst, canToggleOwnedFirst));
        set(inventory, BACK_SLOT, item(Material.ARROW, ChatColor.GREEN + "Go Back", ChatColor.GRAY + "To My Cosmetics"));
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
            new KnifeMenu(coreApi, this).open(player);
            return;
        }
        if (coreApi == null) {
            player.sendMessage(ChatColor.RED + "Cosmetics are unavailable!");
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
            return;
        }
        int availableTokens = Math.max(0, MurderMysteryStats.getTokens(profile.getStats()));
        Set<String> unlockedOptions = normalizeIdSet(profile.getUnlocked().get(CosmeticType.KNIFE));
        Set<String> favoriteOptions = normalizeIdSet(profile.getFavorites().get(CosmeticType.KNIFE));
        String selectedId = normalizeSelectedKnifeId(profile.getSelected().get(CosmeticType.KNIFE));
        SortMode sortMode = resolveSortMode(player.getUniqueId());
        boolean ownedFirst = resolveOwnedFirst(player.getUniqueId());
        List<KnifeEntry> entries = sortEntries(
                buildEntries(unlockedOptions, favoriteOptions, selectedId),
                sortMode,
                ownedFirst
        );
        boolean canToggleOwnedFirst = hasUnlockedPurchasable(unlockedOptions);
        int totalPages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int currentPage = Math.max(1, Math.min(page, totalPages));

        if (slot == PREVIOUS_PAGE_SLOT && totalPages > 1 && currentPage > 1) {
            if (click.isRightClick()) {
                openPage(player, 1);
                return;
            }
            openPage(player, Math.max(1, currentPage - 1));
            return;
        }
        if (slot == NEXT_PAGE_SLOT && totalPages > 1 && currentPage < totalPages) {
            if (click.isRightClick()) {
                openPage(player, totalPages);
                return;
            }
            openPage(player, Math.min(totalPages, currentPage + 1));
            return;
        }
        if (slot == SORT_SLOT) {
            SortMode nextSortMode = sortMode;
            boolean nextOwnedFirst = ownedFirst;
            if (click.isRightClick()) {
                if (!canToggleOwnedFirst) {
                    player.sendMessage(ChatColor.RED + "You don't own any knife skins yet!");
                    return;
                }
                nextOwnedFirst = !ownedFirst;
            } else {
                nextSortMode = sortMode.next();
            }
            if (menuStateService != null) {
                menuStateService.updateSortState(
                        player.getUniqueId(),
                        player.getName(),
                        nextSortMode.name(),
                        nextOwnedFirst
                );
            }
            openPage(player, currentPage);
            return;
        }
        KnifeEntry entry = resolveEntryForSlot(slot, entries, currentPage);
        if (entry == null) {
            return;
        }
        String option = normalizeId(entry.optionId);
        if (isSpecialOption(option)) {
            coreApi.selectCosmetic(player.getUniqueId(), CosmeticType.KNIFE, option);
            sendSelectedMessage(player, displayName(entry));
            if (menuStateService != null) {
                menuStateService.syncPlayer(player);
            }
            openPage(player, currentPage);
            return;
        }
        if (click.isShiftClick()) {
            if (!entry.unlocked) {
                player.sendMessage(ChatColor.RED + "You need to purchase " + ChatColor.GOLD + displayName(entry)
                        + ChatColor.RED + " to favorite it!");
                return;
            }
            if (!coreApi.toggleFavoriteCosmetic(player.getUniqueId(), CosmeticType.KNIFE, option)) {
                player.sendMessage(ChatColor.RED + "Failed to update favorite state!");
                return;
            }
            boolean favoriteNow = coreApi.isFavoriteCosmetic(player.getUniqueId(), CosmeticType.KNIFE, option);
            if (favoriteNow) {
                player.sendMessage(ChatColor.YELLOW + "Added " + ChatColor.GOLD + "✯ " + displayName(entry)
                        + ChatColor.YELLOW + " to your favorites!");
            } else {
                player.sendMessage(ChatColor.YELLOW + "Removed " + ChatColor.GREEN + displayName(entry)
                        + ChatColor.YELLOW + " from your favorites!");
            }
            if (menuStateService != null) {
                menuStateService.syncPlayer(player);
            }
            openPage(player, 1);
            return;
        }
        if (!entry.unlocked) {
            int cost = getCost(option);
            if (availableTokens < cost) {
                int needed = Math.max(0, cost - availableTokens);
                player.closeInventory();
                sendNeedMoreTokensMessage(player, needed, displayName(entry));
                return;
            }
            new KnifePurchaseConfirmMenu(coreApi, costs, currentPage, menuStateService, option, cost, displayName(entry)).open(player);
            return;
        }
        coreApi.selectCosmetic(player.getUniqueId(), CosmeticType.KNIFE, option);
        sendSelectedMessage(player, displayName(entry));
        if (menuStateService != null) {
            menuStateService.syncPlayer(player);
        }
        openPage(player, currentPage);
    }

    private int getCost(String option) {
        if (option == null) {
            return 0;
        }
        String key = normalizeId(option);
        if (isSpecialOption(key)) {
            return 0;
        }
        int cost = 0;
        if (costs.containsKey(key)) {
            cost = Math.max(0, costs.get(key));
        }
        KnifeSkinDefinition skin = coreApi == null ? null : coreApi.getKnifeSkins().get(key);
        if (cost <= 0 && skin != null) {
            cost = Math.max(0, skin.getCost());
        }
        if (cost <= 0) {
            cost = defaultCostForRarity(skin == null ? null : skin.getRarity());
        }
        return Math.max(0, cost);
    }

    private String formatTokens(Profile profile) {
        int tokens = profile == null ? 0 : MurderMysteryStats.getTokens(profile.getStats());
        return numberFormat.format(Math.max(0, tokens));
    }

    private void openPage(Player player, int targetPage) {
        if (player == null) {
            return;
        }
        new KnifeSkinsMenu(coreApi, costs, targetPage, menuStateService).open(player);
    }

    private static String resolveTitle(int page) {
        int safePage = Math.max(1, page);
        if (safePage <= 1) {
            return TITLE;
        }
        return TITLE + " (Page " + safePage + ")";
    }

    private List<KnifeEntry> buildEntries(Set<String> unlockedOptions, Set<String> favoriteOptions, String selectedId) {
        if (coreApi == null) {
            return Collections.emptyList();
        }
        List<KnifeEntry> entries = new ArrayList<KnifeEntry>();
        List<String> options = coreApi.getAvailableCosmetics(CosmeticType.KNIFE);
        if (options == null || options.isEmpty()) {
            return entries;
        }
        Map<String, KnifeSkinDefinition> skins = coreApi.getKnifeSkins();
        for (String option : options) {
            String normalized = normalizeId(option);
            if (normalized.isEmpty()) {
                continue;
            }
            boolean selected = normalized.equals(selectedId);
            boolean unlocked = isSpecialOption(normalized)
                    || CosmeticService.DEFAULT_KNIFE_ID.equals(normalized)
                    || unlockedOptions.contains(normalized);
            boolean favorite = favoriteOptions.contains(normalized);
            if (CosmeticService.RANDOM_KNIFE_ID.equals(normalized)) {
                entries.add(new KnifeEntry(
                        normalized,
                        syntheticSkin(normalized, "CHEST", "&aRandom Knife Skin"),
                        true,
                        selected,
                        false,
                        EntryType.RANDOM
                ));
                continue;
            }
            if (CosmeticService.RANDOM_FAVORITE_KNIFE_ID.equals(normalized)) {
                entries.add(new KnifeEntry(
                        normalized,
                        syntheticSkin(normalized, "ENDER_CHEST", "&aRandom Favorite Knife Skin"),
                        true,
                        selected,
                        false,
                        EntryType.RANDOM_FAVORITE
                ));
                continue;
            }
            KnifeSkinDefinition skin = skins.get(normalized);
            if (skin == null && CosmeticService.DEFAULT_KNIFE_ID.equals(normalized)) {
                skin = syntheticSkin(normalized, "IRON_SWORD", "&aDefault Iron Sword");
            }
            if (skin == null) {
                continue;
            }
            entries.add(new KnifeEntry(normalized, skin, unlocked, selected, favorite, EntryType.STANDARD));
        }
        return entries;
    }

    private List<KnifeEntry> sortEntries(List<KnifeEntry> input, SortMode sortMode, boolean ownedFirst) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        KnifeEntry defaultEntry = null;
        KnifeEntry randomEntry = null;
        KnifeEntry randomFavoriteEntry = null;
        List<KnifeEntry> sorted = new ArrayList<KnifeEntry>();
        for (KnifeEntry entry : input) {
            if (entry == null) {
                continue;
            }
            String normalized = normalizeId(entry.optionId);
            if (CosmeticService.DEFAULT_KNIFE_ID.equals(normalized)) {
                defaultEntry = entry;
                continue;
            }
            if (CosmeticService.RANDOM_KNIFE_ID.equals(normalized)) {
                randomEntry = entry;
                continue;
            }
            if (CosmeticService.RANDOM_FAVORITE_KNIFE_ID.equals(normalized)) {
                randomFavoriteEntry = entry;
                continue;
            }
            sorted.add(entry);
        }
        Comparator<KnifeEntry> base = comparatorFor(sortMode);
        Comparator<KnifeEntry> comparator = base;
        if (ownedFirst) {
            comparator = Comparator.comparingInt((KnifeEntry entry) -> entry.unlocked ? 0 : 1).thenComparing(base);
        }
        comparator = Comparator.comparingInt((KnifeEntry entry) -> entry.favorite ? 0 : 1).thenComparing(comparator);
        Collections.sort(sorted, comparator);
        List<KnifeEntry> combined = new ArrayList<KnifeEntry>();
        if (defaultEntry != null) {
            combined.add(defaultEntry);
        }
        if (randomEntry != null) {
            combined.add(randomEntry);
        }
        if (randomFavoriteEntry != null) {
            combined.add(randomFavoriteEntry);
        }
        combined.addAll(sorted);
        return combined;
    }

    private Comparator<KnifeEntry> comparatorFor(SortMode mode) {
        SortMode safeMode = mode == null ? SortMode.A_Z : mode;
        Comparator<KnifeEntry> byNameAsc = Comparator.comparing(
                (KnifeEntry entry) -> plainDisplayName(entry.definition).toLowerCase(Locale.ROOT)
        ).thenComparing((KnifeEntry entry) -> normalizeId(entry.optionId));
        Comparator<KnifeEntry> byCatalogOrder = Comparator
                .comparingInt((KnifeEntry entry) -> cosmeticSequence(entry.optionId))
                .thenComparing((KnifeEntry entry) -> normalizeId(entry.optionId));
        Comparator<KnifeEntry> byCostAsc = Comparator
                .comparingInt((KnifeEntry entry) -> getCost(entry.optionId))
                .thenComparing(byCatalogOrder);
        Comparator<KnifeEntry> byCostDesc = Comparator
                .comparingInt((KnifeEntry entry) -> getCost(entry.optionId))
                .reversed()
                .thenComparing(byCatalogOrder);
        switch (safeMode) {
            case Z_A:
                return byNameAsc.reversed();
            case LOWEST_RARITY_FIRST:
                return Comparator.comparingInt((KnifeEntry entry) -> rarityRank(entry.definition.getRarity()))
                        .thenComparing(byCostAsc);
            case HIGHEST_RARITY_FIRST:
                return Comparator.comparingInt((KnifeEntry entry) -> rarityRank(entry.definition.getRarity()))
                        .reversed()
                        .thenComparing(byCostDesc);
            case A_Z:
            default:
                return byNameAsc;
        }
    }

    private int cosmeticSequence(String optionId) {
        String normalized = normalizeId(optionId);
        if (normalized.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int skinNumber = KnifeSkinStore.skinNumber(normalized);
        if (skinNumber > 0) {
            return skinNumber;
        }
        if (CosmeticService.DEFAULT_KNIFE_ID.equals(normalized)) {
            return 1;
        }
        if (CosmeticService.RANDOM_KNIFE_ID.equals(normalized)) {
            return 2;
        }
        if (CosmeticService.RANDOM_FAVORITE_KNIFE_ID.equals(normalized)) {
            return 3;
        }
        return Integer.MAX_VALUE;
    }

    private ItemStack buildKnifeItem(KnifeEntry entry, int availableTokens) {
        if (entry == null || entry.definition == null) {
            return item(Material.PAPER, ChatColor.RED + "Unknown Skin");
        }
        if (entry.type == EntryType.RANDOM) {
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Use a Random Knife Skin!");
            lore.add("");
            if (entry.selected) {
                lore.add(ChatColor.GREEN + "SELECTED!");
            } else {
                lore.add(ChatColor.YELLOW + "Click to select!");
            }
            return item(Material.CHEST, ChatColor.GREEN + "Random Knife Skin", lore);
        }
        if (entry.type == EntryType.RANDOM_FAVORITE) {
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Use a Random " + ChatColor.GOLD + "✯ Favorite" + ChatColor.GRAY + " Knife Skin!");
            lore.add("");
            if (entry.selected) {
                lore.add(ChatColor.GREEN + "SELECTED!");
            } else {
                lore.add(ChatColor.YELLOW + "Click to select!");
            }
            return item(Material.ENDER_CHEST, ChatColor.GREEN + "Random Favorite Knife Skin", lore);
        }
        Material material = resolveMaterial(entry.definition.getMaterial());
        int cost = getCost(entry.optionId);
        String rarity = normalizeRarity(entry.definition.getRarity());
        boolean defaultKnife = CosmeticService.DEFAULT_KNIFE_ID.equals(normalizeId(entry.optionId));
        boolean canAfford = availableTokens >= cost;
        String baseName = plainDisplayName(entry.definition);
        String itemName = colorize(entry.definition.getDisplayName());
        if (!entry.unlocked) {
            itemName = canAfford
                    ? ChatColor.GREEN + baseName
                    : ChatColor.RED + baseName;
        }
        if (entry.favorite && entry.unlocked) {
            itemName = ChatColor.GOLD + "✯ " + ChatColor.GREEN + baseName;
        }
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Knife Skin");
        lore.add("");
        String description = definitionDescription(entry.definition);
        if (description.isEmpty()) {
            lore.add(ChatColor.GRAY + "Placeholder text.");
        } else {
            for (String line : wrapDescription(description, 36)) {
                lore.add(ChatColor.GRAY + line);
            }
        }
        if (!defaultKnife) {
            lore.add("");
            lore.add(ChatColor.GRAY + "Rarity: " + rarityColor(rarity) + rarityLabel(rarity));
            lore.add("");
        } else {
            lore.add("");
        }
        if (!entry.unlocked) {
            lore.add(ChatColor.GRAY + "Cost: " + ChatColor.DARK_GREEN + numberFormat.format(cost) + " Tokens");
            lore.add("");
            if (canAfford) {
                lore.add(ChatColor.YELLOW + "Click to purchase!");
            } else {
                int needed = Math.max(0, cost - Math.max(0, availableTokens));
                lore.add(ChatColor.RED + "You need " + ChatColor.DARK_GREEN + numberFormat.format(needed)
                        + ChatColor.RED + " more Tokens!");
            }
            ItemStack lockedItem = item(material, itemName, lore);
            applyLegacyVariantData(lockedItem, entry.optionId);
            return lockedItem;
        }
        if (entry.selected) {
            lore.add(ChatColor.GREEN + "SELECTED!");
        } else {
            lore.add(ChatColor.YELLOW + "Click to select!");
        }
        lore.add(SHIFT_FAVORITE_TEXT);
        ItemStack unlockedItem = item(material, itemName, lore);
        applyLegacyVariantData(unlockedItem, entry.optionId);
        return unlockedItem;
    }

    private ItemStack buildSortItem(SortMode mode, boolean ownedFirst, boolean hasOwnedSkins) {
        SortMode safeMode = mode == null ? SortMode.A_Z : mode;
        SortMode nextMode = safeMode.next();
        Material material = Material.matchMaterial("HOPPER");
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + safeMode.description());
        lore.add("");
        lore.add(ChatColor.GRAY + "Next sort: " + ChatColor.GREEN + nextMode.label());
        lore.add(ChatColor.YELLOW + "Left-click to use!");
        if (hasOwnedSkins) {
            lore.add("");
            lore.add(ChatColor.GRAY + "Owned items first: " + (ownedFirst ? ChatColor.GREEN + "Yes" : ChatColor.RED + "No"));
            lore.add(ChatColor.YELLOW + "Right-click to toggle!");
        }
        return item(
                material == null ? Material.PAPER : material,
                ChatColor.GOLD + "Sorted by: " + ChatColor.GREEN + safeMode.label(),
                lore
        );
    }

    private KnifeEntry resolveEntryForSlot(int slot, List<KnifeEntry> entries, int currentPage) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        int indexOnPage = slotIndex(slot);
        if (indexOnPage < 0) {
            return null;
        }
        int absoluteIndex = (Math.max(1, currentPage) - 1) * PAGE_SIZE + indexOnPage;
        if (absoluteIndex < 0 || absoluteIndex >= entries.size()) {
            return null;
        }
        return entries.get(absoluteIndex);
    }

    private int slotIndex(int slot) {
        for (int i = 0; i < COSMETIC_SLOTS.length; i++) {
            if (COSMETIC_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private SortMode resolveSortMode(UUID uuid) {
        if (menuStateService != null) {
            KnifeMenuStateService.SortState state = menuStateService.getSortState(uuid);
            return parseSortMode(state.getSortMode());
        }
        return SortMode.A_Z;
    }

    private boolean resolveOwnedFirst(UUID uuid) {
        if (menuStateService != null) {
            KnifeMenuStateService.SortState state = menuStateService.getSortState(uuid);
            return state.isOwnedItemsFirst();
        }
        return false;
    }

    private Material resolveMaterial(String materialName) {
        if (materialName == null || materialName.trim().isEmpty()) {
            return Material.IRON_SWORD;
        }
        String normalized = materialName.trim().toUpperCase(Locale.ROOT);
        try {
            return Material.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            if (normalized.endsWith("_SHOVEL")) {
                try {
                    return Material.valueOf(normalized.substring(0, normalized.length() - "_SHOVEL".length()) + "_SPADE");
                } catch (IllegalArgumentException ignored) {
                    return Material.IRON_SWORD;
                }
            }
            return Material.IRON_SWORD;
        }
    }

    private int rarityRank(String rarity) {
        String normalized = normalizeRarity(rarity);
        if (normalized.equals("legendary")) {
            return 3;
        }
        if (normalized.equals("epic")) {
            return 2;
        }
        if (normalized.equals("rare")) {
            return 1;
        }
        return 0;
    }

    private String rarityLabel(String rarity) {
        String normalized = normalizeRarity(rarity);
        if (normalized.equals("legendary")) {
            return "LEGENDARY";
        }
        if (normalized.equals("epic")) {
            return "EPIC";
        }
        if (normalized.equals("rare")) {
            return "RARE";
        }
        return "COMMON";
    }

    private ChatColor rarityColor(String rarity) {
        String normalized = normalizeRarity(rarity);
        if (normalized.equals("legendary")) {
            return ChatColor.GOLD;
        }
        if (normalized.equals("epic")) {
            return ChatColor.DARK_PURPLE;
        }
        if (normalized.equals("rare")) {
            return ChatColor.AQUA;
        }
        return ChatColor.GREEN;
    }

    private String plainDisplayName(KnifeSkinDefinition definition) {
        if (definition == null) {
            return "";
        }
        String colored = colorize(definition.getDisplayName());
        String plain = ChatColor.stripColor(colored);
        return plain == null ? "" : plain;
    }

    @Override
    protected String colorize(String value) {
        if (value == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private Set<String> normalizeIdSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<String>();
        for (String value : values) {
            String key = normalizeId(value);
            if (!key.isEmpty()) {
                normalized.add(key);
            }
        }
        return normalized;
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

    private String normalizeSelectedKnifeId(String value) {
        String normalized = normalizeId(value);
        if (KnifeSkinStore.SKIN_02_CHEST_ID.equals(normalized)) {
            return CosmeticService.RANDOM_KNIFE_ID;
        }
        if (KnifeSkinStore.SKIN_03_ENDER_CHEST_ID.equals(normalized)) {
            return CosmeticService.RANDOM_FAVORITE_KNIFE_ID;
        }
        return normalized;
    }

    private String normalizeRarity(String rarity) {
        String normalized = normalizeId(rarity);
        if (normalized.equals("legendary")) {
            return "legendary";
        }
        if (normalized.equals("epic")) {
            return "epic";
        }
        if (normalized.equals("rare")) {
            return "rare";
        }
        return "common";
    }

    private int defaultCostForRarity(String rarity) {
        String normalized = normalizeRarity(rarity);
        if (normalized.equals("legendary")) {
            return 9500000;
        }
        if (normalized.equals("epic")) {
            return 5750000;
        }
        if (normalized.equals("rare")) {
            return 1500000;
        }
        return 250000;
    }

    private boolean hasUnlockedPurchasable(Set<String> unlockedOptions) {
        if (unlockedOptions == null || unlockedOptions.isEmpty()) {
            return false;
        }
        for (String unlocked : unlockedOptions) {
            String normalized = normalizeId(unlocked);
            if (normalized.isEmpty() || isSpecialOption(normalized)) {
                continue;
            }
            if (CosmeticService.DEFAULT_KNIFE_ID.equals(normalized)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isSpecialOption(String option) {
        String normalized = normalizeId(option);
        return CosmeticService.RANDOM_KNIFE_ID.equals(normalized)
                || CosmeticService.RANDOM_FAVORITE_KNIFE_ID.equals(normalized)
                || KnifeSkinStore.SKIN_02_CHEST_ID.equals(normalized)
                || KnifeSkinStore.SKIN_03_ENDER_CHEST_ID.equals(normalized);
    }

    private String displayName(KnifeEntry entry) {
        if (entry == null || entry.definition == null) {
            return "Unknown Skin";
        }
        String plain = ChatColor.stripColor(colorize(entry.definition.getDisplayName()));
        if (plain == null || plain.trim().isEmpty()) {
            return "Unknown Skin";
        }
        return plain.trim();
    }

    private void sendSelectedMessage(Player player, String knifeName) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.GOLD + "You selected " + ChatColor.GREEN + knifeName + ChatColor.GOLD + "!");
    }

    private void sendPurchasedMessage(Player player, String knifeName) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.GOLD + "You purchased " + ChatColor.GREEN + knifeName + ChatColor.GOLD + "!");
    }

    private void sendNeedMoreTokensMessage(Player player, int needed, String knifeName) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.RED + "You need " + ChatColor.DARK_GREEN + numberFormat.format(Math.max(0, needed))
                + ChatColor.RED + " more tokens to purchase " + ChatColor.GOLD
                + (knifeName == null ? "Knife Skin" : knifeName) + ChatColor.RED + "!");
    }

    private String definitionDescription(KnifeSkinDefinition definition) {
        if (definition == null || definition.getDescription() == null) {
            return "";
        }
        String plain = ChatColor.stripColor(colorize(definition.getDescription()));
        if (plain == null) {
            return "";
        }
        return plain.trim();
    }

    private List<String> wrapDescription(String text, int maxLineLength) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        int width = Math.max(10, maxLineLength);
        String[] words = text.trim().split("\\s+");
        List<String> lines = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            if (current.length() == 0) {
                current.append(word);
                continue;
            }
            if (current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            } else {
                current.append(' ').append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private KnifeSkinDefinition syntheticSkin(String id, String material, String displayName) {
        return new KnifeSkinDefinition(id, material, displayName, "", 0, KnifeSkinDefinition.DEFAULT_RARITY);
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

    private SortMode parseSortMode(String value) {
        if (value == null || value.trim().isEmpty()) {
            return SortMode.A_Z;
        }
        try {
            return SortMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SortMode.A_Z;
        }
    }

    private static final class KnifeEntry {
        private final String optionId;
        private final KnifeSkinDefinition definition;
        private final boolean unlocked;
        private final boolean selected;
        private final boolean favorite;
        private final EntryType type;

        private KnifeEntry(String optionId, KnifeSkinDefinition definition, boolean unlocked, boolean selected, boolean favorite, EntryType type) {
            this.optionId = optionId;
            this.definition = definition;
            this.unlocked = unlocked;
            this.selected = selected;
            this.favorite = favorite;
            this.type = type == null ? EntryType.STANDARD : type;
        }
    }

    private final class KnifePurchaseConfirmMenu extends Menu {
        private static final int CONFIRM_SIZE = 27;
        private static final int CONFIRM_SLOT = 11;
        private static final int CANCEL_SLOT = 15;

        private final CoreApi api;
        private final Map<String, Integer> localCosts;
        private final int returnPage;
        private final KnifeMenuStateService stateService;
        private final String knifeId;
        private final int knifeCost;
        private final String knifeName;

        private KnifePurchaseConfirmMenu(CoreApi api,
                                         Map<String, Integer> localCosts,
                                         int returnPage,
                                         KnifeMenuStateService stateService,
                                         String knifeId,
                                         int knifeCost,
                                         String knifeName) {
            super("Confirm", CONFIRM_SIZE);
            this.api = api;
            this.localCosts = localCosts == null ? Collections.<String, Integer>emptyMap() : new HashMap<String, Integer>(localCosts);
            this.returnPage = Math.max(1, returnPage);
            this.stateService = stateService;
            this.knifeId = normalizeId(knifeId);
            this.knifeCost = Math.max(0, knifeCost);
            this.knifeName = knifeName == null || knifeName.trim().isEmpty() ? "Knife Skin" : knifeName.trim();
        }

        @Override
        protected void populate(Player player, Inventory inventory) {
            if (inventory == null) {
                return;
            }
            inventory.clear();
            set(inventory, CONFIRM_SLOT, clayButton((short) 13, ChatColor.GREEN + "Confirm",
                    ChatColor.GRAY + "Unlock: " + ChatColor.GOLD + knifeName,
                    ChatColor.GRAY + "Cost: " + ChatColor.DARK_GREEN + numberFormat.format(knifeCost) + " Tokens"));
            set(inventory, CANCEL_SLOT, clayButton((short) 14, ChatColor.RED + "Cancel"));
        }

        @Override
        public void onClick(MenuClick click) {
            Player player = click.getPlayer();
            if (player == null) {
                return;
            }
            int slot = click.getRawSlot();
            if (slot == CANCEL_SLOT) {
                new KnifeSkinsMenu(api, localCosts, returnPage, stateService).open(player);
                return;
            }
            if (slot != CONFIRM_SLOT) {
                return;
            }
            if (api == null) {
                player.sendMessage(ChatColor.RED + "Cosmetics are unavailable!");
                return;
            }
            Profile profile = api.getProfile(player.getUniqueId());
            if (profile == null) {
                player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
                return;
            }
            Set<String> unlocked = normalizeIdSet(profile.getUnlocked().get(CosmeticType.KNIFE));
            if (!unlocked.contains(knifeId) && !MurderMysteryStats.spendTokens(api, player.getUniqueId(), knifeCost)) {
                int available = Math.max(0, MurderMysteryStats.getTokens(profile.getStats()));
                int needed = Math.max(0, knifeCost - available);
                player.closeInventory();
                sendNeedMoreTokensMessage(player, needed, knifeName);
                return;
            }
            if (!unlocked.contains(knifeId)) {
                api.unlockCosmetic(player.getUniqueId(), CosmeticType.KNIFE, knifeId);
                sendPurchasedMessage(player, knifeName);
            }
            if (stateService != null) {
                stateService.syncPlayer(player);
            }
            new KnifeSkinsMenu(api, localCosts, returnPage, stateService).open(player);
        }

        private ItemStack clayButton(short colorData, String name, String... lore) {
            Material terracotta = Material.matchMaterial("STAINED_CLAY");
            Material modern = colorData == 13 ? Material.matchMaterial("GREEN_TERRACOTTA") : Material.matchMaterial("RED_TERRACOTTA");
            Material material = modern != null ? modern : (terracotta == null ? Material.WOOL : terracotta);
            ItemStack stack = item(material, name, lore);
            if (terracotta != null && stack != null && stack.getType() == terracotta) {
                stack.setDurability(colorData);
            }
            return stack;
        }
    }

    private enum EntryType {
        STANDARD,
        RANDOM,
        RANDOM_FAVORITE
    }

    private enum SortMode {
        A_Z("A-Z", "Sorts by name: A-Z"),
        Z_A("Z-A", "Sorts by name: Z-A"),
        LOWEST_RARITY_FIRST("Lowest rarity first", "Sorts by rarity: Lowest rarity first"),
        HIGHEST_RARITY_FIRST("Highest rarity first", "Sorts by rarity: Highest rarity first");

        private final String label;
        private final String description;

        SortMode(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() {
            return label;
        }

        public String description() {
            return description;
        }

        public SortMode next() {
            switch (this) {
                case A_Z:
                    return Z_A;
                case Z_A:
                    return LOWEST_RARITY_FIRST;
                case LOWEST_RARITY_FIRST:
                    return HIGHEST_RARITY_FIRST;
                case HIGHEST_RARITY_FIRST:
                default:
                    return A_Z;
            }
        }
    }
}
