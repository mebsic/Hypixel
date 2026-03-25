package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.proxy.service.BlockService;
import io.github.mebsic.proxy.service.FriendService;
import io.github.mebsic.proxy.service.RankResolver;
import io.github.mebsic.proxy.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Optional;
import java.util.UUID;

public class FriendMessageCommand implements SimpleCommand {
    private final ProxyServer proxy;
    private final FriendService friends;
    private final BlockService blocks;
    private final RankResolver rankResolver;

    public FriendMessageCommand(ProxyServer proxy, FriendService friends, BlockService blocks, RankResolver rankResolver) {
        this.proxy = proxy;
        this.friends = friends;
        this.blocks = blocks;
        this.rankResolver = rankResolver;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        Player sender = (Player) invocation.source();
        String[] args = invocation.arguments();
        if (args.length < 2) {
            sendInvalidUsage(sender);
            return;
        }
        String targetInput = args[0];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sender.sendMessage(Component.text("No player found with name " + targetInput + "!", NamedTextColor.RED));
            return;
        }
        if (targetId.equals(sender.getUniqueId())) {
            sender.sendMessage(Component.text("You cannot message yourself!", NamedTextColor.RED));
            return;
        }
        if (!friends.areFriends(sender.getUniqueId(), targetId)) {
            sender.sendMessage(Component.text("You can only message players on your friends list.", NamedTextColor.RED));
            return;
        }
        if (blocks != null && blocks.isEitherBlocked(sender.getUniqueId(), targetId)) {
            sender.sendMessage(Component.text("You cannot message this player.", NamedTextColor.RED));
            return;
        }
        Optional<Player> target = proxy.getPlayer(targetId);
        if (!target.isPresent()) {
            sender.sendMessage(Component.text("That player is offline.", NamedTextColor.RED));
            return;
        }
        String message = joinArgs(args, 1);
        Player recipient = target.get();
        friends.rememberName(sender.getUniqueId(), sender.getUsername());
        friends.rememberName(recipient.getUniqueId(), recipient.getUsername());
        String senderDisplay = formatRankedName(sender.getUniqueId(), sender.getUsername());
        String recipientDisplay = formatRankedName(recipient.getUniqueId(), recipient.getUsername());
        sender.sendMessage(Components.friendPrivateMessage(true, recipientDisplay, message));
        recipient.sendMessage(Components.friendPrivateMessage(false, senderDisplay, message));
    }

    private void sendInvalidUsage(Player sender) {
        sender.sendMessage(Component.text(
                "Invalid usage! Use: /msg <player> <message>",
                NamedTextColor.RED));
    }

    private UUID resolveUuid(String name) {
        if (name == null) {
            return null;
        }
        UUID online = proxy.getPlayer(name).map(Player::getUniqueId).orElse(null);
        if (online != null) {
            proxy.getPlayer(online).ifPresent(player -> friends.rememberName(online, player.getUsername()));
            return online;
        }
        UUID cached = friends.resolveByName(name);
        if (cached != null) {
            return cached;
        }
        UUID mojang = MojangApi.lookupUuid(name);
        if (mojang != null) {
            friends.rememberName(mojang, name);
        }
        return mojang;
    }

    private String formatRankedName(UUID uuid, String fallbackName) {
        if (rankResolver == null) {
            return fallbackName == null ? "" : fallbackName;
        }
        return rankResolver.formatNameWithRank(uuid, fallbackName);
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
