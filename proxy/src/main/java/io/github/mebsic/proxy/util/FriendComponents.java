package io.github.mebsic.proxy.util;

import io.github.mebsic.core.util.CommonMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class FriendComponents {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String COMMAND_SUGGEST_HOVER = "Click to put the command in chat.";
    private static final String ACCEPT_REQUEST_HOVER = "Click to accept the friend request";
    private static final String DENY_REQUEST_HOVER = "Click to deny the friend request";
    private static final String BLOCK_REQUEST_HOVER = "Click to block all future friend requests and chat messages from this player.";
    private static final String REMOVE_ALL_CANCEL_HOVER = "Click to cancel and keep your friends list.";
    private static final String REMOVE_ALL_CLEAR_HOVER = "Click to clear your friends list. (This does not remove your best friends.)";
    private static final String LONG_SEPARATOR = repeatSpaces(80);

    private FriendComponents() {
    }

    public static Component longSeparator() {
        return separator(LONG_SEPARATOR);
    }

    public static Component commandEntry(String syntax, String description) {
        String safeSyntax = syntax == null ? "" : syntax;
        String safeDescription = description == null ? "" : description;
        return Component.text(safeSyntax, NamedTextColor.YELLOW)
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text(safeDescription, NamedTextColor.AQUA))
                .clickEvent(ClickEvent.suggestCommand(safeSyntax))
                .hoverEvent(HoverEvent.showText(Component.text(COMMAND_SUGGEST_HOVER, NamedTextColor.GRAY)));
    }

    public static Component invalidUsage(String syntax) {
        String safe = syntax == null ? "" : syntax;
        return Component.text("Invalid usage! '" + safe + "'", NamedTextColor.RED);
    }

    public static Component noPlayerFound(String rawName) {
        return Component.text(CommonMessages.PLAYER_NOT_FOUND_COMMAND, NamedTextColor.RED);
    }

    public static Component pageHeader(String title, int page, int totalPages, String commandPrefix) {
        int safePage = Math.max(1, page);
        int safeTotal = Math.max(1, totalPages);
        String safeTitle = title == null ? "Friends" : title;
        boolean friendsListTitle = "Friends".equalsIgnoreCase(safeTitle) || "Best Friends".equalsIgnoreCase(safeTitle);
        String leading = friendsListTitle ? repeatSpaces(25) : "";
        Component header = Component.text(leading + safeTitle + " (Page " + safePage + " of " + safeTotal + ")", NamedTextColor.GOLD);
        if (safePage > 1) {
            int previous = safePage - 1;
            header = previousButton(previous, commandPrefix)
                    .append(Component.space())
                    .append(header);
        }
        if (safePage < safeTotal) {
            int next = safePage + 1;
            header = header.append(Component.space())
                    .append(nextButton(next, commandPrefix));
        }
        return header;
    }

    public static Component friendRequestsHeader(int page, int totalPages) {
        int safePage = Math.max(1, page);
        int safeTotal = Math.max(1, totalPages);
        return Component.text("--- Friend Requests (Page " + safePage + " of " + safeTotal + ") ---", NamedTextColor.YELLOW);
    }

    public static Component friendRequestNotification(String formattedFromName, String commandFromName) {
        List<Component> lines = friendRequestNotificationLines(formattedFromName, commandFromName);
        if (lines.isEmpty()) {
            return Component.empty();
        }
        Component combined = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            combined = combined.append(Component.newline()).append(lines.get(i));
        }
        return combined;
    }

    public static List<Component> friendRequestNotificationLines(String formattedFromName, String commandFromName) {
        String safeFormattedName = formattedFromName == null ? "Unknown" : formattedFromName;
        String safeCommandName = commandFromName == null ? "Unknown" : commandFromName;
        Component firstLine = Component.text("Friend request from ", NamedTextColor.YELLOW)
                .append(LEGACY.deserialize(safeFormattedName));
        Component secondLine = actionButton(
                "[ACCEPT]",
                NamedTextColor.GREEN,
                "/friend accept " + safeCommandName,
                ACCEPT_REQUEST_HOVER,
                NamedTextColor.AQUA).decorate(TextDecoration.BOLD)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(actionButton(
                        "[DENY]",
                        NamedTextColor.RED,
                        "/friend deny " + safeCommandName,
                        DENY_REQUEST_HOVER,
                        NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(actionButton(
                        "[BLOCK]",
                        NamedTextColor.GRAY,
                        "/block " + safeCommandName,
                        BLOCK_REQUEST_HOVER,
                        NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
        List<Component> lines = new ArrayList<>(2);
        lines.add(firstLine);
        lines.add(secondLine);
        return lines;
    }

    public static Component incomingRequestEntry(String formattedRequestorName, String commandRequestorName) {
        String safeFormattedName = formattedRequestorName == null ? "Unknown" : formattedRequestorName;
        String safeCommandName = commandRequestorName == null ? "Unknown" : commandRequestorName;
        return Component.text("From ", NamedTextColor.GRAY)
                .append(LEGACY.deserialize(safeFormattedName))
                .append(Component.space())
                .append(actionButton(
                        "[ACCEPT]",
                        NamedTextColor.GREEN,
                        "/friend accept " + safeCommandName,
                        ACCEPT_REQUEST_HOVER,
                        NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(actionButton(
                        "[DENY]",
                        NamedTextColor.RED,
                        "/friend deny " + safeCommandName,
                        DENY_REQUEST_HOVER,
                        NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(actionButton(
                        "[BLOCK]",
                        NamedTextColor.GRAY,
                        "/block " + safeCommandName,
                        BLOCK_REQUEST_HOVER,
                        NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
    }

    public static Component outgoingRequestEntry(String formattedTargetName) {
        String safeFormattedName = formattedTargetName == null ? "Unknown" : formattedTargetName;
        return Component.text("To ", NamedTextColor.GRAY)
                .append(LEGACY.deserialize(safeFormattedName));
    }

    public static List<Component> removeAllConfirmationLines(String cancelCommand, String clearCommand) {
        List<Component> lines = new ArrayList<>(3);
        lines.add(Component.text("You're about to remove ", NamedTextColor.YELLOW)
                .append(Component.text("ALL", NamedTextColor.RED).decorate(TextDecoration.BOLD))
                .append(Component.text(" of your friends (excluding best friends). Are you sure you want to do this?", NamedTextColor.YELLOW)));
        lines.add(Component.empty());
        lines.add(removeAllCancelButton(cancelCommand)
                .append(Component.text(" or ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
                .append(removeAllClearButton(clearCommand)));
        return lines;
    }

    public static Component removeAllCancelButton(String command) {
        String safeCommand = command == null ? "" : command;
        return Component.text("[CANCEL]", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(safeCommand))
                .hoverEvent(HoverEvent.showText(Component.text(REMOVE_ALL_CANCEL_HOVER, NamedTextColor.AQUA)));
    }

    public static Component removeAllClearButton(String command) {
        String safeCommand = command == null ? "" : command;
        return Component.text("[CLEAR FRIENDS LIST]", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(safeCommand))
                .hoverEvent(HoverEvent.showText(Component.text(REMOVE_ALL_CLEAR_HOVER, NamedTextColor.AQUA)));
    }

    private static Component previousButton(int page, String commandPrefix) {
        return previousButton(page, commandPrefix, true);
    }

    private static Component previousButton(int page, String commandPrefix, boolean bold) {
        String command = buildPageCommand(commandPrefix, page);
        Component button = Component.text("<<", NamedTextColor.YELLOW);
        if (bold) {
            button = button.decorate(TextDecoration.BOLD);
        }
        return button.clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to view page " + page, NamedTextColor.YELLOW)));
    }

    private static Component nextButton(int page, String commandPrefix) {
        return nextButton(page, commandPrefix, true);
    }

    private static Component nextButton(int page, String commandPrefix, boolean bold) {
        String command = buildPageCommand(commandPrefix, page);
        Component button = Component.text(">>", NamedTextColor.YELLOW);
        if (bold) {
            button = button.decorate(TextDecoration.BOLD);
        }
        return button.clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to view page " + page, NamedTextColor.YELLOW)));
    }

    private static Component actionButton(String label, NamedTextColor color, String command, String hoverText) {
        return actionButton(label, color, command, hoverText, color);
    }

    private static Component actionButton(String label,
                                          NamedTextColor color,
                                          String command,
                                          String hoverText,
                                          NamedTextColor hoverColor) {
        NamedTextColor safeHoverColor = hoverColor == null ? color : hoverColor;
        return Component.text(label, color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hoverText, safeHoverColor)));
    }

    private static Component separator(String text) {
        return Component.text(text, NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH);
    }

    private static String buildPageCommand(String prefix, int page) {
        String safePrefix = prefix == null ? "/friend list" : prefix.trim();
        return safePrefix + " " + page;
    }

    private static String repeatSpaces(int amount) {
        StringBuilder builder = new StringBuilder(Math.max(0, amount));
        for (int i = 0; i < amount; i++) {
            builder.append(' ');
        }
        return builder.toString();
    }
}
