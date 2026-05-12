package com.blueocean.scraper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class LinkFileReader {

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

    public static List<LinkEntry> read(File file) throws IOException {
        List<LinkEntry> entries = new ArrayList<>();
        String currentCategory = "";

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("淘宝类目：") || line.startsWith("淘宝类目:")) {
                    currentCategory = line.substring(line.indexOf("：") != -1 ? line.indexOf("：") + 1 : line.indexOf(":") + 1).trim();
                } else if (line.startsWith("http")) {
                    entries.add(new LinkEntry(currentCategory, line));
                }
            }
        }

        return entries;
    }

    public static String getCategoryFolderName(String categoryPath) {
        return categoryPath.replace(">", "_").replace(" ", "");
    }
}
