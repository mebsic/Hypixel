package io.github.mebsic.game.service;

import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class TitleService {

    public void reset(Player player) {
        send(player, "", "", 0, 0, 0);
    }

    public void send(Player player,
                     String title,
                     String subtitle,
                     int fadeInTicks,
                     int stayTicks,
                     int fadeOutTicks) {
        if (player == null || !player.isOnline()) {
            return;
        }
        String safeTitle = title == null ? "" : title;
        String safeSubtitle = subtitle == null ? "" : subtitle;
        int fadeIn = Math.max(0, fadeInTicks);
        int stay = Math.max(0, stayTicks);
        int fadeOut = Math.max(0, fadeOutTicks);
        if (tryModernApi(player, safeTitle, safeSubtitle, fadeIn, stay, fadeOut)) {
            return;
        }
        tryLegacyApi(player, safeTitle, safeSubtitle, fadeIn, stay, fadeOut);
    }

    private boolean tryModernApi(Player player,
                                 String title,
                                 String subtitle,
                                 int fadeInTicks,
                                 int stayTicks,
                                 int fadeOutTicks) {
        try {
            player.getClass()
                    .getMethod("sendTitle", String.class, String.class, int.class, int.class, int.class)
                    .invoke(player, title, subtitle, fadeInTicks, stayTicks, fadeOutTicks);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void tryLegacyApi(Player player,
                              String title,
                              String subtitle,
                              int fadeInTicks,
                              int stayTicks,
                              int fadeOutTicks) {
        try {
            String version = player.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object handle = craftPlayerClass.getMethod("getHandle").invoke(player);
            Object connection = handle.getClass().getField("playerConnection").get(handle);

            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Class<?> packetTitleClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutTitle");
            Class<?> enumTitleActionClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutTitle$EnumTitleAction");
            Class<?> iChatBaseComponentClass = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent");
            Class<?> serializerClass = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent$ChatSerializer");

            Method serializerMethod = serializerClass.getMethod("a", String.class);
            Method sendPacketMethod = connection.getClass().getMethod("sendPacket", packetClass);

            Object titleAction = enumValue(enumTitleActionClass, "TITLE");
            Object subtitleAction = enumValue(enumTitleActionClass, "SUBTITLE");
            Object timesAction = enumValue(enumTitleActionClass, "TIMES");

            Object titleComponent = serializerMethod.invoke(null, toJson(title));
            Object subtitleComponent = serializerMethod.invoke(null, toJson(subtitle));
            Object emptyComponent = serializerMethod.invoke(null, toJson(""));

            Object timesPacket = createTimesPacket(packetTitleClass,
                    enumTitleActionClass,
                    iChatBaseComponentClass,
                    timesAction,
                    emptyComponent,
                    fadeInTicks,
                    stayTicks,
                    fadeOutTicks);
            Object titlePacket = createTextPacket(packetTitleClass,
                    enumTitleActionClass,
                    iChatBaseComponentClass,
                    titleAction,
                    titleComponent,
                    fadeInTicks,
                    stayTicks,
                    fadeOutTicks);
            Object subtitlePacket = createTextPacket(packetTitleClass,
                    enumTitleActionClass,
                    iChatBaseComponentClass,
                    subtitleAction,
                    subtitleComponent,
                    fadeInTicks,
                    stayTicks,
                    fadeOutTicks);

            sendPacketMethod.invoke(connection, timesPacket);
            sendPacketMethod.invoke(connection, titlePacket);
            sendPacketMethod.invoke(connection, subtitlePacket);
        } catch (Exception ignored) {
        }
    }

    private Object createTimesPacket(Class<?> packetTitleClass,
                                     Class<?> enumTitleActionClass,
                                     Class<?> iChatBaseComponentClass,
                                     Object timesAction,
                                     Object emptyComponent,
                                     int fadeInTicks,
                                     int stayTicks,
                                     int fadeOutTicks) throws Exception {
        try {
            Constructor<?> constructor = packetTitleClass.getConstructor(int.class, int.class, int.class);
            return constructor.newInstance(fadeInTicks, stayTicks, fadeOutTicks);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> constructor = packetTitleClass.getConstructor(
                    enumTitleActionClass,
                    iChatBaseComponentClass,
                    int.class,
                    int.class,
                    int.class
            );
            return constructor.newInstance(timesAction, emptyComponent, fadeInTicks, stayTicks, fadeOutTicks);
        }
    }

    private Object createTextPacket(Class<?> packetTitleClass,
                                    Class<?> enumTitleActionClass,
                                    Class<?> iChatBaseComponentClass,
                                    Object action,
                                    Object component,
                                    int fadeInTicks,
                                    int stayTicks,
                                    int fadeOutTicks) throws Exception {
        try {
            Constructor<?> constructor = packetTitleClass.getConstructor(enumTitleActionClass, iChatBaseComponentClass);
            return constructor.newInstance(action, component);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> constructor = packetTitleClass.getConstructor(
                    enumTitleActionClass,
                    iChatBaseComponentClass,
                    int.class,
                    int.class,
                    int.class
            );
            return constructor.newInstance(action, component, fadeInTicks, stayTicks, fadeOutTicks);
        }
    }

    @SuppressWarnings("unchecked")
    private Object enumValue(Class<?> enumClass, String value) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), value);
    }

    private String toJson(String text) {
        String safe = text == null ? "" : text;
        safe = safe.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"text\":\"" + safe + "\"}";
    }
}
