package com.blueocean.taobao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("taobao_shop")
public class TaobaoShop {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String wangwangName;
    private String shopName;
    private String shopVersion;
    private String groupName;
    private String tags;
    private Double accountBalance;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWangwangName() { return wangwangName; }
    public void setWangwangName(String wangwangName) { this.wangwangName = wangwangName; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public String getShopVersion() { return shopVersion; }
    public void setShopVersion(String shopVersion) { this.shopVersion = shopVersion; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Double getAccountBalance() { return accountBalance; }
    public void setAccountBalance(Double accountBalance) { this.accountBalance = accountBalance; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}