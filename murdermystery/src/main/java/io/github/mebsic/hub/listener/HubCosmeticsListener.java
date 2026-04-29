package io.github.mebsic.hub.listener;

import io.github.mebsic.core.menu.Menu;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Locale;
import java.text.NumberFormat;
import java.util.UUID;
import java.util.function.Predicate;

public class HubCosmeticsListener implements Listener {
    private static final String MENU_ITEM_NAME = ChatColor.GREEN + "Murder Mystery Menu " + ChatColor.GRAY + "(Right Click)";
    private static final int MENU_ITEM_SLOT = 2;
    private final Menu menu;
    private final CoreApi coreApi;
    private final NumberFormat numberFormat;
    private final Predicate<UUID> menuItemSuppressed;

    public HubCosmeticsListener(Menu menu, CoreApi coreApi) {
        this(menu, coreApi, null);
    }

    public HubCosmeticsListener(Menu menu, CoreApi coreApi, Predicate<UUID> menuItemSuppressed) {
        this.menu = menu;
        this.coreApi = coreApi;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.menuItemSuppressed = menuItemSuppressed;
    }

    public void giveMenuItem(Player player) {
        if (player == null || isMenuItemSuppressed(player)) {
            return;
        }
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MENU_ITEM_NAME);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Tokens: " + ChatColor.DARK_GREEN + formatTokens(player)
            ));
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(MENU_ITEM_SLOT, item);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.EMERALD || !item.hasItemMeta()) {
            return;
        }
        String name = item.getItemMeta().getDisplayName();
        if (!MENU_ITEM_NAME.equals(name)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (isMenuItemSuppressed(player)) {
            player.getInventory().setItem(MENU_ITEM_SLOT, null);
            player.updateInventory();
            return;
        }
        giveMenuItem(player);
        menu.open(player);
    }

    private boolean isMenuItemSuppressed(Player player) {
        return player != null
                && menuItemSuppressed != null
                && menuItemSuppressed.test(player.getUniqueId());
    }

    private String formatTokens(Player player) {
        if (player == null || coreApi == null) {
            return "0";
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        int tokens = profile == null ? 0 : MurderMysteryStats.getTokens(profile.getStats());
        return numberFormat.format(Math.max(0, tokens));
    }
}
