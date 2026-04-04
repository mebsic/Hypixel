package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.ProfileCommandSyncService;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Unlock100RanksGiftedCommand implements CommandExecutor {
    private static final int TARGET_GIFTED_AMOUNT = 100;

    private final CorePlugin plugin;

    public Unlock100RanksGiftedCommand(CorePlugin plugin) {
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

        Profile profile = plugin.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
            return true;
        }

        int current = Math.max(0, profile.getRanksGifted());
        if (current != TARGET_GIFTED_AMOUNT) {
            profile.setRanksGifted(TARGET_GIFTED_AMOUNT);
            plugin.saveProfile(profile);
            ProfileCommandSyncService sync = plugin.getProfileCommandSyncService();
            if (sync != null) {
                sync.dispatchCounterSet(player.getUniqueId(), MongoManager.PROFILE_RANKS_GIFTED_KEY, TARGET_GIFTED_AMOUNT, null, null);
            }
        }

        player.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }
}
