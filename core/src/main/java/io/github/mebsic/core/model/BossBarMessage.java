package io.github.mebsic.core.model;

public class BossBarMessage {
    private final String id;
    private final String text;
    private final float value;
    private final String scope;
    private final String serverType;
    private final String animationType;
    private final String startColor;
    private final String animationColor;
    private final String endColor;
    private final double startSeconds;
    private final double endSeconds;

    public BossBarMessage(String id, String text, float value, String scope, String serverType) {
        this(id, text, value, scope, serverType, "", "", "", "", 0.0D, 5.0D);
    }

    public BossBarMessage(String id,
                          String text,
                          float value,
                          String scope,
                          String serverType,
                          String animationType,
                          String startColor,
                          String animationColor,
                          String endColor,
                          double startSeconds,
                          double endSeconds) {
        this.id = id == null ? "" : id.trim();
        this.text = text == null ? "" : text;
        this.value = value;
        this.scope = scope == null ? "" : scope.trim();
        this.serverType = serverType == null ? "" : serverType.trim();
        this.animationType = animationType == null ? "" : animationType.trim();
        this.startColor = startColor == null ? "" : startColor.trim();
        this.animationColor = animationColor == null ? "" : animationColor.trim();
        this.endColor = endColor == null ? "" : endColor.trim();
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
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

    public String getAnimationType() {
        return animationType;
    }

    public String getStartColor() {
        return startColor;
    }

    public String getAnimationColor() {
        return animationColor;
    }

    public String getEndColor() {
        return endColor;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public double getEndSeconds() {
        return endSeconds;
    }
}
