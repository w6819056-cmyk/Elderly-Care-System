package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("service_order")
public class ServiceOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long elderId;
    private Long itemId;
    private Long providerId;
    private Long creatorId;
    private BigDecimal amount;
    private String status;
    private LocalDateTime appointTime;
    private String address;
    private String remark;
    private LocalDateTime payTime;
    private String cancelReason;
    private BigDecimal refundAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
