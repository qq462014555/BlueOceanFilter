package com.blueocean.controller;

import com.blueocean.scraper.QianniuAttributeScraper;
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

    public QianniuAttributeController(@Value("${app.output-dir:output}") String outputDir) {
        this.scraper = new QianniuAttributeScraper(outputDir);
    }

    @PostMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        log.info("测试千牛 Chrome 连接");
        Map<String, Object> result = scraper.testConnection();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract() {
        log.info("开始提取千牛商品属性");
        Map<String, Object> result = scraper.extract();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/list-saved")
    public ResponseEntity<List<Map<String, Object>>> listSaved() {
        List<Map<String, Object>> files = scraper.listSavedFiles();
        return ResponseEntity.ok(files);
    }

    @PostMapping("/fill-fields")
    public ResponseEntity<Map<String, Object>> fillFields(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) request.get("fields");
        log.info("开始填写属性到千牛页面: {} 个字段", fields != null ? fields.size() : 0);
        Map<String, Object> result = scraper.fillFields(fields);
        return ResponseEntity.ok(result);
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
