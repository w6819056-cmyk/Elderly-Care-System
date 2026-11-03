package com.smartelder.common;

/**
 * 告警分级（需求 SA-08）：红=紧急，黄=关注，蓝=提示。
 */
public final class AlertLevel {

    public static final String RED = "RED";
    public static final String YELLOW = "YELLOW";
    public static final String BLUE = "BLUE";

    private AlertLevel() {
    }

    public static String of(String alertType) {
        return switch (alertType) {
            case "SOS", "FALL" -> RED;
            case "GEOFENCE", "INACTIVITY", "VITAL_ABNORMAL" -> YELLOW;
            default -> BLUE;
        };
    }

    public static String label(String level) {
        return switch (level) {
            case RED -> "紧急";
            case YELLOW -> "关注";
            case BLUE -> "提示";
            default -> level;
        };
    }
}
