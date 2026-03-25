package io.github.mebsic.game.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class LocationUtil {
    private LocationUtil() {
    }

    public static String serialize(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return location.getWorld().getName() + "," +
                location.getX() + "," +
                location.getY() + "," +
                location.getZ() + "," +
                location.getYaw() + "," +
                location.getPitch();
    }

    public static Location deserialize(String data) {
        if (data == null || data.trim().isEmpty()) {
            return null;
        }
        String[] parts = data.split(",");
        if (parts.length < 6) {
            return null;
        }
        World world = resolveWorld(parts[0]);
        if (world == null) {
            return null;
        }
        double x = Double.parseDouble(parts[1]);
        double y = Double.parseDouble(parts[2]);
        double z = Double.parseDouble(parts[3]);
        float yaw = Float.parseFloat(parts[4]);
        float pitch = Float.parseFloat(parts[5]);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private static World resolveWorld(String name) {
        String target = name == null ? "" : name.trim();
        if (!target.isEmpty()) {
            World direct = Bukkit.getWorld(target);
            if (direct != null) {
                return direct;
            }
            for (World world : Bukkit.getWorlds()) {
                if (world == null || world.getName() == null) {
                    continue;
                }
                if (target.equalsIgnoreCase(world.getName())) {
                    return world;
                }
            }
        }
        World defaultWorld = Bukkit.getWorld("world");
        if (defaultWorld != null) {
            return defaultWorld;
        }
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        return Bukkit.getWorlds().get(0);
    }
}
