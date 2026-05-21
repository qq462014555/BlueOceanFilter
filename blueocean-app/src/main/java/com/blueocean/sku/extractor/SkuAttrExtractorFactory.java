package com.blueocean.sku.extractor;

import com.microsoft.playwright.Page;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKU 属性提取器工厂
 * 遍历所有注册的提取策略，按 matches 判断使用哪个
 */
public class SkuAttrExtractorFactory {

    private static final List<SkuAttrExtractor> EXTRACTORS = List.of(
            new PresetAttrExtractor(),
            new CreateSpecAttrExtractor()
            // 后续新增模式直接在这里注册
    );

    public static List<String> extract(Page page) {
        for (SkuAttrExtractor extractor : EXTRACTORS) {
            if (extractor.matches(page)) {
                return extractor.extract(page);
            }
        }
        return List.of();
    }

    /**
     * 诊断模式匹配结果（供前端调试用）
     */
    public static Map<String, Object> diagnose(Page page) {
        Map<String, Object> info = new LinkedHashMap<>();
        for (SkuAttrExtractor extractor : EXTRACTORS) {
            String name = extractor.getClass().getSimpleName();
            boolean matched = extractor.matches(page);
            info.put(name, matched ? "✅ 匹配成功" : "❌ 未匹配");
        }
        return info;
    }

    /**
     * 找到匹配的提取器
     */
    public static SkuAttrExtractor findMatching(Page page) {
        for (SkuAttrExtractor extractor : EXTRACTORS) {
            if (extractor.matches(page)) {
                return extractor;
            }
        }
        return null;
    }
}
