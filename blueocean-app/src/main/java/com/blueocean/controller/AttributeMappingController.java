package com.blueocean.controller;

import com.blueocean.scraper.AttributeSaver;
import com.blueocean.scraper.MerchantAttributeExtractor;
import com.blueocean.service.AttributeMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品属性映射 API
 * 供影刀 RPA 调用：将 1688 采集属性映射到商家后台字段
 */
@RestController
@RequestMapping("/api/attr")
public class AttributeMappingController {

    private static final Logger log = LoggerFactory.getLogger(AttributeMappingController.class);

    private final AttributeMappingService mappingService;

    public AttributeMappingController(AttributeMappingService mappingService) {
        this.mappingService = mappingService;
    }

    /**
     * 测试 CDP 连接
     * GET /api/attr/test-cdp?port=9222
     */
    @GetMapping("/test-cdp")
    public ResponseEntity<?> testCdp(@RequestParam(defaultValue = "9222") int port) {
        try {
            String endpoint = "http://localhost:" + port;
            log.info("测试 CDP 连接: {}", endpoint);
            MerchantAttributeExtractor extractor = new MerchantAttributeExtractor(endpoint);
            extractor.testConnection();
            return ResponseEntity.ok("CDP 连接成功: " + endpoint);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("CDP 连接失败: " + e.getMessage());
        }
    }

    /**
     * 从商家后台提取属性字段（通过 CDP 连接影刀浏览器）
     * POST /api/attr/extract
     * {
     *   "cdpPort": 9222,
     *   "outputDir": "C:\\Users\\46201\\Documents\\商品属性",
     *   "filename": "商家后台字段.json"
     * }
     */
    @PostMapping("/extract")
    public ResponseEntity<?> extractFields(@RequestBody Map<String, Object> request) {
        try {
            int port = request.containsKey("cdpPort") ? ((Number) request.get("cdpPort")).intValue() : 9222;
            String outputDir = (String) request.getOrDefault("outputDir", "C:\\Users\\46201\\Documents\\商品属性");
            String filename = (String) request.getOrDefault("filename", "商家后台字段.json");

            MerchantAttributeExtractor extractor = new MerchantAttributeExtractor("http://localhost:" + port);
            List<Map<String, Object>> fields = extractor.extractFields();

            if (fields.isEmpty()) {
                return ResponseEntity.badRequest().body("未提取到任何字段，请确认商家后台页面已打开");
            }

            String path = extractor.saveToFile(fields, outputDir, filename);
            return ResponseEntity.ok(Map.of(
                    "count", fields.size(),
                    "file", path,
                    "fields", fields
            ));
        } catch (Exception e) {
            log.error("提取属性字段失败", e);
            return ResponseEntity.internalServerError().body("提取失败: " + e.getMessage());
        }
    }

    /**
     * 属性映射接口
     *
     * 请求体格式：
     * {
     *   "productDir": "/path/to/product",
     *   "category": "自行车/车篮",
     *   "targetFields": [
     *     {"label": "安装位置", "type": "text"},
     *     {"label": "品牌", "type": "dropdown", "options": ["品牌A", "品牌B"]},
     *     ...
     *   ]
     * }
     *
     * 返回格式：
     * {
     *   "mappings": {
     *     "安装位置": "安装位置",
     *     "材质": "车筐材质",
     *     ...
     *   },
     *   "matchedCount": 5,
     *   "unmatchedTarget": ["xxx", "yyy"],
     *   "unmatchedSource": ["zzz"]
     * }
     */
    @PostMapping("/map")
    public ResponseEntity<?> mapAttributes(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        String category = (String) request.get("category");
        Object targetFieldsObj = request.get("targetFields");

        if (productDir == null || targetFieldsObj == null) {
            return ResponseEntity.badRequest().body("缺少 productDir 或 targetFields 参数");
        }

        // 1. 加载 1688 采集属性
        Map<String, String> scrapedAttrs = AttributeSaver.load(productDir);
        if (scrapedAttrs == null || scrapedAttrs.isEmpty()) {
            return ResponseEntity.badRequest().body("未找到商品属性文件，请先完成采集: " + productDir);
        }
        log.info("[属性映射API] 加载采集属性 {} 个", scrapedAttrs.size());

        // 2. 提取目标字段名列表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targetFieldsList = (List<Map<String, Object>>) targetFieldsObj;
        List<String> targetFieldNames = targetFieldsList.stream()
                .map(f -> (String) f.get("label"))
                .filter(l -> l != null && !l.isEmpty())
                .toList();

        // 3. 调用 AI 映射
        try {
            Map<String, String> mapping = mappingService.mapAttributes(scrapedAttrs, targetFieldNames, category);

            // 4. 构建完整返回结果（包含字段类型和选项，方便影刀直接填写）
            Map<String, Object> result = new HashMap<>();

            // 映射表：{"采集属性名": {"label": "商家字段名", "type": "text", "value": "铁", "options": [...]}}
            Map<String, Object> fillData = new HashMap<>();
            for (Map.Entry<String, String> entry : mapping.entrySet()) {
                String sourceAttr = entry.getKey();
                String targetLabel = entry.getValue();
                String value = scrapedAttrs.get(sourceAttr);

                // 找到对应的目标字段类型
                Map<String, Object> fieldInfo = targetFieldsList.stream()
                        .filter(f -> targetLabel.equals(f.get("label")))
                        .findFirst()
                        .orElse(Map.of());

                Map<String, Object> item = new HashMap<>();
                item.put("label", targetLabel);
                item.put("type", fieldInfo.getOrDefault("type", "text"));
                item.put("value", value);
                item.put("options", fieldInfo.get("options"));
                fillData.put(targetLabel, item);
            }

            // 未匹配的商家字段
            List<String> unmatchedTarget = targetFieldNames.stream()
                    .filter(name -> !mapping.containsValue(name))
                    .toList();

            // 未匹配的采集属性
            List<String> unmatchedSource = scrapedAttrs.keySet().stream()
                    .filter(name -> !mapping.containsKey(name))
                    .toList();

            result.put("fillData", fillData);
            result.put("matchedCount", mapping.size());
            result.put("unmatchedTarget", unmatchedTarget);
            result.put("unmatchedSource", unmatchedSource);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("[属性映射API] 映射失败", e);
            return ResponseEntity.internalServerError().body("映射失败: " + e.getMessage());
        }
    }
}
