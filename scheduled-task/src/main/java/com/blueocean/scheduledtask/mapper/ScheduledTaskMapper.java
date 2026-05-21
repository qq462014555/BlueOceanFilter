package com.blueocean.scheduledtask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blueocean.scheduledtask.entity.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {
}
