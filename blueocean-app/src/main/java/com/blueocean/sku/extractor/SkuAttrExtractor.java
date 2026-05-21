package com.blueocean.sku.extractor;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * 千牛 SKU 属性提取策略接口
 */
public interface SkuAttrExtractor {

    /**
     * 从千牛页面提取 SKU 属性
     * @param page 已连接到千牛商品发布页面的 Page 对象
     * @return SKU 属性名称列表
     */
    List<String> extract(Page page);

    /**
     * 判断当前页面是否匹配此策略
     */
    boolean matches(Page page);
}
