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

public class HubCosmeticsListener implements Listener {
    private static final String MENU_ITEM_NAME = ChatColor.GREEN + "Murder Mystery Menu " + ChatColor.GRAY + "(Right Click)";
    private final Menu menu;
    private final CoreApi coreApi;
    private final NumberFormat numberFormat;

    public HubCosmeticsListener(Menu menu, CoreApi coreApi) {
        this.menu = menu;
        this.coreApi = coreApi;
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
    }

    public void giveMenuItem(Player player) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MENU_ITEM_NAME);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Tokens: " + ChatColor.DARK_GREEN + formatTokens(player)
            ));
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(2, item);
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
        giveMenuItem(player);
        menu.open(player);
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
