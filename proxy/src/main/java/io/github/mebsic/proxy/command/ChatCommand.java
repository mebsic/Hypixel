package io.github.mebsic.proxy.command;

import io.github.mebsic.proxy.service.ChatChannelService;
import io.github.mebsic.proxy.service.PartyService;
import io.github.mebsic.proxy.util.PartyComponents;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class ChatCommand implements SimpleCommand {
    private final ChatChannelService channels;
    private final PartyService parties;

    public ChatCommand(ChatChannelService channels, PartyService parties) {
        this.channels = channels;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        Player player = (Player) invocation.source();
        String[] args = invocation.arguments();
        if (args.length != 1) {
            sendUsage(player);
            sendAvailableChannels(player);
            return;
        }

        ChatChannelService.ChatChannel channel = ChatChannelService.ChatChannel.fromInput(args[0]);
        if (channel == null) {
            sendUsage(player);
            sendAvailableChannels(player);
            return;
        }

        if (channels == null) {
            String channelLabel = channel == ChatChannelService.ChatChannel.PARTY ? "Party chat" : "All chat";
            String unavailable = channelLabel + " is currently unavailable! Please try again later.";
            sendFramed(player, Component.text(unavailable, NamedTextColor.RED));
            return;
        }

        if (channels.getChannel(player.getUniqueId()) == channel) {
            sendFramed(player, Component.text("You're already in this channel!", NamedTextColor.RED));
            return;
        }

        if (channel == ChatChannelService.ChatChannel.PARTY
                && (parties == null || !parties.isInParty(player.getUniqueId()))) {
            sendFramed(player, Component.text("You must be in a party to join the party channel!", NamedTextColor.RED));
            return;
        }

        channels.setChannel(player.getUniqueId(), channel);
        sendChannelConfirmation(player, channel);
    }

    private void sendUsage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(Component.text("Invalid usage! Correct usage:", NamedTextColor.RED));
        player.sendMessage(Component.text("/chat <channel>", NamedTextColor.RED));
    }

    private void sendAvailableChannels(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(Component.text("Available channels:", NamedTextColor.RED));
        player.sendMessage(Component.text("ALL", NamedTextColor.RED));
        player.sendMessage(Component.text("PARTY", NamedTextColor.RED));
    }

    private void sendFramed(Player player, Component line) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(line == null ? Component.empty() : line);
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendChannelConfirmation(Player player, ChatChannelService.ChatChannel channel) {
        if (player == null || channel == null) {
            return;
        }
        player.sendMessage(Component.text("You are now in the ", NamedTextColor.GREEN)
                .append(Component.text(channel.name(), NamedTextColor.GOLD))
                .append(Component.text(" channel!", NamedTextColor.GREEN)));
    }
}
