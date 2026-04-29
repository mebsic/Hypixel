package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DisplayEndCreditsCommand implements CommandExecutor {
    private static final int END_CREDITS_REASON = 4;
    private static final float END_CREDITS_VALUE = 1.0F;

    private final CorePlugin plugin;

    public DisplayEndCreditsCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + CommonMessages.ONLY_PLAYERS_COMMAND);
            return true;
        }
        Player player = (Player) sender;
        if (!RankUtil.hasAtLeast(plugin, player, Rank.STAFF)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }

        if (args.length != 1) {
            sendUsage(sender, label);
            return true;
        }

        if (args[0].equalsIgnoreCase("all")) {
            return displayForAll(sender);
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + CommonMessages.PLAYER_NOT_FOUND_COMMAND);
            return true;
        }

        if (sendEndCredits(target)) {
            sender.sendMessage(ChatColor.GREEN + CommonMessages.DONE);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Failed!");
        return true;
    }

    private boolean displayForAll(CommandSender sender) {
        boolean failed = false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!sendEndCredits(online)) {
                failed = true;
            }
        }

        if (failed) {
            sender.sendMessage(ChatColor.RED + "Failed!");
            return true;
        }

        sender.sendMessage(ChatColor.GREEN + CommonMessages.DONE);
        return true;
    }

    private boolean sendEndCredits(Player target) {
        if (target == null) {
            return false;
        }
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object handle = craftPlayerClass.getMethod("getHandle").invoke(target);
            Object connection = handle.getClass().getField("playerConnection").get(handle);

            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Class<?> gameStatePacketClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutGameStateChange");
            Object packet = gameStatePacketClass
                    .getConstructor(int.class, float.class)
                    .newInstance(END_CREDITS_REASON, END_CREDITS_VALUE);

            connection.getClass().getMethod("sendPacket", packetClass).invoke(connection, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        sender.sendMessage(ChatColor.RED + "/" + label + " <player/all>");
    }
}
