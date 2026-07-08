package com.blueocean.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * DeepSeek 官方 API 对接 — 支持多模态（图片+文字）和纯文本
 */
@Service
public class DeepSeekService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);
    private static final String DEEPSEEK_API = "https://api.deepseek.com/v1/chat/completions";

    @Value("${app.deepseek-api-key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    /**
     * 多模态：文字 + 多张图片
     */
    public String chatWithImages(String systemPrompt, String userText, List<String> imageBase64List) throws IOException, InterruptedException {
        log.info("[DeepSeek] 多模态, 文字={}字, 图片={}张", userText.length(), imageBase64List != null ? imageBase64List.size() : 0);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", systemPrompt));

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        JSONArray content = new JSONArray();
        content.add(JSONObject.of("type", "text", "text", userText));
        if (imageBase64List != null) {
            for (String img : imageBase64List) {
                content.add(JSONObject.of("type", "image_url", "image_url", JSONObject.of("url", img)));
            }
        }
        userMsg.put("content", content);
        messages.add(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", "deepseek-vl");
        body.put("messages", messages);

        return execute(body.toJSONString());
    }

    /**
     * 纯文本对话
     */
    public String chat(String systemPrompt, String userMessage) throws IOException, InterruptedException {
        log.info("[DeepSeek] 文本, 文字={}字", userMessage.length());

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", systemPrompt));
        messages.add(JSONObject.of("role", "user", "content", userMessage));

        JSONObject body = new JSONObject();
        body.put("model", "deepseek-chat");
        body.put("messages", messages);

        return execute(body.toJSONString());
    }

    private String execute(String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEEPSEEK_API))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("[DeepSeek] API 错误: HTTP {} - {}", response.statusCode(), response.body());
            throw new IOException("DeepSeek 调用失败: HTTP " + response.statusCode());
        }

        String content = JSON.parseObject(response.body())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        log.info("[DeepSeek] 回复={}字", content.length());
        return content.trim();
    }
}
