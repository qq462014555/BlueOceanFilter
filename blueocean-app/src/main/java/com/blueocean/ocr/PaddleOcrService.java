package com.blueocean.ocr;

import com.blueocean.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * PaddleOCR 图片文字识别服务
 * 调用本地 PaddleOCR HTTP 服务 (默认端口 8866)
 */
@Service
public class PaddleOcrService {

    private static final Logger log = LoggerFactory.getLogger(PaddleOcrService.class);

    private final String endpoint;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    public PaddleOcrService(AppProperties props) {
        this.endpoint = props.getPaddleocrEndpoint();
        log.info("PaddleOCR 服务地址: {}", endpoint);
    }

    /**
     * 识别图片中的文字，返回纯文本（按行拼接）
     * @param imageUrl 图片URL
     * @return 识别出的文字
     */
    public String recognizeText(String imageUrl) {
        return recognizeText(imageUrl, true);
    }

    /**
     * 识别图片中的文字
     * @param imageUrl 图片URL
     * @return 识别结果列表
     */
    public List<OcrResultLine> recognizeLines(String imageUrl) {
        try {
            byte[] imageBytes = downloadImage(imageUrl);
            if (imageBytes == null) {
                log.warn("图片下载失败: {}", imageUrl);
                return Collections.emptyList();
            }
            return doRecognize(imageBytes);
        } catch (Exception e) {
            log.error("OCR识别失败: {} - {}", imageUrl, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 识别图片中的文字，返回纯文本
     */
    public String recognizeText(String imageUrl, boolean joinLines) {
        List<OcrResultLine> lines = recognizeLines(imageUrl);
        if (lines.isEmpty()) return "";
        if (!joinLines) return "";
        return String.join("\n", lines.stream().map(OcrResultLine::getText).toList());
    }

    private List<OcrResultLine> doRecognize(byte[] imageBytes) throws IOException, InterruptedException {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        String jsonBody = String.format(
                "{\"images\":[\"%s\"]}",
                base64Image
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/predict/ocr_system"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("OCR服务返回异常状态: {}", response.statusCode());
            return Collections.emptyList();
        }

        return parseOcrResponse(response.body());
    }

    @SuppressWarnings("unchecked")
    private List<OcrResultLine> parseOcrResponse(String jsonBody) {
        List<OcrResultLine> results = new ArrayList<>();
        try {
            // PaddleOCR 返回格式:
            // {"results": [[{"box": [...], "text": "...", "confidence": 0.99}, ...], ...]}
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonBody);

            com.fasterxml.jackson.databind.JsonNode resultsNode = root.path("results");
            if (resultsNode.isArray() && resultsNode.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode firstResult = resultsNode.get(0);
                if (firstResult.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode item : firstResult) {
                        String text = item.path("text").asText();
                        double confidence = item.path("confidence").asDouble(0);
                        if (text != null && !text.isEmpty()) {
                            results.add(new OcrResultLine(text, confidence));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析OCR响应失败: {}", e.getMessage());
        }
        return results;
    }

    private byte[] downloadImage(String url) {
        try {
            java.net.URI uri = URI.create(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://detail.1688.com/");
            try (java.io.InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("下载图片失败: {} - {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * OCR 单行识别结果
     */
    public static class OcrResultLine {
        private final String text;
        private final double confidence;

        public OcrResultLine(String text, double confidence) {
            this.text = text;
            this.confidence = confidence;
        }

        public String getText() { return text; }
        public double getConfidence() { return confidence; }

        @Override
        public String toString() {
            return String.format("[%.2f] %s", confidence, text);
        }
    }
}
