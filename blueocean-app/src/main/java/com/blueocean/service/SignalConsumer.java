package com.blueocean.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

/**
 * 轮询 signal/ 目录，发现 fill_fields 信号文件后自动连接浏览器填写字段
 */
@Slf4j
@Service
public class SignalConsumer {

    private static final String SIGNAL_DIR = findSignalDir();

    @Scheduled(fixedRate = 3000)
    public void pollSignals() {
        try {
            Path signalDir = Paths.get(SIGNAL_DIR);
            if (!Files.exists(signalDir)) return;

            List<Path> files = Files.list(signalDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("fill_fields_"))
                    .sorted(Comparator.comparingLong(p -> {
                        try { return Files.getLastModifiedTime(p).toMillis(); }
                        catch (IOException e) { return 0L; }
                    }))
                    .toList();

            if (files.isEmpty()) return;

            // 只处理最早的一个
            Path signalFile = files.get(0);
            processSignal(signalFile);
        } catch (Exception e) {
            log.error("轮询信号文件异常", e);
        }
    }

    private void processSignal(Path signalFile) {
        try {
            String content = Files.readString(signalFile);
            JSONObject signal = JSON.parseObject(content);
            String jsonPath = signal.getString("jsonPath");
            String productId = signal.getString("productId");

            if (jsonPath == null) {
                log.warn("信号文件缺少 jsonPath: {}", signalFile.getFileName());
                Files.delete(signalFile);
                return;
            }

            log.info("处理信号: {} (productId={})", signalFile.getFileName(), productId);
            String fieldsContent = Files.readString(Paths.get(jsonPath));
            JSONObject fieldsData = JSON.parseObject(fieldsContent);
            JSONArray fields = fieldsData.getJSONArray("fields");
            if (fields == null || fields.isEmpty()) {
                log.warn("没有需要填写的字段: {}", jsonPath);
                Files.delete(signalFile);
                return;
            }

            fillFields(fields, productId);
            Files.delete(signalFile);
            log.info("信号文件处理完成: {}", signalFile.getFileName());
        } catch (Exception e) {
            log.error("处理信号文件失败: {}", signalFile.getFileName(), e);
        }
    }

    private void fillFields(JSONArray fields, String productId) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP("http://127.0.0.1:9223");
            BrowserContext context = browser.contexts().get(0);

            // 找到商品发布标签页
            Page targetPage = null;
            for (Page page : context.pages()) {
                if (page.title().contains("商品发布")) {
                    targetPage = page;
                    break;
                }
            }
            if (targetPage == null) {
                log.error("未找到商品发布页面");
                return;
            }
            targetPage.bringToFront();
            sleep(1000);

            List<Locator> items = targetPage.locator(".default-items-item").all();

            for (int i = 0; i < fields.size(); i++) {
                JSONObject field = fields.getJSONObject(i);
                String label = field.getString("label");
                String type = field.getString("type");
                String value = field.getString("currentValue");

                try {
                    Locator targetItem = findItemByLabel(items, label);
                    if (targetItem == null) {
                        log.warn("未找到字段: {}", label);
                        continue;
                    }

                    if ("select".equals(type)) {
                        targetItem.locator(".next-input-control").first().click();
                        sleep(500);
                        Locator option = targetPage.locator(".options-item:has-text('" + value + "')").first();
                        if (option.count() > 0) {
                            option.click();
                            sleep(500);
                            log.info("✅ {} = {}", label, value);
                        } else {
                            log.warn("❌ {} 找不到选项: {}", label, value);
                        }
                    } else if ("combobox".equals(type) || "input".equals(type) || "measurement".equals(type)) {
                        Locator inputEl = targetItem.locator("input").first();
                        inputEl.click();
                        inputEl.fill(value);
                        sleep(300);
                        inputEl.press("Enter");
                        sleep(300);
                        log.info("✅ {} = {}", label, value);
                    } else if ("checkbox".equals(type)) {
                        targetItem.locator(".next-input-control").first().click();
                        sleep(500);
                        String[] values = value.split(",");
                        for (String v : values) {
                            String trimmed = v.trim();
                            Locator option = targetPage.locator(".options-item:has-text('" + trimmed + "')").first();
                            if (option.count() > 0) {
                                option.click();
                                sleep(300);
                            }
                        }
                        log.info("✅ {} = {}", label, value);
                    }
                    sleep(300);
                } catch (Exception e) {
                    log.error("❌ {} 填写失败: {}", label, e.getMessage());
                }
            }

            // 截图
            Path screenshotPath = Paths.get(SIGNAL_DIR, "filled_" + System.currentTimeMillis() + ".png");
            targetPage.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
            log.info("截图: {}", screenshotPath);

            browser.close();
        } catch (Exception e) {
            log.error("填写字段失败", e);
        }
    }

    private Locator findItemByLabel(List<Locator> items, String label) {
        for (Locator item : items) {
            try {
                Locator labelEl = item.locator(".sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label").first();
                if (labelEl.count() > 0) {
                    String itemLabel = labelEl.innerText().trim();
                    if (label.equals(itemLabel)) {
                        return item;
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String findSignalDir() {
        String projectRoot = System.getProperty("user.dir");
        Path p = Paths.get(projectRoot);
        if (Files.exists(p.resolve(".claude")) || Files.exists(p.resolve("pom.xml"))) {
            return p.resolve("signal").toString();
        }
        // 尝试上级目录
        Path parent = p.getParent();
        if (parent != null && (Files.exists(parent.resolve(".claude")) || Files.exists(parent.resolve("pom.xml")))) {
            return parent.resolve("signal").toString();
        }
        return p.resolve("signal").toString();
    }
}
