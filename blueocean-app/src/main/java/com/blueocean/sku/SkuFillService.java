package com.blueocean.sku;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.blueocean.sku.extractor.SkuAttrExtractorFactory;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * SKU 自动填充服务
 * 流程：连千牛页面获取SKU属性名 → 读本地CSV → AI分析生成single/multi结构 → 根据AI结果判断模式
 */
@Service
public class SkuFillService {

    private static final Logger log = LoggerFactory.getLogger(SkuFillService.class);
    private static final String RPA_BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";
    private static final int CDP_PORT = 9223;
    private static final String CDP_ENDPOINT = "http://127.0.0.1:" + CDP_PORT;
    private static final String DASHSCOPE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    @Value("${app.dashscope-api-key}")
    private String apiKey;

    /**
     * 测试：仅从千牛页面提取 SKU 属性（供前端测试按钮调用）
     */
    public Map<String, Object> testExtractSkuProps() {
        Map<String, Object> result = new LinkedHashMap<>();
        Playwright playwright = null;
        Browser browser = null;

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            Page page = findPublishTab(context);
            if (page == null) {
                result.put("success", false);
                result.put("error", "未找到商品发布页面，请先在千牛中打开商品发布");
                return result;
            }

            page.bringToFront();

            // 1. 先返回模式匹配诊断
            Map<String, Object> diagnose = SkuAttrExtractorFactory.diagnose(page);
            result.put("diagnose", diagnose);

            // 2. 再执行提取
            List<String> props = SkuAttrExtractorFactory.extract(page);
            if (props.isEmpty()) {
                result.put("success", false);
                result.put("error", "未提取到 SKU 属性");
                return result;
            }

            result.put("success", true);
            result.put("props", props);
            result.put("count", props.size());

        } catch (Exception e) {
            log.error("测试提取 SKU 属性失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            if (playwright != null) playwright.close();
        }
        return result;
    }

    /**
     * 主入口：连千牛获取SKU属性名 → 读CSV → AI填充
     * @param forceRefetch 是否强制重新调用 AI
     */
    public Map<String, Object> fillSku(String qianniuTitle, List<String> pageLevels, boolean forceRefetch) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 1. 连接千牛页面，获取 SKU 属性名称
            List<String> qianniuSkuProps = getQianniuSkuProperties();
            if (qianniuSkuProps.isEmpty()) {
                result.put("success", false);
                result.put("error", "未从千牛页面获取到 SKU 属性，请确保已打开商品发布页面");
                return result;
            }
            log.info("千牛 SKU 属性: {}", qianniuSkuProps);

