package com.blueocean.controller.ai_main_image;

import com.blueocean.controller.AiImageController;
import com.blueocean.service.OpenRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/ai-image")
public class WhiteBgController {

    private static final Logger log = LoggerFactory.getLogger(WhiteBgController.class);
    private final OpenRouterService openRouterService;

    @org.springframework.beans.factory.annotation.Value("${app.public-url:}")
    private String publicUrl;

    public WhiteBgController(OpenRouterService openRouterService) {
        this.openRouterService = openRouterService;
    }

    @PostMapping("/generate-white-bg")
    public ResponseEntity<Map<String, Object>> generateWhiteBg(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        boolean force = Boolean.TRUE.equals(request.get("force"));
        if (productDir == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 productDir"));
        List<Map<String, Object>> images = new ArrayList<>();
        Path whiteBgDir = Paths.get(productDir, "白底图");
        if (!force && Files.exists(whiteBgDir)) {
            try (var files = Files.list(whiteBgDir)) {
                files.filter(f -> f.toString().toLowerCase().endsWith(".jpg") || f.toString().toLowerCase().endsWith(".png")).sorted().forEach(f -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("path", f.toString()); item.put("name", f.getFileName().toString()); images.add(item);
                });
            } catch (Exception ignored) {}
        }
        if (!images.isEmpty()) return ResponseEntity.ok(Map.of("success", true, "images", images, "fromCache", true));

        AiImageController.TASK_STATUS.computeIfAbsent(productDir, k -> new ConcurrentHashMap<>()).put("whitebg", "running");
        try {
            List<String> refImages = new ArrayList<>();
            String baseUrl2 = publicUrl != null && !publicUrl.isEmpty() ? publicUrl : "http://127.0.0.1:8080";
            for (String path : openRouterService.stitchDirToFiles(Paths.get(productDir, "主图"), "白底图_主图参考.jpg", 1))
                refImages.add(baseUrl2 + "/api/ai-image/image-file?path=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));
            for (String path : openRouterService.stitchDirToFiles(Paths.get(productDir, "详情图"), "白底图_详情参考.jpg", 2))
                refImages.add(baseUrl2 + "/api/ai-image/image-file?path=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8));

            String baseP = "生成图都保留产品原有的Logo和印刷文字不变，禁止额外添加促销文案、卖点说明、价格、水印、装饰。白底淡影。";
            List<String> refsArg = refImages.isEmpty() ? null : refImages;
            CompletableFuture<String> cf1 = CompletableFuture.supplyAsync(() -> {
                try { return openRouterService.generateImageRaw("openai/gpt-image-2", baseP + "第1张是 只展示产品正面,以45°斜前方单一视角。", productDir, "whitebg_temp", 1, 1, refsArg); }
                catch (Exception e) { log.warn("白底图1失败: {}", e.getMessage()); return null; }
            });
            CompletableFuture<String> cf2 = CompletableFuture.supplyAsync(() -> {
                try { return openRouterService.generateImageRaw("openai/gpt-image-2", baseP + "第2张是正面、侧面、背面三个视角横向排列的组合图。", productDir, "whitebg_temp", 2, 1, refsArg); }
                catch (Exception e) { log.warn("白底图2失败: {}", e.getMessage()); return null; }
            });
            String g1 = cf1.get(); if (g1 != null) { Path fp1 = Paths.get(g1); if (Files.exists(fp1)) { images.add(Map.of("path", fp1.toString(), "name", "白底图_正面45度.jpg")); } }
            String g2 = cf2.get(); if (g2 != null) { Path fp2 = Paths.get(g2); if (Files.exists(fp2)) { images.add(Map.of("path", fp2.toString(), "name", "白底图_多角度组合.jpg")); } }
            AiImageController.TASK_STATUS.computeIfAbsent(productDir, k -> new ConcurrentHashMap<>()).put("whitebg", "completed");
        } catch (Exception e) {
            AiImageController.TASK_STATUS.computeIfAbsent(productDir, k -> new ConcurrentHashMap<>()).put("whitebg", "failed");
            log.error("生成白底图失败", e);
            return ResponseEntity.ok(Map.of("success", false, "error", "生成失败: " + e.getMessage(), "images", images));
        }
        return ResponseEntity.ok(Map.of("success", true, "images", images, "fromCache", false));
    }

    @GetMapping("/list-whitebg-images")
    public ResponseEntity<Map<String, Object>> listWhiteBgImages(@RequestParam String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        Path dir = Paths.get(productDir, "白底图");
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                files.filter(f -> f.toString().toLowerCase().endsWith(".jpg") || f.toString().toLowerCase().endsWith(".png")).sorted().forEach(f -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("path", f.toString()); item.put("name", f.getFileName().toString()); images.add(item);
                });
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("success", true, "images", images));
    }

    @PostMapping("/upload-white-bg")
    public ResponseEntity<Map<String, Object>> uploadWhiteBg(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        String imageData = (String) request.get("image");
        if (productDir == null || imageData == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        try {
            Path whiteBgDir = Paths.get(productDir, "白底图"); Files.createDirectories(whiteBgDir);
            byte[] bytes;
            if (imageData.startsWith("http://") || imageData.startsWith("https://")) {
                var url = new java.net.URL(imageData);
                try (var in = url.openStream()) { bytes = in.readAllBytes(); }
            } else {
                String b64 = imageData.contains(",") ? imageData.substring(imageData.indexOf(",") + 1) : imageData;
                bytes = java.util.Base64.getDecoder().decode(b64);
            }
            long count = Files.list(whiteBgDir).filter(f -> f.toString().toLowerCase().endsWith(".jpg")).count();
            Path target = whiteBgDir.resolve(String.format("白底图_%02d.jpg", count + 1));
            Files.write(target, bytes);
            return ResponseEntity.ok(Map.of("success", true, "path", target.toString(), "name", target.getFileName().toString()));
        } catch (Exception e) { log.error("白底图上传失败", e); return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage())); }
    }
}
