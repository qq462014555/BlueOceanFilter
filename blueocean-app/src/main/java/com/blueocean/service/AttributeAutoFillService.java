package com.blueocean.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

/**
 * 商品属性 AI 自动填充服务
 * 独立服务类，不修改现有代码
 */
@Service
public class AttributeAutoFillService {

    private static final Logger log = LoggerFactory.getLogger(AttributeAutoFillService.class);
    private static final String VL_MODEL = "qwen-vl-max";
    private static final String DASHSCOPE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final int IMG_MAX_WIDTH = 800;  // 压缩后最大宽度
    private static final double JPEG_QUALITY = 0.85;

    private static final String RPA_BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";

    @Value("${app.dashscope-api-key}")
    private String apiKey;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 根据千牛页面的宝贝标题，在 RPA 当日文件目录下查找匹配的商品
     * 只遍历今天的日期目录，如 C:\Users\46201\Documents\无极RPA文件处理\2026年05月16日*\分类\商品名\
     */
    public String findProductDir(String qianniuTitle) {
        Path baseDir = Paths.get(RPA_BASE_DIR);
        if (!Files.exists(baseDir)) {
            log.warn("RPA 目录不存在: {}", RPA_BASE_DIR);
            return null;
        }

        String todayPrefix = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        log.info("查找今日日期前缀: {}", todayPrefix);

        try (var taskDirs = Files.list(baseDir)) {
            var taskDirList = taskDirs
                    .filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith(todayPrefix))
                    .toList();

            for (Path taskDir : taskDirList) {
                log.info("匹配到今日任务目录: {}", taskDir.getFileName());
                try (var catDirs = Files.list(taskDir)) {
                    var catDirList = catDirs.filter(Files::isDirectory).toList();

                    for (Path catDir : catDirList) {
                        try (var productDirs = Files.list(catDir)) {
                            var productList = productDirs.filter(Files::isDirectory).toList();

                            for (Path productDir : productList) {
                                String name = productDir.getFileName().toString();
                                if (name.equals(qianniuTitle) || name.startsWith(qianniuTitle)) {
                                    log.info("找到商品目录: {}", productDir);
                                    return productDir.toString();
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("查找商品目录失败: {}", e.getMessage());
        }

        log.warn("未找到匹配的商品: {}", qianniuTitle);
        return null;
    }

    /**
     * 主入口：执行 AI 填充流程
     */
    public Map<String, Object> autoFill(String qianniuTitle, List<Map<String, Object>> qianniuFields) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 1. 查找商品目录
            String productDir = findProductDir(qianniuTitle);
            if (productDir == null) {
                result.put("success", false);
                result.put("error", "未找到匹配的商品目录: " + qianniuTitle);
                return result;
            }
            log.info("找到商品目录: {}", productDir);

            // 2. 读取商品属性
            String productAttrs = readJsonFile(productDir, "商品属性.json");

            // 3. 读取包装信息
            String packInfo = readJsonFile(productDir, "包装信息.json");

            // 4. 拼接主图（01-04）
            String mainLongImg = stitchImages(productDir, "主图", 4);

            // 5. 拼接详情图（前4张）
            String detailLongImg = stitchImages(productDir, "详情图", 4);

            // 6. 读取提示词
            String sysPrompt = readSystemPrompt();

            // 7. 构建用户提示词
            String userPrompt = buildUserPrompt(sysPrompt, productAttrs, packInfo, qianniuFields);

            // 8. 调用 qwen-vl-max
            List<String> imagePaths = new ArrayList<>();
            if (mainLongImg != null) imagePaths.add(mainLongImg);
            if (detailLongImg != null) imagePaths.add(detailLongImg);

            String aiJson = callVlModel(userPrompt, imagePaths);

            // 9. 解析 AI 返回的 JSON
            JSONArray filledFields = parseAiResponse(aiJson, qianniuFields);

            // 10. 保存到原千牛属性 JSON 文件
            String savedPath = saveFilledFields(productDir, filledFields);

            result.put("success", true);
            result.put("fieldCount", filledFields.size());
            result.put("productDir", productDir);
            result.put("savedPath", savedPath);
            result.put("fields", filledFields);

        } catch (Exception e) {
            log.error("AI 自动填充失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== 图片处理 ====================

    /**
     * 读取目录下图片，统一宽度后拼接成一张竖向长图
     */
    private String stitchImages(String productDir, String subDir, int maxCount) {
        Path dir = Paths.get(productDir, subDir);
        if (!Files.exists(dir)) return null;

        try {
            List<Path> imageFiles = Files.list(dir)
                    .filter(p -> p.toString().toLowerCase().endsWith(".jpg") || p.toString().toLowerCase().endsWith(".png"))
                    .sorted()
                    .limit(maxCount)
                    .toList();

            if (imageFiles.isEmpty()) return null;

            // 先读取所有图片，取最大宽度作为统一宽度
            List<BufferedImage> rawImages = new ArrayList<>();
            int maxWidth = 0;
            for (Path p : imageFiles) {
                BufferedImage img = ImageIO.read(p.toFile());
                if (img != null) {
                    rawImages.add(img);
                    if (img.getWidth() > maxWidth) maxWidth = img.getWidth();
                }
            }
            if (rawImages.isEmpty()) return null;

            // 限制最大宽度
            if (maxWidth > IMG_MAX_WIDTH) maxWidth = IMG_MAX_WIDTH;

            // 统一所有图片宽度
            List<BufferedImage> images = new ArrayList<>();
            for (BufferedImage img : rawImages) {
                images.add(resizeToWidth(img, maxWidth));
            }

            // 竖向拼接
            int totalHeight = images.stream().mapToInt(BufferedImage::getHeight).sum();
            BufferedImage longImg = new BufferedImage(maxWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = longImg.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, maxWidth, totalHeight);
            int y = 0;
            for (BufferedImage img : images) {
                g.drawImage(img, 0, y, null);
                y += img.getHeight();
            }
            g.dispose();

            // 保存为临时文件
            String tmpFile = System.getProperty("java.io.tmpdir") + "/qianniu_longimg_" + subDir + "_" + System.currentTimeMillis() + ".jpg";
            try (var out = new FileOutputStream(tmpFile)) {
                ImageIO.write(longImg, "jpg", out);
            }
            log.info("拼接 {} 长图: {} 张 → {} ({}x{})", subDir, images.size(), tmpFile, maxWidth, totalHeight);
            return tmpFile;

        } catch (Exception e) {
            log.error("拼接 {} 图片失败: {}", subDir, e.getMessage());
            return null;
        }
    }

    /**
     * 将图片缩放到指定宽度，保持宽高比
     */
    private BufferedImage resizeToWidth(BufferedImage img, int targetWidth) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= targetWidth && w >= targetWidth - 2) return img;  // 宽度已接近

        int newH = h * targetWidth / w;
        BufferedImage resized = new BufferedImage(targetWidth, newH, BufferedImage.TYPE_INT_RGB);
        resized.createGraphics().drawImage(img.getScaledInstance(targetWidth, newH, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        return resized;
    }

    // ==================== AI 调用 ====================

    /**
     * 调用 qwen-vl-max 多模态模型
     */
    private String callVlModel(String userPrompt, List<String> imagePaths) throws Exception {
        JSONArray contentArray = new JSONArray();

        // 添加图片内容
        for (String imgPath : imagePaths) {
            File imgFile = new File(imgPath);
            if (!imgFile.exists()) continue;

            // 读取图片转 Base64
            byte[] imgBytes = Files.readAllBytes(imgFile.toPath());
            String base64 = Base64.getEncoder().encodeToString(imgBytes);

            JSONObject imgObj = new JSONObject();
            imgObj.put("type", "image_url");
            JSONObject urlObj = new JSONObject();
            urlObj.put("url", "data:image/jpeg;base64," + base64);
            imgObj.put("image_url", urlObj);
            contentArray.add(imgObj);
        }

        // 添加文本
        JSONObject textObj = new JSONObject();
        textObj.put("type", "text");
        textObj.put("text", userPrompt);
        contentArray.add(textObj);

        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", contentArray);
        messages.add(userMsg);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", VL_MODEL);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.1);

        String jsonBody = requestBody.toJSONString();
        log.info("调用 qwen-vl-max, 图片数={}, 请求大小={}KB", imagePaths.size(), jsonBody.length() / 1024);

        HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(60)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_URL))
                .timeout(java.time.Duration.ofSeconds(120))
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
        log.info("AI 返回内容长度: {} 字符", content.length());
        return content;
    }

    // ==================== 解析与保存 ====================

    /**
     * 解析 AI 返回的 JSON 数组
     */
    private JSONArray parseAiResponse(String aiJson, List<Map<String, Object>> originalFields) {
        // 尝试提取 JSON 数组（可能包含 markdown 代码块标记）
        String cleanJson = aiJson.trim();
        if (cleanJson.startsWith("```")) {
            int start = cleanJson.indexOf('[');
            int end = cleanJson.lastIndexOf(']') + 1;
            if (start >= 0 && end > start) {
                cleanJson = cleanJson.substring(start, end);
            }
        }

        JSONArray filledArray = JSON.parseArray(cleanJson);
        if (filledArray == null || filledArray.isEmpty()) {
            log.warn("AI 返回 JSON 解析失败，返回原始字段");
            JSONArray fallback = new JSONArray();
            for (Map<String, Object> f : originalFields) {
                fallback.add(f);
            }
            return fallback;
        }

        // 清洗 measurement 类型字段：只保留纯数字
        for (int i = 0; i < filledArray.size(); i++) {
            JSONObject obj = filledArray.getJSONObject(i);
            if ("measurement".equals(obj.getString("type"))) {
                String val = obj.getString("currentValue");
                if (val != null && !val.isEmpty()) {
                    String digits = val.replaceAll("^([0-9]+).*$", "$1");
                    if (!digits.equals(val) && !digits.isEmpty()) {
                        obj.put("currentValue", digits);
                        log.info("清洗 measurement 字段 [{}]：{} → {}", obj.getString("label"), val, digits);
                    }
                }
            }
        }

        log.info("AI 返回 {} 个字段", filledArray.size());
        return filledArray;
    }

    /**
     * 保存填充后的属性到千牛属性 JSON 文件
     */
    private String saveFilledFields(String productDir, JSONArray filledFields) {
        try {
            String filename = "qianniu_attr_ai_back.json";
            Path filePath = Paths.get(productDir, filename);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("extractedAt", java.time.LocalDateTime.now().toString());
            payload.put("filledBy", "qwen-vl-max");
            payload.put("fieldCount", filledFields.size());
            payload.put("fields", filledFields);

            Files.writeString(filePath, mapper.writeValueAsString(payload), StandardCharsets.UTF_8);
            log.info("填充结果已保存: {}", filePath);
            return filePath.toString();

        } catch (IOException e) {
            log.error("保存填充结果失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    private String readJsonFile(String dir, String filename) {
        try {
            Path path = Paths.get(dir, filename);
            if (!Files.exists(path)) return null;
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("读取 {} 失败: {}", filename, e.getMessage());
            return null;
        }
    }

    private String buildUserPrompt(String sysPrompt, String productAttrs, String packInfo, List<Map<String, Object>> qianniuFields) {
        StringBuilder sb = new StringBuilder();
        sb.append(sysPrompt).append("\n\n");

        if (productAttrs != null) {
            sb.append("【1688 商品属性】\n").append(productAttrs).append("\n\n");
        }
        if (packInfo != null) {
            sb.append("【包装信息】\n").append(packInfo).append("\n\n");
        }

        sb.append("【千牛属性模板（需要填充 currentValue）】\n");
        try {
            sb.append(mapper.writeValueAsString(qianniuFields));
        } catch (Exception e) {
            sb.append(qianniuFields.toString());
        }

        return sb.toString();
    }

    private String readSystemPrompt() {
        try {
            return new String(AttributeAutoFillService.class.getClassLoader()
                    .getResourceAsStream("prompts/attribute-fill-prompt.txt")
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("读取提示词失败: {}", e.getMessage());
            return "请根据商品信息和图片，填充千牛属性模板中的 currentValue 字段。";
        }
    }
}
