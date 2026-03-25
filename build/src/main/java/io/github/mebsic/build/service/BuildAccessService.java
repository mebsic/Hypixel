package io.github.mebsic.build.service;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.CommonMessages;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class BuildAccessService {
    private static final int MAX_STAFF_RESOLUTION_ATTEMPTS = 20;
    private static final long STAFF_RESOLUTION_RETRY_TICKS = 5L;

    private static final Set<String> CORE_COMMANDS = immutableSet(
            "ban", "unban", "mute", "unmute", "kick",
            "rank",
            "gamemode", "gm",
            "give", "clear",
            "teleport", "tp",
            "fly", "help"
    );

    private static final Set<String> ALLOWED_CORE_COMMANDS = immutableSet(
            "ban", "unban", "mute", "unmute", "kick", "rank", "gamemode", "gm",
            "teleport", "tp", "clear", "give"
    );

    private final JavaPlugin plugin;
    private final CorePlugin corePlugin;

    public BuildAccessService(JavaPlugin plugin, CorePlugin corePlugin) {
        this.plugin = plugin;
        this.corePlugin = corePlugin;
    }

    public boolean requireStaff(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return false;
        }
        Player player = (Player) sender;
        if (!isStaff(player)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return false;
        }
        return true;
    }

    public boolean isStaff(Player player) {
        Rank rank = resolveRank(player);
        return rank != null && rank.isAtLeast(Rank.STAFF);
    }

    public Rank resolveRank(Player player) {
        if (player == null || corePlugin == null) {
            return null;
        }
        Profile profile = corePlugin.getProfile(player.getUniqueId());
        if (profile == null) {
            return null;
        }
        return profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
    }

    public void validateStaffAccess(Player player, int attempt) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Profile profile = corePlugin == null ? null : corePlugin.getProfile(player.getUniqueId());
        if (profile == null && attempt < MAX_STAFF_RESOLUTION_ATTEMPTS) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> validateStaffAccess(player, attempt + 1),
                    STAFF_RESOLUTION_RETRY_TICKS
            );
            return;
        }

        Rank rank = profile == null || profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (!rank.isAtLeast(Rank.STAFF)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            player.kickPlayer(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return;
        }

        applyBuildDefaults(player);
    }

    public void applyBuildDefaults(Player player) {
        if (player == null) {
            return;
        }
        if (!player.isOp()) {
            player.setOp(true);
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            player.setGameMode(GameMode.CREATIVE);
        }
        player.setAllowFlight(true);
        if (!player.isFlying()) {
            player.setFlying(true);
        }
    }

    public boolean shouldBlockCoreCommand(String commandLine) {
        String label = commandLabel(commandLine);
        if (label.isEmpty()) {
            return false;
        }
        return CORE_COMMANDS.contains(label) && !ALLOWED_CORE_COMMANDS.contains(label);
    }

    private static String commandLabel(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) {
            return "";
        }
        String withoutSlash = trimmed.substring(1).trim();
        if (withoutSlash.isEmpty()) {
            return "";
        }
        int spaceIndex = withoutSlash.indexOf(' ');
        String label = spaceIndex < 0 ? withoutSlash : withoutSlash.substring(0, spaceIndex);
        int namespaceIndex = label.indexOf(':');
        if (namespaceIndex >= 0 && namespaceIndex + 1 < label.length()) {
            label = label.substring(namespaceIndex + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    private static Set<String> immutableSet(String... values) {
        if (values == null || values.length == 0) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
}
