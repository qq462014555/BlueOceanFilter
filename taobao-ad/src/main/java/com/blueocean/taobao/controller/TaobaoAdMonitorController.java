package com.blueocean.taobao.controller;

import com.blueocean.common.annotation.ScheduledTask;
import com.blueocean.common.util.EmailSender;
import com.blueocean.taobao.config.TaobaoChromeConfig;
import com.blueocean.taobao.enums.TimeRange;
import com.blueocean.taobao.service.SxkcScraper;
import com.blueocean.taobao.service.TaobaoAdSyncService;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.util.StringUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/taobao-ad")
public class TaobaoAdMonitorController {

    @Resource
    private TaobaoChromeConfig chromeConfig;

    @Resource
    private TaobaoAdSyncService syncService;

    @Resource
    private EmailSender emailSender;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> result = new HashMap<>();
        result.put("chromePort", 9224);
        result.put("targetUrl", chromeConfig.getTargetUrl());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/live-data")
    public ResponseEntity<Map<String, Object>> getLiveData(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword) {
        try {
            SxkcScraper.ScrapeResult result = syncService.syncFromBrowser(startDate, endDate);

            // 如果有搜索关键词，先设置搜索再重新抓取
            if (keyword != null && !keyword.isEmpty()) {
                syncService.setSearchOnPage(keyword);
                result = syncService.syncFromBrowser(startDate, endDate);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("startDate", result.startDate);
            response.put("endDate", result.endDate);
            response.put("records", result.shops);
            response.put("total", result.shops.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取实时数据失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @ScheduledTask(name = "潜力款_尝试推广邮件通知", description = "从省心快车抓取智能潜力款数据")
    @PostMapping("/scrape-details")
    public ResponseEntity<Map<String, Object>> scrapeShopDetails(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        try {
            List<SxkcScraper.ShopDetailResult> results = List.of();
            if(StringUtils.isEmpty(startDate) && StringUtils.isEmpty(endDate)){
                String format = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                results = syncService.scrapeShopDetails(format,format, TimeRange.PAST_90_DAYS);
            }else{
                 results = syncService.scrapeShopDetails(startDate, endDate,TimeRange.PAST_90_DAYS);
            }

            // 按店铺分层构建邮件内容
            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset='UTF-8'></head><body>");
            html.append("<h2>潜力款智能计划90天报表</h2>");

            for (SxkcScraper.ShopDetailResult detail : results) {
                String shopTitle = (detail.shopName != null ? detail.shopName : "") +
                                   (detail.wangwang != null ? " (" + detail.wangwang + ")" : "");
                html.append("<h3>").append(shopTitle).append(" - ").append(detail.planData.size()).append("个计划</h3>");

                if (detail.planData.isEmpty()) {
                    html.append("<p style='color:#999;'>无计划数据</p>");
                    continue;
                }

                html.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;font-size:12px;margin-bottom:24px;'>");
                html.append("<tr bgcolor='#f2f2f2'>");
                html.append("<th>计划ID</th><th>计划名称</th><th>状态</th><th>日预算</th><th>时间折扣</th><th>单元数</th>");
                html.append("<th>展现量</th><th>点击量</th><th>花费</th><th>点击率</th><th>平均点击成本</th><th>千次展现花费</th>");
                html.append("<th>潜在转化</th><th>收藏宝贝</th><th>收藏店铺</th><th>总收藏</th>");
                html.append("<th>直接加购</th><th>间接加购</th><th>总加购</th><th>加购率</th><th>收藏率</th><th>加购成本</th>");
                html.append("<th>转化率</th><th>直接GMV</th><th>直接笔数</th><th>间接GMV</th><th>间接笔数</th>");
                html.append("<th>投产比</th><th>总GMV</th><th>总笔数</th><th>CTR</th><th>线索</th><th>旺旺咨询</th>");
                html.append("</tr>");

                for (SxkcScraper.PlanRow plan : detail.planData) {
                    html.append("<tr>");
                    html.append("<td>").append(nvl(plan.planId)).append("</td>");
                    html.append("<td>").append(nvl(plan.planName)).append("</td>");
                    html.append("<td>").append(nvl(plan.planStatus)).append("</td>");
                    html.append("<td>").append(fmt(plan.dailyBudget)).append("</td>");
                    html.append("<td>").append(nvl(plan.timeDiscount)).append("</td>");
                    html.append("<td>").append(plan.unitCount).append("</td>");
                    html.append("<td>").append(plan.impressions).append("</td>");
                    html.append("<td>").append(plan.clicks).append("</td>");
                    html.append("<td><b style='color:#e74c3c;'>¥").append(fmt(plan.cost)).append("</b></td>");
                    html.append("<td>").append(pct(plan.clickRate)).append("</td>");
                    html.append("<td>¥").append(fmt(plan.avgClickCost)).append("</td>");
                    html.append("<td>¥").append(fmt(plan.cpmCost)).append("</td>");
                    html.append("<td>").append(fmt(plan.potentialConversion)).append("</td>");
                    html.append("<td>").append(plan.favoritedItems).append("</td>");
                    html.append("<td>").append(plan.favoritedShops).append("</td>");
                    html.append("<td>").append(plan.totalFavorites).append("</td>");
                    html.append("<td>").append(plan.directCartCount).append("</td>");
                    html.append("<td>").append(plan.indirectCartCount).append("</td>");
                    html.append("<td>").append(plan.totalCartCount).append("</td>");
                    html.append("<td>").append(pct(plan.cartRate)).append("</td>");
                    html.append("<td>").append(pct(plan.itemFavoriteRate)).append("</td>");
                    html.append("<td>¥").append(fmt(plan.cartCost)).append("</td>");
                    html.append("<td>").append(pct(plan.conversionRate)).append("</td>");
                    html.append("<td>¥").append(fmt(plan.directGmv)).append("</td>");
                    html.append("<td>").append(plan.directTransactions).append("</td>");
                    html.append("<td>¥").append(fmt(plan.indirectGmv)).append("</td>");
                    html.append("<td>").append(plan.indirectTransactions).append("</td>");
                    html.append("<td>").append(fmt(plan.roi)).append("</td>");
                    html.append("<td>¥").append(fmt(plan.totalGmv)).append("</td>");
                    html.append("<td>").append(plan.totalTransactions).append("</td>");
                    html.append("<td>").append(pct(plan.ctr)).append("</td>");
                    html.append("<td>").append(nvl(plan.clueInfo)).append("</td>");
                    html.append("<td>").append(plan.wwConsultCount).append("</td>");
                    html.append("</tr>");
                }
                html.append("</table>");
            }
            html.append("<p style='color:#999;font-size:12px;margin-top:16px;'>自动生成</p>");
            html.append("</body></html>");

            emailSender.sendHtml("", "[所有计划90天]  - " + results.size() + "个店铺", html.toString());
            log.info("潜力款邮件已发送，店铺数: {}, 计划数: {}", results.size(),
                    results.stream().mapToInt(d -> d.planData.size()).sum());


            List<SxkcScraper.ShopDetailResult> shopDetailResults = syncService.scrapeShopDetails(startDate, endDate, TimeRange.TODAY);
            log.info("店铺详情采集完成，店铺数: {}", shopDetailResults.size());

            Map<String, Object> response = new HashMap<>();
            response.put("total", results.size());
            response.put("shops", results);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("店铺详情采集失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @ScheduledTask(name = "广告总花费邮件通知", description = "从省心快车抓取广告数据，汇总总花费并通过邮件发送通知")
    @GetMapping("/report-total-cost")
    public Map<String, Object> reportTotalCost() {
        Map<String, Object> result = new HashMap<>();
        try {
            String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            SxkcScraper.ScrapeResult scrapeResult = syncService.syncFromBrowser(today, today);
            List<SxkcScraper.ShopRow> shopRows = scrapeResult.shops;

            double totalCost = 0;
            double balanceSum = 0;
            long totalClicks = 0;
            long totalImpressions = 0;
            double totalGmv = 0;

            for (SxkcScraper.ShopRow row : shopRows) {
                totalCost += row.cost;
                balanceSum += row.balance;
                totalClicks += row.clicks;
                totalImpressions += row.impressions;
                totalGmv += row.totalGmv;
            }

            result.put("success", true);
            result.put("shopCount", shopRows.size());
            result.put("totalCost", String.format("%.2f", totalCost));
            result.put("totalClicks", totalClicks);
            result.put("totalImpressions", totalImpressions);
            result.put("totalGmv", String.format("%.2f", totalGmv));
            result.put("avgCpc", totalClicks > 0 ? String.format("%.2f", totalCost / totalClicks) : "0.00");

            // 构建 HTML 邮件内容
            StringBuilder html = new StringBuilder();
            html.append("<html><head><meta charset='UTF-8'></head><body>");
            html.append("<h2>淘宝广告花费日报</h2>");
            html.append("<p>统计店铺数: ").append(shopRows.size()).append("</p>");

            // 店铺明细表
            html.append("<h3>店铺明细</h3>");
            html.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;font-size:13px;margin-bottom:20px;'>");
            html.append("<tr bgcolor='#f2f2f2'>");
            html.append("<th>店铺</th><th>旺旺</th><th>推广余额</th><th>花费</th><th>点击</th><th>展现</th><th>总GMV</th><th>ROI</th>");
            html.append("</tr>");

            for (SxkcScraper.ShopRow row : shopRows) {
                html.append("<tr>");
                html.append("<td>").append(row.shopName != null ? row.shopName : "-").append("</td>");
                html.append("<td>").append(row.wangwang != null ? row.wangwang : "-").append("</td>");
                html.append("<td><b style='color:#e74c3c;'>¥").append(String.format("%.2f", row.balance)).append("</b></td>");
                html.append("<td><b style='color:#e74c3c;'>¥").append(String.format("%.2f", row.cost)).append("</b></td>");
                html.append("<td>").append(row.clicks).append("</td>");
                html.append("<td>").append(row.impressions).append("</td>");
                html.append("<td>¥").append(String.format("%.2f", row.totalGmv)).append("</td>");
                html.append("<td>").append(String.format("%.2f", row.roi)).append("</td>");
                html.append("</tr>");
            }
            html.append("</table>");

            // 汇总
            html.append("<h3>汇总</h3>");
            html.append("<table border='1' cellpadding='8' cellspacing='0' style='border-collapse:collapse;font-size:14px;'>");
            html.append("<tr bgcolor='#f2f2f2'><th>指标</th><th>数值</th></tr>");
            html.append("<tr><td>推广账户余额</td><td><b>¥").append(String.format("%.2f", balanceSum)).append("</b></td></tr>");
            html.append("<tr><td>总花费</td><td><b style='color:#e74c3c;'>¥").append(String.format("%.2f", totalCost)).append("</b></td></tr>");
            html.append("<tr><td>总点击</td><td>").append(totalClicks).append("</td></tr>");
            html.append("<tr><td>总展现</td><td>").append(totalImpressions).append("</td></tr>");
            html.append("<tr><td>总GMV</td><td>¥").append(String.format("%.2f", totalGmv)).append("</td></tr>");
            html.append("<tr><td>平均点击成本</td><td>¥").append(totalClicks > 0 ? String.format("%.2f", totalCost / totalClicks) : "0.00").append("</td></tr>");
            html.append("</table>");
            html.append("<p style='color:#999;font-size:12px;margin-top:16px;'>自动生成</p>");
            html.append("</body></html>");

            // 对比数据库最新记录，内容不变则不发送邮件
            if (syncService.isEmailDataUnchanged(scrapeResult)) {
                log.info("广告数据与上次一致，跳过邮件发送");
                result.put("emailSent", true);
                log.info("广告花费汇总: 总花费={}, 总点击={}, 总展现={}, 总GMV={}", totalCost, totalClicks, totalImpressions, totalGmv);

            } else {
                emailSender.sendHtml("", "[淘宝广告] 花费通知 - 总花费¥" + String.format("%.2f", totalCost), html.toString());
                result.put("emailSent", true);
                log.info("广告花费汇总: 总花费={}, 总点击={}, 总展现={}, 总GMV={}", totalCost, totalClicks, totalImpressions, totalGmv);
            }
        } catch (Exception e) {
            log.error("获取广告总花费失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String nvl(String s) {
        return s != null && !s.isEmpty() ? s : "-";
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }

    private String pct(double v) {
        return v > 0 ? String.format("%.2f%%", v) : "-";
    }
}
