package com.smartelder.common;

import java.util.Map;
import java.util.Set;

/**
 * 告警处置闭环状态机（需求 SA-09）：待处置 -> 已确认 -> 已回访 -> 已归档。
 */
public final class AlertStatus {

    public static final String PENDING = "PENDING";
    public static final String ACKED = "ACKED";
    public static final String FOLLOWED = "FOLLOWED";
    public static final String ARCHIVED = "ARCHIVED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            PENDING, Set.of(ACKED),
            ACKED, Set.of(FOLLOWED, ARCHIVED),
            FOLLOWED, Set.of(ARCHIVED),
            ARCHIVED, Set.of());

    private AlertStatus() {
    }

    public static void assertTransition(String from, String to) {
        Set<String> next = ALLOWED.get(from);
        if (next == null || !next.contains(to)) {
            throw BizException.conflict("告警状态不允许从 " + from + " 变更为 " + to);
        }
    }

    public static String label(String status) {
        return switch (status) {
            case PENDING -> "待处置";
            case ACKED -> "已确认";
            case FOLLOWED -> "已回访";
            case ARCHIVED -> "已归档";
            default -> status;
        };
    }
}
