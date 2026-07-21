package com.blueocean.service;

import com.blueocean.controller.AiImageController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 商品分析 + 提示词管理 业务逻辑
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private static final String PROMPT_FILE = "ai_image_prompts.json";

    private final OpenRouterService openRouterService;
    private final DeepSeekService deepSeekService;
    private final DashScopeClient dashScopeClient;

    public AnalysisService(OpenRouterService openRouterService, DeepSeekService deepSeekService, DashScopeClient dashScopeClient) {
        this.openRouterService = openRouterService;
        this.deepSeekService = deepSeekService;
        this.dashScopeClient = dashScopeClient;
    }

    /** AI 分析：有缓存直接返回，否则调 AI 并缓存 */
    public Map<String, Object> analyzeProduct(String productDir, String platform, boolean forceNew) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Path productDirPath = Paths.get(productDir);
            String title = productDirPath.getFileName().toString();
            Path cacheDir = Paths.get(productDir, "AI重绘图");
            Path cacheFile = cacheDir.resolve(".analysis_" + platform + ".json");

            // 缓存命中
            if (Files.exists(cacheFile) && !forceNew) {
                log.info("命中缓存: {}", cacheFile);
                String cachedJson = Files.readString(cacheFile);
                Map<String, Object> cached = com.alibaba.fastjson2.JSON.parseObject(cachedJson, Map.class);
                result.put("success", true); result.put("prompts", cached); result.put("title", title); result.put("cached", true);
                return result;
            }

            // 调 AI 分析
            log.info("调用 AI 分析...");
            setTaskStatus(productDir, "analysis", "running");
            String promptTemplate = readPromptFile(platform);
            String userPrompt = promptTemplate.replace("{{TITLE}}", title);
            List<String> productImages = new ArrayList<>();
            productImages.addAll(openRouterService.stitchDirToBase64(Paths.get(productDir, "主图"), "主图拼接参考.jpg", 1));
            productImages.addAll(openRouterService.stitchDirToBase64(Paths.get(productDir, "详情图"), "详情图拼接参考.jpg", 2));
            String aiResponse = dashScopeClient.chatWithImages(getAnalysisSystemPrompt(), userPrompt, productImages.isEmpty() ? null : productImages);

            // 解析 AI 返回
            String cleanJson = aiResponse.replaceAll("```[a-z]*\\n?", "").trim();
            Map<String, Object> prompts = new LinkedHashMap<>();
            int searchStart = 0;
            while (true) {
                int objStart = cleanJson.indexOf('{', searchStart);
                if (objStart < 0) break;
                int objEnd = cleanJson.indexOf('}', objStart);
                if (objEnd < 0) break;
                try {
                    Map<String, Object> parsed = com.alibaba.fastjson2.JSON.parseObject(cleanJson.substring(objStart, objEnd + 1), Map.class);
                    if (parsed != null) prompts.putAll(parsed);
                } catch (Exception ignored) {}
                searchStart = objEnd + 1;
            }
            result.put("success", true); result.put("prompts", prompts); result.put("title", title);

            // 缓存到文件
            try { Files.createDirectories(cacheDir); Files.writeString(cacheFile, com.alibaba.fastjson2.JSON.toJSONString(prompts)); } catch (IOException e) { log.warn("写入缓存失败: {}", e.getMessage()); }
            setTaskStatus(productDir, "analysis", "completed");
            return result;

        } catch (Exception e) {
            log.error("AI 分析失败", e);
            setTaskStatus(productDir, "analysis", "failed");
            result.put("success", true); result.put("prompts", getDefaultPrompts(platform)); result.put("note", "AI 分析失败，使用默认提示词");
            return result;
        }
    }

    /** 检查缓存文件是否存在 */
    public boolean isAnalysisDone(String productDir, String platform) {
        return Files.exists(Paths.get(productDir, "AI重绘图", ".analysis_" + platform + ".json"));
    }

    /** 智能优化提示词 */
    public String optimizePrompt(String originalPrompt, Map<String, String> analysis) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个电商主图 Prompt 优化助手。\n\n## 商品分析\n");
        for (Map.Entry<String, String> e : analysis.entrySet()) sb.append(e.getKey()).append("：").append(e.getValue()).append("\n");
        sb.append("\n## 原始 Prompt\n").append(originalPrompt);
        sb.append("\n\n## 优化要求\n1. 保持核心产品不变\n2. 优化光影、构图、背景、氛围\n3. 30~80个中文词\n4. 返回纯文本，不要JSON\n5. 突出标题中的卖点");
        return deepSeekService.chat("你是一个电商主图Prompt优化专家，只输出优化后的Prompt，不加解释。", sb.toString()).trim();
    }

    // ===== 任务状态 =====

    public String getTaskStatus(String productDir, String task) {
        ConcurrentHashMap<String, String> tasks = AiImageController.TASK_STATUS.get(productDir);
        return tasks != null ? tasks.getOrDefault(task, "none") : "none";
    }

    public void setTaskStatus(String productDir, String task, String status) {
        AiImageController.TASK_STATUS.computeIfAbsent(productDir, k -> new ConcurrentHashMap<>()).put(task, status);
        if ("completed".equals(status) || "failed".equals(status)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { Thread.sleep(172800000L); } catch (InterruptedException ignored) {}
                ConcurrentHashMap<String, String> t = AiImageController.TASK_STATUS.get(productDir);
                if (t != null) t.remove(task);
            });
        }
    }

    // ===== 提示词管理 =====

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, String>> loadPrompts() {
        try {
            Path promptsFile = Paths.get(System.getProperty("user.dir"), PROMPT_FILE);
            if (!Files.exists(promptsFile)) {
                var in = getClass().getClassLoader().getResourceAsStream(PROMPT_FILE);
                if (in != null) { Files.write(promptsFile, in.readAllBytes()); }
            }
            if (Files.exists(promptsFile)) return com.alibaba.fastjson2.JSON.parseObject(Files.readString(promptsFile), Map.class);
        } catch (Exception e) { log.warn("加载提示词失败: {}", e.getMessage()); }
        Map<String, Map<String, String>> def = new LinkedHashMap<>();
        for (String p : List.of("taobao", "douyin", "shopee")) {
            def.put(p, getDefaultPrompts(p));
        }
        return def;
    }

    public void savePrompts(Map<String, Map<String, String>> platforms) {
        try { Files.writeString(Paths.get(System.getProperty("user.dir"), PROMPT_FILE), com.alibaba.fastjson2.JSON.toJSONString(platforms)); } catch (IOException e) { log.warn("保存提示词失败: {}", e.getMessage()); }
    }

    public Map<String, String> getDefaultPrompts(String platform) {
        Map<String, String> prompts = new LinkedHashMap<>();
        String baseStyle = "taobao".equals(platform) ? "白底, 高光质感, 产品居中" : "douyin".equals(platform) ? "色彩鲜艳, 场景化, 吸引眼球" : "简洁清晰, 干净背景";
        for (int i = 1; i <= 5; i++) prompts.put("图" + i, "Product on clean background, professional lighting, " + baseStyle);
        return prompts;
    }

    private String readPromptFile(String platform) {
        try {
            String fileName = "prompts/photo/taobao.txt";
            if (platform != null) {
                String pf = switch (platform) { case "douyin" -> "prompts/photo/douyin.txt"; case "shopee" -> "prompts/photo/shopee.txt"; default -> "prompts/photo/taobao.txt"; };
                if (getClass().getClassLoader().getResource(pf) != null) fileName = pf;
            }
            var in = getClass().getClassLoader().getResourceAsStream(fileName);
            if (in != null) return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { log.warn("读取提示词文件失败: {}", e.getMessage()); }
        return "";
    }

    private String getAnalysisSystemPrompt() {
        return """
            # Role：电商主图重绘提示词专家 - 淘宝
            ## Task
            1. **分析商品**：根据提供的商品标题、原主图、详情图，分析产品的品类、材质、卖点、目标人群、使用场景、视觉特征
            ## 核心原则（必须遵守）
            这是一个 **（图生图）重绘任务**：
            - 原图已经提供了产品的外形、结构、颜色
            - 生成的 prompt 要描述的是：**基于原图，改成什么风格**
            - **标题描述的场景和卖点是最重要的参考依据**，必须优先体现在提示词中
            - 不要改变产品的核心外观（形状、结构、颜色）
            - 只改变背景、光线、构图角度、氛围、场景
            """;
    }
}
