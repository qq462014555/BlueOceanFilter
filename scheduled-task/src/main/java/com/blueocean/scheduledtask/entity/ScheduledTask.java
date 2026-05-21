package com.blueocean.scheduledtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("scheduled_task")
public class ScheduledTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String content;
    private String cronExpression;
    private String status; // ACTIVE, PAUSED
    private Integer executeCount;
    private LocalDateTime lastExecuteTime;
    private LocalDateTime nextExecuteTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String methodBeanName;
    private String methodSignature;
    private String methodParams;
    private String excludeTimeStart;
    private String excludeTimeEnd;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getExecuteCount() { return executeCount; }
    public void setExecuteCount(Integer executeCount) { this.executeCount = executeCount; }
    public LocalDateTime getLastExecuteTime() { return lastExecuteTime; }
    public void setLastExecuteTime(LocalDateTime lastExecuteTime) { this.lastExecuteTime = lastExecuteTime; }
    public LocalDateTime getNextExecuteTime() { return nextExecuteTime; }
    public void setNextExecuteTime(LocalDateTime nextExecuteTime) { this.nextExecuteTime = nextExecuteTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public String getMethodBeanName() { return methodBeanName; }
    public void setMethodBeanName(String methodBeanName) { this.methodBeanName = methodBeanName; }
    public String getMethodSignature() { return methodSignature; }
    public void setMethodSignature(String methodSignature) { this.methodSignature = methodSignature; }
    public String getMethodParams() { return methodParams; }
    public void setMethodParams(String methodParams) { this.methodParams = methodParams; }
    public String getExcludeTimeStart() { return excludeTimeStart; }
    public void setExcludeTimeStart(String excludeTimeStart) { this.excludeTimeStart = excludeTimeStart; }
    public String getExcludeTimeEnd() { return excludeTimeEnd; }
    public void setExcludeTimeEnd(String excludeTimeEnd) { this.excludeTimeEnd = excludeTimeEnd; }
}
