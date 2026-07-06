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
    private static final String RPA_BASE_DIR = "C:\\Users\\46201\\Documents\\无极RPA文件处理";

    public QianniuAttributeScraper(String outputDir) {
        // outputDir 参数保留兼容，实际保存到 RPA_BASE_DIR
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
            log.info("CDP 连接成功！上下文数={}, 标签页数={}", contexts.size(), pageCount);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getMessage());
            log.error("CDP 连接失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 只提取宝贝标题（轻量，不提取完整属性字段），用于缓存判断
     */
    public Map<String, Object> extractTitleOnly() {
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
            page.evaluate("window.scrollTo(0, 0);");
            page.waitForTimeout(500);

            String qianniuTitle = extractTitle(page);
            log.info("宝贝标题: {}", qianniuTitle);

            result.put("success", true);
            result.put("title", qianniuTitle);
            result.put("sourceUrl", page.url());

        } catch (Exception e) {
            log.error("提取标题失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 执行属性提取（完整流程）
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

            // 提取宝贝标题
            String qianniuTitle = extractTitle(page);
            log.info("宝贝标题: {}", qianniuTitle);

            // 提取类目路径
            String categoryPath = extractCategory(page);
            log.info("类目路径: {}", categoryPath);

            List<AttributeField> fields = extractAllAttributes(page);
            log.info("提取到 {} 个属性字段", fields.size());

            String savedPath = saveToFile(fields, page.url(), qianniuTitle, categoryPath);

            result.put("success", true);
            result.put("title", qianniuTitle);
            result.put("fieldCount", fields.size());
            result.put("sourceUrl", page.url());
            result.put("savedPath", savedPath);
            result.put("fields", fields);

        } catch (Exception e) {
            log.error("提取属性失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            // if (playwright != null) playwright.close(); // 已注释：不关闭浏览器
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
     * 提取宝贝标题
     */
    @SuppressWarnings("unchecked")
    private String extractTitle(Page page) {
        try {
            String title = (String) page.evaluate("() => {\n" +
                    "  const input = document.querySelector('#struct-title input');\n" +
                    "  return input ? input.value : '';\n" +
                    "}");
            return title != null ? title.trim() : "";
        } catch (Exception e) {
            log.warn("提取宝贝标题失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 提取类目路径（如：家装灯饰光源_光源_LED球泡灯）
     */
    @SuppressWarnings("unchecked")
    private String extractCategory(Page page) {
        try {
            String category = (String) page.evaluate("() => {\n" +
                    "  // 尝试找类目面包屑或选择器中的类目文本\n" +
                    "  const els = document.querySelectorAll('.category-path, .category-breadcrumb, .sell-category, .cat-path');\n" +
                    "  for (const el of els) {\n" +
                    "    const text = el.textContent.trim();\n" +
                    "    if (text && text.length > 1) return text;\n" +
                    "  }\n" +
                    "  // 尝试找包含 > 或 / 分隔符的元素\n" +
                    "  const allEls = document.querySelectorAll('.next-breadcrumb, .cat-name, .category-name');\n" +
                    "  for (const el of allEls) {\n" +
                    "    const text = el.textContent.trim();\n" +
                    "    if (text && text.length > 1) return text;\n" +
                    "  }\n" +
                    "  return '';\n" +
                    "}");
            if (category != null && !category.isEmpty()) {
                // 把 > 或 / 替换为 _
                return category.replace(">", "_").replace("/", "_").replaceAll("\\s+", "");
            }
        } catch (Exception e) {
            log.warn("提取类目路径失败: {}", e.getMessage());
        }
        return "";
    }

    /**
     * 提取单个下拉框的选项并关闭
     */
    @SuppressWarnings("unchecked")
    private List<String> extractAndClose(Page page, Locator selectLocator) {
        // 点之前先回滚页面到顶部，确保下拉框在可视区
        page.evaluate("() => window.scrollTo(0, 0);");
        page.waitForTimeout(300);

        List<String> options = new ArrayList<>();
        int maxRetries = 3;

        // 重试点击，最多 3 次
        for (int retry = 0; retry < maxRetries; retry++) {
            if (retry > 0) {
                log.info("    第 {} 次重试点击...", retry + 1);
            }

            try {
                selectLocator.click();
                log.info("    点击成功，等待 overlay 出现...");
            } catch (Exception e) {
                log.warn("    点击失败: {}", e.getMessage());
                try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
                continue;
            }
            page.waitForTimeout(1000);

            // 检查 overlay 是否出现
            Object overlayExists = page.evaluate("""
                    () => {
                        const overlays = Array.from(document.querySelectorAll('.next-overlay-wrapper, .options-content'));
                        if (overlays.length === 0) return null;
                        return overlays[overlays.length - 1];
                    }
                    """);
            if (overlayExists == null) {
                log.warn("    未找到 overlay 元素（重试 {}/{})", retry + 1, maxRetries);
                page.waitForTimeout(1000);
                continue;
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

                // 提取当前 overlay 中的可见选项
                List<String> texts = (List<String>) page.evaluate("() => {\n" +
                        "  const overlays = Array.from(document.querySelectorAll('next-overlay-wrapper,.next-overlay-wrapper.opened, .options-content'));\n" +
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
                            log.info("  新选项: {}", t);
                        }
                    }
                }

                // 判断是否还有更多内容
                Object hasMore = page.evaluate("() => {\n" +
                        "  const sc = Array.from(document.querySelectorAll('.next-overlay-wrapper,.next-overlay-wrapper.opened,.options-content')).pop();\n" +
                        "  if (!sc) return false;\n" +
                        "  return sc.scrollTop + sc.clientHeight < sc.scrollHeight - 5;\n" +
                        "}");
                if (Boolean.FALSE.equals(hasMore)) {
                    break;
                }
            }

            if (!collected.isEmpty()) {
                // 按字母长度过滤超集（去掉拼接的长串）
                List<String> raw = new ArrayList<>(collected);
                for (String o : raw) {
                    boolean isSubset = true;
                    for (String p : raw) {
                        if (!o.equals(p) && p.contains(o) && p.length() > o.length()) {
                            isSubset = false;
                            break;
                        }
                    }
                    if (isSubset) options.add(o);
                }
            }

            // 按 Escape 关闭下拉框
            log.info("    提取完成，共 {} 个有效选项", options.size());
            page.keyboard().press("Escape");
            page.waitForTimeout(1500);

            if (!options.isEmpty()) {
                break;  // 成功获取到选项，不再重试
            }
            // 如果没获取到选项，关闭后继续重试
        }

        return options;
    }

    @SuppressWarnings("unchecked")
    private List<AttributeField> extractAllAttributes(Page page) {
        List<AttributeField> fields = new ArrayList<>();

        // 第一步：JS 提取所有属性基本信息（标签、类型、是否必填、当前值）
        List<Map<String, Object>> fieldResults = (List<Map<String, Object>>) page.evaluate(
                "() => {\n" +
                "  const results = [];\n" +
                "  const elements = document.querySelectorAll('.default-items-item');\n" +
                "  \n" + "  for (const el of elements) {\n" +
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
        int domIndex = 0;  // DOM 中所有 .next-input-control 的全局索引
        for (Map<String, Object> fieldData : fieldResults) {
            String label = (String) fieldData.get("label");
            boolean required = Boolean.TRUE.equals(fieldData.get("required"));
            String currentValue = (String) fieldData.get("currentValue");
            String type = (String) fieldData.get("type");
            boolean hasSelect = Boolean.TRUE.equals(fieldData.get("hasSelect"));

            List<String> options = new ArrayList<>();
            if (hasSelect) {
                domIndex++;  // 无论是否有默认值，都要递增 DOM 索引
                if (currentValue == null || currentValue.isEmpty()) {
                    log.info("  正在提取 [{}] (第 {} 个下拉)...", label, domIndex);
                    Locator selectLocator = page.locator(".default-items-item .next-select .next-input-control").nth(domIndex - 1);
                    options = extractAndClose(page, selectLocator);
                    log.info("  [{}] 类型: {}, 提取到 {} 个选项", label, type, options.size());
                } else {
                    log.info("  [{}] 类型: {}, 跳过（默认值: {}）", label, type, currentValue);
                }
            } else {
                log.info("  [{}] 类型: {}, 选项数: 0{}", label, type,
                        currentValue != null && !currentValue.isEmpty() ? " (默认值: " + currentValue + ")" : "");
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
     * 保存提取结果到 RPA 目录
     * 先递归查找是否存在同名标题目录，有则直接写入；否则新建完整路径
     */
    private String saveToFile(List<AttributeField> fields, String sourceUrl, String title, String categoryPath) {
        try {
            String safeTitle = (title != null && !title.isEmpty()) ? title.trim() : "未命名商品";
            safeTitle = safeTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (safeTitle.length() > 100) safeTitle = safeTitle.substring(0, 100);
            final String searchTitle = safeTitle;

            Path rpaBaseDir = Paths.get(RPA_BASE_DIR);

            // 1. 找到今天的日期文件夹
            Path todayDir = null;
            String todayPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            if (Files.exists(rpaBaseDir)) {
                try (var stream = Files.list(rpaBaseDir)) {
                    todayDir = stream
                            .filter(Files::isDirectory)
                            .filter(d -> d.getFileName().toString().startsWith(todayPrefix))
                            .findFirst()
                            .orElse(null);
                }
            }
            if (todayDir == null) {
                log.warn("未找到今日日期文件夹: {}", todayPrefix);
                return null;
            }

            // 2. 在当日文件夹下循环所有子目录，找到匹配商品标题的目录
            Path targetDir = null;
            try (var walk = Files.walk(todayDir)) {
                targetDir = walk
                        .filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString().equals(searchTitle))
                        .findFirst()
                        .orElse(null);
            }
            if (targetDir == null) {
                log.warn("在当日目录下未找到商品目录: {}", safeTitle);
                return null;
            }

            // 3. 写入 JSON
            Path filePath = targetDir.resolve("qianniu_attr.json");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("extractedAt", LocalDateTime.now().toString());
            payload.put("sourceUrl", sourceUrl);
            payload.put("title", safeTitle);
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
     * 将 AI 填充的值写回千牛页面
     * 优先从 RPA 目录查找已填充的 JSON 文件，没有则使用前端传入的 fields
     */
    @SuppressWarnings("unchecked")
  /*  public Map<String, Object> fillFields(List<Map<String, Object>> fields) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 优先从 RPA 今日目录查找已填充的 JSON 文件
        Path rpaBaseDir = Paths.get(RPA_BASE_DIR);
        String todayPrefix = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        if (Files.exists(rpaBaseDir)) {
            try (var taskDirs = Files.list(rpaBaseDir)) {
                var taskDirList = taskDirs
                        .filter(Files::isDirectory)
                        .filter(d -> d.getFileName().toString().startsWith(todayPrefix))
                        .toList();
                for (Path taskDir : taskDirList) {
                    try (var catDirs = Files.list(taskDir)) {
                        var catDirList = catDirs.filter(Files::isDirectory).toList();
                        for (Path catDir : catDirList) {
                            try (var productDirs = Files.list(catDir)) {
                                var productList = productDirs.filter(Files::isDirectory).toList();
                                for (Path productDir : productList) {
                                    try (var dirFiles = Files.list(productDir)) {
                                        Path filledFile = dirFiles
                                                .filter(p -> p.getFileName().toString().equals("qianniu_attr_ai_back.json"))
                                                .findFirst()
                                                .orElse(null);
                                        if (filledFile != null && Files.exists(filledFile)) {
                                            String content = Files.readString(filledFile, StandardCharsets.UTF_8);
                                            Map<String, Object> parsed = mapper.readValue(content, Map.class);
                                            List<Map> fieldsList = (List<Map>) parsed.get("fields");
                                            if (fieldsList != null && !fieldsList.isEmpty()) {
                                                @SuppressWarnings("rawtypes")
                                                ArrayList rawList = new ArrayList(fieldsList);
                                                fields = rawList;
                                                log.info("从 RPA 目录读取已填充 JSON: {}", filledFile);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("扫描 RPA 目录失败: {}", e.getMessage());
            }
        }

        if (fields == null || fields.isEmpty()) {
            result.put("success", false);
            result.put("error", "没有可填写的字段（前端未传字段且 RPA 目录下无已填充 JSON）");
            return result;
        }

        Playwright playwright = null;
        Browser browser = null;
        int filledCount = 0;
        int skipCount = 0;
        int failCount = 0;
        List<String> failedLabels = new ArrayList<>();

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().connectOverCDP(CDP_ENDPOINT);
            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            Page page = findPublishTab(context);
            if (page == null) {
                result.put("success", false);
                result.put("error", "未找到「商品发布」标签页");
                return result;
            }
            log.info("找到商品发布页面: {}", page.url());
            page.bringToFront();

            // 回滚到页面顶部
            page.evaluate("window.scrollTo(0, 0);");
            page.waitForTimeout(500);

            for (Map<String, Object> field : fields) {
                String label = (String) field.get("label");
                String type = (String) field.get("type");
                String value = (String) field.get("currentValue");

                if (value == null || value.isEmpty()) {
                    skipCount++;
                    continue;
                }

                log.info("正在填充 [{}] ({}) = {}", label, type, value);
                boolean ok = fillSingleField(page, label, type, value);
                if (ok) {
                    filledCount++;
                } else {
                    failCount++;
                    failedLabels.add(label);
                    log.warn("填写失败，停止后续操作");
                    break;
                }
                page.waitForTimeout(300);
            }

            result.put("success", failCount == 0);
            result.put("filled", filledCount);
            result.put("skipped", skipCount);
            result.put("failed", failCount);
            if (!failedLabels.isEmpty()) {
                result.put("error", "以下字段填写失败: " + String.join(", ", failedLabels));
            }

        } catch (Exception e) {
            log.error("填写属性失败", e);
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            // if (playwright != null) playwright.close(); // 已注释：不关闭浏览器
        }

        return result;
    }
*/
    /**
     * 填写单个字段
     */
   /* @SuppressWarnings("unchecked")
    private boolean fillSingleField(Page page, String label, String type, String value) {
        try {
            if ("input".equals(type) || "measurement".equals(type) || "combobox".equals(type)) {
                // 文本/数值输入：直接找 input 填入
                Locator row = findFieldRow(page, label);
                if (row == null) return false;
                Locator input = row.locator("input").first();
                if (input.count() == 0) return false;
                input.click();
                page.waitForTimeout(200);
                input.fill(value);
                page.waitForTimeout(300);
                log.info("  [{}] 文本填写: {}", label, value);
                return true;

            } else if ("checkbox".equals(type)) {
                // 多选：打开下拉，勾选匹配的选项
                return fillDropdown(page, label, value, true);

            } else if ("select".equals(type) || "combobox".equals(type)) {
                // 单选下拉：打开下拉，选择匹配的选项
                return fillDropdown(page, label, value, false);
            }

            return false;
        } catch (Exception e) {
            log.warn("  [{}] 填写失败: {}", label, e.getMessage());
            return false;
        }
    }
*/
    /**
     * 填写下拉字段（单选或多选）
     */
//    @SuppressWarnings("unchecked")
//    private boolean fillDropdown(Page page, String label, String value, boolean isCheckbox) {
//        try {
//            // 找到该字段行的第一个 .next-input-control 并点击
//            Locator row = findFieldRow(page, label);
//            if (row == null) {
//                log.warn("  [{}] 未找到字段行", label);
//                return false;
//            }
//
//            Locator selectCtrl = row.locator(".next-input-control, .next-select-inner").first();
//            if (selectCtrl.count() == 0) return false;
//
//            // 回滚页面到顶部
//     /*       page.evaluate("() => window.scrollTo(0, 0);");
//            page.waitForTimeout(300);*/
//
//            // 滚动到字段位置
//            page.evaluate("el => el.scrollIntoView({block: 'center'});", selectCtrl.elementHandle());
//            page.waitForTimeout(300);
//
//            selectCtrl.click();
//            page.waitForTimeout(1000);
//
//            // 检查 overlay 是否出现
//            Object overlayExists = page.evaluate("() => {\n" +
//                    "  const overlays = document.querySelectorAll('.next-overlay-wrapper, .options-content');\n" +
//                    "  return overlays.length > 0;\n" +
//                    "}");
//            if (Boolean.FALSE.equals(overlayExists)) {
//                log.warn("  [{}] 下拉框未弹出", label);
//                return false;
//            }
//
//            // 多选处理
//            if (isCheckbox) {
//                String[] parts = value.split(",");
//                for (String part : parts) {
//                    String opt = part.trim();
//                    if (opt.isEmpty()) continue;
//                    selectOption(page, opt);
//                    page.waitForTimeout(300);
//                }
//            } else {
//                selectOption(page, value);
//            }
//
//            // 按 Enter 确认输入
//            page.keyboard().press("Enter");
//            page.waitForTimeout(300);
//
//            // 关闭下拉
//            page.keyboard().press("Escape");
//            page.waitForTimeout(500);
//
//            log.info("  [{}] 下拉选择: {}", label, value);
//            return true;
//
//        } catch (Exception e) {
//            log.warn("  [{}] 下拉选择失败: {}", label, e.getMessage());
//            try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
//            return false;
//        }
//    }

    /**
     * 在下拉选项中选择一个
     */
    private boolean selectOption(Page page, String option) {
        Object result = page.evaluate("option => {\n" +
                "  const overlays = Array.from(document.querySelectorAll('.next-overlay-wrapper, .options-content'));\n" +
                "  if (overlays.length === 0) return {found: false, available: []};\n" +
                "  const overlay = overlays[overlays.length - 1];\n" +
                "  const items = overlay.querySelectorAll('.options-item');\n" +
                "  const available = [];\n" +
                "  for (const item of items) {\n" +
                "    const title = item.getAttribute('title') || item.textContent.trim();\n" +
                "    available.push(title);\n" +
                "    // 精确匹配优先\n" +
                "    if (title === option) {\n" +
                "      item.click();\n" +
                "      return {found: true, matched: title};\n" +
                "    }\n" +
                "  }\n" +
                "  // 模糊匹配：选项包含目标值\n" +
                "  for (const item of items) {\n" +
                "    const title = item.getAttribute('title') || item.textContent.trim();\n" +
                "    if (title.includes(option) || option.includes(title)) {\n" +
                "      item.click();\n" +
                "      return {found: true, matched: title};\n" +
                "    }\n" +
                "  }\n" +
                "  return {found: false, available: available};\n" +
                "}", option);

        if (result instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) result;
            boolean found = Boolean.TRUE.equals(map.get("found"));
            if (!found) {
                log.warn("    选项 '{}' 未找到，可选值: {}", option, map.get("available"));
            }
        }

        return true;
    }

    /**
     * 根据标签名找到字段行
     */
    @SuppressWarnings("unchecked")
    private Locator findFieldRow(Page page, String label) {
        // 通过 JS 找到匹配的行元素，然后通过 CSS 选择器定位
        Object matchedIndex = page.evaluate("label => {\n" +
                "  const rows = document.querySelectorAll('.default-items-item');\n" +
                "  for (let i = 0; i < rows.length; i++) {\n" +
                "    const row = rows[i];\n" +
                "    const labelEl = row.querySelector('.sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label');\n" +
                "    if (!labelEl) continue;\n" +
                "    const title = labelEl.getAttribute('title') || labelEl.textContent.replace(/[\\*]/g, '').trim();\n" +
                "    if (title === label) return i;\n" +
                "  }\n" +
                "  return -1;\n" +
                "}", label);

        if (!(matchedIndex instanceof Number)) return null;
        int idx = ((Number) matchedIndex).intValue();
        if (idx < 0) return null;

        return page.locator(".default-items-item").nth(idx);
    }

    /**
     * 列出已保存的 JSON 文件（递归扫描 RPA 目录下所有 qianniu_attr.json）
     */
    public List<Map<String, Object>> listSavedFiles() {
        List<Map<String, Object>> files = new ArrayList<>();
        Path rpaBaseDir = Paths.get(RPA_BASE_DIR);
        if (!Files.exists(rpaBaseDir)) return files;

        try {
            Files.walk(rpaBaseDir)
                    .filter(p -> "qianniu_attr.json".equals(p.getFileName().toString()))
                    .forEach(jsonFile -> {
                        Map<String, Object> info = new LinkedHashMap<>();
                        // 提取商品名（父目录名）
                        Path parent = jsonFile.getParent();
                        info.put("productName", parent.getFileName().toString());
                        // 相对路径作为标识
                        info.put("name", rpaBaseDir.relativize(jsonFile).toString());
                        info.put("path", jsonFile.toString());
                        try {
                            info.put("size", Files.size(jsonFile));
                            info.put("modified", Files.getLastModifiedTime(jsonFile).toString());
                        } catch (IOException e) {
                            info.put("size", 0);
                            info.put("modified", "unknown");
                        }
                        files.add(info);
                    });

            files.sort((a, b) -> {
                String ma = (String) a.get("modified");
                String mb = (String) b.get("modified");
                return mb.compareTo(ma);
            });

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