            if(CollectionUtils.isNotEmpty(qianniuSkuProps)) {
                // 2. 查找商品目录
                String productDir = findProductDir(qianniuTitle);
                if (productDir == null) {
                    result.put("success", false);
                    result.put("error", "未找到匹配的商品目录: " + qianniuTitle);
                    return result;
                }
                log.info("找到商品目录: {}", productDir);

                // 3. 读取价格表 CSV
                List<Map<String, Object>> skuData = readPriceCsv(productDir);
                if (skuData.isEmpty()) {
                    result.put("success", false);
                    result.put("error", "价格表为空");
                    return result;
                }

                // 4. 优先读文件，不存在或强制重新抓取才调 AI
                Path jsonPath = Paths.get(productDir, "sku-ai-result.json");
                JSONObject aiResult;
                if (!forceRefetch && Files.exists(jsonPath)) {
                    String existingJson = Files.readString(jsonPath, StandardCharsets.UTF_8);
                    aiResult = JSON.parseObject(existingJson);
                    log.info("读取已有 AI 结果文件: {}", jsonPath);
                } else {
                    String aiJson = callAi(productDir, skuData, qianniuSkuProps, pageLevels);
                    aiResult = parseAiResponse(aiJson);
                    String savedPath = saveAiResult(productDir, aiResult);
                    log.info("AI 结果已保存: {}", savedPath);
                }

                String mode = determineMode(aiResult, skuData.size());
                log.info("本次使用模式: {}", mode);
                result.put("success", true);
                result.put("mode", mode);
                result.put("modeReason", getModeReason(aiResult));
                result.put("productDir", productDir);
                result.put("qianniuSkuProps", qianniuSkuProps);
                result.put("aiResult", aiResult);
                result.put("skuData", skuData);

            }
        } catch (Exception e) {
            log.error("SKU 填充失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 填写 SKU 选项值到千牛页面
     */
    public Map<String, Object> fillSkuToPage(String productDir) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 1. 读取 AI 结果
            Path jsonPath = Paths.get(productDir, "sku-ai-result.json");
            if (!Files.exists(jsonPath)) {
                result.put("success", false);
                result.put("error", "未找到 sku-ai-result.json: " + jsonPath);
                return result;
            }
            String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
            JSONObject aiResult = JSON.parseObject(json);

            // 2. 判断模式并打印日志
            String mode = determineMode(aiResult, 0);
            String reason = getModeReason(aiResult);
            log.info("=== 即将操作千牛页面，当前模式: {} | 原因: {} ===", mode, reason);

            // 3. 连接千牛页面
            Playwright playwright = Playwright.create();
            try {
                Browser browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
                BrowserContext context = browser.contexts().isEmpty()
                        ? browser.newContext()
                        : browser.contexts().get(0);

                Page page = findPublishTab(context);
                if (page == null) {
                    result.put("success", false);
                    result.put("error", "未找到商品发布页面，请先在千牛中打开商品发布");
                    return result;
                }
                page.bringToFront();

                // 3. 获取当前提取器并调用 fillSku
                com.blueocean.sku.extractor.SkuAttrExtractor extractor =
                        com.blueocean.sku.extractor.SkuAttrExtractorFactory.findMatching(page);
                if (extractor instanceof com.blueocean.sku.extractor.PresetAttrExtractor) {
                    com.blueocean.sku.extractor.PresetAttrExtractor presetExtractor =
                            (com.blueocean.sku.extractor.PresetAttrExtractor) extractor;

                    // 从 multi.levels 提取需要填写的属性
                    JSONObject multi = aiResult.getJSONObject("multi");
                    if (multi != null) {
                        com.alibaba.fastjson2.JSONArray levels = multi.getJSONArray("levels");
                        if (levels != null) {
                            java.util.Map<String, java.util.List<String>> attrOptions = new LinkedHashMap<>();
                            for (int i = 0; i < levels.size(); i++) {
                                JSONObject level = levels.getJSONObject(i);
                                String name = level.getString("name");
                                com.alibaba.fastjson2.JSONArray options = level.getJSONArray("options");
                                if (name != null && options != null) {
                                    java.util.List<String> opts = new ArrayList<>();
                                    for (int j = 0; j < options.size(); j++) {
                                        opts.add(options.getString(j));
                                    }
                                    attrOptions.put(name, opts);
                                }
                            }
                            presetExtractor.fillSku(page, attrOptions);
                            log.info("SKU 填写完成: {}", attrOptions.keySet());
                        }
                    }
                } else {
                    result.put("success", false);
                    result.put("error", "当前页面不是预置属性模式，无法填写 SKU");
                    return result;
                }

                result.put("success", true);
                result.put("message", "SKU 属性已填写到千牛页面");
            } finally {
                playwright.close();
            }
        } catch (Exception e) {
            log.error("SKU 填写到页面失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 连接千牛页面，提取 SKU 属性名称
     */
    private List<String> getQianniuSkuProperties() {
        Playwright playwright = null;
        Browser browser = null;

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            Page page = findPublishTab(context);
            if (page == null) {
                log.warn("未找到商品发布页面");
                return List.of();
            }

            page.bringToFront();
            return SkuAttrExtractorFactory.extract(page);

        } catch (Exception e) {
            log.warn("连接千牛页面获取 SKU 属性失败: {}", e.getMessage());
            return List.of();
        } finally {
            if (playwright != null) playwright.close();
        }
    }

    /**
     * 查找商品发布页面
     */
    private Page findPublishTab(BrowserContext context) {
        for (Page page : context.pages()) {
            try {
                String title = page.title();
                if (title != null && (title.contains("商品发布") || title.contains("publish"))) {
                    return page;
                }
            } catch (Exception ignored) {}
        }
        // 模糊匹配
        for (Page page : context.pages()) {
            try {
                String title = page.title();
                if (title != null && (title.contains("发布") || title.contains("商品"))) {
                    return page;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 查找商品目录
     */
    private String findProductDir(String qianniuTitle) {
        Path baseDir = Paths.get(RPA_BASE_DIR);
        if (!Files.exists(baseDir)) return null;

        String todayPrefix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        try (var taskDirs = Files.list(baseDir)) {
            for (Path taskDir : taskDirs.filter(Files::isDirectory).filter(d -> d.getFileName().toString().startsWith(todayPrefix)).toList()) {
                try (var catDirs = Files.list(taskDir)) {
                    for (Path catDir : catDirs.filter(Files::isDirectory).toList()) {
                        try (var productDirs = Files.list(catDir)) {
                            for (Path productDir : productDirs.filter(Files::isDirectory).toList()) {
                                String name = productDir.getFileName().toString();
                                if (name.equals(qianniuTitle) || name.startsWith(qianniuTitle)) {
                                    return productDir.toString();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("查找商品目录失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 读取价格表 CSV
     */
    private List<Map<String, Object>> readPriceCsv(String productDir) {
        List<Map<String, Object>> skuList = new ArrayList<>();
        try {
            Path csvPath = Paths.get(productDir, "价格表.csv");
            if (!Files.exists(csvPath)) return skuList;

            try (BufferedReader br = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
                String headerLine = br.readLine();
                if (headerLine == null) return skuList;
                String[] headers = headerLine.split(",");
                String line;
                while ((line = br.readLine()) != null && !line.trim().isEmpty()) {
                    String[] values = line.split(",");
                    Map<String, Object> sku = new LinkedHashMap<>();
                    for (int i = 0; i < Math.min(headers.length, values.length); i++) {
                        sku.put(headers[i].trim(), values[i].trim());
                    }
                    if (!sku.isEmpty()) skuList.add(sku);
                }
            }
        } catch (Exception e) {
            log.error("读取价格表失败: {}", e.getMessage());
        }
        return skuList;
    }

    /**
     * 调用 AI
     */
    private String callAi(String productDir, List<Map<String, Object>> skuData,
                          List<String> qianniuSkuProps, List<String> pageLevels) throws Exception {

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是电商商品运营专家，负责完善商品 SKU 规格信息。\n\n");

        prompt.append("【千牛页面 SKU 属性】\n");
        prompt.append("千牛平台提供的可选 SKU 属性名称: ").append(qianniuSkuProps).append("\n");
        prompt.append("（注意：千牛只提供属性名称，不提供具体选项值）\n\n");

        prompt.append("【本地 CSV SKU 数据】\n");
        prompt.append("本地价格表中有 ").append(skuData.size()).append(" 个 SKU\n");
        prompt.append("SKU 名称格式为: 属性1-属性2-属性3（如：白色-S、黑色-M）\n\n");

        prompt.append("【任务要求】\n");
        prompt.append("1. **选择属性**：从千牛提供的可选属性中，选出和本地 CSV 数据对应的属性（可以不全选，比如 CSV 只有2层结构，就选2个属性）\n");
        prompt.append("2. **排列顺序**：决定选中属性的层级顺序（排在前面的是外层）\n");
        prompt.append("3. **同义词匹配**：本地 CSV 的叫法和千牛属性名称可能不同，例如 CSV 可能叫「颜色」「规格」，而千牛叫「颜色分类」「款式」，请你根据语义智能匹配对应关系\n");
        prompt.append("4. **输出两种结构**：\n");
        prompt.append("   - **single 结构**：保持原有 ").append(skuData.size()).append(" 个 SKU，每个 SKU 名称作为整体\n");
        prompt.append("   - **multi 结构**：生成完整的笛卡尔积组合\n");
        prompt.append("5. 本地已有的 SKU，从 CSV 中取价格和库存填入\n");
        prompt.append("6. multi 结构中缺失的组合，price 和 stock 留空字符串 \"\"\n\n");

        prompt.append("【输出格式】\n");
        prompt.append("严格只返回 JSON，不要任何解释性文字：\n");
        prompt.append("{\n");
        prompt.append("  \"single\": [{\"name\":\"SKU名\",\"price\":\"价格\",\"stock\":\"库存\"}],\n");
        prompt.append("  \"multi\": {\n");
        prompt.append("    \"levels\": [{\"name\":\"属性名\",\"options\":[\"选项1\",\"选项2\"]}],\n");
        prompt.append("    \"skus\": [{\"specs\":[\"选项值1\",\"选项值2\"],\"price\":\"价格\",\"stock\":\"库存\"}]\n");
        prompt.append("  }\n");
        prompt.append("}\n\n");

        prompt.append("【本地 CSV 原始数据】\n");
        for (Map<String, Object> sku : skuData) {
            prompt.append(sku.toString()).append("\n");
        }

        String promptContent = prompt.toString();
        log.info("=== AI 提示词（User Prompt）===\n{}", promptContent);

        // 调用 API
        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", "你是专业的电商商品运营专家，负责完善商品 SKU 规格信息。");
        messages.add(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt.toString());
        messages.add(userMsg);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "qwen-plus");
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.1);

        String jsonBody = requestBody.toJSONString();
        log.info("调用 AI 生成 SKU 方案, 请求大小={}KB", jsonBody.length() / 1024);

        HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_URL))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("AI 调用失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        JSONObject resp = JSON.parseObject(response.body());
        JSONArray choices = resp.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("AI 返回为空");
        }

        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
        log.info("AI 返回内容: {}", content);
        return content;
    }

    /**
     * 获取模式判断原因（供日志打印）
     */
    private String getModeReason(JSONObject aiResult) {
        try {
            JSONObject multi = aiResult.getJSONObject("multi");
            if (multi != null) {
                com.alibaba.fastjson2.JSONArray levels = multi.getJSONArray("levels");
                com.alibaba.fastjson2.JSONArray skus = multi.getJSONArray("skus");
                if (levels != null && skus != null && !skus.isEmpty()) {
                    long fullCartesian = 1;
                    for (int i = 0; i < levels.size(); i++) {
                        fullCartesian *= levels.getJSONObject(i).getJSONArray("options").size();
                    }
                    int actualSkus = skus.size();
                    double ratio = (double) actualSkus / fullCartesian;
                    return String.format("multi.skus %d个 / 笛卡尔积 %d个 = %.0f%%", actualSkus, fullCartesian, ratio * 100);
                }
                return "multi.levels 或 multi.skus 为空";
            }
            return "AI 返回的 multi 字段为空";
        } catch (Exception e) {
            return "解析 multi 失败: " + e.getMessage();
        }
    }

    /**
     * 根据 AI 返回结果判断使用哪种模式
     * multi 结构中有有效数据时用 multi，否则用 single
     */
    private String determineMode(JSONObject aiResult, int existingCount) {
        try {
            JSONObject multi = aiResult.getJSONObject("multi");
            if (multi != null) {
                JSONArray levels = multi.getJSONArray("levels");
                JSONArray skus = multi.getJSONArray("skus");
                if (levels != null && skus != null && !skus.isEmpty()) {
                    // 计算完整笛卡尔积数量
                    long fullCartesian = 1;
                    for (int i = 0; i < levels.size(); i++) {
                        fullCartesian *= levels.getJSONObject(i).getJSONArray("options").size();
                    }
                    int actualSkus = skus.size();
                    double ratio = (double) actualSkus / fullCartesian;
                    if (ratio >= 0.8) {
                        log.info("=== 模式判断: multi | 原因: AI 返回的 multi.skus 数量 {}/笛卡尔积 {} = {}%, 超过80%阈值 ===", actualSkus, fullCartesian, String.format("%.0f", ratio * 100));
                        return "multi";
                    } else {
                        log.info("=== 模式判断: single | 原因: AI 返回的 multi.skus 数量 {}/笛卡尔积 {} = {}%, 未达80%阈值 ===", actualSkus, fullCartesian, String.format("%.0f", ratio * 100));
                    }
                } else {
                    log.info("=== 模式判断: single | 原因: multi.levels 或 multi.skus 为空 ===");
                }
            } else {
                log.info("=== 模式判断: single | 原因: AI 返回的 multi 字段为空 ===");
            }
        } catch (Exception e) {
            log.info("=== 模式判断: single | 原因: 解析 AI multi 结构失败 ===");
        }
        return "single";
    }

    /**
     * 保存 AI 返回结果到商品目录下
     */
    private String saveAiResult(String productDir, JSONObject aiResult) throws Exception {
        Path dir = Paths.get(productDir);
        if (!Files.exists(dir)) return null;

        String fileName = "sku-ai-result.json";
        Path filePath = dir.resolve(fileName);
        Files.writeString(filePath, aiResult.toJSONString(), StandardCharsets.UTF_8);
        return filePath.toString();
    }

    /**
     * 解析 AI 返回的 JSON
     */
    private JSONObject parseAiResponse(String aiJson) {
        String cleanJson = aiJson.trim();
        if (cleanJson.startsWith("```")) {
            int start = cleanJson.indexOf('{');
            int end = cleanJson.lastIndexOf('}') + 1;
            if (start >= 0 && end > start) {
                cleanJson = cleanJson.substring(start, end);
            }
        }
        return JSON.parseObject(cleanJson);
    }
}
