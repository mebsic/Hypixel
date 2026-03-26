package io.github.mebsic.core.service;

import io.github.mebsic.core.model.Rank;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GiftRequestService {
    private static final long DEFAULT_EXPIRY_MILLIS = 5L * 60L * 1000L;

    private final ConcurrentHashMap<UUID, GiftRequest> pendingByTarget;
    private final long expiryMillis;

    public GiftRequestService() {
        this(DEFAULT_EXPIRY_MILLIS);
    }

    public GiftRequestService(long expiryMillis) {
        this.pendingByTarget = new ConcurrentHashMap<UUID, GiftRequest>();
        this.expiryMillis = Math.max(10_000L, expiryMillis);
    }

    public boolean queue(GiftRequest request) {
        if (request == null || request.getTargetUuid() == null) {
            return false;
        }
        UUID targetUuid = request.getTargetUuid();
        while (true) {
            GiftRequest existing = pendingByTarget.get(targetUuid);
            if (existing != null) {
                if (!isExpired(existing)) {
                    return false;
                }
                pendingByTarget.remove(targetUuid, existing);
                continue;
            }
            GiftRequest created = pendingByTarget.putIfAbsent(targetUuid, request);
            if (created == null) {
                return true;
            }
        }
    }

    public GiftRequest get(UUID targetUuid) {
        if (targetUuid == null) {
            return null;
        }
        GiftRequest request = pendingByTarget.get(targetUuid);
        if (request == null) {
            return null;
        }
        if (isExpired(request)) {
            pendingByTarget.remove(targetUuid, request);
            return null;
        }
        return request;
    }

    public GiftRequest remove(UUID targetUuid) {
        GiftRequest request = get(targetUuid);
        if (request == null) {
            return null;
        }
        pendingByTarget.remove(targetUuid, request);
        return request;
    }

    public List<GiftRequest> removeByParticipant(UUID participantUuid) {
        List<GiftRequest> removed = new ArrayList<GiftRequest>();
        if (participantUuid == null) {
            return removed;
        }
        for (Map.Entry<UUID, GiftRequest> entry : pendingByTarget.entrySet()) {
            if (entry == null) {
                continue;
            }
            GiftRequest request = entry.getValue();
            if (request == null) {
                continue;
            }
            UUID gifterUuid = request.getGifterUuid();
            UUID targetUuid = request.getTargetUuid();
            boolean matches = participantUuid.equals(gifterUuid) || participantUuid.equals(targetUuid);
            if (!matches) {
                continue;
            }
            UUID key = entry.getKey();
            if (key == null) {
                continue;
            }
            if (pendingByTarget.remove(key, request)) {
                removed.add(request);
            }
        }
        return removed;
    }

    private boolean isExpired(GiftRequest request) {
        if (request == null) {
            return true;
        }
        long createdAt = Math.max(0L, request.getCreatedAt());
        long now = System.currentTimeMillis();
        return now - createdAt > expiryMillis;
    }

    public static final class GiftRequest {
        private final UUID gifterUuid;
        private final String gifterName;
        private final UUID targetUuid;
        private final String targetName;
        private final Rank giftedRank;
        private final int costGold;
        private final Integer mvpPlusPlusDays;
        private final long createdAt;

        public GiftRequest(UUID gifterUuid,
                           String gifterName,
                           UUID targetUuid,
                           String targetName,
                           Rank giftedRank,
                           int costGold,
                           Integer mvpPlusPlusDays,
                           long createdAt) {
            this.gifterUuid = gifterUuid;
            this.gifterName = gifterName == null ? "" : gifterName.trim();
            this.targetUuid = targetUuid;
            this.targetName = targetName == null ? "" : targetName.trim();
            this.giftedRank = giftedRank == null ? Rank.DEFAULT : giftedRank;
            this.costGold = Math.max(0, costGold);
            this.mvpPlusPlusDays = mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays);
            this.createdAt = Math.max(0L, createdAt);
        }

        public UUID getGifterUuid() {
            return gifterUuid;
        }

        public String getGifterName() {
            return gifterName;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getTargetName() {
            return targetName;
        }

        public Rank getGiftedRank() {
            return giftedRank;
        }

        public int getCostGold() {
            return costGold;
        }

        public Integer getMvpPlusPlusDays() {
            return mvpPlusPlusDays;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public boolean isMvpPlusPlusGift() {
            return giftedRank == Rank.MVP_PLUS_PLUS;
        }
    }
}
