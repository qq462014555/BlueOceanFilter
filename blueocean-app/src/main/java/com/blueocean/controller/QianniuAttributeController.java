package com.blueocean.controller;

import com.blueocean.scraper.QianniuAttributeScraper;
import com.blueocean.service.AttributeAutoFillService;
import com.blueocean.util.ClaudeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@RestController
@RequestMapping("/api/qianniu-attribute")
public class QianniuAttributeController {

    private static final Logger log = LoggerFactory.getLogger(QianniuAttributeController.class);

    private final QianniuAttributeScraper scraper;
    private final AttributeAutoFillService autoFillService;

    public QianniuAttributeController(@Value("${app.output-dir:output}") String outputDir,
                                      AttributeAutoFillService autoFillService) {
        this.scraper = new QianniuAttributeScraper(outputDir);
        this.autoFillService = autoFillService;
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        log.info("测试千牛 Chrome 连接");
        Map<String, Object> result = scraper.testConnection();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(@RequestParam(defaultValue = "false") boolean forceNew) {
        log.info("开始提取千牛商品属性, forceNew={}", forceNew);

        // 1. 先轻量提取标题（不获取完整属性字段），用于缓存判断
        Map<String, Object> titleResult = scraper.extractTitleOnly();
        if (!Boolean.TRUE.equals(titleResult.get("success"))) {
            return ResponseEntity.ok(titleResult);
        }

        String title = (String) titleResult.get("title");
        if (title == null || title.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", "未能获取宝贝标题");
            return ResponseEntity.ok(err);
        }

        // 2. 获取标题后判断缓存（forceNew=true 时跳过缓存）
        if (!forceNew) {
            Path cachedAiBack = findAiBackFile(title);
            if (cachedAiBack != null) {
                log.info("跳板命中 🚀 已有 AI 缓存，跳过页面属性提取+AI，直接发送填写信号: {}", cachedAiBack);
                Map<String, Object> result = new LinkedHashMap<>(titleResult);
                result.put("autoFillResult", Map.of(
                        "success", true,
                        "cached", true,
                        "savedPath", cachedAiBack.toString(),
                        "message", "使用已有 AI 缓存，跳过提取"
                ));
                result.put("fillFieldsSignaled", true);
                result.put("fillFieldsPath", cachedAiBack.toString());
                ClaudeSignal.fillFields(cachedAiBack.toString(), "QianniuAttributeController", title);
                return ResponseEntity.ok(result);
            }
            log.info("未找到缓存，继续完整提取流程");
        } else {
            log.info("forceNew=true，跳过缓存，强制走完整提取");
        }

        // 3. 无缓存 / 强制新请求：走完整提取流程（获取属性字段 → AI 解析 → 填写）
        Map<String, Object> result = scraper.extract();
        if (Boolean.TRUE.equals(result.get("success"))) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
            if (title != null && fields != null && !fields.isEmpty()) {
                try {
                    log.info("提取成功，调用 AI 填充: title={}, fields={}", title, fields.size());
                    Map<String, Object> fillResult = autoFillService.autoFill(title, fields);
                    if (Boolean.TRUE.equals(fillResult.get("success"))) {
                        result.put("autoFillResult", fillResult);

                        Path aiBackPath = findAiBackFile(title);
                        if (aiBackPath != null) {
                            log.info("AI 填充成功，发送 Claude 填写信号: {}", aiBackPath);
                            ClaudeSignal.fillFields(aiBackPath.toString(), "QianniuAttributeController", title);
                            result.put("fillFieldsSignaled", true);
                            result.put("fillFieldsPath", aiBackPath.toString());
                        } else {
                            log.warn("未找到 qianniu_attr_ai_back.json，跳过 Claude 填写信号");
                        }
                    } else {
                        log.warn("属性自动填充失败: {}", fillResult.get("error"));
                    }
                } catch (Exception e) {
                    log.warn("属性自动填充异常: {}", e.getMessage());
                }
            }
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/list-saved")
    public ResponseEntity<List<Map<String, Object>>> listSaved() {
        List<Map<String, Object>> files = scraper.listSavedFiles();
        return ResponseEntity.ok(files);
    }

    @PostMapping("/fill-fields")
    public ResponseEntity<Map<String, Object>> fillFields(@RequestBody Map<String, Object> request) {
        String title = (String) request.getOrDefault("title", "");

        if (title == null || title.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 title 参数"));
        }

        // 在 RPA 目录下查找 qianniu_attr_ai_back.json
        Path aiBackPath = findAiBackFile(title);
        if (aiBackPath == null) {
            log.warn("未找到 AI 返回的属性文件: {}", title);
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "未找到 " + title + " 的 qianniu_attr_ai_back.json 文件"));
        }

        log.info("找到 AI 返回文件: {}", aiBackPath);

        // 发送信号，让 Claude 来填写
        ClaudeSignal.fillFields(aiBackPath.toString(), "QianniuAttributeController", title);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "已通知 Claude 从 " + aiBackPath.getFileName() + " 读取字段并填写到浏览器");
        result.put("jsonPath", aiBackPath.toString());
        return ResponseEntity.ok(result);
    }

    /**
     * 在 RPA 目录下查找指定商品标题的 qianniu_attr_ai_back.json 文件
     */
    private Path findAiBackFile(String title) {
        Path rpaBaseDir = Paths.get("C:\\Users\\46201\\Documents\\无极RPA文件处理");
        if (!Files.exists(rpaBaseDir)) return null;

        try {
            // 递归搜索所有子目录下的 qianniu_attr_ai_back.json，匹配商品标题
            try (var walk = Files.walk(rpaBaseDir)) {
                return walk
                        .filter(Files::isRegularFile)
                        .filter(p -> "qianniu_attr_ai_back.json".equals(p.getFileName().toString()))
                        .filter(p -> {
                            // 检查父目录名是否包含标题
                            String parentName = p.getParent().getFileName().toString();
                            return parentName.contains(title) || title.contains(parentName);
                        })
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException e) {
            log.error("搜索 AI 返回文件失败", e);
            return null;
        }
    }

    @GetMapping("/read-json")
    public ResponseEntity<Map<String, Object>> readJson(@RequestParam String filepath) {
        Path path = Paths.get(filepath);
        if (!Files.exists(path)) {
            Map<String, Object> err = Map.of("success", false, "error", "文件不存在: " + filepath);
            return ResponseEntity.badRequest().body(err);
        }
        try {
            String content = Files.readString(path);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = com.alibaba.fastjson2.JSON.parseObject(content, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
            String title = (String) data.getOrDefault("title", "");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("fields", fields != null ? fields : Collections.emptyList());
            result.put("fieldCount", fields != null ? fields.size() : 0);
            result.put("title", title);
            result.put("savedPath", path.toString());
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> err = Map.of("success", false, "error", "读取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }
}
