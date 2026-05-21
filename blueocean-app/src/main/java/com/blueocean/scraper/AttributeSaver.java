package com.blueocean.scraper;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 商品属性本地存储工具
 * 将采集到的属性保存为 JSON 文件，供后续 AI 映射填写商家后台使用
 */
public class AttributeSaver {

    private static final Logger log = LoggerFactory.getLogger(AttributeSaver.class);

    /**
     * 将采集到的商品属性保存为 JSON 文件到商品目录
     */
    public static void save(String productDir, Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            log.info("无商品属性需要保存");
            return;
        }

        Path path = Paths.get(productDir, "商品属性.json");
        try {
            String json = JSON.toJSONString(attributes);
            Files.writeString(path, json, java.nio.charset.StandardCharsets.UTF_8);
            log.info("商品属性已保存: {} ({} 个字段)", path, attributes.size());
        } catch (IOException e) {
            log.error("保存商品属性失败: {}", e.getMessage());
        }
    }

    /**
     * 从商品目录加载已保存的商品属性
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> load(String productDir) {
        Path path = Paths.get(productDir, "商品属性.json");
        try {
            String json = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            return JSON.parseObject(json, Map.class);
        } catch (IOException e) {
            log.warn("读取商品属性文件失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取某个类目目录下所有商品的属性文件列表
     */
    public static List<File> listAttributeFiles(String categoryDir) {
        File dir = new File(categoryDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log.warn("目录不存在: {}", categoryDir);
            return List.of();
        }
        return List.of(dir.listFiles((d, name) -> name.endsWith(".json")));
    }
}
