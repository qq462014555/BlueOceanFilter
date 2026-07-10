package com.blueocean.controller.ai_main_image;
import com.blueocean.controller.AiImageController;

import com.blueocean.service.OpenRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 替换图生成与管理
 */
@RestController
@RequestMapping("/api/ai-image")
public class ReplaceController {

    private static final Logger log = LoggerFactory.getLogger(ReplaceController.class);
    private final OpenRouterService openRouterService;

    public ReplaceController(OpenRouterService openRouterService) {
        this.openRouterService = openRouterService;
    }

    /**
     * 替换图生成：以白底图为参考，将用户上传图替换到场景中
     */
    @PostMapping("/replace")
    public ResponseEntity<Map<String, Object>> replaceImages(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked") List<String> images = (List<String>) request.get("images");
        @SuppressWarnings("unchecked") List<String> prompts = (List<String>) request.get("prompts");
        if (productDir == null || images == null || images.isEmpty()) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        String model = (String) request.get("model");
        if (model == null || model.isEmpty()) model = "black-forest-labs/FLUX.1-schnell";
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();

        for (int i = 0; i < images.size(); i++) {
            String userImg = images.get(i);
            String extraPrompt = (prompts != null && i < prompts.size()) ? prompts.get(i) : "";
            try {
                Path inputDir = Paths.get(productDir, "替换图_输入"); Files.createDirectories(inputDir);
                String userFileName = String.format("input_%02d.jpg", i + 1);
                Path userFile = inputDir.resolve(userFileName);
                String imgData = userImg.contains(",") ? userImg.substring(userImg.indexOf(',') + 1) : userImg;
                Files.write(userFile, java.util.Base64.getDecoder().decode(imgData));

                String prompt = "请将白底图中的产品替换到场景图中，注意尽可能的保持产品的完整外形特征。";
                if (!extraPrompt.isEmpty()) prompt += "\n用户补充说明：" + extraPrompt;

                // 参考图
                List<String> refs = new ArrayList<>();
                String baseUrl = "http://127.0.0.1:8080";
                for (String p : openRouterService.stitchDirToFiles(Paths.get(productDir, "白底图"), "替换_白底图参考.jpg", 3)) {
                    refs.add(baseUrl + "/api/ai-image/image-file?path=" + java.net.URLEncoder.encode(p, java.nio.charset.StandardCharsets.UTF_8));
                }
                try {
                    imgData = userImg.contains(",") ? userImg.substring(userImg.indexOf(',') + 1) : userImg;
                    byte[] imgBytes = java.util.Base64.getDecoder().decode(imgData);
                    Path tdir = Paths.get(System.getProperty("java.io.tmpdir"), "ai-ref", "replace"); Files.createDirectories(tdir);
                    Path tf = tdir.resolve("user_" + i + ".jpg");
                    javax.imageio.ImageIO.write(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imgBytes)), "jpg", tf.toFile());
                    refs.add(baseUrl + "/api/ai-image/image-file?path=" + java.net.URLEncoder.encode(tf.toString(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ex) { refs.add(userImg); }
                if (refs.size() > 4) refs = new ArrayList<>(refs.subList(0, 4));

                String savedPath = openRouterService.generateImageWithRefs(model, prompt, Map.of(), productDir, "replace_gen", i + 1, 1, refs.isEmpty() ? null : refs);
                Path outputDir = Paths.get(productDir, "替换图"); Files.createDirectories(outputDir);
                Path outPath = outputDir.resolve(String.format("替换图_%02d.jpg", i + 1));
                Path tempPath = Paths.get(savedPath);
                if (Files.exists(tempPath)) Files.copy(tempPath, outPath, StandardCopyOption.REPLACE_EXISTING);

                String similarWarning = checkImageSimilarity(outPath.toString(), userImg);
                Map<String, Object> resultItem = new LinkedHashMap<>();
                resultItem.put("key", "替换图" + (i + 1)); resultItem.put("path", outPath.toString()); resultItem.put("success", true);
                if (similarWarning != null) resultItem.put("warning", similarWarning);
                results.add(resultItem);
            } catch (Exception e) {
                log.error("替换图{}生成失败: {}", i + 1, e.getMessage());
                errors.add(Map.of("key", "替换图" + (i + 1), "error", e.getMessage()));
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("results", results); result.put("errors", errors);
        result.put("total", results.size() + errors.size()); result.put("succeeded", results.size()); result.put("failed", errors.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/list-replace-images")
    public ResponseEntity<Map<String, Object>> listReplaceImages(@RequestParam String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        Path replaceDir = Paths.get(productDir, "替换图");
        if (Files.exists(replaceDir)) {
            try (var files = Files.list(replaceDir)) {
    /**
     * 列出已有替换图
     */
                files.filter(f -> { String n = f.toString().toLowerCase(); return n.endsWith(".jpg") || n.endsWith(".png"); }).sorted().forEach(f -> {
                    Map<String, Object> item = new LinkedHashMap<>(); item.put("path", f.toString()); item.put("name", f.getFileName().toString()); images.add(item);
                });
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("success", true, "images", images));
    }

    private String checkImageSimilarity(String newImagePath, String originalBase64) {
        try {
            Path newPath = Paths.get(newImagePath);
            if (!Files.exists(newPath)) return "生成图片不存在";
            long newSize = Files.size(newPath);
            String origData = originalBase64.contains(",") ? originalBase64.substring(originalBase64.indexOf(',') + 1) : originalBase64;
            byte[] origBytes = java.util.Base64.getDecoder().decode(origData);
            if (newSize > origBytes.length * 1.5) return null;
            if (newSize < origBytes.length * 0.3) return "生成图片与原图差异较大，建议检查";
            return null;
        } catch (Exception e) { return "检查失败"; }
    }
}
