package io.github.mebsic.proxy;

import com.google.inject.Inject;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.DomainSettingsStore;
import io.github.mebsic.core.util.HubMessageUtil;
import io.github.mebsic.core.util.NetworkConstants;
import io.github.mebsic.proxy.cache.MotdCache;
import io.github.mebsic.proxy.command.BlockCommand;
import io.github.mebsic.proxy.command.BuildCommand;
import io.github.mebsic.proxy.command.CancelRestartCommand;
import io.github.mebsic.proxy.command.CancelUpdateCommand;
import io.github.mebsic.proxy.command.ChatCommand;
import io.github.mebsic.proxy.command.FriendCommand;
import io.github.mebsic.proxy.command.FriendMessageCommand;
import io.github.mebsic.proxy.command.HubCommand;
import io.github.mebsic.proxy.command.MaintenanceCommand;
import io.github.mebsic.proxy.command.PartyChatCommand;
import io.github.mebsic.proxy.command.PartyCommand;
import io.github.mebsic.proxy.command.PlayCommand;
import io.github.mebsic.proxy.command.RestartCommand;
import io.github.mebsic.proxy.command.StaffChatCommand;
import io.github.mebsic.proxy.command.UpdateCommand;
import io.github.mebsic.proxy.config.ConfigLoader;
import io.github.mebsic.proxy.config.ProxyConfig;
import io.github.mebsic.proxy.manager.MongoManager;
import io.github.mebsic.proxy.service.ServerRegistryService;
import io.github.mebsic.proxy.service.BlockService;
import io.github.mebsic.proxy.service.ChatChannelService;
import io.github.mebsic.proxy.service.ChatMessageService;
import io.github.mebsic.proxy.service.ChatRestrictionService;
import io.github.mebsic.proxy.service.FriendService;
import io.github.mebsic.proxy.service.PartyService;
import io.github.mebsic.proxy.service.QueueOrchestrator;
import io.github.mebsic.proxy.service.RankResolver;
import io.github.mebsic.proxy.service.StaffChatService;
import io.github.mebsic.proxy.util.Components;
import io.github.mebsic.proxy.util.PartyComponents;
import io.github.mebsic.core.manager.RedisManager;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.Favicon;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bson.Document;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import redis.clients.jedis.Jedis;

@Plugin(id = "hypixelproxy", name = "HypixelProxy", version = "1.0.0")
public class HypixelProxyPlugin {
    public static final String PROXY_RESTARTING_MESSAGE = "This proxy is restarting";
    public static final String PROXY_RECONNECT_TO_MESSAGE = "Please reconnect to ";
    private static final int MAINTENANCE_VERSION_PROTOCOL = -1;
    private static final String MAINTENANCE_VERSION_TEXT = LegacyComponentSerializer.legacySection()
            .serialize(Component.text("Maintenance", NamedTextColor.DARK_RED));
    private static final String DISABLED_PROXY_COMMAND = "server";
    private static final long INFRA_HEALTH_CHECK_SECONDS = 1L;
    private static final long FAILOVER_SHUTDOWN_DELAY_MILLIS = 200L;
    private static final int INFRA_FAILURES_BEFORE_FAILOVER = 10;
    private static final long INFRA_FAILURE_GRACE_PERIOD_MILLIS = 20_000L;
    private static final long INFRA_FAILURE_SUSTAINED_MILLIS_BEFORE_FAILOVER = 60_000L;
    private static final long MONGO_CONNECT_TIMEOUT_SECONDS = 3L;
    private static final long MONGO_SOCKET_TIMEOUT_SECONDS = 5L;
    private static final long MONGO_SERVER_SELECTION_TIMEOUT_SECONDS = 3L;
    private static final long PARTY_FOLLOW_RETRY_DELAY_MILLIS = 750L;
    private static final long DEFERRED_POST_GAME_QUEUE_EXPIRY_MILLIS = 5L * 60L * 1000L;
    private static final long PLAY_AGAIN_INTENT_WINDOW_MILLIS = 7_500L;
    private static final MinecraftChannelIdentifier PLAY_AGAIN_INTENT_CHANNEL =
            MinecraftChannelIdentifier.from("hypixel:playagain");
    private static final Component NON_1_8_DISCONNECT_REASON = Component.text(
            "Please connect using Minecraft version ",
            NamedTextColor.RED
    ).append(Component.text("1.8", NamedTextColor.RED))
            .append(Component.text("!", NamedTextColor.RED));
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private ProxyConfig config;
    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;
    private RedisManager redis;
    private MotdCache motdCache;
    private Favicon favicon;
    private ServerRegistryService registryService;
    private QueueOrchestrator queueOrchestrator;
    private RankResolver rankResolver;
    private FriendService friendService;
    private BlockService blockService;
    private ChatChannelService chatChannelService;
    private ChatMessageService chatMessageService;
    private ChatRestrictionService chatRestrictionService;
    private PartyService partyService;
    private StaffChatService staffChatService;
    private UpdateCommand updateCommand;
    private final AtomicBoolean infrastructureFailure = new AtomicBoolean(false);
    private final AtomicInteger mongoHealthFailures = new AtomicInteger(0);
    private final AtomicInteger redisHealthFailures = new AtomicInteger(0);
    private final AtomicLong mongoFirstFailureAtMillis = new AtomicLong(0L);
    private final AtomicLong redisFirstFailureAtMillis = new AtomicLong(0L);
    private volatile long infraHealthChecksStartedAtMillis;
    private static volatile String domainToConnect = "";
    private static volatile boolean connectUsingDomain = false;
    private final Map<UUID, DeferredQueueRequest> deferredPostGameQueueRequests = new ConcurrentHashMap<UUID, DeferredQueueRequest>();
    private final Map<UUID, Long> recentPlayAgainIntents = new ConcurrentHashMap<UUID, Long>();

