package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Profile;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.server.ServerType;
import io.github.mebsic.core.util.CommonMessages;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FireworkCommand implements CommandExecutor {
    private static final long COOLDOWN_MS = 15_000L;

    private final CorePlugin plugin;
    private final Map<UUID, Long> lastLaunchAtByPlayer;

    public FireworkCommand(CorePlugin plugin) {
        this.plugin = plugin;
        this.lastLaunchAtByPlayer = new ConcurrentHashMap<UUID, Long>();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        ServerType serverType = plugin == null ? null : plugin.getServerType();
        if (serverType == null || !serverType.isHub()) {
            player.sendMessage(ChatColor.RED + "This command is not available on this server!");
            return true;
        }

        Profile profile = plugin.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(ChatColor.RED + "Your profile is still loading. Try again in a moment.");
            return true;
        }

        Rank rank = profile.getRank() == null ? Rank.DEFAULT : profile.getRank();
        if (!rank.isAtLeast(Rank.VIP)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }

        long now = System.currentTimeMillis();
        long last = lastLaunchAtByPlayer.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < COOLDOWN_MS) {
            long remainingMs = COOLDOWN_MS - (now - last);
            long remainingSeconds = Math.max(1L, (long) Math.ceil(remainingMs / 1000.0));
            player.sendMessage(ChatColor.RED + "You have to wait "
                    + remainingSeconds
                    + " second"
                    + (remainingSeconds == 1L ? "" : "s")
                    + " between sending fireworks!");
            return true;
        }

        Location launchLocation = player.getLocation().clone().add(0.0, 1.0, 0.0);
        World world = launchLocation.getWorld();
        if (world == null) {
            return true;
        }

        Firework firework = world.spawn(launchLocation, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.clearEffects();
        meta.addEffect(FireworkEffect.builder()
                .with(randomEffectType())
                .withColor(randomColor())
                .withFade(randomColor())
                .flicker(ThreadLocalRandom.current().nextBoolean())
                .trail(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
        firework.setVelocity(new Vector(0.0, 1.0, 0.0));

        lastLaunchAtByPlayer.put(player.getUniqueId(), now);
        player.sendMessage(ChatColor.YELLOW + "Launched a firework!");
        return true;
    }

    private Color randomColor() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return Color.fromRGB(
                random.nextInt(64, 256),
                random.nextInt(64, 256),
                random.nextInt(64, 256));
    }

    private FireworkEffect.Type randomEffectType() {
        FireworkEffect.Type[] values = FireworkEffect.Type.values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }
}
