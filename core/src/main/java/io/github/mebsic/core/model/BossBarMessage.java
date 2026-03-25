package io.github.mebsic.core.model;

public class BossBarMessage {
    private final String id;
    private final String text;
    private final float value;
    private final String scope;
    private final String serverType;

    public BossBarMessage(String id, String text, float value, String scope, String serverType) {
        this.id = id == null ? "" : id.trim();
        this.text = text == null ? "" : text;
        this.value = value;
        this.scope = scope == null ? "" : scope.trim();
        this.serverType = serverType == null ? "" : serverType.trim();
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public float getValue() {
        return value;
    }

    public String getScope() {
        return scope;
    }

    public String getServerType() {
        return serverType;
    }
}
