package com.blueocean.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.blueocean.config.AppProperties;
import com.blueocean.entity.KeywordResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Service
public class DashScopeClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);

    private static String SYSTEM_PROMPT;

    static {
        try {
            SYSTEM_PROMPT = new String(DashScopeClient.class.getClassLoader()
                    .getResourceAsStream("prompts/system-prompt.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load system prompt file", e);
        }
    }

    private static final String DASHSCOPE_CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    // 可用模型常量
    public static final String MODEL_QWEN3_6_PLUS = "qwen3.6-plus";
    public static final String MODEL_QWEN3_MAX = "qwen3-max";
    public static final String MODEL_QWEN_PLUS = "qwen-plus";
    public static final String MODEL_QWEN_TURBO = "qwen-turbo";

    public static final String DEFAULT_MODEL = MODEL_QWEN3_6_PLUS;

    private final String apiKey;
    private final HttpClient httpClient;

    public DashScopeClient(AppProperties appProperties) {
        this.apiKey = appProperties.getDashscopeApiKey();
        this.httpClient = HttpClient.newHttpClient();
    }

    private static final String CHAT_SYSTEM_PROMPT = "你是一个 helpful 的 AI 助手。请用简洁清晰的语言回答问题。";

    /**
     * 通用对话方法，直接与模型交互
     * @param systemPrompt 系统提示词，可为 null 使用通用聊天默认值
     * @param userMessage 用户消息
     * @return 模型返回的原始文本
     */
    public String chat(String systemPrompt, String userMessage, String model) throws IOException, InterruptedException, ExecutionException {
        return chat(systemPrompt, userMessage, model, null);
    }

    public String chatWithHistory(String systemPrompt, String userMessage, String model, int[] usageResult, String historyJson, boolean enableSearch) throws IOException, InterruptedException {
        String sysPrompt = (systemPrompt != null && !systemPrompt.isEmpty()) ? systemPrompt : CHAT_SYSTEM_PROMPT;
        String useModel = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
        log.info("[对话] 模型={}, 联网={}, 用户消息={}", useModel, enableSearch ? "是" : "否", userMessage);
        log.info("[对话] 系统提示词={}", (systemPrompt != null && !systemPrompt.isEmpty()) ? systemPrompt.substring(0, Math.min(100, systemPrompt.length())) + (systemPrompt.length() > 100 ? "..." : "") : "默认");

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", sysPrompt);
        messages.add(sysMsg);

        // 追加历史消息
        if (historyJson != null && !historyJson.isEmpty()) {
            try {
                JSONArray history = JSON.parseArray(historyJson);
                for (int i = 0; i < history.size(); i++) {
                    JSONObject h = history.getJSONObject(i);
                    String role = h.getString("role");
                    String content = h.getString("content");
                    JSONObject msg = new JSONObject();
                    msg.put("role", "user".equals(role) ? "user" : "assistant");
                    msg.put("content", content);
                    messages.add(msg);
                }
            } catch (Exception e) {
                log.warn("[对话] 解析历史消息失败: {}", e.getMessage());
            }
        }

        // 添加当前用户消息
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", useModel);
        body.put("messages", messages);
        body.put("enable_search", enableSearch);
        log.info("[对话] 完整请求体:\n{}", body.toJSONString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_CHAT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JSONObject respJson = JSON.parseObject(response.body());
        log.info("[对话] HTTP 状态码={}", response.statusCode());

        if (response.statusCode() != 200) {
            String errMsg = respJson != null ? respJson.getString("message") : "未知错误";
            log.error("[对话] API 错误: {}", errMsg);
            throw new IOException("API 调用失败: " + errMsg);
        }

        // Extract usage if available
        if (usageResult != null && usageResult.length >= 3) {
            JSONObject usage = respJson.getJSONObject("usage");
            if (usage != null) {
                usageResult[0] = usage.getInteger("prompt_tokens");
                usageResult[1] = usage.getInteger("completion_tokens");
                usageResult[2] = usage.getInteger("total_tokens");
            }
        }

        String content = respJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        log.info("[对话] 完整回复={}", content);
        return content.trim();
    }

    public String chat(String systemPrompt, String userMessage, String model, int[] usageResult) throws IOException, InterruptedException, ExecutionException {
        return chat(systemPrompt, userMessage, model, usageResult, false);
    }

    public String chat(String systemPrompt, String userMessage, String model, int[] usageResult, boolean enableSearch) throws IOException, InterruptedException, ExecutionException {
        String sysPrompt = (systemPrompt != null && !systemPrompt.isEmpty()) ? systemPrompt : CHAT_SYSTEM_PROMPT;
        String useModel = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
        log.info("[对话] 模型={}, 联网={}, 用户消息={}", useModel, enableSearch ? "是" : "否", userMessage);

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", sysPrompt);
        messages.add(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", useModel);
        body.put("messages", messages);
        body.put("enable_search", enableSearch);
        log.debug("[对话] 请求体={}", body.toJSONString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_CHAT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        // 使用异步请求，支持中断
        java.util.concurrent.CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = future.get();
        if (future.isCancelled()) {
            throw new IOException("请求已被取消");
        }

        JSONObject respJson = JSON.parseObject(response.body());
        log.info("[对话] HTTP 状态码={}", response.statusCode());

        if (response.statusCode() != 200) {
            String errMsg = respJson != null ? respJson.getString("message") : "未知错误";
            log.error("[对话] API 错误: {}", errMsg);
            throw new IOException("API 调用失败: " + errMsg);
        }

        // Extract usage if available
        if (usageResult != null && usageResult.length >= 3) {
            JSONObject usage = respJson.getJSONObject("usage");
            if (usage != null) {
                usageResult[0] = usage.getInteger("prompt_tokens");
                usageResult[1] = usage.getInteger("completion_tokens");
                usageResult[2] = usage.getInteger("total_tokens");
            }
        }

        String content = respJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        log.info("[对话] 完整回复={}", content);
        return content.trim();
    }

    /**
     * 流式对话，通过 SseEmitter 实时推送 token
     */
    public void chatStream(String systemPrompt, String userMessage, String model, SseEmitter emitter) {
        String sysPrompt = (systemPrompt != null && !systemPrompt.isEmpty()) ? systemPrompt : CHAT_SYSTEM_PROMPT;
        String useModel = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
        log.info("[流式对话] 模型={}, 用户消息={}", useModel, userMessage);

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", sysPrompt);
        messages.add(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", useModel);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("enable_search", false);
        log.debug("[流式对话] 请求体={}", body.toJSONString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_CHAT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-SSE", "enable")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    log.info("[流式对话] HTTP 状态码={}", response.statusCode());
                    if (response.statusCode() != 200) {
                        String errorBody = String.join("\n", response.body().toList());
                        log.error("[流式对话] API 错误响应: {}", errorBody);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(errorBody));
                            emitter.complete();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                        return;
                    }
                    StringBuilder fullReply = new StringBuilder();
                    response.body().forEach(line -> {
                        log.debug("[流式对话] 原始行={}", line);
                        if (!line.startsWith("data:")) return;
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            log.info("[流式对话] 收到 [DONE]，完整回复={}", fullReply.toString());
                            emitter.complete();
                            return;
                        }
                        try {
                            JSONObject chunk = JSON.parseObject(data);
                            // 最后一个 chunk 包含 usage 信息
                            JSONObject usage = chunk.getJSONObject("usage");
                            if (usage != null) {
                                JSONObject usageMsg = new JSONObject();
                                usageMsg.put("type", "usage");
                                usageMsg.put("prompt_tokens", usage.getInteger("prompt_tokens"));
                                usageMsg.put("completion_tokens", usage.getInteger("completion_tokens"));
                                usageMsg.put("total_tokens", usage.getInteger("total_tokens"));
                                try {
                                    emitter.send(SseEmitter.event().name("usage").data(usageMsg.toJSONString()));
                                } catch (IOException e) {
                                    emitter.completeWithError(e);
                                }
                                return;
                            }
                            JSONArray choices = chunk.getJSONArray("choices");
                            if (choices != null && !choices.isEmpty()) {
                                String delta = choices.getJSONObject(0)
                                        .getJSONObject("delta")
                                        .getString("content");
                                if (delta != null && !delta.isEmpty()) {
                                    fullReply.append(delta);
                                    emitter.send(SseEmitter.event().data(delta));
                                }
                            }
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    });
                })
                .exceptionally(ex -> {
                    log.error("[流式对话] 异步异常", ex);
                    emitter.completeWithError(ex);
                    return null;
                });

        emitter.onCompletion(() -> log.info("[流式对话] 连接关闭"));
        emitter.onTimeout(() -> log.warn("[流式对话] 超时"));
        emitter.onError(throwable -> log.error("[流式对话] 错误", throwable));
    }

    public List<KeywordResult> filterBatch(List<String> words) throws IOException, InterruptedException, NoApiKeyException, InputRequiredException {
        String wordList = String.join("\n", words);

        Generation gen = new Generation();
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(SYSTEM_PROMPT)
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content("请筛选以下蓝海词，严格按规则判断每一个：\n" + wordList)
                .build();
        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(MODEL_QWEN3_6_PLUS)
                .messages(Arrays.asList(systemMsg, userMsg))
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();

        String responseBody = gen.call(param).getOutput().getChoices().get(0).getMessage().getContent();

        // 容错：去除可能的 markdown 代码块标记
        responseBody = responseBody.trim();
        if (responseBody.startsWith("```")) {
            int firstNewline = responseBody.indexOf('\n');
            responseBody = responseBody.substring(firstNewline + 1);
            if (responseBody.endsWith("```")) {
                responseBody = responseBody.substring(0, responseBody.length() - 3);
            }
        }
        responseBody = responseBody.trim();

        JSONArray resp = JSON.parseArray(responseBody);

        List<KeywordResult> results = new ArrayList<>();
        for (int i = 0; i < resp.size(); i++) {
            JSONObject obj = resp.getJSONObject(i);
            KeywordResult kr = new KeywordResult();
            kr.setWord(obj.getString("word"));
            kr.setKeep(obj.getBooleanValue("keep"));
            kr.setReason(obj.getString("reason"));
            results.add(kr);
        }
        return results;
    }

    /**
     * HTTP 方式批量筛选蓝海词（与 chat 调用方式一致）
     */
    public List<KeywordResult> filterBatchHttp(List<String> words, String model, boolean enableSearch) throws IOException, InterruptedException {
        String useModel = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
        String wordList = String.join("\n", words);
        log.info("[批量筛选-HTTP] 模型={}, 联网={}, 词数={}", useModel, enableSearch ? "是" : "否", words.size());

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", SYSTEM_PROMPT);
        messages.add(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", "请筛选以下蓝海词，严格按规则判断每一个：\n" + wordList);
        messages.add(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", useModel);
        body.put("messages", messages);
        body.put("enable_search", enableSearch);
        log.debug("[批量筛选-HTTP] 请求体长度={}", body.toJSONString().length());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DASHSCOPE_CHAT_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JSONObject respJson = JSON.parseObject(response.body());
        log.info("[批量筛选-HTTP] HTTP 状态码={}", response.statusCode());

        if (response.statusCode() != 200) {
            String errMsg = respJson != null ? respJson.getString("message") : "未知错误";
            log.error("[批量筛选-HTTP] API 错误: {}", errMsg);
            throw new IOException("API 调用失败: " + errMsg);
        }

        String responseBody = respJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();

        // 容错：去除可能的 markdown 代码块标记
        if (responseBody.startsWith("```")) {
            int firstNewline = responseBody.indexOf('\n');
            responseBody = responseBody.substring(firstNewline + 1);
            if (responseBody.endsWith("```")) {
                responseBody = responseBody.substring(0, responseBody.length() - 3);
            }
        }
        responseBody = responseBody.trim();

        // 先尝试解析，再格式化打印
        Object parsed = JSON.parse(responseBody);
        String formatted = parsed instanceof JSONArray
                ? ((JSONArray) parsed).toJSONString(JSONWriter.Feature.PrettyFormat)
                : responseBody;
        log.info("[批量筛选-HTTP] 模型原始输出:\n{}", formatted);

        JSONArray resp = (JSONArray) parsed;

        List<KeywordResult> results = new ArrayList<>();
        Set<String> seenWords = new HashSet<>();
        for (int i = 0; i < resp.size(); i++) {
            JSONObject obj = resp.getJSONObject(i);
            String word = obj.getString("word");
            // 跳过重复项
            if (seenWords.contains(word)) continue;
            seenWords.add(word);
            KeywordResult kr = new KeywordResult();
            kr.setWord(word);
            kr.setKeep(obj.getBooleanValue("keep"));
            kr.setReason(obj.getString("reason"));
            results.add(kr);
        }
        log.info("[批量筛选-HTTP] 返回 {} 条结果（去重后 {} 条）", resp.size(), results.size());

        if (results.size() != words.size()) {
            log.warn("[批量筛选-HTTP] 结果数({})与输入词数({})不匹配，返回已有结果", results.size(), words.size());
            // 不再抛异常，返回已有结果，由调用方收集缺失的词
        }
        return results;
    }
}
