package com.blueocean.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Service
public class SkuRenameService {

    private static final Logger log = LoggerFactory.getLogger(SkuRenameService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public void renameSku(String productDir, String oldName, String newName) throws IOException {
        Path prodDir = Paths.get(productDir);

        // 1. 商品数据.json — 用 specName 精确匹配，拿到 actualOldName
        String actualOldName = null;
        Path jsonPath = prodDir.resolve("商品数据.json");
        if (Files.exists(jsonPath)) {
            String jsonContent = Files.readString(jsonPath);
            Map<String, Object> jsonData = mapper.readValue(jsonContent, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skus = (List<Map<String, Object>>) jsonData.get("skus");
            if (skus != null) {
                for (Map<String, Object> sku : skus) {
                    String specName = String.valueOf(sku.get("specName"));
                    if (oldName.equals(specName) || specName.contains(oldName)) {
                        sku.put("specName", newName);
                        actualOldName = specName;
                        break;
                    }
                }
            }
            Files.writeString(jsonPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonData));
            log.info("已更新 商品数据.json 中的 SKU 名称");
        }
        if (actualOldName == null) {
            log.warn("未找到匹配的 specName，跳过其他文件");
            return;
        }
        log.info("实际 oldName=[{}] newName=[{}]", actualOldName, newName);

        // 2. 价格表.csv — 全文替换
        replaceInFile(prodDir.resolve("价格表.csv"), actualOldName, oldName, newName);

        // 3. 商品属性.json — 全文替换
        replaceInFile(prodDir.resolve("商品属性.json"), actualOldName, oldName, newName);

        // 4. 包装信息.json — 取每个条目第一个字段的值来匹配
        replaceInPackInfo(prodDir.resolve("包装信息.json"), actualOldName, oldName, newName);
    }

    private void replaceInFile(Path path, String actualOldName, String oldName, String newName) throws IOException {
        if (!Files.exists(path)) return;
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String[] candidates = { actualOldName, oldName };
        boolean matched = false;
        for (String candidate : candidates) {
            if (candidate != null && !candidate.equals(newName) && content.contains(candidate)) {
                content = content.replace(candidate, newName);
                log.info("{} 中匹配到 oldName=[{}]", path.getFileName(), candidate);
                matched = true;
                break;
            }
        }
        if (matched) {
            Files.writeString(path, content);
            log.info("已更新 {}", path.getFileName());
        } else {
            log.warn("{} 未匹配到 oldName", path.getFileName());
        }
    }

    private void replaceInPackInfo(Path path, String actualOldName, String oldName, String newName) throws IOException {
        if (!Files.exists(path)) return;
        String jsonContent = Files.readString(path);
        List<Map<String, Object>> packs = mapper.readValue(jsonContent, List.class);
        boolean changed = false;
        String[] candidates = { actualOldName, oldName };
        for (Map<String, Object> pack : packs) {
            // 取第一个字段的值
            String firstValue = pack.values().iterator().hasNext() ? String.valueOf(pack.values().iterator().next()) : null;
            if (firstValue != null) {
                String[] allCandidates = { actualOldName, oldName, firstValue };
                for (String candidate : allCandidates) {
                    if (candidate != null && !candidate.equals(newName) && firstValue.contains(candidate)) {
                        // 用 firstValue 去匹配所有字段的值
                        for (Map.Entry<String, Object> entry : pack.entrySet()) {
                            String val = String.valueOf(entry.getValue());
                            if (val.contains(candidate)) {
                                entry.setValue(val.replace(candidate, newName));
                            }
                        }
                        changed = true;
                        log.info("包装信息.json 中匹配到 oldName=[{}]", candidate);
                        break;
                    }
                }
            }
        }
        if (changed) {
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(packs));
            log.info("已更新 包装信息.json");
        } else {
            log.warn("包装信息.json 未匹配到 oldName");
        }
    }
}
