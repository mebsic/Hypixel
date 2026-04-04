package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

public class GiveCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public GiveCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + CommonMessages.ONLY_PLAYERS_COMMAND);
            return true;
        }
        Player player = (Player) sender;
        if (!RankUtil.hasAtLeast(plugin, player, Rank.STAFF)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
            sender.sendMessage(ChatColor.RED + "/" + label + " [onlinePlayer] <item> <quantity>");
            return true;
        }

        Player target;
        String rawItem;
        String rawQuantity;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + CommonMessages.PLAYER_NOT_FOUND_COMMAND);
                return true;
            }
            rawItem = args[1];
            rawQuantity = args[2];
        } else {
            target = player;
            rawItem = args[0];
            rawQuantity = args[1];
        }

        ResolvedMaterial resolved = resolveMaterial(rawItem);
        if (resolved == null || resolved.material == null || resolved.material == Material.AIR) {
            sender.sendMessage(ChatColor.RED + "Unknown item! Try again.");
            return true;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(rawQuantity);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Quantity must be a positive number!");
            return true;
        }
        if (quantity <= 0) {
            sender.sendMessage(ChatColor.RED + "Quantity must be a positive number!");
            return true;
        }

        if (isInventoryFull(target, resolved.material)) {
            sender.sendMessage(ChatColor.RED + fullInventoryMessage(player, target));
            return true;
        }

        giveItem(target, resolved, quantity);

        String itemName = resolved.inputName;
        if (sender.equals(target)) {
            sender.sendMessage(ChatColor.GREEN + "Gave you " + quantity + " " + ChatColor.YELLOW + itemName + ChatColor.GREEN + "!");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + target.getName() + " " + quantity + " " + ChatColor.YELLOW + itemName + ChatColor.GREEN + "!");
        return true;
    }

    private void giveItem(Player target, ResolvedMaterial resolved, int quantity) {
        if (target == null || resolved == null || resolved.material == null || quantity <= 0) {
            return;
        }
        int remaining = quantity;
        int maxStack = Math.max(1, new ItemStack(resolved.material, 1).getMaxStackSize());
        while (remaining > 0) {
            int stackAmount = Math.min(maxStack, remaining);
            ItemStack stack = new ItemStack(resolved.material, stackAmount);
            java.util.Map<Integer, ItemStack> leftovers = target.getInventory().addItem(stack);
            if (leftovers != null && !leftovers.isEmpty()) {
                for (ItemStack leftover : leftovers.values()) {
                    if (leftover == null || leftover.getType() == Material.AIR || leftover.getAmount() <= 0) {
                        continue;
                    }
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                }
            }
            remaining -= stackAmount;
        }
        target.updateInventory();
    }

    private boolean isInventoryFull(Player target, Material material) {
        if (target == null || material == null) {
            return true;
        }
        ItemStack[] contents = target.getInventory().getContents();
        for (ItemStack content : contents) {
            if (content == null || content.getType() == Material.AIR) {
                return false;
            }
            if (content.getType() == material && content.getAmount() < content.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private String fullInventoryMessage(Player sender, Player target) {
        if (sender != null && sender.equals(target)) {
            return "Your inventory is full!";
        }
        String name = target == null ? "Player" : target.getName();
        if (name != null && !name.isEmpty() && name.toLowerCase(Locale.ROOT).endsWith("s")) {
            return name + " inventory is full!";
        }
        return name + "'s inventory is full!";
    }

    private ResolvedMaterial resolveMaterial(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.matches("^[0-9]+$")) {
            return null;
        }
        Material direct;
        try {
            direct = Material.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            direct = Material.matchMaterial(normalized);
        }
        if (direct != null) {
            return new ResolvedMaterial(direct, normalized);
        }
        if ("GRASS_BLOCK".equals(normalized)) {
            Material grass = Material.matchMaterial("GRASS");
            if (grass != null) {
                return new ResolvedMaterial(grass, normalized);
            }
        }
        return null;
    }

    private static final class ResolvedMaterial {
        private final Material material;
        private final String inputName;

        private ResolvedMaterial(Material material, String inputName) {
            this.material = material;
            this.inputName = inputName;
        }
    }
}
