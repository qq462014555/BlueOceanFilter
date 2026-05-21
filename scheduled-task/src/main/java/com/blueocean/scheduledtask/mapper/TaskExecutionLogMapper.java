package com.blueocean.scheduledtask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blueocean.scheduledtask.entity.TaskExecutionLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskExecutionLogMapper extends BaseMapper<TaskExecutionLog> {
}
