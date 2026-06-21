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

    private static final Set<String> EXCLUDED_TERMS = new HashSet<>(Set.of(
            "配件大全", "散粉", "煤气罐", "挂钩", "黑椒", "正品", "原著", "护核纪元", "短靴", "皮鞋", "高级感", "酶", "卫生巾", "华为", "全套", "nars", "拉拉裤", "棉服", "牙膏", "背包", "小米", "桶12升", "肥皂", "运动套装", "紧身裤", "套装", "肥牛", "拌饭", "插件", "饭预制菜", "小皮鞋", "凉拖", "染发剂", "高跟鞋", "q币", "csgo", "cdk", "椰子汁", "上册", "话费", "女裤", "瑜伽服", "长袖", "体恤", "通勤鞋", "孕妇装", "棉袄", "羽绒服", "丝巾", "辣椒",
            "品牌店", "警辅", "短剧", "电子书", "网课", "公开课", "服装", "百亿补贴", "配件配大全", "背背佳", "手表", "读物", "电子烟", "卡套", "唇膏", "口红", "惜玥", "医疗", "火机", "佳能", "苹果",
            "贝德美", "五金", "会员", "搜索词", "工作服", "转接头", "透挂", "原神", "牛奶", "耳机", "月饼", "闲鱼", "暗区突围", "欧莱雅", "注册", "月卡", "扭蛋机", "胖东来", "木柯诗", "迅游"
            , "女装", "男装", "穿搭", "衣", "针织衫", "裙子", "耳钉", "短裤", "围巾", "冰汽时代", "p图", "酵素", "冷冻", "鸡爪", "兑换码", "代充", "批发", "ios", "网易", "自抽号"
            , "红包", "猫粮", "假发", "膏", "优惠券", "靴子", "蜂蜜", "胶", "团购", "童装", "游戏", "动漫", "绝地求生", "吃鸡", "和平精英", "pubg"
            , "蛋馆", "洗发水", "氯", "酒", "卤料", "皮裤", "母子装", "亲子装", "童裤", "眼皮贴"
            , "头盔", "申请书", "布鲁可积木人", "代笔", "女鞋", "50", "ccd", "白ti", "漱口水", "爽文", "咖啡液", "马甲", "帽子", "零食", "外搭"
            , "标垫", "奥克文", "鲜活", "樱禾", "超软小羊", "我可能错了", "透专用", "摩享时光", "壹丝幻享", "胜利大师", "抄写", "江南织造局", "凤水", "酥饼"
            , "背心", "蚓蚯", "运动鞋", "热水袋", "充电宝", "雨析棚", "冻干", "螺蛳粉", "婴儿"
            , "生活馆", "旗舰店", "球拍","同款","保鲜剂","蝴蝶刀","南京","洗脸巾"
            ,"现发","胸贴","腐蚀","定制","广西","广东","2026年","2026","2024新款", "300", "元的", "刷题册", "块钱", "痔疮", "战靴", "21", "三元", "书包", "鱼缸", "会元", "ps5", "手办", "凑满", "5折", "8折", "磁玉", "储存卡", "水乳", "口服液", "和平精号", "手游", "三角洲行动", "读书币", "7折", "一次性", "跑鞋", "便秘", "鸡心果", "鲜果", "题库"

            /**违禁*/, "钢珠", "卡密", "必备", "盗听器", "飞行棋成人", "毛主",
            /**性别*/"男款", "女款", "男士", "女士", "女式", "男式"
            /**装饰*/, "耳饰", "发箍", "项链", "自行车", "配饰"
            /**范围词*/, "以上", "商品", "途鸭"
            /**药*/, "药", "头孢", "减脂","不锈钢"
            /**医疗*/,
            /**衣服，鞋*/"卫裤", "睡被", "瑜伽裤", "睡衣", "内衣", "鞋子", "舞鞋", "长靴", "男鞋", "家纺", "礼服", "衫女", "衫男", "鞋女", "鞋男", "衬衫", "内裤", "外套", "牛仔裤", "裙", "裤子", "内搭", "t恤", "丁字裤", "打底衫", "秋装", "工装裤", "分裤", "包包", "袜子", "裤女", "裤男", "汉服",
            /**虚拟产品*/"卡10", "卡100", "卡20", "卡200", "卡880", "卡30", "20元", "vip", "充值"
            /**服务*/, "微信", "退货包", "运费险", "软件", "先享后付", "凑单", "免费", "脚本"
            /**杂物*/, "抽纸", "洗衣液", "雅思", "电子版", "真题"
            /**母婴*/, "母婴", "奶嘴", "宝宝"
            /**食物*/, "意大利面", "曲粉", "豆腐", "香醋", "食品", "橄榄油", "海苔", "方便面", "牛堡", "花生", "主食", "辣酱", "酸奶", "料包", "奶粉", "蛋白粉", "酸菜鱼", "麦片", "酱板"
            /**电器*/, "电器", "电视机", "吸尘器", "电锅", "投影仪", "学习机", "充电线"
            /**化妆品*/, "粉扑", "遮暇", "遮瑕", "粉饼", "粉底", "防晒", "气垫", "化妆品", "眉笔", "霜", "油水乳", "露水乳", "腮红", "眼影", "水杨酸", "唇泥", "身体乳", "精华", "沐浴露", "面膜", "卸妆膏", "卸妆油", "洁泥膜", "沐霸", "洗面奶"
            /**品牌*/, "肯德基", "麦当劳", "品牌", "小红书", "海蓝之", "修丽可", "荣耀", "安踏", "隆力奇", "新百伦", "沃尔玛", "李宁", "公牛", "山姆", "unny", "声阔", "迅雷", "白惜", "珀莱雅", "美团"
            /**诱惑*/, "丝袜", "性感", "房事", "行房"
            /**诱惑*/, "德国",

            "hera", "kled", "akf粉底液", "rc", "芭贝拉", "mac", "ct", "不见", "kato", "udo", "dpdp"
    ));

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

            // 4.2 根据 EXCLUDED_TERMS 排除包含排除词的词
            int excludedTermCount = 0;
            List<String> afterExcludeFilter = new ArrayList<>();
            for (String word : distinctKeywords) {
                boolean shouldExclude = false;
                for (String term : EXCLUDED_TERMS) {
                    if (word.contains(term)) {
                        shouldExclude = true;
                        excluded.add(new KeywordResult(word, false, "匹配排除词: " + term));
                        excludedTermCount++;
                        log.info("排除词匹配: [{}] → 包含排除词: {}", word, term);
                        break;
                    }
                }
                if (!shouldExclude) {
                    afterExcludeFilter.add(word);
                }
            }
            if (excludedTermCount > 0) {
                log.info("EXCLUDED_TERMS 匹配排除 {} 个", excludedTermCount);
            }
            distinctKeywords = afterExcludeFilter;


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

            log.info("Excel 内重复 {} 个，排除词匹配 {} 个，历史重复 {} 个，待AI筛选 {} 个",
                    excelDupCount, excludedTermCount, excluded.size() - excelDupCount - excludedTermCount, newWords.size());

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
