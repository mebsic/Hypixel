package io.github.mebsic.murdermystery.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.murdermystery.service.ActionBarService;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ToggleHintsCommand implements CommandExecutor {
    private final CorePlugin corePlugin;
    private final ActionBarService actionBarService;

    public ToggleHintsCommand(CorePlugin corePlugin, ActionBarService actionBarService) {
        this.corePlugin = corePlugin;
        this.actionBarService = actionBarService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + CommonMessages.ONLY_PLAYERS_COMMAND);
            return true;
        }
        Player player = (Player) sender;
        if (corePlugin == null) {
            return true;
        }
        Profile profile = corePlugin.getProfile(player.getUniqueId());
        if (profile == null || profile.getStats() == null) {
            player.sendMessage(ChatColor.RED + CommonMessages.PROFILE_LOADING);
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

        if (actionBarService != null) {
            actionBarService.handleHintsToggled(player, nextEnabled);
        }
        return true;
    }
}
