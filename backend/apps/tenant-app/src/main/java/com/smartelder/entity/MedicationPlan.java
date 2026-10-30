package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("medication_plan")
public class MedicationPlan {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long elderId;
    private String drugName;
    private String dosage;
    private String frequency;
    private String remindTime;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
}
