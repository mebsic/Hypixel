package io.github.mebsic.core.listener;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.RankFormatUtil;
import io.github.mebsic.game.model.GameState;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFormatListener implements Listener {
    private static final Sound MENTION_DING_SOUND = Sound.NOTE_PLING;
    private static final int CASE_INSENSITIVE_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern GOOD_GAME_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])good\\s*game(?![A-Za-z0-9_])", CASE_INSENSITIVE_FLAGS);
    private static final Pattern GG_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])g\\s*g(?![A-Za-z0-9_])", CASE_INSENSITIVE_FLAGS);

    private final JavaPlugin plugin;
    private final CoreApi coreApi;
    private final CorePlugin corePlugin;

    public ChatFormatListener(JavaPlugin plugin, CoreApi coreApi) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.coreApi = Objects.requireNonNull(coreApi, "coreApi");
        this.corePlugin = plugin instanceof CorePlugin ? (CorePlugin) plugin : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String rawMessage = event.getMessage() == null ? "" : event.getMessage();
        Set<Player> recipients = new HashSet<>(event.getRecipients());
        UUID uuid = player.getUniqueId();
        Rank rank = coreApi.getRank(uuid);
        ChatRenderStyle style;
        if (rank == null || rank == Rank.DEFAULT) {
            style = new ChatRenderStyle("", ChatColor.GRAY, ChatColor.GRAY, ChatColor.GRAY);
        } else {
            int networkLevel = coreApi.getNetworkLevel(uuid);
            Profile profile = coreApi.getProfile(uuid);
            String plusColor = profile == null ? null : profile.getPlusColor();
            String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
            ChatColor nameColor = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
            String prefix = RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
            style = new ChatRenderStyle(prefix, nameColor, ChatColor.WHITE, ChatColor.WHITE);
        }
        boolean highlightGoodGame = shouldHighlightGoodGame(rank);
        event.setCancelled(true);

        Runnable dispatch = () -> deliverChat(player, rawMessage, recipients, style, highlightGoodGame);
        if (event.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, dispatch);
        } else {
            dispatch.run();
        }
    }

    private void deliverChat(Player sender,
                             String message,
                             Set<Player> recipients,
                             ChatRenderStyle style,
                             boolean highlightGoodGame) {
        if (sender == null) {
            return;
        }
        String baseMessage = highlightGoodGame ? highlightGgPhrases(message, style.messageColor) : message;
        String header = style.prefix + style.nameColor + sender.getName() + style.separatorColor + ": " + style.messageColor;
        String normalLine = header + baseMessage;
        UUID senderId = sender.getUniqueId();
        boolean senderSent = false;
        for (Player recipient : recipients) {
            if (recipient == null || !recipient.isOnline()) {
                continue;
            }
            UUID recipientId = recipient.getUniqueId();
            if (recipientId.equals(senderId)) {
                recipient.sendMessage(normalLine);
                senderSent = true;
                continue;
            }
            if (corePlugin != null && corePlugin.isChatBlocked(senderId, recipientId)) {
                continue;
            }
            String highlighted = highlightExactIgnMentions(baseMessage, recipient.getName(), style.messageColor);
            recipient.sendMessage(header + highlighted);
            if (!baseMessage.equals(highlighted)) {
                recipient.playSound(recipient.getLocation(), MENTION_DING_SOUND, 1.0f, 1.0f);
            }
        }
        if (!senderSent && sender.isOnline()) {
            sender.sendMessage(normalLine);
        }
        Bukkit.getConsoleSender().sendMessage(ChatColor.stripColor(normalLine));
    }

    private String highlightExactIgnMentions(String message, String ign, ChatColor restoreColor) {
        if (message == null || message.isEmpty() || ign == null || ign.isEmpty()) {
            return message == null ? "" : message;
        }
        StringBuilder highlighted = null;
        int copyFrom = 0;
        int searchFrom = 0;
        while (searchFrom <= message.length() - ign.length()) {
            int index = message.indexOf(ign, searchFrom);
            if (index < 0) {
                break;
            }
            int end = index + ign.length();
            if (isNameBoundary(message, index - 1) && isNameBoundary(message, end)) {
                if (highlighted == null) {
                    highlighted = new StringBuilder(message.length() + 16);
                }
                highlighted.append(message, copyFrom, index)
                        .append(ChatColor.YELLOW)
                        .append(ign)
                        .append(restoreColor);
                copyFrom = end;
                searchFrom = end;
            } else {
                searchFrom = index + 1;
            }
        }
        if (highlighted == null) {
            return message;
        }
        highlighted.append(message, copyFrom, message.length());
        return highlighted.toString();
    }

    private boolean shouldHighlightGoodGame(Rank rank) {
        if (rank == null || !rank.isAtLeast(Rank.MVP_PLUS_PLUS)) {
            return false;
        }
        if (corePlugin == null) {
            return false;
        }
        ServerType type = corePlugin.getServerType();
        if (type == null || !type.isGame()) {
            return false;
        }
        return corePlugin.getCurrentGameState() == GameState.ENDING;
    }

    private String highlightGgPhrases(String message, ChatColor restoreColor) {
        String highlighted = applyPatternHighlight(message, GOOD_GAME_PATTERN, restoreColor);
        return applyPatternHighlight(highlighted, GG_PATTERN, restoreColor);
    }

    private String applyPatternHighlight(String message, Pattern pattern, ChatColor restoreColor) {
        if (message == null || message.isEmpty() || pattern == null) {
            return message == null ? "" : message;
        }
        Matcher matcher = pattern.matcher(message);
        if (!matcher.find()) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length() + 16);
        int cursor = 0;
        do {
            int start = matcher.start();
            int end = matcher.end();
            out.append(message, cursor, start)
                    .append(ChatColor.GOLD)
                    .append(message, start, end)
                    .append(restoreColor);
            cursor = end;
        } while (matcher.find());
        out.append(message, cursor, message.length());
        return out.toString();
    }

    private boolean isNameBoundary(String value, int index) {
        if (value == null || index < 0 || index >= value.length()) {
            return true;
        }
        char c = value.charAt(index);
        return !(Character.isLetterOrDigit(c) || c == '_');
    }

    private static final class ChatRenderStyle {
        private final String prefix;
        private final ChatColor nameColor;
        private final ChatColor separatorColor;
        private final ChatColor messageColor;

        private ChatRenderStyle(String prefix, ChatColor nameColor, ChatColor separatorColor, ChatColor messageColor) {
            this.prefix = prefix == null ? "" : prefix;
            this.nameColor = nameColor == null ? ChatColor.WHITE : nameColor;
            this.separatorColor = separatorColor == null ? ChatColor.WHITE : separatorColor;
            this.messageColor = messageColor == null ? ChatColor.WHITE : messageColor;
        }
    }
}
