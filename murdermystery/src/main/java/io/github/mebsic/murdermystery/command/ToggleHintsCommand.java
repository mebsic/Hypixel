package io.github.mebsic.murdermystery.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.murdermystery.service.TipService;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ToggleHintsCommand implements CommandExecutor {
    private final CorePlugin corePlugin;
    private final TipService tipService;

    public ToggleHintsCommand(CorePlugin corePlugin, TipService tipService) {
        this.corePlugin = corePlugin;
        this.tipService = tipService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }
        Player player = (Player) sender;
        if (corePlugin == null) {
            return true;
        }
        Profile profile = corePlugin.getProfile(player.getUniqueId());
        if (profile == null || profile.getStats() == null) {
            player.sendMessage(ChatColor.RED + "Your Profile is not loaded yet!");
            return true;
        }

        boolean nextEnabled = !MurderMysteryStats.areHintsEnabled(profile.getStats());
        boolean changed = MurderMysteryStats.setHintsEnabled(profile.getStats(), nextEnabled);
        if (changed) {
            corePlugin.saveProfile(profile);
        }

        if (nextEnabled) {
            player.sendMessage(ChatColor.GREEN + "Enabled game hints!");
        } else {
            player.sendMessage(ChatColor.RED + "Disabled game hints!");
        }

        if (tipService != null) {
            tipService.handleHintsToggled(player, nextEnabled);
        }
        return true;
    }
}
