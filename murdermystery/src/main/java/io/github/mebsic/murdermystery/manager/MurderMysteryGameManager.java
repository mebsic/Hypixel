package io.github.mebsic.murdermystery.manager;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.GameResult;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.CoreApi;
import io.github.mebsic.core.store.RoleChanceStore;
import io.github.mebsic.core.util.GameRewardUtil;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.core.util.RankFormatUtil;
import io.github.mebsic.core.util.ServerNameFormatUtil;
import io.github.mebsic.game.manager.GameManager;
import io.github.mebsic.game.map.GameMap;
import io.github.mebsic.game.model.GamePlayer;
import io.github.mebsic.game.model.GameState;
import io.github.mebsic.game.model.RoleChance;
import io.github.mebsic.game.service.BossBarService;
import io.github.mebsic.game.service.ScoreboardService;
import io.github.mebsic.murdermystery.game.MurderMysteryGamePlayer;
import io.github.mebsic.murdermystery.game.MurderMysteryGameResult;
import io.github.mebsic.murdermystery.game.MurderMysteryRole;
import io.github.mebsic.murdermystery.registry.KnifeSkinRegistry;
import io.github.mebsic.murdermystery.service.ActionBarService;
import io.github.mebsic.murdermystery.stats.MurderMysteryStats;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.Door;
import org.bukkit.material.MaterialData;
import org.bukkit.material.Openable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MurderMysteryGameManager extends GameManager {
    public enum KillType {
        UNKNOWN,
        BOW,
        KNIFE,
        THROWN_KNIFE
    }

    private static final double DEFAULT_MURDERER_CHANCE = 1.0;
    private static final double DEFAULT_DETECTIVE_CHANCE = 1.0;
    private static final double MIN_ROLE_CHANCE = 0.2;
    private static final double MAX_ROLE_CHANCE = 5.0;
    private static final double MURDERER_INCREASE = 0.15;
    private static final double MURDERER_DECREASE = 1.0;
    private static final double DETECTIVE_INCREASE = 0.15;
    private static final double DETECTIVE_DECREASE = 1.0;
    private static final int INNOCENTS_WIN_WARNING_SECONDS = 60;
    private static final int MURDERER_SWORD_COUNTDOWN_START_REMAINING_SECONDS = 260; // 4:20
    private static final int MURDERER_SWORD_UNLOCK_REMAINING_SECONDS = 255; // 4:15
    private static final int TITLE_FADE_IN_TICKS = 0;
    private static final int TITLE_STAY_TICKS = 60; // 3 seconds
    private static final int ROLE_TITLE_STAY_TICKS = 100; // 5 seconds
    private static final int OUTCOME_TITLE_STAY_TICKS = 200; // 10 seconds
    private static final long SHOT_ARROW_DESPAWN_TICKS = 100L; // 5 seconds
    private static final int TITLE_FADE_OUT_FAST_TICKS = 7;
    private static final int TITLE_FADE_OUT_INSTANT_TICKS = 0;
    private static final String MURDERER_TEAMING_WARNING =
            ChatColor.RED.toString() + ChatColor.BOLD + "Teaming with the Detective/Innocents is not allowed!";
    private static final String DETECTIVE_TEAMING_WARNING =
            ChatColor.RED.toString() + ChatColor.BOLD + "Teaming with the Murderer is not allowed!";
    private static final String INNOCENT_TEAMING_WARNING =
            ChatColor.RED.toString() + ChatColor.BOLD + "Teaming with the Murderer is not allowed!";
    private static final int DEFAULT_GOLD_PICKUP_TOKEN_REWARD = 10;
    private static final int DEFAULT_SURVIVE_30_SECONDS_TOKEN_REWARD = 50;
    private static final int DEFAULT_MURDERER_KILL_TOKEN_REWARD = 100;
    private static final int HOTBAR_SLOT_ONE_INDEX = 0; // Slot 1 in the player's hotbar.
    private static final int KNIFE_HOTBAR_SLOT = 1; // Slot 2 in the player's hotbar.
    private static final int BOW_HOTBAR_SLOT = 1;
    private static final int ARROW_HOTBAR_SLOT = 2;
    private static final int MURDERER_BOW_HOTBAR_SLOT = 2; // Slot 3 in the player's hotbar.
    private static final int MURDERER_ARROW_HOTBAR_SLOT = 3; // Slot 4 in the player's hotbar.
    private static final int GOLD_HOTBAR_SLOT = 8; // Last hotbar slot.
    private static final int MAP_GOLD_PICKUP_DELAY_TICKS = 10;
    private static final int GOLD_FOR_BOW = 10;
    private static final double DROPPED_BOW_PICKUP_RADIUS_SQUARED = 2.25D;
    private static final float DROPPED_BOW_ROTATION_DEGREES_PER_TICK = 8.0f;
    private static final double DROPPED_BOW_HEIGHT_OFFSET = 0.35D;
    private static final long DROPPED_BOW_DEATH_DELAY_TICKS = 2L;
    private static final int POST_GAME_RESULT_TEXT_WIDTH = 75;
    private static final int POST_GAME_REWARD_SUMMARY_TEXT_WIDTH = 80;
    private static final Sound INNOCENT_ROLE_IDLE_SOUND = resolveCompatibleSound("VILLAGER_IDLE", "ENTITY_VILLAGER_AMBIENT");
    private static final Sound INNOCENT_ROLE_ACCEPT_SOUND = resolveCompatibleSound("VILLAGER_YES", "ENTITY_VILLAGER_YES");
    private static final Sound DETECTIVE_ROLE_SOUND = resolveCompatibleSound("LEVEL_UP", "ENTITY_PLAYER_LEVELUP", "ORB_PICKUP", "ENTITY_EXPERIENCE_ORB_PICKUP");
    private static final Sound MODERN_MURDERER_ROLE_SOUND =
            resolveCompatibleSound("ENTITY_ELDER_GUARDIAN_CURSE", "ELDER_GUARDIAN_CURSE");
    private static final String[] MURDERER_ROLE_SOUND_KEYS = {
            "mob.guardian.curse",
            "entity.elder_guardian.curse",
            "entity.guardian.curse"
    };
    private static final Sound MURDERER_ROLE_FALLBACK_SOUND =
            resolveCompatibleSound("WITHER_SPAWN", "ENTITY_WITHER_SPAWN");
    private static final Sound WIN_GAME_ORB_PICKUP_SOUND =
            resolveCompatibleSound("ORB_PICKUP", "ENTITY_EXPERIENCE_ORB_PICKUP");
    private static final Sound WIN_GAME_SOUND = resolveCompatibleSound("LEVEL_UP", "ENTITY_PLAYER_LEVELUP");
    private static final Sound MURDER_KILL_DAMAGE_SOUND = resolveMurderKillDamageSound();
    private static final String TOKEN_REASON_SURVIVED_30_SECONDS = "Survived 30 seconds";
    private static final String TOKEN_REASON_PICKED_UP_GOLD = "Picked up gold";
    private static final String SPECTATOR_CHAT_HINT_LINE_ONE =
            ChatColor.YELLOW + "As a Spectator, you can talk in chat with fellow Spectators.";
    private static final String SPECTATOR_CHAT_HINT_LINE_TWO =
            ChatColor.GOLD + "Alive players cannot see dead players' chat.";
    private static final String DETECTIVE_LEFT_BOW_MESSAGE =
            ChatColor.GOLD + "The Detective has left! "
                    + ChatColor.YELLOW + "Find the Bow for a chance to kill the Murderer.";
    private static final String BOW_DROPPED_CHAT_MESSAGE =
            ChatColor.GOLD + "The Bow has been dropped! "
                    + ChatColor.YELLOW + "Find it for a chance to kill the Murderer.";

    private boolean innocentsWon;
    private int elapsedGameSeconds;
    private boolean murdererSwordUnlocked;
    private boolean outcomeTitlesShown;
    private BukkitTask goldTask;
    private ArmorStand droppedBowDisplay;
    private BukkitTask droppedBowTask;
    private float droppedBowYaw;
    private final Map<Item, Integer> activeMapDropItems = new HashMap<>();
    private final Set<Arrow> activeRoundArrows = new HashSet<>();
    private final Set<OpenableBlockRef> trackedOpenables = new HashSet<>();
    private UUID originalDetectiveUuid;
    private boolean originalDetectiveEliminated;
    private UUID summaryMurdererUuid;
    private boolean summaryMurdererEliminated;
    private ActionBarService actionBarService;

    public MurderMysteryGameManager(CorePlugin plugin, BossBarService bossBarService) {
        super(plugin, bossBarService);
        this.innocentsWon = false;
        this.elapsedGameSeconds = 0;
    }

    public void setActionBarService(ActionBarService actionBarService) {
        this.actionBarService = actionBarService;
    }

    @Override
    protected GamePlayer createGamePlayer(UUID uuid) {
        return new MurderMysteryGamePlayer(uuid);
    }

    @Override
    protected void resetGamePlayer(GamePlayer gamePlayer) {
        MurderMysteryGamePlayer mmPlayer = asMmPlayer(gamePlayer);
        if (mmPlayer != null) {
            mmPlayer.resetForRound();
            return;
        }
        super.resetGamePlayer(gamePlayer);
    }

    public MurderMysteryGamePlayer getMurderMysteryPlayer(Player player) {
        return asMmPlayer(getPlayer(player));
    }

    @Override
    protected void onGameStarted(GameMap activeMap) {
        innocentsWon = false;
        elapsedGameSeconds = 0;
        murdererSwordUnlocked = false;
        outcomeTitlesShown = false;
        closeTrackedOpenables();
        clearActiveRoundArrows();
        clearActiveMapDropItems();
        clearDroppedBowDisplay();
        getTablistService().setNameTagsHidden(true);
        getTablistService().setForcedNameColor(ChatColor.WHITE);
        assignRoles();
        originalDetectiveUuid = findDetectiveUuid();
        originalDetectiveEliminated = false;
        summaryMurdererUuid = findMurdererUuid();
        summaryMurdererEliminated = false;
        giveLoadouts();
        refreshMurdererLastInnocentSpeed();
        startGoldSpawner();
    }

    @Override
    protected boolean onGameTimerTick(int remainingSeconds) {
        handleMurdererSwordTimeline(remainingSeconds);
        if (remainingSeconds == INNOCENTS_WIN_WARNING_SECONDS) {
            broadcast(ChatColor.YELLOW + "Innocents win in "
                    + ChatColor.RED + INNOCENTS_WIN_WARNING_SECONDS
                    + ChatColor.YELLOW + " seconds!");
            for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
                Player player = Bukkit.getPlayer(mmPlayer.getUuid());
                if (player == null || !player.isOnline()) {
                    continue;
                }
                player.playSound(player.getLocation(), Sound.CLICK, 1.0f, 1.0f);
            }
        }
        if (remainingSeconds <= 0) {
            innocentsWon = true;
            endGame();
            return true;
        }
        return false;
    }

    @Override
    protected void onGameSecondElapsed() {
        elapsedGameSeconds++;
        if (elapsedGameSeconds <= 0 || elapsedGameSeconds % 30 != 0) {
            return;
        }
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (!mmPlayer.isAlive()) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            awardTokens(player, getSurvive30SecondsTokenReward(), TOKEN_REASON_SURVIVED_30_SECONDS);
        }
    }

    @Override
    protected void onAlivePlayerQuitInGame(Player player, GamePlayer gamePlayer) {
        MurderMysteryGamePlayer mmPlayer = asMmPlayer(gamePlayer);
        if (mmPlayer == null) {
            return;
        }
        if (mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
            summaryMurdererUuid = mmPlayer.getUuid();
            summaryMurdererEliminated = true;
            assignReplacementMurderer(player.getUniqueId());
            return;
        }
        if (mmPlayer.getRole() == MurderMysteryRole.DETECTIVE || mmPlayer.getRole() == MurderMysteryRole.HERO) {
            if (originalDetectiveUuid != null && originalDetectiveUuid.equals(mmPlayer.getUuid())) {
                originalDetectiveEliminated = true;
            }
            if (mmPlayer.hasDetectiveBow() && player.getInventory().contains(Material.BOW)) {
                dropBowAt(player.getLocation());
                if (mmPlayer.getRole() == MurderMysteryRole.DETECTIVE
                        && originalDetectiveUuid != null
                        && originalDetectiveUuid.equals(mmPlayer.getUuid())) {
                    broadcast(DETECTIVE_LEFT_BOW_MESSAGE);
                } else {
                    broadcast(BOW_DROPPED_CHAT_MESSAGE);
                    broadcastDetectiveStatusSubtitle(ChatColor.GOLD + "The Bow has been dropped!");
                }
            }
            mmPlayer.setHasDetectiveBow(false);
        }
        if (!hasAliveNonMurdererExcluding(player.getUniqueId())) {
            innocentsWon = false;
            endGame();
            return;
        }
        refreshMurdererLastInnocentSpeed();
    }

    @Override
    protected void onGameEnding() {
        clearMurdererLastInnocentSpeed();
        if (goldTask != null) {
            goldTask.cancel();
            goldTask = null;
        }
        closeTrackedOpenables();
        clearActiveRoundArrows();
        clearActiveMapDropItems();
        clearDroppedBowDisplay();
        forceOutcomeTitlesOnGameEnd();
        murdererSwordUnlocked = false;
        outcomeTitlesShown = true;
        getTablistService().setNameTagsHidden(true);
        getTablistService().setForcedNameColor(ChatColor.WHITE);
        playWinSoundToWinners();
        if (innocentsWon) {
            announceHeroWinners();
        }
        int roundDurationSeconds = Math.max(0, elapsedGameSeconds);
        publishResults(roundDurationSeconds);
        elapsedGameSeconds = 0;
    }

    @Override
    protected void restartServerAndRequeuePlayers() {
        cleanupTransientRoundEntitiesForShutdown();
        super.restartServerAndRequeuePlayers();
    }

    @Override
    protected void appendInGameScoreboardLines(Player player, GamePlayer gp, List<String> lines) {
        lines.clear();
        MurderMysteryGamePlayer mmPlayer = asMmPlayer(gp);
        MurderMysteryRole role = mmPlayer == null ? MurderMysteryRole.INNOCENT : mmPlayer.getRole();
        int innocents = getAliveCount(MurderMysteryRole.INNOCENT) + getAliveCount(MurderMysteryRole.HERO) + getAliveCount(MurderMysteryRole.DETECTIVE);
        boolean detectiveAlive = hasAliveDetective();
        boolean heroAlive = hasAliveHero();

        lines.add(buildDateAndServerLine());
        lines.add("");
        if (mmPlayer != null && !mmPlayer.isAlive()) {
            lines.add(ChatColor.WHITE + "Role: " + ChatColor.GRAY + "Dead");
        } else {
            lines.add(ChatColor.WHITE + "Role: " + formatScoreboardRoleColor(role) + formatScoreboardRoleName(role));
        }
        lines.add(" ");
        lines.add(ChatColor.WHITE + "Innocents Left: " + ChatColor.GREEN + innocents);
        lines.add(ChatColor.WHITE + "Time Left: " + ChatColor.GREEN + formatTime(getGameRemaining()));
        lines.add("  ");
        if (role == MurderMysteryRole.MURDERER) {
            if (detectiveAlive) {
                lines.add(ChatColor.WHITE + "Detective: " + ChatColor.GREEN + "Alive");
            } else if (heroAlive) {
                lines.add(ChatColor.WHITE + "Bow: " + ChatColor.GREEN + "Not Dropped");
            } else {
                lines.add(ChatColor.WHITE + "Bow: " + ChatColor.RED + "Dropped");
            }
            lines.add("   ");
            lines.add(ChatColor.WHITE + "Kills: " + ChatColor.GREEN + (mmPlayer == null ? 0 : mmPlayer.getKills()));
            lines.add("    ");
        } else {
            if (detectiveAlive) {
                lines.add(ChatColor.WHITE + "Detective: " + ChatColor.GREEN + "Alive");
            } else if (heroAlive) {
                lines.add(ChatColor.WHITE + "Bow: " + ChatColor.GREEN + "Not Dropped");
            } else {
                lines.add(ChatColor.WHITE + "Bow: " + ChatColor.RED + "Dropped");
            }
            lines.add("   ");
        }
        lines.add(ChatColor.WHITE + "Map: " + ChatColor.GREEN + getScoreboardMapName());
        lines.add("     ");
        lines.add(ChatColor.YELLOW + NetworkConstants.website());
    }

    @Override
    protected void appendPregameScoreboardLines(Player player, GamePlayer gp, List<String> lines) {
        lines.clear();
        lines.add(buildDateAndServerLine());
        lines.add("");
        lines.add(ChatColor.WHITE + "Map: " + ChatColor.GREEN + getScoreboardMapName());
        lines.add(ChatColor.WHITE + "Players: " + ChatColor.GREEN + getPlayerCount() + "/" + getMaxPlayers());
        lines.add(" ");
        if (getState() == GameState.STARTING) {
            int countdown = Math.max(0, getCountdownRemaining());
            lines.add(ChatColor.WHITE + "Starting in " + ChatColor.GREEN + countdown + "s");
        } else {
            lines.add(ChatColor.WHITE + "Waiting...");
        }
        lines.add("  ");
        lines.add(ChatColor.WHITE + "Mode: " + ChatColor.GREEN + "Classic");
        lines.add("   ");
        lines.add(ChatColor.YELLOW + NetworkConstants.website());
    }

    private String buildDateAndServerLine() {
        CorePlugin plugin = getPlugin();
        ServerType type = plugin == null ? ServerType.UNKNOWN : plugin.getServerType();
        String serverName = ServerNameFormatUtil.toScoreboardCode(
                plugin == null ? "mini1A" : plugin.getConfig().getString("server.id", "mini1A"),
                type
        );
        return ChatColor.GRAY + LocalDate.now(ScoreboardService.SCOREBOARD_DATE_ZONE).format(ScoreboardService.SCOREBOARD_DATE_FORMAT)
                + "  " + ChatColor.DARK_GRAY + serverName;
    }

    @Override
    protected String getPostGameHeadline(Player player, GamePlayer gamePlayer) {
        ServerType type = getPlugin() == null ? ServerType.UNKNOWN : getPlugin().getServerType();
        String gameType = type == null ? "GAME" : type.getGameTypeDisplayName();
        String normalized = gameType == null ? "GAME" : gameType.trim();
        if (normalized.isEmpty()) {
            normalized = "GAME";
        }
        return centerPostGameLine(
                ChatColor.WHITE.toString() + ChatColor.BOLD + normalized.toUpperCase(Locale.ROOT),
                POST_GAME_RESULT_TEXT_WIDTH);
    }

    @Override
    protected String getPostGameOutcomeLine(Player player, GamePlayer gamePlayer) {
        String winner = innocentsWon
                ? ChatColor.GREEN + "PLAYERS"
                : ChatColor.RED + "MURDERER";
        String winnerPrefix = innocentsWon ? "Winner: " : " Winner: ";
        return centerPostGameLine(
                ChatColor.WHITE.toString() + ChatColor.BOLD + winnerPrefix + winner,
                POST_GAME_RESULT_TEXT_WIDTH);
    }

    @Override
    protected GameResult createPostGameResult(Player player, GamePlayer gamePlayer) {
        MurderMysteryGamePlayer mmPlayer = asMmPlayer(gamePlayer);
        if (mmPlayer == null) {
            return super.createPostGameResult(player, gamePlayer);
        }
        return createMurderMysteryGameResult(mmPlayer);
    }

    @Override
    protected void appendPostGameSummaryLines(Player player, GamePlayer gamePlayer, List<String> lines) {
        MurderMysteryGamePlayer detective = findPlayerByUuid(originalDetectiveUuid);
        if (detective == null && originalDetectiveUuid == null) {
            detective = findPlayerByRole(MurderMysteryRole.DETECTIVE);
        }
        MurderMysteryGamePlayer murderer = findPlayerByRole(MurderMysteryRole.MURDERER);
        MurderMysteryGamePlayer hero = findHeroSummaryPlayer();

        if (lines.size() >= 2) {
            lines.add(lines.size() - 1, "");
        }
        lines.add("");
        UUID detectiveUuid = originalDetectiveUuid == null && detective != null
                ? detective.getUuid()
                : originalDetectiveUuid;
        boolean detectiveEliminated = detective != null && !detective.isAlive();
        if (originalDetectiveEliminated
                && originalDetectiveUuid != null
                && originalDetectiveUuid.equals(detectiveUuid)) {
            detectiveEliminated = true;
        }
        String detectiveName = formatParticipantName(detectiveUuid, detectiveEliminated, true);
        lines.add(centerPostGameLine(ChatColor.GRAY + "Detective: " + detectiveName, POST_GAME_RESULT_TEXT_WIDTH));
        int murdererKills = getMurdererKillCount(murderer);
        UUID murdererUuid = murderer == null ? summaryMurdererUuid : murderer.getUuid();
        boolean murdererEliminated = murderer != null && !murderer.isAlive();
        if (summaryMurdererEliminated
                && summaryMurdererUuid != null
                && summaryMurdererUuid.equals(murdererUuid)) {
            murdererEliminated = true;
        }
        String murdererName = formatParticipantName(murdererUuid, murdererEliminated, true);
        lines.add(centerPostGameLine(ChatColor.GRAY + "Murderer: " + murdererName
                + ChatColor.GRAY + " (" + ChatColor.GOLD + murdererKills + ChatColor.GRAY + " "
                + murdererKillLabel(murdererKills) + ")", POST_GAME_RESULT_TEXT_WIDTH));
        if (hero != null) {
            lines.add(centerPostGameLine(ChatColor.GRAY + "Hero: " + formatParticipantName(hero, false), POST_GAME_RESULT_TEXT_WIDTH));
        }
    }

    @Override
    protected void appendPostGameRewardSummary(Player player, GamePlayer gamePlayer, List<String> lines) {
        GameResult result = createPostGameResult(player, gamePlayer);
        if (result == null || gamePlayer == null) {
            return;
        }
        GameRewardUtil.ExperienceBreakdown breakdown = GameRewardUtil.calculateExperienceBreakdown(result);
        int tokensEarned = getRoundRewardTotal(gamePlayer.getUuid());
        long experienceEarned = breakdown.getTotalExperience();

        lines.add("");
        lines.add(getPostGameFrameLine());
        lines.add("");
        lines.add(getPostGameFrameLine());
        lines.add(centerPostGameLine(
                ChatColor.WHITE.toString() + ChatColor.BOLD + "Reward Summary",
                POST_GAME_REWARD_SUMMARY_TEXT_WIDTH));
        lines.add("");
        lines.add(ChatColor.GRAY.toString() + ChatColor.BOLD + "   You earned");
        lines.add(ChatColor.WHITE.toString() + ChatColor.BOLD + "     • "
                + ChatColor.DARK_GREEN + ChatColor.BOLD + formatAmount(tokensEarned) + " Murder Mystery "
                + (tokensEarned == 1 ? "Token" : "Tokens"));
        lines.add(ChatColor.WHITE.toString() + ChatColor.BOLD + "     • "
                + ChatColor.DARK_AQUA + ChatColor.BOLD + formatAmount(experienceEarned) + " Hypixel Experience");
        lines.add("");
    }

    @Override
    public void checkWinConditions() {
        if (getState() != GameState.IN_GAME) {
            return;
        }
        refreshMurdererLastInnocentSpeed();
        int aliveCount = 0;
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.isAlive()) {
                aliveCount++;
            }
        }
        boolean murdererAlive = false;
        boolean othersAlive = false;
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (!mmPlayer.isAlive()) {
                continue;
            }
            if (mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
                murdererAlive = true;
            } else {
                othersAlive = true;
            }
        }
        if (!outcomeTitlesShown && aliveCount <= 1) {
            showWinTitleToAlivePlayers();
            outcomeTitlesShown = true;
        }
        if (!murdererAlive) {
            innocentsWon = true;
            endGame();
        } else if (!othersAlive) {
            innocentsWon = false;
            endGame();
        }
    }

    public void handleDeath(Player victim, Player killer) {
        handleDeath(victim, killer, null, KillType.UNKNOWN);
    }

    public void handleDeath(Player victim, Player killer, String customMessage) {
        handleDeath(victim, killer, customMessage, KillType.UNKNOWN);
    }

    public void handleDeath(Player victim, Player killer, String customMessage, KillType killType) {
        MurderMysteryGamePlayer victimData = getMurderMysteryPlayer(victim);
        if (victimData == null || !victimData.isAlive()) {
            return;
        }
        boolean victimWasMurderer = victimData.getRole() == MurderMysteryRole.MURDERER;
        int aliveBeforeDeath = getAlivePlayers();
        MurderMysteryGamePlayer killerData = killer == null ? null : getMurderMysteryPlayer(killer);
        if (victimWasMurderer && killer != null) {
            if (killerData != null) {
                killerData.setKilledMurderer(true);
                if (killerData.getRole() == MurderMysteryRole.HERO) {
                    killerData.addKillAsHero();
                }
            }
        }
        boolean murdererKilledNonMurderer = killer != null
                && killerData != null
                && killerData.getRole() == MurderMysteryRole.MURDERER
                && !victimWasMurderer;
        if (killer != null
                && killerData != null
                && murdererKilledNonMurderer) {
            killerData.addKillAsMurderer();
            if (killType == KillType.KNIFE) {
                killerData.addKnifeKill();
            } else if (killType == KillType.THROWN_KNIFE) {
                killerData.addThrownKnifeKill();
            } else if (killType == KillType.BOW) {
                killerData.addBowKill();
            }
            awardMurdererKillTokens(killer);
            playMurdererKillDamageSoundToAllPlayers();
        } else if (killerData != null
                && killType == KillType.BOW
                && (killerData.getRole() == MurderMysteryRole.DETECTIVE || killerData.getRole() == MurderMysteryRole.HERO)
                && victimWasMurderer) {
            killerData.addBowKill();
        }
        victimData.setAlive(false);
        if (victimWasMurderer
                && killerData != null
                && killerData.getRole() == MurderMysteryRole.DETECTIVE) {
            int elapsedToKill = elapsedSecondsSince(killerData.getDetectiveWeaponGrantedAtMillis());
            killerData.recordDetectiveWinningKillSeconds(elapsedToKill);
        }
        if (murdererKilledNonMurderer
                && killerData != null
                && !hasAliveNonMurdererExcluding(null)) {
            int elapsedToWinningKill = elapsedSecondsSince(killerData.getMurdererWeaponGrantedAtMillis());
            killerData.recordMurdererWinningKillSeconds(elapsedToWinningKill);
        }
        if (victimData.getRole() == MurderMysteryRole.DETECTIVE
                && originalDetectiveUuid != null
                && originalDetectiveUuid.equals(victimData.getUuid())) {
            originalDetectiveEliminated = true;
        }
        if (victimWasMurderer) {
            summaryMurdererUuid = victimData.getUuid();
            summaryMurdererEliminated = true;
        }
        boolean droppedBow = victimData.hasDetectiveBow() && victim.getInventory().contains(Material.BOW);
        if (droppedBow) {
            final Location bowDropLocation = victim.getLocation() == null ? null : victim.getLocation().clone();
            getPlugin().getServer().getScheduler().runTaskLater(
                    getPlugin(),
                    () -> {
                        if (getState() != GameState.IN_GAME) {
                            return;
                        }
                        dropBowAt(bowDropLocation);
                    },
                    DROPPED_BOW_DEATH_DELAY_TICKS
            );
        }
        victimData.setHasDetectiveBow(false);
        victim.getInventory().setArmorContents(null);
        victim.getInventory().clear();
        applyDeadSpectatorState(victim);
        double maxHealth = victim.getMaxHealth();
        if (maxHealth > 0.0D && victim.getHealth() < maxHealth) {
            victim.setHealth(maxHealth);
        }
        victim.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false), true);
        sendDeathChatMessage(victim, victimData, killerData, killer, customMessage);
        sendSpectatorChatHint(victim);
        boolean endingElimination = aliveBeforeDeath <= 2;
        if (endingElimination) {
            showLoseTitle(victim);
            showWinTitleToAlivePlayers();
            outcomeTitlesShown = true;
        }
        if (droppedBow) {
            broadcast(BOW_DROPPED_CHAT_MESSAGE);
        }
        if (!endingElimination) {
            if (victimData.getRole() == MurderMysteryRole.DETECTIVE) {
                broadcastDetectiveStatusSubtitle(ChatColor.GOLD + "The Detective has been killed!");
            } else if (droppedBow) {
                broadcastDetectiveStatusSubtitle(ChatColor.GOLD + "The Bow has been dropped!");
            }
        }
        refreshMurdererLastInnocentSpeed();
        checkWinConditions();
        updateScoreboardAll();
    }

    private void sendDeathChatMessage(Player victim,
                                      MurderMysteryGamePlayer victimData,
                                      MurderMysteryGamePlayer killerData,
                                      Player killer,
                                      String customMessage) {
        if (customMessage != null && !customMessage.trim().isEmpty()) {
            victim.sendMessage(ChatColor.RED + "YOU DIED! " + ChatColor.YELLOW + customMessage);
            return;
        }
        String message;
        if (killerData != null) {
            if (killerData.getRole() == MurderMysteryRole.MURDERER && victimData.getRole() != MurderMysteryRole.MURDERER) {
                message = "The Murderer stabbed you!";
            } else if ((killerData.getRole() == MurderMysteryRole.DETECTIVE || killerData.getRole() == MurderMysteryRole.HERO)
                    && victimData.getRole() == MurderMysteryRole.MURDERER) {
                message = "The Detective shot you!";
            } else {
                message = killer.getName() + " eliminated you!";
            }
        } else {
            message = "You were eliminated!";
        }
        victim.sendMessage(ChatColor.RED + "YOU DIED! " + ChatColor.YELLOW + message);
    }

    private void sendSpectatorChatHint(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(SPECTATOR_CHAT_HINT_LINE_ONE);
        player.sendMessage(SPECTATOR_CHAT_HINT_LINE_TWO);
    }

    private void showRoleTitle(Player player, MurderMysteryRole role) {
        if (player == null || role == null) {
            return;
        }
        String title;
        String subtitle;
        if (role == MurderMysteryRole.MURDERER) {
            title = ChatColor.RED + "ROLE: MURDERER";
            subtitle = ChatColor.YELLOW + "Kill all players!";
        } else if (role == MurderMysteryRole.DETECTIVE) {
            title = ChatColor.AQUA + "ROLE: DETECTIVE";
            subtitle = ChatColor.YELLOW + "Find and kill the Murderer!";
        } else {
            title = ChatColor.GREEN + "ROLE: INNOCENT";
            subtitle = ChatColor.YELLOW + "Stay alive as long as possible!";
        }
        getTitleService().send(
                player,
                title,
                subtitle,
                TITLE_FADE_IN_TICKS,
                ROLE_TITLE_STAY_TICKS,
                TITLE_FADE_OUT_FAST_TICKS
        );
        playRoleAssignmentSound(player, role);
    }

    private void playRoleAssignmentSound(Player player, MurderMysteryRole role) {
        if (player == null || role == null) {
            return;
        }
        if (role == MurderMysteryRole.MURDERER) {
            playMurdererRoleAssignmentSound(player);
            return;
        }
        Sound sound = null;
        if (role == MurderMysteryRole.DETECTIVE || role == MurderMysteryRole.HERO) {
            sound = DETECTIVE_ROLE_SOUND;
        } else if (role == MurderMysteryRole.INNOCENT) {
            sound = chooseInnocentRoleSound();
        }
        playRoleSoundNow(player, sound);
    }

    private Sound chooseInnocentRoleSound() {
        if (INNOCENT_ROLE_IDLE_SOUND == null) {
            return INNOCENT_ROLE_ACCEPT_SOUND;
        }
        if (INNOCENT_ROLE_ACCEPT_SOUND == null) {
            return INNOCENT_ROLE_IDLE_SOUND;
        }
        return Math.random() < 0.5D ? INNOCENT_ROLE_IDLE_SOUND : INNOCENT_ROLE_ACCEPT_SOUND;
    }

    private void playMurdererRoleAssignmentSound(Player player) {
        if (playRoleSoundNow(player, MODERN_MURDERER_ROLE_SOUND)) {
            return;
        }
        for (String soundKey : MURDERER_ROLE_SOUND_KEYS) {
            if (playRoleStringSoundNow(player, soundKey)) {
                return;
            }
        }
        playRoleSoundNow(player, MURDERER_ROLE_FALLBACK_SOUND);
    }

    private boolean playRoleSoundNow(Player player, Sound sound) {
        if (player == null || sound == null) {
            return false;
        }
        try {
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
            return true;
        } catch (IllegalArgumentException ignored) {
            // Fallback sounds are handled by caller.
            return false;
        }
    }

    private boolean playRoleStringSoundNow(Player player, String soundKey) {
        if (player == null || soundKey == null || soundKey.trim().isEmpty()) {
            return false;
        }
        final String roleSoundKey = soundKey.trim();
        try {
            player.playSound(player.getLocation(), roleSoundKey, 1.0f, 1.0f);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private void showLoseTitle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        getTitleService().reset(player);
        getTitleService().send(
                player,
                ChatColor.RED + "YOU LOSE!",
                ChatColor.GOLD + "You died!",
                TITLE_FADE_IN_TICKS,
                OUTCOME_TITLE_STAY_TICKS,
                TITLE_FADE_OUT_FAST_TICKS
        );
    }

    private void showWinTitleToAlivePlayers() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (!mmPlayer.isAlive()) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            String subtitle = mmPlayer.getRole() == MurderMysteryRole.MURDERER
                    ? ChatColor.GOLD + "Every survivor died!"
                    : ChatColor.GOLD + "The Murderer has been stopped!";
            getTitleService().reset(player);
            getTitleService().send(
                    player,
                    ChatColor.GREEN + "YOU WIN!",
                    subtitle,
                    TITLE_FADE_IN_TICKS,
                    OUTCOME_TITLE_STAY_TICKS,
                    TITLE_FADE_OUT_FAST_TICKS
            );
        }
    }

    private void forceOutcomeTitlesOnGameEnd() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            boolean won = didRoleWin(mmPlayer.getRole());
            getTitleService().reset(player);
            if (won) {
                String subtitle = mmPlayer.getRole() == MurderMysteryRole.MURDERER
                        ? ChatColor.GOLD + "Every survivor died!"
                        : ChatColor.GOLD + "The Murderer has been stopped!";
                getTitleService().send(
                        player,
                        ChatColor.GREEN + "YOU WIN!",
                        subtitle,
                        TITLE_FADE_IN_TICKS,
                        OUTCOME_TITLE_STAY_TICKS,
                        TITLE_FADE_OUT_FAST_TICKS
                );
                continue;
            }
            String subtitle = mmPlayer.getRole() == MurderMysteryRole.MURDERER
                    ? ChatColor.GOLD + "The Innocents survived!"
                    : ChatColor.GOLD + "Every survivor died!";
            getTitleService().send(
                    player,
                    ChatColor.RED + "YOU LOSE!",
                    subtitle,
                    TITLE_FADE_IN_TICKS,
                    OUTCOME_TITLE_STAY_TICKS,
                    TITLE_FADE_OUT_FAST_TICKS
            );
        }
    }

    private void broadcastDetectiveStatusSubtitle(String subtitle) {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            getTitleService().send(
                    player,
                    "",
                    subtitle,
                    TITLE_FADE_IN_TICKS,
                    TITLE_STAY_TICKS,
                    TITLE_FADE_OUT_INSTANT_TICKS
            );
        }
    }

    public void addGold(Player player, int amount) {
        MurderMysteryGamePlayer mmPlayer = getMurderMysteryPlayer(player);
        if (mmPlayer == null || !mmPlayer.isAlive()) {
            return;
        }
        awardTokens(player, getGoldPickupTokenReward(), TOKEN_REASON_PICKED_UP_GOLD);
        mmPlayer.addGold(amount);
        if (mmPlayer.getRole() == MurderMysteryRole.INNOCENT && mmPlayer.getGold() >= GOLD_FOR_BOW) {
            grantArrowsFromGold(player, mmPlayer, BOW_HOTBAR_SLOT, ARROW_HOTBAR_SLOT);
        } else if (mmPlayer.getRole() == MurderMysteryRole.MURDERER && mmPlayer.getGold() >= GOLD_FOR_BOW) {
            grantArrowsFromGold(player, mmPlayer, MURDERER_BOW_HOTBAR_SLOT, MURDERER_ARROW_HOTBAR_SLOT);
        }
        syncGoldHotbarItem(player, mmPlayer);
        updateScoreboard(player);
    }

    private int grantArrowsFromGold(Player player,
                                    MurderMysteryGamePlayer mmPlayer,
                                    int bowSlot,
                                    int arrowSlot) {
        if (player == null || mmPlayer == null) {
            return 0;
        }
        if (!player.getInventory().contains(Material.BOW)) {
            player.getInventory().setItem(bowSlot, createGameBowItem(player));
        }
        int arrowsAdded = 0;
        while (mmPlayer.getGold() >= GOLD_FOR_BOW) {
            if (!addSingleArrowToInventory(player, arrowSlot)) {
                break;
            }
            mmPlayer.removeGold(GOLD_FOR_BOW);
            arrowsAdded++;
            showBowShotSubtitle(player);
        }
        return arrowsAdded;
    }

    private boolean addSingleArrowToInventory(Player player, int preferredArrowSlot) {
        if (player == null) {
            return false;
        }
        ItemStack preferred = player.getInventory().getItem(preferredArrowSlot);
        if (preferred == null || preferred.getType() == Material.AIR) {
            player.getInventory().setItem(preferredArrowSlot, new ItemStack(Material.ARROW, 1));
            return true;
        }
        if (preferred.getType() == Material.ARROW && preferred.getAmount() < 64) {
            preferred.setAmount(preferred.getAmount() + 1);
            player.getInventory().setItem(preferredArrowSlot, preferred);
            return true;
        }
        int existingArrowSlot = player.getInventory().first(Material.ARROW);
        if (existingArrowSlot >= 0) {
            ItemStack existing = player.getInventory().getItem(existingArrowSlot);
            if (existing != null && existing.getType() == Material.ARROW && existing.getAmount() < 64) {
                existing.setAmount(existing.getAmount() + 1);
                player.getInventory().setItem(existingArrowSlot, existing);
                return true;
            }
        }
        int emptySlot = player.getInventory().firstEmpty();
        if (emptySlot >= 0) {
            player.getInventory().setItem(emptySlot, new ItemStack(Material.ARROW, 1));
            return true;
        }
        return false;
    }

    private void showBowShotSubtitle(Player player) {
        if (player == null) {
            return;
        }
        getTitleService().send(
                player,
                "",
                ChatColor.GREEN + "+1 Bow Shot!",
                TITLE_FADE_IN_TICKS,
                TITLE_STAY_TICKS,
                TITLE_FADE_OUT_INSTANT_TICKS
        );
    }

    public void untrackMapDropItem(Item item) {
        if (item == null) {
            return;
        }
        activeMapDropItems.remove(item);
    }

    public boolean isTrackedMapDropItem(Item item) {
        return item != null && activeMapDropItems.containsKey(item);
    }

    public void trackOpenableInteraction(Block block) {
        if (block == null || !isTrackedOpenableType(block.getType())) {
            return;
        }
        Block normalized = normalizeTrackedOpenable(block);
        if (normalized == null || normalized.getWorld() == null) {
            return;
        }
        trackedOpenables.add(OpenableBlockRef.from(normalized));
    }

    public void sendMurdererKnifeKillMessage(Player murderer, Player victim, double distanceMeters) {
        if (murderer == null || victim == null || !murderer.isOnline()) {
            return;
        }
        MurderMysteryGamePlayer murdererData = getMurderMysteryPlayer(murderer);
        if (murdererData == null || murdererData.getRole() != MurderMysteryRole.MURDERER) {
            return;
        }
        ChatColor victimNameColor = resolveRankNameColor(victim.getUniqueId());
        String victimName = victim.getName();
        if (victimName == null || victimName.trim().isEmpty()) {
            victimName = resolveParticipantName(victim.getUniqueId());
        }
        String formattedDistance = String.format(Locale.US, "%.2fm", Math.max(0.0D, distanceMeters));
        murderer.sendMessage(
                ChatColor.YELLOW + "Killed "
                        + victimNameColor + victimName
                        + ChatColor.YELLOW + " from "
                        + ChatColor.YELLOW + formattedDistance
                        + ChatColor.YELLOW + " away!"
        );
    }

    public void trackRoundArrow(Arrow arrow) {
        if (arrow == null) {
            return;
        }
        arrow.setBounce(false);
        makeArrowUnpickable(arrow);
        activeRoundArrows.add(arrow);
        getPlugin().getServer().getScheduler().runTaskLater(getPlugin(), () -> despawnRoundArrow(arrow), SHOT_ARROW_DESPAWN_TICKS);
    }

    private void makeArrowUnpickable(Arrow arrow) {
        if (arrow == null) {
            return;
        }
        if (trySetArrowPickupStatusDisallowed(arrow)) {
            return;
        }
        trySetLegacyArrowPickupDisallowed(arrow);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean trySetArrowPickupStatusDisallowed(Arrow arrow) {
        try {
            Class pickupStatusClass = Class.forName("org.bukkit.entity.Arrow$PickupStatus");
            Method setPickupStatus = arrow.getClass().getMethod("setPickupStatus", pickupStatusClass);
            Object disallowed = Enum.valueOf(pickupStatusClass.asSubclass(Enum.class), "DISALLOWED");
            setPickupStatus.invoke(arrow, disallowed);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean trySetLegacyArrowPickupDisallowed(Arrow arrow) {
        try {
            Method getHandle = arrow.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(arrow);
            Field fromPlayer = handle.getClass().getDeclaredField("fromPlayer");
            fromPlayer.setAccessible(true);
            fromPlayer.setInt(handle, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void convertToHero(Player player) {
        convertToHero(player, false);
    }

    public void convertDroppedBowCarrierToHero(Player player) {
        MurderMysteryGamePlayer mmPlayer = getMurderMysteryPlayer(player);
        if (mmPlayer == null || !mmPlayer.isAlive() || mmPlayer.getRole() != MurderMysteryRole.INNOCENT) {
            return;
        }
        convertToHero(player, false);
    }

    public void convertToHero(Player player, boolean fromGold) {
        MurderMysteryGamePlayer mmPlayer = getMurderMysteryPlayer(player);
        if (mmPlayer == null || mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
            return;
        }
        if (mmPlayer.getRole() == MurderMysteryRole.INNOCENT) {
            mmPlayer.setRole(MurderMysteryRole.HERO);
        }
        if (fromGold) {
            mmPlayer.setHeroFromGold(true);
        } else {
            mmPlayer.setHasDetectiveBow(true);
        }
        if (!player.getInventory().contains(Material.BOW)) {
            player.getInventory().setItem(BOW_HOTBAR_SLOT, createGameBowItem(player));
        }
        if (!player.getInventory().contains(Material.ARROW)) {
            player.getInventory().setItem(ARROW_HOTBAR_SLOT, new ItemStack(Material.ARROW, 1));
        }
        syncGoldHotbarItem(player, mmPlayer);
        if (!fromGold) {
            player.getInventory().setHeldItemSlot(BOW_HOTBAR_SLOT);
            player.sendMessage(ChatColor.GREEN + "You picked up the bow! "
                    + ChatColor.GOLD + "GOAL: Find and kill the murderer!");
            broadcast(ChatColor.YELLOW + "A player has picked up the Bow!");
        }
        updateScoreboardAll();
    }

    private void awardTokens(Player player, int amount, String reason) {
        if (!addTokens(player, amount)) {
            return;
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        trackRoundReward(player.getUniqueId(), tokenRewardLabel(normalizedReason), amount);
        if (!normalizedReason.isEmpty()) {
            player.sendMessage(ChatColor.DARK_GREEN + "+" + amount + " tokens! " + normalizedReason);
        }
        if (actionBarService != null) {
            actionBarService.showTokenReward(player, amount);
        }
    }

    private void awardMurdererKillTokens(Player player) {
        int amount = getMurdererKillTokenReward();
        if (!addTokens(player, amount)) {
            return;
        }
        trackRoundReward(player.getUniqueId(), tokenRewardLabel("Killed a player"), amount);
        player.sendMessage(ChatColor.DARK_GREEN + "+" + amount + " tokens! Killed a player");
    }

    private boolean addTokens(Player player, int amount) {
        if (player == null || amount <= 0) {
            return false;
        }
        CoreApi coreApi = getPlugin().getCoreApi();
        if (coreApi == null) {
            return false;
        }
        Profile profile = coreApi.getProfile(player.getUniqueId());
        if (profile == null) {
            return false;
        }
        MurderMysteryStats.addTokens(coreApi, player.getUniqueId(), amount);
        return true;
    }

    private int getGoldPickupTokenReward() {
        return getConfiguredReward("murdermystery.rewards.goldPickupTokens", DEFAULT_GOLD_PICKUP_TOKEN_REWARD);
    }

    private int getSurvive30SecondsTokenReward() {
        return getConfiguredReward("murdermystery.rewards.survive30SecondsTokens", DEFAULT_SURVIVE_30_SECONDS_TOKEN_REWARD);
    }

    private int getMurdererKillTokenReward() {
        return getConfiguredReward("murdermystery.rewards.murdererKillTokens", DEFAULT_MURDERER_KILL_TOKEN_REWARD);
    }

    private int getConfiguredReward(String path, int fallback) {
        int value = getPlugin().getConfig().getInt(path, fallback);
        return Math.max(0, value);
    }

    private void startGoldSpawner() {
        if (goldTask != null) {
            goldTask.cancel();
        }
        goldTask = getPlugin().getServer().getScheduler().runTaskTimer(getPlugin(), () -> {
            if (getState() != GameState.IN_GAME) {
                return;
            }
            GameMap map = getMapManager().getActiveMap();
            if (map == null || map.getDropItemSpawns().isEmpty()) {
                return;
            }
            List<Location> dropItemSpawns = map.getDropItemSpawns();
            List<Integer> availableDropIndexes = new ArrayList<>();
            for (int index = 0; index < dropItemSpawns.size(); index++) {
                Location candidate = dropItemSpawns.get(index);
                if (candidate == null || candidate.getWorld() == null || isMapDropSpawnOccupied(index)) {
                    continue;
                }
                availableDropIndexes.add(index);
            }
            if (availableDropIndexes.isEmpty()) {
                return;
            }
            int dropIndex = availableDropIndexes.get((int) (Math.random() * availableDropIndexes.size()));
            Location location = dropItemSpawns.get(dropIndex);
            if (location == null || location.getWorld() == null) {
                return;
            }
            ItemStack dropTemplate = new ItemStack(Material.GOLD_INGOT, 1);
            List<ItemStack> configuredDrops = map.getDropItemStacks();
            if (configuredDrops != null && dropIndex >= 0 && dropIndex < configuredDrops.size()) {
                ItemStack configured = configuredDrops.get(dropIndex);
                if (configured != null && configured.getType() == Material.GOLD_INGOT) {
                    dropTemplate = configured.clone();
                    dropTemplate.setAmount(1);
                }
            }
            Item dropped = location.getWorld().dropItemNaturally(location, dropTemplate);
            if (dropped != null) {
                dropped.setPickupDelay(MAP_GOLD_PICKUP_DELAY_TICKS);
                activeMapDropItems.put(dropped, dropIndex);
            }
        }, 40L, 60L);
    }

    private boolean isMapDropSpawnOccupied(int dropIndex) {
        return activeMapDropItems.containsValue(dropIndex);
    }

    private void clearActiveMapDropItems() {
        if (activeMapDropItems.isEmpty()) {
            return;
        }
        for (Item item : new ArrayList<>(activeMapDropItems.keySet())) {
            if (item != null && item.isValid() && !item.isDead()) {
                item.remove();
            }
            activeMapDropItems.remove(item);
        }
    }

    private void closeTrackedOpenables() {
        if (trackedOpenables.isEmpty()) {
            return;
        }
        Set<OpenableBlockRef> snapshot = new HashSet<>(trackedOpenables);
        trackedOpenables.clear();
        for (OpenableBlockRef ref : snapshot) {
            Block block = ref.resolve();
            if (block == null) {
                continue;
            }
            forceCloseOpenable(block);
        }
    }

    private void forceCloseOpenable(Block block) {
        Block normalized = normalizeTrackedOpenable(block);
        if (normalized == null || !isTrackedOpenableType(normalized.getType())) {
            return;
        }
        BlockState state = normalized.getState();
        MaterialData data = state.getData();
        if (!(data instanceof Openable)) {
            return;
        }
        Openable openable = (Openable) data;
        if (!openable.isOpen()) {
            return;
        }
        openable.setOpen(false);
        state.setData((MaterialData) openable);
        state.update(true, false);
    }

    private Block normalizeTrackedOpenable(Block block) {
        if (block == null) {
            return null;
        }
        if (!isDoorType(block.getType())) {
            return block;
        }
        BlockState state = block.getState();
        MaterialData data = state.getData();
        if (!(data instanceof Door)) {
            return block;
        }
        Door door = (Door) data;
        if (!door.isTopHalf()) {
            return block;
        }
        Block lower = block.getRelative(BlockFace.DOWN);
        if (lower != null && isDoorType(lower.getType())) {
            return lower;
        }
        return block;
    }

    private boolean isTrackedOpenableType(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        if ("FENCE_GATE".equals(name) || name.endsWith("_FENCE_GATE")) {
            return true;
        }
        if ("TRAP_DOOR".equals(name) || name.endsWith("_TRAPDOOR") || name.endsWith("TRAPDOOR")) {
            return true;
        }
        return isDoorType(material);
    }

    private boolean isDoorType(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return (name.endsWith("_DOOR") || name.endsWith("_DOOR_BLOCK")) && !name.contains("TRAP");
    }

    private void clearActiveRoundArrows() {
        if (activeRoundArrows.isEmpty()) {
            return;
        }
        for (Arrow arrow : new ArrayList<>(activeRoundArrows)) {
            despawnRoundArrow(arrow);
        }
    }

    private void despawnRoundArrow(Arrow arrow) {
        if (arrow == null) {
            return;
        }
        activeRoundArrows.remove(arrow);
        if (arrow.isValid() && !arrow.isDead()) {
            arrow.remove();
        }
    }

    private void giveLoadouts() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null) {
                continue;
            }
            player.getInventory().clear();
            showRoleTitle(player, mmPlayer.getRole());
            sendTeamingWarning(player, mmPlayer.getRole());
            syncGoldHotbarItem(player, mmPlayer);
        }
    }

    private void handleMurdererSwordTimeline(int remainingSeconds) {
        if (murdererSwordUnlocked || getState() != GameState.IN_GAME) {
            return;
        }
        if (remainingSeconds <= MURDERER_SWORD_UNLOCK_REMAINING_SECONDS) {
            murdererSwordUnlocked = true;
            giveMurdererSwordNow();
            giveDetectiveBowNow();
            forceGamePlayersToHotbarSlotOne();
            broadcastMurdererSwordReceivedMessageToOthers();
            updateScoreboardAll();
            return;
        }
        if (remainingSeconds <= MURDERER_SWORD_COUNTDOWN_START_REMAINING_SECONDS) {
            int secondsUntilUnlock = remainingSeconds - MURDERER_SWORD_UNLOCK_REMAINING_SECONDS;
            if (secondsUntilUnlock > 0) {
                broadcastMurdererSwordCountdownMessage(secondsUntilUnlock);
            }
        }
    }

    private void broadcastMurdererSwordCountdownMessage(int seconds) {
        String unit = seconds == 1 ? "second" : "seconds";
        String murdererMessage = ChatColor.YELLOW + "You get your sword in "
                + ChatColor.RED + seconds
                + ChatColor.YELLOW + " " + unit + "!";
        String othersMessage = ChatColor.YELLOW + "The Murderer gets their sword in "
                + ChatColor.RED + seconds
                + ChatColor.YELLOW + " " + unit + "!";
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
                player.sendMessage(murdererMessage);
                player.playSound(player.getLocation(), Sound.CLICK, 1.0f, 1.0f);
            } else {
                player.sendMessage(othersMessage);
            }
        }
    }

    private void broadcastMurdererSwordReceivedMessageToOthers() {
        String othersMessage = ChatColor.YELLOW + "The Murderer has received their sword!";
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.sendMessage(othersMessage);
        }
    }

    private void giveDetectiveBowNow() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() != MurderMysteryRole.DETECTIVE || !mmPlayer.isAlive()) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (!player.getInventory().contains(Material.BOW)) {
                player.getInventory().setItem(BOW_HOTBAR_SLOT, createGameBowItem(player));
            }
            mmPlayer.setHasDetectiveBow(true);
            mmPlayer.markDetectiveWeaponGrantedNow();
            if (!player.getInventory().contains(Material.ARROW)) {
                player.getInventory().setItem(ARROW_HOTBAR_SLOT, new ItemStack(Material.ARROW, 1));
            }
            syncGoldHotbarItem(player, mmPlayer);
        }
    }

    private void giveMurdererSwordNow() {
        CoreApi coreApi = getPlugin().getCoreApi();
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() != MurderMysteryRole.MURDERER || !mmPlayer.isAlive()) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (player.getInventory().contains(Material.IRON_SWORD)) {
                if (mmPlayer.getMurdererWeaponGrantedAtMillis() <= 0L) {
                    mmPlayer.markMurdererWeaponGrantedNow();
                }
                continue;
            }
            ItemStack knife = createMurdererKnife(coreApi, player);
            player.getInventory().setItem(KNIFE_HOTBAR_SLOT, knife);
            player.getInventory().setHeldItemSlot(KNIFE_HOTBAR_SLOT);
            mmPlayer.markMurdererWeaponGrantedNow();
            player.sendMessage(ChatColor.YELLOW + "You have received your sword!");
            getTitleService().send(
                    player,
                    "",
                    ChatColor.GOLD + "Right click " + ChatColor.YELLOW + "to throw your knife!",
                    TITLE_FADE_IN_TICKS,
                    TITLE_STAY_TICKS,
                    TITLE_FADE_OUT_FAST_TICKS
            );
        }
    }

    private void assignRoles() {
        List<MurderMysteryGamePlayer> players = getMmPlayers();
        if (players.isEmpty()) {
            return;
        }
        RoleChanceStore chanceStore = getPlugin().getRoleChanceStore();
        if (chanceStore != null) {
            assignRolesByChance(players, chanceStore);
            return;
        }
        java.util.Collections.shuffle(players);
        players.get(0).setRole(MurderMysteryRole.MURDERER);
        if (players.size() > 1) {
            players.get(1).setRole(MurderMysteryRole.DETECTIVE);
        }
        for (int i = 2; i < players.size(); i++) {
            players.get(i).setRole(MurderMysteryRole.INNOCENT);
        }
    }

    private UUID findDetectiveUuid() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() == MurderMysteryRole.DETECTIVE) {
                return mmPlayer.getUuid();
            }
        }
        return null;
    }

    private UUID findMurdererUuid() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
                return mmPlayer.getUuid();
            }
        }
        return null;
    }

    public MurderMysteryGamePlayer getCurrentMurderer() {
        return findPlayerByRole(MurderMysteryRole.MURDERER);
    }

    public MurderMysteryGamePlayer getCurrentDetective() {
        MurderMysteryGamePlayer detective = findPlayerByRole(MurderMysteryRole.DETECTIVE);
        if (detective != null) {
            return detective;
        }
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() == MurderMysteryRole.HERO && mmPlayer.hasDetectiveBow()) {
                return mmPlayer;
            }
        }
        return null;
    }

    private void assignRolesByChance(List<MurderMysteryGamePlayer> players, RoleChanceStore chanceStore) {
        List<UUID> uuids = new ArrayList<>();
        for (MurderMysteryGamePlayer mmPlayer : players) {
            uuids.add(mmPlayer.getUuid());
        }
        Map<UUID, RoleChance> chances = chanceStore.load(uuids, DEFAULT_MURDERER_CHANCE, DEFAULT_DETECTIVE_CHANCE);
        MurderMysteryGamePlayer murderer = selectWeighted(players, chances, true);
        if (murderer != null) {
            murderer.setRole(MurderMysteryRole.MURDERER);
        }
        MurderMysteryGamePlayer detective = null;
        if (players.size() > 1) {
            List<MurderMysteryGamePlayer> detectivePool = new ArrayList<>(players);
            if (murderer != null) {
                detectivePool.remove(murderer);
            }
            detective = selectWeighted(detectivePool, chances, false);
            if (detective != null) {
                detective.setRole(MurderMysteryRole.DETECTIVE);
            }
        }
        for (MurderMysteryGamePlayer mmPlayer : players) {
            if (mmPlayer != murderer && mmPlayer != detective) {
                mmPlayer.setRole(MurderMysteryRole.INNOCENT);
            }
        }
        for (MurderMysteryGamePlayer mmPlayer : players) {
            RoleChance chance = chances.get(mmPlayer.getUuid());
            if (chance == null) {
                continue;
            }
            if (murderer != null) {
                chance.adjustMurdererChance(mmPlayer == murderer ? -MURDERER_DECREASE : MURDERER_INCREASE, MIN_ROLE_CHANCE, MAX_ROLE_CHANCE);
            }
            if (detective != null) {
                chance.adjustDetectiveChance(mmPlayer == detective ? -DETECTIVE_DECREASE : DETECTIVE_INCREASE, MIN_ROLE_CHANCE, MAX_ROLE_CHANCE);
            }
        }
        chanceStore.save(chances.values());
    }

    private MurderMysteryGamePlayer selectWeighted(List<MurderMysteryGamePlayer> candidates, Map<UUID, RoleChance> chances, boolean murderer) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (MurderMysteryGamePlayer mmPlayer : candidates) {
            RoleChance chance = chances.get(mmPlayer.getUuid());
            double weight = chance == null ? 1.0 : (murderer ? chance.getMurdererChance() : chance.getDetectiveChance());
            total += Math.max(0.0, weight);
        }
        if (total <= 0.0) {
            return candidates.get((int) (Math.random() * candidates.size()));
        }
        double roll = Math.random() * total;
        double running = 0.0;
        for (MurderMysteryGamePlayer mmPlayer : candidates) {
            RoleChance chance = chances.get(mmPlayer.getUuid());
            double weight = chance == null ? 1.0 : (murderer ? chance.getMurdererChance() : chance.getDetectiveChance());
            running += Math.max(0.0, weight);
            if (roll <= running) {
                return mmPlayer;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private void assignReplacementMurderer(UUID leaver) {
        List<MurderMysteryGamePlayer> candidates = new ArrayList<>();
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (!mmPlayer.isAlive()) {
                continue;
            }
            if (mmPlayer.getUuid().equals(leaver)) {
                continue;
            }
            if (mmPlayer.getRole() == MurderMysteryRole.INNOCENT) {
                candidates.add(mmPlayer);
            }
        }
        if (candidates.isEmpty()) {
            innocentsWon = true;
            endGame();
            return;
        }
        MurderMysteryGamePlayer selected = candidates.get((int) (Math.random() * candidates.size()));
        selected.setRole(MurderMysteryRole.MURDERER);
        summaryMurdererUuid = selected.getUuid();
        summaryMurdererEliminated = false;
        Player player = Bukkit.getPlayer(selected.getUuid());
        if (player != null) {
            if (murdererSwordUnlocked) {
                CoreApi coreApi = getPlugin().getCoreApi();
                ItemStack knife = createMurdererKnife(coreApi, player);
                player.getInventory().setItem(KNIFE_HOTBAR_SLOT, knife);
                player.getInventory().setHeldItemSlot(KNIFE_HOTBAR_SLOT);
                selected.markMurdererWeaponGrantedNow();
            }
            showRoleTitle(player, selected.getRole());
            sendTeamingWarning(player, selected.getRole());
            player.sendMessage(ChatColor.GREEN + "The previous Murderer left, you are now taking their position!");
        }
        refreshMurdererLastInnocentSpeed();
    }

    private void forceGamePlayersToHotbarSlotOne() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.getInventory().setHeldItemSlot(HOTBAR_SLOT_ONE_INDEX);
        }
    }

    private void sendTeamingWarning(Player player, MurderMysteryRole role) {
        if (player == null) {
            return;
        }
        if (role == MurderMysteryRole.MURDERER) {
            player.sendMessage(MURDERER_TEAMING_WARNING);
            return;
        }
        if (role == MurderMysteryRole.DETECTIVE || role == MurderMysteryRole.HERO) {
            player.sendMessage(DETECTIVE_TEAMING_WARNING);
            return;
        }
        player.sendMessage(INNOCENT_TEAMING_WARNING);
    }

    private boolean hasAliveNonMurdererExcluding(UUID excludedUuid) {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer == null || !mmPlayer.isAlive()) {
                continue;
            }
            if (excludedUuid != null && excludedUuid.equals(mmPlayer.getUuid())) {
                continue;
            }
            if (mmPlayer.getRole() != MurderMysteryRole.MURDERER) {
                return true;
            }
        }
        return false;
    }

    private int getAliveNonMurdererCount() {
        int aliveNonMurderers = 0;
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer == null || !mmPlayer.isAlive()) {
                continue;
            }
            if (mmPlayer.getRole() != MurderMysteryRole.MURDERER) {
                aliveNonMurderers++;
            }
        }
        return aliveNonMurderers;
    }

    private void refreshMurdererLastInnocentSpeed() {
        if (getState() != GameState.IN_GAME) {
            clearMurdererLastInnocentSpeed();
            return;
        }
        if (getAliveNonMurdererCount() == 1) {
            applyMurdererLastInnocentSpeed();
            return;
        }
        clearMurdererLastInnocentSpeed();
    }

    private void applyMurdererLastInnocentSpeed() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer == null || !mmPlayer.isAlive() || mmPlayer.getRole() != MurderMysteryRole.MURDERER) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.addPotionEffect(
                    new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false),
                    true
            );
        }
    }

    private void clearMurdererLastInnocentSpeed() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer == null || !mmPlayer.isAlive() || mmPlayer.getRole() != MurderMysteryRole.MURDERER) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    private ItemStack createMurdererKnife(CoreApi coreApi, Player player) {
        ItemStack knife = null;
        if (player != null && coreApi != null) {
            KnifeSkinRegistry registry = KnifeSkinRegistry.fromCoreApi(coreApi);
            Profile profile = coreApi.getProfile(player.getUniqueId());
            if (profile == null) {
                profile = new Profile(player.getUniqueId(), player.getName());
            }
            knife = registry.createKnife(profile);
        }
        return applyCombatItemDescription(
                knife,
                Material.IRON_SWORD,
                "Knife",
                "Use your Knife to kill players."
        );
    }

    private ItemStack applyCombatItemDescription(ItemStack item,
                                                 Material fallbackMaterial,
                                                 String title,
                                                 String loreLine) {
        ItemStack resolved = item;
        if (resolved == null || resolved.getType() == Material.AIR) {
            resolved = new ItemStack(fallbackMaterial, 1);
        }
        ItemMeta meta = resolved.getItemMeta();
        if (meta == null) {
            return resolved;
        }
        meta.setDisplayName(ChatColor.GREEN + title);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + loreLine);
        meta.setLore(lore);
        resolved.setItemMeta(meta);
        return resolved;
    }

    private void updateGoldHotbarItem(Player player, MurderMysteryGamePlayer mmPlayer) {
        if (player == null || mmPlayer == null || !mmPlayer.isAlive()) {
            return;
        }
        clearStrayInventoryGold(player);
        int gold = Math.max(0, mmPlayer.getGold());
        if (gold <= 0) {
            player.getInventory().setItem(GOLD_HOTBAR_SLOT, null);
            return;
        }
        int shownAmount = Math.min(64, gold);
        player.getInventory().setItem(GOLD_HOTBAR_SLOT, new ItemStack(Material.GOLD_INGOT, shownAmount));
    }

    private void clearStrayInventoryGold(Player player) {
        if (player == null) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot == GOLD_HOTBAR_SLOT) {
                continue;
            }
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != Material.GOLD_INGOT) {
                continue;
            }
            player.getInventory().setItem(slot, null);
        }
    }

    private void syncGoldHotbarItem(Player player, MurderMysteryGamePlayer mmPlayer) {
        if (player == null) {
            return;
        }
        updateGoldHotbarItem(player, mmPlayer);
        player.updateInventory();
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                return;
            }
            MurderMysteryGamePlayer refreshed = getMurderMysteryPlayer(online);
            updateGoldHotbarItem(online, refreshed);
            online.updateInventory();
        });
    }

    private void dropBowAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        clearDroppedBowDisplay();
        Location spawn = location.clone().add(0.0D, DROPPED_BOW_HEIGHT_OFFSET, 0.0D);
        ArmorStand stand = spawn.getWorld().spawn(spawn, ArmorStand.class);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setArms(true);
        stand.setBasePlate(false);
        // Use a full-size stand so the dropped bow is clearly visible.
        stand.setSmall(false);
        stand.setItemInHand(new ItemStack(Material.BOW, 1));
        droppedBowDisplay = stand;
        droppedBowYaw = spawn.getYaw();
        droppedBowTask = getPlugin().getServer().getScheduler().runTaskTimer(
                getPlugin(),
                this::tickDroppedBowDisplay,
                1L,
                1L
        );
    }

    private void tickDroppedBowDisplay() {
        if (getState() != GameState.IN_GAME) {
            clearDroppedBowDisplay();
            return;
        }
        if (droppedBowDisplay == null || !droppedBowDisplay.isValid() || droppedBowDisplay.isDead()) {
            clearDroppedBowDisplay();
            return;
        }
        Location displayLocation = droppedBowDisplay.getLocation();
        droppedBowYaw += DROPPED_BOW_ROTATION_DEGREES_PER_TICK;
        if (droppedBowYaw >= 360.0f) {
            droppedBowYaw -= 360.0f;
        }
        displayLocation.setYaw(droppedBowYaw);
        droppedBowDisplay.teleport(displayLocation);
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (!mmPlayer.isAlive() || mmPlayer.getRole() != MurderMysteryRole.INNOCENT) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (player.getInventory().contains(Material.BOW)) {
                continue;
            }
            if (!player.getWorld().equals(displayLocation.getWorld())) {
                continue;
            }
            if (player.getLocation().distanceSquared(displayLocation) > DROPPED_BOW_PICKUP_RADIUS_SQUARED) {
                continue;
            }
            convertDroppedBowCarrierToHero(player);
            if (!player.getInventory().contains(Material.BOW)) {
                continue;
            }
            clearDroppedBowDisplay();
            return;
        }
    }

    private ItemStack createGameBowItem(Player player) {
        ItemStack bow = null;
        MurderMysteryRole role = null;
        if (player != null) {
            MurderMysteryGamePlayer mmPlayer = getMurderMysteryPlayer(player);
            if (mmPlayer != null) {
                role = mmPlayer.getRole();
            }
            CoreApi coreApi = getPlugin().getCoreApi();
            if (coreApi != null) {
                ItemStack customBow = coreApi.createBow(player.getUniqueId());
                if (customBow != null && customBow.getType() != Material.AIR) {
                    bow = customBow;
                }
            }
        }
        String loreLine = role == MurderMysteryRole.MURDERER
                ? "Use your Bow to kill players."
                : "Use your Bow to kill the Murderer.";
        return applyCombatItemDescription(
                bow,
                Material.BOW,
                "Bow",
                loreLine
        );
    }

    private void clearDroppedBowDisplay() {
        if (droppedBowTask != null) {
            droppedBowTask.cancel();
            droppedBowTask = null;
        }
        if (droppedBowDisplay != null) {
            if (droppedBowDisplay.isValid()) {
                droppedBowDisplay.remove();
            }
            droppedBowDisplay = null;
        }
        droppedBowYaw = 0.0f;
    }

    public void cleanupTransientRoundEntitiesForShutdown() {
        closeTrackedOpenables();
        clearActiveRoundArrows();
        clearActiveMapDropItems();
        clearDroppedBowDisplay();
    }

    public boolean isDroppedBowDisplay(Entity entity) {
        if (entity == null || droppedBowDisplay == null) {
            return false;
        }
        return entity.getUniqueId().equals(droppedBowDisplay.getUniqueId());
    }

    private int getAliveCount(MurderMysteryRole role) {
        int count = 0;
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() == role && mmPlayer.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private boolean hasAliveDetective() {
        return getAliveCount(MurderMysteryRole.DETECTIVE) > 0;
    }

    private boolean hasAliveHero() {
        return getAliveCount(MurderMysteryRole.HERO) > 0;
    }

    private String formatScoreboardRoleName(MurderMysteryRole role) {
        if (role == MurderMysteryRole.HERO) {
            return MurderMysteryRole.DETECTIVE.getDisplayName();
        }
        return role == null ? MurderMysteryRole.INNOCENT.getDisplayName() : role.getDisplayName();
    }

    private ChatColor formatScoreboardRoleColor(MurderMysteryRole role) {
        if (role == MurderMysteryRole.HERO) {
            return MurderMysteryRole.DETECTIVE.getColor();
        }
        return role == null ? MurderMysteryRole.INNOCENT.getColor() : role.getColor();
    }

    private void announceHeroWinners() {
        List<String> heroes = new ArrayList<>();
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() != MurderMysteryRole.HERO) {
                continue;
            }
            if (!mmPlayer.isHeroFromGold() || !mmPlayer.hasKilledMurderer()) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            heroes.add(player == null ? mmPlayer.getUuid().toString() : player.getName());
        }
        if (!heroes.isEmpty()) {
            broadcast(ChatColor.GOLD + "Hero: " + ChatColor.WHITE + String.join(", ", heroes));
        }
    }

    private static Sound resolveMurderKillDamageSound() {
        return resolveCompatibleSound("ENTITY_PLAYER_HURT", "HURT_FLESH");
    }

    private static Sound resolveCompatibleSound(String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            try {
                return Sound.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Try next enum constant.
            }
        }
        return null;
    }

    private void playMurdererKillDamageSoundToAllPlayers() {
        if (MURDER_KILL_DAMAGE_SOUND == null) {
            return;
        }
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            player.playSound(player.getLocation(), MURDER_KILL_DAMAGE_SOUND, 1.0f, 1.0f);
        }
    }

    private void playWinSoundToWinners() {
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (!didRoleWin(mmPlayer.getRole())) {
                continue;
            }
            Player player = Bukkit.getPlayer(mmPlayer.getUuid());
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (WIN_GAME_ORB_PICKUP_SOUND != null) {
                playWinnerOrbPickupSequence(player);
                continue;
            }
            if (WIN_GAME_SOUND != null) {
                player.playSound(player.getLocation(), WIN_GAME_SOUND, 1.0f, 1.0f);
            }
        }
    }

    private void playWinnerOrbPickupSequence(Player player) {
        if (player == null || !player.isOnline() || WIN_GAME_ORB_PICKUP_SOUND == null) {
            return;
        }
        final Player winner = player;
        winner.playSound(winner.getLocation(), WIN_GAME_ORB_PICKUP_SOUND, 1.0f, 0.9f);
        getPlugin().getServer().getScheduler().runTaskLater(getPlugin(), () -> {
            if (winner.isOnline()) {
                winner.playSound(winner.getLocation(), WIN_GAME_ORB_PICKUP_SOUND, 1.0f, 1.25f);
            }
        }, 4L);
    }

    private void publishResults(int roundDurationSeconds) {
        CoreApi coreApi = getPlugin().getCoreApi();
        if (coreApi == null) {
            return;
        }
        int safeRoundDurationSeconds = Math.max(0, roundDurationSeconds);
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            Profile profile = coreApi.getProfile(mmPlayer.getUuid());
            if (profile != null) {
                int completionReward = 1 + Math.max(0, mmPlayer.getKills());
                MurderMysteryStats.addTokens(profile.getStats(), completionReward);
                trackRoundReward(mmPlayer.getUuid(), tokenRewardLabel("Completed game"), completionReward);
                if (mmPlayer.getRole() == MurderMysteryRole.DETECTIVE && innocentsWon) {
                    MurderMysteryStats.addDetectiveWin(profile.getStats());
                }
                if (mmPlayer.getRole() == MurderMysteryRole.MURDERER && !innocentsWon) {
                    MurderMysteryStats.addMurdererWin(profile.getStats());
                }
                if (mmPlayer.getKillsAsMurderer() > 0) {
                    MurderMysteryStats.addMurdererKills(profile.getStats(), mmPlayer.getKillsAsMurderer());
                }
                if (mmPlayer.getBowKills() > 0) {
                    MurderMysteryStats.addBowKills(profile.getStats(), mmPlayer.getBowKills());
                }
                if (mmPlayer.getKnifeKills() > 0) {
                    MurderMysteryStats.addKnifeKills(profile.getStats(), mmPlayer.getKnifeKills());
                }
                if (mmPlayer.getThrownKnifeKills() > 0) {
                    MurderMysteryStats.addThrownKnifeKills(profile.getStats(), mmPlayer.getThrownKnifeKills());
                }
                if (mmPlayer.getKillsAsHero() > 0) {
                    MurderMysteryStats.addHeroKills(profile.getStats(), mmPlayer.getKillsAsHero());
                }
                if (safeRoundDurationSeconds > 1
                        && innocentsWon
                        && mmPlayer.getRole() == MurderMysteryRole.DETECTIVE) {
                    MurderMysteryStats.updateQuickestDetectiveWinSeconds(profile.getStats(), safeRoundDurationSeconds);
                }
                if (safeRoundDurationSeconds > 1
                        && !innocentsWon
                        && mmPlayer.getRole() == MurderMysteryRole.MURDERER) {
                    MurderMysteryStats.updateQuickestMurdererWinSeconds(profile.getStats(), safeRoundDurationSeconds);
                }
            }
            GameResult result = createMurderMysteryGameResult(mmPlayer);
            coreApi.recordGameResult(result);
        }
    }

    private MurderMysteryGamePlayer findPlayerByRole(MurderMysteryRole role) {
        if (role == null) {
            return null;
        }
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() == role) {
                return mmPlayer;
            }
        }
        return null;
    }

    private MurderMysteryGamePlayer findPlayerByUuid(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (uuid.equals(mmPlayer.getUuid())) {
                return mmPlayer;
            }
        }
        return null;
    }

    private MurderMysteryGamePlayer findHeroSummaryPlayer() {
        MurderMysteryGamePlayer firstHero = null;
        for (MurderMysteryGamePlayer mmPlayer : getMmPlayers()) {
            if (mmPlayer.getRole() != MurderMysteryRole.HERO) {
                continue;
            }
            if (firstHero == null) {
                firstHero = mmPlayer;
            }
            if (mmPlayer.hasKilledMurderer()) {
                return mmPlayer;
            }
        }
        return firstHero;
    }

    private String formatParticipantName(MurderMysteryGamePlayer mmPlayer, boolean strikethroughWhenDead) {
        if (mmPlayer == null) {
            return formatParticipantName((UUID) null, false, strikethroughWhenDead);
        }
        return formatParticipantName(mmPlayer.getUuid(), !mmPlayer.isAlive(), strikethroughWhenDead);
    }

    private String formatParticipantName(UUID uuid, boolean eliminated, boolean strikethroughWhenDead) {
        String name = resolveParticipantName(uuid);
        ChatColor color = resolveRankNameColor(uuid);
        if (!eliminated || !strikethroughWhenDead) {
            return color + name;
        }
        return color.toString() + ChatColor.STRIKETHROUGH + name + ChatColor.RESET + ChatColor.GRAY;
    }

    private ChatColor resolveRankNameColor(UUID uuid) {
        CoreApi coreApi = getPlugin() == null ? null : getPlugin().getCoreApi();
        if (coreApi == null || uuid == null) {
            return ChatColor.GRAY;
        }
        Rank rank = coreApi.getRank(uuid);
        if (rank == null) {
            rank = Rank.DEFAULT;
        }
        Profile profile = coreApi.getProfile(uuid);
        String mvpPlusPlusPrefixColor = profile == null ? null : profile.getMvpPlusPlusPrefixColor();
        ChatColor color = RankFormatUtil.baseColor(rank, mvpPlusPlusPrefixColor);
        return color == null ? ChatColor.GRAY : color;
    }

    private String resolveParticipantName(UUID uuid) {
        if (uuid == null) {
            return "Unknown";
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.getName() != null && !player.getName().trim().isEmpty()) {
            return player.getName();
        }
        CoreApi coreApi = getPlugin() == null ? null : getPlugin().getCoreApi();
        if (coreApi != null) {
            Profile profile = coreApi.getProfile(uuid);
            if (profile != null && profile.getName() != null && !profile.getName().trim().isEmpty()) {
                return profile.getName();
            }
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        if (offlinePlayer != null && offlinePlayer.getName() != null && !offlinePlayer.getName().trim().isEmpty()) {
            return offlinePlayer.getName();
        }
        return uuid.toString().substring(0, 8);
    }

    private int getMurdererKillCount(MurderMysteryGamePlayer murderer) {
        return murderer == null ? 0 : Math.max(0, murderer.getKillsAsMurderer());
    }

    private String murdererKillLabel(int killCount) {
        return killCount == 1 ? "kill" : "kills";
    }

    private String formatAmount(long value) {
        return String.format(Locale.US, "%,d", Math.max(0L, value));
    }

    private String centerPostGameLine(String line, int targetWidth) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        int padding = centeredPadding(line, targetWidth);
        if (padding <= 0) {
            return line;
        }
        StringBuilder builder = new StringBuilder(line.length() + padding);
        for (int i = 0; i < padding; i++) {
            builder.append(' ');
        }
        return builder.append(line).toString();
    }

    private int centeredPadding(String line, int targetWidth) {
        if (targetWidth <= 0) {
            return 0;
        }
        String stripped = ChatColor.stripColor(line);
        int visibleLength = stripped == null ? 0 : stripped.length();
        if (visibleLength >= targetWidth) {
            return 0;
        }
        return (targetWidth - visibleLength) / 2;
    }

    private int elapsedSecondsSince(long startMillis) {
        if (startMillis <= 0L) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long elapsedMillis = Math.max(0L, now - startMillis);
        return (int) Math.min(Integer.MAX_VALUE, elapsedMillis / 1000L);
    }

    private boolean didRoleWin(MurderMysteryRole role) {
        if (role == MurderMysteryRole.MURDERER) {
            return !innocentsWon;
        }
        return innocentsWon;
    }

    private String tokenRewardLabel(String reason) {
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty()) {
            return "Tokens Earned";
        }
        return normalizedReason;
    }

    private MurderMysteryGameResult createMurderMysteryGameResult(MurderMysteryGamePlayer mmPlayer) {
        return new MurderMysteryGameResult(
                mmPlayer.getUuid(),
                mmPlayer.getRole() == MurderMysteryRole.MURDERER,
                mmPlayer.isAlive(),
                mmPlayer.getKills());
    }

    private List<MurderMysteryGamePlayer> getMmPlayers() {
        List<MurderMysteryGamePlayer> result = new ArrayList<>();
        for (GamePlayer gamePlayer : getPlayers().values()) {
            MurderMysteryGamePlayer mmPlayer = asMmPlayer(gamePlayer);
            if (mmPlayer != null) {
                result.add(mmPlayer);
            }
        }
        return result;
    }

    private MurderMysteryGamePlayer asMmPlayer(GamePlayer gamePlayer) {
        if (!(gamePlayer instanceof MurderMysteryGamePlayer)) {
            return null;
        }
        return (MurderMysteryGamePlayer) gamePlayer;
    }

    private static final class OpenableBlockRef {
        private final String worldName;
        private final int x;
        private final int y;
        private final int z;

        private OpenableBlockRef(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private static OpenableBlockRef from(Block block) {
            World world = block.getWorld();
            String worldName = world == null ? null : world.getName();
            return new OpenableBlockRef(worldName, block.getX(), block.getY(), block.getZ());
        }

        private Block resolve() {
            if (worldName == null || worldName.trim().isEmpty()) {
                return null;
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return world.getBlockAt(x, y, z);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OpenableBlockRef)) {
                return false;
            }
            OpenableBlockRef that = (OpenableBlockRef) other;
            if (x != that.x || y != that.y || z != that.z) {
                return false;
            }
            if (worldName == null) {
                return that.worldName == null;
            }
            return worldName.equals(that.worldName);
        }

        @Override
        public int hashCode() {
            int result = worldName == null ? 0 : worldName.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
