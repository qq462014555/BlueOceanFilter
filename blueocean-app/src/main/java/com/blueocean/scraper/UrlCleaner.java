package com.blueocean.scraper;

public class UrlCleaner {

    public static String clean(String url) {
        if (url == null || url.isEmpty()) return url;
        url = url.split("\\?")[0];
        url = url.replace("_.webp", "");
        url = url.replace("_sum.jpg", ".jpg");
        // 清理重复的后缀 .jpg.jpg -> .jpg
        while (url.endsWith(".jpg.jpg")) {
            url = url.substring(0, url.length() - 4);
        }
        return url;
    }
}
