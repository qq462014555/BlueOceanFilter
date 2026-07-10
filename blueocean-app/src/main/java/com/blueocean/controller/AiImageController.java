package com.blueocean.controller;

import com.blueocean.service.OpenRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Base64;

/**
 * AI 主图重绘 — 图片生成 + 文件服务（通用功能）
 */
@RestController
@RequestMapping("/api/ai-image")
public class AiImageController {

    private static final Logger log = LoggerFactory.getLogger(AiImageController.class);
    private final OpenRouterService openRouterService;

    // 全局任务状态（被 AnalysisController/WhiteBgController/ReplaceController 共享）
    public static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, String>> TASK_STATUS = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${app.public-url:}")
    private String publicUrl;

    public AiImageController(OpenRouterService openRouterService) {
        this.openRouterService = openRouterService;
    }

    // ==================== 单张生成 ====================

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateImage(@RequestBody Map<String, Object> request) {
        String model = (String) request.get("model");
        String prompt = (String) request.get("prompt");
        String productDir = (String) request.get("productDir");
        String platform = (String) request.get("platform");
        int imageIndex = Integer.parseInt((String) request.getOrDefault("imageIndex", "1"));
        @SuppressWarnings("unchecked") Map<String, String> allPrompts = (Map<String, String>) request.get("allPrompts");
        if (model == null || productDir == null || platform == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少必要参数"));
        }
        try {
            String savedPath = openRouterService.generateImage(model, prompt, allPrompts, productDir, platform, imageIndex, 1);
            return ResponseEntity.ok(Map.of("success", true, "path", savedPath));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ==================== 批量生成 ====================

    @PostMapping("/generate-all")
    public ResponseEntity<Map<String, Object>> generateAll(@RequestBody Map<String, Object> request) {
        String model = (String) request.get("model");
        String prompt = (String) request.get("prompt");
        String productDir = (String) request.get("productDir");
        String platform = (String) request.get("platform");
        @SuppressWarnings("unchecked") Map<String, String> allPrompts = (Map<String, String>) request.get("allPrompts");
        if (model == null || productDir == null || platform == null || allPrompts == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少必要参数"));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        String baseUrl = publicUrl != null && !publicUrl.isEmpty() ? publicUrl : "http://127.0.0.1:8080";
        List<String> refImages = new ArrayList<>();
        Path whiteBgDir = Paths.get(productDir, "白底图");
        if (Files.exists(whiteBgDir)) {
            try {
                Path stitchedPath = openRouterService.stitchWhiteBgToFile(whiteBgDir, "whitebg_stitched.jpg");
                if (stitchedPath != null) {
                    String encoded = java.net.URLEncoder.encode(stitchedPath.toString(), java.nio.charset.StandardCharsets.UTF_8);
                    refImages.add(baseUrl + "/api/ai-image/image-file?path=" + encoded);
                }
            } catch (Exception e) { log.warn("白底图拼接失败: {}", e.getMessage()); }
        }
        for (Map.Entry<String, String> entry : allPrompts.entrySet()) {
            String key = entry.getKey();
            String singlePrompt = entry.getValue();
            if (singlePrompt == null || singlePrompt.trim().isEmpty()) continue;
            int imageIndex = Integer.parseInt(key.replace("图", ""));
            String singleCombined = key + "：\n" + singlePrompt;
            Map<String, String> singlePromptMap = Map.of(key, singlePrompt);
            try {
                String savedPath = openRouterService.generateImageWithRefs(model, singleCombined, singlePromptMap, productDir, platform, imageIndex, 1, refImages.isEmpty() ? null : refImages);
                results.add(Map.of("key", key, "path", savedPath, "success", true));
            } catch (Exception e) {
                log.error("生成{}失败: {}", key, e.getMessage());
                errors.add(Map.of("key", key, "error", e.getMessage()));
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("results", results); result.put("errors", errors);
        result.put("total", results.size() + errors.size()); result.put("succeeded", results.size()); result.put("failed", errors.size());
        return ResponseEntity.ok(result);
    }

    // ==================== 图片文件服务 ====================

    @GetMapping("/image-file")
    public ResponseEntity<byte[]> getImageFile(@RequestParam String path) {
        try {
            Path file = Paths.get(path);
            if (!Files.exists(file)) return ResponseEntity.notFound().build();
            byte[] data = Files.readAllBytes(file);
            String contentType = path.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok().header("Content-Type", contentType).body(data);
        } catch (IOException e) { return ResponseEntity.badRequest().build(); }
    }

    @GetMapping("/ref/{id}")
    public ResponseEntity<byte[]> getRefImage(@PathVariable int id) {
        try {
            Path file = Paths.get(System.getProperty("java.io.tmpdir"), "ai-ref", id + ".jpg");
            if (!Files.exists(file)) return ResponseEntity.notFound().build();
            return ResponseEntity.ok().header("Content-Type", "image/jpeg").body(Files.readAllBytes(file));
        } catch (IOException e) { return ResponseEntity.badRequest().build(); }
    }

    // ==================== 已生成图片列表 ====================

    @GetMapping("/list-images")
    public ResponseEntity<Map<String, Object>> listAiImages(@RequestParam String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        for (String platform : List.of("taobao", "douyin", "shopee")) {
            Path dir = Paths.get(productDir, "AI重绘图", platform);
            if (Files.exists(dir)) {
                try (var files = Files.list(dir)) {
                    files.filter(f -> { String n = f.toString().toLowerCase(); return n.endsWith(".jpg") || n.endsWith(".png"); })
                        .sorted().forEach(f -> { Map<String, Object> item = new LinkedHashMap<>(); item.put("path", f.toString()); item.put("name", f.getFileName().toString()); item.put("platform", platform); images.add(item); });
                } catch (Exception ignored) {}
            }
        }
        return ResponseEntity.ok(Map.of("images", images));
    }

    // ==================== 文件删除 ====================

    @PostMapping("/delete-file")
    public ResponseEntity<Map<String, Object>> deleteFile(@RequestBody Map<String, Object> request) {
        String path = (String) request.get("path");
        if (path == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 path"));
        try {
            Path file = Paths.get(path);
            if (Files.exists(file)) { Files.delete(file); log.info("删除成功: {}", file); return ResponseEntity.ok(Map.of("success", true)); }
            return ResponseEntity.ok(Map.of("success", false, "error", "文件不存在"));
        } catch (Exception e) { return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage())); }
    }
}
