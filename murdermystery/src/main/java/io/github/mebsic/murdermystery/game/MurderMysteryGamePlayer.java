package io.github.mebsic.murdermystery.game;

import io.github.mebsic.game.model.GamePlayer;

import java.util.UUID;

public class MurderMysteryGamePlayer extends GamePlayer {
    private MurderMysteryRole role;
    private int gold;
    private long lastKnifeThrow;
    private long lastArrowShot;
    private boolean heroFromGold;
    private boolean hasDetectiveBow;
    private boolean killedMurderer;
    private int killsAsMurderer;
    private int bowKills;
    private int knifeKills;
    private int thrownKnifeKills;
    private int killsAsHero;
    private long murdererWeaponGrantedAtMillis;
    private long detectiveWeaponGrantedAtMillis;
    private int murdererWinningKillSeconds;
    private int detectiveWinningKillSeconds;

    public MurderMysteryGamePlayer(UUID uuid) {
        super(uuid);
        this.role = MurderMysteryRole.INNOCENT;
        this.gold = 0;
        this.lastKnifeThrow = 0L;
        this.lastArrowShot = 0L;
        this.heroFromGold = false;
        this.hasDetectiveBow = false;
        this.killedMurderer = false;
        this.killsAsMurderer = 0;
        this.bowKills = 0;
        this.knifeKills = 0;
        this.thrownKnifeKills = 0;
        this.killsAsHero = 0;
        this.murdererWeaponGrantedAtMillis = 0L;
        this.detectiveWeaponGrantedAtMillis = 0L;
        this.murdererWinningKillSeconds = 0;
        this.detectiveWinningKillSeconds = 0;
    }

    public MurderMysteryRole getRole() {
        return role;
    }

    public void setRole(MurderMysteryRole role) {
        this.role = role;
    }

    public int getGold() {
        return gold;
    }

    public void addGold(int amount) {
        this.gold += amount;
    }

    public void removeGold(int amount) {
        this.gold = Math.max(0, this.gold - amount);
    }

    public long getLastKnifeThrow() {
        return lastKnifeThrow;
    }

    public void setLastKnifeThrow(long lastKnifeThrow) {
        this.lastKnifeThrow = lastKnifeThrow;
    }

    public long getLastArrowShot() {
        return lastArrowShot;
    }

    public void setLastArrowShot(long lastArrowShot) {
        this.lastArrowShot = lastArrowShot;
    }

    public boolean isHeroFromGold() {
        return heroFromGold;
    }

    public void setHeroFromGold(boolean heroFromGold) {
        this.heroFromGold = heroFromGold;
    }

    public boolean hasKilledMurderer() {
        return killedMurderer;
    }

    public boolean hasDetectiveBow() {
        return hasDetectiveBow;
    }

    public void setHasDetectiveBow(boolean hasDetectiveBow) {
        this.hasDetectiveBow = hasDetectiveBow;
    }

    public void setKilledMurderer(boolean killedMurderer) {
        this.killedMurderer = killedMurderer;
    }

    public int getKillsAsMurderer() {
        return Math.max(0, killsAsMurderer);
    }

    public void addKillAsMurderer() {
        killsAsMurderer++;
    }

    public int getBowKills() {
        return Math.max(0, bowKills);
    }

    public void addBowKill() {
        bowKills++;
    }

    public int getKnifeKills() {
        return Math.max(0, knifeKills);
    }

    public void addKnifeKill() {
        knifeKills++;
    }

    public int getThrownKnifeKills() {
        return Math.max(0, thrownKnifeKills);
    }

    public void addThrownKnifeKill() {
        thrownKnifeKills++;
    }

    public int getKillsAsHero() {
        return Math.max(0, killsAsHero);
    }

    public void addKillAsHero() {
        killsAsHero++;
    }

    public long getMurdererWeaponGrantedAtMillis() {
        return murdererWeaponGrantedAtMillis;
    }

    public void markMurdererWeaponGrantedNow() {
        if (murdererWeaponGrantedAtMillis <= 0L) {
            murdererWeaponGrantedAtMillis = System.currentTimeMillis();
        }
    }

    public long getDetectiveWeaponGrantedAtMillis() {
        return detectiveWeaponGrantedAtMillis;
    }

    public void markDetectiveWeaponGrantedNow() {
        if (detectiveWeaponGrantedAtMillis <= 0L) {
            detectiveWeaponGrantedAtMillis = System.currentTimeMillis();
        }
    }

    public int getMurdererWinningKillSeconds() {
        return Math.max(0, murdererWinningKillSeconds);
    }

    public void recordMurdererWinningKillSeconds(int elapsedSeconds) {
        int safe = Math.max(0, elapsedSeconds);
        if (safe <= 0) {
            return;
        }
        if (murdererWinningKillSeconds <= 0 || safe < murdererWinningKillSeconds) {
            murdererWinningKillSeconds = safe;
        }
    }

    public int getDetectiveWinningKillSeconds() {
        return Math.max(0, detectiveWinningKillSeconds);
    }

    public void recordDetectiveWinningKillSeconds(int elapsedSeconds) {
        int safe = Math.max(0, elapsedSeconds);
        if (safe <= 0) {
            return;
        }
        if (detectiveWinningKillSeconds <= 0 || safe < detectiveWinningKillSeconds) {
            detectiveWinningKillSeconds = safe;
        }
    }

    public void resetForRound() {
        setAlive(true);
        resetKills();
        role = MurderMysteryRole.INNOCENT;
        gold = 0;
        lastKnifeThrow = 0L;
        lastArrowShot = 0L;
        heroFromGold = false;
        hasDetectiveBow = false;
        killedMurderer = false;
        killsAsMurderer = 0;
        bowKills = 0;
        knifeKills = 0;
        thrownKnifeKills = 0;
        killsAsHero = 0;
        murdererWeaponGrantedAtMillis = 0L;
        detectiveWeaponGrantedAtMillis = 0L;
        murdererWinningKillSeconds = 0;
        detectiveWinningKillSeconds = 0;
    }
}
