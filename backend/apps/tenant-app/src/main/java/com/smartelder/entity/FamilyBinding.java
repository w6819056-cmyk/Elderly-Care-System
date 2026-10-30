package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("family_binding")
public class FamilyBinding {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long elderId;
    private Long familyId;
    private String relation;
    private String status;
    private LocalDateTime createdAt;
}
