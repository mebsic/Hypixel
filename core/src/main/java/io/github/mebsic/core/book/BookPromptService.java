package io.github.mebsic.core.book;

import io.github.mebsic.core.CorePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BookPromptService implements Listener {
    private static final String COMMAND_PREFIX = "/bookprompt";

    private final CorePlugin plugin;
    private final Map<UUID, ActivePrompt> activePrompts;

    public BookPromptService(CorePlugin plugin) {
        this.plugin = plugin;
        this.activePrompts = new ConcurrentHashMap<UUID, ActivePrompt>();
    }

    public boolean openPrompt(Player viewer, InteractiveBookPrompt prompt) {
        if (viewer == null || prompt == null) {
            return false;
        }
        UUID viewerUuid = viewer.getUniqueId();
        if (viewerUuid == null) {
            return false;
        }
        UUID promptViewerUuid = prompt.getViewerUuid();
        if (promptViewerUuid != null && !viewerUuid.equals(promptViewerUuid)) {
            return false;
        }
        cancelPrompt(viewerUuid);
        String token = createToken();
        ItemStack book = prompt.buildBook(plugin, token);
        if (book == null) {
            return false;
        }
        activePrompts.put(viewerUuid, new ActivePrompt(token, prompt));
        boolean opened = openBook(viewer, book);
        if (!opened) {
            cancelPrompt(viewerUuid);
        }
        return opened;
    }

    public void cancelPrompt(UUID viewerUuid) {
        if (viewerUuid == null) {
            return;
        }
        ActivePrompt removed = activePrompts.remove(viewerUuid);
        if (removed == null || removed.prompt == null) {
            return;
        }
        removed.prompt.onCancel(plugin, viewerUuid);
    }

    public void shutdown() {
        for (Map.Entry<UUID, ActivePrompt> entry : activePrompts.entrySet()) {
            if (entry == null) {
                continue;
            }
            ActivePrompt active = entry.getValue();
            if (active == null || active.prompt == null) {
                continue;
            }
            active.prompt.onCancel(plugin, entry.getKey());
        }
        activePrompts.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPromptCommand(PlayerCommandPreprocessEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(COMMAND_PREFIX + " ")) {
            return;
        }
        event.setCancelled(true);

        String[] parts = trimmed.split("\\s+");
        if (parts.length < 3) {
            return;
        }
        String token = parts[1] == null ? "" : parts[1].trim();
        String action = parts[2] == null ? "" : parts[2].trim().toLowerCase(Locale.ROOT);
        if (token.isEmpty() || action.isEmpty()) {
            return;
        }

        Player viewer = event.getPlayer();
        UUID viewerUuid = viewer.getUniqueId();
        ActivePrompt active = activePrompts.get(viewerUuid);
        if (active == null || active.prompt == null) {
            return;
        }
        if (!active.token.equalsIgnoreCase(token)) {
            return;
        }

        activePrompts.remove(viewerUuid);
        if ("yes".equals(action)) {
            active.prompt.onYes(plugin, viewer);
            return;
        }
        if ("no".equals(action)) {
            active.prompt.onNo(plugin, viewer);
            return;
        }
        active.prompt.onCancel(plugin, viewerUuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        cancelPrompt(event.getPlayer().getUniqueId());
    }

    private String createToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private boolean openBook(Player viewer, ItemStack book) {
        if (viewer == null || book == null || !viewer.isOnline()) {
            return false;
        }
        viewer.closeInventory();
        try {
            Method modern = viewer.getClass().getMethod("openBook", ItemStack.class);
            modern.invoke(viewer, book);
            return true;
        } catch (Exception ignored) {
            // Older API fallback below.
        }
        try {
            Method getHandle = viewer.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(viewer);
            if (handle == null) {
                return false;
            }
            String craftPackage = viewer.getClass().getPackage().getName();
            String craftBase = craftPackage.contains(".entity")
                    ? craftPackage.substring(0, craftPackage.lastIndexOf('.'))
                    : craftPackage;
            Class<?> craftItemStack = Class.forName(craftBase + ".inventory.CraftItemStack");
            Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
            Object nmsBook = asNmsCopy.invoke(null, book);
            if (openBookViaHandle(viewer, handle, nmsBook, book)) {
                return true;
            }
            return openBookLegacy(viewer, book);
        } catch (Exception ignored) {
            return openBookLegacy(viewer, book);
        }
    }

    private Method findCompatibleMethod(Class<?> owner, String name, Class<?> paramType) {
        if (owner == null || name == null || name.trim().isEmpty()) {
            return null;
        }
        for (Method method : owner.getMethods()) {
            if (method == null || !name.equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params == null || params.length != 1) {
                continue;
            }
            if (params[0].isAssignableFrom(paramType) || paramType.isAssignableFrom(params[0])) {
                return method;
            }
        }
        return null;
    }

    private boolean openBookViaHandle(Player viewer, Object handle, Object nmsBook, ItemStack book) {
        if (viewer == null || handle == null || nmsBook == null || book == null) {
            return false;
        }
        int slot = viewer.getInventory().getHeldItemSlot();
        ItemStack previous = viewer.getInventory().getItem(slot);
        viewer.getInventory().setItem(slot, book);
        viewer.updateInventory();
        boolean opened = false;
        Class<?> owner = handle.getClass();
        Class<?> bookType = nmsBook.getClass();
        try {
            Method named = findCompatibleMethod(owner, "openBook", bookType);
            if (named != null) {
                named.invoke(handle, nmsBook);
                opened = true;
                return true;
            }
            Method twoArg = findBookMethodWithHand(owner, bookType);
            if (twoArg != null) {
                Object hand = resolveMainHand(twoArg.getParameterTypes()[1]);
                if (hand != null) {
                    twoArg.invoke(handle, nmsBook, hand);
                    opened = true;
                    return true;
                }
            }
            Method oneArgFallback = findObfuscatedOneArgBookMethod(owner, bookType);
            if (oneArgFallback != null) {
                oneArgFallback.invoke(handle, nmsBook);
                opened = true;
                return true;
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            restoreHeldItem(viewer, slot, previous, opened);
        }
        return false;
    }

    private Method findBookMethodWithHand(Class<?> owner, Class<?> bookType) {
        if (owner == null || bookType == null) {
            return null;
        }
        Method fallback = null;
        for (Method method : owner.getMethods()) {
            if (method == null) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params == null || params.length != 2) {
                continue;
            }
            if (!params[0].isAssignableFrom(bookType) && !bookType.isAssignableFrom(params[0])) {
                continue;
            }
            if (!params[1].isEnum()) {
                continue;
            }
            if ("openBook".equals(method.getName())) {
                return method;
            }
            if ("a".equals(method.getName())) {
                fallback = method;
                continue;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    private Method findObfuscatedOneArgBookMethod(Class<?> owner, Class<?> bookType) {
        if (owner == null || bookType == null) {
            return null;
        }
        for (Method method : owner.getMethods()) {
            if (method == null || !"a".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params == null || params.length != 1) {
                continue;
            }
            if (!params[0].isAssignableFrom(bookType) && !bookType.isAssignableFrom(params[0])) {
                continue;
            }
            if (method.getReturnType() == Void.TYPE) {
                return method;
            }
        }
        return null;
    }

    private Object resolveMainHand(Class<?> handType) {
        if (handType == null || !handType.isEnum()) {
            return null;
        }
        Object[] constants = handType.getEnumConstants();
        if (constants == null || constants.length == 0) {
            return null;
        }
        for (Object constant : constants) {
            if (constant == null) {
                continue;
            }
            String key = constant.toString();
            if (key == null) {
                continue;
            }
            String upper = key.trim().toUpperCase(Locale.ROOT);
            if ("MAIN_HAND".equals(upper) || "MAINHAND".equals(upper) || "HAND".equals(upper)) {
                return constant;
            }
        }
        return constants[0];
    }

    private boolean openBookLegacy(Player viewer, ItemStack book) {
        if (viewer == null || book == null) {
            return false;
        }
        int slot = viewer.getInventory().getHeldItemSlot();
        ItemStack previous = viewer.getInventory().getItem(slot);
        boolean opened = false;
        try {
            viewer.getInventory().setItem(slot, book);
            viewer.updateInventory();

            Method getHandle = viewer.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(viewer);
            if (handle == null) {
                return false;
            }

            Field playerConnectionField = handle.getClass().getField("playerConnection");
            Object playerConnection = playerConnectionField.get(handle);
            if (playerConnection == null) {
                return false;
            }

            String craftPackage = viewer.getClass().getPackage().getName();
            String craftBase = craftPackage.contains(".entity")
                    ? craftPackage.substring(0, craftPackage.lastIndexOf('.'))
                    : craftPackage;
            String version = craftBase.substring(craftBase.lastIndexOf('.') + 1);
            String nmsPackage = "net.minecraft.server." + version;

            Class<?> serializerClass = Class.forName(nmsPackage + ".PacketDataSerializer");
            Class<?> payloadClass = Class.forName(nmsPackage + ".PacketPlayOutCustomPayload");
            Class<?> packetClass = Class.forName(nmsPackage + ".Packet");
            Method sendPacket = findCompatibleMethod(playerConnection.getClass(), "sendPacket", packetClass);
            if (sendPacket == null) {
                return false;
            }
            if (!sendLegacyBookOpenPacket(playerConnection, sendPacket, serializerClass, payloadClass, false)
                    && !sendLegacyBookOpenPacket(playerConnection, sendPacket, serializerClass, payloadClass, true)) {
                return false;
            }
            opened = true;
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            restoreHeldItem(viewer, slot, previous, opened);
        }
    }

    private boolean sendLegacyBookOpenPacket(Object playerConnection,
                                             Method sendPacket,
                                             Class<?> serializerClass,
                                             Class<?> payloadClass,
                                             boolean includeHandByte) {
        if (playerConnection == null || sendPacket == null || serializerClass == null || payloadClass == null) {
            return false;
        }
        try {
            Class<?> byteBufClass = Class.forName("io.netty.buffer.ByteBuf");
            Class<?> unpooledClass = Class.forName("io.netty.buffer.Unpooled");
            Object byteBuf;
            if (includeHandByte) {
                byteBuf = unpooledClass.getMethod("buffer", int.class).invoke(null, 256);
                byteBufClass.getMethod("setByte", int.class, int.class).invoke(byteBuf, 0, 0);
                byteBufClass.getMethod("writerIndex", int.class).invoke(byteBuf, 1);
            } else {
                byteBuf = unpooledClass.getMethod("wrappedBuffer", byte[].class).invoke(null, new Object[] {new byte[0]});
            }
            Object serializer = serializerClass.getConstructor(byteBufClass).newInstance(byteBuf);
            Object packet = payloadClass.getConstructor(String.class, serializerClass).newInstance("MC|BOpen", serializer);
            sendPacket.invoke(playerConnection, packet);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void restoreHeldItem(Player viewer, int slot, ItemStack previous, boolean delayed) {
        if (viewer == null) {
            return;
        }
        final ItemStack restore = previous == null ? null : previous.clone();
        Runnable restoreTask = () -> {
            if (!viewer.isOnline()) {
                return;
            }
            viewer.getInventory().setItem(slot, restore);
            viewer.updateInventory();
        };
        if (delayed && plugin != null && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, restoreTask, 5L);
            return;
        }
        restoreTask.run();
    }

    private static final class ActivePrompt {
        private final String token;
        private final InteractiveBookPrompt prompt;

        private ActivePrompt(String token, InteractiveBookPrompt prompt) {
            this.token = token == null ? "" : token;
            this.prompt = prompt;
        }
    }
}
