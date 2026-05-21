package com.blueocean.taobao.service;

import com.blueocean.scheduledtask.entity.TaskExecutionLog;
import com.blueocean.scheduledtask.mapper.TaskExecutionLogMapper;
import com.blueocean.taobao.entity.TaobaoAdRecord;
import com.blueocean.taobao.enums.TimeRange;
import com.blueocean.taobao.mapper.TaobaoAdRecordMapper;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class TaobaoAdSyncService {

    @Resource
    private SxkcScraper scraper;

    @Resource
    private TaobaoAdRecordMapper adRecordMapper;

    @Resource
    private TaskExecutionLogMapper taskLogMapper;

    /**
     * 从浏览器抓取数据，保存到数据库后返回
     * @param startDate 开始日期 (yyyy-MM-dd)
     * @param endDate 结束日期 (yyyy-MM-dd)
     */
    public SxkcScraper.ScrapeResult syncFromBrowser(String startDate, String endDate) {
        SxkcScraper.ScrapeResult result = scraper.scrapeShops(startDate, endDate);
        log.info("从省心快车抓取到 {} 条店铺数据，日期范围: {} ~ {}", result.shops.size(), result.startDate, result.endDate);

        // 保存到数据库
        LocalDate statDate = result.endDate != null ? result.endDate : LocalDate.now();
        List<TaobaoAdRecord> records = new ArrayList<>();
        for (SxkcScraper.ShopRow row : result.shops) {
            TaobaoAdRecord record = new TaobaoAdRecord();
            record.setWangwang(row.wangwang);
            record.setShopName(row.shopName);
            record.setCost(row.cost);
            record.setClicks(row.clicks);
            record.setImpressions(row.impressions);
            record.setTotalGmv(row.totalGmv);
            record.setRoi(row.roi);
            record.setClickRate(row.clickRate);
            record.setStatDate(statDate);
            record.setCreateTime(LocalDateTime.now());
            records.add(record);
        }

        if (!records.isEmpty()) {
            // 检查是否与数据库最新一批数据完全一致
            if (isSameAsLatestBatch(statDate, records)) {
                log.info("本批数据与最新记录完全一致，跳过新增");
            } else {
                for (TaobaoAdRecord record : records) {
                    adRecordMapper.insert(record);
                }
                log.info("保存 {} 条广告记录到数据库", records.size());
            }
        }

        return result;
    }

    public void setSearchOnPage(String keyword) {
        scraper.setSearchOnPage(keyword);
    }

    @SneakyThrows
    public List<SxkcScraper.ShopDetailResult> scrapeShopDetails(String startDate, String endDate, TimeRange timeRange) {
        return scraper.scrapeShopDetails(startDate, endDate,timeRange);
    }

    /**
     * 对比 task_execution_log 最新记录，result 内容相同则忽略
     */
    public boolean isEmailDataUnchanged(SxkcScraper.ScrapeResult scrapeResult) {
        // 构建本次结果的指纹
        List<String> parts = new ArrayList<>();
        double totalGmvSum = scrapeResult.shops.stream()
                .mapToDouble(shop -> shop.cost)
                .sum();

        // 查 task_execution_log 中 "广告总花费邮件通知" 按创建时间最新的一条
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskExecutionLog> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(TaskExecutionLog::getTaskName, "广告总花费邮件通知")
               .orderByDesc(TaskExecutionLog::getExecuteTime)
               .last("LIMIT 1");
        TaskExecutionLog latestLog = taskLogMapper.selectOne(wrapper);
        if (latestLog == null || latestLog.getResult() == null) return false;

        return latestLog.getResult().contains("totalCost="+new BigDecimal(totalGmvSum).setScale(2, BigDecimal.ROUND_HALF_UP));
    }


    /**
     * 判断本批数据是否与数据库最新一批完全一致
     */
    private boolean isSameAsLatestBatch(LocalDate statDate, List<TaobaoAdRecord> newRecords) {
        // 查询 statDate 的最新 createTime
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaobaoAdRecord> countWrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        countWrapper.eq(TaobaoAdRecord::getStatDate, statDate)
                    .orderByDesc(TaobaoAdRecord::getCreateTime)
                    .last("LIMIT 1");
        List<TaobaoAdRecord> latestSample = adRecordMapper.selectList(countWrapper);
        if (latestSample.isEmpty()) {
            return false;
        }

        LocalDateTime latestCreateTime = latestSample.get(0).getCreateTime();

        // 查询该 createTime 的所有记录（即最新一批）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaobaoAdRecord> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(TaobaoAdRecord::getStatDate, statDate)
               .eq(TaobaoAdRecord::getCreateTime, latestCreateTime);
        List<TaobaoAdRecord> latestBatch = adRecordMapper.selectList(wrapper);

        if (latestBatch.size() != newRecords.size()) {
            return false;
        }

        // 按 wangwang 建立映射对比
        Map<String, TaobaoAdRecord> latestMap = new HashMap<>();
        for (TaobaoAdRecord r : latestBatch) {
            if (r.getWangwang() != null) {
                latestMap.put(r.getWangwang(), r);
            }
        }

        for (TaobaoAdRecord nr : newRecords) {
            TaobaoAdRecord lr = latestMap.get(nr.getWangwang());
            if (lr == null) return false;
            if (!Objects.equals(nr.getShopName(), lr.getShopName())) return false;
            if (!Objects.equals(nr.getCost(), lr.getCost())) return false;
            if (!Objects.equals(nr.getClicks(), lr.getClicks())) return false;
            if (!Objects.equals(nr.getImpressions(), lr.getImpressions())) return false;
            if (!Objects.equals(nr.getTotalGmv(), lr.getTotalGmv())) return false;
            if (!Objects.equals(nr.getRoi(), lr.getRoi())) return false;
            if (!Objects.equals(nr.getClickRate(), lr.getClickRate())) return false;
        }

        return true;
    }
}
