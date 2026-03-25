package io.github.mebsic.proxy.service;

import io.github.mebsic.proxy.util.Components;
import io.github.mebsic.proxy.util.PartyComponents;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class PartyService {
    private static final long OFFLINE_REMOVAL_DELAY_MILLIS = 5L * 60L * 1000L;
    private static final long OFFLINE_REMOVAL_DELAY_MINUTES = OFFLINE_REMOVAL_DELAY_MILLIS / (60L * 1000L);
    private static final long INVITE_EXPIRY_MILLIS = 60L * 1000L;
    private static final long LEADER_WARP_AUTHORIZATION_MILLIS = 15L * 1000L;
    private static final String ANY_GAME_JOIN_TARGET = "*";
    private static final int POLL_DURATION_SECONDS = 30;
    private static final long POLL_DURATION_MILLIS = POLL_DURATION_SECONDS * 1000L;
    private static final int[] POLL_COUNTDOWN_CHECKPOINTS = {20, 10};
    private static final int POLL_BAR_SQUARES = 10;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public enum LeaveResult {
        NOT_IN_PARTY,
        LEFT,
        DISBANDED
    }

    public enum KickResult {
        NOT_IN_PARTY,
        NOT_LEADER,
        CANNOT_KICK_SELF,
        CANNOT_KICK_LEADER,
        TARGET_NOT_IN_PARTY,
        KICKED
    }

    public enum PromoteResult {
        NOT_IN_PARTY,
        NOT_LEADER,
        CANNOT_TARGET_SELF,
        TARGET_NOT_IN_PARTY,
        ALREADY_LEADER,
        PROMOTED_TO_MODERATOR,
        TRANSFERRED_LEADER
    }

    public enum DemoteResult {
        NOT_IN_PARTY,
        NOT_LEADER,
        CANNOT_TARGET_SELF,
        TARGET_NOT_IN_PARTY,
        CANNOT_DEMOTE_LEADER,
        ALREADY_MEMBER,
        DEMOTED_TO_MEMBER
    }

    public enum TransferResult {
        NOT_IN_PARTY,
        NOT_LEADER,
        CANNOT_TARGET_SELF,
        TARGET_NOT_IN_PARTY,
        ALREADY_LEADER,
        TRANSFERRED
    }

    public enum HijackResult {
        TARGET_NOT_IN_PARTY,
        ALREADY_LEADER,
        HIJACKED
    }

    public enum KickOfflineStatus {
        NOT_IN_PARTY,
        NOT_LEADER,
        NO_OFFLINE_MEMBERS,
        REMOVED
    }

    public enum PollCreateResult {
        NOT_IN_PARTY,
        NOT_LEADER,
        ALREADY_ACTIVE,
        INVALID_POLL,
        CREATED
    }

    public enum PollVoteResult {
        NOT_IN_PARTY,
        NO_ACTIVE_POLL,
        INVALID_OPTION,
        VOTED
    }

    private final ProxyServer proxy;
    private final RankResolver rankResolver;
    private final Map<UUID, Party> parties = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> memberToLeader = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> pendingInvites = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastKnownName = new ConcurrentHashMap<>();
    private final Map<UUID, Long> offlineSince = new ConcurrentHashMap<>();
    private final Map<UUID, PartyPoll> activePolls = new ConcurrentHashMap<>();
    private final Map<UUID, AuthorizedGameJoin> authorizedGameJoins = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> memberRemovedFromPartyListener;

    public PartyService(ProxyServer proxy) {
        this(proxy, null);
    }

    public PartyService(ProxyServer proxy, RankResolver rankResolver) {
        this.proxy = proxy;
        this.rankResolver = rankResolver;
    }

    public void setMemberRemovedFromPartyListener(Consumer<UUID> listener) {
        this.memberRemovedFromPartyListener = listener;
    }

    public void track(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        rememberName(uuid, player.getUsername());
        Long wasOfflineAt = offlineSince.remove(uuid);
        if (wasOfflineAt == null) {
            return;
        }
        Party party = getParty(uuid);
        if (party == null) {
            return;
        }
        boolean wasLeader = uuid.equals(party.leader);
        String formattedName = formatNameWithRank(uuid, player.getUsername());
        broadcastFramedToParty(party, rejoinedMessage(formattedName, wasLeader), null);
    }

    public void markOffline(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        rememberName(uuid, player.getUsername());
        Party party = getParty(uuid);
        if (party == null) {
            offlineSince.remove(uuid);
            return;
        }
        Long previous = offlineSince.putIfAbsent(uuid, System.currentTimeMillis());
        if (previous != null) {
            return;
        }
        boolean wasLeader = uuid.equals(party.leader);
        String formattedName = formatNameWithRank(uuid, player.getUsername());
        broadcastFramedToParty(party, disconnectMessage(formattedName, wasLeader), uuid);
    }

    public void expireOfflineMembers() {
        long now = System.currentTimeMillis();
        List<UUID> candidates = new ArrayList<UUID>();
        for (Map.Entry<UUID, Long> entry : offlineSince.entrySet()) {
            UUID memberId = entry.getKey();
            if (memberId == null) {
                continue;
            }
            if (!isInParty(memberId)) {
                offlineSince.remove(memberId);
                continue;
            }
            if (proxy != null && proxy.getPlayer(memberId).isPresent()) {
                offlineSince.remove(memberId);
                continue;
            }
            Long leftAt = entry.getValue();
            if (leftAt == null || now - leftAt.longValue() >= OFFLINE_REMOVAL_DELAY_MILLIS) {
                candidates.add(memberId);
            }
        }
        for (UUID memberId : candidates) {
            if (memberId == null) {
                continue;
            }
            if (proxy != null && proxy.getPlayer(memberId).isPresent()) {
                offlineSince.remove(memberId);
                continue;
            }
            Party partyBefore = getParty(memberId);
            boolean wasModerator = partyBefore != null && partyBefore.getModerators().contains(memberId);
            LeaveResult result = leave(memberId);
            offlineSince.remove(memberId);
            if (partyBefore == null || result == LeaveResult.NOT_IN_PARTY) {
                continue;
            }
            String formattedName = formatNameWithRank(memberId, getName(memberId));
            if (result == LeaveResult.DISBANDED) {
                Component disbanded = Component.text("The party was disbanded because ", NamedTextColor.YELLOW)
                        .append(LEGACY.deserialize(formattedName))
                        .append(Component.text(" was offline for too long.", NamedTextColor.YELLOW));
                for (UUID other : partyBefore.getMembers()) {
                    if (other == null || other.equals(memberId)) {
                        continue;
                    }
                    proxy.getPlayer(other).ifPresent(player -> sendFramed(player, disbanded));
                }
                continue;
            }
            Component removed;
            if (wasModerator) {
                removed = LEGACY.deserialize(formattedName)
                        .append(Component.text(
                                " was removed from the party for being offline and lost Party Moderator.",
                                NamedTextColor.YELLOW
                        ));
            } else {
                removed = LEGACY.deserialize(formattedName)
                        .append(Component.text(" was removed from the party for being offline.", NamedTextColor.YELLOW));
            }
            for (UUID other : partyBefore.getMembers()) {
                if (other == null || other.equals(memberId)) {
                    continue;
                }
                proxy.getPlayer(other).ifPresent(player -> sendFramed(player, removed));
            }
        }
    }

    public boolean isInParty(UUID member) {
        return memberToLeader.containsKey(member);
    }

    public Party getParty(UUID member) {
        UUID leader = memberToLeader.get(member);
        return leader == null ? null : parties.get(leader);
    }

    public UUID getLeader(UUID member) {
        Party party = getParty(member);
        return party == null ? null : party.leader;
    }

    public boolean isLeader(UUID member) {
        return parties.containsKey(member);
    }

    public boolean isModerator(UUID member) {
        Party party = getParty(member);
        return party != null && party.moderators.contains(member);
    }

    public void authorizeGameJoin(UUID member, String serverName) {
        if (member == null || serverName == null || serverName.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupExpiredGameJoinAuthorizations(now);
        authorizedGameJoins.put(member, new AuthorizedGameJoin(serverName, now + LEADER_WARP_AUTHORIZATION_MILLIS));
    }

    public void authorizeAnyGameJoin(UUID member) {
        if (member == null) {
            return;
        }
        long now = System.currentTimeMillis();
        cleanupExpiredGameJoinAuthorizations(now);
        authorizedGameJoins.put(member, new AuthorizedGameJoin(ANY_GAME_JOIN_TARGET, now + LEADER_WARP_AUTHORIZATION_MILLIS));
    }

    public boolean consumeAuthorizedGameJoin(UUID member, String serverName) {
        if (member == null || serverName == null || serverName.trim().isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        AuthorizedGameJoin authorization = authorizedGameJoins.get(member);
        if (authorization == null) {
            cleanupExpiredGameJoinAuthorizations(now);
            return false;
        }
        if (authorization.expiresAt <= now) {
            authorizedGameJoins.remove(member, authorization);
            return false;
        }
        if (!ANY_GAME_JOIN_TARGET.equals(authorization.serverName)
                && !authorization.serverName.equalsIgnoreCase(serverName)) {
            return false;
        }
        return authorizedGameJoins.remove(member, authorization);
    }

    public boolean canInvite(UUID member) {
        Party party = getParty(member);
        if (party == null) {
            return true;
        }
        return canInvite(member, party);
    }

    public boolean isAllInviteEnabled(UUID member) {
        Party party = getParty(member);
        return party != null && party.allInviteEnabled;
    }

    public Boolean toggleAllInvite(UUID member) {
        Party party = getParty(member);
        if (party == null || !party.leader.equals(member)) {
            return null;
        }
        party.allInviteEnabled = !party.allInviteEnabled;
        return Boolean.valueOf(party.allInviteEnabled);
    }

    public Boolean setAllInvite(UUID member, boolean enabled) {
        Party party = getParty(member);
        if (party == null || !party.leader.equals(member)) {
            return null;
        }
        party.allInviteEnabled = enabled;
        return Boolean.valueOf(enabled);
    }

    public boolean isPartyChatMuted(UUID member) {
        Party party = getParty(member);
        return party != null && party.partyChatMuted;
    }

    public Boolean togglePartyChatMuted(UUID member) {
        Party party = getParty(member);
        if (party == null || !party.leader.equals(member)) {
            return null;
        }
        party.partyChatMuted = !party.partyChatMuted;
        return Boolean.valueOf(party.partyChatMuted);
    }

    public Boolean setPartyChatMuted(UUID member, boolean muted) {
        Party party = getParty(member);
        if (party == null || !party.leader.equals(member)) {
            return null;
        }
        party.partyChatMuted = muted;
        return Boolean.valueOf(muted);
    }

    public String getName(UUID uuid) {
        return lastKnownName.getOrDefault(uuid, "Unknown");
    }

    public String formatNameWithRank(UUID uuid, String fallbackName) {
        String fallback = fallbackName == null ? "Unknown" : fallbackName;
        if (uuid == null || rankResolver == null) {
            return fallback;
        }
        try {
            return rankResolver.formatNameWithRank(uuid, fallback);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public void rememberName(UUID uuid, String name) {
        if (uuid == null || name == null || name.trim().isEmpty()) {
            return;
        }
        lastKnownName.put(uuid, name);
    }

    public UUID resolveByName(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<UUID, String> entry : lastKnownName.entrySet()) {
            if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean invite(UUID leader, UUID target) {
        if (leader == null || target == null || leader.equals(target)) {
            return false;
        }
        Party party = getParty(leader);
        if (party == null) {
            party = createParty(leader);
        }
        if (!canInvite(leader, party)) {
            return false;
        }
        if (party.members.contains(target)) {
            return false;
        }
        if (memberToLeader.containsKey(target)) {
            return false;
        }
        Map<UUID, Long> invites = pendingInvites.computeIfAbsent(target, key -> new ConcurrentHashMap<UUID, Long>());
        return invites.putIfAbsent(party.leader, Long.valueOf(System.currentTimeMillis())) == null;
    }

    public boolean acceptInvite(UUID target, UUID leader) {
        if (target == null || leader == null) {
            return false;
        }
        Map<UUID, Long> invites = pendingInvites.get(target);
        if (invites == null) {
            return false;
        }
        Long createdAt = invites.get(leader);
        if (createdAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - createdAt.longValue() >= INVITE_EXPIRY_MILLIS) {
            removeInvite(target, leader);
            return false;
        }
        if (!removeInvite(target, leader)) {
            return false;
        }
        Party party = parties.get(leader);
        if (party == null) {
            return false;
        }
        if (memberToLeader.containsKey(target)) {
            return false;
        }
        party.members.add(target);
        memberToLeader.put(target, leader);
        offlineSince.remove(target);
        return true;
    }

    public boolean denyInvite(UUID target, UUID leader) {
        return removeInvite(target, leader);
    }

    public void expirePendingInvites() {
        long now = System.currentTimeMillis();
        List<ExpiredInvite> expired = new ArrayList<ExpiredInvite>();
        for (Map.Entry<UUID, Map<UUID, Long>> targetEntry : pendingInvites.entrySet()) {
            UUID targetId = targetEntry.getKey();
            Map<UUID, Long> invites = targetEntry.getValue();
            if (targetId == null) {
                continue;
            }
            if (invites == null || invites.isEmpty()) {
                pendingInvites.remove(targetId);
                continue;
            }
            for (Map.Entry<UUID, Long> inviteEntry : invites.entrySet()) {
                UUID leaderId = inviteEntry.getKey();
                Long createdAt = inviteEntry.getValue();
                if (leaderId == null || createdAt == null) {
                    continue;
                }
                if (now - createdAt.longValue() < INVITE_EXPIRY_MILLIS) {
                    continue;
                }
                if (invites.remove(leaderId, createdAt)) {
                    expired.add(new ExpiredInvite(targetId, leaderId));
                }
            }
            if (invites.isEmpty()) {
                pendingInvites.remove(targetId, invites);
            }
        }

        if (expired.isEmpty()) {
            return;
        }

        Set<UUID> disbandCandidates = ConcurrentHashMap.newKeySet();
        for (ExpiredInvite invite : expired) {
            if (invite == null) {
                continue;
            }
            UUID targetId = invite.targetId;
            UUID leaderId = invite.leaderId;
            if (targetId == null || leaderId == null) {
                continue;
            }
            String formattedTargetName = formatNameWithRank(targetId, getName(targetId));
            String formattedLeaderName = formatNameWithRank(leaderId, getName(leaderId));

            if (proxy != null) {
                proxy.getPlayer(targetId).ifPresent(player ->
                        sendFramed(player, Component.text("The party invite from ", NamedTextColor.YELLOW)
                                .append(LEGACY.deserialize(formattedLeaderName))
                                .append(Component.text(" has expired.", NamedTextColor.YELLOW)))
                );
                proxy.getPlayer(leaderId).ifPresent(player ->
                        sendFramed(player, Component.text("The party invite to ", NamedTextColor.YELLOW)
                                .append(LEGACY.deserialize(formattedTargetName))
                                .append(Component.text(" has expired.", NamedTextColor.YELLOW)))
                );
            }
            disbandCandidates.add(leaderId);
        }

        for (UUID leaderId : disbandCandidates) {
            if (leaderId == null) {
                continue;
            }
            Party party = parties.get(leaderId);
            if (party == null || !leaderId.equals(party.leader)) {
                continue;
            }
            if (party.members.size() > 1) {
                continue;
            }
            if (hasPendingInvitesFromLeader(leaderId)) {
                continue;
            }
            disband(party);
            if (proxy != null) {
                proxy.getPlayer(leaderId).ifPresent(player ->
                        sendFramed(player, Component.text(
                                "The party was disbanded because all invites expired and the party was empty.",
                                NamedTextColor.RED
                        ))
                );
            }
        }
    }

    public LeaveResult leave(UUID member) {
        UUID leader = memberToLeader.get(member);
        if (leader == null) {
            return LeaveResult.NOT_IN_PARTY;
        }
        authorizedGameJoins.remove(member);
        offlineSince.remove(member);
        Party party = parties.get(leader);
        if (party == null) {
            if (memberToLeader.remove(member) != null) {
                notifyMemberRemovedFromParty(member);
            }
            return LeaveResult.LEFT;
        }
        if (leader.equals(member)) {
            disband(party);
            return LeaveResult.DISBANDED;
        }
        party.members.remove(member);
        party.moderators.remove(member);
        memberToLeader.remove(member);
        notifyMemberRemovedFromParty(member);
        return LeaveResult.LEFT;
    }

    public KickResult kick(UUID actor, UUID target) {
        if (actor == null || target == null) {
            return KickResult.TARGET_NOT_IN_PARTY;
        }
        Party party = getParty(actor);
        if (party == null) {
            return KickResult.NOT_IN_PARTY;
        }
        boolean actorIsLeader = party.leader.equals(actor);
        boolean actorIsModerator = party.moderators.contains(actor);
        if (!actorIsLeader && !actorIsModerator) {
            return KickResult.NOT_LEADER;
        }
        if (actor.equals(target)) {
            return KickResult.CANNOT_KICK_SELF;
        }
        if (party.leader.equals(target)) {
            return KickResult.CANNOT_KICK_LEADER;
        }
        if (!party.members.contains(target)) {
            return KickResult.TARGET_NOT_IN_PARTY;
        }
        party.members.remove(target);
        party.moderators.remove(target);
        memberToLeader.remove(target);
        offlineSince.remove(target);
        notifyMemberRemovedFromParty(target);
        return KickResult.KICKED;
    }

    public PromoteResult promote(UUID actor, UUID target) {
        if (actor == null || target == null) {
            return PromoteResult.TARGET_NOT_IN_PARTY;
        }
        Party party = getParty(actor);
        if (party == null) {
            return PromoteResult.NOT_IN_PARTY;
        }
        if (!party.leader.equals(actor)) {
            return PromoteResult.NOT_LEADER;
        }
        if (actor.equals(target)) {
            return PromoteResult.CANNOT_TARGET_SELF;
        }
        if (!party.members.contains(target)) {
            return PromoteResult.TARGET_NOT_IN_PARTY;
        }
        if (party.leader.equals(target)) {
            return PromoteResult.ALREADY_LEADER;
        }
        if (party.moderators.contains(target)) {
            return transferLeaderInternal(party, actor, target)
                    ? PromoteResult.TRANSFERRED_LEADER
                    : PromoteResult.TARGET_NOT_IN_PARTY;
        }
        party.moderators.add(target);
        return PromoteResult.PROMOTED_TO_MODERATOR;
    }

    public DemoteResult demote(UUID actor, UUID target) {
        if (actor == null || target == null) {
            return DemoteResult.TARGET_NOT_IN_PARTY;
        }
        Party party = getParty(actor);
        if (party == null) {
            return DemoteResult.NOT_IN_PARTY;
        }
        if (!party.leader.equals(actor)) {
            return DemoteResult.NOT_LEADER;
        }
        if (actor.equals(target)) {
            return DemoteResult.CANNOT_TARGET_SELF;
        }
        if (!party.members.contains(target)) {
            return DemoteResult.TARGET_NOT_IN_PARTY;
        }
        if (party.leader.equals(target)) {
            return DemoteResult.CANNOT_DEMOTE_LEADER;
        }
        if (!party.moderators.remove(target)) {
            return DemoteResult.ALREADY_MEMBER;
        }
        return DemoteResult.DEMOTED_TO_MEMBER;
    }

    public TransferResult transfer(UUID actor, UUID target) {
        if (actor == null || target == null) {
            return TransferResult.TARGET_NOT_IN_PARTY;
        }
        Party party = getParty(actor);
        if (party == null) {
            return TransferResult.NOT_IN_PARTY;
        }
        if (!party.leader.equals(actor)) {
            return TransferResult.NOT_LEADER;
        }
        if (actor.equals(target)) {
            return TransferResult.CANNOT_TARGET_SELF;
        }
        if (!party.members.contains(target)) {
            return TransferResult.TARGET_NOT_IN_PARTY;
        }
        if (party.leader.equals(target)) {
            return TransferResult.ALREADY_LEADER;
        }
        return transferLeaderInternal(party, actor, target)
                ? TransferResult.TRANSFERRED
                : TransferResult.TARGET_NOT_IN_PARTY;
    }

    public HijackResult hijack(UUID actor, UUID targetMember) {
        if (actor == null || targetMember == null) {
            return HijackResult.TARGET_NOT_IN_PARTY;
        }
        Party targetParty = getParty(targetMember);
        if (targetParty == null) {
            return HijackResult.TARGET_NOT_IN_PARTY;
        }
        if (actor.equals(targetParty.leader)) {
            return HijackResult.ALREADY_LEADER;
        }

        Party actorParty = getParty(actor);
        if (actorParty != null && actorParty != targetParty) {
            leave(actor);
        }

        targetParty = getParty(targetMember);
        if (targetParty == null) {
            return HijackResult.TARGET_NOT_IN_PARTY;
        }

        UUID oldLeader = targetParty.leader;
        if (oldLeader != null && oldLeader.equals(actor)) {
            return HijackResult.ALREADY_LEADER;
        }

        targetParty.members.add(actor);
        targetParty.moderators.remove(actor);
        if (oldLeader != null) {
            targetParty.moderators.remove(oldLeader);
        }

        targetParty.leader = actor;
        if (oldLeader != null) {
            parties.remove(oldLeader);
        }
        parties.put(actor, targetParty);
        for (UUID member : targetParty.members) {
            memberToLeader.put(member, actor);
        }
        offlineSince.remove(actor);
        remapPendingInviteLeader(oldLeader, actor);
        remapActivePollLeader(oldLeader, actor);
        return HijackResult.HIJACKED;
    }

    public int kickOfflineMembers(UUID actor) {
        KickOfflineResult result = kickOfflineMembersDetailed(actor);
        if (result.status != KickOfflineStatus.REMOVED) {
            if (result.status == KickOfflineStatus.NOT_LEADER) {
                return -1;
            }
            return 0;
        }
        return result.removedMembers.size();
    }

    public KickOfflineResult kickOfflineMembersDetailed(UUID actor) {
        Party party = getParty(actor);
        if (party == null) {
            return new KickOfflineResult(KickOfflineStatus.NOT_IN_PARTY, Collections.emptyList(), false);
        }
        if (!party.leader.equals(actor)) {
            return new KickOfflineResult(KickOfflineStatus.NOT_LEADER, Collections.emptyList(), false);
        }
        List<UUID> toRemove = new ArrayList<UUID>();
        for (UUID member : party.members) {
            if (member == null || member.equals(actor)) {
                continue;
            }
            boolean online = proxy != null && proxy.getPlayer(member).isPresent();
            if (!online) {
                toRemove.add(member);
            }
        }
        if (toRemove.isEmpty()) {
            return new KickOfflineResult(KickOfflineStatus.NO_OFFLINE_MEMBERS, Collections.emptyList(), false);
        }
        for (UUID member : toRemove) {
            party.members.remove(member);
            party.moderators.remove(member);
            memberToLeader.remove(member);
            offlineSince.remove(member);
            notifyMemberRemovedFromParty(member);
        }
        boolean disbandedBecauseEmpty = false;
        if (party.members.size() <= 1 && !hasPendingInvitesFromLeader(actor)) {
            disband(party);
            disbandedBecauseEmpty = true;
        }
        return new KickOfflineResult(
                KickOfflineStatus.REMOVED,
                Collections.unmodifiableList(new ArrayList<UUID>(toRemove)),
                disbandedBecauseEmpty
        );
    }

    public void sendPartyMessage(UUID sender, Component message) {
        sendPartyMessage(sender, message, null);
    }

    public void sendPartyMessage(UUID sender, Component message, Predicate<UUID> recipientFilter) {
        Party party = getParty(sender);
        if (party == null) {
            return;
        }
        for (UUID member : party.members) {
            if (member == null) {
                continue;
            }
            if (recipientFilter != null && !recipientFilter.test(member)) {
                continue;
            }
            proxy.getPlayer(member).ifPresent(player -> player.sendMessage(message));
        }
    }

    public PollCreateResult createPoll(UUID actor, String question, List<String> options) {
        Party party = getParty(actor);
        if (party == null) {
            return PollCreateResult.NOT_IN_PARTY;
        }
        if (!party.leader.equals(actor)) {
            return PollCreateResult.NOT_LEADER;
        }
        if (question == null || question.trim().isEmpty() || options == null || options.isEmpty()) {
            return PollCreateResult.INVALID_POLL;
        }
        List<String> cleanOptions = new ArrayList<String>();
        for (String option : options) {
            String clean = option == null ? "" : option.trim();
            if (!clean.isEmpty()) {
                cleanOptions.add(clean);
            }
        }
        if (cleanOptions.size() < 2 || cleanOptions.size() > 4) {
            return PollCreateResult.INVALID_POLL;
        }
        long now = System.currentTimeMillis();
        PartyPoll poll = new PartyPoll(
                party.leader,
                actor,
                question.trim(),
                cleanOptions,
                now,
                now + POLL_DURATION_MILLIS
        );
        if (activePolls.putIfAbsent(party.leader, poll) != null) {
            return PollCreateResult.ALREADY_ACTIVE;
        }
        broadcastFramedToParty(party, renderPollSnapshot(poll, true, POLL_DURATION_SECONDS), null);
        return PollCreateResult.CREATED;
    }

    public boolean hasActivePoll(UUID memberId) {
        Party party = getParty(memberId);
        if (party == null) {
            return false;
        }
        PartyPoll poll = activePolls.get(party.leader);
        if (poll == null) {
            return false;
        }
        if (System.currentTimeMillis() >= poll.expiresAtMillis) {
            activePolls.remove(party.leader, poll);
            return false;
        }
        return true;
    }

    public PollVoteResult answerPoll(UUID actor, int optionNumber) {
        Party party = getParty(actor);
        if (party == null) {
            return PollVoteResult.NOT_IN_PARTY;
        }
        PartyPoll poll = activePolls.get(party.leader);
        if (poll == null) {
            return PollVoteResult.NO_ACTIVE_POLL;
        }
        if (System.currentTimeMillis() >= poll.expiresAtMillis) {
            activePolls.remove(party.leader, poll);
            return PollVoteResult.NO_ACTIVE_POLL;
        }
        if (optionNumber < 1 || optionNumber > poll.options.size()) {
            return PollVoteResult.INVALID_OPTION;
        }
        poll.votes.put(actor, Integer.valueOf(optionNumber - 1));
        return PollVoteResult.VOTED;
    }

    public void tickPolls() {
        if (activePolls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, PartyPoll> entry : activePolls.entrySet()) {
            UUID leaderId = entry.getKey();
            PartyPoll poll = entry.getValue();
            if (leaderId == null || poll == null) {
                continue;
            }
            Party party = parties.get(leaderId);
            if (party == null || !leaderId.equals(party.leader)) {
                activePolls.remove(leaderId, poll);
                continue;
            }
            long remainingMillis = poll.expiresAtMillis - now;
            if (remainingMillis <= 0L) {
                broadcastFramedToParty(party, renderPollSummary(poll, party), null);
                activePolls.remove(leaderId, poll);
                continue;
            }
            int remainingSeconds = (int) Math.ceil(remainingMillis / 1000.0d);
            if (poll.nextCheckpointIndex < POLL_COUNTDOWN_CHECKPOINTS.length) {
                int checkpoint = POLL_COUNTDOWN_CHECKPOINTS[poll.nextCheckpointIndex];
                if (remainingSeconds <= checkpoint) {
                    broadcastFramedToParty(party, renderPollSnapshot(poll, false, checkpoint), null);
                    poll.nextCheckpointIndex++;
                }
            }
        }
    }

    public Set<UUID> getInvites(UUID target) {
        Map<UUID, Long> invites = pendingInvites.get(target);
        if (invites == null || invites.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(invites.keySet());
    }

    public void notifyPartyStatus(UUID memberId, String name, boolean joined) {
        notifyPartyStatus(memberId, joined);
    }

    public void notifyPartyStatus(UUID memberId, boolean joined) {
        Party party = getParty(memberId);
        if (party == null) {
            return;
        }
        Component message = Components.partyStatus(
                formatNameWithRank(memberId, getName(memberId)),
                joined
        );
        for (UUID member : party.members) {
            if (!member.equals(memberId)) {
                proxy.getPlayer(member).ifPresent(player -> sendFramed(player, message));
            }
        }
    }

    public Set<UUID> getMembers(UUID member) {
        Party party = getParty(member);
        if (party == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(party.members);
    }

    public int getPartySize(UUID member) {
        Party party = getParty(member);
        if (party == null) {
            return 0;
        }
        return party.members.size();
    }

    public String getRoleLabel(UUID member) {
        Party party = getParty(member);
        if (party == null || member == null) {
            return "MEMBER";
        }
        if (party.leader.equals(member)) {
            return "LEADER";
        }
        if (party.moderators.contains(member)) {
            return "MODERATOR";
        }
        return "MEMBER";
    }

    private Party createParty(UUID leader) {
        Party party = new Party(leader);
        party.members.add(leader);
        parties.put(leader, party);
        memberToLeader.put(leader, leader);
        offlineSince.remove(leader);
        return party;
    }

    private boolean canInvite(UUID member, Party party) {
        if (member == null || party == null) {
            return false;
        }
        if (party.leader.equals(member)) {
            return true;
        }
        if (party.moderators.contains(member)) {
            return true;
        }
        return party.allInviteEnabled && party.members.contains(member);
    }

    private boolean removeInvite(UUID target, UUID leader) {
        Map<UUID, Long> invites = pendingInvites.get(target);
        if (invites == null || invites.remove(leader) == null) {
            return false;
        }
        if (invites.isEmpty()) {
            pendingInvites.remove(target);
        }
        return true;
    }

    private boolean hasPendingInvitesFromLeader(UUID leaderId) {
        if (leaderId == null) {
            return false;
        }
        for (Map<UUID, Long> invites : pendingInvites.values()) {
            if (invites != null && invites.containsKey(leaderId)) {
                return true;
            }
        }
        return false;
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
        player.sendMessage(PartyComponents.longSeparator());
        for (Component line : lines) {
            player.sendMessage(line == null ? Component.empty() : line);
        }
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void broadcastFramedToParty(Party party, Component line, UUID excludedMember) {
        List<Component> lines = new ArrayList<Component>(1);
        lines.add(line == null ? Component.empty() : line);
        broadcastFramedToParty(party, lines, excludedMember);
    }

    private void broadcastFramedToParty(Party party, List<Component> lines, UUID excludedMember) {
        if (party == null || proxy == null) {
            return;
        }
        for (UUID member : party.members) {
            if (member == null || member.equals(excludedMember)) {
                continue;
            }
            proxy.getPlayer(member).ifPresent(player -> sendFramed(player, lines));
        }
    }

    private List<Component> renderPollSnapshot(PartyPoll poll, boolean includeHeader, int secondsLeft) {
        List<Component> lines = new ArrayList<Component>();
        if (includeHeader) {
            lines.add(LEGACY.deserialize(formatNameWithRank(poll.creatorId, getName(poll.creatorId)))
                    .append(Component.text(" created a poll! Answer it below by clicking on an option", NamedTextColor.YELLOW)));
        }
        lines.add(Component.text("Question: ", NamedTextColor.YELLOW)
                .append(Component.text(poll.question, NamedTextColor.GREEN)));
        for (int i = 0; i < poll.options.size(); i++) {
            int optionNumber = i + 1;
            String option = poll.options.get(i);
            Component optionLine = Component.text("  - " + optionNumber + ". ", NamedTextColor.YELLOW)
                    .append(Component.text(option, NamedTextColor.GOLD))
                    .clickEvent(ClickEvent.runCommand("/party answer " + optionNumber))
                    .hoverEvent(HoverEvent.showText(Component.text(
                            "Click to choose this option! (/party answer " + optionNumber + ")",
                            NamedTextColor.GRAY
                    )));
            lines.add(optionLine);
        }
        lines.add(Component.text("The poll will end in ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(Math.max(0, secondsLeft)), NamedTextColor.GREEN))
                .append(Component.text(" seconds!", NamedTextColor.YELLOW)));
        return lines;
    }

    private List<Component> renderPollSummary(PartyPoll poll, Party party) {
        List<Component> lines = new ArrayList<Component>();
        lines.add(Component.text("Question: ", NamedTextColor.YELLOW)
                .append(Component.text(poll.question, NamedTextColor.GREEN)));
        int totalVotes = countVotesInParty(poll, party, -1);
        for (int i = 0; i < poll.options.size(); i++) {
            int votes = countVotesInParty(poll, party, i);
            double percentage = totalVotes <= 0 ? 0.0d : (votes * 100.0d) / totalVotes;
            String voteLabel = votes == 1 ? " Vote " : " Votes ";
            int filled = totalVotes <= 0 ? 0 : (int) Math.round((votes * POLL_BAR_SQUARES) / (double) totalVotes);
            if (filled < 0) {
                filled = 0;
            } else if (filled > POLL_BAR_SQUARES) {
                filled = POLL_BAR_SQUARES;
            }
            Component line = Component.text(poll.options.get(i), NamedTextColor.GOLD)
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(votes + voteLabel, NamedTextColor.YELLOW))
                    .append(Component.text("(" + String.format(Locale.US, "%.1f", percentage) + "%) ", NamedTextColor.YELLOW))
                    .append(pollBar(filled));
            lines.add(line);
        }
        return lines;
    }

    private int countVotesInParty(PartyPoll poll, Party party, int optionIndex) {
        int count = 0;
        if (poll == null || party == null) {
            return 0;
        }
        for (Map.Entry<UUID, Integer> vote : poll.votes.entrySet()) {
            UUID voterId = vote.getKey();
            Integer selection = vote.getValue();
            if (voterId == null || selection == null || !party.members.contains(voterId)) {
                continue;
            }
            if (optionIndex < 0 || optionIndex == selection.intValue()) {
                count++;
            }
        }
        return count;
    }

    private Component pollBar(int filled) {
        int safeFilled = Math.max(0, Math.min(POLL_BAR_SQUARES, filled));
        int emptyAmount = POLL_BAR_SQUARES - safeFilled;
        String square = String.valueOf('■');
        String green = square.repeat(safeFilled);
        String gray = square.repeat(emptyAmount);
        return Component.text("[", NamedTextColor.GOLD)
                .append(Component.text(green, NamedTextColor.GREEN))
                .append(Component.text(gray, NamedTextColor.GRAY))
                .append(Component.text("]", NamedTextColor.GOLD));
    }

    private Component disconnectMessage(String formattedName, boolean leader) {
        if (leader) {
            return Component.text("The party leader, ", NamedTextColor.YELLOW)
                    .append(LEGACY.deserialize(formattedName))
                    .append(Component.text(" has disconnected, they have ", NamedTextColor.YELLOW))
                    .append(Component.text(String.valueOf(OFFLINE_REMOVAL_DELAY_MINUTES), NamedTextColor.RED))
                    .append(Component.text(" minutes to rejoin before the party is disbanded.", NamedTextColor.YELLOW));
        }
        return LEGACY.deserialize(formattedName)
                .append(Component.text(" has disconnected, they have ", NamedTextColor.YELLOW))
                .append(Component.text(String.valueOf(OFFLINE_REMOVAL_DELAY_MINUTES), NamedTextColor.RED))
                .append(Component.text(" minutes to rejoin before they are removed from the party.", NamedTextColor.YELLOW));
    }

    private Component rejoinedMessage(String formattedName, boolean leader) {
        if (leader) {
            return Component.text("The party leader ", NamedTextColor.YELLOW)
                    .append(LEGACY.deserialize(formattedName))
                    .append(Component.text(" has rejoined!", NamedTextColor.YELLOW));
        }
        return LEGACY.deserialize(formattedName)
                .append(Component.text(" has rejoined.", NamedTextColor.YELLOW));
    }

    private void disband(Party party) {
        if (party == null) {
            return;
        }
        for (UUID member : party.members) {
            memberToLeader.remove(member);
            offlineSince.remove(member);
            authorizedGameJoins.remove(member);
            notifyMemberRemovedFromParty(member);
        }
        removePendingInvitesFromLeader(party.leader);
        activePolls.remove(party.leader);
        parties.remove(party.leader);
    }

    private void notifyMemberRemovedFromParty(UUID member) {
        if (member == null) {
            return;
        }
        Consumer<UUID> listener = memberRemovedFromPartyListener;
        if (listener == null) {
            return;
        }
        try {
            listener.accept(member);
        } catch (Throwable ignored) {
        }
    }

    private void cleanupExpiredGameJoinAuthorizations(long now) {
        for (Map.Entry<UUID, AuthorizedGameJoin> entry : authorizedGameJoins.entrySet()) {
            UUID memberId = entry.getKey();
            AuthorizedGameJoin authorization = entry.getValue();
            if (authorization != null && authorization.expiresAt <= now) {
                authorizedGameJoins.remove(memberId, authorization);
            }
        }
    }

    private boolean transferLeaderInternal(Party party, UUID oldLeader, UUID newLeader) {
        if (party == null || oldLeader == null || newLeader == null) {
            return false;
        }
        if (!oldLeader.equals(party.leader)) {
            return false;
        }
        if (!party.members.contains(newLeader)) {
            return false;
        }
        party.moderators.remove(newLeader);
        party.moderators.add(oldLeader);
        party.leader = newLeader;
        parties.remove(oldLeader);
        parties.put(newLeader, party);
        for (UUID member : party.members) {
            memberToLeader.put(member, newLeader);
        }
        remapPendingInviteLeader(oldLeader, newLeader);
        remapActivePollLeader(oldLeader, newLeader);
        return true;
    }

    private void remapPendingInviteLeader(UUID oldLeader, UUID newLeader) {
        if (oldLeader == null || newLeader == null || oldLeader.equals(newLeader)) {
            return;
        }
        for (Map<UUID, Long> invites : pendingInvites.values()) {
            if (invites == null) {
                continue;
            }
            Long previousTime = invites.remove(oldLeader);
            if (previousTime == null) {
                continue;
            }
            invites.putIfAbsent(newLeader, previousTime);
        }
    }

    private void remapActivePollLeader(UUID oldLeader, UUID newLeader) {
        if (oldLeader == null || newLeader == null || oldLeader.equals(newLeader)) {
            return;
        }
        PartyPoll poll = activePolls.remove(oldLeader);
        if (poll == null) {
            return;
        }
        poll.leaderId = newLeader;
        activePolls.put(newLeader, poll);
    }

    private void removePendingInvitesFromLeader(UUID leaderId) {
        if (leaderId == null) {
            return;
        }
        for (Map.Entry<UUID, Map<UUID, Long>> entry : pendingInvites.entrySet()) {
            UUID targetId = entry.getKey();
            Map<UUID, Long> invites = entry.getValue();
            if (targetId == null || invites == null) {
                continue;
            }
            invites.remove(leaderId);
            if (invites.isEmpty()) {
                pendingInvites.remove(targetId, invites);
            }
        }
    }

    private static final class PartyPoll {
        private volatile UUID leaderId;
        private final UUID creatorId;
        private final String question;
        private final List<String> options;
        private final Map<UUID, Integer> votes = new ConcurrentHashMap<UUID, Integer>();
        private final long createdAtMillis;
        private final long expiresAtMillis;
        private volatile int nextCheckpointIndex;

        private PartyPoll(UUID leaderId,
                          UUID creatorId,
                          String question,
                          List<String> options,
                          long createdAtMillis,
                          long expiresAtMillis) {
            this.leaderId = leaderId;
            this.creatorId = creatorId;
            this.question = question == null ? "" : question;
            this.options = Collections.unmodifiableList(new ArrayList<String>(options));
            this.createdAtMillis = createdAtMillis;
            this.expiresAtMillis = expiresAtMillis;
            this.nextCheckpointIndex = 0;
        }
    }

    private static final class AuthorizedGameJoin {
        private final String serverName;
        private final long expiresAt;

        private AuthorizedGameJoin(String serverName, long expiresAt) {
            this.serverName = serverName == null ? "" : serverName;
            this.expiresAt = expiresAt;
        }
    }

    private static final class ExpiredInvite {
        private final UUID targetId;
        private final UUID leaderId;

        private ExpiredInvite(UUID targetId, UUID leaderId) {
            this.targetId = targetId;
            this.leaderId = leaderId;
        }
    }

    public static final class KickOfflineResult {
        private final KickOfflineStatus status;
        private final List<UUID> removedMembers;
        private final boolean disbandedBecauseEmpty;

        private KickOfflineResult(KickOfflineStatus status, List<UUID> removedMembers, boolean disbandedBecauseEmpty) {
            this.status = status;
            this.removedMembers = removedMembers == null ? Collections.emptyList() : removedMembers;
            this.disbandedBecauseEmpty = disbandedBecauseEmpty;
        }

        public KickOfflineStatus getStatus() {
            return status;
        }

        public List<UUID> getRemovedMembers() {
            return removedMembers;
        }

        public boolean isDisbandedBecauseEmpty() {
            return disbandedBecauseEmpty;
        }
    }

    public static class Party {
        private volatile UUID leader;
        private final Set<UUID> members = ConcurrentHashMap.newKeySet();
        private final Set<UUID> moderators = ConcurrentHashMap.newKeySet();
        private volatile boolean allInviteEnabled;
        private volatile boolean partyChatMuted;

        private Party(UUID leader) {
            this.leader = leader;
        }

        public UUID getLeader() {
            return leader;
        }

        public Set<UUID> getMembers() {
            return Collections.unmodifiableSet(members);
        }

        public Set<UUID> getModerators() {
            return Collections.unmodifiableSet(moderators);
        }

        public boolean isAllInviteEnabled() {
            return allInviteEnabled;
        }

        public boolean isPartyChatMuted() {
            return partyChatMuted;
        }
    }
}
