package com.blueocean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("filter_task")
public class FilterTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String status;
    private String originalFileName;
    private String keptFileName;
    private String excludedFileName;
    private Integer totalWords;
    private Integer processedWords;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }

    public String getKeptFileName() { return keptFileName; }
    public void setKeptFileName(String keptFileName) { this.keptFileName = keptFileName; }

    public String getExcludedFileName() { return excludedFileName; }
    public void setExcludedFileName(String excludedFileName) { this.excludedFileName = excludedFileName; }

    public Integer getTotalWords() { return totalWords; }
    public void setTotalWords(Integer totalWords) { this.totalWords = totalWords; }

    public Integer getProcessedWords() { return processedWords; }
    public void setProcessedWords(Integer processedWords) { this.processedWords = processedWords; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getFinishTime() { return finishTime; }
    public void setFinishTime(LocalDateTime finishTime) { this.finishTime = finishTime; }
}
