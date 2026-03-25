package io.github.mebsic.murdermystery.command;

import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.ProfileCommandSyncService;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.core.util.RankUtil;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class TokenCommand implements CommandExecutor {
    private static final String LEGACY_TOKENS = "tokens";

    private final CorePlugin corePlugin;

    public TokenCommand(CorePlugin corePlugin) {
        this.corePlugin = corePlugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (corePlugin == null) {
            return true;
        }
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (!RankUtil.hasAtLeast(corePlugin, player, Rank.STAFF)) {
                player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
                return true;
            }
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
            sender.sendMessage(ChatColor.RED + "/token <player> <amount>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        UUID uuid = target != null ? target.getUniqueId() : MojangApi.lookupUuid(args[0]);
        String name = target != null ? target.getName() : args[0];
        if (uuid == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(ChatColor.RED + "Token amount must be a number.");
            return true;
        }
        if (amount < 0) {
            sender.sendMessage(ChatColor.RED + "Token amount must be 0 or higher.");
            return true;
        }

        if (target != null) {
            Profile profile = corePlugin.getProfile(uuid);
            if (profile == null) {
                sender.sendMessage(ChatColor.RED + "Profile is not loaded yet. Try again in a moment.");
                return true;
            }
            int current = MurderMysteryStats.getTokens(profile.getStats());
            if (current != amount) {
                profile.getStats().addCustomCounter(MurderMysteryStats.TOKENS, amount - current);
            }
            int legacy = profile.getStats().getCustomCounter(LEGACY_TOKENS);
            if (legacy > 0) {
                profile.getStats().addCustomCounter(LEGACY_TOKENS, -legacy);
            }
            corePlugin.saveProfile(profile);
        } else {
            if (!corePlugin.isMongoEnabled() || corePlugin.getProfileStore() == null) {
                sender.sendMessage(ChatColor.RED + "MongoDB is not enabled.");
                return true;
            }
            Profile profile = corePlugin.getProfileStore().load(uuid, name);
            int current = MurderMysteryStats.getTokens(profile.getStats());
            if (current != amount) {
                profile.getStats().addCustomCounter(MurderMysteryStats.TOKENS, amount - current);
            }
            int legacy = profile.getStats().getCustomCounter(LEGACY_TOKENS);
            if (legacy > 0) {
                profile.getStats().addCustomCounter(LEGACY_TOKENS, -legacy);
            }
            corePlugin.getProfileStore().save(profile);
        }

        ProfileCommandSyncService sync = corePlugin.getProfileCommandSyncService();
        if (sync != null) {
            sync.dispatchCounterSet(
                    uuid,
                    MurderMysteryStats.TOKENS,
                    amount,
                    LEGACY_TOKENS,
                    null
            );
        }

        sender.sendMessage(ChatColor.GREEN + "Set Murder Mystery tokens for " + name + " to " + amount + ".");
        return true;
    }
}
