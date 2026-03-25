package io.github.mebsic.core.command;

import io.github.mebsic.core.util.NetworkConstants;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public class HelpCommand implements CommandExecutor {
    private static final String CLICK_TO_SELECT = "Click to select!";
    private static final String GO_BACK = "Go back";

    private static final String FORUMS_URL = "https://example.com/forums/5/";
    private static final String MINIGAMES_URL = "https://example.com/forums/#games.67";
    private static final String BUG_REPORT_URL = "https://example.com/bug-reports/create";
    private static final String REPORT_INFO_URL = "https://support.example.com/hc/en-us/articles/360019646359-How-To-Report-Rule-Breakers";
    private static final String STORE_URL = "https://store." + NetworkConstants.DOMAIN;
    private static final String SUPPORT_URL = "https://support.example.com/hc/en-us";
    private static final String ALLOWED_MODS_URL = "https://support.example.com/hc/en-us/articles/6472550754962";
    private static final String RULES_URL = "https://example.com/rules";
    private static final String GENERAL_GAMEPLAY_URL = "https://support.example.com/hc/en-us/categories/360003005440-Hypixel-Guides";
    private static final String RANK_INFO_URL = "https://support.example.com/hc/en-us/articles/360019646559-Hypixel-Ranks-and-How-to-Obtain-Them";
    private static final String CREATOR_PROGRAM_URL = "https://support.example.com/hc/en-us/categories/360003024319-Creators";
    private static final String DISCORD_LINK_URL = "https://support.example.com/hc/en-us/articles/360019646539-How-to-join-the-Hypixel-Discord";
    private static final String FORUM_LINK_URL = "https://support.example.com/hc/en-us/articles/360019647059-Linking-Your-Minecraft-Account-to-Copy-net";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command is for players.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            sendMainMenu(player);
            return true;
        }
        String page = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
        if ("report".equals(page) || "rulebreaker".equals(page) || "rules".equals(page)) {
            sendReportRuleBreakerMenu(player);
            return true;
        }
        if ("general".equals(page) || "gameplay".equals(page) || "server".equals(page)) {
            sendGeneralGameplayMenu(player);
            return true;
        }
        if ("linking".equals(page) || "link".equals(page) || "account".equals(page)) {
            sendLinkingAccountMenu(player);
            return true;
        }
        sendMainMenu(player);
        return true;
    }

    private void sendMainMenu(Player player) {
        sendHeader(player);
        player.sendMessage(ChatColor.YELLOW + "Click to select a help option...");
        player.sendMessage(" ");
        sendOption(player, "Hypixel Minigames", ClickEvent.Action.OPEN_URL, MINIGAMES_URL);
        sendOption(player, "Found a Server Bug/Issue", ClickEvent.Action.OPEN_URL, BUG_REPORT_URL);
        sendOption(player, "Report a Rule Breaker", ClickEvent.Action.RUN_COMMAND, "/help report");
        sendOption(player, "Store", ClickEvent.Action.OPEN_URL, STORE_URL);
        sendOption(player, "Support", ClickEvent.Action.OPEN_URL, SUPPORT_URL);
        sendOption(player, "Allowed Modifications", ClickEvent.Action.OPEN_URL, ALLOWED_MODS_URL);
        sendOption(player, "Hypixel Rules & Policies", ClickEvent.Action.OPEN_URL, RULES_URL);
        sendOption(player, "General Gameplay/Server", ClickEvent.Action.RUN_COMMAND, "/help general");
        sendFooter(player);
    }

    private void sendReportRuleBreakerMenu(Player player) {
        sendHeader(player);
        sendBackLine(player, "Report a Rule Breaker", "/help");
        player.sendMessage(" ");
        sendOption(player, "Report a player", ClickEvent.Action.SUGGEST_COMMAND, "/report <name>");
        sendOption(player, "Further information here", ClickEvent.Action.OPEN_URL, REPORT_INFO_URL);
        sendFooter(player);
    }

    private void sendGeneralGameplayMenu(Player player) {
        sendHeader(player);
        sendBackLine(player, "General Gameplay/Server", "/help");
        player.sendMessage(" ");
        sendOption(player, "General Gameplay", ClickEvent.Action.OPEN_URL, GENERAL_GAMEPLAY_URL);
        sendOption(player, "Rank Information", ClickEvent.Action.OPEN_URL, RANK_INFO_URL);
        sendOption(player, "Creator Program", ClickEvent.Action.OPEN_URL, CREATOR_PROGRAM_URL);
        sendOption(player, "Linking your Minecraft account", ClickEvent.Action.RUN_COMMAND, "/help linking");
        sendFooter(player);
    }

    private void sendLinkingAccountMenu(Player player) {
        sendHeader(player);
        sendBackLine(player, "Linking your Minecraft account", "/help general");
        player.sendMessage(" ");
        sendOption(player, "Link and join our Discord", ClickEvent.Action.OPEN_URL, DISCORD_LINK_URL);
        sendOption(player, "Link your account to the forums", ClickEvent.Action.OPEN_URL, FORUM_LINK_URL);
        sendFooter(player);
    }

    private void sendHeader(Player player) {
        player.sendMessage(" ");
        player.sendMessage(ChatColor.YELLOW.toString() + ChatColor.BOLD + "HYPIXEL NETWORK");
    }

    private void sendFooter(Player player) {
        player.sendMessage(" ");
        TextComponent line = new TextComponent(ChatColor.YELLOW + "Need more help? Visit ");
        TextComponent forums = new TextComponent(ChatColor.AQUA + "our forums");
        forums.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, FORUMS_URL));
        forums.setHoverEvent(buildHover(CLICK_TO_SELECT));
        line.addExtra(forums);
        line.addExtra(new TextComponent(ChatColor.YELLOW + "."));
        player.spigot().sendMessage(line);
        player.sendMessage(" ");
    }

    private void sendBackLine(Player player, String title, String command) {
        TextComponent line = new TextComponent("");
        line.addExtra(new TextComponent(ChatColor.AQUA + "« "));
        line.addExtra(new TextComponent(ChatColor.YELLOW + title));
        line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        line.setHoverEvent(buildHover(GO_BACK));
        player.spigot().sendMessage(line);
    }

    private void sendOption(Player player, String label, ClickEvent.Action action, String value) {
        TextComponent line = new TextComponent("");
        line.addExtra(new TextComponent("  " + ChatColor.RED + "* "));
        TextComponent option = new TextComponent(ChatColor.AQUA + label);
        option.setClickEvent(new ClickEvent(action, value));
        option.setHoverEvent(buildHover(CLICK_TO_SELECT));
        line.addExtra(option);
        player.spigot().sendMessage(line);
    }

    private HoverEvent buildHover(String text) {
        BaseComponent[] hover = new BaseComponent[] {new TextComponent(ChatColor.LIGHT_PURPLE + text)};
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover);
    }
}
