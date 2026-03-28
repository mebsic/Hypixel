package io.github.mebsic.game.service;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ScoreboardService {
    public static final ZoneOffset SCOREBOARD_DATE_ZONE = ZoneOffset.ofHours(-5); // EST
    public static final DateTimeFormatter SCOREBOARD_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final String OBJECTIVE_NAME = "game";
    private static final String TEAM_PREFIX = "game_line_";
    private static final int MAX_LINES = 15;

    private final TablistService tablistService;
    private final Map<UUID, Board> boards;

    public ScoreboardService(TablistService tablistService) {
        this.tablistService = tablistService;
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

    public void update(Player player, String title, List<String> lines) {
        if (player == null) {
            return;
        }
        ScoreboardManager manager = player.getServer().getScoreboardManager();
        if (manager == null) {
            return;
        }
        Board board = boards.computeIfAbsent(player.getUniqueId(), id -> createBoard(manager, title));
        applyTitle(board, title);

        List<String> safe = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
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
        if (tablistService != null) {
            tablistService.updateFor(player);
        }
    }

    public void updateTitle(Player player, String title) {
        if (player == null) {
            return;
        }
        Board board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        applyTitle(board, title);
        assignBoardIfNeeded(player, board);
    }

    private Board createBoard(ScoreboardManager manager, String title) {
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, "dummy");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setDisplayName(title == null ? "" : title);
        List<String> entries = buildEntries();
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entries.get(i);
            Team team = scoreboard.registerNewTeam(TEAM_PREFIX + i);
            team.addEntry(entry);
        }
        return new Board(scoreboard, objective, title == null ? "" : title, entries);
    }

    private List<String> buildEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < MAX_LINES; i++) {
            entries.add(ChatColor.values()[i].toString());
        }
        return entries;
    }

    private void setLine(Board board, int index, String text) {
        String safe = text == null ? "" : text;
        Team team = board.scoreboard.getTeam(TEAM_PREFIX + index);
        if (team == null) {
            return;
        }
        String[] split = splitLine(safe);
        team.setPrefix(split[0]);
        team.setSuffix(split[1]);
    }

    private String[] splitLine(String text) {
        if (text.length() <= 16) {
            return new String[]{text, ""};
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
        String trailingColors = ChatColor.getLastColors(prefix);
        if (!trailingColors.isEmpty() && !suffix.startsWith(trailingColors)) {
            suffix = trailingColors + suffix;
            if (suffix.length() > 16) {
                suffix = suffix.substring(0, 16);
            }
        }
        return new String[]{prefix, suffix};
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
        private String title;
        private List<String> lines;

        private Board(Scoreboard scoreboard, Objective objective, String title, List<String> entries) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.entries = entries;
            this.title = title;
            this.lines = new ArrayList<>();
        }
    }
}
