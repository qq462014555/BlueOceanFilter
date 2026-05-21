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
     *  ai补充sku 并且生成sku-ai——reuslt文件
     * @param request {"title": "千牛页面宝贝标题", "pageLevels": ["颜色分类", "尺码"]}
     */
    @PostMapping("/fill")
    public ResponseEntity<Map<String, Object>> generateSkuAiResult(@RequestBody Map<String, Object> request) {
        String title = (String) request.getOrDefault("title", "");
        @SuppressWarnings("unchecked")
        List<String> pageLevels = (List<String>) request.getOrDefault("pageLevels", new ArrayList<>());
        boolean forceRefetch = Boolean.TRUE.equals(request.get("forceRefetch"));

        log.info("SKU 自动填充: title={}, pageLevels={}, forceRefetch={}", title, pageLevels, forceRefetch);

        Map<String, Object> result = skuFillService.generateSkuAiResult(title, pageLevels, forceRefetch);
        return ResponseEntity.ok(result);
    }

    /**
     *  通过sku-ai-result.json文件 ，自动填写sku值
     */
    @PostMapping("/fill-to-page")
    public ResponseEntity<Map<String, Object>> fillToPage(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.getOrDefault("productDir", "");

        log.info("SKU 填写到页面: productDir={}", productDir);

        Map<String, Object> result = skuFillService.fillSkuToPage(productDir);
        return ResponseEntity.ok(result);
    }

    /**
     * 测试：通用方法填写价格和库存（不依赖页面模式）
     */
    @PostMapping("/test-fill-price-stock")
    public ResponseEntity<Map<String, Object>> testFillPriceStock(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.getOrDefault("productDir", "");
        log.info("测试填写价格库存: productDir={}", productDir);

        Map<String, Object> result = skuFillService.fillPriceAndStock(productDir);

        return ResponseEntity.ok(result);
    }

    /**
     * 测试：上传 SKU 图片（不依赖页面模式）
     */
    @PostMapping("/test-fill-sku-image")
    public ResponseEntity<Map<String, Object>> testFillSkuImage(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.getOrDefault("productDir", "");
        log.info("测试上传 SKU 图片: productDir={}", productDir);

        Map<String, Object> result = skuFillService.fillSkuImage(productDir);

        return ResponseEntity.ok(result);
    }
}
