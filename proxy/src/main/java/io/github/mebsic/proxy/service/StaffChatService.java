package io.github.mebsic.proxy.service;

import io.github.mebsic.proxy.util.Components;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.UUID;

public class StaffChatService {
    private final ProxyServer proxy;
    private final RankResolver rankResolver;

    public StaffChatService(ProxyServer proxy, RankResolver rankResolver) {
        this.proxy = proxy;
        this.rankResolver = rankResolver;
    }

    public boolean isStaff(UUID uuid) {
        return rankResolver != null && rankResolver.isStaff(uuid);
    }

    public void broadcastJoin(Player player) {
        broadcastStatus(player, true);
    }

    public void broadcastQuit(Player player) {
        broadcastStatus(player, false);
    }

    public void broadcastChat(Player sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        String formattedSender = formatStatusName(sender);
        proxy.getAllPlayers().forEach(target -> {
            if (isStaff(target.getUniqueId())) {
                target.sendMessage(Components.staffChat(formattedSender, message));
            }
        });
    }

    private void broadcastStatus(Player player, boolean joined) {
        if (player == null || !shouldBroadcastStatus(player.getUniqueId())) {
            return;
        }
        String formattedName = formatStatusName(player);
        proxy.getAllPlayers().forEach(target -> {
            if (canReceiveStatusBroadcast(target.getUniqueId())) {
                target.sendMessage(Components.staffStatus(formattedName, joined));
            }
        });
    }

    private boolean shouldBroadcastStatus(UUID uuid) {
        return rankResolver != null && rankResolver.hasAtLeast(uuid, "YOUTUBE");
    }

    private boolean canReceiveStatusBroadcast(UUID uuid) {
        return rankResolver != null && rankResolver.hasAtLeast(uuid, "STAFF");
    }

    private String formatStatusName(Player player) {
        if (player == null) {
            return "Unknown";
        }
        if (rankResolver == null) {
            return player.getUsername();
        }
        try {
            return rankResolver.formatNameWithRank(player.getUniqueId(), player.getUsername());
        } catch (Throwable ignored) {
            return player.getUsername();
        }
    }
}
