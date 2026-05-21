package com.blueocean.controller;

import com.blueocean.service.AttributeAutoFillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品属性 AI 自动填充 API
 * 独立控制器，不修改现有 QianniuAttributeController
 */
@RestController
@RequestMapping("/api/attribute-auto-fill")
public class AttributeAutoFillController {

    private static final Logger log = LoggerFactory.getLogger(AttributeAutoFillController.class);

    private final AttributeAutoFillService autoFillService;

    public AttributeAutoFillController(AttributeAutoFillService autoFillService) {
        this.autoFillService = autoFillService;
    }

    /**
     * 查找商品目录
     */
    @PostMapping("/find-product")
    public ResponseEntity<Map<String, Object>> findProduct(@RequestBody Map<String, String> request) {
        String title = request.getOrDefault("title", "");
        log.info("查找商品目录: {}", title);
        String dir = autoFillService.findProductDir(title);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", dir != null);
        result.put("dir", dir);
        return ResponseEntity.ok(result);
    }

    /**
     * 执行 AI 自动填充
     */
    @PostMapping("/fill")
    public ResponseEntity<Map<String, Object>> autoFill(@RequestBody Map<String, Object> request) {
        String title = (String) request.getOrDefault("title", "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> qianniuFields = (List<Map<String, Object>>) request.get("fields");

        log.info("AI 自动填充: title={}, fields={}", title, qianniuFields != null ? qianniuFields.size() : 0);

        Map<String, Object> result = autoFillService.autoFill(title, qianniuFields);
        return ResponseEntity.ok(result);
    }
}
