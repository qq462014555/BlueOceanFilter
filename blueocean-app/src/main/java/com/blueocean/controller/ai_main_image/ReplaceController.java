package com.blueocean.controller.ai_main_image;

import com.blueocean.service.ReplaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/ai-image")
public class ReplaceController {

    private final ReplaceService replaceService;

    public ReplaceController(ReplaceService replaceService) {
        this.replaceService = replaceService;
    }

    @PostMapping("/replace")
    public ResponseEntity<Map<String, Object>> replaceImages(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked") List<String> images = (List<String>) request.get("images");
        @SuppressWarnings("unchecked") List<String> prompts = (List<String>) request.get("prompts");
        if (productDir == null || images == null || images.isEmpty()) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        String model = (String) request.get("model");
        if (model == null || model.isEmpty()) model = "black-forest-labs/FLUX.1-schnell";
        return ResponseEntity.ok(replaceService.generateReplacements(productDir, images, prompts, model));
    }

    @GetMapping("/list-replace-images")
    public ResponseEntity<Map<String, Object>> listReplaceImages(@RequestParam String productDir) {
        return ResponseEntity.ok(Map.of("success", true, "images", replaceService.listReplaceImages(productDir)));
    }
}
