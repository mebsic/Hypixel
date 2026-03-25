package io.github.mebsic.proxy.command;

import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.proxy.service.BlockService;
import io.github.mebsic.proxy.service.FriendService;
import io.github.mebsic.proxy.util.Components;
import io.github.mebsic.proxy.util.FriendComponents;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FriendCommand implements SimpleCommand {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final int MAX_FRIEND_NICKNAME_LENGTH = 16;
    private static final int LIST_PAGE_SIZE = 10;
    private static final int REQUESTS_PAGE_SIZE = 10;
    private static final long NOTIFICATION_TOGGLE_COOLDOWN_MILLIS = 5_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE;
    private static final long MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR;
    private static final long MILLIS_PER_MONTH = 30L * MILLIS_PER_DAY;
    private static final String NOTIFICATION_TOGGLE_COOLDOWN_MESSAGE = "Please wait before doing that again!";
    private static final String PROFILE_DISABLED_MESSAGE = "Profiles are currently disabled!";
    private static final Component COMMAND_SUGGEST_HOVER = Component.text("Click to put the command in chat.", NamedTextColor.GRAY);

    private final ProxyServer proxy;
    private final FriendService friends;
    private final BlockService blocks;
    private final String commandRoot;
    private final Map<UUID, Long> notificationToggleCooldown = new ConcurrentHashMap<>();
    private final Set<UUID> removeAllConfirmations = ConcurrentHashMap.newKeySet();

    public FriendCommand(ProxyServer proxy, FriendService friends, BlockService blocks) {
        this(proxy, friends, blocks, "friend");
    }

    public FriendCommand(ProxyServer proxy, FriendService friends, BlockService blocks, String commandRoot) {
        this.proxy = proxy;
        this.friends = friends;
        this.blocks = blocks;
        String safeRoot = commandRoot == null ? "" : commandRoot.trim().toLowerCase(Locale.ROOT);
        this.commandRoot = safeRoot.isEmpty() ? "friend" : safeRoot;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
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
            case "add":
            case "request":
                handleRequest(player, args, 1, cmd("add <player>"));
                return;
            case "accept":
                handleAccept(player, args);
                return;
            case "deny":
                handleDeny(player, args);
                return;
            case "best":
                handleBest(player, args);
                return;
            case "nickname":
            case "nick":
                handleNickname(player, args);
                return;
            case "block":
                sendFramed(player, FriendComponents.invalidUsage("/block <player>"));
                return;
            case "unblock":
                sendFramed(player, FriendComponents.invalidUsage("/block remove <player>"));
                return;
            case "remove":
            case "delete":
            case "unfriend":
                handleRemove(player, args);
                return;
            case "removeall":
                handleRemoveAll(player, args);
                return;
            case "list":
                handleList(player, args);
                return;
            case "requests":
            case "pending":
            case "req":
                handleRequests(player, args);
                return;
            case "notifications":
            case "notification":
            case "notify":
                handleNotifications(player, args);
                return;
            case "profile":
                handleProfile(player, args);
                return;
            case "msg":
            case "message":
                handleMessage(player, args);
                return;
            default:
                if (args.length == 1) {
                    handleRequest(player, args, 0, cmd("<player>"));
                    return;
                }
                sendInvalidUsage(player, cmd("<player>"));
        }
    }

    private void handleRequest(Player player, String[] args, int targetIndex, String usage) {
        if (player == null) {
            return;
        }
        if (args.length != targetIndex + 1) {
            sendInvalidUsage(player, usage);
            return;
        }
        String targetInput = args[targetIndex];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sendFramed(player, FriendComponents.noPlayerFound(targetInput));
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            sendFramed(player, Component.text("You cannot add yourself as a friend!", NamedTextColor.RED));
            return;
        }
        String targetName = resolveName(targetId, targetInput);
        if (friends.areFriends(player.getUniqueId(), targetId)) {
            sendFramed(player, colorName(targetId, targetName)
                    .append(Component.text(" is already on your friends list!", NamedTextColor.RED)));
            return;
        }
        if (isEitherBlocked(player.getUniqueId(), targetId)) {
            sendFramed(player, Component.text("You cannot send a friend request to this player!", NamedTextColor.RED));
            return;
        }
        if (friends.hasPending(targetId, player.getUniqueId())) {
            sendFramed(player, Component.text("You've already sent a friend request to this person!", NamedTextColor.RED));
            return;
        }
        if (friends.hasPending(player.getUniqueId(), targetId)) {
            sendFramed(player,
                    Component.text("That person already invited you! Try ", NamedTextColor.RED)
                            .append(suggestCommandComponent(cmd("accept " + targetName))));
            return;
        }
        if (!friends.request(player.getUniqueId(), targetId)) {
            sendFramed(player, Component.text("Failed to send a friend request right now!", NamedTextColor.RED));
            return;
        }
        String senderFormattedName = friends.formatNameWithRank(player.getUniqueId());
        proxy.getPlayer(targetId).ifPresent(target ->
                sendFramed(target, FriendComponents.friendRequestNotificationLines(senderFormattedName, player.getUsername())));
        sendFramed(player,
                Component.text("You sent a friend request to ", NamedTextColor.YELLOW)
                        .append(LEGACY.deserialize(friends.formatNameWithRank(targetId)))
                        .append(Component.text("! They have 5 minutes to accept it!", NamedTextColor.YELLOW)));
    }

    private void handleAccept(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("accept <player>"));
            return;
        }
        String targetInput = args[1];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sendFramed(player, FriendComponents.noPlayerFound(targetInput));
            return;
        }
        if (!friends.accept(player.getUniqueId(), targetId)) {
            sendFramed(player,
                    Component.text("That person hasn't invited you to be friends! Try ", NamedTextColor.RED)
                            .append(suggestCommandComponent(cmd("requests"))));
            return;
        }
        String name = resolveName(targetId, targetInput);
        sendFramed(player,
                Component.text("You are now friends with ", NamedTextColor.GREEN)
                        .append(colorName(targetId, name)));
        Component accepter = colorName(player.getUniqueId(), player.getUsername());
        proxy.getPlayer(targetId).ifPresent(target ->
                sendFramed(target, accepter.append(Component.text(" accepted your friend request!", NamedTextColor.YELLOW)))
        );
    }

    private void handleDeny(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("deny <player>"));
            return;
        }
        String targetInput = args[1];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sendFramed(player, FriendComponents.noPlayerFound(targetInput));
            return;
        }
        if (!friends.deny(player.getUniqueId(), targetId)) {
            sendFramed(player,
                    Component.text("That person hasn't invited you to be friends! Try ", NamedTextColor.RED)
                            .append(suggestCommandComponent(cmd("requests"))));
            return;
        }
        String name = resolveName(targetId, targetInput);
        sendFramed(player,
                Component.text("Declined ", NamedTextColor.YELLOW)
                        .append(colorName(targetId, name))
                        .append(Component.text("'s friend request!", NamedTextColor.YELLOW)));
    }

    private void handleBest(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("best <player>"));
            return;
        }
        String targetInput = args[1];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sendFramed(player, FriendComponents.noPlayerFound(targetInput));
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            sendFramed(player, Component.text("You cannot mark yourself as a best friend!", NamedTextColor.RED));
            return;
        }
        if (!friends.areFriends(player.getUniqueId(), targetId)) {
            sendFramed(player, Component.text("That player is not on your friends list!", NamedTextColor.RED));
            return;
        }
        boolean enabled = friends.toggleBestFriend(player.getUniqueId(), targetId);
        Component formatted = LEGACY.deserialize(friends.formatNameWithRank(targetId));
        Component state = Component.text(
                enabled ? " is now a best friend!" : " is no longer a best friend!",
                enabled ? NamedTextColor.GREEN : NamedTextColor.YELLOW
        );
        sendFramed(player, formatted.append(state));
    }

    private void handleNickname(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length < 2) {
            sendInvalidUsage(player, cmd("nickname <player> [nickname]"));
            return;
        }
        String targetInput = args[1];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sendFramed(player, FriendComponents.noPlayerFound(targetInput));
            return;
        }
        String targetName = resolveName(targetId, targetInput);
        if (!friends.areFriends(player.getUniqueId(), targetId)) {
            sendFramed(player,
                    Component.text(targetName, NamedTextColor.GRAY)
                            .append(Component.text(" isn't on your friends list!", NamedTextColor.RED)));
            return;
        }
        String nickname = args.length >= 3 ? joinArgs(args, 2).trim() : "";
        if (nickname.isEmpty()) {
            if (!friends.setFriendNickname(player.getUniqueId(), targetId, null)) {
                sendFramed(player, Component.text("Failed to set that nickname right now!", NamedTextColor.RED));
                return;
            }
            sendFramed(player,
                    colorName(targetId, targetName)
                            .append(Component.text(" no longer has a nickname!", NamedTextColor.GREEN)));
            return;
        }
        if (nickname.length() > MAX_FRIEND_NICKNAME_LENGTH) {
            sendFramed(player, Component.text(
                    "A nickname cannot be longer than 16 characters!",
                    NamedTextColor.RED
            ));
            return;
        }
        if (!friends.setFriendNickname(player.getUniqueId(), targetId, nickname)) {
            sendFramed(player, Component.text("Failed to set that nickname right now!", NamedTextColor.RED));
            return;
        }
        sendFramed(player,
                colorName(targetId, targetName)
                        .append(Component.text(" now has the nickname ", NamedTextColor.GREEN))
                        .append(Component.text(nickname, NamedTextColor.GREEN))
                        .append(Component.text("!", NamedTextColor.GREEN)));
    }

    private void handleRemove(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("remove <player>"));
            return;
        }
        String targetInput = args[1];
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            sendFramed(player, FriendComponents.noPlayerFound(targetInput));
            return;
        }
        if (!friends.removeFriend(player.getUniqueId(), targetId)) {
            sendFramed(player, Component.text("That player is not on your friends list!", NamedTextColor.RED));
            return;
        }
        String name = resolveName(targetId, targetInput);
        sendFramed(player,
                Component.text("You removed ", NamedTextColor.YELLOW)
                        .append(colorName(targetId, name))
                        .append(Component.text(" from your friends list!", NamedTextColor.YELLOW)));
        Component remover = LEGACY.deserialize(friends.formatNameWithRank(player.getUniqueId()));
        proxy.getPlayer(targetId).ifPresent(target ->
                sendFramed(target,
                        remover.append(Component.text(" removed you from their friends list!", NamedTextColor.YELLOW))));
    }

    private void handleRemoveAll(Player player, String[] args) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (args.length == 1) {
            if (countRemovableFriends(playerId) <= 0) {
                sendFramed(player, Component.text("You don't have any friends to remove.", NamedTextColor.RED));
                return;
            }
            removeAllConfirmations.add(playerId);
            sendFramed(player, FriendComponents.removeAllConfirmationLines(
                    cmd("removeall cancel"),
                    cmd("removeall clear")));
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("removeall"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if ("cancel".equals(action)) {
            removeAllConfirmations.remove(playerId);
            sendFramed(player, Component.text("Nothing happened.", NamedTextColor.GREEN));
            return;
        }
        if ("clear".equals(action) || "confirm".equals(action)) {
            if (!removeAllConfirmations.remove(playerId)) {
                if (countRemovableFriends(playerId) <= 0) {
                    sendFramed(player, Component.text("You don't have any friends to remove.", NamedTextColor.RED));
                    return;
                }
                removeAllConfirmations.add(playerId);
                sendFramed(player, FriendComponents.removeAllConfirmationLines(
                        cmd("removeall cancel"),
                        cmd("removeall clear")));
                return;
            }
            int removed = friends.removeAllFriendsExceptBest(playerId);
            if (removed <= 0) {
                sendFramed(player, Component.text("You don't have any friends to remove.", NamedTextColor.RED));
                return;
            }
            sendFramed(player, Component.text("Removed all of your friends!", NamedTextColor.GREEN));
            return;
        }
        sendInvalidUsage(player, cmd("removeall"));
    }

    private int countRemovableFriends(UUID ownerId) {
        if (ownerId == null) {
            return 0;
        }
        Set<UUID> friendSet = friends.getFriends(ownerId);
        if (friendSet == null || friendSet.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (UUID friendId : friendSet) {
            if (friendId == null) {
                continue;
            }
            if (friends.isBestFriend(ownerId, friendId)) {
                continue;
            }
            count++;
        }
        return count;
    }

    private void handleList(Player player, String[] args) {
        if (player == null) {
            return;
        }
        boolean bestOnly = args.length >= 2 && "best".equalsIgnoreCase(args[1]);
        if (bestOnly) {
            if (args.length > 3) {
                sendInvalidUsage(player, cmd("list best <page>"));
                return;
            }
        } else if (args.length > 2) {
            sendInvalidUsage(player, cmd("list <page>"));
            return;
        }
        int pageIndex = bestOnly ? 2 : 1;
        int page = parsePage(player, args, pageIndex, bestOnly ? cmd("list best <page>") : cmd("list <page>"));
        if (page < 1) {
            return;
        }
        UUID ownerId = player.getUniqueId();
        List<UUID> ordered = sortedFriends(ownerId);
        if (bestOnly) {
            ordered.removeIf(friendId -> !friends.isBestFriend(ownerId, friendId));
        }
        if (ordered.isEmpty()) {
            if (bestOnly) {
                sendFramed(player, Component.text("You don't have any best friends yet.", NamedTextColor.YELLOW));
            } else {
                sendFramed(player,
                        Component.text("You don't have any friends yet! Add some with /friend add player", NamedTextColor.YELLOW));
            }
            return;
        }
        int totalPages = pageCount(ordered.size(), LIST_PAGE_SIZE);
        if (page > totalPages) {
            sendFramed(player, Component.text("That page does not exist.", NamedTextColor.RED));
            return;
        }
        List<Component> lines = new ArrayList<>();
        String title = bestOnly ? "Best Friends" : "Friends";
        String commandPrefix = bestOnly ? cmd("list best") : cmd("list");
        lines.add(FriendComponents.pageHeader(title, page, totalPages, commandPrefix));
        for (UUID friendId : pageSlice(ordered, page, LIST_PAGE_SIZE)) {
            lines.add(buildFriendListEntry(player, friendId));
        }
        sendFramed(player, lines);
    }

    private void handleRequests(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length > 2) {
            sendInvalidUsage(player, cmd("requests <page>"));
            return;
        }
        int page = parsePage(player, args, 1, cmd("requests <page>"));
        if (page < 1) {
            return;
        }
        UUID self = player.getUniqueId();
        List<UUID> incoming = new ArrayList<>(friends.getPendingOrdered(self));
        List<UUID> outgoing = new ArrayList<>(friends.getOutgoingPendingOrdered(self));
        if (incoming.isEmpty() && outgoing.isEmpty()) {
            List<Component> emptyPage = new ArrayList<>(1);
            emptyPage.add(FriendComponents.friendRequestsHeader(1, 1));
            sendUnframed(player, emptyPage);
            return;
        }
        List<RequestEntry> requests = new ArrayList<>(incoming.size() + outgoing.size());
        for (UUID requesterId : incoming) {
            requests.add(RequestEntry.incoming(requesterId));
        }
        for (UUID targetId : outgoing) {
            requests.add(RequestEntry.outgoing(targetId));
        }
        int totalPages = pageCount(requests.size(), REQUESTS_PAGE_SIZE);
        if (page > totalPages) {
            sendUnframed(player, Component.text("That page does not exist.", NamedTextColor.RED));
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(FriendComponents.friendRequestsHeader(page, totalPages));
        for (RequestEntry entry : requestPageSlice(requests, page, REQUESTS_PAGE_SIZE)) {
            String plainName = friends.getName(entry.otherId);
            String formattedName = friends.formatNameWithRank(entry.otherId);
            if (entry.outgoing) {
                lines.add(FriendComponents.outgoingRequestEntry(formattedName));
                continue;
            }
            lines.add(FriendComponents.incomingRequestEntry(formattedName, plainName));
        }
        sendUnframed(player, lines);
    }

    private void handleNotifications(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length != 1) {
            sendInvalidUsage(player, cmd("notifications"));
            return;
        }
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        Long previous = notificationToggleCooldown.get(playerId);
        if (previous != null && now - previous < NOTIFICATION_TOGGLE_COOLDOWN_MILLIS) {
            player.sendMessage(Component.text(NOTIFICATION_TOGGLE_COOLDOWN_MESSAGE, NamedTextColor.RED));
            return;
        }
        notificationToggleCooldown.put(playerId, now);
        boolean enabled = friends.toggleFriendJoinLeaveNotifications(playerId);
        String status = enabled
                ? "Enabled friend join/leave notifications!"
                : "Disabled friend join/leave notifications!";
        sendFramed(player, Component.text(status, NamedTextColor.YELLOW));
    }

    private void handleProfile(Player player, String[] args) {
        if (player == null) {
            return;
        }
        if (args.length != 2) {
            sendInvalidUsage(player, cmd("profile <player>"));
            return;
        }
        player.sendMessage(Component.text(PROFILE_DISABLED_MESSAGE, NamedTextColor.RED));
    }

    private void handleMessage(Player player, String[] args) {
        if (args.length < 3) {
            sendInvalidUsage(player, cmd("msg <player> <message>"));
            return;
        }
        sendFriendMessage(player, args[1], joinArgs(args, 2));
    }

    private void sendFriendMessage(Player sender, String targetName, String message) {
        UUID targetId = resolveUuid(targetName);
        if (targetId == null) {
            sendFramed(sender, FriendComponents.noPlayerFound(targetName));
            return;
        }
        if (!friends.areFriends(sender.getUniqueId(), targetId)) {
            sendFramed(sender, Component.text("You are not friends with that player.", NamedTextColor.RED));
            return;
        }
        if (isEitherBlocked(sender.getUniqueId(), targetId)) {
            sendFramed(sender, Component.text("You cannot message this player.", NamedTextColor.RED));
            return;
        }
        friends.rememberName(sender.getUniqueId(), sender.getUsername());
        proxy.getPlayer(targetId).ifPresentOrElse(target -> {
            friends.rememberName(target.getUniqueId(), target.getUsername());
            String senderDisplay = friends.formatNameWithRank(sender.getUniqueId());
            String targetDisplay = friends.formatNameWithRank(target.getUniqueId());
            sender.sendMessage(Components.friendPrivateMessage(true, targetDisplay, message));
            target.sendMessage(Components.friendPrivateMessage(false, senderDisplay, message));
        }, () -> sendFramed(sender, Component.text("That player is offline.", NamedTextColor.RED)));
    }

    private UUID resolveUuid(String name) {
        if (name == null) {
            return null;
        }
        UUID online = proxy.getPlayer(name).map(Player::getUniqueId).orElse(null);
        if (online != null) {
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

    private String resolveName(UUID uuid, String fallback) {
        if (uuid == null) {
            return fallback == null ? "Unknown" : fallback;
        }
        Optional<Player> online = proxy.getPlayer(uuid);
        if (online.isPresent()) {
            String onlineName = online.get().getUsername();
            friends.rememberName(uuid, onlineName);
            return onlineName;
        }
        String remembered = friends.getName(uuid);
        if (remembered != null && !"Unknown".equalsIgnoreCase(remembered)) {
            return remembered;
        }
        return fallback == null ? "Unknown" : fallback;
    }

    private int parsePage(Player player, String[] args, int index, String usage) {
        if (args.length <= index) {
            return 1;
        }
        try {
            int page = Integer.parseInt(args[index]);
            if (page < 1) {
                sendInvalidUsage(player, usage);
                return -1;
            }
            return page;
        } catch (NumberFormatException ex) {
            sendInvalidUsage(player, usage);
            return -1;
        }
    }

    private List<UUID> sortedFriends(UUID ownerId) {
        Set<UUID> friendSet = friends.getFriends(ownerId);
        List<UUID> ordered = new ArrayList<>(friendSet);
        Map<UUID, Boolean> visibleOnlineByFriend = new HashMap<UUID, Boolean>();
        Map<UUID, Boolean> bestByFriend = new HashMap<UUID, Boolean>();
        for (UUID friendId : ordered) {
            FriendService.FriendPresence presence = friends.getPresence(friendId);
            visibleOnlineByFriend.put(friendId, isVisibleAsOnlineInFriendList(friendId, presence));
            bestByFriend.put(friendId, friends.isBestFriend(ownerId, friendId));
        }
        ordered.sort(Comparator
                .comparing((UUID id) -> Boolean.TRUE.equals(bestByFriend.get(id)) ? 0 : 1)
                .thenComparing((UUID id) -> Boolean.TRUE.equals(visibleOnlineByFriend.get(id)) ? 0 : 1)
                .thenComparing(id -> friends.getName(id), String.CASE_INSENSITIVE_ORDER));
        return ordered;
    }

    private List<UUID> pageSlice(List<UUID> source, int page, int size) {
        int start = Math.max(0, (page - 1) * size);
        int end = Math.min(source.size(), start + size);
        return source.subList(start, end);
    }

    private List<RequestEntry> requestPageSlice(List<RequestEntry> source, int page, int size) {
        int start = Math.max(0, (page - 1) * size);
        int end = Math.min(source.size(), start + size);
        return source.subList(start, end);
    }

    private int pageCount(int totalItems, int size) {
        if (size <= 0) {
            return 1;
        }
        return Math.max(1, (totalItems + size - 1) / size);
    }

    private Component buildFriendListEntry(Player viewer, UUID friendId) {
        UUID viewerId = viewer == null ? null : viewer.getUniqueId();
        boolean bestFriend = viewerId != null && friends.isBestFriend(viewerId, friendId);
        String nickname = viewerId == null ? null : friends.getFriendNickname(viewerId, friendId);
        Component name = friendListName(friendId, nickname);
        if (bestFriend) {
            name = name.decorate(TextDecoration.BOLD);
        }
        String plainName = friends.getName(friendId);
        String profileTarget = (plainName == null || plainName.trim().isEmpty() || "Unknown".equalsIgnoreCase(plainName))
                ? friendId.toString()
                : plainName;
        FriendService.FriendPresence presence = friends.getPresence(friendId);
        boolean shownOnline = isVisibleAsOnlineInFriendList(friendId, presence);
        Component entry;
        if (!shownOnline) {
            entry = name.append(Component.text(" is currently offline", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, false));
        } else {
            String safeLocation = describeOnlineLocation(friendId, presence);
            entry = name.append(Component.text(" is in a " + safeLocation, NamedTextColor.YELLOW)
                    .decoration(TextDecoration.BOLD, false));
        }
        long friendSince = viewer == null ? 0L : friends.getFriendSince(viewer.getUniqueId(), friendId);
        return entry
                .hoverEvent(HoverEvent.showText(buildFriendListHover(friendId, plainName, presence, friendSince, shownOnline)))
                .clickEvent(ClickEvent.runCommand(cmd("profile " + profileTarget)));
    }

    private Component friendListName(UUID friendId, String nickname) {
        String plainName = friends.getName(friendId);
        String safeName = plainName == null || plainName.trim().isEmpty() ? "Unknown" : plainName;
        String formattedWithColor = friends.formatNameWithColor(friendId);
        String rankBaseColor = extractNameColorPrefix(formattedWithColor);
        if (nickname == null) {
            String legacy = (rankBaseColor == null ? "" : rankBaseColor) + safeName;
            return LEGACY.deserialize(legacy);
        }

        String nicknameLegacy = (rankBaseColor == null ? "" : rankBaseColor) + nickname;
        Component nicknameComponent = LEGACY.deserialize(
                nicknameLegacy.trim().isEmpty() ? safeName : nicknameLegacy
        );

        String starLegacy = (rankBaseColor == null ? "" : rankBaseColor) + "§l*";
        return nicknameComponent.append(LEGACY.deserialize(starLegacy));
    }

    private String extractNameColorPrefix(String formattedName) {
        if (formattedName == null || formattedName.trim().isEmpty()) {
            return "";
        }
        String plain = PLAIN.serialize(LEGACY.deserialize(formattedName));
        if (plain.isEmpty() || !formattedName.endsWith(plain)) {
            return "";
        }
        return formattedName.substring(0, formattedName.length() - plain.length());
    }

    private Component buildFriendListHover(UUID friendId,
                                           String friendName,
                                           FriendService.FriendPresence presence,
                                           long friendSince,
                                           boolean shownOnline) {
        String safeName = friendName == null || friendName.trim().isEmpty() ? "Unknown" : friendName;
        String coloredName = friendId == null ? safeName : friends.formatNameWithRank(friendId);
        Component hover = Component.empty();
        if (!shownOnline) {
            hover = hover.append(Component.text("Last seen ", NamedTextColor.GRAY))
                    .append(Component.text(formatLastSeen(presence), NamedTextColor.GRAY))
                    .append(Component.text(" ago", NamedTextColor.GRAY));
            hover = hover.append(Component.newline());
        }
        hover = hover.append(Component.text("Friends for ", NamedTextColor.GRAY))
                .append(Component.text(formatFriendDuration(friendSince), NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Click here to view ", NamedTextColor.YELLOW))
                .append(LEGACY.deserialize(coloredName == null || coloredName.trim().isEmpty() ? safeName : coloredName))
                .append(Component.text("'s profile", NamedTextColor.YELLOW));
        return hover;
    }

    private boolean isVisibleAsOnlineInFriendList(UUID friendId, FriendService.FriendPresence presence) {
        if (presence == null || !presence.isOnline()) {
            return false;
        }
        ServerType resolvedType = resolvePresenceServerType(friendId, presence);
        return resolvedType != ServerType.BUILD;
    }

    private ServerType resolvePresenceServerType(UUID friendId, FriendService.FriendPresence presence) {
        if (presence == null) {
            return ServerType.UNKNOWN;
        }
        ServerType type = presence.getServerType();
        if (type != null && type != ServerType.UNKNOWN) {
            return type;
        }
        String serverName = safeTrim(presence.getServerName());
        if (serverName.isEmpty() && proxy != null && friendId != null) {
            Optional<Player> online = proxy.getPlayer(friendId);
            if (online.isPresent()) {
                serverName = online.get()
                        .getCurrentServer()
                        .map(server -> server.getServerInfo().getName())
                        .orElse("");
            }
            serverName = safeTrim(serverName);
        }
        if (!serverName.isEmpty()) {
            ServerType inferredType = inferServerTypeFromServerName(serverName);
            if (inferredType != null) {
                return inferredType;
            }
        }
        return ServerType.UNKNOWN;
    }

    private String describeOnlineLocation(UUID friendId, FriendService.FriendPresence presence) {
        if (presence == null) {
            return "Game";
        }
        String serverName = safeTrim(presence.getServerName());
        ServerType type = presence.getServerType();
        if ((serverName.isEmpty() || type == null || type == ServerType.UNKNOWN) && proxy != null && friendId != null) {
            Optional<Player> online = proxy.getPlayer(friendId);
            if (online.isPresent()) {
                String liveServer = online.get()
                        .getCurrentServer()
                        .map(server -> server.getServerInfo().getName())
                        .orElse("");
                if (!safeTrim(liveServer).isEmpty()) {
                    serverName = safeTrim(liveServer);
                }
            }
        }
        if (type == null || type == ServerType.UNKNOWN) {
            type = inferServerTypeFromServerName(serverName);
        }
        if (type != null && type != ServerType.UNKNOWN) {
            String gameType = toDisplayCase(type.getGameTypeDisplayName());
            String kind = type.isHub() ? "Lobby" : (type.isGame() ? "Game" : "Server");
            return gameType + " " + kind;
        }
        String explicitLocation = stripServerIdSuffix(presence.getLocation());
        if (!explicitLocation.isEmpty() && !"a game".equalsIgnoreCase(explicitLocation)) {
            if (explicitLocation.regionMatches(true, 0, "a ", 0, 2)) {
                return explicitLocation.substring(2).trim();
            }
            return explicitLocation;
        }
        return "Game";
    }

    private ServerType inferServerTypeFromServerName(String serverName) {
        String normalized = normalizeIdentifier(serverName);
        if (normalized.isEmpty()) {
            return ServerType.UNKNOWN;
        }
        for (ServerType candidate : ServerType.values()) {
            if (candidate == null || candidate == ServerType.UNKNOWN) {
                continue;
            }
            String key = candidate.name();
            if (normalized.equals(key)
                    || normalized.startsWith(key + "_")
                    || normalized.endsWith("_" + key)
                    || normalized.contains("_" + key + "_")) {
                return candidate;
            }
        }
        boolean maybeHub = normalized.contains("HUB") || normalized.contains("LOBBY");
        for (ServerType candidate : ServerType.values()) {
            if (candidate == null || candidate == ServerType.UNKNOWN) {
                continue;
            }
            String gameKey = candidate.name().replace("_HUB", "");
            if (gameKey.isEmpty() || !normalized.contains(gameKey)) {
                continue;
            }
            if (candidate.isHub() && maybeHub) {
                return candidate;
            }
            if (candidate.isGame() && !maybeHub) {
                return candidate;
            }
        }
        return ServerType.UNKNOWN;
    }

    private String toDisplayCase(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Game";
        }
        String[] words = raw.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder(raw.length());
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1));
            }
        }
        return out.length() == 0 ? "Game" : out.toString();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeIdentifier(String raw) {
        String trimmed = safeTrim(raw);
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toUpperCase(ch));
                previousUnderscore = false;
                continue;
            }
            if (!previousUnderscore) {
                out.append('_');
                previousUnderscore = true;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '_') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '_') {
            end--;
        }
        return start >= end ? "" : out.substring(start, end);
    }

    private String stripServerIdSuffix(String location) {
        String trimmed = safeTrim(location);
        if (trimmed.isEmpty()) {
            return "";
        }
        int marker = trimmed.lastIndexOf(" (");
        if (marker > 0 && trimmed.endsWith(")")) {
            return trimmed.substring(0, marker).trim();
        }
        return trimmed;
    }

    private String formatLastSeen(FriendService.FriendPresence presence) {
        if (presence == null) {
            return "less than a minute";
        }
        long updatedAt = presence.getUpdatedAt();
        if (updatedAt <= 0L) {
            return "less than a minute";
        }
        long diff = System.currentTimeMillis() - updatedAt;
        return formatDuration(diff, false);
    }

    private String formatFriendDuration(long since) {
        if (since <= 0L) {
            return "less than a minute";
        }
        long diff = System.currentTimeMillis() - since;
        return formatDuration(diff, true);
    }

    private String formatDuration(long millis, boolean includeMonths) {
        long safe = Math.max(0L, millis);
        if (safe < MILLIS_PER_MINUTE) {
            return "less than a minute";
        }
        if (includeMonths) {
            long months = safe / MILLIS_PER_MONTH;
            long remainingAfterMonths = safe % MILLIS_PER_MONTH;
            long days = remainingAfterMonths / MILLIS_PER_DAY;
            if (months > 0L && days > 0L) {
                return unit(months, "month") + " and " + unit(days, "day");
            }
            if (months > 0L) {
                return unit(months, "month");
            }
            if (days > 0L) {
                return unit(days, "day");
            }
        }
        long days = safe / MILLIS_PER_DAY;
        long remainingAfterDays = safe % MILLIS_PER_DAY;
        long hours = remainingAfterDays / MILLIS_PER_HOUR;
        long remainingAfterHours = remainingAfterDays % MILLIS_PER_HOUR;
        long minutes = remainingAfterHours / MILLIS_PER_MINUTE;
        if (days > 0L && hours > 0L) {
            return unit(days, "day") + " and " + unit(hours, "hour");
        }
        if (days > 0L) {
            return unit(days, "day");
        }
        if (hours > 0L && minutes > 0L) {
            return unit(hours, "hour") + " and " + unit(minutes, "minute");
        }
        if (hours > 0L) {
            return unit(hours, "hour");
        }
        if (minutes > 0L) {
            return unit(minutes, "minute");
        }
        return "less than a minute";
    }

    private String unit(long value, String noun) {
        long safe = Math.max(0L, value);
        if (safe == 1L) {
            return "1 " + noun;
        }
        return safe + " " + noun + "s";
    }

    private String cmd(String suffix) {
        String safeSuffix = suffix == null ? "" : suffix.trim();
        if (safeSuffix.isEmpty()) {
            return "/" + commandRoot;
        }
        return "/" + commandRoot + " " + safeSuffix;
    }

    private void sendInvalidUsage(Player player, String syntax) {
        sendFramed(player, FriendComponents.invalidUsage(syntax));
    }

    private Component suggestCommandComponent(String command) {
        String safe = command == null ? "" : command;
        return Component.text(safe, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.suggestCommand(safe))
                .hoverEvent(HoverEvent.showText(COMMAND_SUGGEST_HOVER));
    }

    private Component colorName(UUID uuid, String fallback) {
        String safeFallback = fallback == null ? "Unknown" : fallback;
        if (uuid == null) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
        String colored = friends.formatNameWithRank(uuid);
        if (colored == null || colored.trim().isEmpty()) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
        return LEGACY.deserialize(colored);
    }

    private boolean isEitherBlocked(UUID first, UUID second) {
        if (blocks == null || first == null || second == null) {
            return false;
        }
        return blocks.isEitherBlocked(first, second);
    }

    private void sendFramed(Player player, Component line) {
        List<Component> lines = new ArrayList<>(1);
        lines.add(line == null ? Component.empty() : line);
        sendFramed(player, lines);
    }

    private void sendUnframed(Player player, Component line) {
        List<Component> lines = new ArrayList<>(1);
        lines.add(line == null ? Component.empty() : line);
        sendUnframed(player, lines);
    }

    private void sendFramed(Player player, List<Component> lines) {
        if (player == null) {
            return;
        }
        player.sendMessage(FriendComponents.longSeparator());
        sendLines(player, lines);
        player.sendMessage(FriendComponents.longSeparator());
    }

    private void sendUnframed(Player player, List<Component> lines) {
        if (player == null) {
            return;
        }
        sendLines(player, lines);
    }

    private void sendLines(Player player, List<Component> lines) {
        if (lines == null) {
            return;
        }
        for (Component line : lines) {
            player.sendMessage(line == null ? Component.empty() : line);
        }
    }

    private void sendHelp(Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Friend Commands:", NamedTextColor.GREEN));
        lines.add(FriendComponents.commandEntry("/friend accept <player>", "Accept a friend request"));
        lines.add(FriendComponents.commandEntry("/friend add <player>", "Add a player as a friend"));
        lines.add(FriendComponents.commandEntry("/friend best <player>", "Toggle a player as best Friend"));
        lines.add(FriendComponents.commandEntry("/friend deny <player>", "Decline a friend request"));
        lines.add(FriendComponents.commandEntry("/friend help", "Prints all available friend commands."));
        lines.add(FriendComponents.commandEntry("/friend list <page/best>", "List your friends"));
        lines.add(FriendComponents.commandEntry("/friend nickname <player> [nickname]", "Set or clear a friend nickname"));
        lines.add(FriendComponents.commandEntry("/friend notifications", "Toggle friend join/leave notifications"));
        lines.add(FriendComponents.commandEntry("/friend remove <player>", "Remove a player from your friends"));
        lines.add(FriendComponents.commandEntry("/friend removeall", "Remove all your friends (excluding best friends)"));
        lines.add(FriendComponents.commandEntry("/friend requests <page>", "View friend requests"));
        sendFramed(player, lines);
    }

    private static final class RequestEntry {
        private final UUID otherId;
        private final boolean outgoing;

        private RequestEntry(UUID otherId, boolean outgoing) {
            this.otherId = otherId;
            this.outgoing = outgoing;
        }

        private static RequestEntry incoming(UUID otherId) {
            return new RequestEntry(otherId, false);
        }

        private static RequestEntry outgoing(UUID otherId) {
            return new RequestEntry(otherId, true);
        }
    }
}
