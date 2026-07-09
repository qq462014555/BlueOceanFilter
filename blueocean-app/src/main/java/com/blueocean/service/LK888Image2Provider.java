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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * LK888 平台 Image2 图生成（异步任务）
 */
@Service
public class LK888Image2Provider implements Image2Provider {

    private static final Logger log = LoggerFactory.getLogger(LK888Image2Provider.class);
    private static final String CREATE_URL = "https://api.lk888.ai/v1/images/generations";
    private static final String STATUS_URL = "https://api.lk888.ai/v1/media/status?page=1&page_size=50";

    private static String brief(String s) {
        if (s == null) return "null";
        // 只截取 base64 数据的前20字
        return s.replaceAll("data:([^;]+;base64,)[^\"]{20}[^\"]*", "data:$1...(截断)");
    }

    @Value("${app.lk888-api-key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(300)).build();

    @Override
    public List<String> generate(String prompt, int n, String size, List<String> refImages) throws Exception {
        // LK888 每请求只出一张图，n>1 时并发请求
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try { return generateOne(prompt, size, refImages); }
                catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> f : futures) {
            results.add(f.get());
        }
        log.info("[LK888] ✅ 并发完成 {} 张图", results.size());
        return results;
    }

    private String generateOne(String prompt, String size, List<String> refImages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", "gpt-image-2");

        JSONObject params = new JSONObject();
        params.put("quality", "auto");
        params.put("resolution", "1K");
        params.put("response_format", "url");
        params.put("size", "1024x1024");

        if (refImages != null && !refImages.isEmpty()) {
            JSONArray images = new JSONArray();
            for (String img : refImages) {
                if (img != null) images.add(img);
            }
            if (!images.isEmpty()) {
                params.put("images", images);
            }
        }
        body.put("params", params);
        body.put("prompt", prompt);

        String jsonBody = body.toJSONString();
        String logBody = jsonBody.replaceAll("\"data:([^;]+;base64,)[^\"]{30}[^\"]*\"", "\"data:$1...(截断)");
        log.info("[LK888] curl:\ncurl -X POST {} \\\n  -H \"Authorization: Bearer {}\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{}'",
            CREATE_URL, apiKey, logBody);

        HttpRequest createReq = HttpRequest.newBuilder()
                .uri(URI.create(CREATE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> createResp;
        try {
            createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new RuntimeException("LK888 连接失败: " + e.getMessage(), e);
        }
        String respBody = createResp.body();
        log.info("[LK888] 回参: HTTP {} - {}", createResp.statusCode(), brief(respBody));
        if (createResp.statusCode() != 200) {
            throw new RuntimeException("LK888 调用失败: HTTP " + createResp.statusCode() + " - " + brief(respBody));
        }

        JSONObject cr = JSON.parseObject(respBody);
        JSONArray dataArr = cr.getJSONArray("data");
        if (dataArr != null && !dataArr.isEmpty()) {
            JSONObject item = dataArr.getJSONObject(0);
            String b64 = item.getString("b64_json");
            if (b64 != null && !b64.isEmpty()) return "data:image/png;base64," + b64;
            String url = item.getString("url");
            if (url != null && !url.isEmpty()) return url;
        }
        throw new RuntimeException("LK888 返回格式异常: " + brief(respBody));
    }
}
