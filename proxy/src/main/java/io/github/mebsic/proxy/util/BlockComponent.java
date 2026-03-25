package io.github.mebsic.proxy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class BlockComponent {
    private static final String COMMAND_SUGGEST_HOVER = "Click to put the command in chat.";
    private static final String REMOVE_ALL_CANCEL_HOVER = "Click to cancel and keep the players blocked.";
    private static final String REMOVE_ALL_CLEAR_HOVER = "Click to clear your blocked players list.";

    private BlockComponent() {
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
        String safe = rawName == null ? "unknown" : rawName;
        return Component.text("No player found with name " + safe + "!", NamedTextColor.RED);
    }

    public static Component removeAllCancelButton(String command) {
        String safeCommand = command == null ? "" : command;
        return Component.text("[CANCEL]", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(safeCommand))
                .hoverEvent(HoverEvent.showText(Component.text(REMOVE_ALL_CANCEL_HOVER, NamedTextColor.WHITE)));
    }

    public static Component removeAllClearButton(String command) {
        String safeCommand = command == null ? "" : command;
        return Component.text("[CLEAR BLOCKED PLAYERS LIST]", NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(safeCommand))
                .hoverEvent(HoverEvent.showText(Component.text(REMOVE_ALL_CLEAR_HOVER, NamedTextColor.WHITE)));
    }
}
