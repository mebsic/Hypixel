package io.github.mebsic.core.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MojangApi {
    private static final String MOJANG_ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SERVICES_ENDPOINT = "https://api.minecraftservices.com/minecraft/profile/lookup/name/";

    private MojangApi() {
    }

    public static UUID lookupUuid(String username) {
        UUID uuid = fetchUuid(SERVICES_ENDPOINT + username);
        if (uuid != null) {
            return uuid;
        }
        return fetchUuid(MOJANG_ENDPOINT + username);
    }

    private static UUID fetchUuid(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status == 204 || status == 404) {
                return null;
            }
            if (status != 200) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                JsonObject obj = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                if (!obj.has("id")) {
                    return null;
                }
                String raw = obj.get("id").getAsString();
                return formatUuid(raw);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static UUID formatUuid(String raw) {
        if (raw == null || raw.length() != 32) {
            return null;
        }
        String formatted = raw.substring(0, 8) + "-" +
                raw.substring(8, 12) + "-" +
                raw.substring(12, 16) + "-" +
                raw.substring(16, 20) + "-" +
                raw.substring(20);
        return UUID.fromString(formatted);
    }
}
