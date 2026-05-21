package com.blueocean.service;

import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.blueocean.ExcelWriter;
import com.blueocean.config.AppProperties;
import com.blueocean.entity.FilterTask;
import com.blueocean.entity.KeywordHistory;
import com.blueocean.entity.KeywordResult;
import com.blueocean.mapper.FilterTaskMapper;
import com.blueocean.mapper.KeywordHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Service
public class KeywordProcessingService {

    private static final Logger log = LoggerFactory.getLogger(KeywordProcessingService.class);

    private final DashScopeClient dashScopeClient;
    private final KeywordHistoryMapper historyMapper;
    private final FilterTaskMapper taskMapper;
    private final ExcelReader excelReader;
    private final ExcelWriter excelWriter;
    private final AppProperties appProperties;
    private final EmailNotificationService emailNotificationService;

    public KeywordProcessingService(DashScopeClient dashScopeClient,
                                    KeywordHistoryMapper historyMapper,
                                    FilterTaskMapper taskMapper,
                                    ExcelReader excelReader,
                                    ExcelWriter excelWriter,
                                    AppProperties appProperties,
                                    EmailNotificationService emailNotificationService) {
        this.dashScopeClient = dashScopeClient;
        this.historyMapper = historyMapper;
        this.taskMapper = taskMapper;
        this.excelReader = excelReader;
        this.excelWriter = excelWriter;
        this.appProperties = appProperties;
        this.emailNotificationService = emailNotificationService;
    }

    @Async("taskExecutor")
    public CompletableFuture<Void> processFile(byte[] fileBytes, String filename, Long taskId, String model, boolean enableSearch) {
        return CompletableFuture.runAsync(() -> processSync(fileBytes, filename, taskId, model, enableSearch));
    }

