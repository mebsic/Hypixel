package io.github.mebsic.hub.util;

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
        if (parts.length < 4) {
            return null;
        }
        World world = resolveWorld(parts[0]);
        if (world == null) {
            return null;
        }
        Double x = parseDouble(parts[1]);
        Double y = parseDouble(parts[2]);
        Double z = parseDouble(parts[3]);
        if (x == null || y == null || z == null) {
            return null;
        }
        Float yaw = parts.length > 4 ? parseFloat(parts[4]) : 0.0f;
        Float pitch = parts.length > 5 ? parseFloat(parts[5]) : 0.0f;
        if (yaw == null) {
            yaw = 0.0f;
        }
        if (pitch == null) {
            pitch = 0.0f;
        }
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

    private static Double parseDouble(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Float parseFloat(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
