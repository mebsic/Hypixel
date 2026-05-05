package io.github.mebsic.core.model;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Profile {
    private final UUID uuid;
    private String name;
    private Rank rank;
    private int networkLevel;
    private int networkGold;
    private int ranksGifted;
    private long hycopyExperience;
    private String plusColor;
    private String mvpPlusPlusPrefixColor;
    private String firstLogin;
    private String lastLogin;
    private boolean online;
    private boolean hasActiveSubscription;
    private long subscriptionExpiresAt;
    private boolean flightEnabled;
    private long buildModeExpiresAt;
    private boolean playerVisibilityEnabled;
    private int spectatorSpeedLevel;
    private boolean spectatorAutoTeleportEnabled;
    private boolean spectatorNightVisionEnabled;
    private boolean spectatorHideOtherSpectatorsEnabled;
    private boolean spectatorFirstPersonEnabled;
    private final Stats stats;
    private final Map<CosmeticType, String> selected;
    private final Map<CosmeticType, Set<String>> unlocked;
    private final Map<CosmeticType, Set<String>> favorites;

    public Profile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.rank = Rank.DEFAULT;
        this.networkLevel = 0;
        this.networkGold = 0;
        this.ranksGifted = 0;
        this.hycopyExperience = 0L;
        this.plusColor = null;
        this.mvpPlusPlusPrefixColor = null;
        this.firstLogin = null;
        this.lastLogin = null;
        this.online = false;
        this.hasActiveSubscription = false;
        this.subscriptionExpiresAt = 0L;
        this.flightEnabled = false;
        this.buildModeExpiresAt = 0L;
        this.playerVisibilityEnabled = true;
        this.spectatorSpeedLevel = 0;
        this.spectatorAutoTeleportEnabled = false;
        this.spectatorNightVisionEnabled = true;
        this.spectatorHideOtherSpectatorsEnabled = false;
        this.spectatorFirstPersonEnabled = false;
        this.stats = new Stats();
        this.selected = new EnumMap<>(CosmeticType.class);
        this.unlocked = new EnumMap<>(CosmeticType.class);
        this.favorites = new EnumMap<>(CosmeticType.class);
        for (CosmeticType type : CosmeticType.values()) {
            unlocked.put(type, new HashSet<String>());
            favorites.put(type, new HashSet<String>());
        }
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Rank getRank() {
        return rank;
    }

    public void setRank(Rank rank) {
        this.rank = rank;
    }

    public int getNetworkLevel() {
        return networkLevel;
    }

    public void setNetworkLevel(int networkLevel) {
        this.networkLevel = Math.max(0, networkLevel);
    }

    public int getNetworkGold() {
        return networkGold;
    }

    public void setNetworkGold(int networkGold) {
        this.networkGold = Math.max(0, networkGold);
    }

    public int getRanksGifted() {
        return ranksGifted;
    }

    public void setRanksGifted(int ranksGifted) {
        this.ranksGifted = Math.max(0, ranksGifted);
    }

    public long getHycopyExperience() {
        return hycopyExperience;
    }

    public void setHycopyExperience(long hycopyExperience) {
        this.hycopyExperience = Math.max(0L, hycopyExperience);
    }

    public String getPlusColor() {
        return plusColor;
    }

    public void setPlusColor(String plusColor) {
        this.plusColor = plusColor;
    }

    public String getMvpPlusPlusPrefixColor() {
        return mvpPlusPlusPrefixColor;
    }

    public void setMvpPlusPlusPrefixColor(String mvpPlusPlusPrefixColor) {
        this.mvpPlusPlusPrefixColor = mvpPlusPlusPrefixColor;
    }

    public String getFirstLogin() {
        return firstLogin;
    }

    public void setFirstLogin(String firstLogin) {
        this.firstLogin = firstLogin;
    }

    public String getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(String lastLogin) {
        this.lastLogin = lastLogin;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public boolean hasActiveSubscription() {
        return hasActiveSubscription;
    }

    public void setHasActiveSubscription(boolean hasActiveSubscription) {
        this.hasActiveSubscription = hasActiveSubscription;
    }

    public long getSubscriptionExpiresAt() {
        return subscriptionExpiresAt;
    }

    public void setSubscriptionExpiresAt(long subscriptionExpiresAt) {
        this.subscriptionExpiresAt = Math.max(0L, subscriptionExpiresAt);
    }

    public boolean isFlightEnabled() {
        return flightEnabled;
    }

    public void setFlightEnabled(boolean flightEnabled) {
        this.flightEnabled = flightEnabled;
    }

    public long getBuildModeExpiresAt() {
        return buildModeExpiresAt;
    }

    public void setBuildModeExpiresAt(long buildModeExpiresAt) {
        this.buildModeExpiresAt = Math.max(0L, buildModeExpiresAt);
    }

    public boolean isPlayerVisibilityEnabled() {
        return playerVisibilityEnabled;
    }

    public void setPlayerVisibilityEnabled(boolean playerVisibilityEnabled) {
        this.playerVisibilityEnabled = playerVisibilityEnabled;
    }

    public int getSpectatorSpeedLevel() {
        return spectatorSpeedLevel;
    }

    public void setSpectatorSpeedLevel(int spectatorSpeedLevel) {
        this.spectatorSpeedLevel = Math.max(0, Math.min(4, spectatorSpeedLevel));
    }

    public boolean isSpectatorAutoTeleportEnabled() {
        return spectatorAutoTeleportEnabled;
    }

    public void setSpectatorAutoTeleportEnabled(boolean spectatorAutoTeleportEnabled) {
        this.spectatorAutoTeleportEnabled = spectatorAutoTeleportEnabled;
    }

    public boolean isSpectatorNightVisionEnabled() {
        return spectatorNightVisionEnabled;
    }

    public void setSpectatorNightVisionEnabled(boolean spectatorNightVisionEnabled) {
        this.spectatorNightVisionEnabled = spectatorNightVisionEnabled;
    }

    public boolean isSpectatorHideOtherSpectatorsEnabled() {
        return spectatorHideOtherSpectatorsEnabled;
    }

    public void setSpectatorHideOtherSpectatorsEnabled(boolean spectatorHideOtherSpectatorsEnabled) {
        this.spectatorHideOtherSpectatorsEnabled = spectatorHideOtherSpectatorsEnabled;
    }

    public boolean isSpectatorFirstPersonEnabled() {
        return spectatorFirstPersonEnabled;
    }

    public void setSpectatorFirstPersonEnabled(boolean spectatorFirstPersonEnabled) {
        this.spectatorFirstPersonEnabled = spectatorFirstPersonEnabled;
    }

    public Stats getStats() {
        return stats;
    }

    public Map<CosmeticType, String> getSelected() {
        return selected;
    }

    public Map<CosmeticType, Set<String>> getUnlocked() {
        return unlocked;
    }

    public Map<CosmeticType, Set<String>> getFavorites() {
        return favorites;
    }
}
