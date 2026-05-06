package com.blueocean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("trend_keywords")
public class TrendKeyword {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String word;
    private Integer usageCount;
    private String burstMonths;
    private String layoutMonths;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }

    public Integer getUsageCount() { return usageCount; }
    public void setUsageCount(Integer usageCount) { this.usageCount = usageCount; }

    public String getBurstMonths() { return burstMonths; }
    public void setBurstMonths(String burstMonths) { this.burstMonths = burstMonths; }

    public String getLayoutMonths() { return layoutMonths; }
    public void setLayoutMonths(String layoutMonths) { this.layoutMonths = layoutMonths; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
