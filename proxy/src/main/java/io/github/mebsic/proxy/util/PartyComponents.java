package io.github.mebsic.proxy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class PartyComponents {
    private static final String COMMAND_SUGGEST_HOVER = "Click to put the command in chat.";
    private static final String LONG_SEPARATOR = repeatSpaces(80);

    private PartyComponents() {
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

    private static Component separator(String text) {
        return Component.text(text, NamedTextColor.BLUE, TextDecoration.STRIKETHROUGH);
    }

    private static String repeatSpaces(int amount) {
        StringBuilder builder = new StringBuilder(Math.max(0, amount));
        for (int i = 0; i < amount; i++) {
            builder.append(' ');
        }
        return builder.toString();
    }
}