    @Inject
    public HypixelProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        try {
            loadConfig();
            setupMongo();
            ensureProxyCollections();
            startDomainRefreshTask();
            setupRedis();
            this.motdCache = new MotdCache(mongoDatabase, config);
            try {
                // Warm cache at startup so the first ping has database-backed MOTD values.
                motdCache.refresh();
                motdCache.finishRefresh();
            } catch (Exception ex) {
                logger.warn("Initial MOTD cache warmup failed: {}", ex.getMessage());
            }
            this.favicon = loadFavicon();
            this.registryService = new ServerRegistryService(proxy, config, mongoDatabase);
            this.registryService.refresh();
            this.rankResolver = new RankResolver(mongoDatabase);
            String friendsCollection = config == null ? null : config.getFriendsCollection();
            this.friendService = new FriendService(proxy, mongoDatabase, friendsCollection, rankResolver, registryService, redis);
            this.blockService = new BlockService(mongoDatabase, friendsCollection, redis, rankResolver);
            this.friendService.bootstrapOnlinePlayers();
            this.blockService.bootstrapOnlinePlayers(proxy);
            this.chatChannelService = new ChatChannelService(mongoDatabase);
            this.chatChannelService.bootstrapOnlinePlayers(proxy);
            this.chatRestrictionService = new ChatRestrictionService(mongoDatabase);
            this.chatMessageService = new ChatMessageService(mongoDatabase, rankResolver);
            this.partyService = new PartyService(proxy, rankResolver);
            this.partyService.setMemberRemovedFromPartyListener(memberId -> {
                if (chatChannelService != null) {
                    chatChannelService.setChannel(memberId, ChatChannelService.ChatChannel.ALL);
                }
            });
            this.staffChatService = new StaffChatService(proxy, rankResolver, chatMessageService);
            CommandManager commands = proxy.getCommandManager();
            commands.unregister(DISABLED_PROXY_COMMAND);
            commands.unregister("msg");
            commands.unregister("tell");
            commands.unregister("message");
            commands.unregister("w");
            commands.unregister("whisper");
            commands.register("play", new PlayCommand(proxy, registryService, partyService));
            commands.register("build", new BuildCommand(registryService, rankResolver, partyService));
            HubCommand hubCommand = new HubCommand(proxy, config, registryService);
            commands.register("hub", hubCommand);
            commands.register("h", hubCommand);
            commands.register("l", hubCommand);
            commands.register("lobby", hubCommand);
            commands.register("maintenance", new MaintenanceCommand(proxy, this, config, mongoDatabase, motdCache, rankResolver));
            this.updateCommand = new UpdateCommand(proxy, this, rankResolver, logger);
            commands.register("update", this.updateCommand);
            commands.register("cancelupdate", new CancelUpdateCommand(this.updateCommand, rankResolver));
            RestartCommand restartCommand = new RestartCommand(proxy, this, rankResolver, registryService, logger);
            commands.register("restart", restartCommand);
            commands.register("cancelrestart", new CancelRestartCommand(restartCommand, rankResolver));
            FriendCommand friendCommand = new FriendCommand(proxy, friendService, blockService, "friend");
            FriendCommand friendAliasCommand = new FriendCommand(proxy, friendService, blockService, "f");
            BlockCommand blockCommand = new BlockCommand(proxy, blockService, friendService);
            FriendMessageCommand friendMessageCommand = new FriendMessageCommand(proxy, friendService, blockService, rankResolver);
            PartyCommand partyCommand = new PartyCommand(
                    proxy,
                    partyService,
                    rankResolver,
                    registryService,
                    blockService,
                    chatRestrictionService,
                    "party",
                    chatMessageService
            );
            PartyCommand partyAliasCommand = new PartyCommand(
                    proxy,
                    partyService,
                    rankResolver,
                    registryService,
                    blockService,
                    chatRestrictionService,
                    "p",
                    chatMessageService
            );
            PartyChatCommand partyChatCommand = new PartyChatCommand(
                    partyService,
                    rankResolver,
                    blockService,
                    chatRestrictionService,
                    chatMessageService
            );
            ChatCommand chatCommand = new ChatCommand(chatChannelService, partyService);
            StaffChatCommand staffChatCommand = new StaffChatCommand(staffChatService);
            commands.register("friend", friendCommand);
            commands.register("f", friendAliasCommand);
            commands.register("block", blockCommand);
            commands.register("msg", friendMessageCommand);
            commands.register("tell", friendMessageCommand);
            commands.register("message", friendMessageCommand);
            commands.register("w", friendMessageCommand);
            commands.register("whisper", friendMessageCommand);
            commands.register("party", partyCommand);
            commands.register("p", partyAliasCommand);
            commands.register("pchat", partyChatCommand);
            commands.register("pc", partyChatCommand);
            commands.register("chat", chatCommand);
            commands.register("staffchat", staffChatCommand);
            commands.register("sc", staffChatCommand);
            proxy.getChannelRegistrar().register(PLAY_AGAIN_INTENT_CHANNEL);
            int refresh = config == null ? 1 : Math.max(1, config.getRegistryRefreshSeconds());
            proxy.getScheduler().buildTask(this, () -> {
                if (registryService != null) {
                    registryService.refresh();
                }
            }).repeat(refresh, TimeUnit.SECONDS).schedule();
            proxy.getScheduler().buildTask(this, () -> {
                if (friendService != null) {
                    friendService.expirePendingRequests();
                }
            }).repeat(1, TimeUnit.SECONDS).schedule();
            proxy.getScheduler().buildTask(this, () -> {
                if (partyService != null) {
                    partyService.expirePendingInvites();
                    partyService.expireOfflineMembers();
                    partyService.tickPolls();
                }
            }).repeat(1, TimeUnit.SECONDS).schedule();
            proxy.getScheduler().buildTask(this, this::processDeferredPostGameQueues)
                    .repeat(1, TimeUnit.SECONDS)
                    .schedule();
            if (redis != null) {
                this.queueOrchestrator = new QueueOrchestrator(proxy, registryService, redis, partyService);
                this.queueOrchestrator.start();
                proxy.getScheduler().buildTask(this, () -> {
                    if (queueOrchestrator != null) {
                        queueOrchestrator.processAssignments();
                    }
                }).repeat(250, TimeUnit.MILLISECONDS).schedule();
            }
            startInfrastructureHealthMonitor();
        } catch (Exception ex) {
            triggerInfrastructureFailover("startup", ex);
        }
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        if (motdCache == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!motdCache.hasRefreshed()) {
            if (motdCache.startRefresh()) {
                try {
                    motdCache.refresh();
                } catch (Exception ex) {
                    logger.warn(
                            "MOTD cache refresh failed: {}",
                            ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    );
                } finally {
                    motdCache.finishRefresh();
                }
            }
        } else if (motdCache.shouldRefresh(now) && motdCache.startRefresh()) {
            proxy.getScheduler().buildTask(this, () -> {
                try {
                    motdCache.refresh();
                } catch (Exception ex) {
                    logger.warn(
                            "MOTD cache refresh failed: {}",
                            ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
                    );
                } finally {
                    motdCache.finishRefresh();
                }
            }).schedule();
        }
        boolean maintenance = motdCache.isMaintenanceEnabled();
        String first = maintenance ? motdCache.getMaintenanceMotdFirstLine() : motdCache.getMotdFirstLine();
        String second = maintenance ? motdCache.getMaintenanceMotdSecondLine() : motdCache.getMotdSecondLine();
        String motd = first == null ? "" : first;
        if (second != null && !second.trim().isEmpty()) {
            motd = motd + "\n" + second;
        }
        ServerPing ping = event.getPing();
        ServerPing.Builder builder = ping.asBuilder();
        Component desc = LegacyComponentSerializer.legacySection().deserialize(motd);
        builder.description(desc);
        if (maintenance) {
            builder.version(new ServerPing.Version(MAINTENANCE_VERSION_PROTOCOL, MAINTENANCE_VERSION_TEXT));
        }
        Integer configuredMaxPlayers = motdCache.getMaxPlayers();
        if (configuredMaxPlayers != null) {
            builder.maximumPlayers(configuredMaxPlayers);
            ping.getPlayers().ifPresent(players -> {
                if (players.getOnline() > configuredMaxPlayers) {
                    builder.onlinePlayers(configuredMaxPlayers);
                }
            });
        }
        if (favicon != null) {
            builder.favicon(favicon);
        }
        event.setPing(builder.build());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (event == null || !PLAY_AGAIN_INTENT_CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        cleanupExpiredPlayAgainIntents(System.currentTimeMillis());
        UUID playerId = readPlayAgainIntentPlayerId(event.getData());
        if (playerId != null) {
            recentPlayAgainIntents.put(playerId, System.currentTimeMillis());
        }
    }

