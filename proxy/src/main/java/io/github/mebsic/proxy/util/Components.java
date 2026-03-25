package io.github.mebsic.proxy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class Components {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private Components() {
    }

    public static Component friendRequest(String fromName) {
        Component accept = Component.text("[ACCEPT]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/friend accept " + fromName))
                .hoverEvent(HoverEvent.showText(Component.text("Click to accept", NamedTextColor.GREEN)));
        Component deny = Component.text("[DENY]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/friend deny " + fromName))
                .hoverEvent(HoverEvent.showText(Component.text("Click to deny", NamedTextColor.RED)));
        return Component.text("Friend request from ", NamedTextColor.YELLOW)
                .append(Component.text(fromName, NamedTextColor.AQUA))
                .append(Component.space())
                .append(accept)
                .append(Component.space())
                .append(deny);
    }

    public static List<Component> partyInviteLines(String formattedLeaderName, String commandLeaderName) {
        String safeCommandLeader = commandLeaderName == null ? "Unknown" : commandLeaderName;
        String safeFormattedLeader = formattedLeaderName == null ? "Unknown" : formattedLeaderName;
        Component firstLine = LEGACY.deserialize(safeFormattedLeader)
                .append(Component.text(" has invited you to join their party!", NamedTextColor.YELLOW));
        Component secondLine = Component.text("You have ", NamedTextColor.YELLOW)
                .append(Component.text("60", NamedTextColor.RED))
                .append(Component.text(" seconds to accept. ", NamedTextColor.YELLOW))
                .append(Component.text("Click here to join!", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/party accept " + safeCommandLeader))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to run\n/party accept " + safeCommandLeader))));
        List<Component> lines = new ArrayList<Component>(2);
        lines.add(firstLine);
        lines.add(secondLine);
        return lines;
    }

    public static Component partyInvite(String formattedLeaderName, String commandLeaderName) {
        List<Component> lines = partyInviteLines(formattedLeaderName, commandLeaderName);
        if (lines.isEmpty()) {
            return Component.empty();
        }
        Component combined = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            combined = combined.append(Component.newline()).append(lines.get(i));
        }
        return combined;
    }

    public static Component partyInvite(String leaderName) {
        return partyInvite(leaderName, leaderName);
    }

    public static Component friendStatus(String name, boolean joined) {
        String safeName = name == null ? "" : name;
        return Component.text("Friend > ", NamedTextColor.GREEN)
                .append(LEGACY.deserialize(safeName))
                .append(Component.text(joined ? " joined." : " left.", NamedTextColor.YELLOW));
    }

    public static Component partyStatus(String name, boolean joined) {
        String safeName = name == null ? "" : name;
        return LEGACY.deserialize(safeName)
                .append(Component.text(joined ? " joined the party." : " left the party.", NamedTextColor.YELLOW));
    }

    public static Component staffJoin(String name) {
        return staffStatus(name, true);
    }

    public static Component staffQuit(String name) {
        return staffStatus(name, false);
    }

    public static Component staffStatus(String formattedName, boolean joined) {
        String safeName = formattedName == null ? "" : formattedName;
        return Component.text("[STAFF] ", NamedTextColor.AQUA)
                .append(LEGACY.deserialize(safeName))
                .append(Component.text(joined ? " joined." : " left.", NamedTextColor.YELLOW));
    }

    public static Component staffChat(String formattedName, String message) {
        String safeName = formattedName == null ? "" : formattedName;
        return Component.text("[STAFF] ", NamedTextColor.AQUA)
                .append(LEGACY.deserialize(safeName))
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(Component.text(message, NamedTextColor.WHITE));
    }

    public static Component partyChat(String name, String message) {
        String safeName = name == null ? "" : name;
        String safeMessage = message == null ? "" : message;
        return Component.text("Party", NamedTextColor.BLUE)
                .append(Component.text(" > ", NamedTextColor.DARK_GRAY))
                .append(LEGACY.deserialize(safeName))
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(Component.text(safeMessage, NamedTextColor.WHITE));
    }

    public static Component friendPrivateMessage(boolean outgoing, String formattedName, String message) {
        String safeName = formattedName == null ? "" : formattedName;
        String safeMessage = message == null ? "" : message;
        return Component.text(outgoing ? "To " : "From ", NamedTextColor.LIGHT_PURPLE)
                .append(LEGACY.deserialize(safeName))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(safeMessage, NamedTextColor.GRAY));
    }

    public static Component friendRequestExpired(String formattedTargetName) {
        String safeTarget = formattedTargetName == null ? "" : formattedTargetName;
        return Component.text("Your friend request to ", NamedTextColor.YELLOW)
                .append(LEGACY.deserialize(safeTarget))
                .append(Component.text(" has expired.", NamedTextColor.YELLOW));
    }

    public static Component transferToServer(String serverName) {
        String serverId = serverName == null ? "" : serverName.trim();
        if (serverId.isEmpty()) {
            return Component.text("Sending you to another server!", NamedTextColor.GREEN);
        }
        return Component.text("Sending you to " + serverId + "!", NamedTextColor.GREEN);
    }
}
