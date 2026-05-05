package io.github.mebsic.core.menu;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.RankColorUtil;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RankColorMenu extends Menu {
    public static final String TITLE = "Rank Color";
    private static final int SIZE = 45;
    private static final int CLOSE_SLOT = 40;
    private static final int TOGGLE_SLOT = 44;
    private static final String CLOSE_NAME = ChatColor.RED + "Close";
    private static final String TOGGLE_NAME = ChatColor.GREEN + "Toggle Prefix Color";
    private static final int GIFTED_RANKS_REQUIRED = 100;
    private static final int[] COLOR_SLOTS = new int[] {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };
    private final CoreApi coreApi;

    public RankColorMenu(CoreApi coreApi) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (coreApi == null || player == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            return;
        }
        int level = profile.getNetworkLevel();
        int giftedRanks = readGiftedRanks(profile);
        boolean giftedRewardUnlocked = isGiftedRewardUnlocked(giftedRanks);
        String selected = getEffectiveSelectedColorId(level, profile.getPlusColor(), giftedRewardUnlocked);
        List<RankColorUtil.PlusColor> colors = RankColorUtil.getAllPlusColors();
        for (int i = 0; i < colors.size() && i < COLOR_SLOTS.length; i++) {
            RankColorUtil.PlusColor color = colors.get(i);
            boolean active = color.getId().equalsIgnoreCase(selected);
            boolean unlocked = active || RankColorUtil.isPlusColorUnlocked(color, level, giftedRewardUnlocked);
            List<String> lore = buildLore(color, unlocked, active);
            ItemStack item = dyeItem(color.getColor(), colorTitle(color, unlocked), lore);
            set(inventory, COLOR_SLOTS[i], item);
        }
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, CLOSE_NAME));
        Rank rank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (canUsePrefixColorToggle(rank)) {
            set(inventory, TOGGLE_SLOT, buildToggleItem(profile.getMvpPlusPlusPrefixColor()));
        }
    }

    @Override
    public void onClick(MenuClick click) {
        Player player = click.getPlayer();
        if (player == null) {
            return;
        }
        if (click.getRawSlot() == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (coreApi == null) {
            return;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            return;
        }
        Rank rank = profile.getRank();
        if (!rank.isAtLeast(Rank.MVP_PLUS)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return;
        }
        if (click.getRawSlot() == TOGGLE_SLOT) {
            if (!canUsePrefixColorToggle(rank)) {
                return;
            }
            String selectedId = RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(profile.getMvpPlusPlusPrefixColor());
            String nextId = togglePrefixColorId(selectedId);
            coreApi.setMvpPlusPlusPrefixColor(player.getUniqueId(), nextId);
            sendPrefixColorMessage(player, nextId);
            open(player);
            return;
        }
        RankColorUtil.PlusColor color = resolveColor(click.getRawSlot());
        if (color == null) {
            return;
        }
        int level = profile.getNetworkLevel();
        int giftedRanks = readGiftedRanks(profile);
        boolean giftedRewardUnlocked = isGiftedRewardUnlocked(giftedRanks);
        String selectedId = getEffectiveSelectedColorId(level, profile.getPlusColor(), giftedRewardUnlocked);
        if (color.getId().equalsIgnoreCase(selectedId)) {
            sendAlreadySelectedMessage(player);
            return;
        }
        if (!RankColorUtil.isPlusColorUnlocked(color, level, giftedRewardUnlocked)) {
            player.sendMessage(ChatColor.RED + "You haven't unlocked (or claimed) that yet!");
            return;
        }
        coreApi.setPlusColor(player.getUniqueId(), color.getId());
        sendSelectionMessage(player);
        open(player);
    }

    @Override
    public boolean isAllowedClick(MenuClick click) {
        return super.isAllowedClick(click);
    }

    private RankColorUtil.PlusColor resolveColor(int rawSlot) {
        List<RankColorUtil.PlusColor> colors = RankColorUtil.getAllPlusColors();
        for (int i = 0; i < COLOR_SLOTS.length && i < colors.size(); i++) {
            if (rawSlot == COLOR_SLOTS[i]) {
                return colors.get(i);
            }
        }
        return null;
    }

    private ItemStack dyeItem(ChatColor color, String name, List<String> lore) {
        Material modern = matchModernDye(color);
        if (modern != null) {
            return item(modern, name, lore);
        }
        Material legacy = matchLegacyDye();
        if (legacy == null) {
            return item(Material.PAPER, name, lore);
        }
        short data = legacyDyeData(color);
        ItemStack stack = new ItemStack(legacy, 1, data);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<String> buildLore(RankColorUtil.PlusColor color, boolean unlocked, boolean active) {
        List<String> lore = new ArrayList<>();
        if (isDefaultRed(color)) {
            lore.add(ChatColor.GRAY + "The default color for " + formatMvpPlus(ChatColor.RED));
            lore.add("");
        } else {
            lore.add(ChatColor.GRAY + "Changes the color of the plus in " + formatMvpPlus(ChatColor.RED));
            lore.add(ChatColor.GRAY + "to " + colorDisplayLower(color)
                    + ChatColor.GRAY + ", turning it into " + formatMvpPlus(color.getColor()));
            lore.add("");
            lore.add(ChatColor.GRAY + "Shown in tab list also when chatting");
            lore.add(ChatColor.GRAY + "and joining lobbies.");
            lore.add("");
        }
        if (active) {
            lore.add(ChatColor.GREEN + "Currently selected!");
        } else if (unlocked) {
            lore.add(ChatColor.YELLOW + "Click to select!");
        } else if (isGiftedRewardColor(color)) {
            lore.add(ChatColor.GOLD + "Unlock by claiming 100 Ranks Gifted Reward!");
        } else {
            lore.add(ChatColor.DARK_AQUA + "Unlocked at Hycopy Level " + color.getLevel());
        }
        return lore;
    }

    private ItemStack buildToggleItem(String selectedPrefixColorId) {
        String selectedId = RankColorUtil.getEffectiveMvpPlusPlusPrefixColorId(selectedPrefixColorId);
        String nextId = togglePrefixColorId(selectedId);
        ChatColor selectedColor = RankColorUtil.getMvpPlusPlusPrefixColor(selectedId);
        ChatColor nextColor = RankColorUtil.getMvpPlusPlusPrefixColor(nextId);
        String selectedName = RankColorUtil.getMvpPlusPlusPrefixColorDisplayName(selectedId);
        String nextName = RankColorUtil.getMvpPlusPlusPrefixColorDisplayName(nextId);
        Material star = Material.matchMaterial("NETHER_STAR");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Selected: " + selectedColor + selectedName);
        lore.add("");
        lore.add(ChatColor.GRAY + "Click to change the color to " + nextColor + nextName);
        return item(star == null ? Material.PAPER : star, TOGGLE_NAME, lore);
    }

    private String togglePrefixColorId(String selectedId) {
        if (RankColorUtil.MVP_PLUS_PLUS_PREFIX_AQUA.equalsIgnoreCase(selectedId)) {
            return RankColorUtil.MVP_PLUS_PLUS_PREFIX_GOLD;
        }
        return RankColorUtil.MVP_PLUS_PLUS_PREFIX_AQUA;
    }

    private String colorTitle(RankColorUtil.PlusColor color, boolean unlocked) {
        ChatColor titleColor = unlocked ? ChatColor.GREEN : ChatColor.RED;
        return titleColor + color.getDisplayName() + " Rank Color";
    }

    private String colorDisplayLower(RankColorUtil.PlusColor color) {
        if (color == null || color.getDisplayName() == null) {
            return "red";
        }
        return color.getDisplayName().toLowerCase(Locale.ROOT);
    }

    private boolean isDefaultRed(RankColorUtil.PlusColor color) {
        return color != null && "red".equalsIgnoreCase(color.getId());
    }

    private boolean isGiftedRewardColor(RankColorUtil.PlusColor color) {
        return color != null && color.isGiftedReward();
    }

    private int readGiftedRanks(Profile profile) {
        return Math.max(0, GiftSupport.readGiftedRanks(profile));
    }

    private boolean isGiftedRewardUnlocked(int giftedRanks) {
        return Math.max(0, giftedRanks) >= GIFTED_RANKS_REQUIRED;
    }

    private String getEffectiveSelectedColorId(int networkLevel, String selectedId, boolean giftedRewardUnlocked) {
        RankColorUtil.PlusColor selected = RankColorUtil.getPlusColorById(selectedId);
        if (selected != null && RankColorUtil.isPlusColorUnlocked(selected, networkLevel, giftedRewardUnlocked)) {
            return selected.getId();
        }
        return RankColorUtil.getEffectivePlusColorId(networkLevel, null);
    }

    private boolean canUsePrefixColorToggle(Rank rank) {
        return rank == Rank.MVP_PLUS_PLUS || rank == Rank.STAFF || rank == Rank.YOUTUBE;
    }

    private void sendSelectionMessage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.GREEN + "Selected!");
    }

    private void sendAlreadySelectedMessage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.RED + "You already have that selected!");
    }

    private void sendPrefixColorMessage(Player player, String prefixColorId) {
        if (player == null) {
            return;
        }
        ChatColor color = RankColorUtil.getMvpPlusPlusPrefixColor(prefixColorId);
        String name = RankColorUtil.getMvpPlusPlusPrefixColorDisplayName(prefixColorId);
        player.sendMessage(ChatColor.GREEN + "Changed your prefix color to "
                + color + name + ChatColor.GREEN + "!");
    }

    private String formatMvpPlus(ChatColor plusColor) {
        ChatColor safe = plusColor == null ? ChatColor.RED : plusColor;
        return ChatColor.AQUA + "MVP" + safe + "+";
    }

    private Material matchModernDye(ChatColor color) {
        String name = modernDyeName(color);
        if (name == null) {
            return null;
        }
        return Material.matchMaterial(name);
    }

    private String modernDyeName(ChatColor color) {
        if (color == null) {
            return null;
        }
        switch (color) {
            case RED:
                return "RED_DYE";
            case GOLD:
                return "ORANGE_DYE";
            case GREEN:
                return "LIME_DYE";
            case YELLOW:
                return "YELLOW_DYE";
            case LIGHT_PURPLE:
                return "MAGENTA_DYE";
            case WHITE:
                return "WHITE_DYE";
            case BLUE:
                return "LIGHT_BLUE_DYE";
            case DARK_AQUA:
                return "CYAN_DYE";
            case DARK_BLUE:
                return "BLUE_DYE";
            case DARK_GREEN:
                return "GREEN_DYE";
            case DARK_RED:
                return "RED_DYE";
            case DARK_PURPLE:
                return "PURPLE_DYE";
            case DARK_GRAY:
                return "GRAY_DYE";
            case BLACK:
                return "BLACK_DYE";
            default:
                return null;
        }
    }

    private Material matchLegacyDye() {
        try {
            return Material.valueOf("INK_SACK");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private short legacyDyeData(ChatColor color) {
        if (color == null) {
            return 1;
        }
        switch (color) {
            case RED:
                return 1;
            case GOLD:
                return 14;
            case GREEN:
                return 10;
            case YELLOW:
                return 11;
            case LIGHT_PURPLE:
                return 13;
            case WHITE:
                return 15;
            case BLUE:
                return 12;
            case DARK_AQUA:
                return 6;
            case DARK_BLUE:
                return 4;
            case DARK_GREEN:
                return 2;
            case DARK_RED:
                return 1;
            case DARK_PURPLE:
                return 5;
            case DARK_GRAY:
                return 8;
            case BLACK:
                return 0;
            default:
                return 1;
        }
    }
}
