package io.github.mebsic.core.listener;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.game.model.GameState;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerAchievementAwardedEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

import java.util.Locale;

public class GameplayRulesListener implements Listener {
    private final CorePlugin plugin;
    private final ServerType serverType;
    private final boolean hungerLossEnabled;
    private final boolean healthLossEnabled;
    private final boolean blockBreakEnabled;
    private final boolean containerInteractionBlocked;
    private final boolean mechanismInteractionBlocked;
    private final boolean farmlandTrampleBlocked;
    private final boolean paintingBreakBlocked;
    private final boolean beaconInteractionBlocked;
    private final boolean weatherCycleEnabled;
    private final boolean vanillaAchievementsEnabled;

    public GameplayRulesListener(CorePlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();
        this.serverType = plugin.getServerType() == null ? ServerType.UNKNOWN : plugin.getServerType();
        String serverName = normalize(config.getString("server.id", ""));

        this.hungerLossEnabled = resolveToggle(config, "gameplay.hungerLoss", this.serverType, serverName, false);
        this.healthLossEnabled = resolveToggle(config, "gameplay.healthLoss", this.serverType, serverName, false);
        this.blockBreakEnabled = resolveToggle(config, "gameplay.blockBreak", this.serverType, serverName, true);
        this.containerInteractionBlocked = this.serverType == ServerType.MURDER_MYSTERY_HUB || this.serverType == ServerType.MURDER_MYSTERY;
        this.mechanismInteractionBlocked = this.serverType == ServerType.MURDER_MYSTERY_HUB;
        this.farmlandTrampleBlocked = this.serverType == ServerType.MURDER_MYSTERY_HUB || this.serverType == ServerType.MURDER_MYSTERY;
        this.paintingBreakBlocked = this.serverType != null && this.serverType.isHub();
        this.beaconInteractionBlocked = this.serverType != null && this.serverType.isHub();
        boolean weatherEnabled = resolveToggle(config, "gameplay.weatherCycle", this.serverType, serverName, true);
        if (this.serverType == ServerType.MURDER_MYSTERY_HUB || this.serverType == ServerType.MURDER_MYSTERY) {
            weatherEnabled = false;
        }
        this.weatherCycleEnabled = weatherEnabled;
        boolean resolvedVanillaAchievements =
                resolveToggle(config, "gameplay.vanillaAchievements", this.serverType, serverName, false);
        if (this.serverType != null && this.serverType.isGame()) {
            resolvedVanillaAchievements = false;
        }
        this.vanillaAchievementsEnabled = resolvedVanillaAchievements;

        plugin.getLogger().info(
                "Gameplay rules resolved for " + this.serverType.name()
                        + " (" + (serverName.isEmpty() ? "unknown-server" : serverName) + "): "
                        + "hungerLoss=" + hungerLossEnabled
                        + ", healthLoss=" + healthLossEnabled
                        + ", blockBreak=" + blockBreakEnabled
                        + ", containerInteractionBlocked=" + containerInteractionBlocked
                        + ", mechanismInteractionBlocked=" + mechanismInteractionBlocked
                        + ", farmlandTrampleBlocked=" + farmlandTrampleBlocked
                        + ", paintingBreakBlocked=" + paintingBreakBlocked
                        + ", beaconInteractionBlocked=" + beaconInteractionBlocked
                        + ", weatherCycle=" + weatherCycleEnabled
                        + ", vanillaAchievements=" + vanillaAchievementsEnabled
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyRules(event.getPlayer());
        applyVanillaAchievementRules(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAchievementAwarded(PlayerAchievementAwardedEvent event) {
        if (vanillaAchievementsEnabled || event == null) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (hungerLossEnabled || !(event.getEntity() instanceof Player)) {
            return;
        }
        event.setCancelled(true);
        Player player = (Player) event.getEntity();
        if (player.getFoodLevel() < 20) {
            player.setFoodLevel(20);
        }
        if (player.getSaturation() < 20.0f) {
            player.setSaturation(20.0f);
        }
        player.setExhaustion(0.0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (healthLossEnabled || !(event.getEntity() instanceof Player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (blockBreakEnabled || event == null || event.getPlayer() == null) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPaintingBreak(HangingBreakByEntityEvent event) {
        if (!paintingBreakBlocked || event == null || event.getRemover() == null) {
            return;
        }
        if (!(event.getRemover() instanceof Player)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event == null) {
            return;
        }
        Action action = event.getAction();
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Material type = clicked.getType();
        if (action == Action.RIGHT_CLICK_BLOCK) {
            if (shouldBlockMechanismUntilInGame() && isBlockedMechanismInteractionMaterial(type)) {
                event.setCancelled(true);
                return;
            }
            if (containerInteractionBlocked && isBlockedContainerInteractionMaterial(type)) {
                event.setCancelled(true);
                return;
            }
            if (beaconInteractionBlocked && isBeacon(type)) {
                event.setCancelled(true);
                return;
            }
            if (mechanismInteractionBlocked && isBlockedMechanismInteractionMaterial(type)) {
                event.setCancelled(true);
                return;
            }
        }
        if (farmlandTrampleBlocked && action == Action.PHYSICAL && isFarmland(type)) {
            event.setCancelled(true);
        }
    }

    private boolean shouldBlockMechanismUntilInGame() {
        if (plugin == null || serverType == null || !serverType.isGame()) {
            return false;
        }
        return plugin.getCurrentGameState() != GameState.IN_GAME;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (weatherCycleEnabled || event == null) {
            return;
        }
        if (event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        if (weatherCycleEnabled || event == null) {
            return;
        }
        if (event.toThunderState()) {
            event.setCancelled(true);
        }
    }

    private void applyRules(Player player) {
        if (player == null) {
            return;
        }
        if (!hungerLossEnabled) {
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            player.setExhaustion(0.0f);
        }
        if (!healthLossEnabled) {
            double maxHealth = player.getMaxHealth();
            if (player.getHealth() < maxHealth) {
                player.setHealth(maxHealth);
            }
        }
        if (!weatherCycleEnabled && player.getWorld() != null) {
            player.getWorld().setStorm(false);
            player.getWorld().setThundering(false);
            player.getWorld().setGameRuleValue("doWeatherCycle", "false");
        }
    }

    private void applyVanillaAchievementRules(Player player) {
        if (player == null || player.getWorld() == null) {
            return;
        }
        String value = Boolean.toString(vanillaAchievementsEnabled);
        player.getWorld().setGameRuleValue("announceAchievements", value);
        player.getWorld().setGameRuleValue("announceAdvancements", value);
    }

    private boolean isBlockedContainerInteractionMaterial(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        if ("HOPPER".equals(name)
                || "CHEST".equals(name)
                || "TRAPPED_CHEST".equals(name)
                || "ENDER_CHEST".equals(name)
                || "WORKBENCH".equals(name)
                || "CRAFTING_TABLE".equals(name)
                || "ANVIL".equals(name)
                || name.endsWith("_ANVIL")) {
            return true;
        }
        return false;
    }

    private boolean isBlockedMechanismInteractionMaterial(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        if ("FENCE_GATE".equals(name) || name.endsWith("_FENCE_GATE")) {
            return true;
        }
        if ("LEVER".equals(name)) {
            return true;
        }
        if (name.endsWith("_BUTTON")) {
            return true;
        }
        if ("TRAP_DOOR".equals(name) || name.endsWith("_TRAPDOOR") || name.endsWith("TRAPDOOR")) {
            return true;
        }
        return (name.endsWith("_DOOR") || name.endsWith("_DOOR_BLOCK")) && !name.contains("TRAP");
    }

    private boolean isFarmland(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return "SOIL".equals(name) || "FARMLAND".equals(name);
    }

    private boolean isBeacon(Material material) {
        if (material == null) {
            return false;
        }
        return "BEACON".equals(material.name());
    }

    private boolean resolveToggle(FileConfiguration config,
                                  String path,
                                  ServerType serverType,
                                  String serverName,
                                  boolean fallbackDefault) {
        boolean enabled = config.getBoolean(path + ".defaultEnabled", fallbackDefault);

        if (serverType != null) {
            enabled = override(enabled, readBoolean(config, path + ".byServerType." + serverType.name()));
            if (serverType.isHub()) {
                enabled = override(enabled, readBoolean(config, path + ".byServerType.HUB"));
            } else if (serverType.isGame()) {
                enabled = override(enabled, readBoolean(config, path + ".byServerType.GAME"));
            }
        }

        if (!serverName.isEmpty()) {
            enabled = override(enabled, readBoolean(config, path + ".byServerId." + serverName));
            enabled = override(enabled, readBoolean(config, path + ".byServerId." + serverName.toLowerCase(Locale.ROOT)));
        }

        return enabled;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private boolean override(boolean current, Boolean override) {
        return override == null ? current : override;
    }

    private Boolean readBoolean(FileConfiguration config, String path) {
        if (config == null || path == null || path.trim().isEmpty()) {
            return null;
        }
        if (!config.isSet(path)) {
            return null;
        }
        Object raw = config.get(path);
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw == null) {
            return null;
        }
        String normalized = raw.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
