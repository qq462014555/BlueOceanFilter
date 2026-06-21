package com.blueocean.controller;

import com.blueocean.config.ChromeDebugConfig;
import com.blueocean.ocr.PaddleOcrService;
import com.blueocean.scraper.*;
import com.blueocean.service.EmailNotificationService;
import com.blueocean.util.ChromeLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.util.ArrayList;
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
    /** 实时日志缓冲区，前端轮询读取 */
    private final List<String> logBuffer = new CopyOnWriteArrayList<>();
    /** SSE 发射器，用于实时推送日志到前端 */
    private volatile SseEmitter logEmitter = null;

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
            // 单链接也设置进度回调，将日志推送到 SSE 控制台
            productScraper.setProgressCallback(msg -> {
                addLog(msg);
                statusMessage = msg;
            });
            List<ProductData> results = productScraper.scrapeProducts(Collections.singletonList(link));
            if (results.isEmpty()) {
                return ResponseEntity.badRequest().body(Collections.singletonMap("error", "抓取失败，可能无法检测页面布局"));
            }
            return ResponseEntity.ok(results.get(0));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "抓取失败: " + e.getMessage()));
        } finally {
            productScraper.setProgressCallback(null);
            emitComplete();
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
        // 只返回最近200条日志
        int logSize = logBuffer.size();
        int startIdx = Math.max(0, logSize - 200);
        map.put("logs", new ArrayList<>(logBuffer.subList(startIdx, logSize)));
        return ResponseEntity.ok(map);
    }

    @GetMapping("/results")
    public ResponseEntity<?> getResults() {
        return ResponseEntity.ok(results);
    }

    /** SSE 实时日志流 */
    @GetMapping(value = "/log-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs() {
        SseEmitter emitter = new SseEmitter(0L); // 无超时
        logEmitter = emitter;
        emitter.onCompletion(() -> { if (logEmitter == emitter) logEmitter = null; });
        emitter.onTimeout(() -> { if (logEmitter == emitter) logEmitter = null; });
        return emitter;
    }

    private void addLog(String msg) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String logLine = "[" + time + "] " + msg;
        logBuffer.add(logLine);
        // SSE 实时推送
        SseEmitter emitter = logEmitter;
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("log").data(logLine));
            } catch (Exception e) {
                logEmitter = null;
            }
        }
    }

    /** 标记采集完成，通知前端的 SSE */
    private void emitComplete() {
        SseEmitter emitter = logEmitter;
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("complete").data("done"));
                emitter.complete();
            } catch (Exception ignored) {}
            logEmitter = null;
        }
    }

    @Async("taskExecutor")
    public void runAsync() {
        running = true;
        results.clear();
        processedCount.set(0);
        logBuffer.clear();

        // 将进度回调接入日志缓冲区
        productScraper.setProgressCallback(msg -> {
            addLog(msg);
            statusMessage = msg;
        });

        try {
            File linkFile = LinkFileReader.findLatestLinkFile();
            log.info("找到链接文件: {}", linkFile.getAbsolutePath());
            statusMessage = "读取链接文件: " + linkFile.getName();
            addLog("读取链接文件: " + linkFile.getName());

            List<LinkEntry> links = LinkFileReader.read(linkFile);
            totalCount.set(links.size());
            log.info("共解析到 {} 个商品链接", links.size());
            addLog("共 " + links.size() + " 个商品链接");

            statusMessage = "开始并发抓取 " + links.size() + " 个商品...";
            addLog("开始采集 " + links.size() + " 个商品...");
            List<ProductData> productResults = productScraper.scrapeProducts(links);
            results.addAll(productResults);
            processedCount.set(links.size());

            if (!results.isEmpty()) {
                statusMessage = "生成Excel汇总表...";
                addLog("生成Excel汇总表...");
                String excelPath = excelReporter.generateSummary(results);
                log.info("Excel汇总表已生成: {}", excelPath);
            }

            statusMessage = String.format("采集完成！共处理 %d 个商品，成功 %d 个", links.size(), results.size());
            log.info(statusMessage);
            addLog("✅ 采集完成！共处理 " + links.size() + " 个商品，成功 " + results.size() + " 个");
            emailNotificationService.sendNotification("商品采集完成", statusMessage);
        } catch (Exception e) {
            statusMessage = "采集失败: " + e.getMessage();
            log.error("采集任务异常", e);
            addLog("❌ 采集失败: " + e.getMessage());
            emailNotificationService.sendNotification("商品采集失败", statusMessage);
        } finally {
            emitComplete();
            running = false;
            productScraper.setProgressCallback(null);
        }
    }

    @Async("taskExecutor")
    public void runFromRpaAsync() {
        running = true;
        results.clear();
        processedCount.set(0);
        logBuffer.clear();

        // 将进度回调接入日志缓冲区和状态更新
        productScraper.setProgressCallback(msg -> {
            addLog(msg);
            // 更新状态消息为最新日志
            statusMessage = msg;
        });

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
            addLog("读取链接文件: " + linkFile.getParentFile().getName());

            List<LinkEntry> links = LinkFileReader.read(linkFile);
            totalCount.set(links.size());
            log.info("共解析到 {} 个商品链接", links.size());
            addLog("共 " + links.size() + " 个商品链接");

            statusMessage = "开始并发抓取 " + links.size() + " 个商品...";
            addLog("开始采集 " + links.size() + " 个商品...");
            List<ProductData> productResults = productScraper.scrapeProducts(links);
            results.addAll(productResults);
            processedCount.set(links.size());

            if (!results.isEmpty()) {
                statusMessage = "生成Excel汇总表...";
                addLog("生成Excel汇总表...");
                String excelPath = excelReporter.generateSummary(results);
                log.info("Excel汇总表已生成: {}", excelPath);
            }

            statusMessage = String.format("采集完成！共处理 %d 个商品，成功 %d 个", links.size(), results.size());
            log.info(statusMessage);
            addLog("✅ 采集完成！共处理 " + links.size() + " 个商品，成功 " + results.size() + " 个");
            emailNotificationService.sendNotification("RPA商品采集完成", statusMessage);
        } catch (Exception e) {
            statusMessage = "采集失败: " + e.getMessage();
            log.error("采集任务异常", e);
            addLog("❌ 采集失败: " + e.getMessage());
            emailNotificationService.sendNotification("RPA商品采集失败", statusMessage);
        } finally {
            emitComplete();
            running = false;
            productScraper.setProgressCallback(null);
        }
    }
}
