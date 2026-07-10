package com.blueocean.controller.ai_main_image;

import com.blueocean.service.AnalysisService;
import com.blueocean.service.OpenRouterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 主图生成管理：提示词管理 + 提示词优化
 */
@RestController
@RequestMapping("/api/ai-image")
public class MainImageController {

    private final AnalysisService analysisService;
    private final OpenRouterService openRouterService;

    public MainImageController(AnalysisService analysisService, OpenRouterService openRouterService) {
        this.analysisService = analysisService;
        this.openRouterService = openRouterService;
    }

    /** 获取所有平台的提示词模板 */
    @GetMapping("/prompts")
    public ResponseEntity<Map<String, Object>> getPrompts() {
        return ResponseEntity.ok(Map.of("platforms", analysisService.loadPrompts(), "models", openRouterService.getSupportedModels()));
    }

    /** 保存提示词模板 */
    @PostMapping("/prompts")
    public ResponseEntity<Map<String, Object>> savePrompts(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked") Map<String, Map<String, String>> platforms = (Map<String, Map<String, String>>) request.get("platforms");
        if (platforms == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 platforms"));
        analysisService.savePrompts(platforms);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 智能优化单条提示词 */
    @PostMapping("/optimize-prompt")
    public ResponseEntity<Map<String, Object>> optimizePrompt(@RequestBody Map<String, Object> request) {
        String originalPrompt = (String) request.get("prompt");
        @SuppressWarnings("unchecked") Map<String, String> analysis = (Map<String, String>) request.get("analysis");
        if (originalPrompt == null || analysis == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        try {
            String optimized = analysisService.optimizePrompt(originalPrompt, analysis);
            return ResponseEntity.ok(Map.of("success", true, "optimizedPrompt", optimized));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
