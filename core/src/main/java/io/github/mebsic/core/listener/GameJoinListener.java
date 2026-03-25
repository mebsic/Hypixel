package io.github.mebsic.core.listener;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.server.GameTypePlayerCountProvider;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.RankFormatUtil;
import io.github.mebsic.game.model.GameState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class GameJoinListener implements Listener {
    private final JavaPlugin plugin;
    private final CoreApi coreApi;
    private final ServerType serverType;
    private final GameTypePlayerCountProvider playerCountProvider;
    private final ConcurrentMap<UUID, ChatColor> nameColors;

    public GameJoinListener(JavaPlugin plugin, CoreApi coreApi, ServerType serverType, GameTypePlayerCountProvider playerCountProvider) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.coreApi = Objects.requireNonNull(coreApi, "coreApi");
        this.serverType = Objects.requireNonNull(serverType, "serverType");
        this.playerCountProvider = Objects.requireNonNull(playerCountProvider, "playerCountProvider");
        this.nameColors = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!serverType.isGame()) {
            return;
        }
        event.setJoinMessage(null);
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            ChatColor nameColor = resolveNameColor(uuid);
            nameColors.put(uuid, nameColor);
            int currentPlayers = player.getServer().getOnlinePlayers().size();
            int maxPlayers = playerCountProvider.getMaxPlayers(serverType);
            if (maxPlayers <= 0) {
                maxPlayers = Math.max(currentPlayers, player.getServer().getMaxPlayers());
            }
            String message = nameColor + player.getName()
                    + ChatColor.YELLOW + " has joined ("
                    + ChatColor.AQUA + currentPlayers
                    + ChatColor.YELLOW + "/"
                    + ChatColor.AQUA + maxPlayers
                    + ChatColor.YELLOW + ")!";
            player.getServer().broadcastMessage(message);
        }, 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!serverType.isGame()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GameState state = resolveGameState();
        if (state != GameState.WAITING && state != GameState.STARTING) {
            nameColors.remove(uuid);
            event.setQuitMessage(null);
            return;
        }
        ChatColor nameColor = resolveNameColor(uuid);
        ChatColor cached = nameColors.remove(uuid);
        if (cached != null) {
            nameColor = cached;
        }
        String message = nameColor + player.getName()
                + ChatColor.YELLOW + " has quit!";
        event.setQuitMessage(message);
    }

    private ChatColor resolveNameColor(UUID uuid) {
        Rank rank = coreApi.getRank(uuid);
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        Profile profile = coreApi.getProfile(uuid);
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        return RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
    }

    private GameState resolveGameState() {
        if (plugin instanceof CorePlugin) {
            return ((CorePlugin) plugin).getCurrentGameState();
        }
        return GameState.WAITING;
    }
}
