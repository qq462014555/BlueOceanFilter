package com.blueocean.controller.ai_main_image;

import com.blueocean.service.AnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * AI 商品分析 + 提示词管理
 */
@RestController
@RequestMapping("/api/ai-image")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /** AI 商品分析：调通义千问分析商品生成提示词，有缓存直接返回 */
    @PostMapping("/auto-generate-prompts")
    public ResponseEntity<Map<String, Object>> autoGeneratePrompts(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir");
        String platform = (String) request.get("platform");
        boolean forceNew = Boolean.TRUE.equals(request.get("forceNew"));
        if (productDir == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少 productDir"));
        return ResponseEntity.ok(analysisService.analyzeProduct(productDir, platform, forceNew));
    }

    /** 检查分析结果缓存文件是否存在（前端轮询用） */
    @GetMapping("/analysis-done")
    public ResponseEntity<Map<String, Object>> isAnalysisDone(@RequestParam String productDir, @RequestParam String platform) {
        return ResponseEntity.ok(Map.of("exists", analysisService.isAnalysisDone(productDir, platform)));
    }


    /** 获取任务状态 */
    @GetMapping("/task-status")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@RequestParam String productDir, @RequestParam String task) {
        return ResponseEntity.ok(Map.of("success", true, "status", analysisService.getTaskStatus(productDir, task)));
    }

    /** 设置任务状态 */
    @PostMapping("/task-status")
    public ResponseEntity<Map<String, Object>> setTaskStatus(@RequestBody Map<String, Object> request) {
        String productDir = (String) request.get("productDir"); String task = (String) request.get("task"); String status = (String) request.get("status");
        if (productDir == null || task == null || status == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "缺少参数"));
        analysisService.setTaskStatus(productDir, task, status);
        return ResponseEntity.ok(Map.of("success", true));
    }

}
