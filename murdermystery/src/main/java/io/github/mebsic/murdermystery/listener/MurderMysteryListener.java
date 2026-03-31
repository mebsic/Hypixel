package io.github.mebsic.murdermystery.listener;

import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.service.QueueService;
import io.github.mebsic.murdermystery.game.MurderMysteryGamePlayer;
import io.github.mebsic.murdermystery.game.MurderMysteryRole;
import io.github.mebsic.murdermystery.manager.MurderMysteryGameManager;
import io.github.mebsic.murdermystery.MurderMysteryPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;

import java.util.Locale;

public class MurderMysteryListener implements Listener {
    private static final String KNIFE_META = "murdermystery-knife";
    private static final String KNIFE_NAME = "Knife";
    private static final String KNIFE_LORE = "Use your Knife to kill players.";
    private static final String PROJECTILE_LAUNCH_X_META = "murdermystery-launch-x";
    private static final String PROJECTILE_LAUNCH_Y_META = "murdermystery-launch-y";
    private static final String PROJECTILE_LAUNCH_Z_META = "murdermystery-launch-z";
    private static final int ARROW_HOTBAR_SLOT = 2;

    private final MurderMysteryPlugin plugin;
    private final MurderMysteryGameManager gameManager;
    private final QueueService queueService;

    public MurderMysteryListener(MurderMysteryPlugin plugin, MurderMysteryGameManager gameManager, QueueService queueService) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.queueService = queueService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (queueService != null) {
            queueService.handleJoin(event.getPlayer());
        } else {
            gameManager.handleJoin(event.getPlayer());
        }
        if (plugin.getTipService() != null) {
            plugin.getTipService().handlePlayerJoin(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (queueService != null) {
            queueService.handleQuit(event.getPlayer());
        } else {
            gameManager.handleQuit(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
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
        if (gameManager.getState() != GameState.IN_GAME) {
            return;
        }
        MurderMysteryGamePlayer gp = gameManager.getMurderMysteryPlayer(player);
        if (gp == null || gp.getRole() != MurderMysteryRole.MURDERER) {
            return;
        }
        ItemStack item = player.getItemInHand();
        if (!isMurdererKnife(item)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - gp.getLastKnifeThrow() < 2000L) {
            player.sendMessage(ChatColor.RED + "Knife cooldown.");
            return;
        }
        gp.setLastKnifeThrow(now);
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setMetadata(KNIFE_META, new FixedMetadataValue(plugin, player.getUniqueId().toString()));
        setProjectileLaunchMetadata(snowball, player);
        event.setCancelled(true);
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
            gameManager.untrackMapDropItem(event.getItem());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!gameManager.isInGame(player) || gameManager.getState() != GameState.IN_GAME) {
                    return;
                }
                if (!consumeSingleMaterial(player, Material.GOLD_INGOT)) {
                    return;
                }
                gameManager.addGold(player, 1);
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
            gameManager.convertDroppedBowCarrierToDetective(player);
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
            String distanceSuffix = projectileDistanceSuffix(arrow, shooter, victim);
            if (shooterData.getRole() == MurderMysteryRole.DETECTIVE || shooterData.getRole() == MurderMysteryRole.HERO) {
                cancelArrowHit(event, arrow);
                if (shooter.getUniqueId().equals(victim.getUniqueId())) {
                    gameManager.handleDeath(victim, shooter, "You successfully shot yourself!");
                    return;
                }
                if (victimData.getRole() == MurderMysteryRole.MURDERER) {
                    shooterData.addKill();
                }
                gameManager.handleDeath(victim, shooter, "A player killed you! " + distanceSuffix, MurderMysteryGameManager.KillType.BOW);
                return;
            }
            if (shooterData.getRole() == MurderMysteryRole.MURDERER) {
                cancelArrowHit(event, arrow);
                if (shooter.getUniqueId().equals(victim.getUniqueId())) {
                    gameManager.handleDeath(victim, null, "You successfully shot yourself!", MurderMysteryGameManager.KillType.BOW);
                    return;
                }
                if (victimData.getRole() != MurderMysteryRole.MURDERER) {
                    shooterData.addKill();
                    gameManager.handleDeath(victim, shooter, "The Murderer shot you! " + distanceSuffix, MurderMysteryGameManager.KillType.BOW);
                    return;
                }
                return;
            }
            cancelArrowHit(event, arrow);
            return;
        }
        if (damager instanceof Snowball) {
            Snowball snowball = (Snowball) damager;
            if (!snowball.hasMetadata(KNIFE_META)) {
                return;
            }
            event.setCancelled(true);
            Player shooterPlayer = null;
            if (snowball.getShooter() instanceof Player) {
                shooterPlayer = (Player) snowball.getShooter();
                MurderMysteryGamePlayer shooterData = gameManager.getMurderMysteryPlayer(shooterPlayer);
                if (shooterData != null) {
                    shooterData.addKill();
                }
            }
            double distanceMeters = projectileDistanceMeters(snowball, shooterPlayer, victim);
            String distanceSuffix = formatDistanceSuffix(distanceMeters);
            gameManager.sendMurdererKnifeKillMessage(shooterPlayer, victim, distanceMeters);
            gameManager.handleDeath(victim, shooterPlayer, "The Murderer threw their knife at you! " + distanceSuffix, MurderMysteryGameManager.KillType.THROWN_KNIFE);
        }
    }

    @EventHandler
    public void onAnyDamage(EntityDamageEvent event) {
        if (gameManager.isDroppedBowDisplay(event.getEntity())) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent byEntityEvent = (EntityDamageByEntityEvent) event;
                Entity damager = byEntityEvent.getDamager();
                if (damager instanceof Arrow || damager instanceof Snowball) {
                    removeProjectileEntity(damager);
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
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        if (!gameManager.isInGame(player)) {
            return;
        }
        if (gameManager.getState() != GameState.IN_GAME) {
            return;
        }
        Object projectile = event.getProjectile();
        if (projectile instanceof Arrow) {
            Arrow launchedArrow = (Arrow) projectile;
            setProjectileLaunchMetadata(launchedArrow, player);
            gameManager.trackRoundArrow(launchedArrow);
        }
        MurderMysteryGamePlayer gp = gameManager.getMurderMysteryPlayer(player);
        if (gp == null || !gp.isAlive()) {
            return;
        }
        if (gp.getRole() != MurderMysteryRole.DETECTIVE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - gp.getLastArrowShot() < 5000L) {
            event.setCancelled(true);
            return;
        }
        gp.setLastArrowShot(now);
        player.getInventory().remove(Material.ARROW);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (gameManager.isInGame(player) && gameManager.getState() == GameState.IN_GAME) {
                player.getInventory().setItem(ARROW_HOTBAR_SLOT, new ItemStack(Material.ARROW, 1));
            }
        }, 100L);
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

    private String projectileDistanceSuffix(Entity projectile, Player shooter, Player victim) {
        double meters = projectileDistanceMeters(projectile, shooter, victim);
        return formatDistanceSuffix(meters);
    }

    private String formatDistanceSuffix(double meters) {
        return ChatColor.DARK_GRAY + "(" + String.format(Locale.US, "%.2f", meters) + "m)";
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

    private boolean consumeSingleMaterial(Player player, Material material) {
        if (player == null || material == null) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            if (stack.getAmount() <= 1) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
                player.getInventory().setItem(slot, stack);
            }
            return true;
        }
        return false;
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
}
