package io.github.mebsic.core.util;

import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.service.CoreApi;
import org.bukkit.entity.Player;

public final class RankUtil {
    private RankUtil() {
    }

    public static Rank getRank(CoreApi api, Player player) {
        if (api == null || player == null) {
            return Rank.DEFAULT;
        }
        return api.getRank(player.getUniqueId());
    }

    public static boolean hasAtLeast(CoreApi api, Player player, Rank required) {
        return getRank(api, player).isAtLeast(required);
    }
}
