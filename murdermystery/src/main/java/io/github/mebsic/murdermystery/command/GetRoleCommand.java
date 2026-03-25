package io.github.mebsic.murdermystery.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.RankFormatUtil;
import io.github.mebsic.core.util.RankUtil;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.murdermystery.game.MurderMysteryGamePlayer;
import io.github.mebsic.murdermystery.game.MurderMysteryRole;
import io.github.mebsic.murdermystery.manager.MurderMysteryGameManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class GetRoleCommand implements CommandExecutor {
    private final CorePlugin corePlugin;
    private final MurderMysteryGameManager gameManager;

    public GetRoleCommand(CorePlugin corePlugin, MurderMysteryGameManager gameManager) {
        this.corePlugin = corePlugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!RankUtil.hasAtLeast(corePlugin, player, Rank.STAFF)) {
                player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
                return true;
            }
        }
        if (gameManager == null) {
            sender.sendMessage(ChatColor.RED + "This command is only available on Murder Mystery games!");
            return true;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            sender.sendMessage(ChatColor.RED + "The game has not started yet!");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /getrole <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online!");
            return true;
        }

        MurderMysteryGamePlayer mmPlayer = gameManager.getMurderMysteryPlayer(target);
        if (mmPlayer == null) {
            sender.sendMessage(ChatColor.RED + "That player is not in this game!");
            return true;
        }
        sender.sendMessage(buildRoleSentence(mmPlayer));
        return true;
    }

    private String buildRoleSentence(MurderMysteryGamePlayer mmPlayer) {
        String name = resolvePlayerName(mmPlayer);
        String rankedName = formatRankedName(mmPlayer, name);
        if (!mmPlayer.isAlive()) {
            return rankedName + ChatColor.RED + " has died!";
        }
        if (mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
            return rankedName + ChatColor.GREEN + " is the " + ChatColor.RED + "Murderer" + ChatColor.GREEN + "!";
        }
        if (mmPlayer.getRole() == MurderMysteryRole.DETECTIVE
                || (mmPlayer.getRole() == MurderMysteryRole.HERO && mmPlayer.hasDetectiveBow())) {
            return rankedName + ChatColor.GREEN + " is the " + ChatColor.AQUA + "Detective" + ChatColor.GREEN + "!";
        }
        return rankedName + ChatColor.GREEN + " is Innocent!";
    }

    private String formatRankedName(MurderMysteryGamePlayer mmPlayer, String fallbackName) {
        if (corePlugin == null || mmPlayer == null) {
            return ChatColor.GRAY + fallbackName;
        }
        Profile profile = corePlugin.getProfile(mmPlayer.getUuid());
        Rank rank = profile == null || profile.getRank() == null
                ? corePlugin.getRank(mmPlayer.getUuid())
                : profile.getRank();
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        int networkLevel = profile == null ? 0 : Math.max(0, profile.getNetworkLevel());
        String plusColor = profile == null ? null : profile.getPlusColor();
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        String prefix = RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
        ChatColor nameColor = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
        return prefix + nameColor + fallbackName;
    }

    private String resolvePlayerName(MurderMysteryGamePlayer mmPlayer) {
        Player online = Bukkit.getPlayer(mmPlayer.getUuid());
        if (online != null && online.getName() != null && !online.getName().trim().isEmpty()) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(mmPlayer.getUuid());
        if (offline != null && offline.getName() != null && !offline.getName().trim().isEmpty()) {
            return offline.getName();
        }
        return mmPlayer.getUuid().toString().substring(0, 8);
    }
}
