package io.github.mebsic.core.listener;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.RankFormatUtil;
import io.github.mebsic.core.service.HubContext;
import io.github.mebsic.core.util.ActionBarUtil;
import io.github.mebsic.game.service.TitleService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class HubListener implements Listener {
    private static final String JOIN_SUFFIX = ChatColor.GOLD + " joined the lobby!";
    private static final long JOIN_MESSAGE_RETRY_INTERVAL_TICKS = 10L;
    private static final int JOIN_MESSAGE_MAX_RETRIES = 10;
    private static final int HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS = 4;

    private final HubContext plugin;
    private final CoreApi coreApi;
    private final TitleService titleService;
    private final Set<UUID> pendingJoinAnnouncements;
    private final Map<UUID, BukkitTask> pendingJoinAnnouncementTasks;

    public HubListener(HubContext plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.coreApi = Objects.requireNonNull(plugin.getCoreApi(), "coreApi");
        this.titleService = new TitleService();
        this.pendingJoinAnnouncements = ConcurrentHashMap.newKeySet();
        this.pendingJoinAnnouncementTasks = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.setJoinMessage(null);
        Player player = event.getPlayer();
        titleService.reset(player);
        ActionBarUtil.send(player, " ");
        resetHubInventory(player);
        UUID uuid = player.getUniqueId();
        Rank rank = coreApi.getRank(uuid);
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        int networkLevel = coreApi.getNetworkLevel(uuid);
        Profile profile = coreApi.getProfile(uuid);
        String plusColor = profile == null ? null : profile.getPlusColor();
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        teleportToHubSpawn(player);
        plugin.handleHubJoin(player);
        applySpeed(player, rank);
        applySpeedAfterProfileLoad(player);
        boolean announced = broadcastRankJoinMessage(player, rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
        if (!announced && profile == null) {
            pendingJoinAnnouncements.add(uuid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.setQuitMessage(null);
        UUID uuid = event.getPlayer().getUniqueId();
        pendingJoinAnnouncements.remove(uuid);
        cancelJoinAnnouncementRetry(uuid);
        plugin.handleHubQuit(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getY() >= 0.0D) {
            return;
        }
        teleportToHubSpawn(player);
    }

    private void applySpeed(Player player, Rank rank) {
        if (rank == null || player == null) {
            return;
        }
        player.removePotionEffect(PotionEffectType.SPEED);
        if (rank.isAtLeast(Rank.MVP_PLUS_PLUS)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false));
    }

    private void resetHubInventory(Player player) {
        if (player == null) {
            return;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    private void applySpeedAfterProfileLoad(Player player) {
        if (player == null || !(plugin instanceof Plugin)) {
            return;
        }
        Plugin bukkitPlugin = (Plugin) plugin;
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(bukkitPlugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            Profile refreshedProfile = coreApi.getProfile(uuid);
            Rank refreshed = refreshedProfile == null ? coreApi.getRank(uuid) : refreshedProfile.getRank();
            if (refreshed == null) {
                refreshed = Rank.DEFAULT;
            }
            applySpeed(online, refreshed);
            if (!pendingJoinAnnouncements.remove(uuid)) {
                return;
            }
            int networkLevel = refreshedProfile == null ? coreApi.getNetworkLevel(uuid) : refreshedProfile.getNetworkLevel();
            String plusColor = refreshedProfile == null ? null : refreshedProfile.getPlusColor();
            String mvpPlusPlusPrefixColor = refreshedProfile == null ? null : refreshedProfile.getMvpPlusPlusPrefixColor();
            boolean announced = broadcastRankJoinMessage(
                    online,
                    refreshed,
                    networkLevel,
                    plusColor,
                    mvpPlusPlusPrefixColor
            );
            if (!announced && refreshedProfile == null) {
                scheduleJoinAnnouncementRetries(uuid);
            }
        }, 20L);
    }

    private String stripNewLines(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private boolean broadcastRankJoinMessage(Player player,
                                             Rank rank,
                                             int networkLevel,
                                             String plusColor,
                                             String mvpPlusPlusPrefixColor) {
        if (player == null || rank == null || rank == Rank.STAFF || rank == Rank.YOUTUBE) {
            return false;
        }
        ChatColor nameColor = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
        if (rank == Rank.MVP_PLUS_PLUS) {
            String message = ChatColor.AQUA + " >"
                    + ChatColor.RED + ">"
                    + ChatColor.GREEN + "> "
                    + RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor)
                    + nameColor
                    + player.getName()
                    + JOIN_SUFFIX
                    + " "
                    + ChatColor.GREEN + "<"
                    + ChatColor.RED + "<"
                    + ChatColor.AQUA + "<";
            player.getServer().broadcastMessage(stripNewLines(message));
            return true;
        }
        if (rank == Rank.MVP_PLUS) {
            String message = RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor)
                    + nameColor
                    + player.getName()
                    + JOIN_SUFFIX;
            player.getServer().broadcastMessage(stripNewLines(message));
            return true;
        }
        return false;
    }

    private void scheduleJoinAnnouncementRetries(UUID uuid) {
        if (uuid == null || !(plugin instanceof Plugin) || !pendingJoinAnnouncements.add(uuid)) {
            return;
        }
        cancelJoinAnnouncementRetry(uuid);
        final int[] attemptsRemaining = {JOIN_MESSAGE_MAX_RETRIES};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer((Plugin) plugin, () -> {
            if (!pendingJoinAnnouncements.contains(uuid)) {
                cancelJoinAnnouncementRetry(uuid);
                return;
            }
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                pendingJoinAnnouncements.remove(uuid);
                cancelJoinAnnouncementRetry(uuid);
                return;
            }
            Profile profile = coreApi.getProfile(uuid);
            Rank rank = profile == null ? coreApi.getRank(uuid) : profile.getRank();
            if (rank == null) {
                rank = Rank.DEFAULT;
            }
            int networkLevel = profile == null ? coreApi.getNetworkLevel(uuid) : profile.getNetworkLevel();
            String plusColor = profile == null ? null : profile.getPlusColor();
            String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
            boolean announced = broadcastRankJoinMessage(online, rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
            attemptsRemaining[0]--;
            if (announced || profile != null || attemptsRemaining[0] <= 0) {
                pendingJoinAnnouncements.remove(uuid);
                cancelJoinAnnouncementRetry(uuid);
            }
        }, JOIN_MESSAGE_RETRY_INTERVAL_TICKS, JOIN_MESSAGE_RETRY_INTERVAL_TICKS);
        pendingJoinAnnouncementTasks.put(uuid, task);
    }

    private void cancelJoinAnnouncementRetry(UUID uuid) {
        if (uuid == null) {
            return;
        }
        BukkitTask task = pendingJoinAnnouncementTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void teleportToHubSpawn(Player player) {
        if (player == null) {
            return;
        }
        Location spawn = plugin.getHubSpawn();
        if (spawn == null) {
            spawn = player.getWorld() == null ? null : player.getWorld().getSpawnLocation();
        }
        if (spawn == null) {
            return;
        }
        Location randomized = randomizeHubSpawn(spawn);
        player.teleport(randomized);
        if (plugin instanceof Plugin) {
            final Location delayed = randomized.clone();
            Bukkit.getScheduler().runTask((Plugin) plugin, () -> {
                if (player.isOnline()) {
                    player.teleport(delayed);
                }
            });
        }
    }

    private Location randomizeHubSpawn(Location spawn) {
        Location randomized = spawn.clone();
        int offsetX = 0;
        int offsetZ = 0;
        while (offsetX == 0 && offsetZ == 0) {
            offsetX = ThreadLocalRandom.current().nextInt(
                    -HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS,
                    HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS + 1
            );
            offsetZ = ThreadLocalRandom.current().nextInt(
                    -HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS,
                    HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS + 1
            );
        }
        randomized.setX(randomized.getX() + offsetX);
        randomized.setZ(randomized.getZ() + offsetZ);
        return randomized;
    }
}
