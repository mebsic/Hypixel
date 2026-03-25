package io.github.mebsic.proxy.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.HubMessageUtil;
import io.github.mebsic.proxy.config.ProxyConfig;
import io.github.mebsic.proxy.service.ServerRegistryService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class HubCommand implements SimpleCommand {
    private static final int UNKNOWN_LOBBY_NUMBER = Integer.MAX_VALUE;

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ServerRegistryService registry;

    public HubCommand(ProxyServer proxy, ProxyConfig config, ServerRegistryService registry) {
        this.proxy = proxy;
        this.config = config;
        this.registry = registry;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        if (registry != null) {
            registry.refresh();
        }

        String currentServerName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
        ServerType sourceType = resolveSourceType(currentServerName);
        List<RegisteredServer> availableHubs = listHubTargets(sourceType);

        Optional<RegisteredServer> target = resolveHubTarget(sourceType, currentServerName);
        if (!target.isPresent()) {
            player.sendMessage(Component.text(CommonMessages.NO_SERVERS_AVAILABLE, NamedTextColor.RED));
            return;
        }

        connectToHub(player, sourceType, currentServerName, target.get(), availableHubs);
    }

    private void connectToHub(Player player,
                              ServerType sourceType,
                              String currentServerName,
                              RegisteredServer targetServer,
                              List<RegisteredServer> availableHubs) {
        if (player == null || targetServer == null) {
            return;
        }
        String targetName = targetServer.getServerInfo().getName();
        if (currentServerName != null && currentServerName.equalsIgnoreCase(targetName)) {
            int lobbyNumber = resolveLobbyNumber(targetName, availableHubs);
            if (lobbyNumber > 0) {
                String hubLabel = HubMessageUtil.hubDisplayName(resolveMessageType(sourceType, targetName));
                player.sendMessage(Component.text(
                        "You are already in " + hubLabel + " #" + lobbyNumber,
                        NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text(
                    HubMessageUtil.alreadyInHubMessage(resolveMessageType(sourceType, targetName), targetName),
                    NamedTextColor.RED));
            return;
        }

        player.createConnectionRequest(targetServer).fireAndForget();
    }

    private ServerType resolveSourceType(String currentServerName) {
        if (registry == null || currentServerName == null || currentServerName.trim().isEmpty()) {
            return ServerType.UNKNOWN;
        }
        return registry.findServerType(currentServerName).orElse(ServerType.UNKNOWN);
    }

    private Optional<RegisteredServer> resolveHubTarget(ServerType sourceType, String currentServerName) {
        if (registry != null) {
            Optional<RegisteredServer> matched = registry.findHubServerFor(sourceType, currentServerName);
            if (matched.isPresent()) {
                return matched;
            }
            matched = registry.findHubServerFor(sourceType);
            if (matched.isPresent()) {
                return matched;
            }
        }

        String fallbackHub = config == null ? null : config.getHubServer();
        if (fallbackHub == null || fallbackHub.trim().isEmpty()) {
            return Optional.empty();
        }
        return proxy.getServer(fallbackHub.trim());
    }

    private ServerType resolveMessageType(ServerType sourceType, String serverName) {
        if (sourceType != null && sourceType != ServerType.UNKNOWN) {
            return sourceType;
        }
        if (registry == null || serverName == null || serverName.trim().isEmpty()) {
            return ServerType.UNKNOWN;
        }
        return registry.findServerType(serverName).orElse(ServerType.UNKNOWN);
    }

    private List<RegisteredServer> listHubTargets(ServerType sourceType) {
        if (registry == null) {
            return new ArrayList<>();
        }
        List<RegisteredServer> all = registry.getHubServers();
        if (all.isEmpty()) {
            return all;
        }

        ServerType preferredHubType = sourceType == null ? ServerType.UNKNOWN : sourceType.toHubType();
        List<RegisteredServer> preferred = new ArrayList<>();
        if (preferredHubType != null && preferredHubType != ServerType.UNKNOWN) {
            for (RegisteredServer hub : all) {
                if (hub == null) {
                    continue;
                }
                String name = hub.getServerInfo().getName();
                ServerType type = registry.findServerType(name).orElse(ServerType.UNKNOWN);
                if (type == preferredHubType) {
                    preferred.add(hub);
                }
            }
        }

        List<RegisteredServer> scoped = preferred.isEmpty() ? new ArrayList<>(all) : preferred;
        scoped.sort(Comparator
                .comparingInt((RegisteredServer server) -> lobbySortNumber(server.getServerInfo().getName()))
                .thenComparing(server -> server.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER));
        return scoped;
    }

    private int lobbySortNumber(String serverName) {
        int number = HubMessageUtil.extractLobbyNumber(serverName);
        return number > 0 ? number : UNKNOWN_LOBBY_NUMBER;
    }

    private int resolveLobbyNumber(String serverName, List<RegisteredServer> hubs) {
        if (serverName == null || serverName.trim().isEmpty() || hubs == null || hubs.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < hubs.size(); i++) {
            RegisteredServer hub = hubs.get(i);
            if (hub == null) {
                continue;
            }
            String candidate = hub.getServerInfo().getName();
            if (candidate.equalsIgnoreCase(serverName)) {
                return i + 1;
            }
        }
        return -1;
    }
}
