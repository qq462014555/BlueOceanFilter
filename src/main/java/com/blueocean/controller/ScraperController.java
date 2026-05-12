package com.blueocean.controller;

import com.blueocean.scraper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/scraper")
public class ScraperController {

    private static final Logger log = LoggerFactory.getLogger(ScraperController.class);

    private final ProductScraper productScraper = new ProductScraper();
    private final ExcelReporter excelReporter = new ExcelReporter();

    private volatile boolean running = false;
    private volatile String statusMessage = "就绪";
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final List<ProductData> results = new CopyOnWriteArrayList<>();

    @PostMapping("/start")
    public ResponseEntity<?> start() {
        if (running) {
            return ResponseEntity.badRequest().body("采集任务正在运行中");
        }
        runAsync();
        return ResponseEntity.ok(Collections.singletonMap("message", "采集任务已启动"));
    }

    @GetMapping("/scrape")
    public ResponseEntity<?> scrapeSingle(@RequestParam String url) {
        try {
            LinkEntry link = new LinkEntry("", url);
            List<ProductData> results = productScraper.scrapeProducts(Collections.singletonList(link));
            if (results.isEmpty()) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", "抓取失败，可能无法检测页面布局"));
            }
            return ResponseEntity.ok(results.get(0));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "抓取失败: " + e.getMessage()));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> map = new HashMap<>();
        map.put("running", running);
        map.put("message", statusMessage);
        map.put("processed", processedCount.get());
        map.put("total", totalCount.get());
        return ResponseEntity.ok(map);
    }

    @Async("taskExecutor")
    public void runAsync() {
        running = true;
        results.clear();
        processedCount.set(0);

        try {
            File linkFile = LinkFileReader.findLatestLinkFile();
            log.info("找到链接文件: {}", linkFile.getAbsolutePath());
            statusMessage = "读取链接文件: " + linkFile.getName();

            List<LinkEntry> links = LinkFileReader.read(linkFile);
            totalCount.set(links.size());
            log.info("共解析到 {} 个商品链接", links.size());

            for (int i = 0; i < links.size(); i++) {
                LinkEntry link = links.get(i);
                statusMessage = String.format("[%d/%d] 正在抓取: %s", i + 1, links.size(), link.getCategoryPath());
                log.info(statusMessage);

                List<ProductData> productResults = productScraper.scrapeProducts(Collections.singletonList(link));
                results.addAll(productResults);
                processedCount.incrementAndGet();
            }

            if (!results.isEmpty()) {
                statusMessage = "生成Excel汇总表...";
                String excelPath = excelReporter.generateSummary(results);
                log.info("Excel汇总表已生成: {}", excelPath);
            }

            statusMessage = String.format("采集完成！共处理 %d 个商品，成功 %d 个", links.size(), results.size());
            log.info(statusMessage);
        } catch (Exception e) {
            statusMessage = "采集失败: " + e.getMessage();
            log.error("采集任务异常", e);
        } finally {
            running = false;
        }
    }
}
