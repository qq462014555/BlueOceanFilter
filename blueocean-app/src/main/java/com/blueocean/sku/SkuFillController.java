package com.blueocean.sku;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * SKU 自动填充 API
 * 独立控制器，不修改现有代码
 */
@RestController
@RequestMapping("/api/sku-fill")
public class SkuFillController {

    private static final Logger log = LoggerFactory.getLogger(SkuFillController.class);

    private final SkuFillService skuFillService;

    public SkuFillController(SkuFillService skuFillService) {
        this.skuFillService = skuFillService;
    }

    /**
     * 测试：仅从千牛页面提取 SKU 属性
     */
    @PostMapping("/test-extract")
    public ResponseEntity<Map<String, Object>> testExtract() {
        log.info("测试提取千牛 SKU 属性");
        Map<String, Object> result = skuFillService.testExtractSkuProps();
        return ResponseEntity.ok(result);
    }

    /**
     * 执行 SKU 自动填充
     * @param request {"title": "千牛页面宝贝标题", "pageLevels": ["颜色分类", "尺码"]}
     */
    @PostMapping("/fill")
    public ResponseEntity<Map<String, Object>> fillSku(@RequestBody Map<String, Object> request) {
        String title = (String) request.getOrDefault("title", "");
        @SuppressWarnings("unchecked")
        List<String> pageLevels = (List<String>) request.getOrDefault("pageLevels", new ArrayList<>());
        boolean forceRefetch = Boolean.TRUE.equals(request.get("forceRefetch"));

        log.info("SKU 自动填充: title={}, pageLevels={}, forceRefetch={}", title, pageLevels, forceRefetch);

        Map<String, Object> result = skuFillService.fillSku(title, pageLevels, forceRefetch);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/fill-to-page")
    public ResponseEntity<Map<String, Object>> fillToPage(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.getOrDefault("productDir", "");

        log.info("SKU 填写到页面: productDir={}", productDir);

        Map<String, Object> result = skuFillService.fillSkuToPage(productDir);
        return ResponseEntity.ok(result);
    }
}
