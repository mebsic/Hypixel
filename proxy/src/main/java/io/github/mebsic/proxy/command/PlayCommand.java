package io.github.mebsic.proxy.command;

import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.proxy.service.ServerRegistryService;
import io.github.mebsic.proxy.service.PartyService;
import io.github.mebsic.proxy.config.ProxyConfig;
import io.github.mebsic.proxy.util.Components;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PlayCommand implements SimpleCommand {
    private static final String MURDER_CLASSIC_TOKEN = "murder_classic";

    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final ServerRegistryService registry;
    private final PartyService parties;

    public PlayCommand(ProxyServer proxy, ProxyConfig config, ServerRegistryService registry) {
        this(proxy, config, registry, null);
    }

    public PlayCommand(ProxyServer proxy, ProxyConfig config, ServerRegistryService registry, PartyService parties) {
        this.proxy = proxy;
        this.config = config;
        this.registry = registry;
        this.parties = parties;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return;
        }
        if (registry != null) {
            registry.refresh();
        }
        Player player = (Player) invocation.source();
        String[] args = invocation.arguments();
        if (args.length < 1 || args[0] == null || args[0].trim().isEmpty()) {
            sendUsage(player);
            return;
        }

        ServerType selectedType = resolveType(args[0]);
        if (!isPlayableGameType(selectedType)) {
            sendUnknownGameType(player);
            return;
        }

        String currentServerName = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
        int requiredOpenSlots = requiredOpenSlots(player);
        Optional<RegisteredServer> target = pickServer(selectedType, currentServerName, requiredOpenSlots);
        if (!target.isPresent()) {
            if (requiredOpenSlots > 1) {
                player.sendMessage(Component.text(
                        "No game servers are available with enough space for your party right now.",
                        NamedTextColor.RED
                ));
            } else {
                player.sendMessage(Component.text("There are no games available! Please try again later.", NamedTextColor.RED));
            }
            return;
        }
        RegisteredServer targetServer = target.get();
        String targetName = targetServer.getServerInfo().getName();
        player.createConnectionRequest(targetServer).fireAndForget();
        boolean suppressTransferMessage = parties != null
                && parties.isInParty(player.getUniqueId())
                && !parties.isLeader(player.getUniqueId());
        if (!suppressTransferMessage) {
            player.sendMessage(Components.transferToServer(targetName));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        String prefix = args.length == 0 ? "" : normalizeGameType(args[0]);
        List<String> options = new java.util.ArrayList<>();
        for (String token : suggestionTokens()) {
            if (prefix.isEmpty() || normalizeGameType(token).startsWith(prefix)) {
                options.add(token);
            }
        }
        return options;
    }

    private Optional<RegisteredServer> pickServer(ServerType type, String excludedServerName, int requiredOpenSlots) {
        if (registry == null || !isPlayableGameType(type)) {
            return Optional.empty();
        }
        return registry.findAvailableGameServer(type, excludedServerName, requiredOpenSlots);
    }

    private ServerType resolveType(String input) {
        String normalized = normalizeGameType(input);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map.Entry<ServerType, String> option : gameTypeOptions().entrySet()) {
            if (normalized.equals(normalizeGameType(option.getValue()))) {
                return option.getKey();
            }
        }
        for (ServerType type : ServerType.values()) {
            if (!isPlayableGameType(type)) {
                continue;
            }
            if (matchesTypeAlias(type, normalized)) {
                return type;
            }
        }
        ServerType resolved = ServerType.fromString(normalized.toUpperCase(Locale.ROOT));
        return isPlayableGameType(resolved) ? resolved : null;
    }

    private boolean matchesTypeAlias(ServerType type, String normalizedInput) {
        if (!isPlayableGameType(type) || normalizedInput == null || normalizedInput.isEmpty()) {
            return false;
        }
        return aliasesFor(type).contains(normalizedInput);
    }

    private void sendUsage(Player player) {
        player.sendMessage(Component.text("Invalid usage! Correct usage:", NamedTextColor.RED));
        player.sendMessage(Component.text("/play <gameType>", NamedTextColor.RED));
        player.sendMessage(Component.text("Available game types:", NamedTextColor.RED));
        sendAvailableGameTypes(player);
    }

    private void sendUnknownGameType(Player player) {
        player.sendMessage(Component.text("Unknown game type! Available game types:", NamedTextColor.RED));
        sendAvailableGameTypes(player);
    }

    private void sendAvailableGameTypes(Player player) {
        for (Map.Entry<ServerType, String> option : gameTypeOptions().entrySet()) {
            String token = option.getValue();
            player.sendMessage(Component.text(token, NamedTextColor.RED));
        }
    }

    private List<String> suggestionTokens() {
        Set<String> suggestions = new LinkedHashSet<>();
        for (Map.Entry<ServerType, String> option : gameTypeOptions().entrySet()) {
            suggestions.add(option.getValue());
        }
        return new java.util.ArrayList<>(suggestions);
    }

    private Map<ServerType, String> gameTypeOptions() {
        Map<ServerType, String> options = new LinkedHashMap<>();
        for (ServerType type : ServerType.values()) {
            if (!isPlayableGameType(type)) {
                continue;
            }
            options.put(type, canonicalGameTypeToken(type));
        }
        return options;
    }

    private boolean isPlayableGameType(ServerType type) {
        return type != null && type.isGame() && !type.isBuild() && type != ServerType.UNKNOWN;
    }

    private String canonicalGameTypeToken(ServerType type) {
        if (type == null) {
            return "game";
        }
        if (type == ServerType.MURDER_MYSTERY) {
            return MURDER_CLASSIC_TOKEN;
        }
        return normalizeGameType(baseGameTypeToken(type));
    }

    private String baseGameTypeToken(ServerType type) {
        if (type == null) {
            return "GAME";
        }
        String token = type.name();
        if (token.endsWith("_HUB") && token.length() > "_HUB".length()) {
            token = token.substring(0, token.length() - "_HUB".length());
        }
        if (token.endsWith("_GAME") && token.length() > "_GAME".length()) {
            token = token.substring(0, token.length() - "_GAME".length());
        }
        token = token.trim();
        return token.isEmpty() ? "GAME" : token;
    }

    private Set<String> aliasesFor(ServerType type) {
        Set<String> aliases = new LinkedHashSet<>();
        addAlias(aliases, canonicalGameTypeToken(type));
        addAlias(aliases, type.getId());
        addAlias(aliases, type.getId().replace("_", ""));
        addAlias(aliases, baseGameTypeToken(type));
        addAlias(aliases, baseGameTypeToken(type).replace("_", ""));

        String display = normalizeGameType(type.getGameTypeDisplayName());
        addAlias(aliases, display);
        addAlias(aliases, display.replace("_", ""));
        if (!display.isEmpty()) {
            String firstWord = display.split("_")[0];
            addAlias(aliases, firstWord);
            addAlias(aliases, firstWord + "_classic");
        }

        if (type == ServerType.MURDER_MYSTERY) {
            addAlias(aliases, MURDER_CLASSIC_TOKEN);
        }

        return aliases;
    }

    private void addAlias(Set<String> aliases, String raw) {
        String normalized = normalizeGameType(raw);
        if (!normalized.isEmpty()) {
            aliases.add(normalized);
        }
    }

    private String normalizeGameType(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private int requiredOpenSlots(Player player) {
        if (player == null) {
            return 1;
        }
        if (parties == null || !parties.isLeader(player.getUniqueId())) {
            return 1;
        }
        int additionalOnlineMembers = 0;
        for (java.util.UUID memberId : parties.getMembers(player.getUniqueId())) {
            if (memberId == null || memberId.equals(player.getUniqueId())) {
                continue;
            }
            if (proxy.getPlayer(memberId).isPresent()) {
                additionalOnlineMembers++;
            }
        }
        return 1 + additionalOnlineMembers;
    }

    private void sendToHub(Player player) {
        if (registry != null) {
            List<RegisteredServer> hubs = registry.getHubServers();
            if (!hubs.isEmpty()) {
                player.createConnectionRequest(hubs.get(0)).fireAndForget();
                return;
            }
        }
        String hubServer = config == null ? null : config.getHubServer();
        if (hubServer != null && !hubServer.trim().isEmpty()) {
            proxy.getServer(config.getHubServer()).ifPresent(server ->
                    player.createConnectionRequest(server).fireAndForget());
        }
    }
}
