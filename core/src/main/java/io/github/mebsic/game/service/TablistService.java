package io.github.mebsic.game.service;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.core.util.RankFormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class TablistService {
    private static final String LEGACY_NAME_TAG_TEAM = "game_nametags";
    private static final String OLD_NAME_TAG_TEAM_PREFIX = "gnt_";
    private static final String NAME_TAG_TEAM_PREFIX = "gt";
    private static final int SORT_NAME_PART_LENGTH = 8;
    private static final int SORT_ID_PART_LENGTH = 4;
    private static final int MAX_TEAM_TEXT_LENGTH = 16;
    private static final String TAB_HEADER = ChatColor.AQUA + "You are playing on "
            + ChatColor.YELLOW + ChatColor.BOLD + "MC." + NetworkConstants.DOMAIN.toUpperCase(Locale.ROOT);
    private static final String TAB_FOOTER = ChatColor.GREEN + "Ranks, Boosters & MORE! "
            + ChatColor.RED + ChatColor.BOLD + "STORE." + NetworkConstants.DOMAIN.toUpperCase(Locale.ROOT);

    private final CoreApi coreApi;
    private boolean nameTagsHidden;
    private boolean showRankPrefix;
    private ChatColor forcedNameColor;
    private VisibilityPolicy visibilityPolicy;
    private boolean enforceVisibilityWithHidePlayer;

    @FunctionalInterface
    public interface VisibilityPolicy {
        boolean isVisible(Player viewer, Player target);
    }

    public TablistService(CoreApi coreApi, ServerType serverType) {
        this.coreApi = coreApi;
        this.nameTagsHidden = false;
        this.showRankPrefix = serverType != null && serverType.isHub();
        this.forcedNameColor = null;
        this.visibilityPolicy = null;
        this.enforceVisibilityWithHidePlayer = false;
    }

    public void updateAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            updateFor(viewer);
        }
    }

    public void updateFor(Player viewer) {
        if (viewer == null) {
            return;
        }
        applyNameTagRule(viewer);
        applyHeaderFooter(viewer);
        for (Player target : Bukkit.getOnlinePlayers()) {
            updatePlayer(target);
        }
    }

    public void updatePlayer(Player player) {
        if (player == null) {
            return;
        }
        Rank rank = coreApi == null ? Rank.DEFAULT : coreApi.getRank(player.getUniqueId());
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        String displayName = buildTabName(player, rank);
        try {
            player.setPlayerListName(displayName);
        } catch (IllegalArgumentException ex) {
            player.setPlayerListName(resolveNameColorCode(player.getUniqueId(), rank) + player.getName());
        }
    }

    public void setNameTagsHidden(boolean hidden) {
        if (this.nameTagsHidden == hidden) {
            return;
        }
        this.nameTagsHidden = hidden;
        updateAll();
    }

    public void setShowRankPrefix(boolean showRankPrefix) {
        if (this.showRankPrefix == showRankPrefix) {
            return;
        }
        this.showRankPrefix = showRankPrefix;
        updateAll();
    }

    public void setForcedNameColor(ChatColor forcedNameColor) {
        if (this.forcedNameColor == forcedNameColor) {
            return;
        }
        this.forcedNameColor = forcedNameColor;
        updateAll();
    }

    public void setVisibilityPolicy(VisibilityPolicy visibilityPolicy) {
        if (this.visibilityPolicy == visibilityPolicy) {
            return;
        }
        this.visibilityPolicy = visibilityPolicy;
        updateAll();
    }

    public void setEnforceVisibilityWithHidePlayer(boolean enforceVisibilityWithHidePlayer) {
        if (this.enforceVisibilityWithHidePlayer == enforceVisibilityWithHidePlayer) {
            return;
        }
        this.enforceVisibilityWithHidePlayer = enforceVisibilityWithHidePlayer;
        updateAll();
    }

    private String buildTabName(Player player, Rank rank) {
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        UUID uuid = player.getUniqueId();
        String nameColorCode = resolveNameColorCode(uuid, rank);
        if (!showRankPrefix || rank == Rank.DEFAULT) {
            return nameColorCode + player.getName();
        }
        String name = resolveRankPrefix(uuid, rank)
                + nameColorCode
                + player.getName();
        if (stripLength(name) > 16) {
            return nameColorCode + player.getName();
        }
        return name;
    }

    private int stripLength(String value) {
        return ChatColor.stripColor(value) == null ? 0 : ChatColor.stripColor(value).length();
    }

    private void applyNameTagRule(Player viewer) {
        Scoreboard scoreboard = viewer.getScoreboard();
        if (scoreboard == null) {
            return;
        }

        Team legacy = scoreboard.getTeam(LEGACY_NAME_TAG_TEAM);
        if (legacy != null) {
            legacy.unregister();
        }

        Set<String> visibleNames = new HashSet<>();
        for (Player target : Bukkit.getOnlinePlayers()) {
            boolean visible = isVisibleToViewer(viewer, target);
            applyViewerVisibility(viewer, target, visible);
            if (!visible) {
                removePlayerFromNameTagTeams(scoreboard, target.getName());
                continue;
            }
            visibleNames.add(target.getName());
            UUID targetId = target.getUniqueId();
            Rank rank = resolveRank(targetId);
            String teamName = nameTagTeamName(targetId, rank, target.getName());
            Team team = scoreboard.getTeam(teamName);
            if (team == null) {
                team = scoreboard.registerNewTeam(teamName);
            }
            team.setNameTagVisibility(nameTagsHidden ? NameTagVisibility.NEVER : NameTagVisibility.ALWAYS);
            team.setPrefix(buildNameTagPrefix(targetId, rank));
            if (team.getSuffix() != null && !team.getSuffix().isEmpty()) {
                team.setSuffix("");
            }
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(target.getName());
            for (Team existing : new HashSet<>(scoreboard.getTeams())) {
                if (existing == null || !isNameTagTeam(existing.getName())) {
                    continue;
                }
                if (existing.getName().equals(teamName)) {
                    continue;
                }
                if (existing.hasPlayer(offlinePlayer)) {
                    existing.removePlayer(offlinePlayer);
                }
            }
            if (!team.hasPlayer(offlinePlayer)) {
                team.addPlayer(offlinePlayer);
            }
        }

        Set<Team> currentTeams = new HashSet<>(scoreboard.getTeams());
        for (Team team : currentTeams) {
            if (team == null || !isNameTagTeam(team.getName())) {
                continue;
            }
            team.setNameTagVisibility(nameTagsHidden ? NameTagVisibility.NEVER : NameTagVisibility.ALWAYS);
            Set<OfflinePlayer> members = new HashSet<>(team.getPlayers());
            for (OfflinePlayer member : members) {
                if (member == null || member.getName() == null) {
                    continue;
                }
                if (!visibleNames.contains(member.getName())) {
                    team.removePlayer(member);
                }
            }
            if (team.getPlayers().isEmpty()) {
                team.unregister();
            }
        }
    }

    private Rank resolveRank(UUID uuid) {
        Rank rank = coreApi == null ? Rank.DEFAULT : coreApi.getRank(uuid);
        if (rank == null) {
            return Rank.DEFAULT;
        }
        return rank;
    }

    private String buildNameTagPrefix(UUID uuid, Rank rank) {
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        String colorCode = resolveNameColorCode(uuid, rank);
        String prefix;
        if (!showRankPrefix || rank == Rank.DEFAULT) {
            prefix = colorCode;
        } else {
            prefix = resolveRankPrefix(uuid, rank) + colorCode;
        }
        return trimTeamText(prefix);
    }

    private String resolveRankPrefix(UUID uuid, Rank rank) {
        if (rank == null || rank == Rank.DEFAULT) {
            return "";
        }
        if (coreApi == null) {
            return rank.getPrefix();
        }
        Profile profile = coreApi.getProfile(uuid);
        int networkLevel = coreApi.getNetworkLevel(uuid);
        String plusColor = profile == null ? null : profile.getPlusColor();
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        return RankFormatUtil.buildPrefix(rank, networkLevel, plusColor, mvpPlusPlusPrefixColor);
    }

    private String resolveNameColorCode(UUID uuid, Rank rank) {
        ChatColor forced = forcedNameColor;
        if (forced != null) {
            return forced.toString();
        }
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        if (coreApi == null) {
            return rank.getColor().toString();
        }
        Profile profile = coreApi.getProfile(uuid);
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        return RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor).toString();
    }

    private boolean isVisibleToViewer(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            return true;
        }
        if (visibilityPolicy == null) {
            return true;
        }
        try {
            return visibilityPolicy.isVisible(viewer, target);
        } catch (Exception ignored) {
            return true;
        }
    }

    private void applyViewerVisibility(Player viewer, Player target, boolean visible) {
        if (!enforceVisibilityWithHidePlayer || viewer == null || target == null) {
            return;
        }
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        if (visible) {
            viewer.showPlayer(target);
        } else {
            viewer.hidePlayer(target);
        }
    }

    private void removePlayerFromNameTagTeams(Scoreboard scoreboard, String playerName) {
        if (scoreboard == null || playerName == null || playerName.isEmpty()) {
            return;
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        for (Team team : new HashSet<>(scoreboard.getTeams())) {
            if (team == null || !isNameTagTeam(team.getName())) {
                continue;
            }
            if (team.hasPlayer(offlinePlayer)) {
                team.removePlayer(offlinePlayer);
            }
            if (team.getPlayers().isEmpty()) {
                team.unregister();
            }
        }
    }

    private String trimTeamText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= MAX_TEAM_TEXT_LENGTH) {
            return value;
        }
        String trimmed = value.substring(0, MAX_TEAM_TEXT_LENGTH);
        if (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == ChatColor.COLOR_CHAR) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String nameTagTeamName(UUID uuid, Rank rank, String playerName) {
        String compactUuid = uuid.toString().replace("-", "");
        int idStart = Math.max(0, compactUuid.length() - SORT_ID_PART_LENGTH);
        String idPart = compactUuid.substring(idStart);
        String namePart = sanitizeNamePart(playerName);
        return NAME_TAG_TEAM_PREFIX
                + twoDigitPriority(rankPriority(rank))
                + namePart
                + idPart;
    }

    private String sanitizeNamePart(String playerName) {
        String safe = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(SORT_NAME_PART_LENGTH);
        for (int i = 0; i < safe.length() && normalized.length() < SORT_NAME_PART_LENGTH; i++) {
            char c = safe.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                normalized.append(c);
                continue;
            }
            normalized.append('x');
        }
        while (normalized.length() < SORT_NAME_PART_LENGTH) {
            normalized.append('_');
        }
        return normalized.toString();
    }

    private String twoDigitPriority(int priority) {
        int safe = Math.max(0, Math.min(99, priority));
        if (safe < 10) {
            return "0" + safe;
        }
        return String.valueOf(safe);
    }

    private int rankPriority(Rank rank) {
        if (rank == null) {
            return 99;
        }
        switch (rank) {
            case STAFF:
                return 0;
            case YOUTUBE:
                return 1;
            case MVP_PLUS_PLUS:
                return 2;
            case MVP_PLUS:
                return 3;
            case MVP:
                return 4;
            case VIP_PLUS:
                return 5;
            case VIP:
                return 6;
            case DEFAULT:
            default:
                return 7;
        }
    }

    private boolean isNameTagTeam(String teamName) {
        return teamName != null
                && (teamName.startsWith(NAME_TAG_TEAM_PREFIX) || teamName.startsWith(OLD_NAME_TAG_TEAM_PREFIX));
    }

    private void applyHeaderFooter(Player viewer) {
        if (viewer == null) {
            return;
        }
        if (tryDirectHeaderFooter(viewer, TAB_HEADER, TAB_FOOTER)) {
            return;
        }
        tryLegacyHeaderFooter(viewer, TAB_HEADER, TAB_FOOTER);
    }

    private boolean tryDirectHeaderFooter(Player viewer, String header, String footer) {
        try {
            viewer.getClass()
                    .getMethod("setPlayerListHeaderFooter", String.class, String.class)
                    .invoke(viewer, header, footer);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void tryLegacyHeaderFooter(Player viewer, String header, String footer) {
        try {
            String version = viewer.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object handle = craftPlayer.getMethod("getHandle").invoke(viewer);
            Object connection = handle.getClass().getField("playerConnection").get(handle);

            Class<?> ichat = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent");
            Class<?> serializer = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent$ChatSerializer");
            Object headerComponent = serializer.getMethod("a", String.class).invoke(null, jsonText(header));
            Object footerComponent = serializer.getMethod("a", String.class).invoke(null, jsonText(footer));

            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutPlayerListHeaderFooter");
            Object packet = packetClass.getConstructor(ichat).newInstance(headerComponent);
            java.lang.reflect.Field footerField = packetClass.getDeclaredField("b");
            footerField.setAccessible(true);
            footerField.set(packet, footerComponent);

            Class<?> packetBase = Class.forName("net.minecraft.server." + version + ".Packet");
            connection.getClass().getMethod("sendPacket", packetBase).invoke(connection, packet);
        } catch (Exception ignored) {
            // Best effort only.
        }
    }

    private String jsonText(String text) {
        String safe = text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"text\":\"" + safe + "\"}";
    }
}
