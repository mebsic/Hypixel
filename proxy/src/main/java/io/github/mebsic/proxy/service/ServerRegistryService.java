package io.github.mebsic.proxy.service;

import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.proxy.config.ProxyConfig;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ServerRegistryService {
    private static final long STALE_HEARTBEAT_MILLIS = 2 * 60 * 1000L;
    private static final long LOOKUP_REFRESH_MAX_AGE_MILLIS = 250L;
    private static final long CLEANUP_INTERVAL_MILLIS = 10_000L;
    private static final long REFRESH_FAILURE_LOG_INTERVAL_MILLIS = 10_000L;
    private static final long RESTART_MARKER_MAX_AGE_MILLIS = 10L * 60L * 1000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRegistryService.class);
    private final ProxyServer proxy;
    private final ProxyConfig config;
    private final MongoDatabase database;
    private final Object refreshLock;
    private volatile Map<UUID, RegistryEntry> entries;
    private volatile long lastRefreshMillis;
    private volatile long lastCleanupMillis;
    private volatile long lastRefreshFailureLogMillis;

    public ServerRegistryService(ProxyServer proxy, ProxyConfig config, MongoDatabase database) {
        this.proxy = proxy;
        this.config = config;
        this.database = database;
        this.refreshLock = new Object();
        this.entries = Collections.emptyMap();
        this.lastRefreshMillis = 0L;
        this.lastCleanupMillis = 0L;
        this.lastRefreshFailureLogMillis = 0L;
    }

    public void refresh() {
        refreshIfStale(0L);
    }

    public void refreshIfStale(long maxAgeMillis) {
        long now = System.currentTimeMillis();
        long maxAge = Math.max(0L, maxAgeMillis);
        if (maxAge > 0 && now - lastRefreshMillis <= maxAge) {
            return;
        }
        synchronized (refreshLock) {
            long current = System.currentTimeMillis();
            if (maxAge > 0 && current - lastRefreshMillis <= maxAge) {
                return;
            }
            try {
                refreshNow(current);
                lastRefreshMillis = current;
                lastRefreshFailureLogMillis = 0L;
            } catch (RuntimeException ex) {
                maybeLogRefreshFailure(current, ex);
            }
        }
    }

    private void maybeLogRefreshFailure(long now, RuntimeException ex) {
        long previous = lastRefreshFailureLogMillis;
        if (previous > 0L && now - previous < REFRESH_FAILURE_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastRefreshFailureLogMillis = now;
        String message = ex == null ? null : ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            LOGGER.warn("Server registry refresh failed; continuing with cached server snapshot.");
        } else {
            LOGGER.warn(
                    "Server registry refresh failed; continuing with cached server snapshot: {}",
                    message
            );
        }
        if (ex != null) {
            LOGGER.debug("Server registry refresh failure details", ex);
        }
    }

    private void refreshNow(long now) {
        if (database == null || config == null) {
            return;
        }
        MongoCollection<Document> collection = database.getCollection(config.getRegistryCollection());
        MongoCollection<Document> drainingCollection = database.getCollection(config.getAutoscaleCollection());
        String group = config.getRegistryGroup() == null ? "" : config.getRegistryGroup().trim();
        if (now - lastCleanupMillis >= CLEANUP_INTERVAL_MILLIS) {
            cleanupStaleRecords(collection, group, now);
            lastCleanupMillis = now;
        }
        FindIterable<Document> find = group.isEmpty()
                ? collection.find()
                : collection.find(Filters.eq("group", group));
        List<Document> docs = find.into(new ArrayList<>());
        Set<String> drainingIds = new HashSet<>();
        Set<String> drainingAddresses = new HashSet<>();
        org.bson.conversions.Bson drainingFilter = Filters.and(
                Filters.eq("docType", "drain"),
                Filters.eq("active", true)
        );
        if (!group.isEmpty()) {
            drainingFilter = Filters.and(drainingFilter, Filters.eq("gameType", group));
        }
        List<Document> drainingDocs = drainingCollection.find(drainingFilter).into(new ArrayList<>());
        for (Document drainDoc : drainingDocs) {
            String drainServerId = normalizeLookupKey(drainDoc.getString("serverId"));
            if (!drainServerId.isEmpty()) {
                drainingIds.add(drainServerId);
            }
            String drainServerAddress = normalizeLookupKey(drainDoc.getString("serverAddress"));
            if (!drainServerAddress.isEmpty()) {
                drainingAddresses.add(drainServerAddress);
            }
        }
        Map<String, RegistryEntry> nextByServerId = new HashMap<>();
        Map<String, Long> heartbeatByServerId = new HashMap<>();
        for (Document doc : docs) {
            String idRaw = doc.getString("_id");
            if (idRaw == null) {
                continue;
            }
            String serverId = idRaw.trim();
            if (serverId.isEmpty()) {
                continue;
            }
            String idKey = normalizeLookupKey(serverId);
            UUID id;
            try {
                id = UUID.fromString(serverId);
            } catch (IllegalArgumentException ex) {
                // Backends may publish non-UUID ids (e.g., container-based ids); hash to a stable UUID.
                id = UUID.nameUUIDFromBytes(serverId.getBytes(StandardCharsets.UTF_8));
            }
            String address = doc.getString("address");
            Integer port = doc.getInteger("port");
            String typeRaw = doc.getString("type");
            String status = doc.getString("status");
            Long heartbeat = doc.getLong("lastHeartbeat");
            Integer players = doc.getInteger("players");
            Integer maxPlayers = doc.getInteger("maxPlayers");
            String state = doc.getString("state");
            String addressKey = normalizeLookupKey(address);
            if (address == null || port == null || typeRaw == null) {
                continue;
            }
            ServerType type = ServerType.fromString(typeRaw);
            if (type == ServerType.UNKNOWN) {
                continue;
            }
            if (isRestarting(doc)) {
                continue;
            }
            if (status != null && !status.equalsIgnoreCase("online")) {
                continue;
            }
            if (heartbeat != null && now - heartbeat > config.getRegistryStaleSeconds() * 1000L) {
                continue;
            }
            if (drainingIds.contains(idKey) || drainingAddresses.contains(addressKey)) {
                state = "DRAINING";
            }
            RegistryEntry entry = new RegistryEntry(serverId, id, serverId, address, port, type,
                    players == null ? 0 : players,
                    maxPlayers == null ? 0 : maxPlayers,
                    state);
            long heartbeatValue = heartbeat == null ? Long.MIN_VALUE : heartbeat;
            Long current = heartbeatByServerId.get(idKey);
            if (current != null && current >= heartbeatValue) {
                continue;
            }
            heartbeatByServerId.put(idKey, heartbeatValue);
            nextByServerId.put(idKey, entry);
        }

        Map<UUID, RegistryEntry> next = new HashMap<>();
        for (RegistryEntry entry : nextByServerId.values()) {
            next.put(entry.id, entry);
        }
        updateRegistered(next);
    }

    private boolean isRestarting(Document doc) {
        if (doc == null) {
            return false;
        }
        Object restartingValue = doc.get("restarting");
        if (!(restartingValue instanceof Boolean) || !((Boolean) restartingValue)) {
            return false;
        }
        Long requestedAt = doc.getLong("restartRequestedAt");
        if (requestedAt == null || requestedAt <= 0L) {
            return false;
        }
        long age = System.currentTimeMillis() - requestedAt;
        return age >= 0L && age <= RESTART_MARKER_MAX_AGE_MILLIS;
    }

    private String normalizeLookupKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void cleanupStaleRecords(MongoCollection<Document> collection, String group, long now) {
        long cutoff = now - STALE_HEARTBEAT_MILLIS;
        org.bson.conversions.Bson staleFilter = Filters.or(
                Filters.exists("lastHeartbeat", false),
                Filters.lt("lastHeartbeat", cutoff)
        );
        org.bson.conversions.Bson scopedFilter = group == null || group.isEmpty()
                ? staleFilter
                : Filters.and(Filters.eq("group", group), staleFilter);
        collection.updateMany(scopedFilter, new Document("$set", new Document("status", "offline")));
        collection.deleteMany(scopedFilter);
    }

    public List<RegisteredServer> getGameServers() {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        List<RegisteredServer> servers = new ArrayList<>();
        Map<UUID, RegistryEntry> snapshot = entries;
        for (RegistryEntry entry : snapshot.values()) {
            if (!entry.type.isGame()) {
                continue;
            }
            proxy.getServer(entry.name).ifPresent(servers::add);
        }
        return servers;
    }

    public List<RegisteredServer> getHubServers() {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        List<RegisteredServer> servers = new ArrayList<>();
        Map<UUID, RegistryEntry> snapshot = entries;
        for (RegistryEntry entry : snapshot.values()) {
            if (!entry.type.isHub()) {
                continue;
            }
            proxy.getServer(entry.name).ifPresent(servers::add);
        }
        return servers;
    }

    public Optional<String> findAvailableGameServerName(ServerType type) {
        return findAvailableGameServerName(type, null);
    }

    public Optional<String> findAvailableGameServerName(ServerType type, String excludedName) {
        return findAvailableGameServerName(type, excludedName, 1);
    }

    public Optional<String> findAvailableGameServerName(ServerType type, String excludedName, int requiredOpenSlots) {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        RegistryEntry best = pickBestGameEntry(type, excludedName, requiredOpenSlots);
        if (best == null) {
            refreshIfStale(0L);
            best = pickBestGameEntry(type, excludedName, requiredOpenSlots);
        }
        return Optional.ofNullable(best == null ? null : best.name);
    }

    public Optional<RegisteredServer> findAvailableGameServer(ServerType type, String excludedName) {
        return findAvailableGameServer(type, excludedName, 1);
    }

    public Optional<RegisteredServer> findAvailableGameServer(ServerType type,
                                                              String excludedName,
                                                              int requiredOpenSlots) {
        Optional<String> name = findAvailableGameServerName(type, excludedName, requiredOpenSlots);
        if (!name.isPresent()) {
            return Optional.empty();
        }
        return proxy.getServer(name.get());
    }

    public Optional<RegisteredServer> findServerByRegistryId(String registryId) {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        RegistryEntry entry = pickServerByRegistryId(registryId);
        if (entry == null) {
            refreshIfStale(0L);
            entry = pickServerByRegistryId(registryId);
        }
        if (entry == null) {
            return Optional.empty();
        }
        return proxy.getServer(entry.name);
    }

    public Optional<RegisteredServer> findHubServerFor(ServerType sourceType) {
        return findHubServerFor(sourceType, null);
    }

    public Optional<RegisteredServer> findHubServerFor(ServerType sourceType, String excludedName) {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        ServerType preferredHub = sourceType == null ? ServerType.UNKNOWN : sourceType.toHubType();
        RegistryEntry preferred = pickBestHubEntry(preferredHub, excludedName);
        if (preferred == null) {
            RegistryEntry any = pickBestHubEntry(ServerType.UNKNOWN, excludedName);
            if (any != null) {
                return proxy.getServer(any.name);
            }
            refreshIfStale(0L);
            preferred = pickBestHubEntry(preferredHub, excludedName);
            if (preferred == null) {
                RegistryEntry retriedAny = pickBestHubEntry(ServerType.UNKNOWN, excludedName);
                if (retriedAny == null) {
                    return Optional.empty();
                }
                return proxy.getServer(retriedAny.name);
            }
        }
        return proxy.getServer(preferred.name);
    }

    public Optional<ServerType> findServerType(String serverId) {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        if (serverId == null || serverId.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<ServerType> found = findServerTypeInSnapshot(serverId);
        if (found.isPresent()) {
            return found;
        }
        refreshIfStale(0L);
        return findServerTypeInSnapshot(serverId);
    }

    public Optional<ServerDetails> findServerDetails(String serverId) {
        refreshIfStale(LOOKUP_REFRESH_MAX_AGE_MILLIS);
        if (serverId == null || serverId.trim().isEmpty()) {
            return Optional.empty();
        }
        Optional<ServerDetails> found = findServerDetailsInSnapshot(serverId);
        if (found.isPresent()) {
            return found;
        }
        refreshIfStale(0L);
        return findServerDetailsInSnapshot(serverId);
    }

    public void markServerRestarting(String serverId) {
        String targetServerId = serverId == null ? "" : serverId.trim();
        if (targetServerId.isEmpty()) {
            return;
        }
        markServerRestartingInSnapshot(targetServerId);
        if (database == null || config == null) {
            return;
        }
        try {
            MongoCollection<Document> collection = database.getCollection(config.getRegistryCollection());
            org.bson.conversions.Bson filter = Filters.eq("_id", targetServerId);
            String group = config.getRegistryGroup() == null ? "" : config.getRegistryGroup().trim();
            if (!group.isEmpty()) {
                filter = Filters.and(filter, Filters.eq("group", group));
            }
            long now = System.currentTimeMillis();
            Document update = new Document("$set", new Document("status", "restarting")
                    .append("state", "WAITING_RESTART")
                    .append("restarting", true)
                    .append("restartRequestedAt", now)
                    .append("lastHeartbeat", now));
            collection.updateOne(filter, update);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to mark server {} as restarting in registry.", targetServerId, ex);
        }
    }

    private Optional<ServerType> findServerTypeInSnapshot(String serverId) {
        return findServerDetailsInSnapshot(serverId).map(ServerDetails::getType);
    }

    private Optional<ServerDetails> findServerDetailsInSnapshot(String serverId) {
        Map<UUID, RegistryEntry> snapshot = entries;
        for (RegistryEntry entry : snapshot.values()) {
            if (entry.name.equalsIgnoreCase(serverId)) {
                return Optional.of(new ServerDetails(
                        entry.name,
                        entry.type,
                        entry.state,
                        entry.players,
                        entry.maxPlayers
                ));
            }
        }
        return Optional.empty();
    }

    private void markServerRestartingInSnapshot(String serverId) {
        Map<UUID, RegistryEntry> snapshot = entries;
        if (snapshot.isEmpty()) {
            return;
        }
        Map<UUID, RegistryEntry> next = new HashMap<>(snapshot);
        boolean changed = false;
        for (Map.Entry<UUID, RegistryEntry> item : snapshot.entrySet()) {
            RegistryEntry current = item.getValue();
            if (current == null || !current.name.equalsIgnoreCase(serverId)) {
                continue;
            }
            next.put(item.getKey(), new RegistryEntry(
                    current.registryId,
                    current.id,
                    current.name,
                    current.address,
                    current.port,
                    current.type,
                    current.players,
                    current.maxPlayers,
                    "WAITING_RESTART"
            ));
            changed = true;
        }
        if (changed) {
            entries = Collections.unmodifiableMap(next);
        }
    }

    private void updateRegistered(Map<UUID, RegistryEntry> next) {
        Map<UUID, RegistryEntry> currentEntries = entries;
        Set<UUID> removed = new HashSet<>(currentEntries.keySet());
        removed.removeAll(next.keySet());
        for (UUID id : removed) {
            RegistryEntry entry = currentEntries.get(id);
            if (entry != null) {
                proxy.getServer(entry.name).ifPresent(server ->
                        proxy.unregisterServer(server.getServerInfo()));
            }
        }
        for (RegistryEntry entry : next.values()) {
            RegistryEntry current = currentEntries.get(entry.id);
            if (current != null && current.equals(entry)) {
                continue;
            }
            ServerInfo info = new ServerInfo(entry.name, new InetSocketAddress(entry.address, entry.port));
            proxy.registerServer(info);
        }
        entries = Collections.unmodifiableMap(new HashMap<>(next));
    }

    private RegistryEntry pickBestGameEntry(ServerType type, String excludedName, int requiredOpenSlots) {
        if (type == null || !type.isGame()) {
            return null;
        }
        RegistryEntry best = null;
        Map<UUID, RegistryEntry> snapshot = entries;
        for (RegistryEntry entry : snapshot.values()) {
            if (entry.type != type) {
                continue;
            }
            if (excludedName != null && entry.name.equalsIgnoreCase(excludedName)) {
                continue;
            }
            if (!entry.isAvailable(requiredOpenSlots)) {
                continue;
            }
            if (best == null || entry.players < best.players) {
                best = entry;
            }
        }
        return best;
    }

    private RegistryEntry pickBestHubEntry(ServerType preferredHubType, String excludedName) {
        RegistryEntry best = null;
        Map<UUID, RegistryEntry> snapshot = entries;
        for (RegistryEntry entry : snapshot.values()) {
            if (!entry.type.isHub()) {
                continue;
            }
            if (excludedName != null && entry.name.equalsIgnoreCase(excludedName)) {
                continue;
            }
            if (preferredHubType != null
                    && preferredHubType != ServerType.UNKNOWN
                    && entry.type != preferredHubType) {
                continue;
            }
            if (!entry.isAvailable(1)) {
                continue;
            }
            if (best == null || entry.players < best.players) {
                best = entry;
            }
        }
        return best;
    }

    private RegistryEntry pickServerByRegistryId(String registryId) {
        if (registryId == null || registryId.trim().isEmpty()) {
            return null;
        }
        String wanted = registryId.trim().toLowerCase(java.util.Locale.ROOT);
        RegistryEntry best = null;
        Map<UUID, RegistryEntry> snapshot = entries;
        for (RegistryEntry entry : snapshot.values()) {
            if (entry.registryId == null || !entry.registryId.equalsIgnoreCase(wanted)) {
                continue;
            }
            if (!entry.isAvailable(1)) {
                continue;
            }
            if (best == null || entry.players < best.players) {
                best = entry;
            }
        }
        return best;
    }

    private static class RegistryEntry {
        private final String registryId;
        private final UUID id;
        private final String name;
        private final String address;
        private final int port;
        private final ServerType type;
        private final int players;
        private final int maxPlayers;
        private final String state;

        private RegistryEntry(String registryId, UUID id, String name, String address, int port, ServerType type,
                              int players, int maxPlayers, String state) {
            this.registryId = registryId == null ? "" : registryId.trim().toLowerCase(java.util.Locale.ROOT);
            this.id = id;
            this.name = name;
            this.address = address;
            this.port = port;
            this.type = type;
            this.players = players;
            this.maxPlayers = maxPlayers;
            this.state = state;
        }

        private boolean equals(RegistryEntry other) {
            if (other == null) {
                return false;
            }
            return name.equals(other.name)
                    && address.equals(other.address)
                    && port == other.port
                    && type == other.type;
        }

        private boolean isAvailable(int requiredOpenSlots) {
            int needed = Math.max(1, requiredOpenSlots);
            if (maxPlayers > 0 && players + needed > maxPlayers) {
                return false;
            }
            if (state == null) {
                return true;
            }
            String normalized = state.trim().toUpperCase(java.util.Locale.ROOT);
            return !normalized.equals("IN_GAME")
                    && !normalized.equals("ENDING")
                    && !normalized.equals("RESTARTING")
                    && !normalized.equals("LOCKED")
                    && !normalized.equals("DRAINING")
                    && !normalized.equals("WAITING_RESTART");
        }
    }

    public static final class ServerDetails {
        private final String name;
        private final ServerType type;
        private final String state;
        private final int players;
        private final int maxPlayers;

        private ServerDetails(String name, ServerType type, String state, int players, int maxPlayers) {
            this.name = name;
            this.type = type == null ? ServerType.UNKNOWN : type;
            this.state = state == null ? "" : state;
            this.players = Math.max(0, players);
            this.maxPlayers = Math.max(0, maxPlayers);
        }

        public String getName() {
            return name;
        }

        public ServerType getType() {
            return type;
        }

        public String getState() {
            return state;
        }

        public int getPlayers() {
            return players;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }
    }
}
