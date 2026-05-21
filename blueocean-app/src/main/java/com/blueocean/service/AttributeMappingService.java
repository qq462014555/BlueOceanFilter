package com.blueocean.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品属性 AI 映射服务
 * 将 1688 采集到的商品属性智能映射到商家后台发布页面的字段名称
 */
@Service
public class AttributeMappingService {

    private static final Logger log = LoggerFactory.getLogger(AttributeMappingService.class);
    private static final String DASHSCOPE_CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private final String apiKey;
    private final HttpClient httpClient;

    public AttributeMappingService(DashScopeClient dashScopeClient) {
        try {
            var field = DashScopeClient.class.getDeclaredField("apiKey");
            field.setAccessible(true);
            this.apiKey = (String) field.get(dashScopeClient);
        } catch (Exception e) {
            throw new RuntimeException("无法获取 DashScope API Key", e);
        }
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * 将 1688 采集属性映射到商家后台字段
     *
     * @param scrapedAttrs  1688 采集到的属性 {"品牌":"2FAST4YOU", "材质":"铁", ...}
     * @param targetFields  商家后台页面需要填写的字段列表 ["安装位置", "适用车型", "品牌", ...]
     * @param category      类目名称，帮助 AI 理解上下文
     * @return 映射结果 {"1688属性名": "商家后台字段名", ...}
     */
    public Map<String, String> mapAttributes(
            Map<String, String> scrapedAttrs,
            List<String> targetFields,
            String category) throws IOException, InterruptedException {

        if (scrapedAttrs == null || scrapedAttrs.isEmpty() || targetFields == null || targetFields.isEmpty()) {
            log.warn("[属性映射] 输入数据为空");
            return Map.of();
        }

        String sysPrompt = """
                你是一个电商属性字段映射专家。
                你的任务是将 1688 商品详情页采集到的属性，映射到商家后台发布页面的字段名称。

                规则：
                1. 只输出 JSON，不输出任何其他文字
                2. 格式为 {"1688采集属性名": "商家后台字段名"}
                3. 只输出能明确匹配的字段对
                4. 语义相近即可匹配，如"材质"→"车筐材质"、"表面处理"→"表面工艺"
                5. 如果商家必填字段在采集属性中有对应值，请尽量匹配
                6. 不确定的不要匹配
                """;

        String userMessage = """
                类目: %s

                1688 采集到的商品属性（key=属性名, value=属性值）：
                %s

                商家后台需要填写的字段列表：
                %s

                请输出映射 JSON（只输出JSON，格式为 {"采集属性名":"商家字段名"}）：
                """.formatted(
                category != null ? category : "未知类目",
                JSON.toJSONString(scrapedAttrs),
                targetFields
        );

        log.info("[属性映射] 采集属性={}个, 商家字段={}个", scrapedAttrs.size(), targetFields.size());

        return callDashScope(sysPrompt, userMessage);
    }

    private Map<String, String> callDashScope(String systemPrompt, String userMessage)
            throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", DashScopeClient.MODEL_QWEN3_6_PLUS);

        var messages = new com.alibaba.fastjson2.JSONArray();
        var sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        var userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        body.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_CHAT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JSONObject respJson = JSON.parseObject(response.body());

        if (response.statusCode() != 200) {
            String errMsg = respJson != null ? respJson.getString("message") : "未知错误";
            log.error("[属性映射] API 错误: {}", errMsg);
            throw new IOException("API 调用失败: " + errMsg);
        }

        String content = respJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();

        // 去除可能的 markdown 代码块
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            content = content.substring(firstNewline + 1);
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
        }
        content = content.trim();

        log.info("[属性映射] AI 回复: {}", content);

        JSONObject mapping = JSON.parseObject(content);
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : mapping.keySet()) {
            result.put(key, mapping.getString(key));
        }
        log.info("[属性映射] 匹配成功 {} 个字段: {}", result.size(), result);
        return result;
    }
}
