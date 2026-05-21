package com.blueocean.taobao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("taobao_ad_record")
public class TaobaoAdRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String wangwang;
    private String shopName;
    private Double cost;
    private Long clicks;
    private Long impressions;
    private Double totalGmv;
    private Double roi;
    private Double clickRate;
    private LocalDate statDate;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWangwang() { return wangwang; }
    public void setWangwang(String wangwang) { this.wangwang = wangwang; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public Double getCost() { return cost; }
    public void setCost(Double cost) { this.cost = cost; }
    public Long getClicks() { return clicks; }
    public void setClicks(Long clicks) { this.clicks = clicks; }
    public Long getImpressions() { return impressions; }
    public void setImpressions(Long impressions) { this.impressions = impressions; }
    public Double getTotalGmv() { return totalGmv; }
    public void setTotalGmv(Double totalGmv) { this.totalGmv = totalGmv; }
    public Double getRoi() { return roi; }
    public void setRoi(Double roi) { this.roi = roi; }
    public Double getClickRate() { return clickRate; }
    public void setClickRate(Double clickRate) { this.clickRate = clickRate; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
