package io.github.mebsic.game.service;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.BossBarMessage;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.store.BossBarMessageStore;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.game.model.GameState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public class BossBarService {
    private static final long TICK_INTERVAL = 1L; // Minimum Bukkit interval (1 tick = 50ms).
    private static final long DOMAIN_COLOR_CYCLE_INTERVAL_TICKS = 10L; // 0.5 seconds
    private static final long HUB_MESSAGE_ROTATION_INTERVAL_TICKS = 100L; // 5 seconds
    private static final long STORE_REFRESH_INTERVAL_TICKS = 600L; // 30 seconds
    private static final long LEGACY_METADATA_KEEPALIVE_INTERVAL_TICKS = 5L; // 0.25 seconds
    private static final double LEGACY_ANCHOR_FORWARD_DISTANCE = 24.0D;
    private static final double LEGACY_ANCHOR_VERTICAL_OFFSET = 8.0D;
    private static final double LEGACY_WORLD_MIN_Y = 1.0D;
    private static final double LEGACY_WORLD_MAX_Y_MARGIN = 2.0D;
    private static final double LEGACY_IDLE_ANCHOR_BOB_AMPLITUDE = 0.25D; // Keep legacy boss entities active while idle.
    private static final double LEGACY_IDLE_ANCHOR_ORBIT_RADIUS = 0.60D; // Add lateral motion when head/camera is static.
    private static final double LEGACY_IDLE_ANCHOR_BOB_PERIOD_TICKS = 40.0D; // 2 seconds
    private static final long LEGACY_ENTITY_RESYNC_INTERVAL_TICKS = 10L; // 0.5 seconds
    private static final float MAX_BOSS_HEALTH = 300.0f;
    private static final float MIN_MESSAGE_VALUE = 0.01f;
    private static final List<ChatColor> DOMAIN_CYCLE_COLORS = Arrays.asList(
            ChatColor.AQUA,
            ChatColor.GREEN,
            ChatColor.WHITE
    );

    private final Plugin plugin;
    private final ServerType serverType;
    private final Supplier<GameState> gameStateSupplier;
    private final Set<UUID> trackedPlayers;
    private final Map<UUID, LegacyBarView> activeBars;
    private final LegacyBossBarAdapter legacyAdapter;
    private final BossBarMessageStore messageStore;

    private List<BossBarMessage> gameMessages;
    private List<BossBarMessage> hubMessages;
    private BossBarMessage currentGameMessage;
    private BossBarMessage currentHubMessage;
    private int domainColorIndex;
    private long domainColorCycleElapsedTicks;
    private long tickCounter;

    private BukkitTask tickTask;
    private BukkitTask hubMessageTask;
    private BukkitTask storeRefreshTask;

    public BossBarService(Plugin plugin, CorePlugin corePlugin) {
        this(plugin, corePlugin, null);
    }

    public BossBarService(Plugin plugin, CorePlugin corePlugin, Supplier<GameState> gameStateSupplier) {
        this.plugin = plugin;
        this.serverType = corePlugin == null ? ServerType.UNKNOWN : corePlugin.getServerType();
        this.gameStateSupplier = gameStateSupplier;
        this.trackedPlayers = ConcurrentHashMap.newKeySet();
        this.activeBars = new ConcurrentHashMap<>();
        this.legacyAdapter = new LegacyBossBarAdapter(plugin);
        this.messageStore = corePlugin == null || corePlugin.getMongoManager() == null
                ? null
                : new BossBarMessageStore(corePlugin.getMongoManager());
        this.gameMessages = defaultGameMessages();
        this.hubMessages = defaultHubMessages();
        this.currentGameMessage = null;
        this.currentHubMessage = null;
        this.domainColorIndex = 0;
        this.domainColorCycleElapsedTicks = 0L;
        this.tickCounter = 0L;
    }

    public void start() {
        if (tickTask != null) {
            return;
        }
        rotateGameMessage();
        rotateHubMessage();
        requestMessageRefresh();
        for (Player player : Bukkit.getOnlinePlayers()) {
            show(player);
        }
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, TICK_INTERVAL);
        if (isHubServer()) {
            hubMessageTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::rotateHubMessage,
                    HUB_MESSAGE_ROTATION_INTERVAL_TICKS,
                    HUB_MESSAGE_ROTATION_INTERVAL_TICKS
            );
        } else if (isGameServer()) {
            hubMessageTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::rotateGameMessage,
                    HUB_MESSAGE_ROTATION_INTERVAL_TICKS,
                    HUB_MESSAGE_ROTATION_INTERVAL_TICKS
            );
        }
        if (messageStore != null) {
            storeRefreshTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::requestMessageRefresh,
                    20L,
                    STORE_REFRESH_INTERVAL_TICKS
            );
        }
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (hubMessageTask != null) {
            hubMessageTask.cancel();
            hubMessageTask = null;
        }
        if (storeRefreshTask != null) {
            storeRefreshTask.cancel();
            storeRefreshTask = null;
        }
        for (UUID uuid : new ArrayList<>(activeBars.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            destroyBar(uuid, player);
        }
        trackedPlayers.clear();
        activeBars.clear();
    }

    public void show(Player player) {
        if (player == null) {
            return;
        }
        trackedPlayers.add(player.getUniqueId());
        if (tickTask != null) {
            updatePlayerBar(player, resolveGameState());
        }
    }

    public void remove(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        trackedPlayers.remove(uuid);
        destroyBar(uuid, player);
    }

    private void tick() {
        if (!legacyAdapter.isAvailable() || trackedPlayers.isEmpty()) {
            return;
        }
        tickCounter += TICK_INTERVAL;
        GameState state = resolveGameState();
        if (isGameServer() && state != GameState.IN_GAME && state != GameState.ENDING) {
            domainColorCycleElapsedTicks += TICK_INTERVAL;
            if (domainColorCycleElapsedTicks >= DOMAIN_COLOR_CYCLE_INTERVAL_TICKS) {
                domainColorIndex = (domainColorIndex + 1) % DOMAIN_CYCLE_COLORS.size();
                domainColorCycleElapsedTicks = 0L;
            }
        } else {
            domainColorCycleElapsedTicks = 0L;
        }
        for (UUID uuid : new ArrayList<>(trackedPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                trackedPlayers.remove(uuid);
                destroyBar(uuid, null);
                continue;
            }
            updatePlayerBar(player, state);
        }
    }

    private void updatePlayerBar(Player player, GameState state) {
        DisplayState display = resolveDisplayState(state);
        if (display == null || display.text == null || display.text.trim().isEmpty()) {
            destroyBar(player.getUniqueId(), player);
            return;
        }
        UUID uuid = player.getUniqueId();
        LegacyBarView current = activeBars.get(uuid);
        Location anchor = buildAnchor(player);
        if (current == null) {
            LegacyBarView created = legacyAdapter.spawn(player, display.text, display.value, anchor);
            if (created != null) {
                activeBars.put(uuid, created);
            }
            return;
        }
        current.resyncElapsedTicks += TICK_INTERVAL;
        if (current.resyncElapsedTicks >= LEGACY_ENTITY_RESYNC_INTERVAL_TICKS) {
            // Some legacy clients stop rendering static fake boss entities. Recreate periodically.
            LegacyBarView recreated = legacyAdapter.spawn(player, display.text, display.value, anchor);
            if (recreated != null) {
                legacyAdapter.destroy(player, current);
                activeBars.put(uuid, recreated);
                return;
            }
            current.resyncElapsedTicks = 0L;
        }
        legacyAdapter.update(player, current, display.text, display.value, anchor);
    }

    private DisplayState resolveDisplayState(GameState state) {
        if (isHubServer()) {
            BossBarMessage hubMessage = currentHubMessage;
            if (hubMessage == null) {
                hubMessage = firstMessage(hubMessages);
            }
            if (hubMessage == null) {
                return null;
            }
            String text = formatTemplate(hubMessage.getText());
            return new DisplayState(text, sanitizeValue(hubMessage.getValue()));
        }
        if (state == GameState.IN_GAME || state == GameState.ENDING) {
            return null;
        }
        BossBarMessage gameMessage = currentGameMessage;
        if (gameMessage == null) {
            gameMessage = firstMessage(gameMessages);
        }
        if (gameMessage == null) {
            return null;
        }
        String text = buildStyledGameBannerText(gameMessage.getText());
        return new DisplayState(text, sanitizeValue(gameMessage.getValue()));
    }

    private String buildStyledGameBannerText(String template) {
        String source = template == null ? "" : template;
        if (source.trim().isEmpty()) {
            source = "Playing {gameType} on {domain}";
        }
        source = source
                .replace("%gameType%", "{gameType}")
                .replace("%domain%", "{domain}");
        final String gameMarker = "__GAME_TYPE__";
        final String domainMarker = "__DOMAIN__";
        source = source
                .replace("{gameType}", gameMarker)
                .replace("{domain}", domainMarker);

        // Normalize user-configured colors away so server-defined style remains consistent.
        String normalized = ChatColor.translateAlternateColorCodes('&', source);
        String stripped = ChatColor.stripColor(normalized);
        if (stripped == null || stripped.trim().isEmpty()) {
            stripped = "Playing " + gameMarker + " on " + domainMarker;
        }
        if (!stripped.contains(gameMarker) || !stripped.contains(domainMarker)) {
            stripped = "Playing " + gameMarker + " on " + domainMarker;
        }

        String baseStyle = ChatColor.YELLOW.toString() + ChatColor.BOLD;
        ChatColor domainColor = DOMAIN_CYCLE_COLORS.get(domainColorIndex % DOMAIN_CYCLE_COLORS.size());
        String gamePart = ChatColor.WHITE.toString() + ChatColor.BOLD + resolveGameType() + baseStyle;
        String domainPart = domainColor.toString() + ChatColor.BOLD + resolveDomainLabel() + baseStyle;

        String styled = stripped
                .replace(gameMarker, gamePart)
                .replace(domainMarker, domainPart);
        return baseStyle + styled;
    }

    private void rotateHubMessage() {
        if (!isHubServer()) {
            return;
        }
        currentHubMessage = nextHubMessage(hubMessages, currentHubMessage);
    }

    private void rotateGameMessage() {
        if (!isGameServer()) {
            return;
        }
        currentGameMessage = nextHubMessage(gameMessages, currentGameMessage);
    }

    private BossBarMessage nextHubMessage(List<BossBarMessage> messages, BossBarMessage current) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        if (current == null) {
            return messages.get(0);
        }
        for (int i = 0; i < messages.size(); i++) {
            BossBarMessage message = messages.get(i);
            if (!sameMessage(message, current)) {
                continue;
            }
            int nextIndex = (i + 1) % messages.size();
            return messages.get(nextIndex);
        }
        return messages.get(0);
    }

    private boolean sameMessage(BossBarMessage first, BossBarMessage second) {
        if (first == null || second == null) {
            return false;
        }
        String firstId = first.getId();
        String secondId = second.getId();
        if (firstId != null && secondId != null && !firstId.isEmpty() && !secondId.isEmpty()) {
            return firstId.equals(secondId);
        }
        return first.getText().equals(second.getText());
    }

    private void requestMessageRefresh() {
        if (messageStore == null || plugin == null || !plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                messageStore.ensureDefaults();
                List<BossBarMessage> loadedGame = messageStore.loadByScope(BossBarMessageStore.SCOPE_GAME, serverType);
                List<BossBarMessage> loadedHub = messageStore.loadByScope(BossBarMessageStore.SCOPE_HUB, serverType);
                if (!plugin.isEnabled()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> applyLoadedMessages(loadedGame, loadedHub));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to refresh boss bar messages from MongoDB.", ex);
            }
        });
    }

    private void applyLoadedMessages(List<BossBarMessage> loadedGame, List<BossBarMessage> loadedHub) {
        if (loadedGame == null || loadedGame.isEmpty()) {
            this.gameMessages = defaultGameMessages();
        } else {
            this.gameMessages = Collections.unmodifiableList(new ArrayList<>(loadedGame));
        }
        if (loadedHub == null || loadedHub.isEmpty()) {
            this.hubMessages = defaultHubMessages();
        } else {
            this.hubMessages = Collections.unmodifiableList(new ArrayList<>(loadedHub));
        }
        if (isHubServer() && (currentHubMessage == null || !containsMessage(hubMessages, currentHubMessage))) {
            currentHubMessage = firstMessage(hubMessages);
        }
        if (isGameServer() && (currentGameMessage == null || !containsMessage(gameMessages, currentGameMessage))) {
            currentGameMessage = firstMessage(gameMessages);
        }
    }

    private boolean containsMessage(List<BossBarMessage> messages, BossBarMessage target) {
        if (messages == null || messages.isEmpty() || target == null) {
            return false;
        }
        for (BossBarMessage message : messages) {
            if (sameMessage(message, target)) {
                return true;
            }
        }
        return false;
    }

    private List<BossBarMessage> defaultGameMessages() {
        return Collections.singletonList(new BossBarMessage(
                "game_default_banner",
                "Playing {gameType} on {domain}",
                1.0f,
                BossBarMessageStore.SCOPE_GAME,
                "ANY"
        ));
    }

    private List<BossBarMessage> defaultHubMessages() {
        return Collections.emptyList();
    }

    private BossBarMessage firstMessage(List<BossBarMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.get(0);
    }

    private String formatTemplate(String template) {
        String gameType = resolveGameType();
        String domain = resolveDomainLabel();
        String formatted = template == null ? "" : template;
        formatted = formatted.replace("{gameType}", gameType)
                .replace("{domain}", domain)
                .replace("%gameType%", gameType)
                .replace("%domain%", domain)
                .replace('\n', ' ')
                .replace('\r', ' ');
        return ChatColor.translateAlternateColorCodes('&', formatted);
    }

    private String resolveGameType() {
        ServerType type = serverType == null ? ServerType.UNKNOWN : serverType;
        return type.getGameTypeDisplayName();
    }

    private String resolveDomainLabel() {
        return "MC." + NetworkConstants.DOMAIN.toUpperCase(Locale.ROOT);
    }

    private float sanitizeValue(float raw) {
        if (Float.isNaN(raw) || Float.isInfinite(raw)) {
            return 1.0f;
        }
        return Math.max(MIN_MESSAGE_VALUE, Math.min(1.0f, raw));
    }

    private Location buildAnchor(Player player) {
        Location eye = player.getEyeLocation().clone();
        Vector direction = eye.getDirection();
        if (direction == null || direction.lengthSquared() <= 0.0D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }
        direction.normalize();
        double upwardFactor = Math.max(0.0D, Math.min(1.0D, direction.getY()));
        eye.add(direction.clone().multiply(LEGACY_ANCHOR_FORWARD_DISTANCE));
        eye.add(0.0D, LEGACY_ANCHOR_VERTICAL_OFFSET * (1.0D - upwardFactor), 0.0D);
        double playerPhase = Math.abs(player.getUniqueId().hashCode() % 360);
        double radians = (tickCounter + playerPhase) * (2.0D * Math.PI / LEGACY_IDLE_ANCHOR_BOB_PERIOD_TICKS);
        eye.add(Math.cos(radians) * LEGACY_IDLE_ANCHOR_ORBIT_RADIUS, 0.0D, Math.sin(radians) * LEGACY_IDLE_ANCHOR_ORBIT_RADIUS);
        eye.add(0.0D, Math.sin(radians) * LEGACY_IDLE_ANCHOR_BOB_AMPLITUDE, 0.0D);
        if (player.getWorld() != null) {
            double maxY = Math.max(LEGACY_WORLD_MIN_Y + 1.0D, player.getWorld().getMaxHeight() - LEGACY_WORLD_MAX_Y_MARGIN);
            eye.setY(Math.max(LEGACY_WORLD_MIN_Y, Math.min(maxY, eye.getY())));
        }
        return eye;
    }

    private float toBossHealth(float value) {
        return Math.max(1.0f, Math.min(MAX_BOSS_HEALTH, value * MAX_BOSS_HEALTH));
    }

    private GameState resolveGameState() {
        if (gameStateSupplier == null) {
            return GameState.WAITING;
        }
        try {
            GameState state = gameStateSupplier.get();
            return state == null ? GameState.WAITING : state;
        } catch (Exception ignored) {
            return GameState.WAITING;
        }
    }

    private boolean isHubServer() {
        return serverType != null && serverType.isHub();
    }

    private boolean isGameServer() {
        return serverType != null && serverType.isGame();
    }

    private void destroyBar(UUID uuid, Player player) {
        LegacyBarView current = activeBars.remove(uuid);
        if (current == null) {
            return;
        }
        Player target = player;
        if (target == null) {
            target = Bukkit.getPlayer(uuid);
        }
        if (target != null && target.isOnline()) {
            legacyAdapter.destroy(target, current);
        }
    }

    private static final class DisplayState {
        private final String text;
        private final float value;

        private DisplayState(String text, float value) {
            this.text = text;
            this.value = value;
        }
    }

    private static final class LegacyBarView {
        private final Object entity;
        private final int entityId;
        private String text;
        private float value;
        private long metadataKeepaliveElapsedTicks;
        private long resyncElapsedTicks;

        private LegacyBarView(Object entity, int entityId, String text, float value) {
            this.entity = entity;
            this.entityId = entityId;
            this.text = text;
            this.value = value;
            this.metadataKeepaliveElapsedTicks = 0L;
            this.resyncElapsedTicks = 0L;
        }
    }

    private final class LegacyBossBarAdapter {
        private final Constructor<?> witherConstructor;
        private final Constructor<?> spawnPacketConstructor;
        private final Constructor<?> destroyPacketConstructor;
        private final Constructor<?> teleportPacketConstructor;
        private final Constructor<?> metadataPacketConstructor;

        private final Method getHandlePlayerMethod;
        private final Method getHandleWorldMethod;
        private final Method sendPacketMethod;
        private final Method setLocationMethod;
        private final Method setInvisibleMethod;
        private final Method setCustomNameMethod;
        private final Method setCustomNameVisibleMethod;
        private final Method setHealthMethod;
        private final Method getIdMethod;
        private final Method getDataWatcherMethod;

        private final Field playerConnectionField;
        private final Class<?> craftPlayerClass;
        private final Class<?> craftWorldClass;

        private boolean available;

        private LegacyBossBarAdapter(Plugin plugin) {
            Constructor<?> witherCtor;
            Constructor<?> spawnCtor;
            Constructor<?> destroyCtor;
            Constructor<?> teleportCtor;
            Constructor<?> metadataCtor;
            Method getHandlePlayer;
            Method getHandleWorld;
            Method sendPacket;
            Method setLocation;
            Method setInvisible;
            Method setCustomName;
            Method setCustomNameVisible;
            Method setHealth;
            Method getId;
            Method getDataWatcher;
            Field connectionField;
            Class<?> localCraftPlayer;
            Class<?> localCraftWorld;
            boolean initialized;
            try {
                String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
                localCraftPlayer = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
                localCraftWorld = Class.forName("org.bukkit.craftbukkit." + version + ".CraftWorld");
                Class<?> worldClass = Class.forName("net.minecraft.server." + version + ".World");
                Class<?> entityClass = Class.forName("net.minecraft.server." + version + ".Entity");
                Class<?> entityLivingClass = Class.forName("net.minecraft.server." + version + ".EntityLiving");
                Class<?> entityWitherClass = Class.forName("net.minecraft.server." + version + ".EntityWither");
                Class<?> entityPlayerClass = Class.forName("net.minecraft.server." + version + ".EntityPlayer");
                Class<?> playerConnectionClass = Class.forName("net.minecraft.server." + version + ".PlayerConnection");
                Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
                Class<?> dataWatcherClass = Class.forName("net.minecraft.server." + version + ".DataWatcher");
                Class<?> packetSpawnClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutSpawnEntityLiving");
                Class<?> packetDestroyClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutEntityDestroy");
                Class<?> packetTeleportClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutEntityTeleport");
                Class<?> packetMetadataClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutEntityMetadata");

                witherCtor = entityWitherClass.getConstructor(worldClass);
                spawnCtor = packetSpawnClass.getConstructor(entityLivingClass);
                destroyCtor = packetDestroyClass.getConstructor(int[].class);
                teleportCtor = packetTeleportClass.getConstructor(entityClass);
                metadataCtor = packetMetadataClass.getConstructor(int.class, dataWatcherClass, boolean.class);

                getHandlePlayer = localCraftPlayer.getMethod("getHandle");
                getHandleWorld = localCraftWorld.getMethod("getHandle");
                connectionField = entityPlayerClass.getField("playerConnection");
                sendPacket = playerConnectionClass.getMethod("sendPacket", packetClass);
                setLocation = entityClass.getMethod("setLocation", double.class, double.class, double.class, float.class, float.class);
                setInvisible = entityClass.getMethod("setInvisible", boolean.class);
                setCustomName = entityClass.getMethod("setCustomName", String.class);
                setCustomNameVisible = entityClass.getMethod("setCustomNameVisible", boolean.class);
                setHealth = entityLivingClass.getMethod("setHealth", float.class);
                getId = entityClass.getMethod("getId");
                getDataWatcher = entityClass.getMethod("getDataWatcher");
                initialized = true;
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Legacy boss bar adapter disabled for this server version.", ex);
                witherCtor = null;
                spawnCtor = null;
                destroyCtor = null;
                teleportCtor = null;
                metadataCtor = null;
                getHandlePlayer = null;
                getHandleWorld = null;
                sendPacket = null;
                setLocation = null;
                setInvisible = null;
                setCustomName = null;
                setCustomNameVisible = null;
                setHealth = null;
                getId = null;
                getDataWatcher = null;
                connectionField = null;
                localCraftPlayer = null;
                localCraftWorld = null;
                initialized = false;
            }
            this.witherConstructor = witherCtor;
            this.spawnPacketConstructor = spawnCtor;
            this.destroyPacketConstructor = destroyCtor;
            this.teleportPacketConstructor = teleportCtor;
            this.metadataPacketConstructor = metadataCtor;
            this.getHandlePlayerMethod = getHandlePlayer;
            this.getHandleWorldMethod = getHandleWorld;
            this.sendPacketMethod = sendPacket;
            this.setLocationMethod = setLocation;
            this.setInvisibleMethod = setInvisible;
            this.setCustomNameMethod = setCustomName;
            this.setCustomNameVisibleMethod = setCustomNameVisible;
            this.setHealthMethod = setHealth;
            this.getIdMethod = getId;
            this.getDataWatcherMethod = getDataWatcher;
            this.playerConnectionField = connectionField;
            this.craftPlayerClass = localCraftPlayer;
            this.craftWorldClass = localCraftWorld;
            this.available = initialized;
        }

        private boolean isAvailable() {
            return available;
        }

        private LegacyBarView spawn(Player player, String text, float value, Location anchor) {
            if (!available || player == null || anchor == null || player.getWorld() == null) {
                return null;
            }
            try {
                Object worldHandle = getHandleWorldMethod.invoke(craftWorldClass.cast(player.getWorld()));
                Object entity = witherConstructor.newInstance(worldHandle);
                setLocationMethod.invoke(entity, anchor.getX(), anchor.getY(), anchor.getZ(), 0.0f, 0.0f);
                setInvisibleMethod.invoke(entity, true);
                setCustomNameMethod.invoke(entity, text);
                setCustomNameVisibleMethod.invoke(entity, true);
                setHealthMethod.invoke(entity, toBossHealth(value));
                Object packet = spawnPacketConstructor.newInstance(entity);
                sendPacket(player, packet);
                int entityId = ((Number) getIdMethod.invoke(entity)).intValue();
                return new LegacyBarView(entity, entityId, text, value);
            } catch (Exception ex) {
                disable(ex);
                return null;
            }
        }

        private void update(Player player, LegacyBarView bar, String text, float value, Location anchor) {
            if (!available || player == null || bar == null || anchor == null) {
                return;
            }
            try {
                setLocationMethod.invoke(bar.entity, anchor.getX(), anchor.getY(), anchor.getZ(), 0.0f, 0.0f);
                Object teleportPacket = teleportPacketConstructor.newInstance(bar.entity);
                sendPacket(player, teleportPacket);
                bar.metadataKeepaliveElapsedTicks += TICK_INTERVAL;
                boolean changed = !text.equals(bar.text) || Math.abs(bar.value - value) > 0.0001f;
                if (!changed && bar.metadataKeepaliveElapsedTicks < LEGACY_METADATA_KEEPALIVE_INTERVAL_TICKS) {
                    return;
                }
                setCustomNameMethod.invoke(bar.entity, text);
                setHealthMethod.invoke(bar.entity, toBossHealth(value));
                Object watcher = getDataWatcherMethod.invoke(bar.entity);
                Object metadataPacket = metadataPacketConstructor.newInstance(bar.entityId, watcher, true);
                sendPacket(player, metadataPacket);
                bar.text = text;
                bar.value = value;
                bar.metadataKeepaliveElapsedTicks = 0L;
            } catch (Exception ex) {
                disable(ex);
            }
        }

        private void destroy(Player player, LegacyBarView bar) {
            if (!available || player == null || bar == null) {
                return;
            }
            try {
                Object packet = destroyPacketConstructor.newInstance(new int[]{bar.entityId});
                sendPacket(player, packet);
            } catch (Exception ex) {
                disable(ex);
            }
        }

        private void sendPacket(Player player, Object packet) throws Exception {
            if (player == null || packet == null) {
                return;
            }
            Object handle = getHandlePlayerMethod.invoke(craftPlayerClass.cast(player));
            Object connection = playerConnectionField.get(handle);
            sendPacketMethod.invoke(connection, packet);
        }

        private void disable(Exception ex) {
            if (!available) {
                return;
            }
            available = false;
            plugin.getLogger().log(Level.WARNING, "Disabling legacy boss bar adapter after packet failure.", ex);
        }
    }
}
