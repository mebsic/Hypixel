package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.service.HubParkourCommandHandler;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ParkourCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public ParkourCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        if (plugin.getServerType() == null || !plugin.getServerType().isHub()) {
            return true;
        }
        HubParkourCommandHandler handler = plugin.getHubParkourCommandHandler();
        if (handler == null) {
            player.sendMessage(ChatColor.RED + "There is no parkour challenge available!");
            return true;
        }
        handler.handleParkourCommand(player, args == null ? new String[0] : args);
        return true;
    }
}
