package com.smartelder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartelder.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
}
