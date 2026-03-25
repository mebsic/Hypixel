package io.github.mebsic.core.listener;

import io.github.mebsic.core.model.Punishment;
import io.github.mebsic.core.model.PunishmentType;
import io.github.mebsic.core.service.PunishmentService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.UUID;

public class PunishmentListener implements Listener {
    private final PunishmentService punishments;

    public PunishmentListener(PunishmentService punishments) {
        this.punishments = punishments;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (punishments == null) {
            return;
        }
        UUID uuid = event.getUniqueId();
        Punishment ban = punishments.getActivePunishment(uuid, PunishmentType.BAN);
        if (ban != null) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    punishments.formatBanMessage(ban)
            );
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (punishments == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        Punishment mute = punishments.getActiveMute(player.getUniqueId());
        if (mute != null) {
            event.setCancelled(true);
            player.sendMessage(punishments.formatMuteMessage(mute));
        }
    }
}
