package io.github.mebsic.core.command;

import io.github.mebsic.core.CorePlugin;
import io.github.mebsic.core.model.Rank;
import io.github.mebsic.core.util.CommonMessages;
import io.github.mebsic.core.util.RankUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EffectCommand implements CommandExecutor {
    private static final int TICKS_PER_SECOND = 20;
    private static final int FOREVER_TICKS = Integer.MAX_VALUE;
    private static final Map<String, PotionEffectType> EFFECT_TYPES = createEffectTypes();

    private final CorePlugin plugin;

    public EffectCommand(CorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + CommonMessages.ONLY_PLAYERS_COMMAND);
            return true;
        }
        Player player = (Player) sender;
        if (!RankUtil.hasAtLeast(plugin, player, Rank.STAFF)) {
            player.sendMessage(ChatColor.RED + CommonMessages.NO_PERMISSION_COMMAND);
            return true;
        }

        if (args.length < 3 || args.length > 4) {
            sendUsage(sender, label);
            return true;
        }

        List<Player> targets = resolveTargets(sender, args[0]);
        if (targets == null) {
            return true;
        }

        PotionEffectType effectType = resolveEffectType(args[1]);
        if (effectType == null) {
            sender.sendMessage(ChatColor.RED + "Unknown type!");
            return true;
        }

        int durationTicks = parseDurationTicks(args[2]);
        if (durationTicks <= 0) {
            sender.sendMessage(ChatColor.RED + "Duration cannot be a negative number!");
            return true;
        }

        int amount = 0;
        if (args.length == 4) {
            amount = parseAmount(args[3]);
            if (amount < 0) {
                sender.sendMessage(ChatColor.RED + "Amount cannot be a negative number!");
                return true;
            }
        }

        PotionEffect effect = new PotionEffect(effectType, durationTicks, amount);
        boolean failed = false;
        for (Player target : targets) {
            try {
                target.addPotionEffect(effect, true);
            } catch (RuntimeException ignored) {
                failed = true;
            }
        }

        sender.sendMessage(failed ? ChatColor.RED + "Failed!" : ChatColor.GREEN + CommonMessages.DONE);
        return true;
    }

    private List<Player> resolveTargets(CommandSender sender, String rawTarget) {
        if (rawTarget == null) {
            return null;
        }
        if (rawTarget.equalsIgnoreCase("all")) {
            return new ArrayList<Player>(Bukkit.getOnlinePlayers());
        }
        Player target = Bukkit.getPlayerExact(rawTarget);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + CommonMessages.PLAYER_NOT_FOUND_COMMAND);
            return null;
        }
        List<Player> targets = new ArrayList<Player>();
        targets.add(target);
        return targets;
    }

    private PotionEffectType resolveEffectType(String rawType) {
        if (rawType == null) {
            return null;
        }
        String normalized = normalizeEffectType(rawType);
        if (normalized.isEmpty()) {
            return null;
        }
        return EFFECT_TYPES.get(normalized);
    }

    private int parseDurationTicks(String rawDuration) {
        if (rawDuration == null) {
            return -1;
        }
        String normalized = rawDuration.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("forever")) {
            return FOREVER_TICKS;
        }
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            long seconds = Long.parseLong(normalized);
            if (seconds <= 0L) {
                return -1;
            }
            if (seconds > FOREVER_TICKS / TICKS_PER_SECOND) {
                return FOREVER_TICKS;
            }
            return (int) (seconds * TICKS_PER_SECOND);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private int parseAmount(String rawAmount) {
        if (rawAmount == null) {
            return -1;
        }
        try {
            int amount = Integer.parseInt(rawAmount);
            return amount < 0 ? -1 : amount;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private String normalizeEffectType(String rawType) {
        String value = rawType.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        return value;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.RED + "Invalid usage! Correct usage:");
        sender.sendMessage(ChatColor.RED + "/" + label + " <player/all> <type> <duration/forever> [amount]");
    }

    private static Map<String, PotionEffectType> createEffectTypes() {
        Map<String, PotionEffectType> types = new HashMap<>();
        types.put("speed", PotionEffectType.SPEED);
        types.put("slow", PotionEffectType.SLOW);
        types.put("fast_digging", PotionEffectType.FAST_DIGGING);
        types.put("slow_digging", PotionEffectType.SLOW_DIGGING);
        types.put("increase_damage", PotionEffectType.INCREASE_DAMAGE);
        types.put("heal", PotionEffectType.HEAL);
        types.put("harm", PotionEffectType.HARM);
        types.put("jump", PotionEffectType.JUMP);
        types.put("confusion", PotionEffectType.CONFUSION);
        types.put("regeneration", PotionEffectType.REGENERATION);
        types.put("damage_resistance", PotionEffectType.DAMAGE_RESISTANCE);
        types.put("fire_resistance", PotionEffectType.FIRE_RESISTANCE);
        types.put("water_breathing", PotionEffectType.WATER_BREATHING);
        types.put("invisibility", PotionEffectType.INVISIBILITY);
        types.put("blindness", PotionEffectType.BLINDNESS);
        types.put("night_vision", PotionEffectType.NIGHT_VISION);
        types.put("hunger", PotionEffectType.HUNGER);
        types.put("weakness", PotionEffectType.WEAKNESS);
        types.put("poison", PotionEffectType.POISON);
        types.put("wither", PotionEffectType.WITHER);
        types.put("health_boost", PotionEffectType.HEALTH_BOOST);
        types.put("absorption", PotionEffectType.ABSORPTION);
        types.put("saturation", PotionEffectType.SATURATION);
        return types;
    }
}
