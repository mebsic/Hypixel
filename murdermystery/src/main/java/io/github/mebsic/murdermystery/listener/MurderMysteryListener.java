package io.github.mebsic.murdermystery.listener;

import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.service.QueueService;
import io.github.mebsic.murdermystery.game.MurderMysteryGamePlayer;
import io.github.mebsic.murdermystery.game.MurderMysteryRole;
import io.github.mebsic.murdermystery.manager.MurderMysteryGameManager;
import io.github.mebsic.murdermystery.MurderMysteryPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MurderMysteryListener implements Listener {
    private static final String KNIFE_NAME = "Knife";
    private static final String KNIFE_LORE = "Use your Knife to kill players.";
    private static final String PROJECTILE_LAUNCH_X_META = "murdermystery-launch-x";
    private static final String PROJECTILE_LAUNCH_Y_META = "murdermystery-launch-y";
    private static final String PROJECTILE_LAUNCH_Z_META = "murdermystery-launch-z";
    private static final int ARROW_HOTBAR_SLOT = 2;
    private static final double DETECTIVE_HERO_ARROW_COOLDOWN_SECONDS = 5.0D;
    private static final double DETECTIVE_HERO_ARROW_RECHARGE_SECONDS = 5.0D;
    private static final double MURDERER_KNIFE_THROW_COOLDOWN_SECONDS = 5.5D;
    private static final double MURDERER_KNIFE_THROWING_SECONDS = 0.5D;
    private static final double THROWN_KNIFE_LIFETIME_SECONDS = 5.0D;
    private static final double MURDERER_THROW_SLOWNESS_SECONDS = MURDERER_KNIFE_THROWING_SECONDS;
    private static final int MURDERER_THROW_SLOWNESS_AMPLIFIER = 1; // Slowness II
    private static final double THROWN_KNIFE_SPEED_BLOCKS_PER_TICK = 0.55D;
    private static final double THROWN_KNIFE_LAUNCH_HEIGHT = 0.75D;
    private static final double THROWN_KNIFE_SPAWN_FORWARD_OFFSET = 0.7D;
    private static final double THROWN_KNIFE_HIT_RADIUS = 0.9D;
    private static final double THROWN_KNIFE_ARROW_COLLISION_RADIUS = 0.65D;
    private static final double THROWN_KNIFE_BLOCK_SAMPLE_STEP = 0.2D;
    private static final EulerAngle THROWN_KNIFE_ARM_POSE = new EulerAngle(Math.toRadians(180.0D), 0.0D, 0.0D);
    private static final int GLASS_PANE_BREAK_ANIMATION_STAGE = 4;
    private static final long GLASS_PANE_BREAK_RESET_SECONDS = 5L;
    private static final long MURDERER_KNIFE_DRIP_PARTICLE_INTERVAL_TICKS = 3L;
    private static final double MURDERER_KNIFE_DRIP_PARTICLE_CHANCE = 0.35D;
    private static final double MURDERER_KNIFE_DRIP_HAND_HEIGHT = 1.15D;
    private static final double MURDERER_KNIFE_DRIP_HAND_FORWARD_OFFSET = 0.42D;
    private static final double MURDERER_KNIFE_DRIP_HAND_SIDE_OFFSET = -0.17D;
    private static final double MURDERER_KNIFE_DRIP_HAND_JITTER = 0.07D;
    private static final float MURDERER_KNIFE_DRIP_RED = 1.0F;
    private static final float MURDERER_KNIFE_DRIP_GREEN = 0.0F;
    private static final float MURDERER_KNIFE_DRIP_BLUE = 0.0F;
    private static final int MURDERER_KNIFE_DRIP_PARTICLE_RADIUS = 64;
    private static final Sound GLASS_SHATTER_SOUND = resolveCompatibleSound("GLASS", "BLOCK_GLASS_BREAK");
    private static final Sound ARROW_KNIFE_CLANK_SOUND = resolveCompatibleSound("ITEM_BREAK", "ENTITY_ITEM_BREAK");
    private static final float ARROW_KNIFE_CLANK_VOLUME = 1.0F;
    private static final float ARROW_KNIFE_CLANK_PITCH = 1.35F;
    private static final double ELIMINATED_PLAYER_HEALTH = 0.01D;

    private final MurderMysteryPlugin plugin;
    private final MurderMysteryGameManager gameManager;
    private final QueueService queueService;
    private final Map<UUID, ThrownKnife> activeThrownKnives;
    private final Map<UUID, BukkitTask> pendingKnifeLaunches;
    private final Map<String, BukkitTask> paneBreakResetTasks;

    public MurderMysteryListener(MurderMysteryPlugin plugin, MurderMysteryGameManager gameManager, QueueService queueService) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.queueService = queueService;
        this.activeThrownKnives = new HashMap<>();
        this.pendingKnifeLaunches = new HashMap<>();
        this.paneBreakResetTasks = new HashMap<>();
        plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickMurdererKnifeDripParticles,
                MURDERER_KNIFE_DRIP_PARTICLE_INTERVAL_TICKS,
                MURDERER_KNIFE_DRIP_PARTICLE_INTERVAL_TICKS
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (queueService != null) {
            queueService.handleJoin(event.getPlayer());
        } else {
            gameManager.handleJoin(event.getPlayer());
        }
        if (plugin.getActionBarService() != null) {
            plugin.getActionBarService().handlePlayerJoin(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelPendingKnifeLaunch(event.getPlayer().getUniqueId());
        despawnThrownKnife(event.getPlayer().getUniqueId());
        if (queueService != null) {
            queueService.handleQuit(event.getPlayer());
        } else {
            gameManager.handleQuit(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        cancelPendingKnifeLaunch(event.getEntity().getUniqueId());
        despawnThrownKnife(event.getEntity().getUniqueId());
        event.setDeathMessage(null);
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!gameManager.isInGame(event.getPlayer())) {
            return;
        }
        if (gameManager.getState() == GameState.IN_GAME) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isInGame(player)) {
            return;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            return;
        }
        event.setCancelled(true);
        resyncDamagedItemSlots(player, event.getItem());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isInGame(player)) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK) {
            gameManager.trackOpenableInteraction(event.getClickedBlock());
        }
        if (attemptMurdererKnifeThrow(player, event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (attemptMurdererKnifeThrow(player, player == null ? null : player.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    private boolean attemptMurdererKnifeThrow(Player player, ItemStack interactionItem) {
        if (player == null || !gameManager.isInGame(player)) {
            return false;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            return false;
        }
        MurderMysteryGamePlayer gp = gameManager.getMurderMysteryPlayer(player);
        if (gp == null || gp.getRole() != MurderMysteryRole.MURDERER || !gp.isAlive()) {
            return false;
        }
        ItemStack item = interactionItem;
        if (item == null || item.getType() == Material.AIR) {
            item = player.getItemInHand();
        }
        if (!isMurdererKnife(item)) {
            return false;
        }
        long now = System.currentTimeMillis();
        long lastKnifeThrow = gp.getLastKnifeThrow();
        if (lastKnifeThrow > now) {
            lastKnifeThrow = 0L;
            gp.setLastKnifeThrow(0L);
        }
        if (now - lastKnifeThrow < secondsToMillis(MURDERER_KNIFE_THROW_COOLDOWN_SECONDS)) {
            boolean canceledPendingThrow = cancelPendingKnifeLaunch(player.getUniqueId());
            if (canceledPendingThrow) {
                gp.setLastKnifeThrow(0L);
                player.removePotionEffect(PotionEffectType.SLOW);
                if (plugin.getActionBarService() != null) {
                    plugin.getActionBarService().showKnifeStoppedChargingActionBar(player);
                }
            }
            return false;
        }
        if (!scheduleKnifeLaunch(player, item, THROWN_KNIFE_LIFETIME_SECONDS)) {
            return false;
        }
        gp.setLastKnifeThrow(now);
        if (plugin.getActionBarService() != null) {
            plugin.getActionBarService().showKnifeThrowActionBar(player);
        }
        player.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.SLOW,
                        (int) Math.max(1L, secondsToTicks(MURDERER_THROW_SLOWNESS_SECONDS) - 1L),
                        MURDERER_THROW_SLOWNESS_AMPLIFIER,
                        false,
                        false
                ),
                true
        );
        return true;
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isInGame(player)) {
            return;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        if (item.getType() == Material.GOLD_INGOT && gameManager.isTrackedMapDropItem(event.getItem())) {
            final org.bukkit.entity.Item pickedItemEntity = event.getItem();
            int stackedAmount = Math.max(1, item.getAmount());
            int pickedAmount = stackedAmount;
            try {
                pickedAmount = Math.max(0, stackedAmount - event.getRemaining());
            } catch (Throwable ignored) {
                // Legacy API safety: default to the visible stack amount.
            }
            if (pickedAmount <= 0) {
                return;
            }
            final int creditedGold = pickedAmount;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (pickedItemEntity == null || pickedItemEntity.isDead() || !pickedItemEntity.isValid()) {
                    gameManager.untrackMapDropItem(pickedItemEntity);
                }
                if (!gameManager.isInGame(player) || gameManager.getState() != GameState.IN_GAME) {
                    return;
                }
                gameManager.addGold(player, creditedGold);
            });
            return;
        }
        if (item.getType() == Material.BOW) {
            event.setCancelled(true);
            MurderMysteryGamePlayer mmPlayer = gameManager.getMurderMysteryPlayer(player);
            if (mmPlayer == null || !mmPlayer.isAlive() || mmPlayer.getRole() != MurderMysteryRole.INNOCENT) {
                return;
            }
            event.getItem().remove();
            gameManager.convertDroppedBowCarrierToHero(player);
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player victim = (Player) event.getEntity();
        if (!gameManager.isInGame(victim)) {
            return;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            event.setCancelled(true);
            return;
        }
        MurderMysteryGamePlayer victimData = gameManager.getMurderMysteryPlayer(victim);
        if (victimData == null || !victimData.isAlive()) {
            event.setCancelled(true);
            Entity damager = event.getDamager();
            if (damager instanceof Arrow) {
                removeProjectileEntity(damager);
            }
            return;
        }

        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            Player attacker = (Player) damager;
            MurderMysteryGamePlayer attackerData = gameManager.getMurderMysteryPlayer(attacker);
            if (attackerData == null || !attackerData.isAlive()) {
                event.setCancelled(true);
                return;
            }
            if (attackerData.getRole() == MurderMysteryRole.MURDERER && victimData.getRole() != MurderMysteryRole.MURDERER) {
                ItemStack held = attacker.getItemInHand();
                if (!isMurdererKnife(held)) {
                    event.setCancelled(true);
                    return;
                }
                event.setCancelled(true);
                attackerData.addKill();
                setEliminatedHealth(victim);
                gameManager.handleDeath(victim, attacker, null, MurderMysteryGameManager.KillType.KNIFE);
                return;
            }
            event.setCancelled(true);
            return;
        }
        if (damager instanceof Arrow) {
            Arrow arrow = (Arrow) damager;
            if (!(arrow.getShooter() instanceof Player)) {
                return;
            }
            Player shooter = (Player) arrow.getShooter();
            MurderMysteryGamePlayer shooterData = gameManager.getMurderMysteryPlayer(shooter);
            if (shooterData == null || !shooterData.isAlive()) {
                cancelArrowHit(event, arrow);
                return;
            }
            double distanceMeters = projectileDistanceMeters(arrow, shooter, victim);
            String distanceSuffix = formatDistanceSuffix(distanceMeters);
            if (shooterData.getRole() == MurderMysteryRole.DETECTIVE || shooterData.getRole() == MurderMysteryRole.HERO) {
                cancelArrowHit(event, arrow);
                if (shooter.getUniqueId().equals(victim.getUniqueId())) {
                    setEliminatedHealth(victim);
                    gameManager.handleDeath(victim, shooter, "You successfully shot yourself!");
                    return;
                }
                if (victimData.getRole() == MurderMysteryRole.MURDERER) {
                    shooterData.addKill();
                }
                gameManager.sendProjectileKillDistanceMessage(shooter, victim, distanceMeters);
                setEliminatedHealth(victim);
                gameManager.handleDeath(victim, shooter, "A player killed you! " + distanceSuffix, MurderMysteryGameManager.KillType.BOW);
                return;
            }
            if (shooterData.getRole() == MurderMysteryRole.MURDERER) {
                cancelArrowHit(event, arrow);
                if (shooter.getUniqueId().equals(victim.getUniqueId())) {
                    setEliminatedHealth(victim);
                    gameManager.handleDeath(victim, null, "You successfully shot yourself!", MurderMysteryGameManager.KillType.BOW);
                    return;
                }
                if (victimData.getRole() != MurderMysteryRole.MURDERER) {
                    shooterData.addKill();
                    gameManager.sendProjectileKillDistanceMessage(shooter, victim, distanceMeters);
                    setEliminatedHealth(victim);
                    gameManager.handleDeath(victim, shooter, "The Murderer shot you! " + distanceSuffix, MurderMysteryGameManager.KillType.BOW);
                    return;
                }
                return;
            }
            cancelArrowHit(event, arrow);
            return;
        }
    }

    @EventHandler
    public void onAnyDamage(EntityDamageEvent event) {
        UUID thrownKnifeShooterUuid = findThrownKnifeShooterByDisplay(event.getEntity());
        if (gameManager.isDroppedBowDisplay(event.getEntity()) || thrownKnifeShooterUuid != null) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent byEntityEvent = (EntityDamageByEntityEvent) event;
                Entity damager = byEntityEvent.getDamager();
                if (damager instanceof Arrow) {
                    if (thrownKnifeShooterUuid != null) {
                        handleArrowKnifeCollision(
                                thrownKnifeShooterUuid,
                                (Arrow) damager,
                                damager.getLocation()
                        );
                    } else {
                        removeProjectileEntity(damager);
                    }
                }
            }
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (!gameManager.isInGame(player)) {
            return;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.DROWNING) {
            event.setCancelled(true);
            MurderMysteryGamePlayer mmPlayer = gameManager.getMurderMysteryPlayer(player);
            if (mmPlayer == null || !mmPlayer.isAlive()) {
                return;
            }
            player.setRemainingAir(player.getMaximumAir());
            gameManager.handleDeath(player, null, "You drowned!");
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        ItemStack heldItem = player.getItemInHand();
        ItemStack bowSnapshot = heldItem != null && heldItem.getType() == Material.BOW ? heldItem.clone() : null;
        Entity projectileEntity = event.getProjectile() instanceof Entity ? (Entity) event.getProjectile() : null;
        if (event.isCancelled()) {
            removeProjectileEntity(projectileEntity);
            return;
        }
        if (!gameManager.isInGame(player)) {
            event.setCancelled(true);
            removeProjectileEntity(projectileEntity);
            player.updateInventory();
            return;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            event.setCancelled(true);
            removeProjectileEntity(projectileEntity);
            return;
        }
        MurderMysteryGamePlayer gp = gameManager.getMurderMysteryPlayer(player);
        if (gp == null || !gp.isAlive()) {
            event.setCancelled(true);
            removeProjectileEntity(projectileEntity);
            return;
        }
        if (isDetectiveLikeCooldownRole(gp.getRole(), gp)) {
            long now = System.currentTimeMillis();
            if (now - gp.getLastArrowShot() < secondsToMillis(DETECTIVE_HERO_ARROW_COOLDOWN_SECONDS)) {
                event.setCancelled(true);
                removeProjectileEntity(projectileEntity);
                player.updateInventory();
                return;
            }
        }
        Object projectile = event.getProjectile();
        if (projectile instanceof Arrow) {
            Arrow launchedArrow = (Arrow) projectile;
            // Fix arrow bounce
            launchedArrow.setVelocity(launchedArrow.getVelocity());
            setProjectileLaunchMetadata(launchedArrow, player);
            gameManager.trackRoundArrow(launchedArrow);
            if (bowSnapshot != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!gameManager.isInGame(player) || gameManager.getState() != GameState.IN_GAME) {
                        return;
                    }
                    resyncDamagedItemSlots(player, bowSnapshot);
                });
            }
        }
        if (!isDetectiveLikeCooldownRole(gp.getRole(), gp)) {
            return;
        }
        long now = System.currentTimeMillis();
        gp.setLastArrowShot(now);
        player.getInventory().remove(Material.ARROW);
        if (plugin.getActionBarService() != null) {
            plugin.getActionBarService().showBowChargeActionBar(player);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (gameManager.isInGame(player) && gameManager.getState() == GameState.IN_GAME) {
                player.getInventory().setItem(ARROW_HOTBAR_SLOT, new ItemStack(Material.ARROW, 1));
            }
        }, secondsToTicks(DETECTIVE_HERO_ARROW_RECHARGE_SECONDS));
    }

    private void tickMurdererKnifeDripParticles() {
        if (gameManager.getState() != GameState.IN_GAME) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == null || !player.isOnline() || !gameManager.isInGame(player)) {
                continue;
            }
            MurderMysteryGamePlayer mmPlayer = gameManager.getMurderMysteryPlayer(player);
            if (mmPlayer == null || !mmPlayer.isAlive() || mmPlayer.getRole() != MurderMysteryRole.MURDERER) {
                continue;
            }
            if (!isMurdererKnife(player.getItemInHand())) {
                continue;
            }
            if (Math.random() > MURDERER_KNIFE_DRIP_PARTICLE_CHANCE) {
                continue;
            }
            spawnMurdererKnifeDripParticle(player);
        }
    }

    private void spawnMurdererKnifeDripParticle(Player player) {
        if (player == null) {
            return;
        }
        Location handLocation = murdererKnifeHandLocation(player);
        if (handLocation == null || handLocation.getWorld() == null) {
            return;
        }
        double jitterX = (Math.random() - 0.5D) * MURDERER_KNIFE_DRIP_HAND_JITTER;
        double jitterY = (Math.random() - 0.5D) * MURDERER_KNIFE_DRIP_HAND_JITTER;
        double jitterZ = (Math.random() - 0.5D) * MURDERER_KNIFE_DRIP_HAND_JITTER;
        handLocation.add(jitterX, jitterY, jitterZ);
        playRedKnifeDust(handLocation);
    }

    private void playRedKnifeDust(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        try {
            location.getWorld().spigot().playEffect(
                    location,
                    Effect.COLOURED_DUST,
                    0,
                    0,
                    MURDERER_KNIFE_DRIP_RED,
                    MURDERER_KNIFE_DRIP_GREEN,
                    MURDERER_KNIFE_DRIP_BLUE,
                    1.0F,
                    0,
                    MURDERER_KNIFE_DRIP_PARTICLE_RADIUS
            );
        } catch (Throwable ignored) {
            location.getWorld().playEffect(location, Effect.COLOURED_DUST, 0);
        }
    }

    private Location murdererKnifeHandLocation(Player player) {
        Location base = player.getLocation().clone().add(0.0D, MURDERER_KNIFE_DRIP_HAND_HEIGHT, 0.0D);
        double yawRadians = Math.toRadians(base.getYaw());
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        base.add(
                (forwardX * MURDERER_KNIFE_DRIP_HAND_FORWARD_OFFSET) + (rightX * MURDERER_KNIFE_DRIP_HAND_SIDE_OFFSET),
                0.0D,
                (forwardZ * MURDERER_KNIFE_DRIP_HAND_FORWARD_OFFSET) + (rightZ * MURDERER_KNIFE_DRIP_HAND_SIDE_OFFSET)
        );
        return base;
    }

    private void setEliminatedHealth(Player victim) {
        if (victim == null || victim.isDead()) {
            return;
        }
        double maxHealth = victim.getMaxHealth();
        if (maxHealth <= 0.0D) {
            return;
        }
        double targetHealth = Math.min(maxHealth, ELIMINATED_PLAYER_HEALTH);
        if (targetHealth <= 0.0D) {
            return;
        }
        if (victim.getHealth() > targetHealth) {
            victim.setHealth(targetHealth);
        }
    }

    private boolean isDetectiveLikeCooldownRole(MurderMysteryRole role, MurderMysteryGamePlayer gp) {
        if (gp == null) {
            return false;
        }
        if (role != MurderMysteryRole.DETECTIVE && role != MurderMysteryRole.HERO) {
            return false;
        }
        return role != MurderMysteryRole.HERO || !gp.isHeroFromGold();
    }

    private void setProjectileLaunchMetadata(Entity projectile, Player shooter) {
        if (projectile == null || shooter == null) {
            return;
        }
        Location launch = shooter.getLocation();
        projectile.setMetadata(PROJECTILE_LAUNCH_X_META, new FixedMetadataValue(plugin, launch.getX()));
        projectile.setMetadata(PROJECTILE_LAUNCH_Y_META, new FixedMetadataValue(plugin, launch.getY()));
        projectile.setMetadata(PROJECTILE_LAUNCH_Z_META, new FixedMetadataValue(plugin, launch.getZ()));
    }

    private String formatDistanceSuffix(double meters) {
        return ChatColor.GRAY + "(" + String.format(Locale.US, "%.2f", meters) + "m)";
    }

    private double projectileDistanceMeters(Entity projectile, Player shooter, Player victim) {
        if (victim == null) {
            return 0.0D;
        }
        Location victimLocation = victim.getLocation();
        if (projectile != null) {
            double launchX = projectileMetadataDouble(projectile, PROJECTILE_LAUNCH_X_META);
            double launchY = projectileMetadataDouble(projectile, PROJECTILE_LAUNCH_Y_META);
            double launchZ = projectileMetadataDouble(projectile, PROJECTILE_LAUNCH_Z_META);
            if (!Double.isNaN(launchX) && !Double.isNaN(launchY) && !Double.isNaN(launchZ)) {
                double dx = victimLocation.getX() - launchX;
                double dy = victimLocation.getY() - launchY;
                double dz = victimLocation.getZ() - launchZ;
                return Math.max(0.0D, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        if (shooter == null || shooter.getWorld() == null || !shooter.getWorld().equals(victim.getWorld())) {
            return 0.0D;
        }
        return Math.max(0.0D, shooter.getLocation().distance(victimLocation));
    }

    private double projectileMetadataDouble(Entity projectile, String key) {
        if (projectile == null || key == null || key.trim().isEmpty() || !projectile.hasMetadata(key)) {
            return Double.NaN;
        }
        for (MetadataValue value : projectile.getMetadata(key)) {
            if (value == null || value.getOwningPlugin() != plugin) {
                continue;
            }
            return value.asDouble();
        }
        return Double.NaN;
    }

    private void cancelArrowHit(EntityDamageByEntityEvent event, Arrow arrow) {
        if (event != null) {
            event.setCancelled(true);
        }
        removeProjectileEntity(arrow);
    }

    private void removeProjectileEntity(Entity projectile) {
        if (projectile == null || !projectile.isValid() || projectile.isDead()) {
            return;
        }
        projectile.remove();
    }

    private boolean scheduleKnifeLaunch(Player shooter, ItemStack sourceKnife, double lifetimeSeconds) {
        if (shooter == null || lifetimeSeconds <= 0.0D) {
            return false;
        }
        UUID shooterUuid = shooter.getUniqueId();
        cancelPendingKnifeLaunch(shooterUuid);
        ItemStack knifeSnapshot = sourceKnife == null ? null : sourceKnife.clone();
        BukkitTask launchTask;
        try {
            launchTask = plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> {
                        pendingKnifeLaunches.remove(shooterUuid);
                        Player liveShooter = plugin.getServer().getPlayer(shooterUuid);
                        if (!isValidKnifeShooter(liveShooter)) {
                            return;
                        }
                        launchThrownKnife(liveShooter, knifeSnapshot, lifetimeSeconds);
                    },
                    secondsToTicks(MURDERER_KNIFE_THROWING_SECONDS)
            );
        } catch (Throwable ignored) {
            return false;
        }
        pendingKnifeLaunches.put(shooterUuid, launchTask);
        return true;
    }

    private boolean cancelPendingKnifeLaunch(UUID shooterUuid) {
        if (shooterUuid == null) {
            return false;
        }
        BukkitTask pending = pendingKnifeLaunches.remove(shooterUuid);
        if (pending != null) {
            pending.cancel();
            return true;
        }
        return false;
    }

    private boolean launchThrownKnife(Player shooter, ItemStack sourceKnife, double lifetimeSeconds) {
        if (shooter == null || lifetimeSeconds <= 0.0D) {
            return false;
        }
        if (shooter.getWorld() == null) {
            return false;
        }
        UUID shooterUuid = shooter.getUniqueId();
        despawnThrownKnife(shooterUuid);
        Vector direction = shooter.getEyeLocation().getDirection();
        if (direction == null || direction.lengthSquared() <= 0.000001D) {
            direction = shooter.getLocation().getDirection();
        }
        if (direction == null || direction.lengthSquared() <= 0.000001D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }
        direction = direction.normalize();
        Vector velocity = direction.clone().multiply(THROWN_KNIFE_SPEED_BLOCKS_PER_TICK);
        Location launchLocation = shooter.getLocation().clone().add(0.0D, THROWN_KNIFE_LAUNCH_HEIGHT, 0.0D);
        Location spawnLocation = launchLocation.clone().add(direction.clone().multiply(THROWN_KNIFE_SPAWN_FORWARD_OFFSET));
        spawnLocation.setPitch(0.0F);
        ArmorStand display;
        try {
            display = spawnLocation.getWorld().spawn(spawnLocation, ArmorStand.class);
        } catch (Throwable ignored) {
            return false;
        }
        configureThrownKnifeDisplay(display, sourceKnife);
        ThrownKnife thrownKnife = new ThrownKnife(
                display,
                velocity,
                launchLocation.clone(),
                System.currentTimeMillis() + secondsToMillis(lifetimeSeconds)
        );
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                () -> tickThrownKnife(shooterUuid),
                1L,
                1L
        );
        thrownKnife.setTask(task);
        activeThrownKnives.put(shooterUuid, thrownKnife);
        return true;
    }

    private void configureThrownKnifeDisplay(ArmorStand display, ItemStack sourceKnife) {
        if (display == null) {
            return;
        }
        display.setVisible(false);
        display.setGravity(false);
        display.setArms(true);
        display.setBasePlate(false);
        display.setSmall(false);
        display.setRightArmPose(THROWN_KNIFE_ARM_POSE);
        trySetArmorStandMarker(display);
        ItemStack knifeDisplay = sourceKnife == null ? new ItemStack(Material.IRON_SWORD, 1) : sourceKnife.clone();
        if (knifeDisplay.getType() == Material.AIR) {
            knifeDisplay = new ItemStack(Material.IRON_SWORD, 1);
        }
        knifeDisplay.setAmount(1);
        display.setItemInHand(knifeDisplay);
    }

    private void tickThrownKnife(UUID shooterUuid) {
        if (shooterUuid == null) {
            return;
        }
        ThrownKnife knife = activeThrownKnives.get(shooterUuid);
        if (knife == null) {
            return;
        }
        if (System.currentTimeMillis() >= knife.expiresAtMillis) {
            despawnThrownKnife(shooterUuid);
            return;
        }
        ArmorStand display = knife.display;
        if (display == null || !display.isValid() || display.isDead()) {
            despawnThrownKnife(shooterUuid);
            return;
        }
        Player shooter = plugin.getServer().getPlayer(shooterUuid);
        if (!isValidKnifeShooter(shooter)) {
            despawnThrownKnife(shooterUuid);
            return;
        }
        Location from = display.getLocation();
        Location to = from.clone().add(knife.velocity);
        to.setPitch(0.0F);
        if (to.getWorld() == null || !to.getWorld().equals(from.getWorld())) {
            despawnThrownKnife(shooterUuid);
            return;
        }
        Location blockCollision = firstThrownKnifeBlockCollision(from, to);
        Location hitScanEnd = blockCollision == null ? to : blockCollision;
        shatterGlassPanesAlongPath(from, hitScanEnd, knife);
        ArrowKnifeCollision arrowCollision = findArrowKnifeCollision(shooter, from, hitScanEnd);
        if (arrowCollision != null) {
            handleArrowKnifeCollision(shooterUuid, arrowCollision.arrow, arrowCollision.location);
            return;
        }
        Player victim = findKnifeVictim(shooter, from, hitScanEnd);
        if (victim != null) {
            handleThrownKnifeHit(knife, shooter, victim);
            despawnThrownKnife(shooterUuid);
            return;
        }
        if (blockCollision != null) {
            despawnThrownKnife(shooterUuid);
            return;
        }
        display.teleport(to);
    }

    private boolean isValidKnifeShooter(Player shooter) {
        if (shooter == null || !shooter.isOnline()) {
            return false;
        }
        if (!gameManager.isInGame(shooter) || gameManager.getState() != GameState.IN_GAME) {
            return false;
        }
        MurderMysteryGamePlayer shooterData = gameManager.getMurderMysteryPlayer(shooter);
        return shooterData != null && shooterData.isAlive() && shooterData.getRole() == MurderMysteryRole.MURDERER;
    }

    private Player findKnifeVictim(Player shooter, Location from, Location to) {
        if (shooter == null || from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return null;
        }
        if (!from.getWorld().equals(to.getWorld())) {
            return null;
        }
        Vector start = from.toVector();
        Vector end = to.toVector();
        double hitRadiusSquared = THROWN_KNIFE_HIT_RADIUS * THROWN_KNIFE_HIT_RADIUS;
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (candidate == null || !candidate.isOnline()) {
                continue;
            }
            if (candidate.getUniqueId().equals(shooter.getUniqueId())) {
                continue;
            }
            if (!candidate.getWorld().equals(from.getWorld())) {
                continue;
            }
            if (!gameManager.isInGame(candidate)) {
                continue;
            }
            MurderMysteryGamePlayer victimData = gameManager.getMurderMysteryPlayer(candidate);
            if (victimData == null || !victimData.isAlive()) {
                continue;
            }
            if (victimData.getRole() == MurderMysteryRole.MURDERER) {
                continue;
            }
            Vector victimCenter = candidate.getLocation().add(0.0D, 1.0D, 0.0D).toVector();
            if (distanceSquaredPointToSegment(victimCenter, start, end) <= hitRadiusSquared) {
                return candidate;
            }
        }
        return null;
    }

    private ArrowKnifeCollision findArrowKnifeCollision(Player knifeShooter, Location from, Location to) {
        if (knifeShooter == null || from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return null;
        }
        if (!from.getWorld().equals(to.getWorld())) {
            return null;
        }
        World world = from.getWorld();
        Vector knifeStart = from.toVector();
        Vector knifeEnd = to.toVector();
        double collisionRadiusSquared = THROWN_KNIFE_ARROW_COLLISION_RADIUS * THROWN_KNIFE_ARROW_COLLISION_RADIUS;
        ArrowKnifeCollision bestCollision = null;
        double bestDistanceSquared = Double.MAX_VALUE;

        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Arrow)) {
                continue;
            }
            Arrow arrow = (Arrow) entity;
            if (!isArrowKnifeCollisionCandidate(arrow, knifeShooter, world)) {
                continue;
            }
            Location arrowLocation = arrow.getLocation();
            Vector arrowEnd = arrowLocation.toVector();
            Vector arrowStart = arrowEnd.clone().subtract(arrow.getVelocity());
            double distanceSquared = distanceSquaredSegmentToSegment(knifeStart, knifeEnd, arrowStart, arrowEnd);
            if (distanceSquared > collisionRadiusSquared || distanceSquared >= bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            bestCollision = new ArrowKnifeCollision(
                    arrow,
                    closestPointOnSegment(arrowEnd, knifeStart, knifeEnd, world)
            );
        }
        return bestCollision;
    }

    private boolean isArrowKnifeCollisionCandidate(Arrow arrow, Player knifeShooter, World world) {
        if (arrow == null || knifeShooter == null || world == null) {
            return false;
        }
        if (!arrow.isValid() || arrow.isDead() || arrow.getWorld() == null || !arrow.getWorld().equals(world)) {
            return false;
        }
        if (arrow.getVelocity() == null || arrow.getVelocity().lengthSquared() <= 0.000001D) {
            return false;
        }
        if (!(arrow.getShooter() instanceof Player)) {
            return false;
        }
        Player shooter = (Player) arrow.getShooter();
        if (shooter == null || !shooter.isOnline() || !gameManager.isInGame(shooter)) {
            return false;
        }
        MurderMysteryGamePlayer shooterData = gameManager.getMurderMysteryPlayer(shooter);
        return shooterData != null && shooterData.isAlive();
    }

    private void handleArrowKnifeCollision(UUID knifeShooterUuid, Arrow arrow, Location collisionLocation) {
        if (knifeShooterUuid == null || arrow == null) {
            return;
        }
        Location soundLocation = collisionLocation == null ? arrow.getLocation() : collisionLocation;
        playArrowKnifeClank(soundLocation);
        removeProjectileEntity(arrow);
        despawnThrownKnife(knifeShooterUuid);
    }

    private void playArrowKnifeClank(Location location) {
        if (location == null || location.getWorld() == null || ARROW_KNIFE_CLANK_SOUND == null) {
            return;
        }
        try {
            location.getWorld().playSound(
                    location,
                    ARROW_KNIFE_CLANK_SOUND,
                    ARROW_KNIFE_CLANK_VOLUME,
                    ARROW_KNIFE_CLANK_PITCH
            );
        } catch (IllegalArgumentException ignored) {
            // Sound enum mismatch on legacy/newer API variants.
        }
    }

    private void handleThrownKnifeHit(ThrownKnife knife, Player shooter, Player victim) {
        if (knife == null || shooter == null || victim == null) {
            return;
        }
        MurderMysteryGamePlayer shooterData = gameManager.getMurderMysteryPlayer(shooter);
        MurderMysteryGamePlayer victimData = gameManager.getMurderMysteryPlayer(victim);
        if (shooterData == null || victimData == null || !victimData.isAlive()) {
            return;
        }
        if (shooterData.getRole() != MurderMysteryRole.MURDERER || victimData.getRole() == MurderMysteryRole.MURDERER) {
            return;
        }
        shooterData.addKill();
        double distanceMeters = distanceFromLaunch(knife.launchLocation, victim.getLocation());
        String distanceSuffix = formatDistanceSuffix(distanceMeters);
        gameManager.sendProjectileKillDistanceMessage(shooter, victim, distanceMeters);
        setEliminatedHealth(victim);
        gameManager.handleDeath(
                victim,
                shooter,
                "The Murderer threw their Knife at you! " + distanceSuffix,
                MurderMysteryGameManager.KillType.THROWN_KNIFE
        );
    }

    private double distanceFromLaunch(Location launchLocation, Location victimLocation) {
        if (launchLocation == null || victimLocation == null) {
            return 0.0D;
        }
        if (launchLocation.getWorld() == null || victimLocation.getWorld() == null || !launchLocation.getWorld().equals(victimLocation.getWorld())) {
            return 0.0D;
        }
        return Math.max(0.0D, launchLocation.distance(victimLocation));
    }

    private void shatterGlassPanesAlongPath(Location from, Location to, ThrownKnife knife) {
        if (from == null || to == null || knife == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        World world = from.getWorld();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
        int samples = Math.max(1, (int) Math.ceil(length / THROWN_KNIFE_BLOCK_SAMPLE_STEP));
        for (int i = 1; i <= samples; i++) {
            double progress = i / (double) samples;
            double x = from.getX() + (dx * progress);
            double y = from.getY() + (dy * progress);
            double z = from.getZ() + (dz * progress);
            Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (!isGlassPane(block.getType())) {
                continue;
            }
            String blockKey = paneBlockKey(block);
            if (!knife.shatteredPaneKeys.add(blockKey)) {
                continue;
            }
            playGlassPaneShatterEffect(block);
        }
    }

    private Location firstThrownKnifeBlockCollision(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return null;
        }
        World world = from.getWorld();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
        int samples = Math.max(1, (int) Math.ceil(length / THROWN_KNIFE_BLOCK_SAMPLE_STEP));
        for (int i = 1; i <= samples; i++) {
            double progress = i / (double) samples;
            double x = from.getX() + (dx * progress);
            double y = from.getY() + (dy * progress);
            double z = from.getZ() + (dz * progress);
            Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (!isThrownKnifeBlockingBlock(block.getType())) {
                continue;
            }
            Location collision = new Location(world, x, y, z);
            collision.setYaw(to.getYaw());
            collision.setPitch(0.0F);
            return collision;
        }
        return null;
    }

    private String paneBlockKey(Block block) {
        if (block == null || block.getWorld() == null) {
            return "";
        }
        return block.getWorld().getName()
                + ":"
                + block.getX()
                + ":"
                + block.getY()
                + ":"
                + block.getZ();
    }

    private boolean isGlassPane(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.contains("GLASS") && name.contains("PANE");
    }

    private boolean isThrownKnifeBlockingBlock(Material material) {
        return material != null && material != Material.AIR && !isGlassPane(material);
    }

    private void playGlassPaneShatterEffect(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        Location center = block.getLocation().add(0.5D, 0.5D, 0.5D);
        block.getWorld().playEffect(center, Effect.STEP_SOUND, block.getType());
        if (GLASS_SHATTER_SOUND != null) {
            try {
                block.getWorld().playSound(center, GLASS_SHATTER_SOUND, 1.0F, 1.0F);
            } catch (IllegalArgumentException ignored) {
                // Sound enum mismatch on legacy/newer API variants.
            }
        }
        showTemporaryGlassPaneBreakAnimation(block);
    }

    private void showTemporaryGlassPaneBreakAnimation(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        int animationId = paneBreakAnimationId(block);
        Location blockLocation = block.getLocation();
        sendBlockBreakAnimation(blockLocation, animationId, GLASS_PANE_BREAK_ANIMATION_STAGE);

        String blockKey = paneBlockKey(block);
        BukkitTask existingResetTask = paneBreakResetTasks.remove(blockKey);
        if (existingResetTask != null) {
            existingResetTask.cancel();
        }
        BukkitTask resetTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> {
                    paneBreakResetTasks.remove(blockKey);
                    sendBlockBreakAnimation(blockLocation, animationId, -1);
                },
                GLASS_PANE_BREAK_RESET_SECONDS * 20L
        );
        paneBreakResetTasks.put(blockKey, resetTask);
    }

    private int paneBreakAnimationId(Block block) {
        if (block == null || block.getWorld() == null) {
            return 0;
        }
        int hash = 17;
        hash = (31 * hash) + block.getWorld().getUID().hashCode();
        hash = (31 * hash) + block.getX();
        hash = (31 * hash) + block.getY();
        hash = (31 * hash) + block.getZ();
        return hash;
    }

    private void sendBlockBreakAnimation(Location blockLocation, int animationId, int stage) {
        if (blockLocation == null || blockLocation.getWorld() == null) {
            return;
        }
        int safeStage = Math.max(-1, Math.min(9, stage));
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            if (viewer.getWorld() == null || !viewer.getWorld().equals(blockLocation.getWorld())) {
                continue;
            }
            sendBlockBreakAnimationPacket(viewer, blockLocation, animationId, safeStage);
        }
    }

    private void sendBlockBreakAnimationPacket(Player viewer, Location blockLocation, int animationId, int stage) {
        if (viewer == null || blockLocation == null) {
            return;
        }
        try {
            String version = viewer.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object handle = craftPlayerClass.getMethod("getHandle").invoke(viewer);
            Object connection = handle.getClass().getField("playerConnection").get(handle);
            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Class<?> blockBreakPacketClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutBlockBreakAnimation");
            Object packet = createBlockBreakPacket(blockBreakPacketClass, version, animationId, blockLocation, stage);
            if (packet == null) {
                return;
            }
            connection.getClass().getMethod("sendPacket", packetClass).invoke(connection, packet);
        } catch (Throwable ignored) {
        }
    }

    private Object createBlockBreakPacket(Class<?> blockBreakPacketClass,
                                          String version,
                                          int animationId,
                                          Location blockLocation,
                                          int stage) throws Exception {
        int x = blockLocation.getBlockX();
        int y = blockLocation.getBlockY();
        int z = blockLocation.getBlockZ();
        try {
            Class<?> blockPositionClass = Class.forName("net.minecraft.server." + version + ".BlockPosition");
            Object blockPosition = blockPositionClass.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            return blockBreakPacketClass.getConstructor(int.class, blockPositionClass, int.class)
                    .newInstance(animationId, blockPosition, stage);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            return blockBreakPacketClass.getConstructor(int.class, int.class, int.class, int.class, int.class)
                    .newInstance(animationId, x, y, z, stage);
        }
    }

    private UUID findThrownKnifeShooterByDisplay(Entity entity) {
        if (entity == null || activeThrownKnives.isEmpty()) {
            return null;
        }
        UUID entityUuid = entity.getUniqueId();
        for (Map.Entry<UUID, ThrownKnife> entry : activeThrownKnives.entrySet()) {
            ThrownKnife knife = entry.getValue();
            if (knife == null || knife.display == null) {
                continue;
            }
            if (entityUuid.equals(knife.display.getUniqueId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void despawnThrownKnife(UUID shooterUuid) {
        if (shooterUuid == null) {
            return;
        }
        ThrownKnife knife = activeThrownKnives.remove(shooterUuid);
        if (knife == null) {
            return;
        }
        knife.cancelTask();
        if (knife.display != null && knife.display.isValid()) {
            knife.display.remove();
        }
    }

    private double distanceSquaredPointToSegment(Vector point, Vector start, Vector end) {
        if (point == null || start == null || end == null) {
            return Double.MAX_VALUE;
        }
        Vector segment = end.clone().subtract(start);
        double lengthSquared = segment.lengthSquared();
        if (lengthSquared <= 0.000001D) {
            return point.distanceSquared(start);
        }
        double projection = point.clone().subtract(start).dot(segment) / lengthSquared;
        if (projection < 0.0D) {
            projection = 0.0D;
        } else if (projection > 1.0D) {
            projection = 1.0D;
        }
        Vector closest = start.clone().add(segment.multiply(projection));
        return point.distanceSquared(closest);
    }

    private double distanceSquaredSegmentToSegment(Vector firstStart, Vector firstEnd, Vector secondStart, Vector secondEnd) {
        if (firstStart == null || firstEnd == null || secondStart == null || secondEnd == null) {
            return Double.MAX_VALUE;
        }
        Vector firstDirection = firstEnd.clone().subtract(firstStart);
        Vector secondDirection = secondEnd.clone().subtract(secondStart);
        Vector offset = firstStart.clone().subtract(secondStart);
        double firstLengthSquared = firstDirection.dot(firstDirection);
        double secondLengthSquared = secondDirection.dot(secondDirection);
        double secondProjection = secondDirection.dot(offset);
        double firstScale;
        double secondScale;

        if (firstLengthSquared <= 0.000001D && secondLengthSquared <= 0.000001D) {
            return firstStart.distanceSquared(secondStart);
        }
        if (firstLengthSquared <= 0.000001D) {
            firstScale = 0.0D;
            secondScale = clamp(secondProjection / secondLengthSquared, 0.0D, 1.0D);
        } else {
            double firstProjection = firstDirection.dot(offset);
            if (secondLengthSquared <= 0.000001D) {
                secondScale = 0.0D;
                firstScale = clamp(-firstProjection / firstLengthSquared, 0.0D, 1.0D);
            } else {
                double directionDot = firstDirection.dot(secondDirection);
                double denominator = firstLengthSquared * secondLengthSquared - directionDot * directionDot;
                if (denominator != 0.0D) {
                    firstScale = clamp(
                            (directionDot * secondProjection - firstProjection * secondLengthSquared) / denominator,
                            0.0D,
                            1.0D
                    );
                } else {
                    firstScale = 0.0D;
                }
                double secondNumerator = directionDot * firstScale + secondProjection;
                if (secondNumerator < 0.0D) {
                    secondScale = 0.0D;
                    firstScale = clamp(-firstProjection / firstLengthSquared, 0.0D, 1.0D);
                } else if (secondNumerator > secondLengthSquared) {
                    secondScale = 1.0D;
                    firstScale = clamp((directionDot - firstProjection) / firstLengthSquared, 0.0D, 1.0D);
                } else {
                    secondScale = secondNumerator / secondLengthSquared;
                }
            }
        }

        Vector firstClosest = firstStart.clone().add(firstDirection.multiply(firstScale));
        Vector secondClosest = secondStart.clone().add(secondDirection.multiply(secondScale));
        return firstClosest.distanceSquared(secondClosest);
    }

    private Location closestPointOnSegment(Vector point, Vector start, Vector end, World world) {
        if (point == null || start == null || end == null || world == null) {
            return null;
        }
        Vector segment = end.clone().subtract(start);
        double lengthSquared = segment.lengthSquared();
        if (lengthSquared <= 0.000001D) {
            return start.toLocation(world);
        }
        double projection = point.clone().subtract(start).dot(segment) / lengthSquared;
        Vector closest = start.clone().add(segment.multiply(clamp(projection, 0.0D, 1.0D)));
        return closest.toLocation(world);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private long secondsToMillis(double seconds) {
        return Math.max(1L, Math.round(seconds * 1000.0D));
    }

    private long secondsToTicks(double seconds) {
        return Math.max(1L, Math.round(seconds * 20.0D));
    }

    private void trySetArmorStandMarker(ArmorStand armorStand) {
        if (armorStand == null) {
            return;
        }
        try {
            Method setMarker = armorStand.getClass().getMethod("setMarker", boolean.class);
            setMarker.invoke(armorStand, true);
        } catch (Throwable ignored) {
            // Legacy API does not support marker armor stands.
        }
    }

    private static Sound resolveCompatibleSound(String... soundNames) {
        if (soundNames == null) {
            return null;
        }
        for (String soundName : soundNames) {
            if (soundName == null || soundName.trim().isEmpty()) {
                continue;
            }
            try {
                return Sound.valueOf(soundName.trim());
            } catch (IllegalArgumentException ignored) {
                // Try next sound key candidate.
            }
        }
        return null;
    }

    private void resyncDamagedItemSlots(Player player, ItemStack damagedItem) {
        if (player == null || damagedItem == null || damagedItem.getType() == Material.AIR) {
            return;
        }
        PlayerInventory inventory = player.getInventory();

        ItemStack[] contents = inventory.getContents();
        if (contents != null) {
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack slotItem = contents[slot];
                if (!isSameItemForResync(slotItem, damagedItem)) {
                    continue;
                }
                inventory.setItem(slot, copyItemWithDurability(slotItem, damagedItem));
                return;
            }
        }

        ItemStack helmet = inventory.getHelmet();
        if (isSameItemForResync(helmet, damagedItem)) {
            inventory.setHelmet(copyItemWithDurability(helmet, damagedItem));
            return;
        }
        ItemStack chestplate = inventory.getChestplate();
        if (isSameItemForResync(chestplate, damagedItem)) {
            inventory.setChestplate(copyItemWithDurability(chestplate, damagedItem));
            return;
        }
        ItemStack leggings = inventory.getLeggings();
        if (isSameItemForResync(leggings, damagedItem)) {
            inventory.setLeggings(copyItemWithDurability(leggings, damagedItem));
            return;
        }
        ItemStack boots = inventory.getBoots();
        if (isSameItemForResync(boots, damagedItem)) {
            inventory.setBoots(copyItemWithDurability(boots, damagedItem));
            return;
        }

        int heldSlot = inventory.getHeldItemSlot();
        ItemStack heldItem = inventory.getItem(heldSlot);
        if (isSameItemForResync(heldItem, damagedItem)) {
            inventory.setItem(heldSlot, copyItemWithDurability(heldItem, damagedItem));
        }
    }

    private ItemStack copyItemWithDurability(ItemStack slotItem, ItemStack durabilitySource) {
        if (slotItem == null || durabilitySource == null) {
            return slotItem;
        }
        ItemStack copy = slotItem.clone();
        copy.setDurability(durabilitySource.getDurability());
        return copy;
    }

    private boolean isSameItemForResync(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return false;
        }
        if (a == b) {
            return true;
        }
        if (a.getType() != b.getType()) {
            return false;
        }
        ItemMeta aMeta = a.getItemMeta();
        ItemMeta bMeta = b.getItemMeta();
        if (aMeta == null && bMeta == null) {
            return true;
        }
        if (aMeta == null || bMeta == null) {
            return false;
        }
        return aMeta.equals(bMeta);
    }

    private boolean isMurdererKnife(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                String displayName = ChatColor.stripColor(meta.getDisplayName());
                if (KNIFE_NAME.equalsIgnoreCase(displayName)) {
                    return true;
                }
            }
            if (meta.hasLore() && meta.getLore() != null) {
                for (String loreLine : meta.getLore()) {
                    if (loreLine == null) {
                        continue;
                    }
                    if (KNIFE_LORE.equalsIgnoreCase(ChatColor.stripColor(loreLine))) {
                        return true;
                    }
                }
            }
        }
        return item.getType() == Material.IRON_SWORD;
    }

    private static class ThrownKnife {
        private final ArmorStand display;
        private final Vector velocity;
        private final Location launchLocation;
        private final long expiresAtMillis;
        private final Set<String> shatteredPaneKeys;
        private BukkitTask task;

        private ThrownKnife(ArmorStand display,
                            Vector velocity,
                            Location launchLocation,
                            long expiresAtMillis) {
            this.display = display;
            this.velocity = velocity == null ? new Vector(0.0D, 0.0D, 0.0D) : velocity.clone();
            this.launchLocation = launchLocation == null ? null : launchLocation.clone();
            this.expiresAtMillis = expiresAtMillis;
            this.shatteredPaneKeys = new HashSet<>();
        }

        private void setTask(BukkitTask task) {
            this.task = task;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    private static class ArrowKnifeCollision {
        private final Arrow arrow;
        private final Location location;

        private ArrowKnifeCollision(Arrow arrow, Location location) {
            this.arrow = arrow;
            this.location = location == null ? null : location.clone();
        }
    }
}
