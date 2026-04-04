package io.github.mebsic.core.listener;

import com.mongodb.client.MongoCollection;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.store.MapConfigStore;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.UpdateOptions;

public class HubImageDisplayListener implements Listener {
    private static final String MAP_CONFIG_UPDATE_CHANNEL = "map_config_update";
    private static final String MAP_CONFIG_UPDATE_PREFIX = "maps:";
    private static final long IMAGE_INITIAL_REFRESH_DELAY_TICKS = 1L;
    private static final long IMAGE_REFRESH_INTERVAL_TICKS = 1200L;
    private static final int IMAGE_SOURCE_CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int IMAGE_SOURCE_READ_TIMEOUT_MILLIS = 10_000;
    private static final int IMAGE_GRID_WIDTH = 15;
    private static final int IMAGE_GRID_HEIGHT = 6;
    private static final int IMAGE_TILE_SIZE = 128;
    private static final int IMAGE_TOTAL_TILES = IMAGE_GRID_WIDTH * IMAGE_GRID_HEIGHT;

    private final Plugin plugin;
    private final CorePlugin corePlugin;
    private final ServerType serverType;
    private final AtomicBoolean refreshInFlight;
    private final BukkitTask refreshTask;
    private final List<MapTile> mapTiles;
    private volatile String activeGameKey;
    private RuntimeImage runtimeImage;

    public HubImageDisplayListener(Plugin plugin, CorePlugin corePlugin, ServerType serverType) {
        this.plugin = plugin;
        this.corePlugin = corePlugin;
        this.serverType = serverType == null ? ServerType.UNKNOWN : serverType;
        this.refreshInFlight = new AtomicBoolean(false);
        this.activeGameKey = MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
        this.mapTiles = new ArrayList<MapTile>();
        ensureImageSourceDocument();
        subscribeToMapConfigUpdates();
        this.refreshTask = startRefreshTask();
        refreshDisplay();
    }

    public void shutdown() {
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        despawnRuntimeImage();
        mapTiles.clear();
    }

