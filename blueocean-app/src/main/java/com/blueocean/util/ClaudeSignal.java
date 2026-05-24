package com.blueocean.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 向 Claude 发送浏览器操作信号的工具类
 * 信号文件写入项目根目录 signal/ 目录，Claude 轮询读取后自动执行
 */
public class ClaudeSignal {

    private static final String SIGNAL_DIR;

    static {
        String projectRoot = findProjectRoot();
        SIGNAL_DIR = Paths.get(projectRoot, "signal").toString();
        try {
            Files.createDirectories(Paths.get(SIGNAL_DIR));
        } catch (IOException e) {
            throw new RuntimeException("无法创建信号目录: " + SIGNAL_DIR);
        }
    }

    /**
     * 发送"填写字段"信号 —— Claude 连浏览器逐字段填值
     */
    public static void fillFields(String jsonPath, String source, String productId) {
        writeSignal("fill_fields", source, productId, null, jsonPath, "请填写 " + productId + " 的商品属性字段");
    }

    /**
     * 发送"截图"信号 —— Claude 连浏览器截图
     */
    public static void screenshot(String source, String productId, String details) {
        writeSignal("screenshot", source, productId, null, null, details);
    }

    /**
     * 发送"验证"信号 —— Claude 连浏览器检查填写结果
     */
    public static void verifyFill(String jsonPath, String source, String productId) {
        writeSignal("verify_fill", source, productId, null, jsonPath, "验证 " + productId + " 的属性字段是否填写正确");
    }

    /**
     * 发送自定义信号
     */
    public static void custom(String action, String source, String productId, String jsonPath, String details) {
        writeSignal(action, source, productId, null, jsonPath, details);
    }

    /**
     * 清理旧的信号文件，确保每次写入前只有一个新文件
     */
    private static void cleanOldSignals() {
        try {
            try (var walk = Files.walk(Paths.get(SIGNAL_DIR))) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                    });
            }
        } catch (IOException e) {
            // ignore
        }
    }

    private static void writeSignal(String action, String source, String productId, String targetUrl,
                                     String jsonPath, String details) {
        try {
            // 写新信号前，清理旧的信号文件（确保只有一个）
            cleanOldSignals();

            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("source", source);
            signal.put("action", action);
            signal.put("details", details);
            if (productId != null) signal.put("productId", productId);
            if (targetUrl != null) signal.put("targetUrl", targetUrl);
            if (jsonPath != null) signal.put("jsonPath", jsonPath);

            String json = toJson(signal);
            String fileName = action + "_" + productId + "_" + UUID.randomUUID().toString().substring(0, 8) + ".json";
            Path filePath = Paths.get(SIGNAL_DIR, fileName);
            Files.writeString(filePath, json);
            System.out.println("[ClaudeSignal] 已发送信号: " + filePath);
        } catch (IOException e) {
            throw new RuntimeException("写入信号文件失败: " + e.getMessage(), e);
        }
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",\n");
            sb.append("  \"").append(entry.getKey()).append("\": ");
            Object v = entry.getValue();
            if (v instanceof String) {
                sb.append("\"").append(escapeJson((String) v)).append("\"");
            } else {
                sb.append(v);
            }
            first = false;
        }
        sb.append("\n}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String findProjectRoot() {
        Path current = Paths.get(System.getProperty("user.dir"));
        for (Path p = current; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".claude")) || Files.exists(p.resolve("pom.xml"))) {
                return p.toString();
            }
            if (p.getParent() == null) break;
        }
        return current.toString();
    }
}