    private void processSync(byte[] fileBytes, String filename, Long taskId, String model, boolean enableSearch) {
        FilterTask task = taskMapper.selectById(taskId);
        Path tempFile = null;
        try {
            task.setStatus("RUNNING");
            task.setCreateTime(LocalDateTime.now());
            taskMapper.updateById(task);

            // 1. Save uploaded file to temp
            tempFile = Files.createTempFile("blueocean_", ".xlsx");
            Files.copy(new ByteArrayInputStream(fileBytes), tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 2. Read keywords
            log.info("Reading keywords from uploaded file");
            List<String> keywords = excelReader.readKeywordsFromXlsx(tempFile.toString());
            task.setTotalWords(keywords.size());
            taskMapper.updateById(task);
            log.info("Read {} keywords", keywords.size());

            // 3. Load history keywords
            log.info("Loading history keywords...");
            List<KeywordHistory> historyList = historyMapper.selectList(null);
            Set<String> historySet = historyList.stream()
                    .map(KeywordHistory::getWord)
                    .collect(Collectors.toCollection(HashSet::new));
            log.info("Loaded {} history keywords", historySet.size());

            // 4. 先去重 Excel 内部重复
            List<KeywordResult> excluded = new ArrayList<>();
            Set<String> seenInFile = new HashSet<>();
            int excelDupCount = 0;
            List<String> distinctKeywords = new ArrayList<>();
            for (String word : keywords) {
                if (!seenInFile.add(word)) {
                    excelDupCount++;
                    excluded.add(new KeywordResult(word, false, "Excel 内重复"));
                } else {
                    distinctKeywords.add(word);
                }
            }
            if (excelDupCount > 0) {
                log.info("Excel 内部重复 {} 个，已排除", excelDupCount);
            }

            // 5. 再与历史词库比对
            Map<String, LocalDateTime> historyTimeMap = new HashMap<>();
            for (KeywordHistory kh : historyList) {
                historyTimeMap.put(kh.getWord(), kh.getCreateTime());
            }

            List<String> newWords = new ArrayList<>();
            for (String word : distinctKeywords) {
                if (historySet.contains(word)) {
                    LocalDateTime dbTime = historyTimeMap.get(word);
                    String timeStr = dbTime != null ? dbTime.toString() : "未知时间";
                    excluded.add(new KeywordResult(word, false, "历史重复（" + timeStr + "）"));
                    log.info("历史重复: [{}] 数据库中存在时间: {}", word, timeStr);
                } else {
                    newWords.add(word);
                }
            }

            log.info("Excel 内重复 {} 个，历史重复 {} 个，待AI筛选 {} 个",
                    excelDupCount, excluded.size() - excelDupCount, newWords.size());

            // 5. Batch filter only new words (并发 10 线程)
            List<KeywordResult> aiResults = new ArrayList<>();
            if (!newWords.isEmpty()) {
                List<List<String>> batches = partition(newWords, appProperties.getBatchSize());
                log.info("待筛选词分 {} 批处理，并发 10 线程", batches.size());

                int concurrency = 10;
                ExecutorService batchExecutor = Executors.newFixedThreadPool(concurrency);
                Semaphore semaphore = new Semaphore(concurrency);

                // 收集失败的批次词
                List<String> failedWords = Collections.synchronizedList(new ArrayList<>());

                List<CompletableFuture<List<KeywordResult>>> futures = new ArrayList<>();

                for (int i = 0; i < batches.size(); i++) {
                    final int batchIndex = i;
                    final List<String> batch = batches.get(i);
                    semaphore.acquire();

                    CompletableFuture<List<KeywordResult>> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            log.info("Processing batch {}/{} ({} words)", batchIndex + 1, batches.size(), batch.size());
                            List<KeywordResult> results = null;
                            for (int retry = 1; retry <= appProperties.getRetryTimes(); retry++) {
                                try {
                                    results = dashScopeClient.filterBatchHttp(batch, model, enableSearch);
                                    // 收集缺失的词（输入了但模型没返回的）
                                    Set<String> returnedWords = results.stream()
                                            .map(KeywordResult::getWord)
                                            .collect(Collectors.toSet());
                                    List<String> missing = new ArrayList<>();
                                    for (String w : batch) {
                                        if (!returnedWords.contains(w)) {
                                            missing.add(w);
                                        }
                                    }
                                    if (!missing.isEmpty()) {
                                        log.warn("Batch {} 输入{}词，返回{}结果，缺失{}词: {}",
                                                batchIndex + 1, batch.size(), results.size(), missing.size(), missing);
                                        failedWords.addAll(missing);
                                    }
                                    for (KeywordResult r : results) {
                                        if (r.isKeep()) {
                                            log.info("合规: [{}] 原因: {}", r.getWord(), r.getReason());
                                        } else {
                                            log.info("剔除: [{}] 原因: {}", r.getWord(), r.getReason());
                                        }
                                    }
                                    return results;
                                } catch (Exception e) {
                                    log.warn("Batch {} retry {}: {}", batchIndex + 1, retry, e.getMessage());
                                    if (retry < appProperties.getRetryTimes()) {
                                        Thread.sleep(2000L * retry);
                                    }
                                }
                            }
                            // 重试全部失败，整个批次都加入失败汇总
                            log.warn("Batch {} 重试全部失败，{}词加入失败汇总", batchIndex + 1, batch.size());
                            failedWords.addAll(batch);
                            return Collections.<KeywordResult>emptyList();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return Collections.emptyList();
                        } finally {
                            semaphore.release();
                        }
                    }, batchExecutor);

                    futures.add(future);
                }

                // 等待所有批次完成
                CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                allDone.join();

                // 收集成功结果
                for (CompletableFuture<List<KeywordResult>> future : futures) {
                    aiResults.addAll(future.join());
                }

                batchExecutor.shutdown();

                // 如果有失败的词，汇总成一批再次批量请求
                if (!failedWords.isEmpty()) {
                    log.warn("=== 共 {} 个词批次失败，汇总重新批量请求 ===", failedWords.size());
                    // 失败词可能很多，按 batch_size 分成若干批
                    List<List<String>> retryBatches = partition(failedWords, appProperties.getBatchSize());
                    for (int i = 0; i < retryBatches.size(); i++) {
                        List<String> retryBatch = retryBatches.get(i);
                        log.info("失败词汇总重试 {}/{} ({} words)", i + 1, retryBatches.size(), retryBatch.size());
                        try {
                            List<KeywordResult> retryResults = dashScopeClient.filterBatchHttp(retryBatch, model, enableSearch);
                            aiResults.addAll(retryResults);
                            log.info("失败词汇总重试 {}/{} 返回 {} 条结果", i + 1, retryBatches.size(), retryResults.size());
                            for (KeywordResult r : retryResults) {
                                if (r.isKeep()) {
                                    log.info("合规: [{}] 原因: {}", r.getWord(), r.getReason());
                                } else {
                                    log.info("剔除: [{}] 原因: {}", r.getWord(), r.getReason());
                                }
                            }
                        } catch (Exception e) {
                            // 如果再次失败，这批词标记为待人工复查
                            log.error("失败词汇总重试 {}/{} 再次失败，标记为待人工复查: {}", i + 1, retryBatches.size(), e.getMessage());
                            for (String w : retryBatch) {
                                aiResults.add(new KeywordResult(w, true, "API调用失败，待人工复查"));
                            }
                        }
                        if (i < retryBatches.size() - 1) {
                            Thread.sleep(appProperties.getSleepMs());
                        }
                    }
                }
                task.setProcessedWords(aiResults.size());
                taskMapper.updateById(task);
            } else {
                log.info("所有词均为历史库中已有词，无需调用AI");
                task.setProcessedWords(excluded.size());
                taskMapper.updateById(task);
            }

