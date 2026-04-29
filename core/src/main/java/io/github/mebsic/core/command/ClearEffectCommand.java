package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ClearEffectCommand implements CommandExecutor {
    private static final int MAX_CLEAR_PASSES = 16;

    private final CorePlugin plugin;

    public ClearEffectCommand(CorePlugin plugin) {
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

        if (args.length != 1) {
            sendUsage(sender, label);
            return true;
        }

        List<Player> targets = resolveTargets(sender, args[0]);
        if (targets == null) {
            return true;
        }

        if (!args[0].equalsIgnoreCase("all") && targets.size() == 1 && !hasEffects(targets.get(0))) {
            sender.sendMessage(ChatColor.RED + "That player does not have any effects!");
            return true;
        }

        boolean failed = false;
        for (Player target : targets) {
            try {
                clearAllEffects(target);
            } catch (RuntimeException ignored) {
                failed = true;
            }
        }

        sender.sendMessage(failed ? ChatColor.RED + "Failed!" : ChatColor.GREEN + CommonMessages.DONE);
        return true;
    }

    private List<Player> resolveTargets(CommandSender sender, String rawTarget) {
        if (rawTarget == null) {
            return null;
        }
        if (rawTarget.equalsIgnoreCase("all")) {
            return new ArrayList<Player>(Bukkit.getOnlinePlayers());
        }
        Player target = Bukkit.getPlayerExact(rawTarget);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + CommonMessages.PLAYER_NOT_FOUND_COMMAND);
            return null;
        }
        List<Player> targets = new ArrayList<Player>();
        targets.add(target);
        return targets;
    }

    private boolean hasEffects(Player target) {
        return target != null && !target.getActivePotionEffects().isEmpty();
    }

    private void clearAllEffects(Player target) {
        for (int pass = 0; pass < MAX_CLEAR_PASSES; pass++) {
            Collection<PotionEffect> effects = new ArrayList<PotionEffect>(target.getActivePotionEffects());
            if (effects.isEmpty()) {
                return;
            }
            for (PotionEffect effect : effects) {
                if (effect != null && effect.getType() != null) {
                    target.removePotionEffect(effect.getType());
                }
            }
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        sender.sendMessage(ChatColor.RED + "/" + label + " <player/all>");
    }
}
