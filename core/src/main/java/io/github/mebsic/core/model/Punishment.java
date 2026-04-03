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
    private final UUID deactivatedByUuid;
    private final String deactivatedByName;
    private final Long deactivatedAt;

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
        this(id, type, targetUuid, targetName, actorUuid, actorName, reason, createdAt, expiresAt, active, null, null, null);
    }

    public Punishment(String id,
                      PunishmentType type,
                      UUID targetUuid,
                      String targetName,
                      UUID actorUuid,
                      String actorName,
                      String reason,
                      long createdAt,
                      Long expiresAt,
                      boolean active,
                      UUID deactivatedByUuid,
                      String deactivatedByName,
                      Long deactivatedAt) {
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
        this.deactivatedByUuid = deactivatedByUuid;
        this.deactivatedByName = deactivatedByName;
        this.deactivatedAt = deactivatedAt;
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

    public UUID getDeactivatedByUuid() {
        return deactivatedByUuid;
    }

    public String getDeactivatedByName() {
        return deactivatedByName;
    }

    public Long getDeactivatedAt() {
        return deactivatedAt;
    }
}
