package com.blueocean.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * OpenRouter 中转站 API 对接 — AI 主图重绘
 */
@Service
public class  OpenRouterService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterService.class);

    private static final String OPENROUTER_API = "https://openrouter.ai/api/v1/chat/completions";
    private static final String OPENROUTER_RESPONSES_API = "https://openrouter.ai/api/v1/responses";
    private static final String OPENROUTER_IMAGES_API = "https://openrouter.ai/api/v1/images";

    // 图片保存根目录
    private static final String IMAGE_OUTPUT_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理\\AI重绘图";

    @Value("${app.openrouter-api-key:}")
    private String apiKey;

    // 代理配置（从 application.yml 读取，app.proxy-host / app.proxy-port）
    @Value("${app.proxy-host:}")
    private String proxyHost;

    @Value("${app.proxy-port:0}")
    private int proxyPort;

    private HttpClient directClient;
    private HttpClient proxiedClient;

    @jakarta.annotation.PostConstruct
    public void initHttpClient() {
        directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

        if (proxyHost != null && !proxyHost.isEmpty() && proxyPort > 0) {
            proxiedClient = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                    .connectTimeout(Duration.ofSeconds(60))
                    .build();
        } else {
            proxiedClient = null;
        }
    }

    /**
     * 每次调用时检测代理是否可用，返回对应的 HttpClient
     */
    private HttpClient getActiveHttpClient() {
        if (proxiedClient == null) {
            return directClient;
        }
        // 检测代理端口是否有效
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(proxyHost, proxyPort), 1000);
            log.info("🌐 代理可用 {}:{}", proxyHost, proxyPort);
            return proxiedClient;
        } catch (Exception e) {
            log.warn("🌐 代理不可用 ({}:{})，已切直连", proxyHost, proxyPort);
            return directClient;
        }
    }

    /**
     * 调用 OpenRouter 生成图片
     *
     * @param model    模型名称，如 black-forest-labs/FLUX.1-schnell
     * @param prompt   提示词
     * @param productDir 商品目录（用于保存图片）
     * @param platform 平台名称（taobao/douyin/shopee）
     * @param imageIndex 第几张图 (1-5)
     * @return 生成图片的本地路径
     */
    public String generateImage(String model, String prompt, Map<String, String> allPrompts, String productDir, String platform, int imageIndex, int n) {
        return generateImageWithRefs(model, prompt, allPrompts, productDir, platform, imageIndex, n, null);
    }

    /**
     * 生成图片（支持自定义参考图）
     */
    public String generateImageWithRefs(String model, String prompt, Map<String, String> allPrompts, String productDir, String platform, int imageIndex, int n, List<String> refImages) {
        try {
            String fullPrompt = buildFullPrompt(prompt, allPrompts, imageIndex, n);
            boolean isImage2 = model != null && model.toLowerCase().contains("gpt-image");
            String jsonBody;
            String endpoint;

            if (isImage2) {
                jsonBody = buildImage2RequestBody(model, fullPrompt, productDir, n);
                if (refImages != null && !refImages.isEmpty()) {
                    jsonBody = addRefsToImage2Body(jsonBody, refImages);
                }
                endpoint = OPENROUTER_IMAGES_API;
            } else {
                jsonBody = buildImageRequestBody(model, fullPrompt, productDir, n);
                if (refImages != null && !refImages.isEmpty()) {
                    jsonBody = addRefsToResponsesBody(jsonBody, refImages);
                }
                endpoint = OPENROUTER_RESPONSES_API;
            }

            log.info("调用 OpenRouter 生成图片: model={}, endpoint={}, n={}", model, endpoint, n);

            List<String> imageUrls = executeImageRequestWithRetry(jsonBody, endpoint);
            if (imageUrls.isEmpty()) {
                throw new RuntimeException("生成图片失败，已重试10次");
            }

            return downloadAllImages(imageUrls, productDir, platform, imageIndex);

        } catch (Exception e) {
            log.error("生成图片失败", e);
            throw new RuntimeException("生成图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建完整提示词：第几张图 + 全套5张图需求 + 本次要求
     */
    private String buildFullPrompt(String prompt, Map<String, String> allPrompts, int imageIndex, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("请一次性生成").append(n).append("张电商主图，全部为正方形1440×1440像素。强制文字规范：画面中所有文字必须是标准规范的简体中文汉字，每个字的笔画结构准确无误，禁止生成AI捏造的伪汉字，禁止笔画残缺、错乱、拼接错误。" +
                "物理规则强制约束：所有物体严格遵循现实物理重力，物体之间必须存在真实接触贴合，接触位置生成自然压痕、接触阴影、明暗交界；禁止物品悬浮悬空、穿模穿透、无支撑漂浮；物体受力结构合理，重物完全被支架承托，无悬空脱离结构的视觉错误；光影统一单一光源，所有物体根据光源生成对应投影，物体贴合墙面/支架处有厚重贴合阴影，杜绝无阴影浮空bug；禁止AI生成违背物理常识的错位、漂浮、断层物体。\n\n");
        sb.append("当前生成第").append(imageIndex).append("张图。\n\n");
        sb.append("【全套5张图需求】\n");
        if (allPrompts != null) {
            for (int i = 1; i <= 5; i++) {
                String key = "图" + i;
                String val = allPrompts.get(key);
                if (val != null && !val.trim().isEmpty()) {
                    sb.append("图").append(i).append("：").append(val).append("\n");
                }
            }
        }
        sb.append("\n【本次生成要求】\n").append(prompt);
        return sb.toString();
    }

    /**
     * 拼接目录下的图片，base64 编码后加入 content 数组
     */
    private void addStitchedImage(JSONArray contentArr, Path dir, String saveFileName) {
        addStitchedImagesSplit(contentArr, dir, saveFileName, 1);
    }

    /**
     * 构建 Responses API 请求体（文本 + 可选原图参考）
     */
    private String buildImageRequestBody(String model, String fullPrompt, String productDir, int n) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);

        // 构建 input 数组（不同于 chat completions 的 messages）
        JSONArray inputArr = new JSONArray();

        // 构建 user message：content 数组（input_text + 可选 input_image）
        JSONArray contentArr = new JSONArray();

        // 文本部分
        JSONObject textPart = new JSONObject();
        textPart.put("type", "input_text");
        textPart.put("text", fullPrompt);
        contentArr.add(textPart);

        // 白底图参考（如有）
        addWhiteBgReferences(contentArr, productDir);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("type", "message");
        userMsg.put("content", contentArr);
        inputArr.add(userMsg);

        requestBody.put("input", inputArr);
        requestBody.put("max_output_tokens", 2048);
        // 生成多张图
        requestBody.put("n", n);

        return requestBody.toJSONString();
    }

    /**
     * 构建 Image2 API 请求体（openai/gpt-image-2 专用）
     */
    private String buildImage2RequestBody(String model, String fullPrompt, String productDir, int n) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("prompt", fullPrompt);
        requestBody.put("n", n);
        requestBody.put("size", "1440x1440");

        // 白底图参考（如有）
        JSONArray refs = addWhiteBgImage2References(productDir);
        if (!refs.isEmpty()) {
            requestBody.put("input_references", refs);
        }
        return requestBody.toJSONString();
    }

    /**
     * 白底图参考（Image2 格式）
     */
    private JSONArray addWhiteBgImage2References(String productDir) {
        JSONArray refs = new JSONArray();
        if (productDir == null) return refs;
        Path whiteBgDir = Paths.get(productDir, "白底图");
        if (!Files.exists(whiteBgDir)) return refs;
        log.info("白底图目录: {}", whiteBgDir);
        try {
            byte[] stitched = stitchImages(whiteBgDir, 1024, 0.9f);
            if (stitched != null) {
                saveSentImage(stitched, "白底图_拼接参考_openrouter.jpg");
                String base64 = Base64.getEncoder().encodeToString(stitched);
                JSONObject imgPart = new JSONObject();
                imgPart.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:image/jpeg;base64," + base64);
                imgPart.put("image_url", urlObj);
                refs.add(imgPart);
                log.info("已添加白底图参考(Image2): {}KB, 拼接图来自: {}", stitched.length / 1024, whiteBgDir);
            }
        } catch (Exception e) {
            log.warn("白底图拼接失败: {}", e.getMessage());
        }
        return refs;
    }

    /**
     * 白底图参考（Responses API 格式，input_image）
     */
    private void addWhiteBgReferences(JSONArray contentArr, String productDir) {
        if (productDir == null) return;
        Path whiteBgDir = Paths.get(productDir, "白底图");
        if (!Files.exists(whiteBgDir)) return;
        log.info("白底图目录: {}", whiteBgDir);
        try {
            byte[] stitched = stitchImages(whiteBgDir, 1024, 0.9f);
            if (stitched != null) {
                saveSentImage(stitched, "白底图_拼接参考_responses.jpg");
                String base64 = Base64.getEncoder().encodeToString(stitched);
                JSONObject imgPart = new JSONObject();
                imgPart.put("type", "input_image");
                imgPart.put("image_url", "data:image/jpeg;base64," + base64);
                contentArr.add(imgPart);
                log.info("已添加白底图参考(Responses): {}KB, 已保存至发送记录", stitched.length / 1024);
            }
        } catch (Exception e) {
            log.warn("白底图拼接失败: {}", e.getMessage());
        }
    }

    /**
     * 给 Image2 请求体添加参考图（input_references）
     */
    private String addRefsToImage2Body(String jsonBody, List<String> refImages) {
        try {
            JSONObject body = JSON.parseObject(jsonBody);
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
            log.info("已添加 {} 张参考图到 Image2 请求", refImages.size());
            return body.toJSONString();
        } catch (Exception e) {
            log.warn("添加参考图失败: {}", e.getMessage());
            return jsonBody;
        }
    }

    /**
     * 给 Responses API 请求体添加参考图（input_image）
     */
    private String addRefsToResponsesBody(String jsonBody, List<String> refImages) {
        try {
            JSONObject body = JSON.parseObject(jsonBody);
            JSONArray input = body.getJSONArray("input");
            if (input != null && !input.isEmpty()) {
                JSONObject userMsg = input.getJSONObject(0);
                JSONArray content = userMsg.getJSONArray("content");
                if (content != null) {
                    // 在文本前面插入图片
                    JSONArray newContent = new JSONArray();
                    for (String img : refImages) {
                        JSONObject imgPart = new JSONObject();
                        imgPart.put("type", "input_image");
                        imgPart.put("image_url", img);
                        newContent.add(imgPart);
                    }
                    // 把原来的内容（文本）加回去
                    for (int i = 0; i < content.size(); i++) {
                        newContent.add(content.getJSONObject(i));
                    }
                    userMsg.put("content", newContent);
                }
            }
            log.info("已添加 {} 张参考图到 Responses 请求", refImages.size());
            return body.toJSONString();
        } catch (Exception e) {
            log.warn("添加参考图失败: {}", e.getMessage());
            return jsonBody;
        }
    }

    /**
     * 拼接目录下的图片，返回 base64 data URL 列表（供 AI 分析使用）
     */
    public List<String> stitchDirToBase64(Path dir, String saveFileName, int parts) {
        List<String> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;
        try {
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(f -> {
                    String n = f.toString().toLowerCase();
                    return n.endsWith(".jpg") || n.endsWith(".png");
                }).sorted().toList();
            }
            if (files.isEmpty()) return result;

            int groupSize = (int) Math.ceil((double) files.size() / parts);
            for (int p = 0; p < parts; p++) {
                int from = p * groupSize;
                int to = Math.min(from + groupSize, files.size());
                if (from >= files.size()) break;
                byte[] stitched = stitchImages(files.subList(from, to), 1024, 0.9f);
                if (stitched != null) {
                    String name = parts > 1 ? saveFileName.replace(".jpg", "_" + (p + 1) + ".jpg") : saveFileName;
                    saveSentImage(stitched, name);
                    String base64 = Base64.getEncoder().encodeToString(stitched);
                    result.add("data:image/jpeg;base64," + base64);
                    log.info("拼接参考图: {} ({}KB, {}/{})", name, stitched.length / 1024, p + 1, parts);
                }
            }
        } catch (Exception e) {
            log.warn("拼接目录失败 {}: {}", saveFileName, e.getMessage());
        }
        return result;
    }

    /**
     * 拼接目录下的图片，以 input_references 格式加入
     */
    private void addImage2References(JSONArray refs, Path dir, String saveFileName) {
        if (!Files.exists(dir)) return;
        try {
            byte[] stitched = stitchImages(dir, 1024, 0.9f);
            if (stitched != null) {
                saveSentImage(stitched, saveFileName);
                String base64 = Base64.getEncoder().encodeToString(stitched);
                JSONObject imgPart = new JSONObject();
                imgPart.put("type", "image_url");
                JSONObject urlObj = new JSONObject();
                urlObj.put("url", "data:image/jpeg;base64," + base64);
                imgPart.put("image_url", urlObj);
                refs.add(imgPart);
                log.info("已添加原图参考: {} ({}KB)", saveFileName, stitched.length / 1024);
            }
        } catch (Exception e) {
            log.warn("拼接原图失败 {}: {}", saveFileName, e.getMessage());
        }
    }

    /**
     * 将目录下的图片分成 N 份分别拼接，每份作为独立的 input_image
     */
    private void addStitchedImagesSplit(JSONArray contentArr, Path dir, String saveFileName, int parts) {
        if (!Files.exists(dir)) {
            log.info("目录不存在，跳过: {}", dir);
            return;
        }
        try {
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream
                        .filter(f -> {
                            String n = f.toString().toLowerCase();
                            return n.endsWith(".jpg") || n.endsWith(".png");
                        })
                        .sorted()
                        .toList();
            }
            if (files.isEmpty()) return;

            // 分成 N 组
            int groupSize = (int) Math.ceil((double) files.size() / parts);
            for (int p = 0; p < parts; p++) {
                int from = p * groupSize;
                int to = Math.min(from + groupSize, files.size());
                if (from >= files.size()) break;
                List<Path> group = files.subList(from, to);
                byte[] stitched = stitchImages(group, 1024, 0.9f);
                if (stitched != null) {
                    String name = parts > 1 ? saveFileName.replace(".jpg", "_" + (p + 1) + ".jpg") : saveFileName;
                    saveSentImage(stitched, name);
                    String base64 = Base64.getEncoder().encodeToString(stitched);
                    JSONObject imgPart = new JSONObject();
                    imgPart.put("type", "input_image");
                    imgPart.put("image_url", "data:image/jpeg;base64," + base64);
                    contentArr.add(imgPart);
                    log.info("已添加原图参考: {} ({}KB, 第{}/{})", name, stitched.length / 1024, p + 1, parts);
                }
            }
        } catch (Exception e) {
            log.warn("拼接原图失败 {}: {}", saveFileName, e.getMessage());
        }
    }

    /**
     * 发送请求（带重试，最多2次），返回图片 URL 列表
     */
    private List<String> executeImageRequestWithRetry(String jsonBody, String endpoint) {
        List<String> imageUrls = new ArrayList<>();
        Exception lastError = null;

        for (int retry = 0; retry < 10; retry++) {
            if (retry > 0) {
                log.warn("第{}次重试（间隔10秒）...", retry);
                try { Thread.sleep(10000L); } catch (InterruptedException ie) { break; }
            }

            try {
                // 屏蔽 base64 数据再打印日志
                String logBody = jsonBody.replaceAll("\"data:image/([^\"]{30})[^\"]{100,}", "\"data:image/$1...(截断)");
                log.info("完整请求 curl:\ncurl -X POST {} \\\n  -H \"Authorization: Bearer {}\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{}'",
                        endpoint, apiKey, logBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = getActiveHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.error("OpenRouter 调用失败: HTTP {} - {}", response.statusCode(), response.body());
                    lastError = new RuntimeException("AI 调用失败: HTTP " + response.statusCode());
                    continue;
                }

                // 打印返回数据（base64 截取前20字）
                String respLog = response.body().replaceAll("\"data:([^;]+;base64,)[^\"]{20}[^\"]*\"", "\"data:$1...(截断)");
                log.info("OpenRouter 返回: HTTP 200, body={}", respLog);

                // 检查返回状态（即便 HTTP 200，body 里也可能 status=failed）
                JSONObject respObj = JSON.parseObject(response.body());
                String respStatus = respObj.getString("status");
                if ("failed".equals(respStatus)) {
                    String errMsg = respObj.getJSONObject("error") != null
                            ? respObj.getJSONObject("error").getString("message") : "未知错误";
                    log.error("OpenRouter 返回失败状态: {} - {}", respStatus, errMsg);
                    lastError = new RuntimeException("AI 返回失败: " + errMsg);
                    continue;
                }

                imageUrls = parseImageResponse(response.body());
                if (!imageUrls.isEmpty()) break;

                lastError = new RuntimeException("未找到生成的图片");
                log.warn("返回结果中未找到图片，重试: {}", response.body());

            } catch (Exception e) {
                lastError = e;
                log.warn("请求异常: {}", e.getMessage());
            }
        }
        return imageUrls;
    }

    /**
     * 解析返回结果，提取图片（支持多种 API 格式）
     */
    private List<String> parseImageResponse(String responseBody) {
        List<String> imageUrls = new ArrayList<>();
        try {
            JSONObject resp = JSON.parseObject(responseBody);

            // 1. Images API 格式: data[].b64_json（openai/gpt-image-2）
            JSONArray data = resp.getJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.size(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    String b64 = item.getString("b64_json");
                    if (b64 != null && !b64.isEmpty()) {
                        imageUrls.add("data:image/png;base64," + b64);
                        log.info("解析到图片(b64_json): {} 字", b64.length());
                    }
                    // 也可能有 url 字段
                    String url = item.getString("url");
                    if (url != null && !url.isEmpty() && !imageUrls.contains(url)) {
                        imageUrls.add(url);
                    }
                }
                if (!imageUrls.isEmpty()) return imageUrls;
            }

            // 2. Responses API 格式: output[] { type: "image_generation_call", result: "data:image/..." }
            JSONArray output = resp.getJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.size(); i++) {
                    JSONObject item = output.getJSONObject(i);
                    String result = item.getString("result");
                    if (result != null && !result.isEmpty() && result.startsWith("data:image")) {
                        imageUrls.add(result);
                    }
                }
                if (!imageUrls.isEmpty()) return imageUrls;
            }

            // 3. 兜底：output[].content[] (type="image_url")
            if (output != null) {
                for (int i = 0; i < output.size(); i++) {
                    JSONObject item = output.getJSONObject(i);
                    JSONArray content = item.getJSONArray("content");
                    if (content != null) {
                        for (int j = 0; j < content.size(); j++) {
                            JSONObject part = content.getJSONObject(j);
                            if ("image_url".equals(part.getString("type"))) {
                                JSONObject imageUrl = part.getJSONObject("image_url");
                                if (imageUrl != null) {
                                    String u = imageUrl.getString("url");
                                    if (u != null && !u.isEmpty()) imageUrls.add(u);
                                }
                            }
                        }
                    }
                }
                if (!imageUrls.isEmpty()) return imageUrls;
            }

            // 4. 兜底：Chat Completions 格式 choices[].message.content[]
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                JSONArray content = message.getJSONArray("content");
                if (content != null) {
                    for (int i = 0; i < content.size(); i++) {
                        JSONObject part = content.getJSONObject(i);
                        if ("image_url".equals(part.getString("type"))) {
                            JSONObject imageUrl = part.getJSONObject("image_url");
                            if (imageUrl != null) {
                                String u = imageUrl.getString("url");
                                if (u != null && !u.isEmpty()) imageUrls.add(u);
                            }
                        }
                    }
                }
            }

            log.info("获取到 {} 张图片", imageUrls.size());
        } catch (Exception e) {
            log.warn("解析返回结果失败: {}", e.getMessage());
        }
        return imageUrls;
    }

    /**
     * 从文本中尝试提取图片 URL
     */
    private void tryExtractImageUrl(String text, List<String> result) {
        if (text == null || text.isEmpty()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s\"')}]+(?:\\.(?:png|jpg|jpeg|gif|webp))").matcher(text);
        while (m.find()) {
            String url = m.group();
            if (!result.contains(url)) result.add(url);
        }
    }

    /**
     * 下载所有图片到本地
     */
    private String downloadAllImages(List<String> imageUrls, String productDir, String platform, int startIndex) {
        String firstPath = null;
        for (int i = 0; i < imageUrls.size(); i++) {
            int idx = startIndex + i;
            String path = downloadImage(imageUrls.get(i), productDir, platform, idx);
            if (i == 0) firstPath = path;
        }
        log.info("图片已保存: {} (共{}张)", firstPath, imageUrls.size());
        return firstPath;
    }

    /**
     * 拼接目录下所有图片为一张竖长图，压缩后返回 byte[]
     */
    private byte[] stitchImages(Path dir, int maxWidth, float quality) throws IOException {
        if (!Files.exists(dir)) return null;

        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream
                    .filter(f -> {
                        String n = f.toString().toLowerCase();
                        return n.endsWith(".jpg") || n.endsWith(".png");
                    })
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) return null;
        return stitchImages(files, maxWidth, quality);
    }

    /**
     * 拼接指定图片列表为一张竖长图
     */
    private byte[] stitchImages(List<Path> files, int maxWidth, float quality) throws IOException {
        // 读取并缩放所有图片（统一缩放到 maxWidth，保证拼接宽度一致）
        List<BufferedImage> images = new ArrayList<>();
        for (Path f : files) {
            BufferedImage img = ImageIO.read(f.toFile());
            if (img == null) continue;

            int w = img.getWidth();
            int h = img.getHeight();

            // 所有图统一缩放到 maxWidth
            double scale = (double) maxWidth / w;
            int newH = Math.max(1, (int) (h * scale));
            BufferedImage scaled = new BufferedImage(maxWidth, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = scaled.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(img, 0, 0, maxWidth, newH, null);
            g2d.dispose();
            images.add(scaled);
        }
        if (images.isEmpty()) return null;

        // 竖向拼接
        int totalH = images.stream().mapToInt(BufferedImage::getHeight).sum();
        int width = images.get(0).getWidth();

        // 限制宽高比在 [1:10, 10:1] 以内（模型 qwen-image 要求）
        int maxH = width * 10;
        if (totalH > maxH) {
            double ratio = (double) maxH / totalH;
            for (int i = 0; i < images.size(); i++) {
                BufferedImage img = images.get(i);
                int newH = Math.max(1, (int) (img.getHeight() * ratio));
                BufferedImage scaled = new BufferedImage(width, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = scaled.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(img, 0, 0, width, newH, null);
                g2d.dispose();
                images.set(i, scaled);
            }
            totalH = maxH;
            log.warn("拼接图比例超限，压缩至 {}x{} (10:1)", width, totalH);
        }

        BufferedImage combined = new BufferedImage(width, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = combined.createGraphics();
        int y = 0;
        for (BufferedImage img : images) {
            g2d.drawImage(img, 0, y, null);
            y += img.getHeight();
        }
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(combined, "jpg", baos);
        byte[] result = baos.toByteArray();
        log.info("图片拼接: {}张 → 1张 ({}x{}, {}KB)", images.size(), width, totalH, result.length / 1024);
        return result;
    }

    /**
     * 保存发送给 AI 的图片到日期归档目录，供用户查看
     */
    private void saveSentImage(byte[] data, String fileName) {
        try {
            String dateDir = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            Path saveDir = Paths.get(IMAGE_OUTPUT_DIR, "发送记录", dateDir);
            Files.createDirectories(saveDir);
            Path target = saveDir.resolve(fileName);
            // 去重：已存在则加序号
            int seq = 1;
            while (Files.exists(target)) {
                String name = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
                String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
                target = saveDir.resolve(name + "_" + seq + ext);
                seq++;
            }
            Files.write(target, data);
            log.info("已保存发送图片: {}", target);
        } catch (IOException e) {
            log.warn("保存发送图片失败: {}", e.getMessage());
        }
    }

    /**
     * 压缩单张图片：最长边缩到 maxSize，JPEG 质量 quality
     */
    private byte[] compressImage(Path imgPath, int maxSize, float quality) throws IOException {
        BufferedImage original = ImageIO.read(imgPath.toFile());
        if (original == null) {
            // 读不到就用原图
            return Files.readAllBytes(imgPath);
        }

        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= maxSize && h <= maxSize) {
            // 已经够小，只做 JPEG 压缩
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(original, "jpg", baos);
            return baos.toByteArray();
        }

        // 缩放
        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, newW, newH, null);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpg", baos);
        byte[] result = baos.toByteArray();
        log.info("图片压缩: {} → {}x{} ({}KB → {}KB)", imgPath.getFileName(), newW, newH,
                Files.size(imgPath) / 1024, result.length / 1024);
        return result;
    }

    /**
     * 解码 base64 并保存为图片（二次写入，剥离元数据）
     */
    private String saveBase64AsImage(String base64Str, Path targetPath) throws IOException {
        byte[] data = Base64.getDecoder().decode(base64Str);
        BufferedImage rawImg = ImageIO.read(new ByteArrayInputStream(data));
        if (rawImg != null) {
            ImageIO.write(rawImg, "jpg", targetPath.toFile());
            log.info("图片已保存（二次写入，剥离元数据）: {}", targetPath);
            return targetPath.toString();
        }
        // 兜底：直接写字节
        Files.write(targetPath, data);
        log.info("图片已保存（base64直接解码）: {}", targetPath);
        return targetPath.toString();
    }

    /**
     * 下载图片到本地目录
     * 目录结构: productDir/AI重绘图/{platform}/主图_XX.jpg
     */
    private String downloadImage(String imageUrl, String productDir, String platform, int imageIndex) {
        try {
            // 确定保存路径
            Path saveDir;
            if (productDir != null && !productDir.isEmpty()) {
                saveDir = Paths.get(productDir, "AI重绘图", platform);
            } else {
                saveDir = Paths.get(IMAGE_OUTPUT_DIR, platform);
            }
            Files.createDirectories(saveDir);

            String prefix = platform != null && platform.contains("replace") ? "替换图" : "主图";
            String fileName = String.format(prefix + "_%02d.jpg", imageIndex);
            Path targetPath = saveDir.resolve(fileName);

            // 如果是 base64 data URL，直接解码保存（二次写入，剥离元数据）
            if (imageUrl.startsWith("data:image/")) {
                int commaIdx = imageUrl.indexOf(',');
                if (commaIdx > 0) {
                    String base64 = imageUrl.substring(commaIdx + 1);
                    return saveBase64AsImage(base64, targetPath);
                }
            }

            // 也可能是纯 base64 字符串（无 data:image/ 前缀）
            if (imageUrl.length() > 100 && !imageUrl.startsWith("http")) {
                try {
                    return saveBase64AsImage(imageUrl, targetPath);
                } catch (Exception e) {
                    log.warn("不是 base64，尝试作为 URL 下载: {}", e.getMessage());
                }
            }

            // 否则从 URL 下载
            java.net.URI uri = URI.create(imageUrl);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = getActiveHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() == 200 && resp.body().length > 0) {
                Files.write(targetPath, resp.body());
                return targetPath.toString();
            } else {
                throw new RuntimeException("下载图片失败: HTTP " + resp.statusCode());
            }

        } catch (Exception e) {
            throw new RuntimeException("保存图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 AI 分析（文本+图片），用于自动生成提示词
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param images       图片 Base64 列表（可选）
     * @return AI 返回的文本
     */
    public String callAiAnalysis(String systemPrompt, String userPrompt, List<String> images) {
        try {
            JSONObject requestBody = new JSONObject();
            // 用 GPT-4o 或 Claude 分析图片
            requestBody.put("model", "openai/gpt-4o");

            JSONArray messages = new JSONArray();

            // System message
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            // User message with text + images
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");

            JSONArray contentArray = new JSONArray();
            // Text
            JSONObject textContent = new JSONObject();
            textContent.put("type", "text");
            textContent.put("text", userPrompt);
            contentArray.add(textContent);
            // Images
            if (images != null) {
                for (String img : images) {
                    JSONObject imgContent = new JSONObject();
                    imgContent.put("type", "image_url");
                    JSONObject urlObj = new JSONObject();
                    urlObj.put("url", img);
                    imgContent.put("image_url", urlObj);
                    contentArray.add(imgContent);
                }
            }
            userMsg.put("content", contentArray);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 2000);

            String jsonBody = requestBody.toJSONString();
            log.info("调用 OpenRouter 分析: prompt={}字符, images={}张", userPrompt.length(), images != null ? images.size() : 0);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENROUTER_API))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "http://localhost:8080")
                    .header("X-OpenRouter-Title", "BlueOceanFilter")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = getActiveHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenRouter 分析调用失败: HTTP {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("AI 分析失败: HTTP " + response.statusCode());
            }

            JSONObject resp = JSON.parseObject(response.body());
            String content = resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            log.info("AI 分析返回: {} 字符", content.length());
            return content;

        } catch (Exception e) {
            log.error("AI 分析异常", e);
            throw new RuntimeException("AI 分析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 OpenRouter 支持的模型列表（用于前端下拉选择）
     */
    public List<Map<String, String>> getSupportedModels() {
        List<Map<String, String>> models = new ArrayList<>();
        models.add(Map.of("id", "google/gemini-3.1-flash-image", "name", "Nano Banana 2"));
        models.add(Map.of("id", "google/gemini-3-pro-image", "name", "Nano Banana Pro"));
        models.add(Map.of("id", "openai/gpt-image-2", "name", "Image 2"));
        return models;
    }
}