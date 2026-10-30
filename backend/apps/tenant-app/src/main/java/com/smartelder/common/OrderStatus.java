package com.smartelder.common;

import java.util.Map;
import java.util.Set;

/**
 * 订单状态机（对应需求 SV-12）。
 * 待支付 -> 待接单 -> 服务中 -> 待评价 -> 已完成
 * 待支付/待接单 可取消；已支付后退款进入退款流程。
 */
public final class OrderStatus {

    public static final String PENDING_PAY = "PENDING_PAY";
    public static final String PENDING_ACCEPT = "PENDING_ACCEPT";
    public static final String IN_SERVICE = "IN_SERVICE";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String REFUNDED = "REFUNDED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            PENDING_PAY, Set.of(PENDING_ACCEPT, CANCELLED),
            PENDING_ACCEPT, Set.of(IN_SERVICE, CANCELLED, REFUNDED),
            IN_SERVICE, Set.of(PENDING_REVIEW, REFUNDED),
            PENDING_REVIEW, Set.of(COMPLETED),
            COMPLETED, Set.of(),
            CANCELLED, Set.of(),
            REFUNDED, Set.of());

    private OrderStatus() {
    }

    public static void assertTransition(String from, String to) {
        Set<String> next = ALLOWED.get(from);
        if (next == null || !next.contains(to)) {
            throw BizException.conflict("订单状态不允许从 " + label(from) + " 变更为 " + label(to));
        }
    }

    public static boolean canCancel(String status) {
        return PENDING_PAY.equals(status) || PENDING_ACCEPT.equals(status);
    }

    public static String label(String status) {
        return switch (status) {
            case PENDING_PAY -> "待支付";
            case PENDING_ACCEPT -> "待接单";
            case IN_SERVICE -> "服务中";
            case PENDING_REVIEW -> "待评价";
            case COMPLETED -> "已完成";
            case CANCELLED -> "已取消";
            case REFUNDED -> "已退款";
            default -> status;
        };
    }
}
