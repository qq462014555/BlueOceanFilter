package com.blueocean.controller;

import com.blueocean.config.ChromeDebugConfig;
import com.blueocean.ocr.PaddleOcrService;
import com.blueocean.scraper.*;
import com.blueocean.service.EmailNotificationService;
import com.blueocean.util.ChromeLauncher;
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

    private final PaddleOcrService ocrService;
    private final ProductScraper productScraper;
    private final ExcelReporter excelReporter = new ExcelReporter();
    private final EmailNotificationService emailNotificationService;
    private final ChromeDebugConfig chromeDebugConfig;

    public ScraperController(PaddleOcrService ocrService,
                             EmailNotificationService emailNotificationService,
                             ChromeDebugConfig chromeDebugConfig) {
        this.ocrService = ocrService;
        this.productScraper = new ProductScraper(ocrService);
        this.emailNotificationService = emailNotificationService;
        this.chromeDebugConfig = chromeDebugConfig;
    }

    private volatile boolean running = false;
    private volatile String statusMessage = "就绪";
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final List<ProductData> results = new CopyOnWriteArrayList<>();

    private static final String RPA_BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";

    @PostMapping("/start")
    public ResponseEntity<?> start() {
        if (running) {
            return ResponseEntity.badRequest().body("采集任务正在运行中");
        }
        runAsync();
        return ResponseEntity.ok(Collections.singletonMap("message", "采集任务已启动"));
    }

    @PostMapping("/start-from-rpa")
    public ResponseEntity<?> startFromRpa() {
        if (running) {
            return ResponseEntity.badRequest().body("采集任务正在运行中");
        }
        runFromRpaAsync();
        return ResponseEntity.ok(Collections.singletonMap("message", "已从RPA文件启动采集"));
    }

    @PostMapping("/launch-chrome")
    public ResponseEntity<?> launchChrome() {
        return launchChromeWithPort(9222, null);
    }

    @PostMapping("/launch-chrome-merchant")
    public ResponseEntity<?> launchChromeMerchant() {
        return launchChromeWithPort(9223, null);
    }

    @PostMapping("/launch-chrome/{port}")
    public ResponseEntity<?> launchChromeByPort(@PathVariable int port) {
        String debugDir = chromeDebugConfig.getDebugDir(port);
        if (debugDir == null) {
            return ResponseEntity.badRequest().body("未配置端口 " + port + " 对应的数据目录，请在 application.yml 中配置 app.chrome-debug." + port);
        }
        String targetUrl = chromeDebugConfig.getTargetUrl(port);
        return launchChromeWithPort(port, targetUrl);
    }

    private ResponseEntity<?> launchChromeWithPort(int port, String targetUrl) {
        String debugDir = chromeDebugConfig.getDebugDir(port);
        if (debugDir == null) {
            return ResponseEntity.badRequest().body("未配置端口 " + port + " 的数据目录");
        }
        try {
            String msg = ChromeLauncher.launch(chromeDebugConfig.getChromePath(), port, debugDir, targetUrl);
            return ResponseEntity.ok(Collections.singletonMap("message", msg));
        } catch (Exception e) {
            log.error("启动Chrome失败 (port={})", port, e);
            return ResponseEntity.badRequest().body("启动Chrome失败: " + e.getMessage());
        }
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
        map.put("resultCount", results.size());
        return ResponseEntity.ok(map);
    }

    @GetMapping("/results")
    public ResponseEntity<?> getResults() {
        return ResponseEntity.ok(results);
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

            statusMessage = "开始并发抓取 " + links.size() + " 个商品...";
            List<ProductData> productResults = productScraper.scrapeProducts(links);
            results.addAll(productResults);
            processedCount.set(links.size());

            if (!results.isEmpty()) {
                statusMessage = "生成Excel汇总表...";
                String excelPath = excelReporter.generateSummary(results);
                log.info("Excel汇总表已生成: {}", excelPath);
            }

            statusMessage = String.format("采集完成！共处理 %d 个商品，成功 %d 个", links.size(), results.size());
            log.info(statusMessage);
            emailNotificationService.sendNotification("商品采集完成", statusMessage);
        } catch (Exception e) {
            statusMessage = "采集失败: " + e.getMessage();
            log.error("采集任务异常", e);
            emailNotificationService.sendNotification("商品采集失败", statusMessage);
        } finally {
            running = false;
        }
    }

    @Async("taskExecutor")
    public void runFromRpaAsync() {
        running = true;
        results.clear();
        processedCount.set(0);

        try {
            File rpaDir = new File(RPA_BASE_DIR);
            if (!rpaDir.exists() || !rpaDir.isDirectory()) {
                statusMessage = "RPA目录不存在: " + RPA_BASE_DIR;
                running = false;
                return;
            }

            // 找到今天的 _链接.txt 文件（按日期目录匹配）
            File linkFile = LinkFileReader.findTodayLinkFile();
            log.info("从RPA读取今天的链接文件: {}", linkFile.getAbsolutePath());
            statusMessage = "读取RPA文件: " + linkFile.getParentFile().getName();

            List<LinkEntry> links = LinkFileReader.read(linkFile);
            totalCount.set(links.size());
            log.info("共解析到 {} 个商品链接", links.size());

            statusMessage = "开始并发抓取 " + links.size() + " 个商品...";
            List<ProductData> productResults = productScraper.scrapeProducts(links);
            results.addAll(productResults);
            processedCount.set(links.size());

            if (!results.isEmpty()) {
                statusMessage = "生成Excel汇总表...";
                String excelPath = excelReporter.generateSummary(results);
                log.info("Excel汇总表已生成: {}", excelPath);
            }

            statusMessage = String.format("采集完成！共处理 %d 个商品，成功 %d 个", links.size(), results.size());
            log.info(statusMessage);
            emailNotificationService.sendNotification("RPA商品采集完成", statusMessage);
        } catch (Exception e) {
            statusMessage = "采集失败: " + e.getMessage();
            log.error("采集任务异常", e);
            emailNotificationService.sendNotification("RPA商品采集失败", statusMessage);
        } finally {
            running = false;
        }
    }
}
