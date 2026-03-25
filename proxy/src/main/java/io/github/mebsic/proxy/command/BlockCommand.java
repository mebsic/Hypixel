package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.mebsic.core.util.MojangApi;
import io.github.mebsic.proxy.service.BlockService;
import io.github.mebsic.proxy.service.FriendService;
import io.github.mebsic.proxy.util.BlockComponent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BlockCommand implements SimpleCommand {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final int LIST_PAGE_SIZE = 10;
    private static final String HEADER_DASHES = "------";
    private static final String CONFIRM_SEPARATOR = "-----------------------------------------------------";

    private final ProxyServer proxy;
    private final BlockService blocks;
    private final FriendService friends;
    private final Set<UUID> removeAllConfirmations = ConcurrentHashMap.newKeySet();

    public BlockCommand(ProxyServer proxy, BlockService blocks, FriendService friends) {
        this.proxy = proxy;
        this.blocks = blocks;
        this.friends = friends;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        Player player = (Player) invocation.source();
        if (isStaff(player.getUniqueId())) {
            send(player, Component.text("You cannot block any players!", NamedTextColor.RED));
            return;
        }
        if (blocks != null) {
            try {
                blocks.ensurePlayerDocument(player.getUniqueId(), player.getUsername());
            } catch (Throwable ignored) {
            }
        }
        handleBlockCommand(player, invocation.arguments());
    }

    private void handleBlockCommand(Player player, String[] args) {
        if (args.length == 0) {
            sendHelp(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help":
                if (args.length != 1) {
                    send(player, BlockComponent.invalidUsage("/block help"));
                    return;
                }
                sendHelp(player);
                return;
            case "add":
                if (args.length != 2) {
                    send(player, BlockComponent.invalidUsage("/block add <player>"));
                    return;
                }
                handleAdd(player, args[1]);
                return;
            case "remove":
                if (args.length != 2) {
                    send(player, BlockComponent.invalidUsage("/block remove <player>"));
                    return;
                }
                handleRemove(player, args[1]);
                return;
            case "list":
                handleList(player, args);
                return;
            case "removeall":
                handleRemoveAll(player, args);
                return;
            default:
                if (args.length == 1) {
                    handleAdd(player, args[0]);
                    return;
                }
                send(player, BlockComponent.invalidUsage("/block <player>"));
        }
    }

    private void handleAdd(Player player, String targetInput) {
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            send(player, BlockComponent.noPlayerFound(targetInput));
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            send(player, Component.text("You cannot block yourself!", NamedTextColor.RED));
            return;
        }
        if (isStaff(targetId)) {
            send(player, Component.text("You cannot block ", NamedTextColor.RED)
                    .append(rankName(targetId, targetInput))
                    .append(Component.text("!", NamedTextColor.RED)));
            return;
        }
        String name = resolveName(targetId, targetInput);
        if (blocks != null) {
            try {
                blocks.ensurePlayerDocument(targetId, name);
            } catch (Throwable ignored) {
            }
        }
        if (blocks == null || !blocks.blockPlayer(player.getUniqueId(), targetId)) {
            send(player, Component.text("That player is already blocked!", NamedTextColor.RED));
            return;
        }
        if (friends != null) {
            try {
                friends.onPlayerBlocked(player.getUniqueId(), targetId);
            } catch (Throwable ignored) {
            }
        }
        send(player, Component.text("Blocked ", NamedTextColor.GREEN)
                .append(colorName(targetId, name))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }

    private void handleRemove(Player player, String targetInput) {
        if (blocks != null) {
            blocks.getBlockedPlayers(player.getUniqueId());
        }
        UUID targetId = resolveUuid(targetInput);
        if (targetId == null) {
            send(player, BlockComponent.noPlayerFound(targetInput));
            return;
        }
        if (blocks == null || !blocks.unblockPlayer(player.getUniqueId(), targetId)) {
            send(player, Component.text("That player is not blocked!", NamedTextColor.RED));
            return;
        }
        String name = resolveName(targetId, targetInput);
        send(player, Component.text("Unblocked ", NamedTextColor.GREEN)
                .append(colorName(targetId, name))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }

    private void handleList(Player player, String[] args) {
        if (args.length > 2) {
            send(player, BlockComponent.invalidUsage("/block list [page]"));
            return;
        }
        int page = parsePage(args, 1);
        if (page < 1) {
            send(player, BlockComponent.invalidUsage("/block list [page]"));
            return;
        }
        List<UUID> blocked = new ArrayList<>(blocks == null
                ? java.util.Collections.<UUID>emptySet()
                : blocks.getBlockedPlayers(player.getUniqueId()));
        if (blocked.isEmpty()) {
            send(player, Component.text("You have not blocked anyone!", NamedTextColor.RED));
            return;
        }
        blocked.sort(Comparator.comparing(this::nameForSort, String.CASE_INSENSITIVE_ORDER));
        int totalPages = pageCount(blocked.size(), LIST_PAGE_SIZE);
        if (page > totalPages) {
            send(player, Component.text("That page does not exist.", NamedTextColor.RED));
            return;
        }
        int start = Math.max(0, (page - 1) * LIST_PAGE_SIZE);
        int end = Math.min(blocked.size(), start + LIST_PAGE_SIZE);
        List<Component> lines = new ArrayList<>(end - start + 1);
        lines.add(Component.text(HEADER_DASHES + " Blocked Players (Page " + page + " of " + totalPages + ") " + HEADER_DASHES, NamedTextColor.YELLOW));
        for (int i = start; i < end; i++) {
            int displayIndex = i + 1;
            String name = nameForList(blocked.get(i));
            lines.add(Component.text(displayIndex + ". ", NamedTextColor.AQUA)
                    .append(Component.text(name, NamedTextColor.YELLOW)));
        }
        send(player, lines);
    }

    private void handleRemoveAll(Player player, String[] args) {
        if (args.length == 1) {
            promptRemoveAll(player);
            return;
        }
        if (args.length != 2) {
            send(player, BlockComponent.invalidUsage("/block removeall"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        UUID ownerId = player.getUniqueId();
        switch (action) {
            case "cancel":
                removeAllConfirmations.remove(ownerId);
                send(player, Component.text("Nothing happened.", NamedTextColor.GREEN));
                return;
            case "confirm":
            case "clear":
                if (!removeAllConfirmations.remove(ownerId)) {
                    send(player, Component.text("Nothing happened.", NamedTextColor.GREEN));
                    return;
                }
                int removed = blocks == null ? 0 : blocks.unblockAllPlayers(ownerId);
                if (removed <= 0) {
                    send(player, Component.text("Nothing happened.", NamedTextColor.GREEN));
                    return;
                }
                send(player, Component.text("Cleared your blocked players list.", NamedTextColor.GREEN));
                return;
            default:
                send(player, BlockComponent.invalidUsage("/block removeall"));
        }
    }

    private void promptRemoveAll(Player player) {
        if (blocks == null || blocks.getBlockedPlayers(player.getUniqueId()).isEmpty()) {
            send(player, Component.text("You have not blocked anyone!", NamedTextColor.RED));
            return;
        }
        removeAllConfirmations.add(player.getUniqueId());
        List<Component> lines = new ArrayList<>(5);
        lines.add(Component.text(CONFIRM_SEPARATOR, NamedTextColor.DARK_GRAY));
        lines.add(Component.text("You're about to remove ", NamedTextColor.YELLOW)
                .append(Component.text("ALL", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" blocked players. Are you sure you want to do this?", NamedTextColor.YELLOW)));
        lines.add(Component.empty());
        lines.add(BlockComponent.removeAllCancelButton("/block removeall cancel")
                .append(Component.text(" or ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(BlockComponent.removeAllClearButton("/block removeall confirm")));
        lines.add(Component.text(CONFIRM_SEPARATOR, NamedTextColor.DARK_GRAY));
        send(player, lines);
    }

    private void send(Player player, Component line) {
        if (player == null) {
            return;
        }
        player.sendMessage(line == null ? Component.empty() : line);
    }

    private void send(Player player, List<Component> lines) {
        if (player == null) {
            return;
        }
        for (Component line : lines) {
            player.sendMessage(line == null ? Component.empty() : line);
        }
    }

    private int parsePage(String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            int page = Integer.parseInt(args[index]);
            if (page < 1) {
                return -1;
            }
            return page;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int pageCount(int totalItems, int size) {
        if (size <= 0) {
            return 1;
        }
        return Math.max(1, (totalItems + size - 1) / size);
    }

    private String nameForSort(UUID uuid) {
        String name = nameForList(uuid);
        return name == null ? "" : name;
    }

    private String nameForList(UUID uuid) {
        String name = resolveName(uuid, null);
        if (name == null || name.trim().isEmpty() || "Unknown".equalsIgnoreCase(name)) {
            return uuid == null ? "Unknown" : uuid.toString();
        }
        return name;
    }

    private void sendHelp(Player player) {
        List<Component> lines = new ArrayList<>(6);
        lines.add(Component.text("Block commands:", NamedTextColor.GREEN));
        lines.add(BlockComponent.commandEntry("/block add <player>", "Block a player."));
        lines.add(BlockComponent.commandEntry("/block help", "Prints this help message."));
        lines.add(BlockComponent.commandEntry("/block list [page]", "List blocked players."));
        lines.add(BlockComponent.commandEntry("/block remove <player>", "Unblock a player."));
        lines.add(BlockComponent.commandEntry("/block removeall", "Unblock all players."));
        send(player, lines);
    }

    private UUID resolveUuid(String name) {
        if (name == null) {
            return null;
        }
        UUID online = proxy.getPlayer(name).map(Player::getUniqueId).orElse(null);
        if (online != null) {
            if (friends != null) {
                proxy.getPlayer(online).ifPresent(player -> friends.rememberName(online, player.getUsername()));
            }
            return online;
        }
        UUID cached = friends == null ? null : friends.resolveByName(name);
        if (cached != null) {
            return cached;
        }
        UUID mojang;
        try {
            mojang = MojangApi.lookupUuid(name);
        } catch (Throwable ignored) {
            mojang = null;
        }
        if (mojang != null) {
            if (friends != null) {
                friends.rememberName(mojang, name);
            }
        }
        return mojang;
    }

    private String resolveName(UUID uuid, String fallback) {
        if (uuid == null) {
            return fallback == null ? "Unknown" : fallback;
        }
        Optional<Player> online = proxy.getPlayer(uuid);
        if (online.isPresent()) {
            String onlineName = online.get().getUsername();
            if (friends != null) {
                friends.rememberName(uuid, onlineName);
            }
            return onlineName;
        }
        String remembered;
        try {
            remembered = friends == null ? null : friends.getName(uuid);
        } catch (Throwable ignored) {
            remembered = null;
        }
        if (remembered != null && !"Unknown".equalsIgnoreCase(remembered)) {
            return remembered;
        }
        return fallback == null ? "Unknown" : fallback;
    }

    private Component colorName(UUID uuid, String fallback) {
        String safeFallback = fallback == null ? "Unknown" : fallback;
        if (uuid == null) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
        String colored;
        try {
            colored = friends == null ? null : friends.formatNameWithColor(uuid);
        } catch (Throwable ignored) {
            colored = null;
        }
        if (colored == null || colored.trim().isEmpty()) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
        try {
            return LEGACY.deserialize(colored);
        } catch (Throwable ignored) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
    }

    private Component rankName(UUID uuid, String fallback) {
        String safeFallback = fallback == null ? "Unknown" : fallback;
        if (uuid == null || friends == null) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
        String ranked;
        try {
            ranked = friends.formatNameWithRank(uuid);
        } catch (Throwable ignored) {
            ranked = null;
        }
        if (ranked == null || ranked.trim().isEmpty()) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
        try {
            return LEGACY.deserialize(ranked);
        } catch (Throwable ignored) {
            return Component.text(safeFallback, NamedTextColor.GRAY);
        }
    }

    private boolean isStaff(UUID uuid) {
        if (uuid == null || friends == null) {
            return false;
        }
        try {
            return friends.isStaff(uuid);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
