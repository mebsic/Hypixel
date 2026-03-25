package io.github.mebsic.core.model;

import io.github.mebsic.core.util.NetworkConstants;

import java.util.Locale;

public enum BanReasonType {
    WATCHDOG("WATCHDOG CHEAT DETECTION - https://" + NetworkConstants.WEBSITE + "/watchdog", "WATCHDOG"),
    BLACKLISTED_MODIFICATIONS(
            "Cheating through the use of unfair game advantages.",
            "BM",
            "Blacklisted Modifications",
            "Cheating/Unfair Advantage",
            "Using unfair advantages in game"
    ),
    CROSS_TEAMING("Cross teaming, you were found to be working with another team or player.", "CT"),
    TEAM_GRIEFING("You were found to be negatively affecting your fellow team members.", "TG"),
    INAPPROPRIATE_BUILD("Creating a build or drawing which is not appropriate on the server.", "IB", "Inappropriate Build", "Inappropriate Drawing"),
    INAPPROPRIATE_ITEM_NAME("Creating or using an item that has an inappropriate name", "IN"),
    INAPPROPRIATE_ITEM_USAGE("Using pets or cosmetics in an inappropriate way.", "IU"),
    STAFF_IMPERSONATION("Misleading others to believe you are a youtuber or staff member.", "SI"),
    SCAMMING("Attempting to obtain information or something of value from players.", "SC"),
    ENCOURAGING_CHEATING_LVL2("Discussing or acting in a manner which encourages cheating or rule breaking.", "EC2"),
    ENCOURAGING_CHEATING_LVL3("Discussing or acting in a manner which encourages cheating or rule breaking.", "EC3"),
    EXTREME_USER_DISRESPECT("Acting in a manner that is extremely disrespectful to members within the community.", "EUD", "Extreme Negative behaviour"),
    STATS_BOOSTING("Boosting your account to improve your stats.", "SB", "Boosting"),
    INAPPROPRIATE_AESTHETICS("Using inappropriate skins or capes on the server.", "IA"),
    EXPLOITING("Exploiting a bug or issue within the server and using it to your advantage.", "EX", "Exploits"),
    FALSIFIED_INFORMATION("Making or sharing fake information.", "FI"),
    CHARGEBACK("Chargeback: for more info and appeal, go to https://support." + NetworkConstants.DOMAIN + ".", "CHARGEBACK", "Chargeback"),
    ACCOUNT_SELLING("Attempting to sell Minecraft accounts.", "AS"),
    COMPROMISED_ACCOUNT(
            "Your account has a security alert, please secure it and contact appeals.",
            "ACCOUNT_SECURITY_ALERT",
            "Compromised Account",
            "Account Security Alert"
    ),
    ACCOUNT_SECURITY_ALERT_SERVER_ADVERTISING("Your account has a security alert, please secure it and contact appeals.", "CAS"),
    ACCOUNT_SECURITY_ALERT_BLACKLISTED("Your account has a security alert, please secure it and contact appeals.", "CAB"),
    PHISHING_LINK("Attempting to gain access to other user's accounts/information.", "PL"),
    UN_INTENTIONALLY_CAUSING_DISTRESS_2("Unintentionally/Intentionally Causing distress.", "UIB"),
    UN_INTENTIONALLY_CAUSING_DISTRESS_3("Unintentionally/Intentionally Causing distress.", "UI3"),
    INAPPROPRIATE_CONTENT_LVL2("Talking or sharing inappropriate content with adult themes on the server.", "IC2"),
    ACCOUNT_DELETION("Upon request, data for this user has been deleted. https://support." + NetworkConstants.DOMAIN, "ACCOUNT_DELETION"),
    CREATOR_BAN("Please contact creators@example.com for assistance.", "CREATOR_BAN"),
    CREATOR_ACCOUNT_SECURITY_ALERT(
            "Your account has a security alert, please secure it and contact creators@example.com for assistance.",
            "CREATOR_ACCOUNT_SECURITY_ALERT",
            "Creator Compromised Account",
            "Creator Account Security Alert"
    );

    private final String description;
    private final String code;
    private final String[] aliases;

    BanReasonType(String description, String code, String... aliases) {
        this.description = description;
        this.code = code;
        this.aliases = aliases == null ? new String[0] : aliases;
    }

    public String getDescription() {
        return description;
    }

    public String getCode() {
        return code;
    }

    public static BanReasonType fromCode(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return null;
        }
        for (BanReasonType value : values()) {
            if (value.code == null || value.code.trim().isEmpty()) {
                continue;
            }
            if (normalized.equals(normalize(value.code))) {
                return value;
            }
        }
        return null;
    }

    public static BanReasonType resolve(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return null;
        }
        for (BanReasonType value : values()) {
            if (value.matches(normalized)) {
                return value;
            }
        }
        return null;
    }

    private boolean matches(String normalizedInput) {
        if (normalizedInput == null || normalizedInput.isEmpty()) {
            return false;
        }
        if (normalizedInput.equals(normalize(name()))) {
            return true;
        }
        if (code != null && !code.trim().isEmpty() && normalizedInput.equals(normalize(code))) {
            return true;
        }
        for (String alias : aliases) {
            if (alias != null && !alias.trim().isEmpty() && normalizedInput.equals(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String input) {
        StringBuilder builder = new StringBuilder();
        String raw = input.toLowerCase(Locale.ROOT);
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
}
