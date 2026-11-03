package com.smartelder.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record AuditRequest(@NotBlank String decision, String note) {
    }

    public record ProviderRow(Long id, String name, String contactName, String contactPhone, String serviceArea,
                              BigDecimal rating, String auditStatus, String auditNote, LocalDateTime createdAt) {
    }

    public record PendingItemRow(Long id, String name, String category, BigDecimal price, String serviceArea,
                                Long providerId, String providerName, String auditStatus, String status) {
    }

    public record DashboardStats(long elderCount, long familyCount, long providerCount, long orderCount,
                                 long todayOrderCount, long activeAlertCount, long redAlertCount,
                                 BigDecimal totalRevenue, long pendingAuditCount) {
    }
}
