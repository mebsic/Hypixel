package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.proxy.service.PartyService;
import io.github.mebsic.proxy.service.RankResolver;
import io.github.mebsic.proxy.service.ServerRegistryService;
import io.github.mebsic.proxy.util.Components;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Optional;

public class BuildCommand implements SimpleCommand {
    private static final String BUILD_SERVER_ID = "build";

    private final ServerRegistryService registry;
    private final RankResolver rankResolver;
    private final PartyService parties;

    public BuildCommand(ServerRegistryService registry, RankResolver rankResolver) {
        this(registry, rankResolver, null);
    }

    public BuildCommand(ServerRegistryService registry, RankResolver rankResolver, PartyService parties) {
        this.registry = registry;
        this.rankResolver = rankResolver;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        if (rankResolver == null || !rankResolver.isStaff(player.getUniqueId())) {
            player.sendMessage(Component.text(CommonMessages.NO_PERMISSION_COMMAND, NamedTextColor.RED));
            return;
        }

        if (registry != null) {
            registry.refresh();
        }

        Optional<RegisteredServer> target = registry == null
                ? Optional.empty()
                : registry.findServerByRegistryId(BUILD_SERVER_ID);

        if (!target.isPresent()) {
            player.sendMessage(Component.text(CommonMessages.BUILD_SERVER_NOT_ONLINE, NamedTextColor.RED));
            return;
        }

        RegisteredServer targetServer = target.get();
        String targetName = targetServer.getServerInfo().getName();
        String currentServerName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse("");

        if (targetName.equalsIgnoreCase(currentServerName)) {
            player.sendMessage(Component.text("You are already on the build server!", NamedTextColor.RED));
            return;
        }

        player.createConnectionRequest(targetServer).fireAndForget();
        boolean suppressTransferMessage = parties != null
                && parties.isInParty(player.getUniqueId())
                && !parties.isLeader(player.getUniqueId());
        if (!suppressTransferMessage) {
            player.sendMessage(Components.transferToServer(targetName));
        }
    }
}
