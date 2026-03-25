package io.github.mebsic.core.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class FlyCommand implements CommandExecutor {
    private final CorePlugin plugin;

    public FlyCommand(CorePlugin plugin) {
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
        Profile profile = plugin.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + "Your profile is still loading. Try again in a moment.");
            return true;
        }
        Rank rank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (!rank.isAtLeast(Rank.VIP)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }
        boolean enabled = !profile.isFlightEnabled();
        plugin.setFlightEnabled(player.getUniqueId(), enabled);
        if (enabled) {
            player.sendMessage(ChatColor.GREEN + "Toggled on flight!");
        } else {
            player.sendMessage(ChatColor.GREEN + "Toggled off flight!");
        }
        return true;
    }
}
