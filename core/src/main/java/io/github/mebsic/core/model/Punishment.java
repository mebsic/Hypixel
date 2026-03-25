package io.github.mebsic.core.model;

import java.util.UUID;

public class Punishment {
    private final String id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final UUID actorUuid;
    private final String actorName;
    private final String reason;
    private final long createdAt;
    private final Long expiresAt;
    private final boolean active;

    public Punishment(String id,
                      PunishmentType type,
                      UUID targetUuid,
                      String targetName,
                      UUID actorUuid,
                      String actorName,
                      String reason,
                      long createdAt,
                      Long expiresAt,
                      boolean active) {
        this.id = id;
        this.type = type;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.actorUuid = actorUuid;
        this.actorName = actorName;
        this.reason = reason;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.active = active;
    }

    public String getId() {
        return id;
    }

    public PunishmentType getType() {
        return type;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public UUID getActorUuid() {
        return actorUuid;
    }

    public String getActorName() {
        return actorName;
    }

    public String getReason() {
        return reason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }
}
