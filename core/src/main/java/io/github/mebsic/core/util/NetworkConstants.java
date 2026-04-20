package io.github.mebsic.core.util;

public final class NetworkConstants {
    public static final String DEFAULT_DOMAIN = "example.net";
    public static volatile String DOMAIN = DEFAULT_DOMAIN;
    public static volatile String WEBSITE = "www." + DEFAULT_DOMAIN;

    private NetworkConstants() {
    }

    public static String domain() {
        return DOMAIN;
    }

    public static String website() {
        return WEBSITE;
    }

    public static String supportHost() {
        return "support." + domain();
    }

    public static String mcHost() {
        return "mc." + domain();
    }

    public static String displayJoinHost() {
        String resolvedDomain = normalizeDomain(domain());
        if (resolvedDomain.startsWith("mc.")) {
            return resolvedDomain;
        }
        if (hasExplicitSubdomain(resolvedDomain)) {
            return resolvedDomain;
        }
        return "mc." + resolvedDomain;
    }

    public static String storeUrl() {
        return "https://store." + domain();
    }

    public static String supportUrl() {
        return "https://" + supportHost();
    }

    public static String mutesUrl() {
        return website() + "/mutes";
    }

    public static String creatorsEmail() {
        return "creators@" + domain();
    }

    public static synchronized boolean setDomain(String rawDomain) {
        String normalized = normalizeDomain(rawDomain);
        if (normalized.equals(DOMAIN)) {
            return false;
        }
        DOMAIN = normalized;
        WEBSITE = "www." + normalized;
        return true;
    }

    public static synchronized void resetDomain() {
        DOMAIN = DEFAULT_DOMAIN;
        WEBSITE = "www." + DEFAULT_DOMAIN;
    }

    private static String normalizeDomain(String rawDomain) {
        if (rawDomain == null) {
            return DEFAULT_DOMAIN;
        }
        String normalized = rawDomain.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("http://")) {
            normalized = normalized.substring("http://".length());
        } else if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        }
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(0, slash);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return DEFAULT_DOMAIN;
        }
        return normalized;
    }

    private static boolean hasExplicitSubdomain(String host) {
        int firstDot = host.indexOf('.');
        if (firstDot < 0) {
            return false;
        }
        return host.indexOf('.', firstDot + 1) >= 0;
    }
}
