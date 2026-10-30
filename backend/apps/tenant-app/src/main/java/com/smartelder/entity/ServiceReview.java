package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("service_review")
public class ServiceReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long elderId;
    private Long reviewerId;
    private Integer score;
    private String content;
    private LocalDateTime createdAt;
}
