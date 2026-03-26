package io.github.mebsic.core.menu;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.book.GiftDecisionBookPrompt;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.service.GiftRequestService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class GiftConfirmMenu extends Menu {
    private static final String TITLE = "Confirm Gifting";
    private static final int SIZE = 27;
    private static final int CONFIRM_SLOT = 11;
    private static final int CANCEL_SLOT = 15;
    private static final long BOOK_OPEN_RETRY_DELAY_TICKS = 2L;

    private final CorePlugin plugin;
    private final UUID targetUuid;
    private final String targetName;
    private final Rank giftedRank;
    private final int costGold;
    private final Integer mvpPlusPlusDays;

    public GiftConfirmMenu(CorePlugin plugin, Player target, Rank giftedRank, int costGold) {
        this(plugin, target, giftedRank, costGold, null);
    }

    public GiftConfirmMenu(CorePlugin plugin, Player target, int mvpPlusPlusDays, int costGold) {
        this(plugin, target, Rank.MVP_PLUS_PLUS, costGold, Math.max(0, mvpPlusPlusDays));
    }

    private GiftConfirmMenu(CorePlugin plugin, Player target, Rank giftedRank, int costGold, Integer mvpPlusPlusDays) {
        super(TITLE, SIZE);
        this.plugin = plugin;
        this.targetUuid = target == null ? null : target.getUniqueId();
        this.targetName = target == null ? "" : target.getName();
        this.giftedRank = giftedRank == null ? Rank.DEFAULT : giftedRank;
        this.costGold = Math.max(0, costGold);
        this.mvpPlusPlusDays = mvpPlusPlusDays == null ? null : Math.max(0, mvpPlusPlusDays);
    }

    @Override
    protected void populate(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        inventory.clear();
        set(inventory, CONFIRM_SLOT, buildConfirmItem());
        set(inventory, CANCEL_SLOT, buildCancelItem());
    }

    @Override
    public void onClick(MenuClick click) {
        if (click == null || click.getPlayer() == null) {
            return;
        }
        Player sender = click.getPlayer();
        int slot = click.getRawSlot();
        if (slot == CANCEL_SLOT) {
            openPreviousMenu(sender);
            return;
        }
        if (slot != CONFIRM_SLOT) {
            return;
        }
        if (mvpPlusPlusDays != null && mvpPlusPlusDays > 0) {
            confirmMvpPlusPlusGift(sender, mvpPlusPlusDays, costGold);
            return;
        }
        confirmRankGift(sender, giftedRank, costGold);
    }

    private ItemStack buildConfirmItem() {
        return GiftSupport.buildHardenedClayButton(true, ChatColor.GREEN + "Confirm gifting", null);
    }

    private ItemStack buildCancelItem() {
        return GiftSupport.buildHardenedClayButton(false, ChatColor.RED + "Cancel", null);
    }

    private void openPreviousMenu(Player sender) {
        if (sender == null) {
            return;
        }
        Player target = resolveTargetSilently();
        if (target == null) {
            sender.closeInventory();
            return;
        }
        if (mvpPlusPlusDays != null && mvpPlusPlusDays > 0) {
            new GiftMvpPlusPlusMenu(plugin, target).open(sender);
            return;
        }
        new GiftSelectMenu(plugin, target).open(sender);
    }

    private Player resolveTargetSilently() {
        if (targetUuid == null) {
            return null;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            return null;
        }
        return target;
    }

    private void confirmRankGift(Player sender, Rank rankToGift, int goldCost) {
        if (sender == null || rankToGift == null || plugin == null) {
            return;
        }
        if (!isHubServer()) {
            sender.sendMessage(ChatColor.RED + "You can only gift players in a lobby!");
            return;
        }
        Player target = resolveTargetOrMessage(sender);
        if (target == null) {
            sender.closeInventory();
            return;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't send gifts to yourself!");
            sender.closeInventory();
            return;
        }

        Profile senderProfile = plugin.getProfile(sender.getUniqueId());
        if (senderProfile == null) {
            sender.sendMessage(GiftSupport.PROFILE_LOADING_SELF_MESSAGE);
            return;
        }
        Profile targetProfile = plugin.getProfile(target.getUniqueId());
        if (targetProfile == null) {
            sender.sendMessage(GiftSupport.PROFILE_LOADING_TARGET_MESSAGE);
            return;
        }

        Rank currentRank = GiftSupport.safeRank(targetProfile.getRank());
        if (currentRank.isAtLeast(rankToGift)) {
            return;
        }

        int senderGold = Math.max(0, senderProfile.getNetworkGold());
        int safeCost = Math.max(0, goldCost);
        if (senderGold < safeCost) {
            return;
        }

        GiftRequestService requestService = plugin.getGiftRequestService();
        if (requestService == null) {
            return;
        }
        GiftRequestService.GiftRequest request = new GiftRequestService.GiftRequest(
                sender.getUniqueId(),
                sender.getName(),
                target.getUniqueId(),
                target.getName(),
                rankToGift,
                safeCost,
                null,
                System.currentTimeMillis()
        );
        if (!requestService.queue(request)) {
            sender.sendMessage(ChatColor.RED + "That player already has a pending gift request!");
            return;
        }
        if (!scheduleDecisionBookDelivery(sender, targetProfile, requestService, request)) {
            requestService.remove(target.getUniqueId());
            sender.sendMessage(ChatColor.RED + "Unable to deliver the gift request right now!");
        }
    }

    private void confirmMvpPlusPlusGift(Player sender, int days, int goldCost) {
        if (sender == null || plugin == null) {
            return;
        }
        if (!isHubServer()) {
            sender.sendMessage(ChatColor.RED + "You can only gift players in a lobby!");
            return;
        }
        Player target = resolveTargetOrMessage(sender);
        if (target == null) {
            sender.closeInventory();
            return;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't send gifts to yourself!");
            sender.closeInventory();
            return;
        }

        Profile senderProfile = plugin.getProfile(sender.getUniqueId());
        if (senderProfile == null) {
            sender.sendMessage(GiftSupport.PROFILE_LOADING_SELF_MESSAGE);
            return;
        }
        Profile targetProfile = plugin.getProfile(target.getUniqueId());
        if (targetProfile == null) {
            sender.sendMessage(GiftSupport.PROFILE_LOADING_TARGET_MESSAGE);
            return;
        }

        Rank previousRank = GiftSupport.safeRank(targetProfile.getRank());
        boolean eligible = previousRank == Rank.MVP_PLUS || previousRank == Rank.MVP_PLUS_PLUS;
        if (!eligible) {
            sender.sendMessage(ChatColor.RED + "That player must be MVP+ or MVP++ to receive this gift!");
            return;
        }

        int senderGold = Math.max(0, senderProfile.getNetworkGold());
        int safeCost = Math.max(0, goldCost);
        if (senderGold < safeCost) {
            return;
        }

        GiftRequestService requestService = plugin.getGiftRequestService();
        if (requestService == null) {
            return;
        }
        GiftRequestService.GiftRequest request = new GiftRequestService.GiftRequest(
                sender.getUniqueId(),
                sender.getName(),
                target.getUniqueId(),
                target.getName(),
                Rank.MVP_PLUS_PLUS,
                safeCost,
                days,
                System.currentTimeMillis()
        );
        if (!requestService.queue(request)) {
            sender.sendMessage(ChatColor.RED + "That player already has a pending gift request!");
            return;
        }
        if (!scheduleDecisionBookDelivery(sender, targetProfile, requestService, request)) {
            requestService.remove(target.getUniqueId());
            sender.sendMessage(ChatColor.RED + "Unable to deliver the gift request right now!");
        }
    }

    private Player resolveTargetOrMessage(Player sender) {
        if (targetUuid == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player '" + targetName + "'!");
            return null;
        }
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ChatColor.RED + "That player is not in your lobby!");
            return null;
        }
        return target;
    }

    private boolean isHubServer() {
        ServerType type = plugin == null ? null : plugin.getServerType();
        return type != null && type.isHub();
    }

    private boolean openDecisionBook(Player target, GiftRequestService.GiftRequest request) {
        if (plugin == null || target == null || request == null || !target.isOnline()) {
            return false;
        }
        if (plugin.getBookPromptService() == null) {
            return false;
        }
        GiftDecisionBookPrompt prompt = new GiftDecisionBookPrompt(
                target.getUniqueId(),
                request.getGifterUuid(),
                request.getGifterName(),
                request.getGiftedRank(),
                request.getMvpPlusPlusDays()
        );
        return plugin.getBookPromptService().openPrompt(target, prompt);
    }

    private boolean scheduleDecisionBookDelivery(Player sender,
                                                 Profile fallbackTargetProfile,
                                                 GiftRequestService requestService,
                                                 GiftRequestService.GiftRequest request) {
        if (plugin == null || !plugin.isEnabled() || sender == null || requestService == null || request == null) {
            return false;
        }
        plugin.getServer().getScheduler().runTask(plugin, () ->
                attemptDecisionBookDelivery(sender, fallbackTargetProfile, requestService, request, false)
        );
        return true;
    }

    private void attemptDecisionBookDelivery(Player sender,
                                             Profile fallbackTargetProfile,
                                             GiftRequestService requestService,
                                             GiftRequestService.GiftRequest request,
                                             boolean retrying) {
        if (plugin == null || requestService == null || request == null) {
            return;
        }
        UUID targetUuid = request.getTargetUuid();
        if (targetUuid == null) {
            failDecisionBookDelivery(sender, requestService, null);
            return;
        }
        Player liveTarget = Bukkit.getPlayer(targetUuid);
        if (liveTarget == null || !liveTarget.isOnline()) {
            failDecisionBookDelivery(sender, requestService, targetUuid);
            return;
        }
        if (openDecisionBook(liveTarget, request)) {
            if (sender.isOnline()) {
                Profile latestTargetProfile = plugin.getProfile(liveTarget.getUniqueId());
                String targetDisplay = GiftSupport.buildTargetNameWithRankColor(
                        latestTargetProfile == null ? fallbackTargetProfile : latestTargetProfile,
                        liveTarget.getName()
                );
                sender.sendMessage(ChatColor.GREEN + "You have sent a gift to " + targetDisplay + ChatColor.GREEN + "!");
                sender.closeInventory();
            }
            return;
        }
        if (!retrying && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> attemptDecisionBookDelivery(sender, fallbackTargetProfile, requestService, request, true),
                    BOOK_OPEN_RETRY_DELAY_TICKS
            );
            return;
        }
        failDecisionBookDelivery(sender, requestService, targetUuid);
    }

    private void failDecisionBookDelivery(Player sender,
                                          GiftRequestService requestService,
                                          UUID targetUuid) {
        if (requestService != null && targetUuid != null) {
            requestService.remove(targetUuid);
        }
        if (sender != null && sender.isOnline()) {
            sender.sendMessage(ChatColor.RED + "Unable to deliver the gift request right now!");
        }
    }
}
