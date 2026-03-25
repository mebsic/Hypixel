package io.github.mebsic.build.command;

import io.github.mebsic.build.menu.BuildEditMenu;
import io.github.mebsic.build.service.BuildAccessService;
import io.github.mebsic.build.service.BuildMapConfigService;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class EditCommand implements CommandExecutor, TabCompleter {
    private final BuildAccessService accessService;
    private final BuildMapConfigService mapConfigService;

    public EditCommand(BuildAccessService accessService, BuildMapConfigService mapConfigService) {
        this.accessService = accessService;
        this.mapConfigService = mapConfigService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (accessService == null || mapConfigService == null || !accessService.requireStaff(sender)) {
            return true;
        }
        Player player = (Player) sender;
        World world = player.getWorld();
        String worldDirectory = world == null ? "" : safe(world.getName());
        if (worldDirectory.isEmpty()) {
            sendEmptyWorldMessage(player, "current world");
            return true;
        }
        if ("world".equalsIgnoreCase(worldDirectory)) {
            player.sendMessage(ChatColor.RED + "This world cannot be edited!");
            return true;
        }
        if (args == null || args.length == 0) {
            sendUsage(player);
            sendAvailableGameTypes(player, false);
            return true;
        }
        if (args.length != 1) {
            sendUsage(player);
            sendAvailableGameTypes(player, false);
            return true;
        }

        ServerType gameType = mapConfigService.parseGameType(args[0], worldDirectory);
        if (gameType == null || gameType == ServerType.UNKNOWN) {
            sendAvailableGameTypes(player, true);
            return true;
        }
        if (!mapConfigService.isWorldTypeCompatible(gameType, worldDirectory)) {
            sendWorldTypeMismatch(player, worldDirectory, gameType);
            return true;
        }
        new BuildEditMenu(mapConfigService, gameType, worldDirectory).open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (mapConfigService == null || args == null) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return mapConfigService.completeGameTypes(args[0]);
        }
        return Collections.emptyList();
    }

    private void sendUsage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        player.sendMessage(ChatColor.RED + "/edit <gameType>");
    }

    private void sendAvailableGameTypes(Player player, boolean unknownType) {
        if (player == null || mapConfigService == null) {
            return;
        }
        if (unknownType) {
            player.sendMessage(ChatColor.RED + "Unknown game type! Available game types:");
        } else {
            player.sendMessage(ChatColor.RED + "Available game types:");
        }
        for (String type : mapConfigService.availableGameTypes()) {
            player.sendMessage(ChatColor.RED + type);
        }
    }

    private void sendWorldTypeMismatch(Player player, String worldDirectory, ServerType gameType) {
        if (player == null || mapConfigService == null || gameType == null || gameType == ServerType.UNKNOWN) {
            return;
        }
        String worldLabel = safe(worldDirectory);
        if (worldLabel.isEmpty()) {
            worldLabel = "current world";
        }
        boolean hubWorld = mapConfigService.isHubMap(worldDirectory);
        if (hubWorld && gameType.isGame()) {
            player.sendMessage(ChatColor.RED + "You cannot edit the world \"" + worldLabel + "\" as a game map because it is a hub map!");
            return;
        }
        if (!hubWorld && gameType.isHub()) {
            player.sendMessage(ChatColor.RED + "You cannot edit the world \"" + worldLabel + "\" as a hub map because it is a game map!");
        }
    }

    private void sendEmptyWorldMessage(Player player, String worldName) {
        if (player == null) {
            return;
        }
        String worldLabel = safe(worldName);
        if (worldLabel.isEmpty()) {
            worldLabel = "current world";
        }
        player.sendMessage(ChatColor.RED + "You cannot edit the world \"" + worldLabel + "\" because it is empty!");
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }
}
