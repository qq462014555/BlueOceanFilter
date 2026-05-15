package com.blueocean.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 千牛商品发布页面属性提取器
 * 使用 Playwright 原生 Locator API（不依赖 JS click），通过 CDP 连接 Chrome 端口 9223
 */
public class QianniuAttributeScraper {

    private static final Logger log = LoggerFactory.getLogger(QianniuAttributeScraper.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CDP_PORT = 9223;
    private static final String CDP_ENDPOINT = "http://127.0.0.1:" + CDP_PORT;

    private final String outputDir;

    public QianniuAttributeScraper(String outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * 测试 CDP 连接
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Playwright playwright = Playwright.create()) {
            log.info("正在连接 CDP: {}", CDP_ENDPOINT);
            Browser browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
            List<BrowserContext> contexts = browser.contexts();
            int pageCount = contexts.stream().mapToInt(c -> c.pages().size()).sum();
            result.put("connected", true);
            result.put("contexts", contexts.size());
            result.put("pages", pageCount);
            log.info("CDP 连接成功！上下文数={}, 标签页数={}", contexts.size(), pageCount);
            browser.close();
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getMessage());
            log.error("CDP 连接失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 执行属性提取
     */
    public Map<String, Object> extract() {
        Map<String, Object> result = new LinkedHashMap<>();
        Playwright playwright = null;
        Browser browser = null;

        try {
            playwright = Playwright.create();
            log.info("正在连接 CDP: {}", CDP_ENDPOINT);
            browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
            log.info("CDP 连接成功");

            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            Page page = findPublishTab(context);
            if (page == null) {
                result.put("success", false);
                result.put("error", "未找到「商品发布」标签页，请先在 Chrome 中打开该页面");
                return result;
            }

            log.info("找到商品发布页面: {}", page.url());
            page.bringToFront();

            // 先确保页面滚动到顶部
            page.evaluate("window.scrollTo(0, 0);");
            page.waitForTimeout(500);

            List<AttributeField> fields = extractAllAttributes(page);
            log.info("提取到 {} 个属性字段", fields.size());

            String savedPath = saveToFile(fields, page.url());

            result.put("success", true);
            result.put("fieldCount", fields.size());
            result.put("sourceUrl", page.url());
            result.put("savedPath", savedPath);
            result.put("fields", fields);

        } catch (Exception e) {
            log.error("提取属性失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
        }

        return result;
    }

    /**
     * 遍历所有标签页，找到标题包含「商品发布」的页面
     */
    private Page findPublishTab(BrowserContext context) {
        List<Page> pages = context.pages();
        for (Page page : pages) {
            try {
                String title = page.title();
                if (title != null && title.contains("商品发布")) {
                    log.info("找到商品发布标签页: {}", title);
                    return page;
                }
                String url = page.url();
                if (url != null && url.contains("publish")) {
                    log.info("通过 URL 找到商品发布标签页: {}", url);
                    return page;
                }
            } catch (Exception e) {
                log.warn("检查标签页失败: {}", e.getMessage());
            }
        }
        for (Page page : pages) {
            try {
                String title = page.title();
                if (title != null && (title.contains("发布") || title.contains("商品"))) {
                    log.info("模糊匹配到标签页: {}", title);
                    return page;
                }
            } catch (Exception e) {
                log.warn("检查标签页失败: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 提取单个下拉框的选项并关闭
     */
    @SuppressWarnings("unchecked")
    private List<String> extractAndClose(Page page, Locator selectLocator) {
        // 点之前先回滚页面到顶部，确保下拉框在可视区
        page.evaluate("() => window.scrollTo(0, 0);");
        page.waitForTimeout(300);

        // 清理残留的 overlay
        page.evaluate("() => {\n" +
                "  document.querySelectorAll('.next-overlay-wrapper').forEach(e => e.remove());\n" +
                "}");
        page.waitForTimeout(1000);

        // 用 Playwright locator.click() 触发，能激活 React 事件链
        try {
            log.info("    尝试点击 locator...");
            selectLocator.click();
            log.info("    点击成功，等待 overlay 出现...");
        } catch (Exception e) {
            log.warn("    点击失败: {}", e.getMessage());
            try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
            return new ArrayList<>();
        }
        page.waitForTimeout(800);

        List<String> options = new ArrayList<>();
        // 查找 overlay 中的选项，滚动逐步加载
        // 用 position 匹配：找到触发元素最近的 overlay 面板
        Object overlayExists = page.evaluate("""
                () => {
                    const overlays = Array.from(document.querySelectorAll('.next-overlay-wrapper, .options-content'));
                    if (overlays.length === 0) return null;
                    // 取最后一个（最新插入的）
                    return overlays[overlays.length - 1];
                }
                """);
        if (overlayExists == null) {
            log.warn("    未找到 overlay 元素，下拉框未弹出");
            try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
            page.waitForTimeout(300);
            return options;
        }
        log.info("    overlay 已出现，开始滚动提取...");

        Set<String> collected = new LinkedHashSet<>();
        for (int i = 0; i < 20; i++) {
            // 滚动到底部
            page.evaluate("() => {\n" +
                    "  const sc = Array.from(document.querySelectorAll('.next-overlay-wrapper, .options-content')).pop();\n" +
                    "  if (sc) sc.scrollTop = sc.scrollHeight;\n" +
                    "}");
            page.waitForTimeout(500);

            // 提取当前 overlay 中的可见选项（取最后一个 = 最新打开的）
            @SuppressWarnings("unchecked")
            List<String> texts = (List<String>) page.evaluate("() => {\n" +
                    "  const overlays = Array.from(document.querySelectorAll('.next-overlay-wrapper, .options-content'));\n" +
                    "  const overlay = overlays.length > 0 ? overlays[overlays.length - 1] : null;\n" +
                    "  if (!overlay) return [];\n" +
                    "  const items = overlay.querySelectorAll('.options-item');\n" +
                    "  const out = [];\n" +
                    "  for (const it of items) {\n" +
                    "    const t = (it.getAttribute('title') || it.textContent).trim();\n" +
                    "    if (t && t.length > 0 && t.length < 50 && !t.startsWith('请选择') && !t.startsWith('搜索')) {\n" +
                    "      out.push(t);\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return out;\n" +
                    "}");
            if (texts != null) {
                for (String t : texts) {
                    if (collected.add(t)) {
                        log.debug("  新选项: {}", t);
                    }
                }
            }

            // 判断是否还有更多内容
            Object hasMore = page.evaluate("() => {\n" +
                    "  const sc = Array.from(document.querySelectorAll('.next-overlay-wrapper, .options-content')).pop();\n" +
                    "  if (!sc) return false;\n" +
                    "  if (!sc) return false;\n" +
                    "  return sc.scrollTop + sc.clientHeight < sc.scrollHeight - 5;\n" +
                    "}");
            if (Boolean.FALSE.equals(hasMore)) {
                break;
            }
        }

        // 按字母长度过滤超集（去掉拼接的长串）
        List<String> raw = new ArrayList<>(collected);
        List<String> filtered = new ArrayList<>();
        for (String o : raw) {
            boolean isSubset = true;
            for (String p : raw) {
                if (!o.equals(p) && p.contains(o) && p.length() > o.length()) {
                    isSubset = false;
                    break;
                }
            }
            if (isSubset) filtered.add(o);
        }

        // 按 Escape 关闭下拉框
        log.info("    提取完成，共 {} 个有效选项", filtered.size());
        page.keyboard().press("Escape");
        page.waitForTimeout(300);

        return filtered;
    }

    @SuppressWarnings("unchecked")
    private List<AttributeField> extractAllAttributes(Page page) {
        List<AttributeField> fields = new ArrayList<>();

        // 第一步：JS 提取所有属性基本信息（标签、类型、是否必填、当前值）
        List<Map<String, Object>> fieldResults = (List<Map<String, Object>>) page.evaluate(
                "() => {\n" +
                "  const results = [];\n" +
                "  const elements = document.querySelectorAll('.default-items-item');\n" +
                "  \n" +
                "  for (const el of elements) {\n" +
                "    const labelEl = el.querySelector('.sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label');\n" +
                "    if (!labelEl) continue;\n" +
                "    let label = labelEl.getAttribute('title') || labelEl.textContent.trim();\n" +
                "    label = label.replace(/[\\*]/g, '').trim();\n" +
                "    if (!label || label.length < 1) continue;\n" +
                "    \n" +
                "    const required = el.querySelector('.sell-component-info-wrapper-required') !== null;\n" +
                "    let currentValue = '';\n" +
                "    const inputEl = el.querySelector('input');\n" +
                "    if (inputEl && inputEl.value) currentValue = inputEl.value.trim();\n" +
                "    else {\n" +
                "      const emEl = el.querySelector('em');\n" +
                "      if (emEl) currentValue = (emEl.getAttribute('title') || emEl.textContent).trim();\n" +
                "    }\n" +
                "    \n" +
                "    const itemClass = el.className || '';\n" +
                "    let type = 'input';\n" +
                "    if (itemClass.includes('checkbox')) type = 'checkbox';\n" +
                "    else if (itemClass.includes('combobox')) type = 'combobox';\n" +
                "    else if (itemClass.includes('select')) type = 'select';\n" +
                "    else if (itemClass.includes('taoSirProp')) type = 'measurement';\n" +
                "    \n" +
                "    const hasSelect = el.querySelector('.next-input-control') !== null;\n" +
                "    results.push({ label, required, currentValue, type, hasSelect });\n" +
                "  }\n" +
                "  return results;\n" +
                "}");

        if (fieldResults == null) {
            log.warn("JS 执行未返回结果");
            return fields;
        }

        // 第二步：用 Playwright locator 按序号点击每个下拉框
        // 关键点：不使用 CSS nth-of-type（按标签名算的，索引不准），改用 locator.nth(i)
        int selectIndex = 0;
        for (Map<String, Object> fieldData : fieldResults) {
            String label = (String) fieldData.get("label");
            boolean required = Boolean.TRUE.equals(fieldData.get("required"));
            String currentValue = (String) fieldData.get("currentValue");
            String type = (String) fieldData.get("type");
            boolean hasSelect = Boolean.TRUE.equals(fieldData.get("hasSelect"));

            List<String> options = new ArrayList<>();
            if (hasSelect) {
                log.info("  正在提取 [{}] (第 {} 个下拉)...", label, selectIndex + 1);
                Locator selectLocator = page.locator(".default-items-item .next-select .next-input-control").nth(selectIndex);
                options = extractAndClose(page, selectLocator);
                log.info("  [{}] 类型: {}, 提取到 {} 个选项", label, type, options.size());
                selectIndex++;
            } else {
                log.info("  [{}] 类型: {}, 选项数: 0", label, type);
            }

            AttributeField f = new AttributeField();
            f.label = label;
            f.type = type;
            f.required = required;
            f.currentValue = currentValue;
            f.options = options;
            fields.add(f);
        }

        log.info("共提取 {} 个属性字段", fields.size());
        return fields;
    }

    /**
     * 保存提取结果到 JSON 文件
     */
    private String saveToFile(List<AttributeField> fields, String sourceUrl) {
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = "qianniu_attributes_" + timestamp + ".json";
            Path filePath = dir.resolve(filename);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("extractedAt", LocalDateTime.now().toString());
            payload.put("sourceUrl", sourceUrl);
            payload.put("fieldCount", fields.size());
            payload.put("fields", fields);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Files.writeString(filePath, json, StandardCharsets.UTF_8);
            log.info("属性字段已保存到: {}", filePath);
            return filePath.toString();

        } catch (IOException e) {
            log.error("保存属性字段失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 列出已保存的 JSON 文件
     */
    public List<Map<String, Object>> listSavedFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            Path dir = Paths.get(outputDir);
            if (!Files.exists(dir)) return files;

            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".json") && p.toString().contains("qianniu_attributes"))
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("name", p.getFileName().toString());
                            info.put("path", p.toString());
                            try {
                                info.put("size", Files.size(p));
                                info.put("modified", Files.getLastModifiedTime(p).toString());
                            } catch (IOException e) {
                                info.put("size", 0);
                                info.put("modified", "unknown");
                            }
                            files.add(info);
                        });
            }

        } catch (IOException e) {
            log.error("列出文件失败: {}", e.getMessage());
        }

        return files;
    }

    /**
     * 属性字段数据结构
     */
    public static class AttributeField {
        public String label;
        public String type;        // select(单选下拉), checkbox(多选下拉), combobox(可搜索输入), input(文本输入)
        public boolean required;
        public List<String> options;
        public String currentValue;
    }
}
