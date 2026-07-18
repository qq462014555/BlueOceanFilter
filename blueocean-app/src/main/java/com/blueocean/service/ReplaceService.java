package com.blueocean.service;

import com.blueocean.controller.AiImageController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReplaceService {

    private static final Logger log = LoggerFactory.getLogger(ReplaceService.class);

    @org.springframework.beans.factory.annotation.Value("${app.public-url:http://127.0.0.1:8080}")
    private String baseUrl;

    private static final Map<String, List<String>> REPLACE_IMAGE_CACHE = new ConcurrentHashMap<>();
    private final OpenRouterService openRouterService;

    public ReplaceService(OpenRouterService openRouterService) {
        this.openRouterService = openRouterService;
    }

    public Map<String, Object> generateReplacements(String productDir, List<String> images, List<String> prompts, String model, List<String> selectedWhiteBg) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        AiImageController.TASK_STATUS.computeIfAbsent(productDir, k -> new ConcurrentHashMap<>()).put("replace", "running");
        for (int i = 0; i < images.size(); i++) {
            String userImg = images.get(i);
            String extraPrompt = (prompts != null && i < prompts.size()) ? prompts.get(i) : "";
            try {
                String prompt = "请将白底图中的产品替换到场景图中，注意尽可能的保持产品的完整外形特征。";
                if (!extraPrompt.isEmpty()) prompt += "\n用户补充说明：" + extraPrompt;
                List<String> refs = new ArrayList<>();
                if (selectedWhiteBg != null && !selectedWhiteBg.isEmpty()) refs.addAll(selectedWhiteBg);
                else {
                    for (String p : openRouterService.stitchDirToFiles(Paths.get(productDir, "白底图"), "替换_白底图参考.jpg", 3))
                        refs.add(baseUrl + "/api/ai-image/image-file?path=" + java.net.URLEncoder.encode(p, StandardCharsets.UTF_8));
                }
                try {
                    Path tdir = Paths.get(System.getProperty("java.io.tmpdir"), "ai-ref", "replace"); Files.createDirectories(tdir);
                    Path tf = tdir.resolve("user_" + i + ".jpg");
                    byte[] ib = readImageBytes(userImg);
                    Files.write(tf, ib);
                    refs.add(baseUrl + "/api/ai-image/image-file?path=" + java.net.URLEncoder.encode(tf.toString(), StandardCharsets.UTF_8));
                } catch (Exception ex) { refs.add(userImg); }
                if (refs.size() > 4) refs = new ArrayList<>(refs.subList(0, 4));
                String savedPath = openRouterService.generateImageWithRefs(model, prompt, Map.of(), productDir, "replace_gen", i + 1, 1, refs.isEmpty() ? null : refs);
                Path outputDir = Paths.get(productDir, "替换图"); Files.createDirectories(outputDir);
                Path outPath = outputDir.resolve(String.format("替换图_%02d.jpg", i + 1));
                if (Files.exists(Paths.get(savedPath))) Files.copy(Paths.get(savedPath), outPath, StandardCopyOption.REPLACE_EXISTING);
                String similarWarning = checkImageSimilarity(outPath.toString(), userImg);
                Map<String, Object> ri = new LinkedHashMap<>();
                ri.put("key", "替换图" + (i + 1)); ri.put("path", outPath.toString()); ri.put("success", true);
                if (similarWarning != null) ri.put("warning", similarWarning);
                results.add(ri);
            } catch (Exception e) {
                log.error("替换图{}生成失败: {}", i + 1, e.getMessage());
                errors.add(Map.of("key", "替换图" + (i + 1), "error", e.getMessage()));
            }
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { break; }
        }
        AiImageController.TASK_STATUS.computeIfAbsent(productDir, k -> new ConcurrentHashMap<>()).put("replace", "completed");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true); result.put("results", results); result.put("errors", errors);
        result.put("total", results.size() + errors.size()); result.put("succeeded", results.size()); result.put("failed", errors.size());
        return result;
    }

    public void saveReplaceImages(String productDir, List<String> images) {
        REPLACE_IMAGE_CACHE.put(productDir, new ArrayList<>(images));
    }

    public List<String> getReplaceImages(String productDir) {
        List<String> cached = REPLACE_IMAGE_CACHE.get(productDir);
        return cached != null ? new ArrayList<>(cached) : new ArrayList<>();
    }

    public void clearReplaceImages(String productDir) {
        REPLACE_IMAGE_CACHE.remove(productDir);
    }

    public List<Map<String, Object>> listReplaceImages(String productDir) {
        List<Map<String, Object>> images = new ArrayList<>();
        Path dir = Paths.get(productDir, "替换图");
        if (Files.exists(dir)) {
            try (var files = Files.list(dir)) {
                files.filter(f -> f.toString().toLowerCase().endsWith(".jpg") || f.toString().toLowerCase().endsWith(".png"))
                    .sorted().forEach(f -> { Map<String, Object> item = new LinkedHashMap<>(); item.put("path", f.toString()); item.put("name", f.getFileName().toString()); images.add(item); });
            } catch (Exception ignored) {}
        }
        return images;
    }

    private byte[] readImageBytes(String imageData) throws Exception {
        if (imageData.startsWith("http://") || imageData.startsWith("https://")) {
            var url = new URL(imageData);
            try (var in = url.openStream()) { return in.readAllBytes(); }
        } else {
            String b64 = imageData.contains(",") ? imageData.substring(imageData.indexOf(",") + 1) : imageData;
            return java.util.Base64.getDecoder().decode(b64);
        }
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
