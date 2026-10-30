package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("vital_record")
public class VitalRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long elderId;
    private String type;
    private BigDecimal value1;
    private BigDecimal value2;
    private String unit;
    private Integer abnormal;
    private String source;
    private LocalDateTime measuredAt;
    private LocalDateTime createdAt;
}
