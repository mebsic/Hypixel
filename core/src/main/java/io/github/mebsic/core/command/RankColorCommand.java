package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.menu.RankColorMenu;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RankColorCommand implements CommandExecutor {
    private final CorePlugin plugin;
    private final RankColorMenu menu;

    public RankColorCommand(CorePlugin plugin) {
        this.plugin = plugin;
        this.menu = new RankColorMenu(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        ServerType serverType = plugin.getServerType();
        if (serverType == null || !serverType.isHub()) {
            player.sendMessage(ChatColor.RED + "This is only available in lobbies!");
            return true;
        }
        if (!RankUtil.hasAtLeast(plugin, player, Rank.MVP_PLUS)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }
        menu.open(player);
        return true;
    }
}
