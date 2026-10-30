package com.smartelder.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class ServiceDtos {

    private ServiceDtos() {
    }

    public record ItemView(Long id, String name, String category, BigDecimal price, Integer durationMin,
                           String serviceArea, String description, Long providerId) {
    }

    public record CreateOrderRequest(
            @NotNull Long itemId,
            /** 为空表示立即下单 */
            LocalDateTime appointTime,
            @NotBlank @Size(max = 200) String address,
            String remark) {
    }

    public record OrderView(Long id, String orderNo, Long elderId, String elderName, Long itemId, String itemName,
                            Long providerId, String providerName, BigDecimal amount, String status,
                            LocalDateTime appointTime, String address, String remark,
                            LocalDateTime createdAt, String workOrderStatus, Long nurseId) {
    }

    public record ReviewRequest(@NotNull @Min(1) Integer score, String content) {
    }

    public record CheckinRequest(BigDecimal lng, BigDecimal lat, String note) {
    }

    public record AssignRequest(@NotNull Long nurseId) {
    }

    public record DispatchRequest(@NotNull Long providerId) {
    }
}
