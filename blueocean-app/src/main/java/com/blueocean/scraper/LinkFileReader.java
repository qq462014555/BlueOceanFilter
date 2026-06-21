package com.blueocean.scraper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkFileReader {

    private static final Logger log = LoggerFactory.getLogger(LinkFileReader.class);

    // 标题特征：包含6个以上汉字，独占一行
    private static boolean isTitle(String line) {
        int chineseCount = 0;
        for (char c : line.toCharArray()) {
            if (c >= '一' && c <= '鿿') chineseCount++;
        }
        return chineseCount >= 6;
    }

    private static final String BASE_DIR = ScraperConfig.BASE_OUTPUT_DIR;

    public static File findLatestLinkFile() {
        File baseDir = new File(BASE_DIR);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            throw new RuntimeException("基础目录不存在: " + BASE_DIR);
        }

        File latestDir = null;
        for (File dir : baseDir.listFiles()) {
            if (dir.isDirectory() && dir.getName().contains("1688链接")) {
                if (latestDir == null || dir.getName().compareTo(latestDir.getName()) > 0) {
                    latestDir = dir;
                }
            }
        }

        if (latestDir == null) {
            throw new RuntimeException("未找到包含'1688链接'的日期文件夹");
        }

        for (File file : latestDir.listFiles()) {
            if (file.isFile() && file.getName().endsWith("_链接.txt")) {
                return file;
            }
        }

        throw new RuntimeException("在 " + latestDir.getName() + " 中未找到 _链接.txt 文件");
    }

    /**
     * 找到今天（按年月日匹配）的 RPA _链接.txt 文件
     * 目录名格式如: 2026年05月12日14时23分_1688链接
     */
    public static File findTodayLinkFile() {
        File baseDir = new File(BASE_DIR);
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            throw new RuntimeException("基础目录不存在: " + BASE_DIR);
        }

        String todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));

        for (File dir : baseDir.listFiles()) {
            if (dir.isDirectory() && dir.getName().contains("1688链接") && dir.getName().startsWith(todayStr)) {
                for (File file : dir.listFiles()) {
                    if (file.isFile() && file.getName().endsWith("_链接.txt")) {
                        return file;
                    }
                }
            }
        }

        throw new RuntimeException("未找到今天的 _链接.txt 文件（日期: " + todayStr + "）");
    }

    public static List<LinkEntry> read(File file) throws IOException {
        List<LinkEntry> entries = new ArrayList<>();
        String currentCategory = "";
        String pendingTitle = null; // 处理"标题在前URL在后"的格式

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("淘宝类目：") || line.startsWith("淘宝类目:") || line.startsWith("类目:") || line.startsWith("类目：")) {
                    String sep = line.contains("：") ? "：" : ":";
                    currentCategory = line.substring(line.indexOf(sep) + 1).trim();
                } else if (line.startsWith("标题：") || line.startsWith("标题:")) {
                    String sep = line.contains("：") ? "：" : ":";
                    pendingTitle = line.substring(line.indexOf(sep) + 1).trim();
                } else if (line.startsWith("http")) {
                    LinkEntry entry = new LinkEntry(currentCategory, line);
                    if (pendingTitle != null) {
                        entry.setTitle(pendingTitle);
                        pendingTitle = null;
                    }
                    entries.add(entry);
                } else if (isTitle(line)) {
                    // 标题独占一行：下方紧跟该标题对应的商品链接
                    // 如果上一个条目还没有标题，且没有待分配的标题 → 旧格式（标题在URL后）
                    boolean assigned = false;
                    if (pendingTitle == null && !entries.isEmpty() && entries.get(entries.size() - 1).getTitle() == null) {
                        entries.get(entries.size() - 1).setTitle(line);
                        assigned = true;
                        log.info("识别到标题(后置): '{}'", line);
                    }
                    if (!assigned) {
                        // 新格式（标题在URL前）→ 暂存等待下一条URL
                        pendingTitle = line;
                        log.info("识别到标题(前置): '{}'", line);
                    }
                }
            }
        }

        log.info("解析到 {} 个链接:", entries.size());
        for (int i = 0; i < entries.size(); i++) {
            LinkEntry e = entries.get(i);
            log.info("  [{}] title='{}' url={}", i + 1, e.getTitle(), e.getUrl());
        }

        return entries;
    }

    public static String getCategoryFolderName(String categoryPath) {
        return categoryPath.replace(">", "_").replace("/", "_");
    }
}
