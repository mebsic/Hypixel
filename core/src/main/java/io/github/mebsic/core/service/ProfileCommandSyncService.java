package io.github.mebsic.core.service;

import com.google.gson.Gson;
import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.manager.MongoManager;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public class ProfileCommandSyncService {
    private static final String CHANNEL = "profile_command_action";
    private static final String ACTION_SET_RANK = "SET_RANK";
    private static final String ACTION_SET_NETWORK_LEVEL = "SET_NETWORK_LEVEL";
    private static final String ACTION_SET_NETWORK_GOLD = "SET_NETWORK_GOLD";
    private static final String ACTION_SET_MYSTERY_DUST = "SET_MYSTERY_DUST";
    private static final String ACTION_SET_COUNTER = "SET_COUNTER";

    private final CorePlugin plugin;
    private final PubSubService pubSub;
    private final Gson gson;
    private final String localServerId;

    public ProfileCommandSyncService(CorePlugin plugin, PubSubService pubSub, String serverId) {
        this.plugin = plugin;
        this.pubSub = pubSub;
        this.gson = new Gson();
        this.localServerId = normalizeServerId(serverId);
        subscribe();
    }

    public void dispatchRankUpdate(UUID targetUuid, Rank rank, String targetMessage) {
        dispatchRankUpdate(targetUuid, rank, null, targetMessage);
    }

    public void dispatchRankUpdate(UUID targetUuid, Rank rank, Integer mvpPlusPlusDays, String targetMessage) {
        dispatchRankUpdate(targetUuid, rank, mvpPlusPlusDays, false, targetMessage);
    }

    public void dispatchRankUpdate(UUID targetUuid,
                                   Rank rank,
                                   Integer mvpPlusPlusDays,
                                   boolean mvpPlusPlusAccumulate,
                                   String targetMessage) {
        if (targetUuid == null || rank == null) {
            return;
        }
        publish(new ProfileCommandAction(
                localServerId,
                ACTION_SET_RANK,
                targetUuid.toString(),
                rank.name(),
                mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays),
                mvpPlusPlusAccumulate,
                null,
                null,
                0,
                targetMessage
        ));
    }

    public void dispatchNetworkLevelUpdate(UUID targetUuid, int networkLevel, String targetMessage) {
        if (targetUuid == null) {
            return;
        }
        publish(new ProfileCommandAction(
                localServerId,
                ACTION_SET_NETWORK_LEVEL,
                targetUuid.toString(),
                null,
                null,
                false,
                null,
                null,
                Math.max(0, networkLevel),
                targetMessage
        ));
    }

    public void dispatchNetworkGoldUpdate(UUID targetUuid, int networkGold, String targetMessage) {
        if (targetUuid == null) {
            return;
        }
        publish(new ProfileCommandAction(
                localServerId,
                ACTION_SET_NETWORK_GOLD,
                targetUuid.toString(),
                null,
                null,
                false,
                null,
                null,
                Math.max(0, networkGold),
                targetMessage
        ));
    }

    public void dispatchMysteryDustUpdate(UUID targetUuid, int mysteryDust, String targetMessage) {
        if (targetUuid == null) {
            return;
        }
        publish(new ProfileCommandAction(
                localServerId,
                ACTION_SET_MYSTERY_DUST,
                targetUuid.toString(),
                null,
                null,
                false,
                null,
                null,
                Math.max(0, mysteryDust),
                targetMessage
        ));
    }

    public void dispatchCounterSet(UUID targetUuid,
                                   String counterKey,
                                   int counterValue,
                                   String clearCounterKey,
                                   String targetMessage) {
        if (targetUuid == null || counterKey == null || counterKey.trim().isEmpty()) {
            return;
        }
        publish(new ProfileCommandAction(
                localServerId,
                ACTION_SET_COUNTER,
                targetUuid.toString(),
                null,
                null,
                false,
                counterKey.trim(),
                clearCounterKey,
                Math.max(0, counterValue),
                targetMessage
        ));
    }

    private void publish(ProfileCommandAction action) {
        if (pubSub == null || action == null) {
            return;
        }
        try {
            pubSub.publish(CHANNEL, gson.toJson(action));
        } catch (Exception ignored) {
            // The command server already persisted the update.
        }
    }

    private void subscribe() {
        if (plugin == null || pubSub == null) {
            return;
        }
        pubSub.subscribe(CHANNEL, this::handleRemoteAction);
    }

    private void handleRemoteAction(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }
        ProfileCommandAction action;
        try {
            action = gson.fromJson(payload, ProfileCommandAction.class);
        } catch (Exception ignored) {
            return;
        }
        if (action == null || action.action == null || action.targetUuid == null) {
            return;
        }
        if (action.serverId != null && action.serverId.equalsIgnoreCase(localServerId)) {
            return;
        }
        runOnMainThread(() -> applyLocally(action));
    }

    private void runOnMainThread(Runnable task) {
        if (task == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private void applyLocally(ProfileCommandAction action) {
        UUID targetUuid = parseUuid(action.targetUuid);
        if (targetUuid == null || action == null || action.action == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            return;
        }

        String normalizedAction = action.action.trim().toUpperCase(Locale.ROOT);
        switch (normalizedAction) {
            case ACTION_SET_RANK: {
                Rank rank = parseRank(action.rankName);
                if (rank == null) {
                    return;
                }
                plugin.setRank(targetUuid, rank, action.mvpPlusPlusDays, action.mvpPlusPlusAccumulate);
                sendTargetMessage(target, action.targetMessage);
                return;
            }
            case ACTION_SET_NETWORK_LEVEL: {
                plugin.setNetworkLevel(targetUuid, Math.max(0, action.networkLevel));
                sendTargetMessage(target, action.targetMessage);
                return;
            }
            case ACTION_SET_NETWORK_GOLD: {
                plugin.setNetworkGold(targetUuid, Math.max(0, action.networkGold));
                sendTargetMessage(target, action.targetMessage);
                return;
            }
            case ACTION_SET_MYSTERY_DUST: {
                plugin.setMysteryDust(targetUuid, Math.max(0, action.mysteryDust));
                sendTargetMessage(target, action.targetMessage);
                return;
            }
            case ACTION_SET_COUNTER: {
                applyCounterSet(targetUuid, action.counterKey, action.counterValue, action.clearCounterKey);
                sendTargetMessage(target, action.targetMessage);
                return;
            }
            default:
                return;
        }
    }

    private void applyCounterSet(UUID uuid, String key, int value, String clearKey) {
        if (uuid == null || key == null || key.trim().isEmpty()) {
            return;
        }
        Profile profile = plugin.getProfile(uuid);
        if (profile == null) {
            return;
        }
        String normalizedKey = key.trim();
        int desired = Math.max(0, value);
        if (MongoManager.PROFILE_RANKS_GIFTED_KEY.equals(normalizedKey)) {
            profile.setRanksGifted(desired);
        } else if (MongoManager.MURDER_MYSTERY_LIFETIME_WINS_KEY.equals(normalizedKey)) {
            int current = Math.max(0, profile.getStats().getWins());
            if (current != desired) {
                profile.getStats().addWins(desired - current);
            }
        } else if (MongoManager.MURDER_MYSTERY_LIFETIME_KILLS_KEY.equals(normalizedKey)) {
            int current = Math.max(0, profile.getStats().getKills());
            if (current != desired) {
                profile.getStats().addKills(desired - current);
            }
        } else if (MongoManager.MURDER_MYSTERY_LIFETIME_GAMES_KEY.equals(normalizedKey)) {
            int current = Math.max(0, profile.getStats().getGames());
            if (current != desired) {
                profile.getStats().addGames(desired - current);
            }
        } else {
            int current = profile.getStats().getCustomCounter(normalizedKey);
            if (current != desired) {
                profile.getStats().addCustomCounter(normalizedKey, desired - current);
            }
        }
        if (clearKey != null && !clearKey.trim().isEmpty()) {
            String normalizedLegacy = clearKey.trim();
            if (MongoManager.PROFILE_RANKS_GIFTED_KEY.equals(normalizedLegacy)) {
                profile.setRanksGifted(0);
            } else if (MongoManager.MURDER_MYSTERY_LIFETIME_WINS_KEY.equals(normalizedLegacy)) {
                int current = Math.max(0, profile.getStats().getWins());
                if (current > 0) {
                    profile.getStats().addWins(-current);
                }
            } else if (MongoManager.MURDER_MYSTERY_LIFETIME_KILLS_KEY.equals(normalizedLegacy)) {
                int current = Math.max(0, profile.getStats().getKills());
                if (current > 0) {
                    profile.getStats().addKills(-current);
                }
            } else if (MongoManager.MURDER_MYSTERY_LIFETIME_GAMES_KEY.equals(normalizedLegacy)) {
                int current = Math.max(0, profile.getStats().getGames());
                if (current > 0) {
                    profile.getStats().addGames(-current);
                }
            } else {
                int legacy = profile.getStats().getCustomCounter(normalizedLegacy);
                if (legacy > 0) {
                    profile.getStats().addCustomCounter(normalizedLegacy, -legacy);
                }
            }
        }
        plugin.saveProfile(profile);
    }

    private void sendTargetMessage(Player target, String message) {
        if (target == null || message == null || message.trim().isEmpty()) {
            return;
        }
        String normalized = message.replace("\r", "");
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            target.sendMessage(line == null ? "" : line);
        }
    }

    private Rank parseRank(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Rank.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String normalizeServerId(String serverId) {
        if (serverId != null && !serverId.trim().isEmpty()) {
            return serverId.trim();
        }
        return "server-" + UUID.randomUUID();
    }

    private static final class ProfileCommandAction {
        private String serverId;
        private String action;
        private String targetUuid;
        private String rankName;
        private Integer mvpPlusPlusDays;
        private boolean mvpPlusPlusAccumulate;
        private String counterKey;
        private String clearCounterKey;
        private int networkLevel;
        private int networkGold;
        private int mysteryDust;
        private int counterValue;
        private String targetMessage;

        private ProfileCommandAction() {
        }

        private ProfileCommandAction(String serverId,
                                     String action,
                                     String targetUuid,
                                     String rankName,
                                     Integer mvpPlusPlusDays,
                                     boolean mvpPlusPlusAccumulate,
                                     String counterKey,
                                     String clearCounterKey,
                                     int value,
                                     String targetMessage) {
            this.serverId = serverId;
            this.action = action;
            this.targetUuid = targetUuid;
            this.rankName = rankName;
            this.mvpPlusPlusDays = mvpPlusPlusDays;
            this.mvpPlusPlusAccumulate = mvpPlusPlusAccumulate;
            this.counterKey = counterKey;
            this.clearCounterKey = clearCounterKey;
            if (ACTION_SET_NETWORK_LEVEL.equals(action)) {
                this.networkLevel = value;
            } else if (ACTION_SET_NETWORK_GOLD.equals(action)) {
                this.networkGold = value;
            } else if (ACTION_SET_MYSTERY_DUST.equals(action)) {
                this.mysteryDust = value;
            } else {
                this.counterValue = value;
            }
            this.targetMessage = targetMessage;
        }
    }
}
