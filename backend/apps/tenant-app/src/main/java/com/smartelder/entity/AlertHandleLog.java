package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("alert_handle_log")
public class AlertHandleLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long alertId;
    private String action;
    private Long operatorId;
    private String note;
    private LocalDateTime createdAt;
}
