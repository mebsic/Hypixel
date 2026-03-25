package io.github.mebsic.murdermystery.game;

import io.github.mebsic.core.model.GameResult;
import io.github.mebsic.core.server.ServerType;

import java.util.UUID;

public class MurderMysteryGameResult extends GameResult {
    private final boolean murderer;
    private final boolean survived;

    public MurderMysteryGameResult(UUID uuid, boolean murderer, boolean survived, int kills) {
        super(uuid, ServerType.MURDER_MYSTERY, survived, kills);
        this.murderer = murderer;
        this.survived = survived;
    }

    public boolean isMurderer() {
        return murderer;
    }

    public boolean isSurvived() {
        return survived;
    }
}
