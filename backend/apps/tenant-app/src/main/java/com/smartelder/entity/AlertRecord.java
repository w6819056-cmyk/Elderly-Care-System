package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_record")
public class AlertRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long elderId;
    private String type;
    private String level;
    private String content;
    private BigDecimal triggerLng;
    private BigDecimal triggerLat;
    private String status;
    private Long handleBy;
    private LocalDateTime handleTime;
    private String handleNote;
    private LocalDateTime createdAt;
}
