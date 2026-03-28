package io.github.mebsic.hub.service;

import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.core.util.ScoreboardTitleAnimator;
import io.github.mebsic.core.util.ServerNameFormatUtil;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class HubScoreboardService {
    private static final String OBJECTIVE_NAME = "hub";
    private static final String TEAM_PREFIX = "hub_line_";
    private static final int MAX_LINES = 15;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");

    private final CoreApi coreApi;
    private final ServerType serverType;
    private final String serverName;
    private final NumberFormat numberFormat;
    private final Map<UUID, Board> boards;

    public HubScoreboardService(CoreApi coreApi, ServerType serverType, String serverName) {
        this.coreApi = coreApi;
        this.serverType = serverType == null ? ServerType.UNKNOWN : serverType;
        this.serverName = ServerNameFormatUtil.toScoreboardCode(serverName, this.serverType);
        this.numberFormat = NumberFormat.getIntegerInstance(Locale.US);
        this.boards = new HashMap<>();
    }

    public void remove(Player player) {
        if (player == null) {
            return;
        }
        boards.remove(player.getUniqueId());
        ScoreboardManager manager = player.getServer().getScoreboardManager();
        if (manager != null) {
            player.setScoreboard(manager.getNewScoreboard());
        }
    }

    public void update(Player player) {
        if (player == null) {
            return;
        }
        List<String> lines = buildLines(player);
        update(player, lines);
    }

    public void updateAll(Iterable<? extends Player> players) {
        for (Player player : players) {
            update(player);
        }
    }

    public void restartTitleAnimation(Player player) {
        if (player == null) {
            return;
        }
        Board board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        board.titleAnimator.restart();
        String title = board.titleAnimator.resolve(serverType.isHub());
        applyTitle(board, title);
        assignBoardIfNeeded(player, board);
    }

    public void updateAnimatedTitle() {
        for (UUID uuid : new ArrayList<>(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            Board board = boards.get(uuid);
            if (board == null) {
                continue;
            }
            String title = board.titleAnimator.resolve(serverType.isHub());
            applyTitle(board, title);
            assignBoardIfNeeded(player, board);
        }
    }

    private List<String> buildLines(Player player) {
        Profile profile = coreApi == null ? null : coreApi.getProfile(player.getUniqueId());
        int wins = profile == null ? 0 : profile.getStats().getWins();
        int kills = profile == null ? 0 : profile.getStats().getKills();
        int detectiveWins = profile == null ? 0 : MurderMysteryStats.getWinsAsDetective(profile.getStats());
        int murdererWins = profile == null ? 0 : MurderMysteryStats.getWinsAsMurderer(profile.getStats());
        int tokens = profile == null ? 0 : MurderMysteryStats.getTokens(profile.getStats());

        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GRAY + LocalDate.now().format(DATE_FORMATTER)
                + "  " + ChatColor.DARK_GRAY + serverName.toUpperCase(Locale.ROOT));
        lines.add("");
        lines.add(ChatColor.WHITE + "Total Kills: " + ChatColor.GREEN + formatNumber(kills));
        lines.add(ChatColor.WHITE + "Total Wins: " + ChatColor.GREEN + formatNumber(wins));
        lines.add("");
        lines.add(ChatColor.WHITE + "Wins as Detective: " + ChatColor.GREEN + formatNumber(detectiveWins));
        lines.add(ChatColor.WHITE + "Wins as Murderer: " + ChatColor.GREEN + formatNumber(murdererWins));
        lines.add("");
        lines.add(ChatColor.WHITE + "Tokens: " + ChatColor.DARK_GREEN + formatNumber(tokens));
        lines.add("");
        lines.add(ChatColor.YELLOW + NetworkConstants.WEBSITE);
        return lines;
    }

    private String formatNumber(int value) {
        return numberFormat.format(Math.max(0, value));
    }

    private void update(Player player, List<String> lines) {
        ScoreboardManager manager = player.getServer().getScoreboardManager();
        if (manager == null) {
            return;
        }
        Board board = boards.computeIfAbsent(player.getUniqueId(), id -> createBoard(manager));
        String title = board.titleAnimator.resolve(serverType.isHub());
        applyTitle(board, title);

        List<String> safe = new ArrayList<>(lines);
        if (safe.size() > MAX_LINES) {
            safe = safe.subList(0, MAX_LINES);
        }
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = board.entries.get(i);
            if (i >= safe.size()) {
                board.scoreboard.resetScores(entry);
                continue;
            }
            String line = safe.get(i);
            if (!(i < board.lines.size() && line.equals(board.lines.get(i)))) {
                setLine(board, i, line);
            }
            int score = safe.size() - i;
            if (board.objective.getScore(entry).getScore() != score) {
                board.objective.getScore(entry).setScore(score);
            }
        }
        board.lines = new ArrayList<>(safe);
        assignBoardIfNeeded(player, board);
    }

    private Board createBoard(ScoreboardManager manager) {
        ScoreboardTitleAnimator titleAnimator = new ScoreboardTitleAnimator(serverType.getGameTypeDisplayName());
        String title = titleAnimator.resolve(serverType.isHub());
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, "dummy");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setDisplayName(title);
        List<String> entries = buildEntries();
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entries.get(i);
            Team team = scoreboard.registerNewTeam(TEAM_PREFIX + i);
            team.addEntry(entry);
        }
        return new Board(scoreboard, objective, title, entries, titleAnimator);
    }

    private List<String> buildEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < MAX_LINES; i++) {
            ChatColor color = ChatColor.values()[i];
            entries.add(color.toString());
        }
        return entries;
    }

    private void setLine(Board board, int index, String text) {
        if (text == null) {
            text = "";
        }
        String entry = board.entries.get(index);
        Team team = board.scoreboard.getTeam(TEAM_PREFIX + index);
        if (team == null) {
            return;
        }
        String[] parts = split(text);
        team.setPrefix(parts[0]);
        team.setSuffix(parts[1]);
        if (!team.hasEntry(entry)) {
            team.addEntry(entry);
        }
    }

    private String[] split(String text) {
        if (text.length() <= 16) {
            return new String[] {text, ""};
        }
        String prefix = text.substring(0, 16);
        int splitIndex = 16;
        if (prefix.charAt(prefix.length() - 1) == ChatColor.COLOR_CHAR) {
            prefix = prefix.substring(0, prefix.length() - 1);
            splitIndex = 15;
        }
        String suffix = text.substring(splitIndex);
        if (suffix.length() > 16) {
            suffix = suffix.substring(0, 16);
        }
        String colors = ChatColor.getLastColors(prefix);
        if (!colors.isEmpty() && !suffix.startsWith(colors)) {
            suffix = colors + suffix;
            if (suffix.length() > 16) {
                suffix = suffix.substring(0, 16);
            }
        }
        return new String[] {prefix, suffix};
    }

    private void applyTitle(Board board, String title) {
        if (board == null) {
            return;
        }
        String safe = title == null ? "" : title;
        if (safe.equals(board.title)) {
            return;
        }
        board.objective.setDisplayName(safe);
        board.title = safe;
    }

    private void assignBoardIfNeeded(Player player, Board board) {
        if (player == null || board == null) {
            return;
        }
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    private static class Board {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<String> entries;
        private final ScoreboardTitleAnimator titleAnimator;
        private String title;
        private List<String> lines;

        private Board(Scoreboard scoreboard, Objective objective, String title, List<String> entries, ScoreboardTitleAnimator titleAnimator) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.title = title;
            this.entries = entries;
            this.titleAnimator = titleAnimator;
            this.lines = new ArrayList<>();
        }
    }
}