    private BukkitTask startRefreshTask() {
        if (plugin == null || !serverType.isHub()) {
            return null;
        }
        return plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshDisplay,
                IMAGE_INITIAL_REFRESH_DELAY_TICKS,
                IMAGE_REFRESH_INTERVAL_TICKS
        );
    }

    private void refreshDisplay() {
        if (plugin == null || !serverType.isHub()) {
            return;
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ResolvedImageConfig config = null;
            BufferedImage image = null;
            String imageError = "";
            try {
                config = resolveImageConfig();
                if (config != null && config.enabled && !config.imageSource.isEmpty()) {
                    image = loadImage(config.imageSource);
                }
            } catch (Exception ex) {
                imageError = safeText(ex.getMessage());
            }

            final ResolvedImageConfig finalConfig = config;
            final BufferedImage finalImage = image;
            final String finalError = imageError;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        applyResolvedImage(finalConfig, finalImage, finalError);
                    } finally {
                        refreshInFlight.set(false);
                    }
                });
            } catch (Exception ex) {
                refreshInFlight.set(false);
                plugin.getLogger().warning("Failed to apply hub image refresh!\n" + safeText(ex.getMessage()));
            }
        });
    }

    private void applyResolvedImage(ResolvedImageConfig config, BufferedImage image, String imageError) {
        if (config == null || !config.enabled) {
            despawnRuntimeImage();
            return;
        }
        this.activeGameKey = config.gameKey;
        if (config.location == null || config.imageSource.isEmpty()) {
            despawnRuntimeImage();
            return;
        }
        if (image == null) {
            if (!imageError.isEmpty()) {
                plugin.getLogger().warning("Failed to refresh hub image source \"" + config.imageSource + "\": " + imageError);
            }
            return;
        }

        World world = resolveWorld(config.location.worldName);
        if (world == null) {
            despawnRuntimeImage();
            return;
        }
        BlockFace facing = resolveFacing(config.location, config.facingOverride);
        if (facing == null || facing == BlockFace.SELF || facing == BlockFace.UP || facing == BlockFace.DOWN) {
            facing = BlockFace.SOUTH;
        }

        ensureMapTiles(world);
        if (mapTiles.size() != IMAGE_TOTAL_TILES) {
            return;
        }

        BufferedImage scaled = scaleImageForGrid(image);
        applyMapTileImages(scaled);
        ensureRuntimeFrames(world, config.location, facing);
    }

    private BufferedImage scaleImageForGrid(BufferedImage source) {
        int width = IMAGE_GRID_WIDTH * IMAGE_TILE_SIZE;
        int height = IMAGE_GRID_HEIGHT * IMAGE_TILE_SIZE;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private void ensureMapTiles(World world) {
        if (world == null) {
            return;
        }
        if (mapTiles.size() == IMAGE_TOTAL_TILES) {
            return;
        }
        mapTiles.clear();
        for (int i = 0; i < IMAGE_TOTAL_TILES; i++) {
            MapView view = Bukkit.createMap(world);
            StaticImageMapRenderer renderer = new StaticImageMapRenderer();
            for (MapRenderer existing : new ArrayList<MapRenderer>(view.getRenderers())) {
                view.removeRenderer(existing);
            }
            view.addRenderer(renderer);
            mapTiles.add(new MapTile(view, renderer));
        }
    }

    private void applyMapTileImages(BufferedImage scaled) {
        if (scaled == null || mapTiles.size() != IMAGE_TOTAL_TILES) {
            return;
        }
        for (int rowTop = 0; rowTop < IMAGE_GRID_HEIGHT; rowTop++) {
            for (int col = 0; col < IMAGE_GRID_WIDTH; col++) {
                int index = rowTop * IMAGE_GRID_WIDTH + col;
                MapTile tile = mapTiles.get(index);
                if (tile == null || tile.renderer == null) {
                    continue;
                }
                BufferedImage tileImage = scaled.getSubimage(
                        col * IMAGE_TILE_SIZE,
                        rowTop * IMAGE_TILE_SIZE,
                        IMAGE_TILE_SIZE,
                        IMAGE_TILE_SIZE
                );
                tile.renderer.setImage(tileImage);
            }
        }
    }

    private void ensureRuntimeFrames(World world, ImageLocation location, BlockFace facing) {
        if (world == null || location == null || facing == null || mapTiles.size() != IMAGE_TOTAL_TILES) {
            return;
        }
        List<UUID> previous = runtimeImage == null || runtimeImage.frameUuids == null
                ? new ArrayList<UUID>()
                : new ArrayList<UUID>(runtimeImage.frameUuids);
        List<UUID> next = new ArrayList<UUID>(IMAGE_TOTAL_TILES);

        for (int rowTop = 0; rowTop < IMAGE_GRID_HEIGHT; rowTop++) {
            for (int col = 0; col < IMAGE_GRID_WIDTH; col++) {
                int index = rowTop * IMAGE_GRID_WIDTH + col;
                Location tileLocation = tileLocation(world, location, facing, col, rowTop);
                if (tileLocation == null) {
                    continue;
                }

                ItemFrame frame = index < previous.size() ? resolveFrame(previous.get(index)) : null;
                if (frame != null && !matchesFramePlacement(frame, tileLocation, facing)) {
                    frame.remove();
                    frame = null;
                }
                if (frame == null) {
                    try {
                        frame = world.spawn(tileLocation, ItemFrame.class);
                    } catch (Exception ignored) {
                        frame = null;
                    }
                }
                if (frame == null) {
                    continue;
                }
                applyFrameFacing(frame, facing);
                if (frame.getLocation() != null && frame.getLocation().distanceSquared(tileLocation) > 0.01d) {
                    frame.teleport(tileLocation);
                }

                MapTile tile = mapTiles.get(index);
                if (tile != null && tile.mapView != null) {
                    frame.setItem(new ItemStack(Material.MAP, 1, (short) tile.mapView.getId()));
                    frame.setRotation(Rotation.NONE);
                }
                if (frame.getUniqueId() != null) {
                    next.add(frame.getUniqueId());
                }
            }
        }

        Set<UUID> nextSet = new HashSet<UUID>(next);
        for (UUID oldUuid : previous) {
            if (oldUuid == null || nextSet.contains(oldUuid)) {
                continue;
            }
            ItemFrame oldFrame = resolveFrame(oldUuid);
            if (oldFrame != null) {
                oldFrame.remove();
            }
        }

        RuntimeImage runtime = runtimeImage == null ? new RuntimeImage() : runtimeImage;
        runtime.location = location;
        runtime.facing = facing;
        runtime.imageSource = "";
        runtime.frameUuids = next;
        runtimeImage = runtime;
    }

    private Location tileLocation(World world, ImageLocation anchor, BlockFace facing, int col, int rowTop) {
        if (world == null || anchor == null || facing == null) {
            return null;
        }
        int[] step = widthStepForFacing(facing);
        int xStep = step[0];
        int zStep = step[1];
        int yOffset = (IMAGE_GRID_HEIGHT - 1) - Math.max(0, rowTop);

        double baseX = Math.floor(anchor.x) + 0.5d;
        double baseY = Math.floor(anchor.y) + 0.5d;
        double baseZ = Math.floor(anchor.z) + 0.5d;
        return new Location(
                world,
                baseX + (xStep * col),
                baseY + yOffset,
                baseZ + (zStep * col),
                anchor.yaw,
                anchor.pitch
        );
    }

    private int[] widthStepForFacing(BlockFace facing) {
        if (facing == BlockFace.NORTH) {
            return new int[] {-1, 0};
        }
        if (facing == BlockFace.SOUTH) {
            return new int[] {1, 0};
        }
        if (facing == BlockFace.EAST) {
            return new int[] {0, -1};
        }
        if (facing == BlockFace.WEST) {
            return new int[] {0, 1};
        }
        return new int[] {-1, 0};
    }

    private void applyFrameFacing(ItemFrame frame, BlockFace facing) {
        if (frame == null || facing == null) {
            return;
        }
        try {
            frame.setFacingDirection(facing, true);
            return;
        } catch (NoSuchMethodError ignored) {
            // 1.8 API fallback.
        } catch (Exception ignored) {
            // 1.8 API fallback.
        }
        try {
            frame.setFacingDirection(facing);
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    private boolean matchesFramePlacement(ItemFrame frame, Location target, BlockFace facing) {
        if (frame == null || target == null || target.getWorld() == null || facing == null) {
            return false;
        }
        Location current = frame.getLocation();
        if (current == null || current.getWorld() == null) {
            return false;
        }
        if (!target.getWorld().equals(current.getWorld())) {
            return false;
        }
        if (current.distanceSquared(target) > 0.01d) {
            return false;
        }
        return facing == frame.getFacing();
    }

    private void despawnRuntimeImage() {
        RuntimeImage runtime = runtimeImage;
        runtimeImage = null;
        if (runtime == null || runtime.frameUuids == null || runtime.frameUuids.isEmpty()) {
            return;
        }
        for (UUID uuid : runtime.frameUuids) {
            ItemFrame frame = resolveFrame(uuid);
            if (frame != null) {
                frame.remove();
            }
        }
    }

    private ItemFrame resolveFrame(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ItemFrame) || entity.getUniqueId() == null) {
                    continue;
                }
                if (uuid.equals(entity.getUniqueId())) {
                    return (ItemFrame) entity;
                }
            }
        }
        return null;
    }

    private BufferedImage loadImage(String rawSource) throws Exception {
        String source = safeText(rawSource);
        if (source.isEmpty()) {
            return null;
        }
        boolean extensionSupported = hasSupportedImageExtension(source);
        if (source.toLowerCase(Locale.ROOT).startsWith("http://")
                || source.toLowerCase(Locale.ROOT).startsWith("https://")) {
            URLConnection connection = new URL(source).openConnection();
            connection.setConnectTimeout(IMAGE_SOURCE_CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(IMAGE_SOURCE_READ_TIMEOUT_MILLIS);
            if (connection instanceof HttpURLConnection) {
                HttpURLConnection http = (HttpURLConnection) connection;
                http.setInstanceFollowRedirects(true);
            }
            String contentType = safeText(connection.getContentType()).toLowerCase(Locale.ROOT);
            if (!extensionSupported && !isSupportedImageContentType(contentType)) {
                throw new IllegalArgumentException("Only PNG/JPG image sources are supported.");
            }
            try (InputStream stream = connection.getInputStream()) {
                BufferedImage image = ImageIO.read(stream);
                if (image == null) {
                    return null;
                }
                if (!extensionSupported && !isSupportedImageContentType(contentType)) {
                    return null;
                }
                return image;
            }
        }
        if (!extensionSupported) {
            throw new IllegalArgumentException("Only PNG/JPG image files are supported.");
        }
        Path path = Paths.get(source);
        if (!path.isAbsolute() && plugin != null && plugin.getDataFolder() != null) {
            path = plugin.getDataFolder().toPath().resolve(source).normalize();
        }
        if (!Files.exists(path)) {
            return null;
        }
        try (InputStream stream = Files.newInputStream(path)) {
            return ImageIO.read(stream);
        }
    }

    private boolean hasSupportedImageExtension(String source) {
        String clean = safeText(source).toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) {
            return false;
        }
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        int fragment = clean.indexOf('#');
        if (fragment >= 0) {
            clean = clean.substring(0, fragment);
        }
        return clean.endsWith(".png")
                || clean.endsWith(".jpg")
                || clean.endsWith(".jpeg");
    }

    private boolean isSupportedImageContentType(String contentType) {
        String normalized = safeText(contentType).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("image/png")
                || normalized.startsWith("image/jpeg")
                || normalized.startsWith("image/jpg");
    }

    private BlockFace resolveFacing(ImageLocation location, String override) {
        BlockFace configured = parseFacing(override);
        if (configured != null && configured != BlockFace.SELF && configured != BlockFace.UP && configured != BlockFace.DOWN) {
            return configured;
        }
        float yaw = location == null ? 0.0f : location.yaw;
        BlockFace playerFacing = yawToBlockFace(yaw);
        if (playerFacing == null) {
            return BlockFace.SOUTH;
        }
        BlockFace opposite = playerFacing.getOppositeFace();
        return opposite == null ? BlockFace.SOUTH : opposite;
    }

    private BlockFace parseFacing(String raw) {
        String text = safeText(raw).toUpperCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return BlockFace.valueOf(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private BlockFace yawToBlockFace(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized < -180.0f) {
            normalized += 360.0f;
        }
        if (normalized > 180.0f) {
            normalized -= 360.0f;
        }
        if (normalized >= 45.0f && normalized < 135.0f) {
            return BlockFace.WEST;
        }
        if (normalized >= -135.0f && normalized < -45.0f) {
            return BlockFace.EAST;
        }
        if (normalized >= 135.0f || normalized < -135.0f) {
            return BlockFace.NORTH;
        }
        return BlockFace.SOUTH;
    }

    private void subscribeToMapConfigUpdates() {
        if (corePlugin == null || !serverType.isHub()) {
            return;
        }
        if (corePlugin.getPubSubService() == null) {
            return;
        }
        corePlugin.getPubSubService().subscribe(MAP_CONFIG_UPDATE_CHANNEL, this::handleMapConfigUpdateMessage);
    }

    private void handleMapConfigUpdateMessage(String message) {
        String updatedKey = parseUpdatedGameKey(message);
        if (updatedKey.isEmpty()) {
            return;
        }
        if (!shouldReloadForGameKey(updatedKey)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, this::refreshDisplay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null || plugin == null || !serverType.isHub()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, this::refreshDisplay, 2L);
    }

    private String parseUpdatedGameKey(String message) {
        String raw = safeText(message);
        if (raw.isEmpty()) {
            return "";
        }
        String key = raw;
        if (raw.toLowerCase(Locale.ROOT).startsWith(MAP_CONFIG_UPDATE_PREFIX)) {
            key = raw.substring(MAP_CONFIG_UPDATE_PREFIX.length());
        }
        key = MapConfigStore.normalizeGameKey(key);
        if (key.isEmpty()) {
            return "";
        }
        return key;
    }

    private boolean shouldReloadForGameKey(String updatedKey) {
        String normalized = MapConfigStore.normalizeGameKey(updatedKey);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.equalsIgnoreCase(safeText(activeGameKey))) {
            return true;
        }
        return containsIgnoreCase(resolveGameKeyCandidates(), normalized);
    }

    private ResolvedImageConfig resolveImageConfig() {
        List<String> gameKeyCandidates = resolveGameKeyCandidates();
        ImageSourceOverride sourceOverride = resolveImageSourceOverride();
        for (String gameKey : gameKeyCandidates) {
            if (gameKey == null || gameKey.trim().isEmpty()) {
                continue;
            }
            Document root = loadRoot(gameKey);
            if (root == null) {
                continue;
            }
            Document gameSection = resolveGameSection(root, gameKey);
            if (gameSection == null && !MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY.equals(gameKey)) {
                gameSection = resolveGameSection(root, MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY);
            }
            if (gameSection == null) {
                continue;
            }
            Document serverTypeSection = resolveServerTypeSection(gameSection, serverType);
            if (serverTypeSection == null) {
                serverTypeSection = resolveServerTypeSection(root, serverType);
            }
            if (serverTypeSection == null) {
                continue;
            }
            Document information = asDocument(serverTypeSection.get(MongoManager.MAP_INFORMATION_KEY));
            if (information == null) {
                continue;
            }
            ImageInformation parsed = parseInformation(information);
            if (parsed == null) {
                continue;
            }
            String resolvedGameKey = MapConfigStore.normalizeGameKey(gameKey);
            if (resolvedGameKey.isEmpty()) {
                resolvedGameKey = MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY;
            }
            String resolvedSource = parsed.imageSource;
            String resolvedFacing = parsed.facingOverride;
            boolean resolvedEnabled = parsed.enabled;
            if (sourceOverride != null) {
                if (!sourceOverride.imageSource.isEmpty()) {
                    resolvedSource = sourceOverride.imageSource;
                }
                if (!sourceOverride.facingOverride.isEmpty()) {
                    resolvedFacing = sourceOverride.facingOverride;
                }
                if (sourceOverride.enabled != null) {
                    resolvedEnabled = sourceOverride.enabled;
                }
            }
            return new ResolvedImageConfig(
                    resolvedGameKey,
                    parsed.location,
                    resolvedSource,
                    resolvedEnabled,
                    resolvedFacing
            );
        }
        return null;
    }

    private ImageInformation parseInformation(Document information) {
        if (information == null) {
            return null;
        }
        ImageLocation location = parseImageLocation(information.get("imageDisplay"));
        if (location == null) {
            location = parseImageLocation(information.get("imageLocation"));
        }
        if (location == null) {
            location = parseImageLocation(information.get("imageDisplayLocation"));
        }
        if (location == null) {
            location = parseImageLocation(information.get("location"));
        }

        String source = safeText(information.get("imageUrl"));
        if (source.isEmpty()) {
            source = safeText(information.get("imageSource"));
        }
        if (source.isEmpty()) {
            source = safeText(information.get("imagePath"));
        }
        if (source.isEmpty()) {
            source = safeText(information.get("source"));
        }
        String facingOverride = safeText(information.get("imageFacing"));

        Document imageDisplaySection = asDocument(information.get("imageDisplay"));
        if (imageDisplaySection != null) {
            if (source.isEmpty()) {
                source = safeText(imageDisplaySection.get("url"));
            }
            if (source.isEmpty()) {
                source = safeText(imageDisplaySection.get("source"));
            }
            if (source.isEmpty()) {
                source = safeText(imageDisplaySection.get("path"));
            }
            if (facingOverride.isEmpty()) {
                facingOverride = safeText(imageDisplaySection.get("facing"));
            }
        }

        Document imageSection = asDocument(information.get("image"));
        if (imageSection != null) {
            if (source.isEmpty()) {
                source = safeText(imageSection.get("url"));
            }
            if (source.isEmpty()) {
                source = safeText(imageSection.get("source"));
            }
            if (source.isEmpty()) {
                source = safeText(imageSection.get("path"));
            }
            if (location == null) {
                location = parseImageLocation(imageSection.get("location"));
            }
            if (location == null) {
                location = parseImageLocation(imageSection);
            }
            if (facingOverride.isEmpty()) {
                facingOverride = safeText(imageSection.get("facing"));
            }
        } else if (source.isEmpty()) {
            source = safeText(information.get("image"));
        }

        Boolean enabledValue = readBoolean(information.get("imageEnabled"));
        if (enabledValue == null) {
            enabledValue = readBoolean(information.get("enabled"));
        }
        boolean enabled = enabledValue == null || enabledValue;
        if (!enabled && location == null && source.isEmpty()) {
            return new ImageInformation(null, "", false, "");
        }
        if (location == null && source.isEmpty()) {
            return null;
        }
        return new ImageInformation(location, source, enabled, facingOverride);
    }

    private ImageLocation parseImageLocation(Object rawLocation) {
        Document locationDoc = asDocument(rawLocation);
        if (locationDoc != null) {
            String world = safeText(locationDoc.get("world"));
            Double x = readDouble(locationDoc.get("x"));
            Double y = readDouble(locationDoc.get("y"));
            Double z = readDouble(locationDoc.get("z"));
            if (!world.isEmpty() && x != null && y != null && z != null) {
                float yaw = readFloat(locationDoc.get("yaw"), 0.0f);
                float pitch = readFloat(locationDoc.get("pitch"), 0.0f);
                return new ImageLocation(world, x, y, z, yaw, pitch);
            }
            Object nested = locationDoc.get("location");
            if (nested != null && nested != rawLocation) {
                ImageLocation nestedLocation = parseImageLocation(nested);
                if (nestedLocation != null) {
                    return nestedLocation;
                }
            }
            return null;
        }

        String serialized = rawLocation instanceof String ? safeText(rawLocation) : "";
        if (serialized.isEmpty()) {
            return null;
        }
        String[] parts = serialized.split(",");
        if (parts.length < 4) {
            return null;
        }
        String world = safeText(parts[0]);
        if (world.isEmpty()) {
            return null;
        }
        Double x = readDouble(parts[1]);
        Double y = readDouble(parts[2]);
        Double z = readDouble(parts[3]);
        if (x == null || y == null || z == null) {
            return null;
        }
        float yaw = parts.length > 4 ? readFloat(parts[4], 0.0f) : 0.0f;
        float pitch = parts.length > 5 ? readFloat(parts[5], 0.0f) : 0.0f;
        return new ImageLocation(world, x, y, z, yaw, pitch);
    }

    private Document resolveServerTypeSection(Document gameSection, ServerType type) {
        if (gameSection == null || type == null || type == ServerType.UNKNOWN) {
            return null;
        }
        Document serverTypes = asDocument(gameSection.get(MongoManager.MAP_SERVER_TYPES_KEY));
        if (serverTypes == null) {
            return null;
        }
        Document direct = asDocument(serverTypes.get(type.name()));
        if (direct != null) {
            return direct;
        }
        ServerType hubVariant = type.toHubType();
        if (hubVariant != null && hubVariant != ServerType.UNKNOWN) {
            Document fallback = asDocument(serverTypes.get(hubVariant.name()));
            if (fallback != null) {
                return fallback;
            }
        }
        for (Map.Entry<String, Object> entry : serverTypes.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            if (entry.getKey().equalsIgnoreCase(type.name())) {
                return asDocument(entry.getValue());
            }
            if (hubVariant != null && hubVariant != ServerType.UNKNOWN
                    && entry.getKey().equalsIgnoreCase(hubVariant.name())) {
                return asDocument(entry.getValue());
            }
        }
        return null;
    }

    private List<String> resolveGameKeyCandidates() {
        List<String> candidates = new ArrayList<String>();
        String configured = corePlugin == null || corePlugin.getConfig() == null
                ? ""
                : corePlugin.getConfig().getString("server.group", "");
        addGameKeyCandidate(candidates, configured);
        if (serverType != null) {
            addGameKeyCandidate(candidates, serverType.getGameTypeDisplayName());
            String typeName = safeText(serverType.name());
            if (typeName.endsWith("_HUB")) {
                typeName = typeName.substring(0, typeName.length() - "_HUB".length());
            }
            addGameKeyCandidate(candidates, typeName);
        }
        addGameKeyCandidate(candidates, MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY);
        if (candidates.isEmpty()) {
            candidates.add(MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY);
        }
        return candidates;
    }

    private ImageSourceOverride resolveImageSourceOverride() {
        if (corePlugin == null || corePlugin.getMongoManager() == null) {
            return null;
        }
        MongoCollection<Document> collection = corePlugin.getMongoManager().getCollection(MongoManager.MURDER_MYSTERY_INFORMATION_COLLECTION);
        if (collection == null) {
            return null;
        }
        Document record = collection.find(eq("_id", MongoManager.MURDER_MYSTERY_INFORMATION_DOCUMENT_ID)).first();
        return parseImageSourceOverride(record);
    }

    private void ensureImageSourceDocument() {
        if (corePlugin == null || corePlugin.getMongoManager() == null) {
            return;
        }
        MongoCollection<Document> collection = corePlugin.getMongoManager().getCollection(MongoManager.MURDER_MYSTERY_INFORMATION_COLLECTION);
        if (collection == null) {
            return;
        }
        Document defaults = new Document();
        defaults.put("_id", MongoManager.MURDER_MYSTERY_INFORMATION_DOCUMENT_ID);
        defaults.put("imageUrl", "");
        defaults.put("imageEnabled", true);
        defaults.put("imageFacing", "");
        try {
            collection.updateOne(
                    eq("_id", MongoManager.MURDER_MYSTERY_INFORMATION_DOCUMENT_ID),
                    new Document("$setOnInsert", defaults),
                    new UpdateOptions().upsert(true)
            );
        } catch (Exception ex) {
            if (plugin != null) {
                plugin.getLogger().warning("Failed to ensure default hub image source document: " + safeText(ex.getMessage()));
            }
        }
    }

    private ImageSourceOverride parseImageSourceOverride(Document record) {
        if (record == null) {
            return null;
        }
        String source = safeText(record.get("imageUrl"));
        String facing = safeText(record.get("imageFacing"));
        Boolean enabled = readBoolean(record.get("imageEnabled"));
        if (source.isEmpty() && facing.isEmpty() && enabled == null) {
            return null;
        }
        return new ImageSourceOverride(source, facing, enabled);
    }

    private void addGameKeyCandidate(List<String> candidates, String raw) {
        if (candidates == null) {
            return;
        }
        String normalized = MapConfigStore.normalizeGameKey(raw);
        if (normalized.isEmpty() || containsIgnoreCase(candidates, normalized)) {
            return;
        }
        candidates.add(normalized);
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null || target.trim().isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value != null && target.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private Document loadRoot(String gameKey) {
        if (corePlugin == null || corePlugin.getMongoManager() == null) {
            return null;
        }
        MongoCollection<Document> maps = corePlugin.getMongoManager().getCollection(MongoManager.MAPS_COLLECTION);
        if (maps == null || gameKey == null || gameKey.trim().isEmpty()) {
            return null;
        }
        return maps.find(eq("_id", gameKey)).first();
    }

    private Document resolveGameSection(Document root, String gameKey) {
        if (root == null || gameKey == null || gameKey.trim().isEmpty()) {
            return null;
        }
        Document gameTypes = asDocument(root.get("gameTypes"));
        Document section = asDocument(gameTypes == null ? null : gameTypes.get(gameKey));
        if (section != null) {
            return section;
        }
        section = asDocument(root.get(gameKey));
        if (section != null) {
            return section;
        }
        if (gameTypes != null) {
            section = asDocument(gameTypes.get(MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY));
            if (section != null) {
                return section;
            }
        }
        if (root.get("maps") instanceof List<?>) {
            return root;
        }
        return null;
    }

    private World resolveWorld(String worldName) {
        String target = safeText(worldName);
        if (!target.isEmpty()) {
            World world = Bukkit.getWorld(target);
            if (world != null) {
                return world;
            }
            for (World current : Bukkit.getWorlds()) {
                if (current != null && target.equalsIgnoreCase(current.getName())) {
                    return current;
                }
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    private Double readDouble(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        String text = safeText(raw);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private float readFloat(Object raw, float fallback) {
        Double value = readDouble(raw);
        return value == null ? fallback : value.floatValue();
    }

    private Boolean readBoolean(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        if (raw instanceof Number) {
            return ((Number) raw).intValue() != 0;
        }
        String text = safeText(raw).toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text) || "no".equals(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private String safeText(Object raw) {
        if (raw == null) {
            return "";
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Document asDocument(Object raw) {
        if (raw instanceof Document) {
            return (Document) raw;
        }
        if (raw instanceof Map<?, ?>) {
            return new Document((Map<String, Object>) raw);
        }
        return null;
    }

    private static final class MapTile {
        private final MapView mapView;
        private final StaticImageMapRenderer renderer;

        private MapTile(MapView mapView, StaticImageMapRenderer renderer) {
            this.mapView = mapView;
            this.renderer = renderer;
        }
    }

    private static final class ImageInformation {
        private final ImageLocation location;
        private final String imageSource;
        private final boolean enabled;
        private final String facingOverride;

        private ImageInformation(ImageLocation location, String imageSource, boolean enabled, String facingOverride) {
            this.location = location;
            this.imageSource = imageSource == null ? "" : imageSource;
            this.enabled = enabled;
            this.facingOverride = facingOverride == null ? "" : facingOverride;
        }
    }

    private static final class ResolvedImageConfig {
        private final String gameKey;
        private final ImageLocation location;
        private final String imageSource;
        private final boolean enabled;
        private final String facingOverride;

        private ResolvedImageConfig(String gameKey,
                                    ImageLocation location,
                                    String imageSource,
                                    boolean enabled,
                                    String facingOverride) {
            this.gameKey = gameKey == null ? MongoManager.MAP_CONFIG_DEFAULT_GAME_KEY : gameKey;
            this.location = location;
            this.imageSource = imageSource == null ? "" : imageSource;
            this.enabled = enabled;
            this.facingOverride = facingOverride == null ? "" : facingOverride;
        }
    }

    private static final class ImageSourceOverride {
        private final String imageSource;
        private final String facingOverride;
        private final Boolean enabled;

        private ImageSourceOverride(String imageSource, String facingOverride, Boolean enabled) {
            this.imageSource = imageSource == null ? "" : imageSource;
            this.facingOverride = facingOverride == null ? "" : facingOverride;
            this.enabled = enabled;
        }
    }

    private static final class ImageLocation {
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;

        private ImageLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
            this.worldName = worldName == null ? "" : worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class RuntimeImage {
        private List<UUID> frameUuids;
        private ImageLocation location;
        private BlockFace facing;
        private String imageSource;
    }

    private static final class StaticImageMapRenderer extends MapRenderer {
        private BufferedImage image;

        private void setImage(BufferedImage image) {
            this.image = image;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (canvas == null || image == null) {
                return;
            }
            canvas.drawImage(0, 0, image);
        }
    }
}
