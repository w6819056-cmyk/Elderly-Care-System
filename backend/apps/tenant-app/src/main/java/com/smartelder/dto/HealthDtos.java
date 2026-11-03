package com.smartelder.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class HealthDtos {

    private HealthDtos() {
    }

    public record ArchiveRequest(
            BigDecimal heightCm,
            BigDecimal weightKg,
            String bloodType,
            String medicalHistory,
            String allergyHistory,
            String medicationHistory,
            String systolicRange,
            String diastolicRange) {
    }

    /** 体征上报。type: BLOOD_PRESSURE / HEART_RATE / BLOOD_SUGAR / SPO2 / TEMPERATURE */
    public record VitalRequest(
            @NotBlank String type,
            @NotNull @DecimalMin("1") @DecimalMax("300") BigDecimal value1,
            BigDecimal value2,
            LocalDateTime measuredAt) {
    }

    public record VitalView(Long id, String type, BigDecimal value1, BigDecimal value2, String unit,
                            boolean abnormal, String source, LocalDateTime measuredAt) {
    }

    public record MedicationRequest(
            @NotBlank String drugName,
            @NotBlank String dosage,
            @NotBlank String frequency,
            @NotBlank String remindTime) {
    }

    public record MedicationView(Long id, String drugName, String dosage, String frequency,
                                 String remindTime, String status) {
    }

    public record ElderSnapshot(Long elderId, String name, String address, String emergencyContact,
                                String emergencyPhone, List<VitalView> latestVitals,
                                List<AlertDtos.AlertView> activeAlerts,
                                List<ServiceDtos.OrderView> activeOrders,
                                List<MedicationView> medications) {
    }
}
