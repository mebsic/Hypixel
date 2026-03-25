package io.github.mebsic.core.listener;

import io.github.mebsic.core.command.HelpCommand;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.CommonMessages;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class CommandBlockListener implements Listener {
    private static final Map<String, BlockAction> BLOCKED_COMMANDS = new HashMap<String, BlockAction>();
    private static final HelpCommand HELP_COMMAND = new HelpCommand();
    private static final String BUILD_WORLD_NAME = "world";
    private static final String[] BLOCKED_PREFIXES = new String[]{
            "/bukkit",
            "/paper",
            "/spigot"
    };
    private final boolean buildServer;

    static {
        addBlocked(BlockAction.MESSAGE,
                "/minecraft:stop",
                "/minecraft:whitelist",
                "/minecraft:reload",
                "/minecraft:pardon",
                "/minecraft:kick",
                "/minecraft:ban",
                "/pl",
                "/plugins",
                "/me",
                "/?",
                "/whitelist",
                "/stop",
                "/reload",
                "/rl",
                "/op",
                "/deop");

        addBlocked(BlockAction.SILENT,
                "/ban-ip",
                "/pardon-ip",
                "/minecraft:ban-ip",
                "/minecraft:pardon-ip");

    }

    public CommandBlockListener(ServerType serverType) {
        this.buildServer = serverType != null && serverType.isBuild();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message == null) {
            return;
        }
        String normalized = message.trim();
        if (normalized.isEmpty() || !normalized.startsWith("/")) {
            return;
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        String label = commandLabel(lowered);
        String[] args = commandArgs(normalized);
        if ("/help".equals(label)) {
            event.setCancelled(true);
            HELP_COMMAND.onCommand(event.getPlayer(), null, "help", args);
            return;
        }
        if (shouldBlockBuildWorldMv(label, args)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return;
        }
        BlockAction action = BLOCKED_COMMANDS.get(label);
        if (action == BlockAction.SILENT) {
            event.setCancelled(true);
            return;
        }
        if (action == BlockAction.MESSAGE || shouldBlockWithMessage(lowered, label)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
        }
    }

    private boolean shouldBlockWithMessage(String fullCommand, String label) {
        if ("/server".equals(label) || label.endsWith(":server")) {
            return true;
        }
        if (label.startsWith("/minecraft:")) {
            return true;
        }
        if (fullCommand.contains("@") && !buildServer) {
            return true;
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (label.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldBlockBuildWorldMv(String label, String[] args) {
        if (!buildServer) {
            return false;
        }
        String[] effectiveArgs = resolveMvArgs(label, args);
        if (effectiveArgs == null || effectiveArgs.length == 0) {
            return false;
        }
        boolean referencesWorld = false;
        for (String arg : effectiveArgs) {
            if (isWorldArg(arg)) {
                referencesWorld = true;
                break;
            }
        }
        if (!referencesWorld) {
            return false;
        }
        // Allow exactly: /mv tp world (including alias form /mvtp world)
        return !(effectiveArgs.length == 2 && "tp".equals(effectiveArgs[0]) && isWorldArg(effectiveArgs[1]));
    }

    private String[] resolveMvArgs(String label, String[] args) {
        if ("/mv".equals(label)) {
            return args;
        }
        if (!label.startsWith("/mv") || label.length() <= 3) {
            return null;
        }
        String subcommand = label.substring(3);
        String[] resolved = new String[args.length + 1];
        resolved[0] = subcommand;
        System.arraycopy(args, 0, resolved, 1, args.length);
        return resolved;
    }

    private boolean isWorldArg(String arg) {
        return BUILD_WORLD_NAME.equals(unquote(arg));
    }

    private String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void addBlocked(BlockAction action, String... labels) {
        for (String label : labels) {
            BLOCKED_COMMANDS.put(label, action);
        }
    }

    private String commandLabel(String loweredCommand) {
        int space = loweredCommand.indexOf(' ');
        if (space <= 0) {
            return loweredCommand;
        }
        return loweredCommand.substring(0, space);
    }

    private String[] commandArgs(String rawCommand) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) {
            return new String[0];
        }
        String withoutSlash = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        if (withoutSlash.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = withoutSlash.split("\\s+");
        if (parts.length <= 1) {
            return new String[0];
        }
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return args;
    }

    private enum BlockAction {
        MESSAGE,
        SILENT
    }
}
