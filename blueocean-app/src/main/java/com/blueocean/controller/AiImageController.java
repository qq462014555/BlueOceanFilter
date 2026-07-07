package com.blueocean.controller;

import com.blueocean.service.DashScopeClient;
import com.blueocean.service.OpenRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.Base64;

/**
 * AI 主图重绘控制器 — 提示词管理 + 图片生成
 */
@RestController
@RequestMapping("/api/ai-image")
public class AiImageController {

    private static final Logger log = LoggerFactory.getLogger(AiImageController.class);

    private final OpenRouterService openRouterService;
    private final DashScopeClient dashScopeClient;

    // 提示词配置文件路径
    private static final String PROMPT_FILE = "ai_image_prompts.json";

    public AiImageController(OpenRouterService openRouterService, DashScopeClient dashScopeClient) {
        this.openRouterService = openRouterService;
        this.dashScopeClient = dashScopeClient;
    }

    // ==================== AI 智能生成提示词 ====================

    /**
     * 根据商品标题 + 详情图，自动生成 5 张主图的风格提示词
     */
    @PostMapping("/auto-generate-prompts")
    public ResponseEntity<Map<String, Object>> autoGeneratePrompts(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        String platform = (String) request.get("platform");
        boolean forceNew = Boolean.TRUE.equals(request.get("forceNew"));

        if (productDir == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 productDir"));
        }

        try {
            // 1. 获取商品标题（优先从目录名获取）
            Path productDirPath = Paths.get(productDir);
            String title = productDirPath.getFileName().toString();
            log.info("商品标题（从目录名）: '{}'", title);

            // 2. 从对应平台的 prompt 文件读取提示词模板
            String promptTemplate = readPromptFile(platform);
            String userPrompt = promptTemplate.replace("{{TITLE}}", title);

            // 3. 先查缓存：{productDir}/AI重绘图/.analysis_{platform}.json
            Path cacheDir = Paths.get(productDir, "AI重绘图");
            Path cacheFile = cacheDir.resolve(".analysis_" + platform + ".json");
            Map<String, Object> result = new LinkedHashMap<>();

            if (Files.exists(cacheFile) && !forceNew) {
                log.info("命中缓存，跳过 AI 分析: {}", cacheFile);
                String cachedJson = Files.readString(cacheFile);
                Map<String, Object> cached = com.alibaba.fastjson2.JSON.parseObject(cachedJson, Map.class);
                result.put("success", true);
                result.put("prompts", cached);
                result.put("title", title);
                result.put("cached", true);
                return ResponseEntity.ok(result);
            }

            log.info("无缓存，调用 AI 分析...");

            // 4. 拼接主图 + 详情图作为参考图（压缩后传给 qwen-vl-max）
            List<String> productImages = new ArrayList<>();
            productImages.addAll(openRouterService.stitchDirToBase64(Paths.get(productDir, "主图"), "主图拼接参考.jpg", 1));
            productImages.addAll(openRouterService.stitchDirToBase64(Paths.get(productDir, "详情图"), "详情图拼接参考.jpg", 2));

            String systemPrompt = """
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
            // 用 qwen-vl-max 多模态分析（带文字 + 主图 + 详情图）
            String aiResponse = dashScopeClient.chatWithImages(systemPrompt, userPrompt,
                    productImages.isEmpty() ? null : productImages);

            // 5. 解析 AI 返回的 JSON
            try {
                String cleanJson = aiResponse.replaceAll("```[a-z]*\\n?", "").trim();
                @SuppressWarnings("unchecked")
                Map<String, Object> prompts = com.alibaba.fastjson2.JSON.parseObject(cleanJson, Map.class);
                result.put("success", true);
                result.put("prompts", prompts);
                result.put("title", title);

                // 保存到缓存
                try {
                    Files.createDirectories(cacheDir);
                    Files.writeString(cacheFile, cleanJson);
                    log.info("AI 分析结果已缓存: {}", cacheFile);
                } catch (IOException e) {
                    log.warn("写入缓存失败: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.warn("AI 返回解析失败: {}", aiResponse);
                result.put("success", false);
                result.put("error", "AI 返回格式异常");
                result.put("rawResponse", aiResponse);
            }
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("AI 生成提示词失败", e);
            // 返回默认提示词
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("success", true);
            fallback.put("prompts", getDefaultPromptsForPlatform(platform, ""));
            fallback.put("note", "AI 分析失败，使用默认提示词");
            return ResponseEntity.ok(fallback);
        }
    }

    private Map<String, String> getDefaultPromptsForPlatform(String platform, String title) {
        Map<String, String> prompts = new LinkedHashMap<>();
        String baseStyle = "taobao".equals(platform) ? "白底, 高光质感, 产品居中" :
                "douyin".equals(platform) ? "色彩鲜艳, 场景化, 吸引眼球" : "简洁清晰, 干净背景";
        for (int i = 1; i <= 5; i++) {
            prompts.put("图" + i, "Product on clean background, professional lighting, " + baseStyle);
        }
        return prompts;
    }

    /**
     * 从 resources/prompts/ai-image-prompt.txt 读取提示词模板
     */
    private String readPromptFile(String platform) {
        try {
            String fileName = "prompts/photo/taobao.txt";
            if (platform != null) {
                String pf = switch (platform) {
                    case "douyin" -> "prompts/photo/douyin.txt";
                    case "shopee" -> "prompts/photo/shopee.txt";
                    default -> "prompts/photo/taobao.txt";
                };
                if (getClass().getClassLoader().getResource(pf) != null) {
                    fileName = pf;
                }
            }
            var in = getClass().getClassLoader().getResourceAsStream(fileName);
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("读取提示词文件失败，使用默认模板: {}", e.getMessage());
        }
        // 默认兜底
        return """
                ## 商品信息
                商品标题：{{TITLE}}

                ## 目标平台
                {{PLATFORM_ZH}}

                ## 分析要求（显性输出）
                在生成 prompt 前，先分析以下维度，并将分析结果一并输出：
                1. 产品品类（是什么、属于什么类目）
                2. 材质（产品用什么材料做的，表面质感、纹理、光泽）
                3. 核心卖点（功能、设计、尺寸、工艺、独特之处）—— **以标题描述权重高**
                4. 目标人群（谁会买这个产品、用在什么场景）
                5. 使用场景（产品在什么环境中使用）—— **以标题描述权重高**
                6. 视觉特征（形状、颜色、结构、最上镜的角度）

                ## 输出格式
                ```json
                {
                  "品类":"",
                  "材质":"",
                  "卖点":"",
                  "目标人群":"",
                  "使用场景":"",
                  "视觉特征":""
                }
                ```

                ## Prompt 生成规范
                1. 每个 prompt 用**中文**，直接可用于 图生成 模式
                2. 每个 prompt 包含：保留产品外形 + 新背景/新风格 + 光线要求 + 构图
                3. 长度：30~80个中文单词
                4. 5张图要有明显区分（角度/背景/侧重点不同）
                5. 禁止在图片上生成文字/水印/LOGO
                6. 禁止虚构产品不存在的功能或特征
                7. 不要改变产品的核心颜色、形状、结构
                8. **标题中的卖点和场景描述必须优先体现在提示词中**

                ## 最终输出
                只返回JSON，不要任何解释文字。
                """;
    }

    // ==================== 提示词管理 ====================

    /**
     * 获取所有平台的提示词配置
     */
    @GetMapping("/prompts")
    public ResponseEntity<Map<String, Object>> getPrompts() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platforms", loadPrompts());
        result.put("models", openRouterService.getSupportedModels());
        return ResponseEntity.ok(result);
    }

    /**
     * 保存提示词配置
     */
    @PostMapping("/prompts")
    public ResponseEntity<Map<String, Object>> savePrompts(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> platforms = (Map<String, Object>) request.get("platforms");
        if (platforms == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 platforms 参数"));
        }
        try {
            savePromptsToFile(platforms);
            return ResponseEntity.ok(Map.of("success", true, "message", "提示词已保存"));
        } catch (IOException e) {
            log.error("保存提示词失败", e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "保存失败: " + e.getMessage()));
        }
    }

    // ==================== 图片生成 ====================

    /**
     * 生成单张图片
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateImage(@RequestBody Map<String, Object> request) {
        String model = (String) request.get("model");
        String prompt = (String) request.get("prompt");
        String productDir = (String) request.get("productDir");
        String platform = (String) request.get("platform");
        Integer imageIndex = (Integer) request.get("imageIndex");
        int n = request.get("n") != null ? ((Number) request.get("n")).intValue() : 1;
        @SuppressWarnings("unchecked")
        Map<String, String> allPrompts = (Map<String, String>) request.get("allPrompts");

        if (model == null || prompt == null || platform == null || imageIndex == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少必要参数"));
        }

        try {
            String savedPath = openRouterService.generateImage(model, prompt, allPrompts, productDir, platform, imageIndex, n);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "path", savedPath,
                    "platform", platform,
                    "imageIndex", imageIndex
            ));
        } catch (Exception e) {
            log.error("生成图片失败", e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 批量生成勾选的图
     */
    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAll(@RequestBody Map<String, Object> request) {
        String model = (String) request.get("model");
        String prompt = (String) request.get("prompt");
        String productDir = (String) request.get("productDir");
        String platform = (String) request.get("platform");
        @SuppressWarnings("unchecked")
        Map<String, String> allPrompts = (Map<String, String>) request.get("allPrompts");

        if (model == null || productDir == null || platform == null || allPrompts == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少必要参数"));
        }

        // 模型一次只能生成一张图，串行逐张生成
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (Map.Entry<String, String> entry : allPrompts.entrySet()) {
            String key = entry.getKey();
            String singlePrompt = entry.getValue();
            if (singlePrompt == null || singlePrompt.trim().isEmpty()) continue;
            int imageIndex = Integer.parseInt(key.replace("图", ""));
            String singleCombined = key + "：\n" + singlePrompt;
            // 只传当前图的提示词，不带其他图的
            Map<String, String> singlePromptMap = Map.of(key, singlePrompt);
            try {
                String savedPath = openRouterService.generateImage(model, singleCombined, singlePromptMap, productDir, platform, imageIndex, 1);
                results.add(Map.of("key", key, "path", savedPath, "success", true));
            } catch (Exception e) {
                log.error("生成{}失败: {}", key, e.getMessage());
                errors.add(Map.of("key", key, "error", e.getMessage()));
            }
            // 每张间隔 2 秒
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("results", results);
        result.put("errors", errors);
        result.put("total", results.size() + errors.size());
        result.put("succeeded", results.size());
        result.put("failed", errors.size());
        return ResponseEntity.ok(result);
    }

    /**
     * 根据本地路径返回图片文件，用于前端预览
     */
    @GetMapping("/image-file")
    public ResponseEntity<byte[]> getImageFile(@RequestParam String path) {
        try {
            Path file = Paths.get(path);
            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(file);
            String contentType = path.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok()
                    .header("Content-Type", contentType)
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 智能优化提示词：AI 商品分析 + 原提示词 → DeepSeek 优化
     */
    @PostMapping("/optimize-prompt")
    public ResponseEntity<Map<String, Object>> optimizePrompt(@RequestBody Map<String, Object> request) {
        String originalPrompt = (String) request.get("prompt");
        @SuppressWarnings("unchecked")
        Map<String, String> analysis = (Map<String, String>) request.get("analysis");

        if (originalPrompt == null || analysis == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        }

        try {
            // 读取优化提示词模板
            String template = readOptimizePromptTemplate();
            String userPrompt = template
                    .replace("{{CATEGORY}}", analysis.getOrDefault("品类", ""))
                    .replace("{{MATERIAL}}", analysis.getOrDefault("材质", ""))
                    .replace("{{SELLING_POINT}}", analysis.getOrDefault("卖点", ""))
                    .replace("{{TARGET_AUDIENCE}}", analysis.getOrDefault("目标人群", ""))
                    .replace("{{USE_SCENE}}", analysis.getOrDefault("使用场景", ""))
                    .replace("{{VISUAL_FEATURE}}", analysis.getOrDefault("视觉特征", ""))
                    .replace("{{ORIGINAL_PROMPT}}", originalPrompt);

            String systemPrompt = "你是一名电商主图提示词优化专家。根据商品分析信息，优化用户提供的 AI 绘图提示词。";

            // 用通义千问优化（跟 AI 商品分析同一模型）
            String result = dashScopeClient.chat(systemPrompt, userPrompt, DashScopeClient.MODEL_QWEN3_6_PLUS);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "optimizedPrompt", result
            ));
        } catch (Exception e) {
            log.error("优化提示词失败", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "优化失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 读取优化提示词模板
     */
    private String readOptimizePromptTemplate() {
        try {
            var in = getClass().getClassLoader().getResourceAsStream("prompts/photo/optimize_prompt.txt");
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("读取优化提示词模板失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 生成白底图：检查是否存在，不存在则调用 Qwen-Image-2.0 生成
     */
    @PostMapping("/generate-white-bg")
    public ResponseEntity<Map<String, Object>> generateWhiteBg(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        boolean force = Boolean.TRUE.equals(request.get("force"));
        if (productDir == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 productDir"));
        }

        List<Map<String, Object>> images = new ArrayList<>();

        // 检查已存在的白底图
        Path whiteBgDir = Paths.get(productDir, "白底图");
        if (!force && Files.exists(whiteBgDir)) {
            try (var files = Files.list(whiteBgDir)) {
                files.filter(f -> {
                    String n = f.toString().toLowerCase();
                    return n.endsWith(".jpg") || n.endsWith(".png");
                }).sorted().forEach(f -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", f.toString());
                    item.put("name", f.getFileName().toString());
                    images.add(item);
                });
            } catch (IOException ignored) {}
        }

        // 已有白底图，直接返回
        if (!images.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", true, "images", images, "fromCache", true));
        }

        // 没有白底图，调用 Qwen-Image-2.0 生成
        try {
            // 压缩拼接参考图（最多3张，模型限制 0~3 张）
            List<String> refImages = new ArrayList<>();
            refImages.addAll(openRouterService.stitchDirToBase64(Paths.get(productDir, "主图"), "白底图_主图参考.jpg", 2));
            // 最多再加1张详情图（总共不超过3张）
            if (refImages.size() < 3) {
                refImages.addAll(openRouterService.stitchDirToBase64(Paths.get(productDir, "详情图"), "白底图_详情参考.jpg", 1));
            }
            // 截断到3张
            if (refImages.size() > 3) refImages = new ArrayList<>(refImages.subList(0, 3));

            String prompt = "生成该产品的白底图，纯白色背景，产品完整展示，不同角度拍摄，共3张分别从正面45度、正侧面、正上方俯视拍摄，产品细节清晰可见，无阴影，专业电商白底图风格";

            // 直接生成并保存到白底图目录（返回文件路径列表）
            Files.createDirectories(whiteBgDir);
            List<String> savedPaths = dashScopeClient.generateImages(prompt, refImages.isEmpty() ? null : refImages, 3, whiteBgDir.toString());

            for (String path : savedPaths) {
                Path filePath = Paths.get(path);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", path);
                item.put("name", filePath.getFileName().toString());
                images.add(item);
            }
            log.info("白底图生成完成: {} 张", images.size());
        } catch (Exception e) {
            log.error("生成白底图失败", e);
            return ResponseEntity.ok(Map.of("success", false, "error", "生成失败: " + e.getMessage(), "images", images));
        }

        return ResponseEntity.ok(Map.of("success", true, "images", images, "fromCache", false));
    }

    /**
     * 列出某商品的所有 AI 重绘图片
     */
    @GetMapping("/list-images")
    public ResponseEntity<Map<String, Object>> listAiImages(@RequestParam String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (String platform : List.of("taobao", "douyin", "shopee")) {
            Path dir = Paths.get(productDir, "AI重绘图", platform);
            if (Files.exists(dir)) {
                try (var files = Files.list(dir)) {
                    files.filter(f -> {
                        String n = f.toString().toLowerCase();
                        return n.endsWith(".jpg") || n.endsWith(".png");
                    }).sorted().forEach(f -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("path", f.toString());
                        item.put("name", f.getFileName().toString());
                        item.put("platform", platform);
                        images.add(item);
                    });
                } catch (IOException ignored) {
                }
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "images", images));
    }

    @GetMapping("/list-replace-images")
    public ResponseEntity<Map<String, Object>> listReplaceImages(@RequestParam String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        Path replaceDir = Paths.get(productDir, "替换图");
        if (Files.exists(replaceDir)) {
            try (var files = Files.list(replaceDir)) {
                files.filter(f -> {
                    String n = f.toString().toLowerCase();
                    return n.endsWith(".jpg") || n.endsWith(".png");
                }).sorted().forEach(f -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", f.toString());
                    item.put("name", f.getFileName().toString());
                    images.add(item);
                });
            } catch (IOException ignored) {}
        }
        return ResponseEntity.ok(Map.of("success", true, "images", images));
    }

    // ==================== 替换图 ====================

    /**
     * 替换图：以白底图为参考，将用户上传的图替换产品
     */
    @PostMapping("/replace")
    public ResponseEntity<Map<String, Object>> replaceImages(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) request.get("images");
        @SuppressWarnings("unchecked")
        List<String> prompts = (List<String>) request.get("prompts");
        if (productDir == null || images == null || images.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        }

        String model = (String) request.get("model");
        if (model == null || model.isEmpty()) model = "black-forest-labs/FLUX.1-schnell";

        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        // 使用串行生成，每张间隔2秒
        for (int i = 0; i < images.size(); i++) {
            String userImg = images.get(i);
            String extraPrompt = (prompts != null && i < prompts.size()) ? prompts.get(i) : "";
            try {
                // 保存用户输入图
                Path inputDir = Paths.get(productDir, "替换图_输入");
                Files.createDirectories(inputDir);
                String ext = userImg.startsWith("data:image/png") ? ".png" : ".jpg";
                String inputName = "输入图_" + (i + 1) + ext;
                if (userImg.startsWith("data:image")) {
                    byte[] data = Base64.getDecoder().decode(userImg.substring(userImg.indexOf(',') + 1));
                    Files.write(inputDir.resolve(inputName), data);
                }

                // 读取替换提示词
                String prompt;
                try {
                    var in = getClass().getClassLoader().getResourceAsStream("prompts/photo/replace.txt");
                    prompt = in != null ? new String(in.readAllBytes(), StandardCharsets.UTF_8) : "请将白底图中的产品替换到场景图中";
                } catch (Exception e) {
                    prompt = "请将白底图中的产品替换到场景图中";
                }
                prompt += "\n当前第" + (i + 1) + "张场景图，共" + images.size() + "张。";
                if (!extraPrompt.isEmpty()) {
                    prompt += "\n用户补充说明：" + extraPrompt;
                }

                // 白底图参考 + 用户图作为参考
                List<String> refs = openRouterService.stitchDirToBase64(Paths.get(productDir, "白底图"), "替换_白底图参考.jpg", 3);
                refs.add(userImg);
                if (refs.size() > 4) refs = new ArrayList<>(refs.subList(0, 4));

                String savedPath = openRouterService.generateImageWithRefs(
                        model, prompt, Map.of(), productDir, "replace_gen", i + 1, 1, refs);

                // 复制到替换图目录
                Path outputDir = Paths.get(productDir, "替换图");
                Files.createDirectories(outputDir);
                String outName = String.format("替换图_%02d.jpg", i + 1);
                Path outPath = outputDir.resolve(outName);
                Path tempPath = Paths.get(savedPath);
                if (Files.exists(tempPath)) {
                    Files.copy(tempPath, outPath, StandardCopyOption.REPLACE_EXISTING);
                }

                // 对比原图，检测是否替换成功
                String similarWarning = checkImageSimilarity(outPath.toString(), userImg);
                Map<String, Object> resultItem = new LinkedHashMap<>();
                resultItem.put("key", "替换图" + (i + 1));
                resultItem.put("path", outPath.toString());
                resultItem.put("success", true);
                if (similarWarning != null) resultItem.put("warning", similarWarning);
                results.add(resultItem);

                if (i < images.size() - 1) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                log.error("生成替换图失败", e);
                errors.add(Map.of("key", "替换图" + (i + 1), "error", e.getMessage()));
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true, "results", results, "errors", errors,
                "total", results.size() + errors.size(),
                "succeeded", results.size(), "failed", errors.size()));
    }

    // ==================== 数据持久化 ====================

    /**
     * 图片相似度检测，返回警告文字（null 表示正常）
     */
    private String checkImageSimilarity(String genPath, String originalImg) {
        try {
            // 原图可能是 base64，需要先保存
            Path tempDir = Files.createTempDirectory("replace_cmp_");
            Path origPath = tempDir.resolve("original.jpg");
            if (originalImg.startsWith("data:image")) {
                byte[] data = Base64.getDecoder().decode(originalImg.substring(originalImg.indexOf(',') + 1));
                java.awt.image.BufferedImage rawImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
                if (rawImg != null) javax.imageio.ImageIO.write(rawImg, "jpg", origPath.toFile());
                else Files.write(origPath, data);
            }

            // 读取两张图并缩小到8x8
            java.awt.image.BufferedImage imgA = javax.imageio.ImageIO.read(new java.io.File(genPath));
            java.awt.image.BufferedImage imgB = javax.imageio.ImageIO.read(origPath.toFile());
            if (imgA == null || imgB == null) return null;

            int size = 8;
            java.awt.image.BufferedImage smallA = new java.awt.image.BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            java.awt.image.BufferedImage smallB = new java.awt.image.BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D gA = smallA.createGraphics();
            gA.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gA.drawImage(imgA, 0, 0, size, size, null); gA.dispose();
            java.awt.Graphics2D gB = smallB.createGraphics();
            gB.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gB.drawImage(imgB, 0, 0, size, size, null); gB.dispose();

            // 计算像素差异
            double diff = 0, count = 0;
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    int c1 = smallA.getRGB(x, y) & 0xFF;
                    int c2 = smallB.getRGB(x, y) & 0xFF;
                    diff += Math.abs(c1 - c2);
                    count++;
                }
            }
            double similarity = 1 - (diff / (count * 255.0));

            // 清理临时文件
            try { Files.deleteIfExists(origPath); Files.deleteIfExists(tempDir); } catch (Exception ignored) {}

            if (similarity > 0.85) {
                int pct = (int) Math.round(similarity * 100);
                log.warn("替换图与场景图相似度 {}%，可能替换失败", pct);
                return "相似度过高(" + pct + "%)，可能未替换成功";
            }
        } catch (Exception e) {
            log.warn("图片相似度检测失败: {}", e.getMessage());
        }
        return null;
    }

    private Path getPromptFilePath() {
        // 保存在项目根目录
        return Paths.get(System.getProperty("user.dir"), PROMPT_FILE);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPrompts() {
        Path file = getPromptFilePath();
        if (!Files.exists(file)) {
            return getDefaultPrompts();
        }
        try {
            String json = Files.readString(file);
            return com.alibaba.fastjson2.JSON.parseObject(json, Map.class);
        } catch (Exception e) {
            log.warn("读取提示词文件失败，使用默认配置: {}", e.getMessage());
            return getDefaultPrompts();
        }
    }

    private void savePromptsToFile(Map<String, Object> platforms) throws IOException {
        Path file = getPromptFilePath();
        Files.writeString(file,
                com.alibaba.fastjson2.JSON.toJSONString(Map.of("platforms", platforms)));
        log.info("提示词已保存到: {}", file);
    }

    private Map<String, Object> getDefaultPrompts() {
        Map<String, Object> platforms = new LinkedHashMap<>();
        // 淘宝
        Map<String, String> taobaoPrompts = new LinkedHashMap<>();
        taobaoPrompts.put("图1", "首图・流量拦截视觉锤\n" +
                "【本次生成】\n" +
                "图1：首图・流量拦截视觉锤\n" +
                "plaintext\n" +
                "\n" +
                "本图为纯商品展示电商首图，不做促销活动图。画面禁止出现任何价格数字、满减、折扣、限时、爆款、好礼、赠品、包邮、售后退换、福利、抢购、下单等促销或服务类文字、色块、图标；禁止用方括号、大括号、竖线、符号装饰标题。所有文字仅可表达产品功能、材质、卖点、品类信息。\n" +
                "\n" +
                "电商产品首图，正方形1440×1440像素。【商品全称】为画面绝对视觉主体，产品完整清晰展示，占据画面主要视觉重心，采用自然且能突出产品外观的展示角度，重点呈现产品核心结构、形态轮廓、厚度层次和关键功能特征。产品真实还原【核心材质描述】的质感，材质细节清晰，色泽自然，整体干净高级。\n" +
                "\n" +
                "背景为低饱和同色系渐变或极简浅色影棚背景，画面简洁，主体突出，不使用复杂场景干扰产品展示。\n" +
                "\n" +
                "画面文字由AI根据产品结构和画面视觉重心自主排版，只要求信息层级清晰、阅读顺序自然、不遮挡产品核心结构。允许AI自主决定标题、副标题、卖点标签的位置、大小、颜色、字体样式和排版形式，但整体风格必须简约、干净、克制，符合电商首图视觉标准。\n" +
                "\n" +
                "可出现的文字内容仅限：产品功能、材质、卖点、品类相关信息。\n" +
                "\n" +
                "专业影棚柔光打光，光影均匀柔和，产品立体有质感，无过曝、无硬阴影，边缘清晰。整体风格简约高级，无多余装饰元素，所有文字准确无错字、无乱码、字体统一。");

        taobaoPrompts.put("图2", "核心卖点图・痛点解决方案\n" +
                "核心逻辑：量化核心卖点 + 直击用户痛点 + 功能可视化呈现\n" +
                "plaintext\n" +
                "电商产品卖点主图，正方形1440×1440像素。画面中心偏上放置【商品全称】的核心展示视角，清晰呈现产品核心功能结构。\n" +
                "画面均匀分布3个核心卖点模块，搭配极简线性图标，每个模块配清晰黑体文案：\n" +
                "1. 【卖点1：功能+用户价值】\n" +
                "2. 【卖点2：材质+差异化优势】\n" +
                "3. 【卖点3：体验+实际好处】\n" +
                "画面顶部居中放置加粗主标题文案：【一句话痛点解决方案】，字体醒目不突兀。\n" +
                "背景为纯净浅色系，整体干净整洁，产品材质细节真实还原。专业无影棚光效，光影均匀，整体风格专业可信，所有文字清晰准确、字");
        taobaoPrompts.put("图3", "场景代入图・营造使用氛围感\n" +
                "核心逻辑：真实使用场景 + 轻人物互动 + 唤醒用户需求联想\n" +
                "plaintext\n" +
                "电商场景化产品主图，正方形1440×1440像素。【典型使用场景】中自然放置【商品全称】，有真人局部肢体自然互动使用产品（仅露出肢体/局部身体，不露出脸部，规避肖像权风险），采用45度平视/俯拍视角，呈现生活化自然状态。\n" +
                "产品完美融入场景，营造舒适自然的使用氛围感。光线为柔和窗边自然光，色调舒适明亮，画面干净整洁，产品依然是视觉核心，场景元素仅做点缀不抢镜。\n" +
                "画面右上角放置柔和的深灰色文案：【多场景适配slogan】，文字轻盈不破坏画面氛围。\n" +
                "整体色调统一，光影自然真实，材质细节清晰，沉浸式展现使用体验，文字清晰准确无错漏。");
        taobaoPrompts.put("图4", "信任细节图・打消决策顾虑\n" +
                "核心逻辑：细节特写举证 + 售后保障兜底 + 消除决策疑虑\n" +
                "plaintext\n" +
                "电商产品细节信任主图，正方形1440×1440像素。画面采用四格均匀分栏布局，分别展示四个核心产品细节，每个分栏配对应说明文案：\n" +
                "1. 左上：【细节1特写：材质/工艺】，配文【对应优势说明】\n" +
                "2. 右上：【细节2特写：功能/结构】，配文【对应优势说明】\n" +
                "3. 左下：【细节3特写：设计/配件】，配文【对应优势说明】\n" +
                "4. 右下：【细节4特写：便携/收纳】，配文【对应优势说明】\n" +
                "画面顶部居中放置加粗主标题文案：【信任类总结话术】，底部添加一行小字文案：7天无理由退换 赠运费险。\n" +
                "背景为纯净浅灰色，每个分栏干净有序，细节清晰锐利，光影均匀柔和，整体呈现专业可靠的质感，所有文字字体统一、清晰准确、无错字");
        taobaoPrompts.put("图5", "纯白底图・推荐流量入口\n" +
                "核心逻辑：严格符合平台规范 + 算法精准识别 + 获取免费推荐流量\n" +
                "注：此图为流量合规刚需，全程无任何文字、装饰，是所有博主共识的统一标准\n" +
                "plaintext\n" +
                "电商纯白底产品图，正方形1440×1440像素，严格符合电商平台首页推荐流量准入标准。【商品全称】完整居中放置，正面微侧视角，完整展现产品外观轮廓，占据画面80%左右空间。\n" +
                "纯纯白色背景（RGB 255,255,255），完全干净无任何杂质，无投影、无倒影、无拼接、无任何文字、logo与装饰元素。\n" +
                "专业无影棚布光，光影均匀柔和，产品外观、材质纹理清晰可见，颜色还原真实，边缘锐利干净。");
        platforms.put("taobao", taobaoPrompts);

        for (String p : List.of("douyin", "shopee")) {
            Map<String, String> prompts = new LinkedHashMap<>();
            for (int i = 1; i <= 5; i++) {
                prompts.put("图" + i, "");
            }
            platforms.put(p, prompts);
        }
        return platforms;
    }
}