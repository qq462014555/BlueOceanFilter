package com.blueocean.controller.ai_main_image;

import com.blueocean.service.ReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/ai-image")
public class ReferenceController {

    private static final Logger log = LoggerFactory.getLogger(ReferenceController.class);

    private final ReferenceService referenceService;

    public ReferenceController(ReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @PostMapping("/save-ref-cache")
    public ResponseEntity<Map<String, Object>> saveRefCache(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new LinkedHashMap<>();
        String productDir = (String) request.get("productDir");
        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) request.get("images");
        if (productDir == null || images == null) {
            result.put("success", false);
            result.put("error", "缺少参数");
            return ResponseEntity.badRequest().body(result);
        }
        referenceService.saveRefImages(productDir, images);
        result.put("success", true);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/get-ref-cache")
    public ResponseEntity<Map<String, Object>> getRefCache(@RequestParam String productDir) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> images = referenceService.getRefImages(productDir);
        result.put("success", true);
        result.put("images", images);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/list-ref-images")
    public ResponseEntity<Map<String, Object>> listRefImages(@RequestParam String productDir) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> images = referenceService.listRefImages(productDir);
        result.put("success", true);
        result.put("images", images);
        return ResponseEntity.ok(result);
    }
}
