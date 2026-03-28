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
import org.bukkit.Material;
import org.bukkit.World;
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
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class HubListener implements Listener {
    private static final String JOIN_SUFFIX = ChatColor.GOLD + " joined the lobby!";
    private static final long JOIN_MESSAGE_RETRY_INTERVAL_TICKS = 2L;
    private static final int JOIN_MESSAGE_MAX_RETRIES = 40;
    private static final long JOIN_PROFILE_REFRESH_DELAY_TICKS = 1L;
    private static final long JOIN_FLIGHT_REAPPLY_DELAY_TICKS = 1L;
    private static final double JOIN_FLIGHT_MIN_UPWARD_VELOCITY = 0.1D;
    private static final int HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS = 2;
    private static final int HUB_SPAWN_OFFSET_ATTEMPTS = 24;

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
        teleportToHubSpawn(player, true);
        plugin.handleHubJoin(player);
        applyJoinFlightAndVelocity(player, rank);
        scheduleJoinFlightAndVelocityApply(uuid, JOIN_FLIGHT_REAPPLY_DELAY_TICKS);
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
        teleportToHubSpawn(player, false);
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
            applyJoinFlightAndVelocity(online, refreshed);
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
        }, JOIN_PROFILE_REFRESH_DELAY_TICKS);
    }

    private void applyJoinFlightAndVelocity(Player player, Rank rank) {
        if (player == null) {
            return;
        }
        Rank effectiveRank = rank == null ? Rank.DEFAULT : rank;
        boolean enabled = effectiveRank.isAtLeast(Rank.VIP);
        player.setAllowFlight(enabled);
        if (!enabled) {
            player.setFlying(false);
            return;
        }
        if (!player.isFlying()) {
            player.setFlying(true);
        }
        Vector velocity = player.getVelocity();
        if (velocity == null) {
            return;
        }
        double y = Math.max(JOIN_FLIGHT_MIN_UPWARD_VELOCITY, velocity.getY());
        player.setVelocity(new Vector(velocity.getX(), y, velocity.getZ()));
    }

    private void scheduleJoinFlightAndVelocityApply(UUID uuid, long delayTicks) {
        if (uuid == null || !(plugin instanceof Plugin)) {
            return;
        }
        long safeDelay = Math.max(1L, delayTicks);
        Bukkit.getScheduler().runTaskLater((Plugin) plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            Rank rank = coreApi.getRank(uuid);
            if (rank == null) {
                rank = Rank.DEFAULT;
            }
            applyJoinFlightAndVelocity(player, rank);
        }, safeDelay);
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

    private void teleportToHubSpawn(Player player, boolean randomizeHorizontal) {
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
        final double databaseY = spawn.getY();
        Location target = spawn.clone();
        target.setY(databaseY);
        if (randomizeHorizontal) {
            target = randomizeHubSpawn(spawn, databaseY);
        }
        player.teleport(target);
    }

    private Location randomizeHubSpawn(Location spawn, double exactY) {
        Location randomized = spawn.clone();
        randomized.setY(exactY);
        World world = randomized.getWorld();
        if (world == null) {
            return randomized;
        }

        for (int attempt = 0; attempt < HUB_SPAWN_OFFSET_ATTEMPTS; attempt++) {
            int offsetX;
            int offsetZ;
            do {
                offsetX = ThreadLocalRandom.current().nextInt(
                        -HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS,
                        HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS + 1
                );
                offsetZ = ThreadLocalRandom.current().nextInt(
                        -HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS,
                        HUB_SPAWN_HORIZONTAL_RADIUS_BLOCKS + 1
                );
            } while (offsetX == 0 && offsetZ == 0);

            double targetX = spawn.getX() + offsetX;
            double targetZ = spawn.getZ() + offsetZ;
            if (!isSafeAtExactY(world, targetX, exactY, targetZ)) {
                continue;
            }
            randomized.setX(targetX);
            randomized.setZ(targetZ);
            return randomized;
        }

        // If no nearby offset is safe at the configured Y, keep the exact DB location.
        randomized.setX(spawn.getX());
        randomized.setZ(spawn.getZ());
        return randomized;
    }

    private boolean isSafeAtExactY(World world, double x, double y, double z) {
        if (world == null) {
            return false;
        }
        Location feet = new Location(world, x, y, z);
        Location head = feet.clone().add(0.0d, 1.0d, 0.0d);
        Location below = feet.clone().add(0.0d, -1.0d, 0.0d);
        Material feetType = feet.getBlock().getType();
        Material headType = head.getBlock().getType();
        Material belowType = below.getBlock().getType();
        if (isSolid(feetType) || isSolid(headType)) {
            return false;
        }
        return isSolid(belowType);
    }

    private boolean isSolid(Material material) {
        if (material == null) {
            return false;
        }
        try {
            return material.isSolid();
        } catch (NoSuchMethodError ignored) {
            // Legacy safety fallback.
            return material != Material.AIR;
        }
    }
}
