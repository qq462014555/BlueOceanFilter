package com.blueocean.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 补单系统 - 店铺配置
 * 每个店铺包含：平台、店铺ID、店铺名
 */
public class ShopConfig {

    public static List<Map<String, String>> getShops() {
        return List.of(
                shop("淘宝", "tb35238016", "心情喵Koi品趣"),
                shop("淘宝", "tb9327549327", "KimBaL品选趣店"),
                shop("淘宝", "tb35238016", "KimBaL品选趣店"),
                shop("淘宝", "jgj1101110", "心情喵乐活店"),
                shop("抖音", "jgj1101110", "心情喵乐活店")
        );
    }

    /**
     * 获取完整显示文本：平台/shopId/店铺名
     */
    public static String getDisplayValue(Map<String, String> shop) {
        return shop.get("platform") + "/" + shop.get("shopName") + "/" + shop.get("shopId");
    }

    private static Map<String, String> shop(String platform, String shopId, String shopName) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("platform", platform);
        m.put("shopId", shopId);
        m.put("shopName", shopName);
        return m;
    }
}
