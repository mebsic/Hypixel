package io.github.mebsic.murdermystery.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CitizensBodyService {
    private static final String CITIZENS_PLUGIN_NAME = "Citizens";
    private static final String[] SLEEP_TRAIT_CLASS_NAMES = {
            "net.citizensnpcs.trait.SleepTrait",
            "net.citizensnpcs.trait.versioned.SleepTrait"
    };
    private static final String[] ENTITY_POSE_TRAIT_CLASS_NAMES = {
            "net.citizensnpcs.trait.EntityPoseTrait",
            "net.citizensnpcs.trait.versioned.EntityPoseTrait"
    };
    private static final String[] ENTITY_POSE_ENUM_CLASS_NAMES = {
            "net.citizensnpcs.trait.EntityPoseTrait$EntityPose",
            "net.citizensnpcs.trait.versioned.EntityPoseTrait$EntityPose"
    };
    private static final String[] SKIN_TRAIT_CLASS_NAMES = {
            "net.citizensnpcs.trait.SkinTrait"
    };
    private static final String SLEEPING_POSE_NAME = "SLEEPING";
    private static final String METADATA_NAMEPLATE_VISIBLE = "NAMEPLATE_VISIBLE";
    private static final String METADATA_ALWAYS_USE_NAME_HOLOGRAM = "ALWAYS_USE_NAME_HOLOGRAM";
    private static final String METADATA_RESET_PITCH_ON_TICK = "RESET_PITCH_ON_TICK";
    private static final String CORPSE_NPC_NAME_PREFIX = "Body";
    private static final String CORPSE_NAMETAG_TEAM = "mm_body_tag";

    private final JavaPlugin plugin;
    private final ClassLoader citizensClassLoader;
    private final Object npcRegistry;
    private final Class<?> sleepTraitClass;
    private final Class<?> entityPoseTraitClass;
    private final Class<?> entityPoseEnumClass;
    private final Object sleepingPose;
    private final Class<?> skinTraitClass;
    private final Map<UUID, Integer> corpseNpcIdsByVictim = new HashMap<UUID, Integer>();
    private final Map<UUID, UUID> corpseEntityUuidByVictim = new HashMap<UUID, UUID>();
    private final Set<UUID> corpseEntityUuids = new HashSet<UUID>();
    private int npcSequence;

    public CitizensBodyService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.citizensClassLoader = resolveCitizensClassLoader(plugin);
        this.npcRegistry = resolveNpcRegistry(plugin);
        this.sleepTraitClass = resolveAnyClass(SLEEP_TRAIT_CLASS_NAMES);
        this.entityPoseTraitClass = resolveAnyClass(ENTITY_POSE_TRAIT_CLASS_NAMES);
        this.entityPoseEnumClass = resolveAnyClass(ENTITY_POSE_ENUM_CLASS_NAMES);
        this.sleepingPose = resolveEnumConstant(entityPoseEnumClass, SLEEPING_POSE_NAME);
        this.skinTraitClass = resolveAnyClass(SKIN_TRAIT_CLASS_NAMES);
        this.npcSequence = 0;
    }

    public boolean isEnabled() {
        return npcRegistry != null;
    }

    public void spawnCorpse(Player victim) {
        if (!isEnabled() || victim == null || victim.getWorld() == null) {
            return;
        }
        UUID victimUuid = victim.getUniqueId();
        if (victimUuid == null) {
            return;
        }
        removeCorpseByVictim(victimUuid);

        Object npc = createNpc(nextNpcName());
        if (npc == null) {
            return;
        }

        Location spawnLocation = victim.getLocation().clone();
        spawnLocation.setPitch(0.0f);

        try {
            setNpcTransient(npc);
            hideNpcIdentity(npc);
            applyNpcSkin(npc, victim);
            if (!spawnNpc(npc, spawnLocation)) {
                destroyNpc(npc);
                return;
            }
            // Re-apply immediately after spawn so the corpse skin is set in the same tick.
            applyNpcSkin(npc, victim);
            setSleepingPose(npc, spawnLocation);
            Entity entity = resolveNpcEntity(npc);
            Integer npcId = resolveNpcId(npc);
            if (entity == null || entity.getUniqueId() == null || npcId == null || npcId.intValue() <= 0) {
                destroyNpc(npc);
                return;
            }
            hideEntityIdentity(entity);
            corpseNpcIdsByVictim.put(victimUuid, npcId);
            corpseEntityUuidByVictim.put(victimUuid, entity.getUniqueId());
            corpseEntityUuids.add(entity.getUniqueId());
            scheduleSingleSleepingPoseRefresh(npc, spawnLocation);
        } catch (Throwable ignored) {
            destroyNpc(npc);
        }
    }

    private void scheduleSingleSleepingPoseRefresh(Object npc, Location location) {
        if (plugin == null || npc == null || location == null || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isNpcSpawned(npc)) {
                return;
            }
            setSleepingPose(npc, location);
            hideNpcIdentity(npc);
            Entity refreshed = resolveNpcEntity(npc);
            hideEntityIdentity(refreshed);
        }, 1L);
    }

    public boolean isCorpseEntity(Entity entity) {
        if (entity == null || corpseEntityUuids.isEmpty()) {
            return false;
        }
        UUID uuid = entity.getUniqueId();
        if (uuid == null) {
            return false;
        }
        if (corpseEntityUuids.contains(uuid)) {
            return true;
        }
        cleanupStaleEntityUuids();
        return corpseEntityUuids.contains(uuid);
    }

    public void clear() {
        if (corpseNpcIdsByVictim.isEmpty() && corpseEntityUuids.isEmpty() && corpseEntityUuidByVictim.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<Integer>(corpseNpcIdsByVictim.values());
        for (Integer npcId : ids) {
            if (npcId == null || npcId.intValue() <= 0) {
                continue;
            }
            removeNpcById(npcId.intValue());
        }
        for (UUID entityUuid : new ArrayList<UUID>(corpseEntityUuids)) {
            removeEntityByUuid(entityUuid);
        }
        for (UUID entityUuid : new ArrayList<UUID>(corpseEntityUuidByVictim.values())) {
            removeEntityByUuid(entityUuid);
        }
        corpseNpcIdsByVictim.clear();
        corpseEntityUuidByVictim.clear();
        corpseEntityUuids.clear();
        npcSequence = 0;
    }

    private Object resolveNpcRegistry(JavaPlugin plugin) {
        if (plugin == null || plugin.getServer() == null) {
            return null;
        }
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        if (pluginManager == null) {
            return null;
        }
        Plugin citizens = pluginManager.getPlugin(CITIZENS_PLUGIN_NAME);
        if (citizens == null || !citizens.isEnabled()) {
            return null;
        }
        try {
            Class<?> citizensApi = resolveClass("net.citizensnpcs.api.CitizensAPI");
            Method getNpcRegistry = citizensApi.getMethod("getNPCRegistry");
            return getNpcRegistry.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ClassLoader resolveCitizensClassLoader(JavaPlugin plugin) {
        if (plugin == null || plugin.getServer() == null) {
            return null;
        }
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        if (pluginManager == null) {
            return null;
        }
        Plugin citizens = pluginManager.getPlugin(CITIZENS_PLUGIN_NAME);
        if (citizens == null || !citizens.isEnabled()) {
            return null;
        }
        return citizens.getClass().getClassLoader();
    }

    private Class<?> resolveAnyClass(String[] classNames) {
        if (classNames == null || classNames.length == 0) {
            return null;
        }
        for (String className : classNames) {
            Class<?> resolved = resolveClass(className);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private Class<?> resolveClass(String className) {
        if (className == null || className.trim().isEmpty()) {
            return null;
        }
        try {
            if (citizensClassLoader != null) {
                return Class.forName(className.trim(), true, citizensClassLoader);
            }
            return Class.forName(className.trim(), true, getClass().getClassLoader());
        } catch (Throwable ignored) {
            try {
                return Class.forName(className.trim());
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveEnumConstant(Class<?> enumClass, String constant) {
        if (enumClass == null || constant == null || constant.trim().isEmpty() || !enumClass.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), constant.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void removeCorpseByVictim(UUID victimUuid) {
        if (victimUuid == null) {
            return;
        }
        Integer previousNpcId = corpseNpcIdsByVictim.remove(victimUuid);
        UUID previousEntityUuid = corpseEntityUuidByVictim.remove(victimUuid);
        if (previousNpcId != null && previousNpcId.intValue() > 0) {
            removeNpcById(previousNpcId.intValue());
        }
        if (previousEntityUuid != null) {
            corpseEntityUuids.remove(previousEntityUuid);
            removeEntityByUuid(previousEntityUuid);
        }
    }

    private Object createNpc(String name) {
        if (npcRegistry == null) {
            return null;
        }
        try {
            Method createNpc = npcRegistry.getClass().getMethod("createNPC", EntityType.class, String.class);
            return createNpc.invoke(npcRegistry, EntityType.PLAYER, name);
        } catch (Throwable ignored) {
            Method[] methods = npcRegistry.getClass().getMethods();
            for (Method method : methods) {
                if (method == null || !"createNPC".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 2) {
                    continue;
                }
                if (!params[0].isAssignableFrom(EntityType.class) || !params[1].isAssignableFrom(String.class)) {
                    continue;
                }
                Object[] args = new Object[params.length];
                args[0] = EntityType.PLAYER;
                args[1] = name;
                for (int i = 2; i < params.length; i++) {
                    args[i] = null;
                }
                try {
                    return method.invoke(npcRegistry, args);
                } catch (Throwable ignoredAgain) {
                    // Try next overload.
                }
            }
            return null;
        }
    }

    private boolean spawnNpc(Object npc, Location location) {
        if (npc == null || location == null) {
            return false;
        }
        try {
            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            Object result = spawn.invoke(npc, location);
            return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
        } catch (Throwable ignored) {
            Method[] methods = npc.getClass().getMethods();
            for (Method method : methods) {
                if (method == null || !"spawn".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || !params[0].isAssignableFrom(Location.class)) {
                    continue;
                }
                try {
                    Object result = method.invoke(npc, location);
                    return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
                } catch (Throwable ignoredAgain) {
                    // Try next overload.
                }
            }
            return false;
        }
    }

    private boolean setSleepingPose(Object npc, Location location) {
        if (npc == null || location == null) {
            return false;
        }
        boolean appliedSleepTrait = false;
        if (sleepTraitClass != null) {
            Object sleepTrait = getOrAddTrait(npc, sleepTraitClass);
            if (sleepTrait != null) {
                appliedSleepTrait = invokeMethod(
                        sleepTrait,
                        "setSleeping",
                        new Class<?>[]{Location.class},
                        new Object[]{location.clone()}
                );
                if (!appliedSleepTrait) {
                    appliedSleepTrait = invokeLocationMethod(sleepTrait, location.clone(), "setSleeping", "setAsleep", "sleep");
                }
                // Force one immediate tick so pose applies right away.
                invokeMethod(sleepTrait, "run", new Class<?>[0], new Object[0]);
            }
        }
        if (entityPoseTraitClass != null && entityPoseEnumClass != null && sleepingPose != null) {
            Object poseTrait = getOrAddTrait(npc, entityPoseTraitClass);
            if (poseTrait != null) {
                invokeMethod(poseTrait, "setPose", new Class<?>[]{entityPoseEnumClass}, new Object[]{sleepingPose});
            }
        }
        return appliedSleepTrait;
    }

    private boolean invokeLocationMethod(Object target, Location location, String... methodNames) {
        if (target == null || location == null || methodNames == null) {
            return false;
        }
        Block block = location.getBlock();
        for (String methodName : methodNames) {
            if (methodName == null || methodName.trim().isEmpty()) {
                continue;
            }
            Method[] methods = target.getClass().getMethods();
            for (Method method : methods) {
                if (method == null || !methodName.equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                Object[] args = null;
                if (params.length == 1) {
                    if (params[0].isAssignableFrom(Location.class)) {
                        args = new Object[]{location};
                    } else if (params[0].isAssignableFrom(Block.class)) {
                        args = new Object[]{block};
                    }
                } else if (params.length == 2 && (params[1] == boolean.class || params[1] == Boolean.class)) {
                    if (params[0].isAssignableFrom(Location.class)) {
                        args = new Object[]{location, Boolean.TRUE};
                    } else if (params[0].isAssignableFrom(Block.class)) {
                        args = new Object[]{block, Boolean.TRUE};
                    }
                }
                if (args == null) {
                    continue;
                }
                try {
                    method.invoke(target, args);
                    return true;
                } catch (Throwable ignored) {
                    // Try next variant.
                }
            }
        }
        return false;
    }

    private void setNpcTransient(Object npc) {
        if (npc == null) {
            return;
        }
        invokeMethod(npc, "setProtected", new Class<?>[]{boolean.class}, new Object[]{Boolean.TRUE});
        invokeMethod(npc, "setAlwaysUseNameHologram", new Class<?>[]{boolean.class}, new Object[]{Boolean.FALSE});
        applyNameplateTraitVisibility(npc, false);
        try {
            Method setShouldSave = npc.getClass().getMethod("setShouldSave", boolean.class);
            setShouldSave.invoke(npc, Boolean.FALSE);
        } catch (Throwable ignored) {
            // Citizens variants differ; optional best effort only.
        }
        setNpcMetadataPersistent(npc, METADATA_NAMEPLATE_VISIBLE, Boolean.FALSE);
        setNpcMetadataPersistent(npc, METADATA_ALWAYS_USE_NAME_HOLOGRAM, Boolean.FALSE);
        setNpcMetadataPersistent(npc, METADATA_RESET_PITCH_ON_TICK, Boolean.FALSE);
    }

    private void hideNpcIdentity(Object npc) {
        if (npc == null) {
            return;
        }
        applyNameplateTraitVisibility(npc, false);
        invokeMethod(npc, "updateCustomName", new Class<?>[0], new Object[0]);
    }

    private void hideEntityIdentity(Entity entity) {
        if (entity == null) {
            return;
        }
        entity.setCustomNameVisible(false);
        entity.setCustomName(null);
        hideEntityNameTagWithScoreboard(entity);
    }

    private void applyNpcSkin(Object npc, Player ownerPlayer) {
        if (npc == null || ownerPlayer == null) {
            return;
        }
        String owner = normalizeSkinOwner(ownerPlayer.getName());
        if (owner.isEmpty()) {
            owner = "Steve";
        }

        if (skinTraitClass != null) {
            Object skinTrait = getOrAddTrait(npc, skinTraitClass);
            if (skinTrait != null) {
                // Corpses should mirror the victim skin immediately and avoid stale texture cache.
                invokeMethod(skinTrait, "setFetchDefaultSkin", new Class<?>[]{boolean.class}, new Object[]{Boolean.TRUE});
                setSkinTraitBoolean(skinTrait, true, "setShouldUpdateSkins", "setUpdateSkins", "setUseLatestSkin");
                invokeMethod(skinTrait, "setSkinPersistent", new Class<?>[]{Player.class}, new Object[]{ownerPlayer});
                applySkinTraitName(skinTrait, owner, true);
            }
        }

        setNpcMetadataPersistent(npc, "PLAYER_SKIN_USE_LATEST", Boolean.TRUE);
        setNpcMetadataPersistent(npc, "PLAYER_SKIN_UUID", owner);
        setNpcDataPersistent(npc, "cached-skin-uuid-name", owner);
        setNpcDataPersistent(npc, "player-skin-name", owner);
        setNpcDataPersistent(npc, "cached-texture", "");
        setNpcDataPersistent(npc, "cached-signature", "");
        UUID ownerUuid = ownerPlayer.getUniqueId();
        if (ownerUuid != null) {
            String ownerUuidText = ownerUuid.toString().trim();
            if (!ownerUuidText.isEmpty()) {
                setNpcDataPersistent(npc, "cached-skin-uuid", ownerUuidText);
            }
        }
    }

    private String normalizeSkinOwner(String ownerName) {
        String owner = ownerName == null ? "" : ownerName.trim();
        if (owner.isEmpty()) {
            return "";
        }
        owner = owner.replaceAll("\\s+", "");
        if (owner.length() > 16) {
            owner = owner.substring(0, 16);
        }
        return owner;
    }

    private Object getOrAddTrait(Object npc, Class<?> traitClass) {
        if (npc == null || traitClass == null) {
            return null;
        }
        try {
            Method getOrAddTrait = npc.getClass().getMethod("getOrAddTrait", Class.class);
            return getOrAddTrait.invoke(npc, traitClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean applySkinTraitName(Object skinTrait, String owner, boolean forceRefresh) {
        if (skinTrait == null || owner == null || owner.trim().isEmpty()) {
            return false;
        }
        String normalizedOwner = owner.trim();
        if (forceRefresh) {
            try {
                Method setSkinName = skinTrait.getClass().getMethod("setSkinName", String.class, boolean.class);
                setSkinName.invoke(skinTrait, normalizedOwner, Boolean.TRUE);
                return true;
            } catch (Throwable ignored) {
                // Fall through to legacy variants.
            }
        }
        if (invokeMethod(skinTrait, "setSkinName", new Class<?>[]{String.class}, new Object[]{normalizedOwner})) {
            return true;
        }
        return invokeMethod(skinTrait, "setSkinPersistent", new Class<?>[]{String.class}, new Object[]{normalizedOwner});
    }

    private void setSkinTraitBoolean(Object skinTrait, boolean value, String... methodNames) {
        if (skinTrait == null || methodNames == null) {
            return;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.trim().isEmpty()) {
                continue;
            }
            if (invokeMethod(skinTrait, methodName, new Class<?>[]{boolean.class}, new Object[]{Boolean.valueOf(value)})) {
                return;
            }
            if (invokeMethod(skinTrait, methodName, new Class<?>[]{Boolean.class}, new Object[]{Boolean.valueOf(value)})) {
                return;
            }
        }
    }

    private boolean invokeMethod(Object target, String methodName, Class<?>[] parameterTypes, Object[] args) {
        if (target == null || methodName == null || methodName.trim().isEmpty()) {
            return false;
        }
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyNameplateTraitVisibility(Object npc, boolean visible) {
        if (npc == null) {
            return;
        }
        applyTraitBoolean(
                npc,
                "net.citizensnpcs.trait.NameplateTrait",
                visible,
                "setVisible",
                "setNameVisible",
                "setNameplateVisible"
        );
        applyTraitBoolean(
                npc,
                "net.citizensnpcs.trait.versioned.NameplateTrait",
                visible,
                "setVisible",
                "setNameVisible",
                "setNameplateVisible"
        );
        applyTraitBoolean(
                npc,
                "net.citizensnpcs.trait.HologramTrait",
                visible,
                "setNameVisible",
                "setVisible",
                "setUseNameHologram"
        );
        applyTraitBoolean(
                npc,
                "net.citizensnpcs.trait.versioned.HologramTrait",
                visible,
                "setNameVisible",
                "setVisible",
                "setUseNameHologram"
        );
    }

    private void applyTraitBoolean(Object npc, String traitClassName, boolean value, String... methodNames) {
        if (npc == null || traitClassName == null || traitClassName.trim().isEmpty() || methodNames == null) {
            return;
        }
        Class<?> traitClass = resolveClass(traitClassName);
        if (traitClass == null) {
            return;
        }
        Object trait = getOrAddTrait(npc, traitClass);
        if (trait == null) {
            return;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.trim().isEmpty()) {
                continue;
            }
            if (invokeMethod(trait, methodName, new Class<?>[]{boolean.class}, new Object[]{Boolean.valueOf(value)})) {
                return;
            }
            if (invokeMethod(trait, methodName, new Class<?>[]{Boolean.class}, new Object[]{Boolean.valueOf(value)})) {
                return;
            }
        }
    }

    private void setNpcDataPersistent(Object npc, String key, Object value) {
        if (npc == null || key == null || key.trim().isEmpty()) {
            return;
        }
        Object data = invokeNoArg(npc, "data");
        if (data == null) {
            return;
        }
        invokeMethod(data, "setPersistent", new Class<?>[]{String.class, Object.class}, new Object[]{key, value});
    }

    private void setNpcMetadataPersistent(Object npc, String metadataName, Object value) {
        if (npc == null || metadataName == null || metadataName.trim().isEmpty()) {
            return;
        }
        Class<?> metadataClass = resolveClass("net.citizensnpcs.api.npc.NPC$Metadata");
        if (metadataClass == null || !metadataClass.isEnum()) {
            return;
        }
        Object metadata = resolveEnumConstant(metadataClass, metadataName.trim());
        if (metadata == null) {
            return;
        }
        Object data = invokeNoArg(npc, "data");
        if (data == null) {
            return;
        }
        invokeMethod(
                data,
                "setPersistent",
                new Class<?>[]{metadataClass, Object.class},
                new Object[]{metadata, value}
        );
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.trim().isEmpty()) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Integer resolveNpcId(Object npc) {
        Object value = invokeNoArg(npc, "getId");
        if (!(value instanceof Number)) {
            return null;
        }
        return Integer.valueOf(((Number) value).intValue());
    }

    private Entity resolveNpcEntity(Object npc) {
        Object value = invokeNoArg(npc, "getEntity");
        if (!(value instanceof Entity)) {
            return null;
        }
        return (Entity) value;
    }

    private Object resolveNpcById(int id) {
        if (npcRegistry == null || id <= 0) {
            return null;
        }
        try {
            Method getById = npcRegistry.getClass().getMethod("getById", int.class);
            return getById.invoke(npcRegistry, Integer.valueOf(id));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isNpcSpawned(Object npc) {
        if (npc == null) {
            return false;
        }
        try {
            Method isSpawned = npc.getClass().getMethod("isSpawned");
            Object result = isSpawned.invoke(npc);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Throwable ignored) {
            Entity entity = resolveNpcEntity(npc);
            return entity != null && entity.isValid() && !entity.isDead();
        }
    }

    private void removeNpcById(int id) {
        Object npc = resolveNpcById(id);
        if (npc == null) {
            return;
        }
        destroyNpc(npc);
    }

    private void destroyNpc(Object npc) {
        if (npc == null) {
            return;
        }
        Entity entity = resolveNpcEntity(npc);
        if (entity != null && entity.getUniqueId() != null) {
            corpseEntityUuids.remove(entity.getUniqueId());
        }
        if (!invokeMethod(npc, "destroy", new Class<?>[0], new Object[0])) {
            invokeMethod(npc, "despawn", new Class<?>[0], new Object[0]);
        }
        removeEntityNameTagOverride(entity);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        deregisterNpc(npc);
    }

    private void deregisterNpc(Object npc) {
        if (npcRegistry == null || npc == null) {
            return;
        }
        Method[] methods = npcRegistry.getClass().getMethods();
        for (Method method : methods) {
            if (method == null || !"deregister".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || !params[0].isAssignableFrom(npc.getClass())) {
                continue;
            }
            try {
                method.invoke(npcRegistry, npc);
            } catch (Throwable ignored) {
                // Best effort cleanup only.
            }
            return;
        }
    }

    private void cleanupStaleEntityUuids() {
        if (corpseEntityUuids.isEmpty()) {
            return;
        }
        Set<UUID> existing = new HashSet<UUID>();
        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (entity == null || entity.getUniqueId() == null) {
                    continue;
                }
                UUID uuid = entity.getUniqueId();
                if (corpseEntityUuids.contains(uuid)) {
                    existing.add(uuid);
                }
            }
        }
        corpseEntityUuids.retainAll(existing);
    }

    private void removeEntityByUuid(UUID uuid) {
        if (uuid == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Entity entity : world.getEntities()) {
                if (entity == null || entity.getUniqueId() == null) {
                    continue;
                }
                if (!uuid.equals(entity.getUniqueId())) {
                    continue;
                }
                removeEntityNameTagOverride(entity);
                entity.remove();
                return;
            }
        }
    }

    private void hideEntityNameTagWithScoreboard(Entity entity) {
        if (entity == null || entity.getName() == null || entity.getName().trim().isEmpty()) {
            return;
        }
        String entry = entity.getName();
        ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager != null) {
            applyNameTagHiddenToScoreboard(scoreboardManager.getMainScoreboard(), entry);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == null) {
                continue;
            }
            applyNameTagHiddenToScoreboard(viewer.getScoreboard(), entry);
        }
    }

    private void removeEntityNameTagOverride(Entity entity) {
        if (entity == null || entity.getName() == null || entity.getName().trim().isEmpty()) {
            return;
        }
        String entry = entity.getName();
        ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager != null) {
            removeNameTagHiddenFromScoreboard(scoreboardManager.getMainScoreboard(), entry);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer == null) {
                continue;
            }
            removeNameTagHiddenFromScoreboard(viewer.getScoreboard(), entry);
        }
    }

    private void applyNameTagHiddenToScoreboard(Scoreboard scoreboard, String entry) {
        if (scoreboard == null || entry == null || entry.trim().isEmpty()) {
            return;
        }
        Team team = scoreboard.getTeam(CORPSE_NAMETAG_TEAM);
        if (team == null) {
            try {
                team = scoreboard.registerNewTeam(CORPSE_NAMETAG_TEAM);
            } catch (Throwable ignored) {
                team = scoreboard.getTeam(CORPSE_NAMETAG_TEAM);
            }
        }
        if (team == null) {
            return;
        }
        try {
            team.setNameTagVisibility(NameTagVisibility.NEVER);
        } catch (Throwable ignored) {
            // Ignore if visibility API differs on server variant.
        }
        if (!team.hasEntry(entry)) {
            team.addEntry(entry);
        }
    }

    private void removeNameTagHiddenFromScoreboard(Scoreboard scoreboard, String entry) {
        if (scoreboard == null || entry == null || entry.trim().isEmpty()) {
            return;
        }
        Team team = scoreboard.getTeam(CORPSE_NAMETAG_TEAM);
        if (team == null) {
            return;
        }
        if (team.hasEntry(entry)) {
            team.removeEntry(entry);
        }
    }

    private String nextNpcName() {
        npcSequence++;
        String suffix = Integer.toString(npcSequence);
        int maxPrefixLength = Math.max(1, 16 - suffix.length());
        String prefix = CORPSE_NPC_NAME_PREFIX;
        if (prefix.length() > maxPrefixLength) {
            prefix = prefix.substring(0, maxPrefixLength);
        }
        String name = prefix + suffix;
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        String safe = name == null ? "" : name.trim();
        if (safe.isEmpty()) {
            return "Body";
        }
        return safe.toUpperCase(Locale.ROOT);
    }
}
