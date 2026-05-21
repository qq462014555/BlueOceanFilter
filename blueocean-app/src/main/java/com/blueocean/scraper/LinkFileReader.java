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
                    String title = line.substring(line.indexOf(sep) + 1).trim();
                    if (!entries.isEmpty()) {
                        entries.get(entries.size() - 1).setTitle(title);
                    }
                } else if (line.startsWith("http")) {
                    entries.add(new LinkEntry(currentCategory, line));
                } else if (isTitle(line) && !entries.isEmpty()) {
                    // 标题独占一行：6个以上汉字，紧跟在上一个链接后面
                    entries.get(entries.size() - 1).setTitle(line);
                    log.info("识别到标题: '{}'", line);
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
