package io.github.mebsic.core.service;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.menu.EditMessageColorMenu;
import io.github.mebsic.core.menu.EditNewsMenu;
import io.github.mebsic.core.menu.ManageNewsMenu;
import io.github.mebsic.core.menu.NewsMenu;
import io.github.mebsic.core.model.BossBarMessage;
import io.github.mebsic.core.store.BossBarMessageStore;
import io.github.mebsic.core.util.CommonMessages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class NewsEditService implements Listener {
    private static final String ENTER_TEXT_MESSAGE = ChatColor.GREEN + "Enter the text you want to display!";
    private static final String UNAVAILABLE_MESSAGE = ChatColor.RED + "News items are unavailable! Please try again later.";
    private static final String EMPTY_TEXT_MESSAGE = ChatColor.RED + "News text cannot be empty!";
    private static final String DONE_MESSAGE = ChatColor.GREEN + CommonMessages.DONE;
    private static final String SAVE_FAILED_MESSAGE = ChatColor.RED + "Failed to save the news item!";
    private static final List<ChatColor> COLOR_CYCLE = Arrays.asList(
            ChatColor.AQUA,
            ChatColor.BLACK,
            ChatColor.BLUE,
            ChatColor.GOLD,
            ChatColor.GRAY,
            ChatColor.GREEN,
            ChatColor.LIGHT_PURPLE,
            ChatColor.RED,
            ChatColor.WHITE,
            ChatColor.YELLOW,
            ChatColor.DARK_AQUA,
            ChatColor.DARK_BLUE,
            ChatColor.DARK_GRAY,
            ChatColor.DARK_GREEN,
            ChatColor.DARK_PURPLE,
            ChatColor.DARK_RED
    );
    private static final ChatColor DEFAULT_NEW_COLOR = COLOR_CYCLE.get(0);

    private final CorePlugin plugin;
    private final BossBarMessageStore messageStore;
    private final Map<UUID, TextInputMode> pendingTextInput;
    private final Set<UUID> pendingSaves;
    private final Map<UUID, EditSession> activeEditSessions;

    public NewsEditService(CorePlugin plugin) {
        this.plugin = plugin;
        this.messageStore = plugin == null || plugin.getMongoManager() == null
                ? null
                : new BossBarMessageStore(plugin.getMongoManager());
        this.pendingTextInput = new ConcurrentHashMap<UUID, TextInputMode>();
        this.pendingSaves = ConcurrentHashMap.newKeySet();
        this.activeEditSessions = new ConcurrentHashMap<UUID, EditSession>();
    }

    public boolean isAvailable() {
        return messageStore != null;
    }

    public void openMainMenu(Player player) {
        if (player == null) {
            return;
        }
        new NewsMenu(this).open(player);
    }

    public void openManageNewsMenu(Player player, int page) {
        if (player == null) {
            return;
        }
        new ManageNewsMenu(this, page).open(player);
    }

    public void openEditNewsFromManage(Player player, BossBarMessage news, int page) {
        beginEditing(player, news, EditOrigin.MANAGE, page);
    }

    public void startAddNewsInput(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        clearEditingState(uuid);
        pendingTextInput.put(uuid, TextInputMode.ADD_NEWS);
        player.sendMessage(ENTER_TEXT_MESSAGE);
    }

    public void startEditMessageInput(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        EditSession session = session(uuid);
        if (session == null) {
            return;
        }
        pendingTextInput.put(uuid, TextInputMode.EDIT_MESSAGE);
        player.closeInventory();
        player.sendMessage(ENTER_TEXT_MESSAGE);
    }

    public void openColorEditor(Player player) {
        if (player == null || session(player.getUniqueId()) == null) {
            return;
        }
        new EditMessageColorMenu(this).open(player);
    }

    public void openEditMenu(Player player) {
        if (player == null || session(player.getUniqueId()) == null) {
            return;
        }
        new EditNewsMenu(this).open(player);
    }

    public EditSessionSnapshot getEditSessionSnapshot(Player player) {
        if (player == null) {
            return null;
        }
        EditSession session = session(player.getUniqueId());
        if (session == null) {
            return null;
        }
        return new EditSessionSnapshot(
                session.messageId,
                session.text,
                session.messageType,
                session.startColor,
                session.sweepColor,
                session.endColor
        );
    }

    public void toggleMessageType(Player player) {
        if (player == null) {
            return;
        }
        EditSession session = session(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.messageType = session.messageType == MessageType.FLASH ? MessageType.SWEEP : MessageType.FLASH;
    }

    public void cycleColor(Player player, ColorSlot slot) {
        cycleColor(player, slot, false);
    }

    public void cycleColor(Player player, ColorSlot slot, boolean backwards) {
        if (player == null || slot == null) {
            return;
        }
        EditSession session = session(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (slot == ColorSlot.START) {
            session.startColor = cycleColor(session.startColor, backwards);
            return;
        }
        if (slot == ColorSlot.SWEEP) {
            session.sweepColor = cycleColor(session.sweepColor, backwards);
            return;
        }
        session.endColor = cycleColor(session.endColor, backwards);
    }

    public void saveAndFinish(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        EditSession session = activeEditSessions.remove(uuid);
        pendingTextInput.remove(uuid);
        if (session == null) {
            player.closeInventory();
            player.sendMessage(DONE_MESSAGE);
            return;
        }
        if (messageStore == null) {
            player.closeInventory();
            player.sendMessage(UNAVAILABLE_MESSAGE);
            return;
        }
        pendingSaves.add(uuid);
        player.closeInventory();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> saveSession(uuid, session));
    }

    public void cancelAndFinish(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        EditSession session = activeEditSessions.remove(uuid);
        pendingTextInput.remove(uuid);
        if (session == null) {
            player.closeInventory();
            player.sendMessage(DONE_MESSAGE);
            return;
        }
        if (session.origin == EditOrigin.MANAGE) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online == null || !online.isOnline()) {
                    return;
                }
                new ManageNewsMenu(this, session.managePage).open(online);
            });
            return;
        }
        player.closeInventory();
        player.sendMessage(DONE_MESSAGE);
    }

    public List<BossBarMessage> loadAddedNews() {
        if (messageStore == null) {
            return new ArrayList<BossBarMessage>();
        }
        messageStore.ensureDefaults();
        return messageStore.loadEditableHubNews(true);
    }

    public BossBarMessage loadNewsById(String id) {
        if (messageStore == null) {
            return null;
        }
        return messageStore.loadHubNewsById(id);
    }

    public boolean deleteNewsById(String id) {
        if (messageStore == null) {
            return false;
        }
        return messageStore.deleteEditableHubNewsById(id);
    }

    public int deleteAllNews() {
        if (messageStore == null) {
            return 0;
        }
        return messageStore.deleteAllEditableHubNews();
    }

    public void shutdown() {
        pendingTextInput.clear();
        pendingSaves.clear();
        activeEditSessions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        TextInputMode mode = pendingTextInput.remove(uuid);
        if (mode == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage() == null ? "" : event.getMessage();
        Runnable action = () -> handleTextInput(uuid, message, mode);
        if (event.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, action);
            return;
        }
        action.run();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        cancelForDisconnect(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        cancelForDisconnect(event.getPlayer());
    }

    private void beginEditing(Player player, BossBarMessage news, EditOrigin origin, int managePage) {
        if (player == null || news == null) {
            return;
        }
        String messageId = safeText(news.getId());
        if (messageId.isEmpty()) {
            return;
        }
        pendingTextInput.remove(player.getUniqueId());
        EditSession session = sessionFrom(news);
        session.origin = origin == null ? EditOrigin.ADD : origin;
        session.managePage = Math.max(1, managePage);
        activeEditSessions.put(player.getUniqueId(), session);
        new EditNewsMenu(this).open(player);
    }

    private void handleTextInput(UUID uuid, String rawMessage, TextInputMode mode) {
        if (uuid == null || mode == null) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            clearEditingState(uuid);
            return;
        }
        if (messageStore == null) {
            clearEditingState(uuid);
            player.sendMessage(UNAVAILABLE_MESSAGE);
            return;
        }
        String typedText = rawMessage == null ? "" : rawMessage;
        if (typedText.trim().isEmpty()) {
            pendingTextInput.put(uuid, mode);
            player.sendMessage(EMPTY_TEXT_MESSAGE);
            player.sendMessage(ENTER_TEXT_MESSAGE);
            return;
        }
        if (mode == TextInputMode.EDIT_MESSAGE) {
            EditSession session = session(uuid);
            if (session == null) {
                player.sendMessage(SAVE_FAILED_MESSAGE);
                return;
            }
            session.text = typedText;
            new EditNewsMenu(this).open(player);
            return;
        }
        EditSession draft = new EditSession();
        draft.messageId = "";
        draft.text = typedText;
        draft.messageType = MessageType.FLASH;
        draft.startColor = DEFAULT_NEW_COLOR;
        draft.sweepColor = DEFAULT_NEW_COLOR;
        draft.endColor = DEFAULT_NEW_COLOR;
        draft.origin = EditOrigin.ADD;
        draft.managePage = 1;
        activeEditSessions.put(uuid, draft);
        new EditNewsMenu(this).open(player);
    }

    private void saveSession(UUID uuid, EditSession session) {
        boolean saved = false;
        Exception failure = null;
        try {
            String messageId = safeText(session.messageId);
            if (messageId.isEmpty()) {
                BossBarMessage created = messageStore.addHubNewsItem(session.text);
                if (created != null) {
                    messageId = safeText(created.getId());
                }
            }
            saved = !messageId.isEmpty() && messageStore.saveHubNewsProperties(
                    messageId,
                    session.text,
                    session.messageType.name(),
                    session.startColor.name(),
                    session.sweepColor.name(),
                    session.endColor.name()
            );
        } catch (Exception ex) {
            failure = ex;
        }
        final boolean saveResult = saved;
        final Exception thrown = failure;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingSaves.remove(uuid);
            if (thrown != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to save hub news edit settings.", thrown);
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            if (!saveResult) {
                plugin.getLogger().warning("Hub news properties were not updated for id " + session.messageId + ".");
            }
            player.sendMessage(DONE_MESSAGE);
        });
    }

    private void cancelForDisconnect(Player player) {
        if (player == null) {
            return;
        }
        clearEditingState(player.getUniqueId());
    }

    private EditSession session(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return activeEditSessions.get(uuid);
    }

    private EditSession sessionFrom(BossBarMessage message) {
        EditSession session = new EditSession();
        session.messageId = safeText(message.getId());
        session.text = safeText(message.getText());
        session.messageType = MessageType.from(message.getAnimationType());
        session.startColor = parseColor(message.getStartColor());
        session.sweepColor = resolveMiddleColor(message, session.messageType);
        session.endColor = parseColor(message.getEndColor());
        return session;
    }

    private ChatColor resolveMiddleColor(BossBarMessage message, MessageType type) {
        if (message == null) {
            return ChatColor.WHITE;
        }
        if (type == MessageType.FLASH) {
            String flashColor = safeText(message.getFirstColor());
            if (!flashColor.isEmpty()) {
                return parseColor(flashColor);
            }
        }
        return parseColor(message.getAnimationColor());
    }

    private ChatColor parseColor(String value) {
        String normalized = safeText(value).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return ChatColor.WHITE;
        }
        try {
            ChatColor parsed = ChatColor.valueOf(normalized);
            if (parsed != null && parsed.isColor()) {
                return parsed;
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid configured color, use default white.
        }
        return ChatColor.WHITE;
    }

    private ChatColor cycleColor(ChatColor current, boolean backwards) {
        if (current == null) {
            return COLOR_CYCLE.get(0);
        }
        int index = COLOR_CYCLE.indexOf(current);
        if (index < 0) {
            return COLOR_CYCLE.get(0);
        }
        int offset = backwards ? -1 : 1;
        int next = (index + offset + COLOR_CYCLE.size()) % COLOR_CYCLE.size();
        return COLOR_CYCLE.get(next);
    }

    private boolean clearEditingState(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        boolean removedPrompt = pendingTextInput.remove(uuid) != null;
        boolean removedSave = pendingSaves.remove(uuid);
        boolean removedEdit = activeEditSessions.remove(uuid) != null;
        return removedPrompt || removedSave || removedEdit;
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    public enum MessageType {
        FLASH,
        SWEEP;

        public static MessageType from(String raw) {
            if (raw == null) {
                return FLASH;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if ("SWEEP".equals(normalized)) {
                return SWEEP;
            }
            return FLASH;
        }
    }

    public enum ColorSlot {
        START,
        SWEEP,
        END
    }

    public static final class EditSessionSnapshot {
        private final String messageId;
        private final String text;
        private final MessageType messageType;
        private final ChatColor startColor;
        private final ChatColor sweepColor;
        private final ChatColor endColor;

        private EditSessionSnapshot(String messageId,
                                    String text,
                                    MessageType messageType,
                                    ChatColor startColor,
                                    ChatColor sweepColor,
                                    ChatColor endColor) {
            this.messageId = messageId;
            this.text = text;
            this.messageType = messageType;
            this.startColor = startColor;
            this.sweepColor = sweepColor;
            this.endColor = endColor;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getText() {
            return text;
        }

        public MessageType getMessageType() {
            return messageType;
        }

        public ChatColor getStartColor() {
            return startColor;
        }

        public ChatColor getSweepColor() {
            return sweepColor;
        }

        public ChatColor getEndColor() {
            return endColor;
        }
    }

    private static final class EditSession {
        private String messageId;
        private String text;
        private MessageType messageType;
        private ChatColor startColor;
        private ChatColor sweepColor;
        private ChatColor endColor;
        private EditOrigin origin;
        private int managePage;
    }

    private enum EditOrigin {
        ADD,
        MANAGE
    }

    private enum TextInputMode {
        ADD_NEWS,
        EDIT_MESSAGE
    }
}
