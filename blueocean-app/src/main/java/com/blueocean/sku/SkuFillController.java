package com.blueocean.sku;

import com.blueocean.sku.extractor.SkuAttrExtractor;
import io.micrometer.common.util.StringUtils;
import org.apache.commons.compress.utils.Lists;
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

    static SkuAttrExtractor SKU_EXTRACTOR;
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

        log.info("SKU 自动填充: title={}, pageLevels={}, forceRefetch={}", title, forceRefetch);

        Map<String, Object> result = skuFillService.generateSkuAiResult(title, forceRefetch);

        // 成功后，自动填写 SKU、价格库存、SKU 图片到千牛页面
        if (Boolean.TRUE.equals(result.get("success"))) {
            String productDir = (String) result.get("productDir");
            if (productDir != null) {
                try {
                    Map<String, Object> fillResult = skuFillService.fillSkuToPage(productDir, (List<String>) result.get("qianniuSkuProps"));
                    if (Boolean.TRUE.equals(fillResult.get("success"))) {
                        // 2. 填写价格和库存
                        Map<String, Object> priceResult = skuFillService.fillPriceAndStock(productDir);
                        if (Boolean.TRUE.equals(priceResult.get("success"))) {
                            // 3. 上传 SKU 图片
                            Map<String, Object> imageResult = skuFillService.fillSkuImage(productDir);
                            if (!Boolean.TRUE.equals(imageResult.get("success"))) {
                                log.warn("SKU 图片上传失败: {}", imageResult.get("error"));
                            }
                        } else {
                            log.warn("价格和库存填写失败: {}", priceResult.get("error"));
                        }
                    } else {
                        log.warn("SKU 属性填写失败: {}", fillResult.get("error"));
                    }
                } catch (Exception e) {
                    log.warn("自动填写流程异常: {}", e.getMessage());
                }
            }
        }

        return ResponseEntity.ok(result);
    }

    /**
     *  通过sku-ai-result.json文件 ，自动填写sku值
     */
    @PostMapping("/fill-to-page")
    public ResponseEntity<Map<String, Object>> fillToPage(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.getOrDefault("productDir", "");

        log.info("SKU 填写到页面: productDir={}", productDir);

        Map<String, Object> result = skuFillService.fillSkuToPage(productDir,null);

        // SKU 填写成功后，自动填写价格和库存
        if (Boolean.TRUE.equals(result.get("success"))) {
            try {
                Map<String, Object> priceResult = skuFillService.fillPriceAndStock(productDir);
                if (Boolean.TRUE.equals(priceResult.get("success"))) {
                    // 价格和库存填写成功后，自动上传 SKU 图片
                    Map<String, Object> imageResult = skuFillService.fillSkuImage(productDir);
                    if (!Boolean.TRUE.equals(imageResult.get("success"))) {
                        log.warn("SKU 图片上传失败: {}", imageResult.get("error"));
                    }
                } else {
                    log.warn("价格和库存填写失败: {}", priceResult.get("error"));
                }
            } catch (Exception e) {
                log.warn("价格和库存/SKU 图片填写异常: {}", e.getMessage());
            }
        }

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
