package com.blueocean.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * OpenRouter 平台 Image2 图生成
 */
@Service
public class OpenRouterImage2Provider implements Image2Provider {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterImage2Provider.class);
    private static final String API_URL = "https://openrouter.ai/api/v1/images";

    @Value("${app.openrouter-api-key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60)).build();

    @Override
    public List<String> generate(String prompt, int n, String size, List<String> refImages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "openai/gpt-image-2");
        body.put("prompt", prompt);
        body.put("n", n);
        body.put("size", size != null ? size : "1440x1440");

        if (refImages != null && !refImages.isEmpty()) {
            JSONArray refs = new JSONArray();
            for (String img : refImages) {
                JSONObject ref = new JSONObject();
                ref.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", img);
                ref.put("image_url", urlObj);
                refs.add(ref);
            }
            body.put("input_references", refs);
        }

        String jsonBody = body.toJSONString();
        String logBody = jsonBody.replaceAll("\"data:([^;]+;base64,)[^\"]{30}[^\"]*\"", "\"data:$1...(截断)");
        log.info("[OpenRouter-Image2] 请求: {}", logBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenRouter Image2 调用失败: HTTP " + response.statusCode() + " - " + response.body());
        }

        // 解析返回的 data[].url 或 data[].b64_json（全部取）
        JSONObject resp = JSON.parseObject(response.body());
        JSONArray dataArr = resp.getJSONArray("data");
        List<String> results = new java.util.ArrayList<>();
        if (dataArr != null) {
            for (int i = 0; i < dataArr.size(); i++) {
                JSONObject item = dataArr.getJSONObject(i);
                String url = item.getString("url");
                if (url != null && !url.isEmpty()) results.add(url);
                String b64 = item.getString("b64_json");
                if (b64 != null && !b64.isEmpty()) results.add("data:image/png;base64," + b64);
            }
        }
        if (!results.isEmpty()) {
            log.info("[OpenRouter-Image2] 返回 {} 张图", results.size());
            return results;
        }
        throw new RuntimeException("OpenRouter Image2 返回无数据");
    }
}
