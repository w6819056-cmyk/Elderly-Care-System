package com.smartelder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class AlertDtos {

    private AlertDtos() {
    }

    public record SosRequest(BigDecimal lng, BigDecimal lat, String note) {
    }

    /** type: FALL / GEOFENCE / INACTIVITY / SOS / VITAL_ABNORMAL */
    public record TriggerRequest(
            @NotBlank String type,
            BigDecimal lng,
            BigDecimal lat,
            @Size(max = 500) String content) {
    }

    public record HandleRequest(@NotBlank String action, String note) {
    }

    public record AlertView(Long id, Long elderId, String elderName, String type, String level, String content,
                            String status, Long handleBy, LocalDateTime handleTime, String handleNote,
                            LocalDateTime createdAt) {
    }
}
