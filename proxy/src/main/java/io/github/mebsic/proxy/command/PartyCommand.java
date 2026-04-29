package io.github.mebsic.proxy.command;

import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.proxy.service.BlockService;
import io.github.mebsic.proxy.service.ChatChannelService;
import io.github.mebsic.proxy.service.ChatMessageService;
import io.github.mebsic.proxy.service.ChatRestrictionService;
import io.github.mebsic.proxy.service.PartyService.DemoteResult;
import io.github.mebsic.proxy.service.PartyService;
import io.github.mebsic.proxy.service.PartyService.HijackResult;
import io.github.mebsic.proxy.service.PartyService.KickResult;
import io.github.mebsic.proxy.service.PartyService.KickOfflineResult;
import io.github.mebsic.proxy.service.PartyService.KickOfflineStatus;
import io.github.mebsic.proxy.service.PartyService.LeaveResult;
import io.github.mebsic.proxy.service.PartyService.PollCreateResult;
import io.github.mebsic.proxy.service.PartyService.PollVoteResult;
import io.github.mebsic.proxy.service.PartyService.PromoteResult;
import io.github.mebsic.proxy.service.PartyService.TransferResult;
import io.github.mebsic.proxy.service.RankResolver;
import io.github.mebsic.proxy.service.ServerRegistryService;
import io.github.mebsic.proxy.util.Components;
import io.github.mebsic.proxy.util.PartyComponents;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class PartyCommand implements SimpleCommand {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private final ProxyServer proxy;
    private final PartyService parties;
    private final RankResolver rankResolver;
    private final ServerRegistryService registry;
    private final BlockService blocks;
    private final ChatRestrictionService chatRestrictions;
    private final ChatMessageService chatMessages;
    private final String commandRoot;

    public PartyCommand(ProxyServer proxy, PartyService parties) {
        this(proxy, parties, null, null, null, null, "party", null);
    }

    public PartyCommand(ProxyServer proxy, PartyService parties, RankResolver rankResolver, String commandRoot) {
        this(proxy, parties, rankResolver, null, null, null, commandRoot, null);
    }

    public PartyCommand(ProxyServer proxy,
                        PartyService parties,
                        RankResolver rankResolver,
                        ServerRegistryService registry,
                        String commandRoot) {
        this(proxy, parties, rankResolver, registry, null, null, commandRoot, null);
    }

    public PartyCommand(ProxyServer proxy,
                        PartyService parties,
                        RankResolver rankResolver,
                        ServerRegistryService registry,
                        BlockService blocks,
                        ChatRestrictionService chatRestrictions,
                        String commandRoot,
                        ChatMessageService chatMessages) {
        this.proxy = proxy;
        this.parties = parties;
        this.rankResolver = rankResolver;
        this.registry = registry;
        this.blocks = blocks;
        this.chatRestrictions = chatRestrictions;
        this.chatMessages = chatMessages;
        String safeRoot = commandRoot == null ? "" : commandRoot.trim().toLowerCase(Locale.ROOT);
        this.commandRoot = safeRoot.isEmpty() ? "party" : safeRoot;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text(CommonMessages.ONLY_PLAYERS_COMMAND, NamedTextColor.RED));
            return;
        }
        Player player = (Player) invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendHelp(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help":
                sendHelp(player);
                return;
            case "invite":
                handleInvite(player, args, 1);
                return;
            case "accept":
            case "join":
                handleAccept(player, args);
                return;
            case "deny":
                handleDeny(player, args);
                return;
            case "leave":
                handleLeave(player);
                return;
            case "disband":
                handleDisband(player);
                return;
            case "kick":
                handleKick(player, args);
                return;
            case "kickoffline":
                handleKickOffline(player);
                return;
            case "list":
                handleList(player);
                return;
            case "setting":
            case "settings":
                handleSettings(player, args);
                return;
            case "mute":
                handleMute(player, args);
                return;
            case "private":
                sendInvalidUsage(player, cmd("help"));
                return;
            case "poll":
                handlePoll(player, args);
                return;
            case "answer":
                handlePollAnswer(player, args);
                return;
            case "promote":
                handlePromote(player, args);
                return;
            case "demote":
                handleDemote(player, args);
                return;
            case "transfer":
                handleTransfer(player, args);
                return;
            case "hijack":
                handleHijack(player, args);
                return;
            case "warp":
                handleWarp(player);
                return;
            case "chat":
            case "msg":
            case "message":
                handleChat(player, args, 1);
                return;
            default:
                if (args.length == 1) {
                    handleInvite(player, args, 0);
                    return;
                }
                if (parties.isInParty(player.getUniqueId())) {
                    handleChat(player, args, 0);
                    return;
                }
                sendInvalidUsage(player, cmd("invite <player...>"));
        }
    }

    private void handleInvite(Player player, String[] args, int startIndex) {
        UUID playerId = player.getUniqueId();
        if (!parties.canInvite(playerId)) {
            sendFramed(player, Component.text(
                    "You must be the party leader/moderator, or have All Invite enabled, to invite players.",
                    NamedTextColor.RED
            ));
            return;
        }
        if (args.length <= startIndex) {
            sendInvalidUsage(player, cmd("invite <player...>"));
            return;
        }
        UUID leaderId = parties.getLeader(playerId);
        if (leaderId == null) {
            leaderId = playerId;
        }
        int maxPartySize = maxPartySize(leaderId);
        int partySize = parties.getPartySize(playerId);
        if (partySize <= 0) {
            partySize = 1;
        }
        if (partySize >= maxPartySize) {
            sendFramed(player, Component.text(
                    "Your party is full! Your rank allows up to " + maxPartySize + " members.",
                    NamedTextColor.RED
            ));
            return;
        }
        boolean singleTarget = args.length - startIndex == 1;
        List<Component> lines = new ArrayList<Component>();
        List<Component> successLines = new ArrayList<Component>();
        boolean hadOutcome = false;
        String inviterDisplay = formatNameWithRank(player.getUniqueId(), player.getUsername());
        for (int i = startIndex; i < args.length; i++) {
            String targetName = args[i];
            if (targetName == null || targetName.trim().isEmpty()) {
                continue;
            }
            hadOutcome = true;
            Player target = proxy.getPlayer(targetName).orElse(null);
            if (target == null) {
                UUID resolved = resolveUuid(targetName);
                if (resolved == null) {
                    if (singleTarget) {
                        sendInviteError(player, "Couldn't find a player with that name!");
                        return;
                    }
                    lines.add(Component.text("Couldn't find a player with that name!", NamedTextColor.RED));
                    continue;
                }
                if (singleTarget) {
                    sendInviteError(player, "You cannot invite that player since they're not online.");
                    return;
                }
                lines.add(Component.text("You cannot invite that player since they're not online.", NamedTextColor.RED));
                continue;
            }
            UUID targetId = target.getUniqueId();
            if (player.getUniqueId().equals(targetId)) {
                lines.add(Component.text("You cannot invite yourself to your own party!", NamedTextColor.RED));
                continue;
            }
            if (parties.isInParty(targetId)) {
                lines.add(LEGACY.deserialize(formatNameWithRank(targetId, parties.getName(targetId)))
                        .append(Component.text(" is already in a party!", NamedTextColor.RED)));
                continue;
            }
            if (!parties.invite(player.getUniqueId(), targetId)) {
                lines.add(Component.text("Failed to invite ", NamedTextColor.RED)
                        .append(LEGACY.deserialize(formatNameWithRank(targetId, target.getUsername())))
                        .append(Component.text(" right now!", NamedTextColor.RED)));
                continue;
            }
            sendPartyInviteNotification(target, inviterDisplay, player.getUsername());
            successLines.add(LEGACY.deserialize(inviterDisplay)
                    .append(Component.text(" invited ", NamedTextColor.YELLOW))
                    .append(LEGACY.deserialize(formatNameWithRank(targetId, target.getUsername())))
                    .append(Component.text(" to the party! They have ", NamedTextColor.YELLOW))
                    .append(Component.text("60", NamedTextColor.RED))
                    .append(Component.text(" seconds to accept.", NamedTextColor.YELLOW)));
        }
        if (!successLines.isEmpty()) {
            sendFramedToParty(player.getUniqueId(), successLines);
        }
        if (!hadOutcome) {
            sendInvalidUsage(player, cmd("invite <player...>"));
            return;
        }
        if (!lines.isEmpty()) {
            sendFramed(player, lines);
        }
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("accept <player>"));
            return;
        }
        if (parties.isInParty(player.getUniqueId())) {
            sendFramed(player, Component.text("You are already in a party! Leave it to join another one.", NamedTextColor.RED));
            return;
        }
        UUID leaderId = resolveUuid(args[1]);
        if (leaderId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        int maxPartySize = maxPartySize(leaderId);
        int currentPartySize = parties.getPartySize(leaderId);
        if (currentPartySize >= maxPartySize) {
            sendFramed(player, Component.text("That party is full!", NamedTextColor.RED));
            return;
        }
        if (!parties.acceptInvite(player.getUniqueId(), leaderId)) {
            sendFramed(player, Component.text("No pending party invite from that player!", NamedTextColor.RED));
            return;
        }
        sendFramed(player, Component.text("You have joined ", NamedTextColor.YELLOW)
                .append(LEGACY.deserialize(formatNameWithRank(leaderId, args[1])))
                .append(Component.text("'s party!", NamedTextColor.YELLOW)));
        parties.notifyPartyStatus(player.getUniqueId(), true);
    }

    private void handleDeny(Player player, String[] args) {
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("deny <player>"));
            return;
        }
        UUID leaderId = resolveUuid(args[1]);
        if (leaderId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        if (!parties.denyInvite(player.getUniqueId(), leaderId)) {
            sendFramed(player, Component.text("No pending party invite from that player!", NamedTextColor.RED));
            return;
        }
        sendFramed(player, Component.text("Party invite denied.", NamedTextColor.YELLOW));
    }

    private void handleLeave(Player player) {
        PartyService.Party party = parties.getParty(player.getUniqueId());
        if (party == null) {
            sendNotInParty(player);
            return;
        }
        UUID playerId = player.getUniqueId();
        boolean isLeader = parties.isLeader(playerId);
        UUID partyLeaderId = party.getLeader();
        LeaveResult result = parties.leave(playerId);
        if (result == LeaveResult.NOT_IN_PARTY) {
            sendNotInParty(player);
            return;
        }
        if (isLeader) {
            Component disbandedMessage = LEGACY.deserialize(formatNameWithRank(player.getUniqueId(), player.getUsername()))
                    .append(Component.text(" has disbanded the party!", NamedTextColor.YELLOW));
            sendFramed(player, disbandedMessage);
            for (UUID member : party.getMembers()) {
                if (!member.equals(player.getUniqueId())) {
                    proxy.getPlayer(member).ifPresent(target ->
                            sendFramedWithShort(target, singleLine(disbandedMessage))
                    );
                }
            }
            return;
        }
        sendFramed(player, Component.text("You left the party.", NamedTextColor.YELLOW));
        if (result == LeaveResult.DISBANDED) {
            if (partyLeaderId != null && !partyLeaderId.equals(playerId)) {
                proxy.getPlayer(partyLeaderId).ifPresent(target ->
                        sendFramed(
                                target,
                                Component.text(PartyService.EMPTY_PARTY_DISBAND_MESSAGE, NamedTextColor.RED)
                        )
                );
            }
            return;
        }
        for (UUID member : party.getMembers()) {
            proxy.getPlayer(member).ifPresent(target ->
                    sendFramedWithShort(target,
                            singleLine(Components.partyStatus(
                                    formatNameWithRank(player.getUniqueId(), player.getUsername()),
                                    false
                            )))
            );
        }
    }

    private void handleDisband(Player player) {
        if (!parties.isInParty(player.getUniqueId())) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(player.getUniqueId())) {
            sendNotPartyLeader(player);
            return;
        }
        PartyService.Party party = parties.getParty(player.getUniqueId());
        if (party == null) {
            sendNotInParty(player);
            return;
        }
        LeaveResult result = parties.leave(player.getUniqueId());
        if (result == LeaveResult.NOT_IN_PARTY) {
            sendNotInParty(player);
            return;
        }
        Component disbandedMessage = LEGACY.deserialize(formatNameWithRank(player.getUniqueId(), player.getUsername()))
                .append(Component.text(" has disbanded the party!", NamedTextColor.YELLOW));
        sendFramed(player, disbandedMessage);
        for (UUID member : party.getMembers()) {
            if (member == null || member.equals(player.getUniqueId())) {
                continue;
            }
            proxy.getPlayer(member).ifPresent(target ->
                    sendFramedWithShort(target, singleLine(disbandedMessage))
            );
        }
    }

    private void handleKick(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!isPartyLeaderOrModerator(playerId)) {
            sendNotPartyModeratorOrLeader(player);
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("kick <player>"));
            return;
        }
        UUID targetId = resolveUuid(args[1]);
        if (targetId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        KickResult result = parties.kick(playerId, targetId);
        switch (result) {
            case NOT_IN_PARTY:
                sendNotInParty(player);
                return;
            case NOT_LEADER:
                sendNotPartyModeratorOrLeader(player);
                return;
            case CANNOT_KICK_SELF:
                sendFramed(player, Component.text("Use " + cmd("leave") + " to leave your party.", NamedTextColor.RED));
                return;
            case CANNOT_KICK_LEADER:
                sendFramed(player, Component.text("You cannot kick the party leader!", NamedTextColor.RED));
                return;
            case TARGET_NOT_IN_PARTY:
                sendFramed(player, Component.text("That player is not in your party!", NamedTextColor.RED));
                return;
            case KICKED:
            default:
                Component kickedMessage = LEGACY.deserialize(formatNameWithRank(targetId, args[1]))
                        .append(Component.text(" has been removed from the party.", NamedTextColor.YELLOW));
                sendFramedToParty(playerId, kickedMessage);
                String actorDisplay = formatNameWithRank(playerId, player.getUsername());
                proxy.getPlayer(targetId).ifPresent(target ->
                        sendFramedWithShort(target,
                                singleLine(Component.text("You have been kicked from the party by ", NamedTextColor.YELLOW)
                                        .append(LEGACY.deserialize(actorDisplay))))
                );
        }
    }

    private void handleKickOffline(Player player) {
        if (!parties.isInParty(player.getUniqueId())) {
            sendNotInParty(player);
            return;
        }
        KickOfflineResult result = parties.kickOfflineMembersDetailed(player.getUniqueId());
        if (result.getStatus() == KickOfflineStatus.NOT_IN_PARTY) {
            sendNotInParty(player);
            return;
        }
        if (result.getStatus() == KickOfflineStatus.NOT_LEADER) {
            sendNotPartyLeader(player);
            return;
        }
        if (result.getStatus() == KickOfflineStatus.NO_OFFLINE_MEMBERS) {
            sendFramed(player, Component.text("There are no offline players in your party.", NamedTextColor.YELLOW));
            return;
        }
        List<Component> lines = new ArrayList<Component>();
        for (UUID removedId : result.getRemovedMembers()) {
            lines.add(Component.text("Kicked ", NamedTextColor.YELLOW)
                    .append(LEGACY.deserialize(formatNameWithRank(removedId, fallbackName(removedId, "Unknown"))))
                    .append(Component.text(" because they were offline.", NamedTextColor.YELLOW)));
        }
        if (!lines.isEmpty()) {
            sendFramedToParty(player.getUniqueId(), lines);
        }
        if (result.isDisbandedBecauseEmpty()) {
            sendFramed(player, Component.text(
                    PartyService.EMPTY_PARTY_DISBAND_MESSAGE,
                    NamedTextColor.RED
            ));
        }
    }

    private void handlePromote(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(playerId)) {
            sendNotPartyLeader(player);
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("promote <player>"));
            return;
        }
        UUID targetId = resolveUuid(args[1]);
        if (targetId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        PromoteResult result = parties.promote(playerId, targetId);
        String targetName = formatNameWithRank(targetId, args[1]);
        String actorName = formatNameWithRank(playerId, player.getUsername());
        switch (result) {
            case NOT_IN_PARTY:
                sendNotInParty(player);
                return;
            case NOT_LEADER:
                sendNotPartyLeader(player);
                return;
            case CANNOT_TARGET_SELF:
                sendFramed(player, Component.text("You cannot promote yourself!", NamedTextColor.RED));
                return;
            case TARGET_NOT_IN_PARTY:
                sendFramed(player, Component.text("That player is not in your party!", NamedTextColor.RED));
                return;
            case ALREADY_LEADER:
                sendFramed(player, LEGACY.deserialize(targetName)
                        .append(Component.text(" is already the party leader!", NamedTextColor.RED)));
                return;
            case PROMOTED_TO_MODERATOR:
                sendFramedToParty(playerId,
                        LEGACY.deserialize(actorName)
                                .append(Component.text(" has promoted ", NamedTextColor.YELLOW))
                                .append(LEGACY.deserialize(targetName))
                                .append(Component.text(" to Party Moderator.", NamedTextColor.YELLOW)));
                return;
            case TRANSFERRED_LEADER:
            default:
                sendFramedToParty(playerId,
                        Component.text("The party was transferred to ", NamedTextColor.YELLOW)
                                .append(LEGACY.deserialize(targetName))
                                .append(Component.text(".", NamedTextColor.YELLOW)));
        }
    }

    private void handleDemote(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(playerId)) {
            sendNotPartyLeader(player);
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("demote <player>"));
            return;
        }
        UUID targetId = resolveUuid(args[1]);
        if (targetId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        DemoteResult result = parties.demote(playerId, targetId);
        String targetName = formatNameWithRank(targetId, args[1]);
        String actorName = formatNameWithRank(playerId, player.getUsername());
        switch (result) {
            case NOT_IN_PARTY:
                sendNotInParty(player);
                return;
            case NOT_LEADER:
                sendNotPartyLeader(player);
                return;
            case CANNOT_TARGET_SELF:
                sendFramed(player, Component.text("You cannot demote yourself!", NamedTextColor.RED));
                return;
            case TARGET_NOT_IN_PARTY:
                sendFramed(player, Component.text("That player is not in your party!", NamedTextColor.RED));
                return;
            case CANNOT_DEMOTE_LEADER:
                sendFramed(player, Component.text("Use " + cmd("transfer <player>") + " to change the party leader.", NamedTextColor.RED));
                return;
            case ALREADY_MEMBER:
                sendFramed(player, LEGACY.deserialize(targetName)
                        .append(Component.text(" is already a party member!", NamedTextColor.RED)));
                return;
            case DEMOTED_TO_MEMBER:
            default:
                sendFramedToParty(playerId,
                        LEGACY.deserialize(actorName)
                                .append(Component.text(" has demoted ", NamedTextColor.YELLOW))
                                .append(LEGACY.deserialize(targetName))
                                .append(Component.text(" to Party Member.", NamedTextColor.YELLOW)));
        }
    }

    private void handleTransfer(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(playerId)) {
            sendNotPartyLeader(player);
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("transfer <player>"));
            return;
        }
        UUID targetId = resolveUuid(args[1]);
        if (targetId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        TransferResult result = parties.transfer(playerId, targetId);
        String targetName = formatNameWithRank(targetId, args[1]);
        switch (result) {
            case NOT_IN_PARTY:
                sendNotInParty(player);
                return;
            case NOT_LEADER:
                sendNotPartyLeader(player);
                return;
            case CANNOT_TARGET_SELF:
                sendFramed(player, Component.text("You are already the party leader!", NamedTextColor.RED));
                return;
            case TARGET_NOT_IN_PARTY:
                sendFramed(player, Component.text("That player is not in your party!", NamedTextColor.RED));
                return;
            case ALREADY_LEADER:
                sendFramed(player, LEGACY.deserialize(targetName)
                        .append(Component.text(" is already the party leader!", NamedTextColor.RED)));
                return;
            case TRANSFERRED:
            default:
                sendFramedToParty(playerId,
                        Component.text("The party was transferred to ", NamedTextColor.YELLOW)
                                .append(LEGACY.deserialize(targetName))
                                .append(Component.text(".", NamedTextColor.YELLOW)));
        }
    }

    private void handleHijack(Player player, String[] args) {
        if (!isStaff(player.getUniqueId())) {
            sendAccessDenied(player);
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("hijack <player>"));
            return;
        }
        UUID targetId = resolveUuid(args[1]);
        if (targetId == null) {
            sendFramed(player, noPlayerFound(args[1]));
            return;
        }
        HijackResult result = parties.hijack(player.getUniqueId(), targetId);
        switch (result) {
            case TARGET_NOT_IN_PARTY:
                sendFramed(player, LEGACY.deserialize(formatNameWithRank(targetId, args[1]))
                        .append(Component.text(" is not in a party!", NamedTextColor.RED)));
                return;
            case ALREADY_LEADER:
                sendFramed(player, Component.text("You are already the leader of that party!", NamedTextColor.RED));
                return;
            case HIJACKED:
            default:
                String actorDisplay = formatNameWithRank(player.getUniqueId(), player.getUsername());
                sendFramedToParty(player.getUniqueId(),
                        LEGACY.deserialize(actorDisplay)
                                .append(Component.text(" has hijacked the party!", NamedTextColor.YELLOW)));
        }
    }

    private void handleWarp(Player player) {
        if (!parties.isInParty(player.getUniqueId())) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(player.getUniqueId())) {
            sendNotPartyLeader(player);
            return;
        }
        String targetName = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (targetName == null || targetName.trim().isEmpty()) {
            sendFramed(player, Component.text("Cannot determine the current server!", NamedTextColor.RED));
            return;
        }
        RegisteredServer target = proxy.getServer(targetName).orElse(null);
        if (target == null) {
            sendFramed(player, Component.text("Cannot determine the current server!", NamedTextColor.RED));
            return;
        }
        boolean targetIsGame = false;
        if (registry != null) {
            ServerRegistryService.ServerDetails details = registry.findServerDetails(targetName).orElse(null);
            if (details != null) {
                ServerType type = details.getType();
                targetIsGame = type.isGame();
                if (type.isGame() && isWarpBlockedGameState(details.getState())) {
                    sendFramed(player, Component.text(
                            "You cannot warp because the game has started!",
                            NamedTextColor.RED
                    ));
                    return;
                }
            } else {
                targetIsGame = registry.findServerType(targetName)
                        .map(ServerType::isGame)
                        .orElse(false);
            }
        }
        List<Player> partyMembersToNotify = onlinePartyMembers(player.getUniqueId());
        List<Player> toMove = onlinePartyMembersNotOnTarget(player.getUniqueId(), targetName);
        if (!serverHasCapacity(targetName, toMove.size())) {
            sendFramed(player, Component.text(
                    "You cannot warp because this server is full!",
                    NamedTextColor.RED
            ));
            return;
        }
        for (Player partyMember : toMove) {
            if (targetIsGame) {
                parties.authorizeAnyGameJoin(partyMember.getUniqueId());
            }
            partyMember.createConnectionRequest(target).fireAndForget();
        }
        int summonedCount = partyMembersToNotify.size();
        if (summonedCount <= 0) {
            sendFramed(player, Component.text("The party is currently empty!", NamedTextColor.RED));
            return;
        }
        Component summonedNotice = Component.text("Party Leader, ", NamedTextColor.YELLOW)
                .append(LEGACY.deserialize(formatNameWithRank(player.getUniqueId(), player.getUsername())))
                .append(Component.text(", summoned you to their server.", NamedTextColor.YELLOW));
        for (Player partyMember : partyMembersToNotify) {
            sendFramedWithShort(partyMember, singleLine(summonedNotice));
        }
        if (summonedCount == 1) {
            Player summoned = partyMembersToNotify.get(0);
            sendFramed(player, Component.text("You summoned ", NamedTextColor.YELLOW)
                    .append(LEGACY.deserialize(formatNameWithRank(
                            summoned.getUniqueId(),
                            summoned.getUsername()
                    )))
                    .append(Component.text(" to your server.", NamedTextColor.YELLOW)));
            return;
        }
        sendFramed(player, Component.text("You summoned ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(summonedCount), NamedTextColor.GREEN))
                .append(Component.text(" players to your server.", NamedTextColor.YELLOW)));
    }

    private void handleList(Player player) {
        Set<UUID> members = parties.getMembers(player.getUniqueId());
        if (members.isEmpty()) {
            sendNotInParty(player);
            return;
        }
        UUID leaderId = parties.getLeader(player.getUniqueId());
        List<UUID> moderators = new ArrayList<UUID>();
        List<UUID> regularMembers = new ArrayList<UUID>();
        for (UUID memberId : members) {
            if (memberId == null) {
                continue;
            }
            if (leaderId != null && leaderId.equals(memberId)) {
                continue;
            }
            if (parties.isModerator(memberId)) {
                moderators.add(memberId);
                continue;
            }
            regularMembers.add(memberId);
        }
        moderators.sort(Comparator.comparing(id -> fallbackName(id, "Unknown").toLowerCase(Locale.ROOT)));
        regularMembers.sort(Comparator.comparing(id -> fallbackName(id, "Unknown").toLowerCase(Locale.ROOT)));
        List<Component> lines = new ArrayList<Component>();
        lines.add(Component.text("Party Members (" + members.size() + ")", NamedTextColor.GOLD));
        lines.add(Component.empty());
        if (leaderId != null) {
            lines.add(Component.text("Party Leader: ", NamedTextColor.YELLOW)
                    .append(formatPartyListMember(leaderId)));
        }
        if (!moderators.isEmpty()) {
            String label = moderators.size() == 1 ? "Party Moderator: " : "Party Moderators: ";
            lines.add(Component.text(label, NamedTextColor.YELLOW)
                    .append(formatPartyListMembers(moderators)));
        }
        if (!regularMembers.isEmpty()) {
            String label = regularMembers.size() == 1 ? "Party Member: " : "Party Members: ";
            lines.add(Component.text(label, NamedTextColor.YELLOW)
                    .append(formatPartyListMembers(regularMembers)));
        }
        sendFramed(player, lines);
    }

    private Component formatPartyListMembers(List<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Component.text("None", NamedTextColor.GRAY);
        }
        Component combined = Component.empty();
        for (int i = 0; i < memberIds.size(); i++) {
            if (i > 0) {
                combined = combined.append(Component.text(", ", NamedTextColor.WHITE));
            }
            combined = combined.append(formatPartyListMember(memberIds.get(i)));
        }
        return combined;
    }

    private Component formatPartyListMember(UUID memberId) {
        Component name = LEGACY.deserialize(formatNameWithRank(memberId, fallbackName(memberId, "Unknown")));
        NamedTextColor statusColor = proxy.getPlayer(memberId).isPresent()
                ? NamedTextColor.GREEN
                : NamedTextColor.RED;
        return name.append(Component.space()).append(Component.text("●", statusColor));
    }

    private void handleChat(Player player, String[] args, int start) {
        if (!parties.isInParty(player.getUniqueId())) {
            sendNotInParty(player);
            return;
        }
        if (args.length <= start) {
            sendPartyChatUsage(player);
            return;
        }
        if (chatRestrictions != null && chatRestrictions.isMuted(player.getUniqueId())) {
            sendFramed(player, Component.text("You are currently muted!", NamedTextColor.RED));
            return;
        }
        if (parties.isPartyChatMuted(player.getUniqueId())
                && !parties.isLeader(player.getUniqueId())
                && !parties.isModerator(player.getUniqueId())
                && !isStaff(player.getUniqueId())) {
            sendFramed(player, Component.text("This party is currently muted!", NamedTextColor.RED));
            return;
        }
        String message = joinArgs(args, start);
        UUID senderId = player.getUniqueId();
        parties.sendPartyMessage(
                senderId,
                Components.partyChat(formatPartyChatName(senderId, player.getUsername()), message),
                memberId -> canReceivePartyChat(senderId, memberId)
        );
        storePartyChatMessage(player, message);
    }

    private boolean canReceivePartyChat(UUID senderId, UUID recipientId) {
        if (senderId == null || recipientId == null) {
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

    private void handleSettings(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(playerId)) {
            sendNotPartyLeader(player);
            return;
        }
        if (args.length < 2) {
            sendSettingsHelp(player);
            return;
        }
        String setting = args[1].toLowerCase(Locale.ROOT);
        switch (setting) {
            case "allinvite":
                applyAllInviteSetting(player, args, 2);
                return;
            case "mute":
                applyMuteSetting(player, args, 2);
                return;
            default:
                sendSettingsHelp(player);
        }
    }

    private void handleMute(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(playerId)) {
            sendNotPartyLeader(player);
            return;
        }
        applyMuteSetting(player, args, 1);
    }

    private void handlePoll(Player player, String[] args) {
        UUID playerId = player.getUniqueId();
        if (!parties.isInParty(playerId)) {
            sendNotInParty(player);
            return;
        }
        if (!parties.isLeader(playerId)) {
            sendNotPartyLeader(player);
            return;
        }
        if (args.length < 2) {
            sendPollHelp(player, true);
            return;
        }
        PollInput pollInput = parsePollInput(joinArgs(args, 1));
        if (pollInput == null) {
            sendPollHelp(player, true);
            return;
        }
        PollCreateResult result = parties.createPoll(playerId, pollInput.question, pollInput.options);
        switch (result) {
            case NOT_IN_PARTY:
                sendNotInParty(player);
                return;
            case NOT_LEADER:
                sendNotPartyLeader(player);
                return;
            case ALREADY_ACTIVE:
                sendFramed(player, Component.text("You already have an active party poll!", NamedTextColor.RED));
                return;
            case INVALID_POLL:
                sendPollHelp(player, true);
                return;
            case CREATED:
            default:
                return;
        }
    }

    private void handlePollAnswer(Player player, String[] args) {
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("answer <option>"));
            return;
        }
        if (!parties.isInParty(player.getUniqueId())) {
            sendNotInParty(player);
            return;
        }
        if (!parties.hasActivePoll(player.getUniqueId())) {
            sendFramedWithShort(player, singleLine(Component.text("This poll has ended!", NamedTextColor.RED)));
            return;
        }
        int option;
        try {
            option = Integer.parseInt(args[1].trim());
        } catch (NumberFormatException ignored) {
            sendFramed(player, Component.text("That is not a valid poll option!", NamedTextColor.RED));
            return;
        }
        if (option <= 0) {
            sendFramed(player, Component.text("That is not a valid poll option!", NamedTextColor.RED));
            return;
        }
        PollVoteResult result = parties.answerPoll(player.getUniqueId(), option);
        switch (result) {
            case NOT_IN_PARTY:
                sendNotInParty(player);
                return;
            case NO_ACTIVE_POLL:
                sendFramedWithShort(player, singleLine(Component.text("This poll has ended!", NamedTextColor.RED)));
                return;
            case INVALID_OPTION:
                sendFramed(player, Component.text("That is not a valid poll option!", NamedTextColor.RED));
                return;
            case VOTED:
            default:
                sendFramed(player, Component.text("You voted for option " + option + ".", NamedTextColor.GREEN));
        }
    }

    private PollInput parsePollInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        int firstSlash = input.indexOf('/');
        if (firstSlash <= 0) {
            return null;
        }
        String question = input.substring(0, firstSlash).trim();
        if (question.isEmpty()) {
            return null;
        }
        String answersPart = input.substring(firstSlash + 1).trim();
        if (answersPart.isEmpty()) {
            return null;
        }
        String[] answers = answersPart.split("\\s*/\\s*", -1);
        if (answers.length < 2 || answers.length > 4) {
            return null;
        }
        List<String> options = new ArrayList<String>();
        for (String answer : answers) {
            String option = answer == null ? "" : answer.trim();
            if (option.isEmpty()) {
                return null;
            }
            options.add(option);
        }
        return new PollInput(question, options);
    }

    private void sendPollHelp(Player player, boolean includeUsage) {
        List<Component> lines = new ArrayList<Component>();
        lines.add(Component.text(
                "Party Poll command is only usable by the Party Leader, Polls last for ",
                NamedTextColor.YELLOW
        ).append(Component.text("30", NamedTextColor.RED))
                .append(Component.text(
                        " seconds, Results will be shown when the poll ends. You can have between two and four possible answers in your poll!",
                        NamedTextColor.YELLOW
                )));
        if (includeUsage) {
            lines.add(Component.empty());
            lines.add(Component.text(
                    "Invalid usage! /party poll <question/answer/answer[/answer][/answer]>",
                    NamedTextColor.RED
            ));
        }
        sendFramedWithShort(player, lines);
    }

    private void applyAllInviteSetting(Player player, String[] args, int valueIndex) {
        if (args.length > valueIndex + 1) {
            sendInvalidUsage(player, cmd("setting allinvite [on/off]"));
            return;
        }
        Boolean updated;
        if (args.length == valueIndex + 1) {
            Boolean parsed = parseBoolean(args[valueIndex]);
            if (parsed == null) {
                sendInvalidUsage(player, cmd("setting allinvite [on/off]"));
                return;
            }
            updated = parties.setAllInvite(player.getUniqueId(), parsed.booleanValue());
        } else {
            updated = parties.toggleAllInvite(player.getUniqueId());
        }
        if (updated == null) {
            sendNotPartyLeader(player);
            return;
        }
        String actorName = formatNameWithRank(player.getUniqueId(), player.getUsername());
        if (updated.booleanValue()) {
            sendFramedToParty(player.getUniqueId(),
                    LEGACY.deserialize(actorName)
                            .append(Component.text(" enabled All Invite", NamedTextColor.GREEN)));
            return;
        }
        sendFramedToParty(player.getUniqueId(),
                LEGACY.deserialize(actorName)
                        .append(Component.text(" disabled All Invite", NamedTextColor.RED)));
    }

    private void applyMuteSetting(Player player, String[] args, int valueIndex) {
        if (args.length > valueIndex + 1) {
            sendInvalidUsage(player, cmd("mute [on/off]"));
            return;
        }
        Boolean updated;
        if (args.length == valueIndex + 1) {
            Boolean parsed = parseBoolean(args[valueIndex]);
            if (parsed == null) {
                sendInvalidUsage(player, cmd("mute [on/off]"));
                return;
            }
            updated = parties.setPartyChatMuted(player.getUniqueId(), parsed.booleanValue());
        } else {
            updated = parties.togglePartyChatMuted(player.getUniqueId());
        }
        if (updated == null) {
            sendNotPartyLeader(player);
            return;
        }
        if (updated.booleanValue()) {
            List<Component> lines = new ArrayList<Component>();
            lines.add(Component.text("The party is now muted!", NamedTextColor.RED));
            lines.add(Component.text(
                    "Only Party Mods, Staff and the Leader will be able to chat.",
                    NamedTextColor.YELLOW
            ));
            sendFramedToParty(player.getUniqueId(), lines);
            return;
        }
        sendFramedToParty(player.getUniqueId(), Component.text("The party is no longer muted.", NamedTextColor.GREEN));
    }

    private Component noPlayerFound(String input) {
        String safe = input == null ? "unknown" : input;
        return Component.text("No player found with name " + safe + "!", NamedTextColor.RED);
    }

    private UUID resolveUuid(String name) {
        if (name == null) {
            return null;
        }
        UUID online = proxy.getPlayer(name).map(Player::getUniqueId).orElse(null);
        if (online != null) {
            return online;
        }
        UUID cached = parties.resolveByName(name);
        if (cached != null) {
            return cached;
        }
        UUID mojang = MojangApi.lookupUuid(name);
        if (mojang != null) {
            parties.rememberName(mojang, name);
        }
        return mojang;
    }

    private String fallbackName(UUID uuid, String fallback) {
        if (uuid == null) {
            return fallback == null || fallback.trim().isEmpty() ? "Unknown" : fallback;
        }
        String resolved = parties.getName(uuid);
        if (resolved == null || resolved.trim().isEmpty() || "Unknown".equalsIgnoreCase(resolved)) {
            return fallback == null || fallback.trim().isEmpty() ? "Unknown" : fallback;
        }
        return resolved;
    }

    private String formatNameWithRank(UUID uuid, String fallback) {
        String safeFallback = fallbackName(uuid, fallback);
        return parties.formatNameWithRank(uuid, safeFallback);
    }

    private String formatPartyChatName(UUID uuid, String fallback) {
        String safeFallback = fallbackName(uuid, fallback);
        if (uuid == null || rankResolver == null) {
            return "§7" + safeFallback;
        }
        try {
            String ranked = rankResolver.formatNameWithRank(uuid, safeFallback);
            if (ranked == null || ranked.trim().isEmpty()) {
                return "§7" + safeFallback;
            }
            String stripped = LEGACY_CODE.matcher(ranked).replaceAll("");
            if (safeFallback.equals(stripped)) {
                return "§7" + safeFallback;
            }
            return ranked;
        } catch (Throwable ignored) {
            return "§7" + safeFallback;
        }
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

    private void storePartyChatMessage(Player player, String message) {
        if (player == null || message == null || chatMessages == null) {
            return;
        }
        String serverId = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .filter(name -> name != null && !name.trim().isEmpty())
                .orElse("proxy");
        chatMessages.storeMessage(
                player.getUniqueId(),
                player.getUsername(),
                serverId,
                ChatChannelService.ChatChannel.PARTY,
                message
        );
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

    private int maxPartySize(UUID playerId) {
        if (playerId == null || rankResolver == null) {
            return 20;
        }
        try {
            if (rankResolver.hasAtLeast(playerId, "YOUTUBE")) {
                return 100;
            }
            if (rankResolver.hasAtLeast(playerId, "MVP_PLUS_PLUS")) {
                return 40;
            }
        } catch (Throwable ignored) {
            return 20;
        }
        return 20;
    }

    private List<Player> onlinePartyMembers(UUID leaderId) {
        List<Player> players = new ArrayList<Player>();
        if (leaderId == null) {
            return players;
        }
        Set<UUID> members = parties.getMembers(leaderId);
        for (UUID memberId : members) {
            if (memberId == null || memberId.equals(leaderId)) {
                continue;
            }
            proxy.getPlayer(memberId).ifPresent(players::add);
        }
        return players;
    }

    private List<Player> onlinePartyMembersNotOnTarget(UUID leaderId, String targetName) {
        List<Player> players = new ArrayList<Player>();
        if (leaderId == null) {
            return players;
        }
        Set<UUID> members = parties.getMembers(leaderId);
        for (UUID memberId : members) {
            if (memberId == null || memberId.equals(leaderId)) {
                continue;
            }
            proxy.getPlayer(memberId).ifPresent(member -> {
                String memberServer = member.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null);
                if (memberServer != null && memberServer.equalsIgnoreCase(targetName)) {
                    return;
                }
                players.add(member);
            });
        }
        return players;
    }

    private boolean serverHasCapacity(String serverName, int neededSlots) {
        if (neededSlots <= 0 || registry == null || serverName == null || serverName.trim().isEmpty()) {
            return true;
        }
        ServerRegistryService.ServerDetails details = registry.findServerDetails(serverName).orElse(null);
        if (details == null) {
            return true;
        }
        int maxPlayers = details.getMaxPlayers();
        if (maxPlayers <= 0) {
            return true;
        }
        int available = maxPlayers - details.getPlayers();
        return available >= neededSlots;
    }

    private boolean isWarpBlockedGameState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return false;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("IN_GAME") || normalized.equals("ENDING");
    }

    private String cmd(String suffix) {
        String safeSuffix = suffix == null ? "" : suffix.trim();
        if (safeSuffix.isEmpty()) {
            return "/" + commandRoot;
        }
        return "/" + commandRoot + " " + safeSuffix;
    }

    private Boolean parseBoolean(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("on")
                || normalized.equals("true")
                || normalized.equals("yes")
                || normalized.equals("enable")
                || normalized.equals("enabled")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("off")
                || normalized.equals("false")
                || normalized.equals("no")
                || normalized.equals("disable")
                || normalized.equals("disabled")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void sendInvalidUsage(Player player, String ignoredSyntax) {
        sendHelp(player);
    }

    private void sendNotInParty(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text("You are not in a party!", NamedTextColor.RED));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendInviteError(Player player, String message) {
        if (player == null) {
            return;
        }
        String safeMessage = message == null ? "Failed to invite that player!" : message;
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text(safeMessage, NamedTextColor.RED));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendPartyChatUsage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text("Usage: /party chat <message>", NamedTextColor.RED));
        player.sendMessage(Component.text("Usage: /p chat <message>", NamedTextColor.RED));
        player.sendMessage(Component.text("Aliases: /pchat <message>, /pc <message>", NamedTextColor.RED));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendPartyInviteNotification(Player target, String formattedLeaderName, String commandLeaderName) {
        if (target == null) {
            return;
        }
        target.sendMessage(PartyComponents.longSeparator());
        for (Component line : Components.partyInviteLines(formattedLeaderName, commandLeaderName)) {
            target.sendMessage(line == null ? Component.empty() : line);
        }
        target.sendMessage(PartyComponents.longSeparator());
    }

    private void sendAccessDenied(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text(CommonMessages.NO_PERMISSION_COMMAND, NamedTextColor.RED));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendNotPartyLeader(Player player) {
        if (player == null) {
            return;
        }
        sendFramedWithShort(player, singleLine(Component.text("You are not this party's leader!", NamedTextColor.RED)));
    }

    private boolean isPartyLeaderOrModerator(UUID memberId) {
        if (memberId == null) {
            return false;
        }
        return parties.isLeader(memberId) || parties.isModerator(memberId);
    }

    private void sendNotPartyModeratorOrLeader(Player player) {
        if (player == null) {
            return;
        }
        sendFramedWithShort(player, singleLine(Component.text(
                "You must be the party leader or a Party Moderator to use this command.",
                NamedTextColor.RED
        )));
    }

    private void sendFramed(Player player, Component line) {
        List<Component> lines = new ArrayList<Component>(1);
        lines.add(line == null ? Component.empty() : line);
        sendFramed(player, lines);
    }

    private void sendFramed(Player player, List<Component> lines) {
        if (player == null) {
            return;
        }
        sendFramedWithShort(player, lines);
    }

    private void sendFramedToParty(UUID memberId, Component line) {
        List<Component> lines = new ArrayList<Component>(1);
        lines.add(line == null ? Component.empty() : line);
        sendFramedToParty(memberId, lines);
    }

    private void sendFramedToParty(UUID memberId, List<Component> lines) {
        if (memberId == null) {
            return;
        }
        Set<UUID> members = parties.getMembers(memberId);
        if (members.isEmpty()) {
            proxy.getPlayer(memberId).ifPresent(player -> sendFramedWithShort(player, lines));
            return;
        }
        for (UUID partyMemberId : members) {
            if (partyMemberId == null) {
                continue;
            }
            proxy.getPlayer(partyMemberId).ifPresent(player -> sendFramedWithShort(player, lines));
        }
    }

    private void sendFramedWithShort(Player player, List<Component> lines) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        for (Component line : lines) {
            player.sendMessage(line == null ? Component.empty() : line);
        }
        player.sendMessage(PartyComponents.longSeparator());
    }

    private List<Component> singleLine(Component line) {
        List<Component> lines = new ArrayList<Component>(1);
        lines.add(line == null ? Component.empty() : line);
        return lines;
    }

    private static final class PollInput {
        private final String question;
        private final List<String> options;

        private PollInput(String question, List<String> options) {
            this.question = question;
            this.options = options;
        }
    }

    private void sendSettingsHelp(Player player) {
        List<Component> lines = new ArrayList<Component>();
        lines.add(Component.text("Party Settings", NamedTextColor.GOLD));
        lines.add(PartyComponents.commandEntry(cmd("setting allinvite"), "Toggles all invite"));
        lines.add(PartyComponents.commandEntry(cmd("mute"), "Mutes the party chat"));
        sendFramed(player, lines);
    }

    private void sendHelp(Player player) {
        List<Component> lines = new ArrayList<Component>();
        lines.add(Component.text("Party Commands:", NamedTextColor.GREEN));
        lines.add(PartyComponents.commandEntry(cmd("accept <player>"), "Accept a party invite from a player"));
        lines.add(PartyComponents.commandEntry(cmd("chat <message>"), "Send a chat message to the entire party"));
        lines.add(PartyComponents.commandEntry(cmd("demote <player>"), "Demotes another party member from Moderator to Member"));
        lines.add(PartyComponents.commandEntry(cmd("disband"), "Disbands the party"));
        lines.add(PartyComponents.commandEntry(cmd("invite <player...>"), "Invite another player to your party"));
        lines.add(PartyComponents.commandEntry(cmd("kick <player>"), "Remove a player from your party"));
        lines.add(PartyComponents.commandEntry(cmd("kickoffline"), "Remove all players that are offline in your party"));
        lines.add(PartyComponents.commandEntry(cmd("leave"), "Leaves your current party"));
        lines.add(PartyComponents.commandEntry(cmd("list"), "Lists the players in your current party"));
        lines.add(PartyComponents.commandEntry(cmd("mute"), "Mutes party chat so only Party Mods, Staff and the Leader can use it"));
        lines.add(PartyComponents.commandEntry(cmd("poll <question/answer...>"), "Creates a poll for party members to vote on"));
        lines.add(PartyComponents.commandEntry(cmd("promote <player>"), "Promotes another party member to either Party Mod or Party Leader"));
        lines.add(PartyComponents.commandEntry(cmd("settings <setting> <value>"), "Toggles party settings"));
        lines.add(PartyComponents.commandEntry(cmd("transfer <player>"), "Transfers the party to another player."));
        lines.add(PartyComponents.commandEntry(cmd("warp"), "Warps the members of a party to your current server"));
        if (isStaff(player.getUniqueId())) {
            lines.add(PartyComponents.commandEntry(cmd("hijack <player>"), "Take over another player's party"));
        }
        sendFramed(player, lines);
    }
}
