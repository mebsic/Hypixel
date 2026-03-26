package io.github.mebsic.core.menu;

import io.github.mebsic.core.CorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GiftMenu extends Menu {
    private static final String TITLE = "Send a Gift";
    private static final int SIZE = 36;
    private static final int GIFT_SLOT = 13;
    private static final int CLOSE_SLOT = 31;

    private final CorePlugin plugin;
    private final UUID targetUuid;
    private final String targetName;

    public GiftMenu(CorePlugin plugin, Player target) {
        super(TITLE, SIZE);
        this.plugin = plugin;
        this.targetUuid = target == null ? null : target.getUniqueId();
        this.targetName = target == null ? "" : target.getName();
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, GIFT_SLOT, buildGiftItem(resolveTargetDisplayName()));
        set(inventory, CLOSE_SLOT, item(Material.BARRIER, ChatColor.RED + "Close"));
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player player = click.getPlayer();
        if (click.getRawSlot() == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (click.getRawSlot() != GIFT_SLOT) {
            return;
        }
        Player target = targetUuid == null ? null : Bukkit.getPlayer(targetUuid);
        if (targetUuid == null) {
            player.sendMessage(ChatColor.RED + "Unknown player '" + targetName + "'!");
            player.closeInventory();
            return;
        }
        if (target == null || !target.isOnline()) {
            player.sendMessage(ChatColor.RED + "That player is not in your lobby!");
            player.closeInventory();
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You can't send gifts to yourself!");
            player.closeInventory();
            return;
        }
        new GiftSelectMenu(plugin, target).open(player);
    }

    private org.bukkit.inventory.ItemStack buildGiftItem(String targetDisplayName) {
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Gift a rank to " + targetDisplayName + ChatColor.GRAY + ".");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to gift!");
        return GiftSupport.createHeadItem(
                GiftSupport.PRESENT_HEAD_OWNER,
                ChatColor.GREEN + "Gift a Rank",
                lore
        );
    }

    private String resolveTargetDisplayName() {
        String fallback = GiftSupport.safeString(targetName);
        if (fallback.isEmpty()) {
            fallback = "Player";
        }
        if (plugin == null || targetUuid == null) {
            return ChatColor.WHITE + fallback;
        }
        return GiftSupport.buildTargetDisplayName(plugin.getProfile(targetUuid), fallback);
    }
}