            // 6. Separate AI results into kept and excluded
            List<KeywordResult> newlyKept = new ArrayList<>();

            for (KeywordResult r : aiResults) {
                if (r.isKeep()) {
                    newlyKept.add(r);
                    historySet.add(r.getWord());
                } else {
                    excluded.add(r);
                }
            }

            // Print AI kept words
            log.info("=== AI筛选合规词（共{}个） ===", newlyKept.size());
            for (KeywordResult r : newlyKept) {
                log.info("合规: [{}] 原因: {}", r.getWord(), r.getReason());
            }

            // 7. Save ALL words to DB with status and reason
            List<KeywordHistory> allToSave = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();

            // Newly kept words
            for (KeywordResult r : newlyKept) {
                KeywordHistory kh = new KeywordHistory();
                kh.setWord(r.getWord());
                kh.setStatus("合规");
                kh.setReason(r.getReason());
                kh.setUsageCount(1);
                kh.setCreateTime(now);
                kh.setUpdateTime(now);
                allToSave.add(kh);
            }

            // AI excluded words
            for (KeywordResult r : aiResults) {
                if (!r.isKeep()) {
                    KeywordHistory kh = new KeywordHistory();
                    kh.setWord(r.getWord());
                    kh.setStatus("剔除");
                    kh.setReason(r.getReason());
                    kh.setUsageCount(1);
                    kh.setCreateTime(now);
                    kh.setUpdateTime(now);
                    allToSave.add(kh);
                }
            }

            // Already duplicate words
            for (KeywordResult r : excluded) {
                if (r.getReason() != null && r.getReason().startsWith("数据重复")) {
                    KeywordHistory kh = new KeywordHistory();
                    kh.setWord(r.getWord());
                    kh.setStatus("数据重复");
                    kh.setReason(r.getReason());
                    kh.setUsageCount(1);
                    kh.setCreateTime(now);
                    kh.setUpdateTime(now);
                    allToSave.add(kh);
                }
            }

            if (!allToSave.isEmpty()) {
                log.info("Saving {} keywords to database", allToSave.size());
                for (int i = 0; i < allToSave.size(); i += 500) {
                    historyMapper.batchUpsert(allToSave.subList(i, Math.min(i + 500, allToSave.size())));
                }
            }

            // 8. Ensure output directory exists
            Files.createDirectories(Paths.get(appProperties.getOutputDir()));

            // 9. Generate Excel files
            log.info("=== 排除词列表（共{}个） ===", excluded.size());
            for (KeywordResult r : excluded) {
                log.info("排除: [{}] 原因: {}", r.getWord(), r.getReason());
            }

            String dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy_MM_dd"));
            String keptFileName = dateStr + "符合蓝海词.xlsx";
            String excludedFileName = dateStr + "剔除蓝海词.xlsx";
            String keptPath = appProperties.getOutputDir() + "/" + keptFileName;
            String excludedPath = appProperties.getOutputDir() + "/" + excludedFileName;

            excelWriter.writeKeptFile(newlyKept, keptPath);
            excelWriter.writeExcludedFile(excluded, excludedPath);

            // 9. Update task as completed
            task.setStatus("COMPLETED");
            task.setKeptFileName(keptFileName);
            task.setExcludedFileName(excludedFileName);
            task.setFinishTime(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("Task {} completed: {} new, {} excluded", taskId, newlyKept.size(), excluded.size());
            emailNotificationService.sendNotification("蓝海词筛选完成",
                    String.format("任务 %d 已完成\n合规词: %d 个\n剔除词: %d 个", taskId, newlyKept.size(), excluded.size()));

        } catch (Exception e) {
            log.error("Task {} failed", taskId, e);
            task.setStatus("FAILED");
            task.setFinishTime(LocalDateTime.now());
            taskMapper.updateById(task);
            emailNotificationService.sendNotification("蓝海词筛选失败",
                    String.format("任务 %d 失败\n错误信息: %s", taskId, e.getMessage()));
        } finally {
            // Cleanup temp file
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    private KeywordResult filterSingleWord(String word, String model, boolean enableSearch) {
        for (int retry = 1; retry <= appProperties.getRetryTimes(); retry++) {
            try {
                List<KeywordResult> results = dashScopeClient.filterBatchHttp(List.of(word), model, enableSearch);
                if (!results.isEmpty()) {
                    log.info("逐词重试成功: [{}]", word);
                    return results.get(0);
                }
            } catch (Exception e) {
                log.warn("逐词重试 [{}] {}/{}: {}", word, retry, appProperties.getRetryTimes(), e.getMessage());
                if (retry < appProperties.getRetryTimes()) {
                    try { Thread.sleep(2000L * retry); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        log.warn("逐词重试全部失败: [{}]", word);
        return new KeywordResult(word, true, "API调用失败，待人工复查");
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
