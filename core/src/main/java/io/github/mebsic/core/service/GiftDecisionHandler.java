package io.github.mebsic.core.service;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.menu.GiftSupport;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public final class GiftDecisionHandler {
    private static final String GIFT_UNAVAILABLE_MESSAGE = ChatColor.RED + "That gift is no longer available!";

    private GiftDecisionHandler() {
    }

    public static void accept(CorePlugin plugin, Player target) {
        if (plugin == null || target == null) {
            return;
        }
        if (!isHubServer(plugin)) {
            target.sendMessage(ChatColor.RED + "You can only gift players in a lobby!");
            return;
        }

        GiftRequestService service = plugin.getGiftRequestService();
        if (service == null) {
            sendGiftUnavailable(target);
            return;
        }
        GiftRequestService.GiftRequest request = service.get(target.getUniqueId());
        if (request == null) {
            sendGiftUnavailable(target);
            return;
        }

        Player gifter = request.getGifterUuid() == null ? null : Bukkit.getPlayer(request.getGifterUuid());
        if (gifter == null || !gifter.isOnline()) {
            service.remove(target.getUniqueId());
            sendGiftUnavailable(target);
            return;
        }

        Profile gifterProfile = plugin.getProfile(gifter.getUniqueId());
        Profile targetProfile = plugin.getProfile(target.getUniqueId());
        if (gifterProfile == null || targetProfile == null) {
            target.sendMessage(GiftSupport.GIFT_LOADING_MESSAGE);
            return;
        }

        Rank giftedRank = GiftSupport.safeRank(request.getGiftedRank());
        Rank previousTargetRank = GiftSupport.safeRank(targetProfile.getRank());
        int safeCost = Math.max(0, request.getCostGold());
        int gifterGold = Math.max(0, gifterProfile.getNetworkGold());

        if (giftedRank != Rank.MVP_PLUS_PLUS && previousTargetRank.isAtLeast(giftedRank)) {
            service.remove(target.getUniqueId());
            sendGiftUnavailable(target);
            return;
        }
        if (giftedRank == Rank.MVP_PLUS_PLUS
                && previousTargetRank != Rank.MVP_PLUS
                && previousTargetRank != Rank.MVP_PLUS_PLUS) {
            service.remove(target.getUniqueId());
            sendGiftUnavailable(target);
            return;
        }
        if (gifterGold < safeCost) {
            service.remove(target.getUniqueId());
            sendGiftUnavailable(target);
            return;
        }

        if (safeCost > 0) {
            gifterProfile.setNetworkGold(gifterGold - safeCost);
        }
        GiftSupport.incrementGiftedRanks(gifterProfile);
        plugin.saveProfile(gifterProfile);

        ProfileCommandSyncService sync = plugin.getProfileCommandSyncService();
        Integer days = request.getMvpPlusPlusDays();
        if (giftedRank == Rank.MVP_PLUS_PLUS) {
            boolean upgradedRank = previousTargetRank == Rank.MVP_PLUS;
            if (upgradedRank) {
                plugin.setRank(target.getUniqueId(), Rank.MVP_PLUS_PLUS);
            }
            String targetMessage = ChatColor.GREEN + gifter.getName() + " gifted you MVP++"
                    + formatMvpPlusPlusDays(days) + ChatColor.GREEN + "!";
            target.sendMessage(targetMessage);
            if (upgradedRank && sync != null) {
                sync.dispatchRankUpdate(target.getUniqueId(), Rank.MVP_PLUS_PLUS, targetMessage);
            }
            GiftSupport.recordGiftHistoryAsync(plugin, gifter, target, previousTargetRank, Rank.MVP_PLUS_PLUS, safeCost, days);
        } else {
            plugin.setRank(target.getUniqueId(), giftedRank);
            String targetMessage = ChatColor.GREEN + gifter.getName() + " gifted you "
                    + GiftSupport.displayRank(giftedRank) + ChatColor.GREEN + "!";
            target.sendMessage(targetMessage);
            if (sync != null) {
                sync.dispatchRankUpdate(target.getUniqueId(), giftedRank, targetMessage);
            }
            GiftSupport.recordGiftHistoryAsync(plugin, gifter, target, previousTargetRank, giftedRank, safeCost, null);
        }

        service.remove(target.getUniqueId());

        Profile latestTargetProfile = plugin.getProfile(target.getUniqueId());
        String targetDisplay = GiftSupport.buildTargetNameWithRankColor(
                latestTargetProfile == null ? targetProfile : latestTargetProfile,
                target.getName()
        );
        String rankDisplay = GiftSupport.buildGiftRankText(giftedRank, days);
        gifter.sendMessage(targetDisplay + ChatColor.GREEN + " has chosen to accept your gift of "
                + rankDisplay + ChatColor.GREEN + "!");
        target.sendMessage(ChatColor.GREEN + "Accepted the gift!");
        announceAcceptedGift(plugin, gifter, target, giftedRank, days, gifterProfile, targetProfile);
    }

    public static void decline(CorePlugin plugin, Player target) {
        if (plugin == null || target == null) {
            return;
        }
        if (!isHubServer(plugin)) {
            target.sendMessage(ChatColor.RED + "You can only gift players in a lobby!");
            return;
        }
        GiftRequestService service = plugin.getGiftRequestService();
        if (service == null) {
            sendGiftUnavailable(target);
            return;
        }

        GiftRequestService.GiftRequest request = service.remove(target.getUniqueId());
        if (request == null) {
            sendGiftUnavailable(target);
            return;
        }

        Player gifter = request.getGifterUuid() == null ? null : Bukkit.getPlayer(request.getGifterUuid());
        if (gifter != null && gifter.isOnline()) {
            Profile targetProfile = plugin.getProfile(target.getUniqueId());
            String targetDisplay = GiftSupport.buildTargetNameWithRankColor(targetProfile, target.getName());
            String rankDisplay = GiftSupport.buildGiftRankText(request.getGiftedRank(), request.getMvpPlusPlusDays());
            gifter.sendMessage(targetDisplay + ChatColor.RED + " has chosen to decline your gift of "
                    + rankDisplay + ChatColor.RED + "!");
        }
        target.sendMessage(ChatColor.RED + "Declined the gift!");
    }

    private static void announceAcceptedGift(CorePlugin plugin,
                                             Player gifter,
                                             Player target,
                                             Rank giftedRank,
                                             Integer mvpPlusPlusDays,
                                             Profile fallbackGifterProfile,
                                             Profile fallbackTargetProfile) {
        if (plugin == null || gifter == null || target == null) {
            return;
        }
        Profile latestGifterProfile = plugin.getProfile(gifter.getUniqueId());
        Profile latestTargetProfile = plugin.getProfile(target.getUniqueId());
        Profile gifterProfile = latestGifterProfile == null ? fallbackGifterProfile : latestGifterProfile;
        Profile targetProfile = latestTargetProfile == null ? fallbackTargetProfile : latestTargetProfile;

        String gifterDisplay = GiftSupport.buildTargetDisplayName(gifterProfile, gifter.getName());
        String targetDisplay = GiftSupport.buildTargetNameWithRankColor(targetProfile, target.getName());
        String rankDisplay = GiftSupport.buildGiftRankName(giftedRank);
        String durationSegment = formatBroadcastDurationSegment(giftedRank, mvpPlusPlusDays);

        String start = ChatColor.YELLOW.toString() + ChatColor.MAGIC + "z"
                + ChatColor.RED + ChatColor.MAGIC + "z"
                + ChatColor.DARK_RED + ChatColor.MAGIC + "z";
        String end = ChatColor.DARK_RED.toString() + ChatColor.MAGIC + "z"
                + ChatColor.RED + ChatColor.MAGIC + "z"
                + ChatColor.YELLOW + ChatColor.MAGIC + "z";

        String lineOne = start
                + gifterDisplay
                + ChatColor.YELLOW + " gifted "
                + durationSegment
                + rankDisplay
                + ChatColor.YELLOW + " to "
                + targetDisplay
                + ChatColor.YELLOW + "!"
                + end;

        int giftedCount = GiftSupport.readGiftedRanks(gifterProfile);
        String rankWord = giftedCount == 1 ? "rank" : "ranks";
        String lineTwo = ChatColor.YELLOW + "They have gifted "
                + ChatColor.GOLD + giftedCount
                + ChatColor.YELLOW + " "
                + rankWord
                + " so far!";

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(lineOne);
        Bukkit.broadcastMessage(lineTwo);
        Bukkit.broadcastMessage("");

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            GiftSupport.playGiftAnnouncementSound(viewer);
        }
    }

    private static boolean isHubServer(CorePlugin plugin) {
        ServerType type = plugin == null ? null : plugin.getServerType();
        return type != null && type.isHub();
    }

    private static String formatMvpPlusPlusDays(Integer days) {
        Integer safeDays = days == null ? null : Math.max(0, days);
        if (safeDays == null || safeDays <= 0) {
            return "";
        }
        return ChatColor.GOLD + " " + safeDays + " Days";
    }

    private static String formatBroadcastDurationSegment(Rank giftedRank, Integer mvpPlusPlusDays) {
        Rank safe = GiftSupport.safeRank(giftedRank);
        Integer days = mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays);
        if (safe != Rank.MVP_PLUS_PLUS || days == null || days <= 0) {
            return "";
        }
        return ChatColor.GOLD.toString() + days + " Days" + ChatColor.YELLOW + " of ";
    }

    private static void sendGiftUnavailable(Player target) {
        if (target == null) {
            return;
        }
        target.sendMessage(GIFT_UNAVAILABLE_MESSAGE);
    }
}
