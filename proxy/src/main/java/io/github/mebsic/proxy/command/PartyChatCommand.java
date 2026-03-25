package io.github.mebsic.proxy.command;

import io.github.mebsic.proxy.service.BlockService;
import io.github.mebsic.proxy.service.ChatRestrictionService;
import io.github.mebsic.proxy.service.PartyService;
import io.github.mebsic.proxy.service.RankResolver;
import io.github.mebsic.proxy.util.Components;
import io.github.mebsic.proxy.util.PartyComponents;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.UUID;
import java.util.regex.Pattern;

public class PartyChatCommand implements SimpleCommand {
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");
    private final PartyService parties;
    private final RankResolver rankResolver;
    private final BlockService blocks;
    private final ChatRestrictionService chatRestrictions;

    public PartyChatCommand(PartyService parties, RankResolver rankResolver) {
        this(parties, rankResolver, null, null);
    }

    public PartyChatCommand(PartyService parties,
                            RankResolver rankResolver,
                            BlockService blocks,
                            ChatRestrictionService chatRestrictions) {
        this.parties = parties;
        this.rankResolver = rankResolver;
        this.blocks = blocks;
        this.chatRestrictions = chatRestrictions;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        Player player = (Player) invocation.source();
        UUID playerId = player.getUniqueId();
        if (parties == null || !parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendUsage(player);
            return;
        }
        if (chatRestrictions != null && chatRestrictions.isMuted(playerId)) {
            sendFramed(player, Component.text("You are currently muted.", NamedTextColor.RED));
            return;
        }
        if (parties.isPartyChatMuted(playerId)
                && !parties.isLeader(playerId)
                && !parties.isModerator(playerId)
                && !isStaff(playerId)) {
            sendFramed(player, Component.text("This party is currently muted.", NamedTextColor.RED));
            return;
        }
        String message = joinArgs(args);
        parties.sendPartyMessage(
                playerId,
                Components.partyChat(formatPartyChatName(playerId, player.getUsername()), message),
                memberId -> canReceivePartyChat(playerId, memberId)
        );
    }

    private boolean canReceivePartyChat(UUID senderId, UUID recipientId) {
        if (recipientId == null || senderId == null) {
            return false;
        }
        if (senderId.equals(recipientId)) {
            return true;
        }
        if (blocks == null) {
            return true;
        }
        return !blocks.isEitherBlocked(senderId, recipientId);
    }

    private boolean isStaff(UUID uuid) {
        if (uuid == null || rankResolver == null) {
            return false;
        }
        try {
            return rankResolver.isStaff(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String joinArgs(String[] args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private String formatPartyChatName(UUID uuid, String fallbackName) {
        String fallback = fallbackName == null ? "Unknown" : fallbackName;
        if (rankResolver == null || uuid == null) {
            return "§7" + fallback;
        }
        try {
            String ranked = rankResolver.formatNameWithRank(uuid, fallback);
            if (ranked == null || ranked.trim().isEmpty()) {
                return "§7" + fallback;
            }
            String stripped = LEGACY_CODE.matcher(ranked).replaceAll("");
            if (fallback.equals(stripped)) {
                return "§7" + fallback;
            }
            return ranked;
        } catch (Throwable ignored) {
            return "§7" + fallback;
        }
    }

    private void sendUsage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text("Usage: /party chat <message>", NamedTextColor.RED));
        player.sendMessage(Component.text("Aliases: /p chat <message>, /pchat <message>, /pc <message>", NamedTextColor.RED));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendNotInParty(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text("You are not in a party!", NamedTextColor.RED));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendFramed(Player player, Component line) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(line == null ? Component.empty() : line);
        player.sendMessage(PartyComponents.longSeparator());
    }
}
