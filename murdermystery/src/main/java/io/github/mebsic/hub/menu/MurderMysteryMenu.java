package io.github.mebsic.hub.menu;

import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.menu.MenuClick;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.text.NumberFormat;
import java.util.Locale;

public class MurderMysteryMenu extends Menu {
    public static final String TITLE = "Murder Mystery Menu";
    private static final int SIZE = 36;
    private static final int MY_COSMETICS_SLOT = 13;
    private static final int CLOSE_SLOT = 31;
    private static final int TOKENS_SLOT = 32;

    private final CoreApi coreApi;
    private final KnifeMenu cosmeticsMenu;
    private final NumberFormat numberFormat;

    public MurderMysteryMenu(CoreApi coreApi, KnifeMenu cosmeticsMenu) {
        super(TITLE, SIZE);
        this.coreApi = coreApi;
        this.cosmeticsMenu = cosmeticsMenu;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();

        set(inventory, MY_COSMETICS_SLOT, item(
                resolveArmorStandMaterial(),
                ChatColor.GREEN + "My Cosmetics",
                ChatColor.GRAY + "Browse your unlocked Murder",
                ChatColor.GRAY + "Mystery cosmetics, or buy them",
                ChatColor.GRAY + "directly with Tokens.",
                "",
                ChatColor.YELLOW + "Click to browse!"
        ));
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
        set(inventory, TOKENS_SLOT, item(
                Material.EMERALD,
                ChatColor.GRAY + "Total Tokens: " + ChatColor.DARK_GREEN + formatTokens(player),
                ChatColor.GOLD + "https://store." + NetworkConstants.DOMAIN
        ));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null) {
            return;
        }
        Player player = click.getPlayer();
        if (player == null) {
            return;
        }
        int slot = click.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == MY_COSMETICS_SLOT && cosmeticsMenu != null) {
            cosmeticsMenu.open(player);
        }
    }

    private String formatTokens(Player player) {
        if (player == null || coreApi == null) {
            return "0";
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        int tokens = profile == null ? 0 : MurderMysteryStats.getTokens(profile.getStats());
        return numberFormat.format(Math.max(0, tokens));
    }

    private Material resolveArmorStandMaterial() {
        Material armorStand = Material.matchMaterial("ARMOR_STAND");
        return armorStand == null ? Material.PAPER : armorStand;
    }
}
