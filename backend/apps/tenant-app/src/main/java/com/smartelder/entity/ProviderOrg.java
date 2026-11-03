package com.smartelder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("provider_org")
public class ProviderOrg {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String contactName;
    private String contactPhone;
    private String licenseNo;
    private String serviceArea;
    private BigDecimal rating;
    private String auditStatus;
    private String auditNote;
    private String settleAccount;
    private LocalDateTime createdAt;
}
