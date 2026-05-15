package com.blueocean.controller;

import com.blueocean.scraper.QianniuAttributeScraper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
