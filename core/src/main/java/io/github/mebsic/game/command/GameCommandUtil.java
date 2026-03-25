package io.github.mebsic.game.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class GameCommandUtil {
    private GameCommandUtil() {
    }

    public static Player requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return null;
        }
        return (Player) sender;
    }

    public static boolean requireRank(CommandSender sender, CoreApi coreApi, Rank rank) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        if (!RankUtil.hasAtLeast(coreApi, player, rank)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return false;
        }
        return true;
    }
}