    @Subscribe
    public void onKick(KickedFromServerEvent event) {
        String sourceServerName = event.getServer().getServerInfo().getName();
        String reason = kickReason(event);
        if (isConnectionThrottle(reason)) {
            disconnectWithKickReason(event);
            return;
        }
        if (isPunishmentDisconnect(reason)) {
            disconnectWithKickReason(event);
            return;
        }
        ServerType sourceType = ServerType.UNKNOWN;
        boolean transferAfterGameEnd = false;
        if (registryService != null) {
            sourceType = registryService.findServerType(sourceServerName).orElse(ServerType.UNKNOWN);
            transferAfterGameEnd = sourceType.isGame() && shouldTransferToNextGame(event);
            if (transferAfterGameEnd) {
                UUID playerId = event.getPlayer().getUniqueId();
                if (shouldRedirectPostGamePartyMemberToLobby(playerId, sourceServerName)) {
                    deferredPostGameQueueRequests.remove(playerId);
                    java.util.Optional<RegisteredServer> hub =
                            registryService.findHubServerFor(sourceType, sourceServerName);
                    if (hub.isPresent()) {
                        event.setResult(KickedFromServerEvent.RedirectPlayer.create(hub.get()));
                        return;
                    }
                    if (!redirectToFallbackHub(event, sourceServerName, sourceType, true)) {
                        String hubName = HubMessageUtil.hubDisplayName(sourceType);
                        Component reasonComponent = Component.text(
                                "No available servers were found. " + hubName + " is currently unavailable.",
                                NamedTextColor.RED
                        );
                        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reasonComponent));
                    }
                    return;
                }
                java.util.Optional<RegisteredServer> next =
                        registryService.findAvailableGameServer(sourceType, sourceServerName);
                if (next.isPresent() && !isSameServer(sourceServerName, next.get())) {
                    redirectPartyMembersFromSource(event.getPlayer().getUniqueId(), sourceServerName, next.get());
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(next.get()));
                    return;
                }
                java.util.Optional<RegisteredServer> hub =
                        registryService.findHubServerFor(sourceType, sourceServerName);
                if (hub.isPresent()) {
                    event.getPlayer().sendMessage(noServersRedirectMessage(sourceType));
                    redirectPartyMembersFromSource(event.getPlayer().getUniqueId(), sourceServerName, hub.get());
                    event.setResult(KickedFromServerEvent.RedirectPlayer.create(hub.get()));
                    return;
                }
            }
            java.util.Optional<RegisteredServer> hub =
                    registryService.findHubServerFor(sourceType, sourceServerName);
            if (hub.isPresent()) {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(hub.get()));
                return;
            }
        }
        if (!redirectToFallbackHub(event, sourceServerName, sourceType, transferAfterGameEnd)) {
            if (transferAfterGameEnd) {
                String hubName = HubMessageUtil.hubDisplayName(sourceType);
                Component reasonComponent = Component.text(
                        "No available servers were found. " + hubName + " is currently unavailable.",
                        NamedTextColor.RED
                );
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reasonComponent));
                return;
            }
            disconnectWithKickReason(event);
        }
    }

    @Subscribe
    public void onInitialServer(PlayerChooseInitialServerEvent event) {
        if (isUpdateJoinLockActive()) {
            event.getPlayer().disconnect(updateJoinLockReason());
            return;
        }

        if (connectUsingDomain) {
            String expectedConnectHost = domainToConnect;
            if (shouldRejectJoinHost(event.getPlayer(), expectedConnectHost)) {
                event.getPlayer().disconnect(domainJoinBlockedReason(expectedConnectHost));
                return;
            }
        }

        if (motdCache != null && motdCache.isMaintenanceEnabled()) {
            UUID playerId = event.getPlayer().getUniqueId();
            boolean isStaff = rankResolver != null && rankResolver.isStaff(playerId);
            if (!isStaff) {
                event.getPlayer().disconnect(Component.text(
                        "This server is currently in maintenance mode!",
                        NamedTextColor.GOLD));
                return;
            }
        }

        if (registryService != null) {
            // Force a fresh read so newly registered servers are considered on first join.
            registryService.refresh();
            ServerType preferredType = event.getInitialServer()
                    .map(server -> server.getServerInfo().getName())
                    .map(name -> registryService.findServerType(name).orElse(ServerType.UNKNOWN))
                    .orElse(ServerType.UNKNOWN);

            java.util.Optional<RegisteredServer> hub = registryService.findHubServerFor(preferredType);
            if (!hub.isPresent() && preferredType != ServerType.UNKNOWN) {
                hub = registryService.findHubServerFor(ServerType.UNKNOWN);
            }
            if (hub.isPresent()) {
                event.setInitialServer(hub.get());
                return;
            }
        }
        if (hasFallbackHub()) {
            java.util.Optional<RegisteredServer> fallbackHub = proxy.getServer(config.getHubServer());
            if (fallbackHub.isPresent()) {
                event.setInitialServer(fallbackHub.get());
                return;
            }
        }

        event.getPlayer().disconnect(noServersAvailableMessage());
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        String alias = firstCommandAlias(event.getCommand());
        if (!DISABLED_PROXY_COMMAND.equals(alias)) {
            return;
        }
        event.setResult(CommandExecuteEvent.CommandResult.denied());
        event.getCommandSource().sendMessage(Component.text(
                CommonMessages.NO_PERMISSION_COMMAND,
                NamedTextColor.RED
        ));
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        if (event == null || chatChannelService == null || partyService == null) {
            return;
        }
        PlayerChatEvent.ChatResult currentResult = event.getResult();
        if (currentResult == null || !currentResult.isAllowed()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String message = currentResult.getMessage().orElse(event.getMessage());
        if (message == null) {
            return;
        }
        boolean muted = chatRestrictionService != null && chatRestrictionService.isMuted(playerId);
        ChatChannelService.ChatChannel channel = chatChannelService.getChannel(playerId);
        if (channel != ChatChannelService.ChatChannel.PARTY) {
            if (!muted) {
                storeChatMessage(player, message, ChatChannelService.ChatChannel.ALL);
            }
            return;
        }
        if (message.trim().isEmpty()) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            return;
        }

        if (!partyService.isInParty(playerId)) {
            chatChannelService.setChannel(playerId, ChatChannelService.ChatChannel.ALL);
            sendPartyChatFramed(player, Component.text(
                    "You are not in a party. Your chat channel has been set to ALL.",
                    NamedTextColor.RED
            ));
            event.setResult(PlayerChatEvent.ChatResult.denied());
            return;
        }
        if (muted) {
            sendPartyChatFramed(player, Component.text("You are currently muted!", NamedTextColor.RED));
            event.setResult(PlayerChatEvent.ChatResult.denied());
            return;
        }
        if (partyService.isPartyChatMuted(playerId)
                && !partyService.isLeader(playerId)
                && !partyService.isModerator(playerId)
                && !isStaff(playerId)) {
            sendPartyChatFramed(player, Component.text("This party is currently muted!", NamedTextColor.RED));
            event.setResult(PlayerChatEvent.ChatResult.denied());
            return;
        }
        partyService.sendPartyMessage(
                playerId,
                Components.partyChat(formatPartyChatName(playerId, player.getUsername()), message),
                memberId -> canReceivePartyChat(playerId, memberId)
        );
        storeChatMessage(player, message, ChatChannelService.ChatChannel.PARTY);
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (isUpdateJoinLockActive()) {
            event.getPlayer().disconnect(updateJoinLockReason());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        if (registryService == null || !event.getResult().isAllowed()) {
            return;
        }

        RegisteredServer targetServer = event.getResult().getServer().orElse(event.getOriginalServer());
        String targetName = targetServer.getServerInfo().getName();
        ServerType targetType = registryService.findServerType(targetName).orElse(ServerType.UNKNOWN);
        if (!targetType.isGame()) {
            return;
        }

        if (partyService != null) {
            UUID playerId = event.getPlayer().getUniqueId();
            if (partyService.isInParty(playerId)
                    && !partyService.isLeader(playerId)
                    && !partyService.consumeAuthorizedGameJoin(playerId, targetName)) {
                boolean playAgainIntent = consumeRecentPlayAgainIntent(playerId);
                if (redirectUnauthorizedPostGamePartyMemberToHub(event, playAgainIntent)) {
                    deferredPostGameQueueRequests.remove(playerId);
                    return;
                }
                boolean deferredPostGameQueue = rememberDeferredPostGameQueue(event.getPlayer(), targetType);
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
                if (playAgainIntent || !deferredPostGameQueue) {
                    sendPartyLeaderWarpOnlyMessage(event.getPlayer());
                }
                return;
            }
        }

        if (gameServerStateBlocksJoin(targetName)) {
            java.util.Optional<RegisteredServer> fallbackHub = registryService.findHubServerFor(targetType, targetName);
            if (fallbackHub.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(fallbackHub.get()));
                event.getPlayer().sendMessage(couldNotConnectToGameMessage(targetType));
            }
            return;
        }

        if (event.getPreviousServer() != null) {
            if (partyService != null) {
                UUID playerId = event.getPlayer().getUniqueId();
                if (partyService.isLeader(playerId)) {
                    registryService.refreshIfStale(0L);
                    ServerRegistryService.ServerDetails details = registryService.findServerDetails(targetName).orElse(null);
                    int requiredSlots = requiredPartySlotsForTarget(event.getPlayer(), targetName);
                    if (details != null && !hasCapacity(details, requiredSlots)) {
                        event.setResult(ServerPreConnectEvent.ServerResult.denied());
                        sendPartyCapacityBlockedMessage(event.getPlayer());
                        return;
                    }
                }
            }
            return;
        }

        java.util.Optional<RegisteredServer> hub = registryService.findHubServerFor(targetType, targetName);
        if (!hub.isPresent()) {
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.allowed(hub.get()));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        infrastructureFailure.set(true);
        proxy.getChannelRegistrar().unregister(PLAY_AGAIN_INTENT_CHANNEL);
        recentPlayAgainIntents.clear();
        if (queueOrchestrator != null) {
            queueOrchestrator.stop();
            queueOrchestrator = null;
        }
        registryService = null;
        friendService = null;
        blockService = null;
        chatChannelService = null;
        chatMessageService = null;
        chatRestrictionService = null;
        partyService = null;
        staffChatService = null;
        updateCommand = null;
        rankResolver = null;
        motdCache = null;
        if (redis != null) {
            redis.close();
            redis = null;
        }
        if (mongoClient != null) {
            mongoClient.close();
            mongoClient = null;
        }
        mongoDatabase = null;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (event.getPlayer().getProtocolVersion() != ProtocolVersion.MINECRAFT_1_8) {
            event.getPlayer().disconnect(NON_1_8_DISCONNECT_REASON);
            return;
        }
        if (chatChannelService != null) {
            chatChannelService.track(event.getPlayer().getUniqueId());
        }
        if (blockService != null) {
            blockService.ensurePlayerDocument(event.getPlayer().getUniqueId(), event.getPlayer().getUsername());
        }
        if (friendService != null) {
            friendService.track(event.getPlayer());
            friendService.notifyFriendStatus(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), true);
        }
        if (partyService != null) {
            partyService.track(event.getPlayer());
        }
        if (staffChatService != null) {
            staffChatService.broadcastJoin(event.getPlayer());
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        recentPlayAgainIntents.remove(playerId);
        String connectedServerName = event.getServer() == null
                ? null
                : event.getServer().getServerInfo().getName();
        boolean connectedToGame = connectedServerName != null
                && registryService != null
                && registryService.findServerType(connectedServerName).map(ServerType::isGame).orElse(false);
        if (connectedToGame) {
            deferredPostGameQueueRequests.remove(playerId);
        }
        if (friendService != null) {
            friendService.updatePresence(event.getPlayer());
        }
        if (partyService != null) {
            autoFollowPartyOnGameJoin(event.getPlayer(), connectedServerName, false);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        recentPlayAgainIntents.remove(playerId);
        deferredPostGameQueueRequests.remove(playerId);
        if (chatChannelService != null) {
            chatChannelService.clear(playerId);
        }
        if (friendService != null) {
            friendService.markOffline(event.getPlayer());
        }
        if (friendService != null) {
            friendService.notifyFriendStatus(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(), false);
        }
        if (partyService != null) {
            partyService.markOffline(event.getPlayer());
        }
        if (staffChatService != null) {
            staffChatService.broadcastQuit(event.getPlayer());
        }
        if (queueOrchestrator != null) {
            queueOrchestrator.remove(event.getPlayer().getUniqueId());
        }
    }

    private void loadConfig() {
        ConfigLoader loader = new ConfigLoader();
        try {
            this.config = loader.load(dataDir);
        } catch (IOException e) {
            logger.error("Failed to load proxy config!", e);
            this.config = new ProxyConfig();
        }
    }

    private void setupMongo() {
        if (config == null || config.getMongoUri() == null) {
            return;
        }
        this.mongoClient = createMongoClient(config.getMongoUri());
        this.mongoDatabase = mongoClient.getDatabase(config.getMongoDatabase());
    }

    private MongoClient createMongoClient(String uri) {
        ConnectionString connectionString = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToClusterSettings(builder ->
                        builder.serverSelectionTimeout(MONGO_SERVER_SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .applyToSocketSettings(builder -> {
                    builder.connectTimeout((int) MONGO_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    builder.readTimeout((int) MONGO_SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                })
                .build();
        return MongoClients.create(settings);
    }

    private void ensureProxyCollections() {
        if (mongoDatabase == null || config == null) {
            return;
        }
        ensureCollection(config.getMotdCollection());
        ensureCollection(config.getFriendsCollection());
        ensureCollection(config.getRegistryCollection());
        ensureCollection(MongoManager.PROFILES_COLLECTION);
        ensureCollection(MongoManager.MAPS_COLLECTION);
        ensureCollection(MongoManager.AUTOSCALE_COLLECTION);
        ensureCollection(MongoManager.CHAT_MESSAGES_COLLECTION);
        seedMotdDocument();
        seedDomainDocument();
    }

    private void ensureCollection(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String target = name.trim();
        for (String existing : mongoDatabase.listCollectionNames()) {
            if (target.equals(existing)) {
                return;
            }
        }
        try {
            mongoDatabase.createCollection(target);
        } catch (com.mongodb.MongoCommandException ex) {
            if (ex.getErrorCode() != 48) {
                throw ex;
            }
        }
    }

    private void seedMotdDocument() {
        String collectionName = config.getMotdCollection();
        String documentId = config.getMotdDocumentId();
        if (collectionName == null || collectionName.trim().isEmpty()
                || documentId == null || documentId.trim().isEmpty()) {
            return;
        }
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        Document defaults = new Document("motdFirstLine", "§aHypixel Copy")
                .append("motdSecondLine", "")
                .append("maintenanceMotdFirstLine", "§cMaintenance mode")
                .append("maintenanceMotdSecondLine", "")
                .append("playerCount", 200)
                .append("maintenanceEnabled", false);
        collection.updateOne(
                new Document("_id", documentId),
                new Document("$setOnInsert", defaults),
                new UpdateOptions().upsert(true)
        );

        Document existing = collection.find(new Document("_id", documentId))
                .projection(new Document("playerCount", 1).append("maxPlayers", 1))
                .first();
        if (existing == null || existing.get("playerCount") != null) {
            return;
        }
        int resolvedPlayerCount = resolvePlayerCount(existing.get("maxPlayers"));
        collection.updateOne(
                new Document("_id", documentId),
                new Document("$set", new Document("playerCount", resolvedPlayerCount))
        );
    }

    private void seedDomainDocument() {
        String collectionName = config.getMotdCollection();
        if (collectionName == null || collectionName.trim().isEmpty()) {
            return;
        }
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        DomainSettingsStore.ensureDomainDocument(collection);
        ensureDomainToConnectField(collection);
        ensureConnectUsingDomainField(collection);
    }

    private void ensureDomainToConnectField(MongoCollection<Document> collection) {
        if (collection == null) {
            return;
        }
        collection.updateOne(
                new Document("_id", MongoManager.PROXY_SETTINGS_DOMAIN_DOCUMENT_ID)
                        .append(MongoManager.PROXY_SETTINGS_DOMAIN_TO_CONNECT_FIELD, new Document("$exists", false)),
                new Document("$set", new Document(MongoManager.PROXY_SETTINGS_DOMAIN_TO_CONNECT_FIELD, NetworkConstants.DEFAULT_DOMAIN))
        );
    }

    private void ensureConnectUsingDomainField(MongoCollection<Document> collection) {
        if (collection == null) {
            return;
        }
        collection.updateOne(
                new Document("_id", MongoManager.PROXY_SETTINGS_DOMAIN_DOCUMENT_ID)
                        .append(MongoManager.PROXY_SETTINGS_CONNECT_USING_DOMAIN_FIELD, new Document("$exists", false)),
                new Document("$set", new Document(MongoManager.PROXY_SETTINGS_CONNECT_USING_DOMAIN_FIELD, Boolean.FALSE))
        );
    }

    private void startDomainRefreshTask() {
        if (mongoDatabase == null || config == null) {
            return;
        }
        String collectionName = config.getMotdCollection();
        if (collectionName == null || collectionName.trim().isEmpty()) {
            return;
        }
        MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
        refreshNetworkDomain(collection, false);
        proxy.getScheduler().buildTask(this, () -> refreshNetworkDomain(collection, true))
                .repeat(1, TimeUnit.MINUTES)
                .schedule();
    }

    private void refreshNetworkDomain(MongoCollection<Document> collection, boolean logChanges) {
        try {
            boolean domainChanged = DomainSettingsStore.refreshDomain(collection);
            boolean domainToConnectChanged = refreshDomainToConnect(collection);
            boolean connectUsingDomainChanged = refreshConnectUsingDomain(collection);
            if (domainChanged && logChanges) {
                logger.info("Updated network domain to {}", NetworkConstants.domain());
            }
            if (domainToConnectChanged && logChanges) {
                logger.info("Updated domain-to-connect setting.");
            }
            if (connectUsingDomainChanged && logChanges) {
                logger.info("Updated connect-using-domain setting.");
            }
        } catch (Exception ex) {
            logger.debug("Failed to refresh proxy settings domain: {}", ex.getMessage());
        }
    }

    private boolean refreshDomainToConnect(MongoCollection<Document> collection) {
        String resolved = resolveDomainToConnect(collection);
        if (resolved.equals(domainToConnect)) {
            return false;
        }
        domainToConnect = resolved;
        return true;
    }

    private String resolveDomainToConnect(MongoCollection<Document> collection) {
        if (collection == null) {
            return "";
        }
        Document settingsDoc = collection.find(new Document("_id", MongoManager.PROXY_SETTINGS_DOMAIN_DOCUMENT_ID))
                .projection(new Document(MongoManager.PROXY_SETTINGS_DOMAIN_TO_CONNECT_FIELD, 1))
                .first();
        if (settingsDoc == null) {
            return "";
        }
        return normalizeExpectedJoinHost(settingsDoc.get(MongoManager.PROXY_SETTINGS_DOMAIN_TO_CONNECT_FIELD));
    }

    private boolean refreshConnectUsingDomain(MongoCollection<Document> collection) {
        boolean resolved = resolveConnectUsingDomain(collection);
        if (resolved == connectUsingDomain) {
            return false;
        }
        connectUsingDomain = resolved;
        return true;
    }

    private boolean resolveConnectUsingDomain(MongoCollection<Document> collection) {
        if (collection == null) {
            return false;
        }
        Document settingsDoc = collection.find(new Document("_id", MongoManager.PROXY_SETTINGS_DOMAIN_DOCUMENT_ID))
                .projection(new Document(MongoManager.PROXY_SETTINGS_CONNECT_USING_DOMAIN_FIELD, 1))
                .first();
        if (settingsDoc == null) {
            return false;
        }
        Object raw = settingsDoc.get(MongoManager.PROXY_SETTINGS_CONNECT_USING_DOMAIN_FIELD);
        return raw instanceof Boolean && ((Boolean) raw).booleanValue();
    }

    private boolean shouldRejectJoinHost(Player player, String requiredHost) {
        String expected = normalizeExpectedJoinHost(requiredHost);
        if (expected.isEmpty() || player == null) {
            return false;
        }
        String joinedHost = resolveJoinedHost(player);
        return joinedHost.isEmpty() || !joinedHost.equals(expected);
    }

    private String resolveJoinedHost(Player player) {
        if (player == null) {
            return "";
        }
        return player.getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .map(this::normalizeExpectedJoinHost)
                .orElse("");
    }

    private Component domainJoinBlockedReason(String requiredHost) {
        String host = normalizeExpectedJoinHost(requiredHost);
        return Component.text("Please connect using ", NamedTextColor.RED)
                .append(Component.text(host, NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.RED));
    }

    private String normalizeExpectedJoinHost(Object rawDomainOrHost) {
        return normalizeDomainToConnectHost(rawDomainOrHost);
    }

    private static String normalizeDomainToConnectHost(Object rawDomainOrHost) {
        if (rawDomainOrHost == null) {
            return "";
        }
        String normalized = String.valueOf(rawDomainOrHost).trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        String defaultDomain = NetworkConstants.DEFAULT_DOMAIN.toLowerCase(Locale.ROOT);
        String defaultMcHost = "mc." + defaultDomain;
        if (normalized.equals(defaultDomain) || normalized.equals(defaultMcHost)) {
            return defaultMcHost;
        }
        if (normalized.startsWith("mc.")) {
            return normalized.substring("mc.".length());
        }
        return normalized;
    }

    private String normalizeJoinHost(Object rawValue) {
        if (rawValue == null) {
            return "";
        }
        String normalized = String.valueOf(rawValue).trim().toLowerCase(Locale.ROOT);
        return normalized;
    }

    private int resolvePlayerCount(Object value) {
        if (value instanceof Number) {
            int parsed = ((Number) value).intValue();
            return parsed > 0 ? parsed : 200;
        }
        if (value != null) {
            String raw = String.valueOf(value).trim();
            if (!raw.isEmpty()) {
                try {
                    int parsed = Integer.parseInt(raw);
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Use default when existing value is malformed.
                }
            }
        }
        return 200;
    }

    private void setupRedis() {
        if (config == null || config.getRedisHost() == null) {
            return;
        }
        this.redis = new RedisManager(
                config.getRedisHost(),
                config.getRedisPort(),
                config.getRedisPassword(),
                config.getRedisDatabase());
    }

    private Favicon loadFavicon() {
        if (config == null) {
            return null;
        }
        String iconFile = config.getIconFile();
        if (iconFile == null || iconFile.trim().isEmpty()) {
            return null;
        }

        Path configured = Path.of(iconFile.trim());
        Set<Path> candidates = new LinkedHashSet<>();
        if (configured.isAbsolute()) {
            candidates.add(configured.normalize());
        } else {
            // Relative to Velocity plugin data dir.
            candidates.add(dataDir.resolve(configured).normalize());
            Path pluginsDir = dataDir.getParent();
            if (pluginsDir != null) {
                // Relative to Velocity plugins dir.
                candidates.add(pluginsDir.resolve(configured).normalize());
                Path serverRoot = pluginsDir.getParent();
                if (serverRoot != null) {
                    // Relative to Velocity server root (when dataDir is absolute).
                    candidates.add(serverRoot.resolve(configured).normalize());
                }
            }
            // Relative to process working directory.
            candidates.add(configured.normalize());
            // Explicit Docker velocity root fallback.
            candidates.add(Path.of("/server").resolve(configured).normalize());
        }

        for (Path iconPath : candidates) {
            if (!Files.exists(iconPath)) {
                continue;
            }
            try {
                return Favicon.create(iconPath);
            } catch (IOException e) {
                logger.warn("Failed to load server icon from {}!", iconPath, e);
                return null;
            }
        }
        logger.warn("server icon '{}' not found. Checked paths: {}", iconFile, candidates);
        return null;
    }

    private boolean hasFallbackHub() {
        return config != null
                && config.getHubServer() != null
                && !config.getHubServer().trim().isEmpty();
    }

    private boolean redirectToFallbackHub(KickedFromServerEvent event,
                                          String sourceServerName,
                                          ServerType sourceType,
                                          boolean transferAfterGameEnd) {
        if (!hasFallbackHub()) {
            return false;
        }
        java.util.Optional<RegisteredServer> fallback = proxy.getServer(config.getHubServer());
        if (!fallback.isPresent() || isSameServer(sourceServerName, fallback.get())) {
            return false;
        }
        if (transferAfterGameEnd) {
            event.getPlayer().sendMessage(noServersRedirectMessage(sourceType));
        }
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(fallback.get()));
        return true;
    }

    private Component noServersRedirectMessage(ServerType sourceType) {
        String gameName = HubMessageUtil.gameDisplayName(sourceType);
        String hubName = HubMessageUtil.hubDisplayName(sourceType);
        return Component.text(
                "No available " + gameName + " servers. Moving you to " + hubName + ".",
                NamedTextColor.RED
        );
    }

    private void disconnectWithKickReason(KickedFromServerEvent event) {
        Component reason = event.getServerKickReason()
                .orElse(noServersAvailableMessage());
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    private void startInfrastructureHealthMonitor() {
        infraHealthChecksStartedAtMillis = System.currentTimeMillis();
        proxy.getScheduler().buildTask(this, this::checkInfrastructureHealth)
                .repeat(INFRA_HEALTH_CHECK_SECONDS, TimeUnit.SECONDS)
                .schedule();
    }

    private void checkInfrastructureHealth() {
        if (infrastructureFailure.get()) {
            return;
        }
        checkMongoHealth();
        checkRedisHealth();
    }

    private void checkMongoHealth() {
        if (mongoDatabase == null) {
            return;
        }
        try {
            Document result = mongoDatabase.runCommand(new Document("ping", 1));
            Object ok = result == null ? null : result.get("ok");
            if (!(ok instanceof Number) || ((Number) ok).doubleValue() < 1D) {
                throw new IllegalStateException("MongoDB ping response was not healthy.");
            }
            handleDependencyCheckRecovery("MongoDB", mongoHealthFailures, mongoFirstFailureAtMillis);
        } catch (Exception ex) {
            handleDependencyCheckFailure("MongoDB", ex, mongoHealthFailures, mongoFirstFailureAtMillis);
        }
    }

    private void checkRedisHealth() {
        if (redis == null) {
            return;
        }
        try (Jedis jedis = redis.createSubscriberClient()) {
            String pong = jedis.ping();
            if (pong == null || !"PONG".equalsIgnoreCase(pong.trim())) {
                throw new IllegalStateException("Redis ping response was not healthy.");
            }
            handleDependencyCheckRecovery("Redis", redisHealthFailures, redisFirstFailureAtMillis);
        } catch (Exception ex) {
            handleDependencyCheckFailure("Redis", ex, redisHealthFailures, redisFirstFailureAtMillis);
        }
    }

    private void handleDependencyCheckFailure(String dependency,
                                              Throwable cause,
                                              AtomicInteger failuresCounter,
                                              AtomicLong firstFailureAtMillis) {
        if (isWithinInfrastructureGracePeriod()) {
            failuresCounter.set(0);
            firstFailureAtMillis.set(0L);
            if (cause == null) {
                logger.warn("{} health check failed during startup grace period.", dependency);
            } else {
                logger.warn(
                        "{} health check failed during startup grace period: {}",
                        dependency,
                        cause.getMessage()
                );
            }
            return;
        }
        long now = System.currentTimeMillis();
        long firstFailureAt = firstFailureAtMillis.updateAndGet(previous -> previous > 0L ? previous : now);
        long outageDurationMillis = Math.max(0L, now - firstFailureAt);
        int failures = failuresCounter.incrementAndGet();
        if (failures < INFRA_FAILURES_BEFORE_FAILOVER
                || outageDurationMillis < INFRA_FAILURE_SUSTAINED_MILLIS_BEFORE_FAILOVER) {
            if (cause == null) {
                logger.warn(
                        "{} health check failed ({}/{}), outage {}ms. Waiting for sustained consecutive failures before failover.",
                        dependency,
                        failures,
                        INFRA_FAILURES_BEFORE_FAILOVER,
                        outageDurationMillis
                );
            } else {
                logger.warn(
                        "{} health check failed ({}/{}), outage {}ms. Waiting for sustained consecutive failures before failover: {}",
                        dependency,
                        failures,
                        INFRA_FAILURES_BEFORE_FAILOVER,
                        outageDurationMillis,
                        cause.getMessage()
                );
            }
            return;
        }
        triggerInfrastructureFailover(dependency, cause);
    }

    private boolean isWithinInfrastructureGracePeriod() {
        long startedAt = infraHealthChecksStartedAtMillis;
        if (startedAt <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - startedAt < INFRA_FAILURE_GRACE_PERIOD_MILLIS;
    }

    private void handleDependencyCheckRecovery(String dependency,
                                               AtomicInteger failuresCounter,
                                               AtomicLong firstFailureAtMillis) {
        long firstFailureAt = firstFailureAtMillis.getAndSet(0L);
        int previousFailures = failuresCounter.getAndSet(0);
        if (previousFailures > 0) {
            long outageDurationMillis = firstFailureAt > 0L
                    ? Math.max(0L, System.currentTimeMillis() - firstFailureAt)
                    : -1L;
            if (outageDurationMillis >= 0L) {
                logger.info(
                        "{} health check recovered after {} consecutive failure(s) over {}ms.",
                        dependency,
                        previousFailures,
                        outageDurationMillis
                );
            } else {
                logger.info("{} health check recovered after {} consecutive failure(s).", dependency, previousFailures);
            }
        }
    }

    private void triggerInfrastructureFailover(String dependency, Throwable cause) {
        if (!infrastructureFailure.compareAndSet(false, true)) {
            return;
        }
        Component reason = noServersAvailableMessage();
        if (cause == null) {
            logger.error("{} is unavailable. Disconnecting players and shutting down proxy.", dependency);
        } else {
            logger.error("{} is unavailable. Disconnecting players and shutting down proxy.", dependency, cause);
        }
        if (queueOrchestrator != null) {
            queueOrchestrator.stop();
            queueOrchestrator = null;
        }
        deferredPostGameQueueRequests.clear();
        for (Player player : proxy.getAllPlayers()) {
            try {
                player.disconnect(reason);
            } catch (Exception ex) {
                logger.warn("Failed to disconnect {} during infrastructure failover!", player.getUsername(), ex);
            }
        }
        proxy.getScheduler().buildTask(this, () -> proxy.shutdown())
                .delay(FAILOVER_SHUTDOWN_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private Component noServersAvailableMessage() {
        return Component.text(CommonMessages.NO_SERVERS_AVAILABLE, NamedTextColor.RED);
    }

    private boolean redirectUnauthorizedPostGamePartyMemberToHub(ServerPreConnectEvent event,
                                                                 boolean notifyLeaderOnlyMessage) {
        if (event == null || registryService == null) {
            return false;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return false;
        }
        String sourceServerName = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (sourceServerName == null || sourceServerName.trim().isEmpty()) {
            return false;
        }
        registryService.refreshIfStale(0L);
        ServerRegistryService.ServerDetails sourceDetails = registryService.findServerDetails(sourceServerName).orElse(null);
        if (sourceDetails == null || !sourceDetails.getType().isGame() || !isPostGameQueueState(sourceDetails.getState())) {
            return false;
        }
        if (notifyLeaderOnlyMessage) {
            sendPartyLeaderWarpOnlyMessage(player);
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        return true;
    }

    private boolean rememberDeferredPostGameQueue(Player player, ServerType targetType) {
        if (player == null || targetType == null || !targetType.isGame()) {
            return false;
        }
        if (partyService == null || registryService == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (!partyService.isInParty(playerId) || partyService.isLeader(playerId)) {
            return false;
        }
        String sourceServerName = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        if (sourceServerName == null || sourceServerName.trim().isEmpty()) {
            return false;
        }
        registryService.refreshIfStale(0L);
        ServerRegistryService.ServerDetails sourceDetails = registryService.findServerDetails(sourceServerName).orElse(null);
        if (sourceDetails == null || !sourceDetails.getType().isGame() || !isPostGameQueueState(sourceDetails.getState())) {
            return false;
        }
        deferredPostGameQueueRequests.put(playerId, new DeferredQueueRequest(targetType, System.currentTimeMillis()));
        return true;
    }

    private void processDeferredPostGameQueues() {
        if (deferredPostGameQueueRequests.isEmpty()) {
            return;
        }
        if (registryService != null) {
            registryService.refreshIfStale(0L);
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, DeferredQueueRequest> entry : deferredPostGameQueueRequests.entrySet()) {
            UUID playerId = entry.getKey();
            DeferredQueueRequest request = entry.getValue();
            if (playerId == null || request == null) {
                deferredPostGameQueueRequests.remove(playerId, request);
                continue;
            }
            if (now - request.createdAtMillis > DEFERRED_POST_GAME_QUEUE_EXPIRY_MILLIS) {
                deferredPostGameQueueRequests.remove(playerId, request);
                continue;
            }
            Player player = proxy.getPlayer(playerId).orElse(null);
            if (player == null) {
                deferredPostGameQueueRequests.remove(playerId, request);
                continue;
            }
            if (partyService != null && partyService.isInParty(playerId)) {
                continue;
            }
            String currentServerName = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (currentServerName == null || currentServerName.trim().isEmpty() || registryService == null) {
                deferredPostGameQueueRequests.remove(playerId, request);
                continue;
            }
            ServerRegistryService.ServerDetails currentDetails = registryService.findServerDetails(currentServerName).orElse(null);
            boolean stillOnPostGameServer = currentDetails != null
                    && currentDetails.getType().isGame()
                    && isPostGameQueueState(currentDetails.getState());
            if (!stillOnPostGameServer) {
                deferredPostGameQueueRequests.remove(playerId, request);
                continue;
            }
            if (queueOrchestrator == null) {
                deferredPostGameQueueRequests.remove(playerId, request);
                continue;
            }
            queueOrchestrator.requestQueue(playerId, request.targetType);
            deferredPostGameQueueRequests.remove(playerId, request);
        }
    }

    private boolean isPostGameQueueState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return false;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("ENDING")
                || normalized.equals("RESTARTING")
                || normalized.equals("WAITING_RESTART");
    }

    private boolean isSameServer(String sourceServerName, RegisteredServer target) {
        if (sourceServerName == null || target == null) {
            return false;
        }
        String targetName = target.getServerInfo().getName();
        return targetName != null && sourceServerName.equalsIgnoreCase(targetName);
    }

    private void redirectPartyMembersFromSource(UUID memberId, String sourceServerName, RegisteredServer target) {
        if (partyService == null || memberId == null || target == null) {
            return;
        }
        String targetName = target.getServerInfo().getName();
        boolean targetIsGame = registryService != null
                && targetName != null
                && registryService.findServerType(targetName).map(ServerType::isGame).orElse(false);
        Set<UUID> members = partyService.getMembers(memberId);
        if (members.isEmpty()) {
            return;
        }
        for (UUID partyMemberId : members) {
            if (partyMemberId == null || partyMemberId.equals(memberId)) {
                continue;
            }
            proxy.getPlayer(partyMemberId).ifPresent(partyMember -> {
                String currentServer = partyMember.getCurrentServer()
                        .map(server -> server.getServerInfo().getName())
                        .orElse(null);
                if (sourceServerName != null
                        && (currentServer == null || !sourceServerName.equalsIgnoreCase(currentServer))) {
                    return;
                }
                if (targetIsGame && targetName != null) {
                    partyService.authorizeAnyGameJoin(partyMember.getUniqueId());
                }
                partyMember.createConnectionRequest(target).fireAndForget();
            });
        }
    }

    private void autoFollowPartyOnGameJoin(Player leader, String requestedTargetName, boolean retryAttempt) {
        if (leader == null || partyService == null) {
            return;
        }
        UUID leaderId = leader.getUniqueId();
        if (!partyService.isLeader(leaderId)) {
            return;
        }
        String normalizedRequestedTargetName = requestedTargetName == null ? null : requestedTargetName.trim();
        String targetName = normalizedRequestedTargetName == null ? "" : normalizedRequestedTargetName;
        if (targetName.isEmpty()) {
            targetName = leader.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse("");
        }
        if (targetName.trim().isEmpty()) {
            return;
        }
        if (normalizedRequestedTargetName != null) {
            String currentServerName = leader.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (currentServerName != null && !currentServerName.equalsIgnoreCase(targetName)) {
                return;
            }
        }
        if (normalizedRequestedTargetName != null && !normalizedRequestedTargetName.equalsIgnoreCase(targetName)) {
            return;
        }
        RegisteredServer target = proxy.getServer(targetName).orElse(null);
        if (target == null) {
            return;
        }
        if (registryService == null) {
            return;
        }
        registryService.refreshIfStale(0L);
        ServerRegistryService.ServerDetails details = registryService.findServerDetails(targetName).orElse(null);
        ServerType targetType = details == null
                ? registryService.findServerType(targetName).orElse(ServerType.UNKNOWN)
                : details.getType();
        if (!targetType.isGame()) {
            return;
        }
        if (details != null && isPartyFollowBlockedGameState(details.getState())) {
            if (!retryAttempt) {
                leader.sendMessage(Component.text(
                        "Your party couldn't follow because that game is already in progress.",
                        NamedTextColor.RED
                ));
            }
            return;
        }

        final String resolvedTargetName = targetName;
        List<Player> toMove = new java.util.ArrayList<Player>();
        Set<UUID> members = partyService.getMembers(leaderId);
        for (UUID memberId : members) {
            if (memberId == null || memberId.equals(leaderId)) {
                continue;
            }
            proxy.getPlayer(memberId).ifPresent(member -> {
                String currentServer = member.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                        .orElse(null);
                if (currentServer != null && currentServer.equalsIgnoreCase(resolvedTargetName)) {
                    return;
                }
                toMove.add(member);
            });
        }
        if (toMove.isEmpty()) {
            return;
        }
        if (details != null && !hasCapacity(details, toMove.size())) {
            if (!retryAttempt) {
                leader.sendMessage(Component.text(
                        "Your party couldn't follow because this server doesn't have enough free slots.",
                        NamedTextColor.RED
                ));
            }
            return;
        }

        for (Player member : toMove) {
            partyService.authorizeAnyGameJoin(member.getUniqueId());
            member.createConnectionRequest(target).fireAndForget();
        }
        if (!retryAttempt) {
            final UUID followLeaderId = leaderId;
            final String followTargetName = targetName;
            proxy.getScheduler().buildTask(this, () ->
                    proxy.getPlayer(followLeaderId).ifPresent(player ->
                            autoFollowPartyOnGameJoin(player, followTargetName, true)
                    )).delay(PARTY_FOLLOW_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS).schedule();
        }
    }

    private boolean hasCapacity(ServerRegistryService.ServerDetails details, int neededSlots) {
        if (details == null || neededSlots <= 0) {
            return true;
        }
        int maxPlayers = details.getMaxPlayers();
        if (maxPlayers <= 0) {
            return true;
        }
        int available = maxPlayers - details.getPlayers();
        return available >= neededSlots;
    }

    private int requiredPartySlotsForTarget(Player leader, String targetServerName) {
        if (leader == null || partyService == null) {
            return 1;
        }
        UUID leaderId = leader.getUniqueId();
        if (!partyService.isLeader(leaderId)) {
            return 1;
        }
        String safeTarget = targetServerName == null ? "" : targetServerName.trim();
        boolean leaderAlreadyOnTarget = leader.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .map(name -> safeTarget.equalsIgnoreCase(name))
                .orElse(false);
        int required = leaderAlreadyOnTarget ? 0 : 1;
        Set<UUID> members = partyService.getMembers(leaderId);
        for (UUID memberId : members) {
            if (memberId == null || memberId.equals(leaderId)) {
                continue;
            }
            Player member = proxy.getPlayer(memberId).orElse(null);
            if (member == null) {
                continue;
            }
            boolean memberAlreadyOnTarget = member.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .map(name -> safeTarget.equalsIgnoreCase(name))
                    .orElse(false);
            if (memberAlreadyOnTarget) {
                continue;
            }
            required++;
        }
        return required;
    }

    private boolean isPartyFollowBlockedGameState(String state) {
        if (state == null || state.trim().isEmpty()) {
            return false;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("IN_GAME") || normalized.equals("ENDING");
    }

    private boolean isConnectionThrottle(String reason) {
        return reason.contains("connection throttled");
    }

    private boolean canReceivePartyChat(UUID senderId, UUID recipientId) {
        if (senderId == null || recipientId == null) {
            return false;
        }
        if (senderId.equals(recipientId)) {
            return true;
        }
        if (blockService == null) {
            return true;
        }
        return !blockService.isEitherBlocked(senderId, recipientId);
    }

    private void storeChatMessage(Player player, String message, ChatChannelService.ChatChannel channel) {
        if (player == null || message == null || chatMessageService == null) {
            return;
        }
        chatMessageService.storeMessage(
                player.getUniqueId(),
                player.getUsername(),
                resolveChatServerId(player),
                channel,
                message
        );
    }

    private String resolveChatServerId(Player player) {
        if (player == null) {
            return "proxy";
        }
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .filter(name -> name != null && !name.trim().isEmpty())
                .orElse("proxy");
    }

    private boolean isStaff(UUID playerId) {
        if (playerId == null || rankResolver == null) {
            return false;
        }
        try {
            return rankResolver.isStaff(playerId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String formatPartyChatName(UUID playerId, String fallbackName) {
        String fallback = fallbackName == null ? "Unknown" : fallbackName;
        if (rankResolver == null || playerId == null) {
            return "§7" + fallback;
        }
        try {
            String ranked = rankResolver.formatNameWithRank(playerId, fallback);
            if (ranked == null || ranked.trim().isEmpty()) {
                return "§7" + fallback;
            }
            String stripped = LEGACY_CODE.matcher(ranked).replaceAll("");
            if (fallback.equals(stripped)) {
                return "§7" + fallback;
            }
            return ranked;
        } catch (Throwable ignored) {
            return "§7" + fallback;
        }
    }

    private void sendPartyChatFramed(Player player, Component line) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(line == null ? Component.empty() : line);
        player.sendMessage(PartyComponents.longSeparator());
    }

    private String firstCommandAlias(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int split = trimmed.indexOf(' ');
        String alias = split >= 0 ? trimmed.substring(0, split) : trimmed;
        if (alias.startsWith("/")) {
            alias = alias.substring(1);
        }
        return alias.toLowerCase(Locale.ROOT);
    }

    private UUID readPlayAgainIntentPlayerId(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String raw = in.readUTF();
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            return UUID.fromString(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean consumeRecentPlayAgainIntent(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupExpiredPlayAgainIntents(now);
        Long markedAt = recentPlayAgainIntents.get(playerId);
        if (markedAt == null) {
            return false;
        }
        if (now - markedAt.longValue() > PLAY_AGAIN_INTENT_WINDOW_MILLIS) {
            recentPlayAgainIntents.remove(playerId, markedAt);
            return false;
        }
        return recentPlayAgainIntents.remove(playerId, markedAt);
    }

    private void cleanupExpiredPlayAgainIntents(long now) {
        for (Map.Entry<UUID, Long> entry : recentPlayAgainIntents.entrySet()) {
            UUID playerId = entry.getKey();
            Long markedAt = entry.getValue();
            if (playerId == null || markedAt == null || now - markedAt.longValue() > PLAY_AGAIN_INTENT_WINDOW_MILLIS) {
                recentPlayAgainIntents.remove(playerId, markedAt);
            }
        }
    }

    private boolean isPunishmentDisconnect(String reason) {
        if (reason == null || reason.isEmpty()) {
            return false;
        }
        return reason.contains("you are permanently banned")
                || reason.contains("you are temporarily banned")
                || reason.contains("you are banned")
                || reason.contains("ban id:")
                || reason.contains("you have been kicked from this server")
                || reason.contains("you were kicked");
    }

    private String kickReason(KickedFromServerEvent event) {
        return event.getServerKickReason()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .orElse("")
                .toLowerCase(Locale.ROOT);
    }

    private boolean shouldTransferToNextGame(KickedFromServerEvent event) {
        String reason = kickReason(event);
        if (reason.isEmpty()) {
            return false;
        }
        return reason.contains("server closed")
                || reason.contains("restarting")
                || reason.contains("restart");
    }

    private boolean shouldRedirectPostGamePartyMemberToLobby(UUID playerId, String sourceServerName) {
        if (playerId == null || partyService == null || proxy == null) {
            return false;
        }
        if (!partyService.isInParty(playerId) || partyService.isLeader(playerId)) {
            return false;
        }
        UUID leaderId = partyService.getLeader(playerId);
        if (leaderId == null || leaderId.equals(playerId)) {
            return false;
        }
        Player leader = proxy.getPlayer(leaderId).orElse(null);
        if (leader == null) {
            return true;
        }
        String safeSource = sourceServerName == null ? "" : sourceServerName.trim();
        if (safeSource.isEmpty()) {
            return false;
        }
        String leaderServerName = leader.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");
        if (leaderServerName == null || leaderServerName.trim().isEmpty()) {
            return true;
        }
        return !safeSource.equalsIgnoreCase(leaderServerName);
    }

    private Component couldNotConnectToGameMessage(ServerType gameType) {
        String gameName = HubMessageUtil.gameDisplayName(gameType);
        return Component.text(
                "Couldn't connect you to that server, so you were put in the " + gameName + " lobby!",
                NamedTextColor.RED
        );
    }

    private void sendPartyLeaderWarpOnlyMessage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text(
                        "Only the party leader can warp you into a game! Have them click an NPC or ",
                        NamedTextColor.RED
                )
                .append(Component.text("/party leave", NamedTextColor.AQUA)));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private void sendPartyCapacityBlockedMessage(Player player) {
        if (player == null) {
            return;
        }
        player.sendMessage(PartyComponents.longSeparator());
        player.sendMessage(Component.text(
                "This server doesn't have enough room for your party right now.",
                NamedTextColor.RED
        ));
        player.sendMessage(PartyComponents.longSeparator());
    }

    private boolean gameServerStateBlocksJoin(String serverName) {
        if (registryService == null || serverName == null || serverName.trim().isEmpty()) {
            return false;
        }
        return registryService.findServerDetails(serverName)
                .map(ServerRegistryService.ServerDetails::getState)
                .map(this::isJoinBlockedState)
                .orElse(false);
    }

    private boolean isJoinBlockedState(String state) {
        if (state == null) {
            return false;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("IN_GAME")
                || normalized.equals("ENDING")
                || normalized.equals("RESTARTING")
                || normalized.equals("LOCKED")
                || normalized.equals("WAITING_RESTART");
    }

    private boolean isUpdateJoinLockActive() {
        return updateCommand != null && updateCommand.isJoinLockActive();
    }

    private Component updateJoinLockReason() {
        UpdateCommand command = updateCommand;
        if (command != null) {
            return command.restartDisconnectReason();
        }
        return proxyRestartReconnectReason();
    }

    public static Component proxyRestartReconnectReason() {
        return Component.text(PROXY_RESTARTING_MESSAGE + ". " + PROXY_RECONNECT_TO_MESSAGE, NamedTextColor.RED)
                .append(Component.text(domainToConnectHost(), NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.RED));
    }

    public static String domainToConnectHost() {
        return normalizeDomainToConnectHost(domainToConnect);
    }

    private static final class DeferredQueueRequest {
        private final ServerType targetType;
        private final long createdAtMillis;

        private DeferredQueueRequest(ServerType targetType, long createdAtMillis) {
            this.targetType = targetType;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
