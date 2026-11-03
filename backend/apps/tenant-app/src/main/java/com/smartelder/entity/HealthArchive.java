package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("health_archive")
public class HealthArchive {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long elderId;
    private BigDecimal heightCm;
    private BigDecimal weightKg;
    private String bloodType;
    private String medicalHistory;
    private String allergyHistory;
    private String medicationHistory;
    private String systolicRange;
    private String diastolicRange;
    private LocalDateTime updatedAt;
}
