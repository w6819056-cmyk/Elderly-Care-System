package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("message_record")
public class MessageRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long receiverId;
    private String type;
    private String channel;
    private String title;
    private String content;
    private Long bizId;
    private String status;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
