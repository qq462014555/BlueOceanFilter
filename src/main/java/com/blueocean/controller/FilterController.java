package com.blueocean.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.blueocean.entity.FilterTask;
import com.blueocean.entity.KeywordHistory;
import com.blueocean.entity.TrendKeyword;
import com.blueocean.controller.TrendWordInfo;
import com.blueocean.mapper.FilterTaskMapper;
import com.blueocean.mapper.KeywordHistoryMapper;
import com.blueocean.mapper.TrendKeywordMapper;
import com.blueocean.service.DashScopeClient;
import com.blueocean.service.KeywordProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/filter")
@Slf4j
public class FilterController {

    private final KeywordProcessingService processingService;
    private final FilterTaskMapper taskMapper;
    private final KeywordHistoryMapper historyMapper;
    private final DashScopeClient dashScopeClient;
    private final TrendKeywordMapper trendKeywordMapper;

    public FilterController(KeywordProcessingService processingService,
                            FilterTaskMapper taskMapper,
                            KeywordHistoryMapper historyMapper,
                            DashScopeClient dashScopeClient,
                            TrendKeywordMapper trendKeywordMapper) {
        this.processingService = processingService;
        this.taskMapper = taskMapper;
        this.historyMapper = historyMapper;
        this.dashScopeClient = dashScopeClient;
        this.trendKeywordMapper = trendKeywordMapper;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "model", defaultValue = "qwen3.6-plus") String model,
            @RequestParam(value = "enableSearch", defaultValue = "false") boolean enableSearch) {
        Map<String, Object> result = new HashMap<>();

        if (file.isEmpty()) {
            result.put("error", "文件不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".xlsx")) {
            result.put("error", "请上传 .xlsx 格式的文件");
            return ResponseEntity.badRequest().body(result);
        }

        // Create task record
        FilterTask task = new FilterTask();
        task.setStatus("PENDING");
        task.setOriginalFileName(filename);
        task.setTotalWords(0);
        task.setProcessedWords(0);
        taskMapper.insert(task);

        // Read file bytes before async processing (Tomcat cleans up temp files after request ends)
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            result.put("error", "文件读取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }

        // Start async processing
        processingService.processFile(fileBytes, filename, task.getId(), model, enableSearch);

        result.put("taskId", task.getId());
        result.put("message", "任务已创建，开始处理");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable Long taskId) {
        Map<String, Object> result = new HashMap<>();
        FilterTask task = taskMapper.selectById(taskId);

        if (task == null) {
            result.put("error", "任务不存在");
            return ResponseEntity.notFound().build();
        }

        result.put("taskId", task.getId());
        result.put("status", task.getStatus());
        result.put("totalWords", task.getTotalWords());
        result.put("processedWords", task.getProcessedWords());
        result.put("originalFileName", task.getOriginalFileName());
        result.put("createTime", task.getCreateTime());

        if ("COMPLETED".equals(task.getStatus())) {
            result.put("keptFileName", task.getKeptFileName());
            result.put("excludedFileName", task.getExcludedFileName());
            result.put("finishTime", task.getFinishTime());
        }

        if ("FAILED".equals(task.getStatus())) {
            result.put("finishTime", task.getFinishTime());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/download/{taskId}/{type}")
    public ResponseEntity<Resource> download(@PathVariable Long taskId,
                                              @PathVariable String type) {
        FilterTask task = taskMapper.selectById(taskId);
        if (task == null || !"COMPLETED".equals(task.getStatus())) {
            return ResponseEntity.notFound().build();
        }

        String fileName = "kept".equals(type) ? task.getKeptFileName() : task.getExcludedFileName();
        if (fileName == null) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource("output/" + fileName);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }


        String downloadName = "kept".equals(type) ? "合规蓝海词.xlsx" : "剔除蓝海词.xlsx";
        String encodedName = java.net.URLEncoder.encode(downloadName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(resource);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> result = new HashMap<>();

        Page<KeywordHistory> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<KeywordHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(KeywordHistory::getCreateTime);
        Page<KeywordHistory> resultPage = historyMapper.selectPage(pageObj, wrapper);

        result.put("total", resultPage.getTotal());
        result.put("page", resultPage.getCurrent());
        result.put("size", resultPage.getSize());
        result.put("records", resultPage.getRecords());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        String message = (String) request.get("message");
        String systemPrompt = (String) request.get("systemPrompt");
        String model = (String) request.get("model");
        boolean enableSearch = Boolean.TRUE.equals(request.get("enableSearch"));
        Object historyObj = request.get("history");

        String historyJson = null;
        if (historyObj instanceof java.util.List) {
            historyJson = JSON.toJSONString(historyObj);
        } else if (historyObj != null) {
            historyJson = historyObj.toString();
        }

        if (message == null || message.isEmpty()) {
            result.put("error", "消息不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        long startTime = System.currentTimeMillis();
        int[] usage = new int[3];

        try {
            String reply = dashScopeClient.chatWithHistory(systemPrompt, message, model, usage, historyJson, enableSearch);
            long elapsed = System.currentTimeMillis() - startTime;

            result.put("reply", reply);
            result.put("model", model != null ? model : DashScopeClient.DEFAULT_MODEL);
            result.put("elapsed", elapsed);
            if (usage[0] > 0) {
                result.put("prompt_tokens", usage[0]);
                result.put("completion_tokens", usage[1]);
                result.put("total_tokens", usage[2]);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", "AI 调用失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String systemPrompt = request.get("systemPrompt");
        String model = request.get("model");

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        if (message == null || message.isEmpty()) {
            try { emitter.send(SseEmitter.event().name("error").data("消息不能为空")); }
            catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }

        dashScopeClient.chatStream(systemPrompt, message, model, emitter);
        return emitter;
    }

    @GetMapping("/trend/prompt")
    public ResponseEntity<Map<String, Object>> trendPrompt() {
        Map<String, Object> result = new HashMap<>();
        try {
            String prompt = new String(getClass().getClassLoader()
                    .getResourceAsStream("prompts/trend-prompt.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            result.put("prompt", prompt);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", "读取提示词失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @GetMapping("/prompts/{name}")
    public ResponseEntity<Map<String, Object>> getPrompt(@PathVariable String name) {
        Map<String, Object> result = new HashMap<>();
        try {
            String prompt = new String(getClass().getClassLoader()
                    .getResourceAsStream("prompts/" + name + ".txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            result.put("prompt", prompt);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", "读取提示词失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    @PostMapping("/trend/mine")
    public ResponseEntity<Map<String, Object>> trendMine(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        String systemPrompt = (String) request.get("systemPrompt");
        String model = (String) request.get("model");
        boolean enableSearch = Boolean.TRUE.equals(request.get("enableSearch"));

        if (systemPrompt == null || systemPrompt.isEmpty()) {
            result.put("error", "系统提示词不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        long startTime = System.currentTimeMillis();

        try {
            // 1. 调用大模型生成趋势词
            String reply = dashScopeClient.chat(null, systemPrompt, model, null, enableSearch);

            // 2. 解析返回内容，提取带备注的趋势词
            List<TrendWordInfo> aiWords = parseTrendWordsWithMeta(reply);
            if (aiWords.isEmpty()) {
                result.put("error", "模型未返回有效的词列表");
                return ResponseEntity.badRequest().body(result);
            }

            // 3. 加载趋势词表已有词，去重
            List<TrendKeyword> trendList = trendKeywordMapper.selectList(null);
            Set<String> trendSet = new HashSet<>();
            for (TrendKeyword tk : trendList) {
                trendSet.add(tk.getWord());
            }

            List<TrendWordInfo> newWords = new ArrayList<>();
            int existingCount = 0;
            for (TrendWordInfo info : aiWords) {
                if (!trendSet.contains(info.word)) {
                    newWords.add(info);
                    trendSet.add(info.word);
                } else {
                    existingCount++;
                }
            }

            // 4. 新词批量入库（trend_keywords 表）
            if (!newWords.isEmpty()) {
                LocalDateTime now = LocalDateTime.now();
                List<TrendKeyword> toSave = new ArrayList<>();
                for (TrendWordInfo info : newWords) {
                    TrendKeyword tk = new TrendKeyword();
                    tk.setWord(info.word);
                    tk.setUsageCount(info.usageCount);
                    tk.setBurstMonths(info.burstMonths);
                    tk.setLayoutMonths(info.layoutMonths);
                    tk.setRemark(info.remark);
                    tk.setCreateTime(now);
                    tk.setUpdateTime(now);
                    toSave.add(tk);
                }
                for (int i = 0; i < toSave.size(); i += 500) {
                    trendKeywordMapper.batchUpsert(toSave.subList(i, Math.min(i + 500, toSave.size())));
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;

            // 5. 返回带 usage_count 的词列表
            List<TrendKeyword> allTrendWords = trendKeywordMapper.selectList(null);
            Map<String, Integer> usageMap = new HashMap<>();
            Map<String, String> remarkMap = new HashMap<>();
            Map<String, String> burstMap = new HashMap<>();
            Map<String, String> layoutMap = new HashMap<>();
            for (TrendKeyword tk : allTrendWords) {
                usageMap.put(tk.getWord(), tk.getUsageCount());
                if (tk.getBurstMonths() != null) burstMap.put(tk.getWord(), tk.getBurstMonths());
                if (tk.getLayoutMonths() != null) layoutMap.put(tk.getWord(), tk.getLayoutMonths());
                if (tk.getRemark() != null) remarkMap.put(tk.getWord(), tk.getRemark());
            }

            List<Map<String, Object>> wordList = new ArrayList<>();
            for (TrendWordInfo info : newWords) {
                Map<String, Object> obj = new HashMap<>();
                obj.put("word", info.word);
                obj.put("usageCount", usageMap.getOrDefault(info.word, 1));
                obj.put("burstMonths", burstMap.getOrDefault(info.word, info.burstMonths));
                obj.put("layoutMonths", layoutMap.getOrDefault(info.word, info.layoutMonths));
                obj.put("remark", remarkMap.getOrDefault(info.word, info.remark));
                wordList.add(obj);
            }

            result.put("total", aiWords.size());
            result.put("newCount", newWords.size());
            result.put("existingCount", existingCount);
            result.put("newWords", wordList);
            result.put("elapsed", elapsed);
            result.put("model", model != null ? model : DashScopeClient.DEFAULT_MODEL);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            result.put("error", "趋势挖掘失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 解析大模型返回内容，提取词列表
     * 支持 JSON 数组和逐行文本格式
     */
    private List<String> parseWordList(String content) {
        List<String> words = new ArrayList<>();
        if (content == null || content.isEmpty()) return words;

        content = content.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            content = content.substring(firstNewline + 1);
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3).trim();
            }
        }

        // 尝试 JSON 数组
        if (content.startsWith("[")) {
            try {
                JSONArray arr = JSON.parseArray(content);
                for (int i = 0; i < arr.size(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof String) {
                        String w = ((String) item).trim();
                        if (!w.isEmpty()) words.add(w);
                    } else if (item instanceof JSONObject) {
                        String w = ((JSONObject) item).getString("word");
                        if (w != null && !w.isEmpty()) words.add(w);
                    }
                }
            } catch (Exception e) {
                log.warn("解析趋势词 JSON 失败，尝试逐行解析", e);
            }
        }

        // JSON 解析失败，回退到逐行文本
        if (words.isEmpty()) {
            for (String line : content.split("\\n")) {
                String trimmed = line.trim().replaceAll("^[\\d.\\-\\s*]+", "").trim();
                if (!trimmed.isEmpty()) {
                    // 按空格拆分（例如 "露营 天幕 防风 加固 地钉" → 多个独立词）
                    for (String part : trimmed.split("\\s+")) {
                        if (!part.isEmpty()) {
                            words.add(part);
                        }
                    }
                }
            }
        }

        // 去重
        words = new ArrayList<>(words.stream().distinct().toList());
        return words;
    }

    /**
     * 解析带备注的趋势词，格式：
     * 长尾词>分割词1,分割词2 |爆发日：X月X日 布局日：提前X天 备注：简短理由
     * 也兼容旧格式（无 | 分隔的情况）
     */
    private List<TrendWordInfo> parseTrendWordsWithMeta(String content) {
        List<TrendWordInfo> result = new ArrayList<>();
        if (content == null || content.isEmpty()) return result;

        content = content.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            content = content.substring(firstNewline + 1);
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3).trim();
            }
        }

        for (String line : content.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            String wordPart = trimmed;
            String metaPart = "";

            // 用 | 分隔词部分和元数据部分
            int pipeIdx = trimmed.indexOf('|');
            if (pipeIdx >= 0) {
                wordPart = trimmed.substring(0, pipeIdx).trim();
                metaPart = trimmed.substring(pipeIdx + 1).trim();
            }

            String burstMonths = null;
            String layoutMonths = null;
            String remark = null;

            // 从元数据部分提取
            int burstIdx = metaPart.indexOf("爆发日：");
            int burstMonthIdx = metaPart.indexOf("爆发月：");
            int layoutIdx = metaPart.indexOf("布局日：");
            int layoutMonthIdx = metaPart.indexOf("布局月：");
            int remarkIdx = metaPart.indexOf("备注：");

            int effectiveBurstIdx = burstIdx >= 0 ? burstIdx : (burstMonthIdx >= 0 ? burstMonthIdx : -1);
            int effectiveLayoutIdx = layoutIdx >= 0 ? layoutIdx : (layoutMonthIdx >= 0 ? layoutMonthIdx : -1);

            if (effectiveBurstIdx >= 0) {
                String burstKey = burstIdx >= 0 ? "爆发日：" : "爆发月：";
                int burstEnd = effectiveLayoutIdx >= 0 ? effectiveLayoutIdx : (remarkIdx >= 0 ? remarkIdx : metaPart.length());
                burstMonths = metaPart.substring(effectiveBurstIdx + burstKey.length(), burstEnd).trim();

                if (effectiveLayoutIdx >= 0) {
                    String layoutKey = layoutIdx >= 0 ? "布局日：" : "布局月：";
                    int layoutEnd = remarkIdx >= 0 ? remarkIdx : metaPart.length();
                    layoutMonths = metaPart.substring(effectiveLayoutIdx + layoutKey.length(), layoutEnd).trim();
                }
                if (remarkIdx >= 0) {
                    remark = metaPart.substring(remarkIdx + 3).trim();
                }
            } else if (effectiveLayoutIdx >= 0) {
                String layoutKey = layoutIdx >= 0 ? "布局日：" : "布局月：";
                int layoutEnd = remarkIdx >= 0 ? remarkIdx : metaPart.length();
                layoutMonths = metaPart.substring(effectiveLayoutIdx + layoutKey.length(), layoutEnd).trim();
                if (remarkIdx >= 0) {
                    remark = metaPart.substring(remarkIdx + 3).trim();
                }
            }

            // 解析词部分：长尾词>分割词1,分割词2
            if (wordPart.contains(">")) {
                String[] arrowParts = wordPart.split(">", 2);
                String longTail = arrowParts[0].trim();
                if (arrowParts.length > 1) {
                    String[] subWords = arrowParts[1].split("/");
                    for (String sw : subWords) {
                        String trimmedSw = sw.trim();
                        if (!trimmedSw.isEmpty()) {
                            result.add(new TrendWordInfo(trimmedSw, burstMonths, layoutMonths, remark));
                        }
                    }
                }
                // 长尾词也入库
                if (!longTail.isEmpty()) {
                    result.add(new TrendWordInfo(longTail, burstMonths, layoutMonths, remark));
                }
            } else {
                String word = wordPart.trim();
                if (!word.isEmpty()) {
                    result.add(new TrendWordInfo(word, burstMonths, layoutMonths, remark));
                }
            }
        }

        // 去重（按 word 去重，重复词累加 usage_count）
        Map<String, TrendWordInfo> wordMap = new LinkedHashMap<>();
        for (TrendWordInfo info : result) {
            if (wordMap.containsKey(info.word)) {
                TrendWordInfo existing = wordMap.get(info.word);
                existing.burstMonths = info.burstMonths != null ? info.burstMonths : existing.burstMonths;
                existing.layoutMonths = info.layoutMonths != null ? info.layoutMonths : existing.layoutMonths;
                existing.remark = info.remark != null ? info.remark : existing.remark;
            } else {
                wordMap.put(info.word, info);
            }
        }
        List<TrendWordInfo> deduped = new ArrayList<>(wordMap.values());

        // 统计每个词在本轮 AI 返回中的出现次数
        Map<String, Integer> aiCountMap = new HashMap<>();
        for (TrendWordInfo info : result) {
            aiCountMap.merge(info.word, 1, Integer::sum);
        }
        for (TrendWordInfo info : deduped) {
            info.usageCount = aiCountMap.getOrDefault(info.word, 1);
        }
        return deduped;
    }
}
