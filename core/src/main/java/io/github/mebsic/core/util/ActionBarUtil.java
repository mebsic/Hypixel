package io.github.mebsic.core.util;

import org.bukkit.entity.Player;

public final class ActionBarUtil {
    private ActionBarUtil() {
    }

    public static void send(Player player, String message) {
        if (player == null) {
            return;
        }
        if (trySpigotActionBar(player, message)) {
            return;
        }
        sendLegacyActionBar(player, message);
    }

    private static boolean trySpigotActionBar(Player player, String message) {
        try {
            Object spigot = player.getClass().getMethod("spigot").invoke(player);
            Class<?> chatMessageType = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Object actionBar = chatMessageType.getField("ACTION_BAR").get(null);
            Object component = textComponent.getConstructor(String.class).newInstance(message);
            spigot.getClass().getMethod("sendMessage", chatMessageType, baseComponent)
                    .invoke(spigot, actionBar, component);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void sendLegacyActionBar(Player player, String message) {
        try {
            String version = player.getServer().getClass().getPackage().getName().split("\\.")[3];
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object handle = craftPlayer.getMethod("getHandle").invoke(player);
            Object playerConnection = handle.getClass().getField("playerConnection").get(handle);
            Class<?> packetChat = Class.forName("net.minecraft.server." + version + ".PacketPlayOutChat");
            Class<?> ichat = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent");
            Class<?> serializer = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent$ChatSerializer");
            Object component = serializer.getMethod("a", String.class)
                    .invoke(null, "{\"text\":\"" + message.replace("\"", "\\\"") + "\"}");
            Object packet = packetChat.getConstructor(ichat, byte.class).newInstance(component, (byte) 2);
            playerConnection.getClass()
                    .getMethod("sendPacket", Class.forName("net.minecraft.server." + version + ".Packet"))
                    .invoke(playerConnection, packet);
        } catch (Exception ignored) {
        }
    }
}
