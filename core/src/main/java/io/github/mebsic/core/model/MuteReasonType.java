package io.github.mebsic.core.model;

import io.github.mebsic.core.util.NetworkConstants;

import java.util.Locale;

public enum MuteReasonType {
    NEGATIVE_REFERENCE("Discussing important people or world events in a negative way.", "NR"),
    USER_DISRESPECT("Acting in a manner that is disrespectful to members within the community.", "UD"),
    STAFF_DISRESPECT("Disrespectful behaviour directed at staff members.", "SD"),
    INAPPROPRIATE_CONTENT_LVL1("Using adult concepts in public chat on the server.", "IC1"),
    DISCRIMINATION("Discrimination of a player or group of people.", "DI"),
    EXCESSIVE_SWEARING("Excessive use of swearing in chat.", "ES"),
    UN_INTENTIONALLY_CAUSING_DISTRESS(
            "intentionally or unintentionally causing distress.",
            "UI",
            "Unintentionally/Intentionally Causing distress."
    ),
    ENCOURAGING_CHEATING_LVL1("Discussing or actively promoting cheating or breaking of rules on the server.", "EC1"),
    MEDIA_ADVERTISING("Media Advertising", "MA"),
    PUBLIC_SHAMING("Publicly revealing information about a player.", "PS"),
    RUDE("Being rude or inappropriate.", "RU"),
    EXCESSIVE_SPAMMING("Repeatedly posting unnecessary messages or content.", "SP"),
    MISLEADING_INFORMATION(
            "misleading other players into actions that disrupt their game.",
            "MI",
            "Trolling",
            "Misleading other players to carry out actions that disrupts their game."
    ),
    UNNECESSARY_SPOILERS("Giving spoilers, revealing important storylines of popular movies and tv shows.", "US"),
    ESCALATION(
            "a chat offense that is currently under review.",
            "ESC",
            "You have been muted for a chat offense and is currently under review."
    );

    private final String description;
    private final String code;
    private final String[] aliases;

    MuteReasonType(String description, String code, String... aliases) {
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

    public String getFindOutMoreUrl() {
        switch (this) {
            case ESCALATION:
                return "https://support." + NetworkConstants.DOMAIN;
            default:
                return null;
        }
    }

    public static MuteReasonType fromCode(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return null;
        }
        for (MuteReasonType value : values()) {
            if (value.code == null || value.code.trim().isEmpty()) {
                continue;
            }
            if (normalized.equals(normalize(value.code))) {
                return value;
            }
        }
        return null;
    }

    public static MuteReasonType resolve(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return null;
        }
        for (MuteReasonType value : values()) {
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
        if (description != null && !description.trim().isEmpty()
                && normalizedInput.equals(normalize(description))) {
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
