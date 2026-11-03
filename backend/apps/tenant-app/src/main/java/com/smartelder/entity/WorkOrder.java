package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("work_order")
public class WorkOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long providerId;
    private Long nurseId;
    private String status;
    private LocalDateTime acceptTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal startLng;
    private BigDecimal startLat;
    private BigDecimal endLng;
    private BigDecimal endLat;
    private String serviceNote;
    private LocalDateTime createdAt;
}
