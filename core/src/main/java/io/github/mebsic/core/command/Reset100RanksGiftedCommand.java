package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
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

public class Reset100RanksGiftedCommand implements CommandExecutor {
    private static final String GIFTED_COUNTER_KEY = "ranksGifted";
    private static final int TARGET_GIFTED_AMOUNT = 0;

    private final CorePlugin plugin;

    public Reset100RanksGiftedCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (!RankUtil.hasAtLeast(plugin, player, Rank.STAFF)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }

        Profile profile = plugin.getProfile(player.getUniqueId());
        if (profile == null || profile.getStats() == null) {
            player.sendMessage(ChatColor.RED + "Your profile is still loading! Try again in a moment.");
            return true;
        }

        int current = Math.max(0, profile.getStats().getCustomCounter(GIFTED_COUNTER_KEY));
        if (current != TARGET_GIFTED_AMOUNT) {
            profile.getStats().addCustomCounter(GIFTED_COUNTER_KEY, TARGET_GIFTED_AMOUNT - current);
            plugin.saveProfile(profile);
            ProfileCommandSyncService sync = plugin.getProfileCommandSyncService();
            if (sync != null) {
                sync.dispatchCounterSet(player.getUniqueId(), GIFTED_COUNTER_KEY, TARGET_GIFTED_AMOUNT, null, null);
            }
        }

        player.sendMessage(ChatColor.GREEN + "Done!");
        return true;
    }
}
