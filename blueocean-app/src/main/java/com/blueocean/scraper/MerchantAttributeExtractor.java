package com.blueocean.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 商家后台属性提取器
 * 连接到 CDP 端口，自动提取页面上所有商品属性字段和下拉选项
 */
public class MerchantAttributeExtractor {

    private static final Logger log = LoggerFactory.getLogger(MerchantAttributeExtractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DEFAULT_CDP_ENDPOINT = "http://localhost:9223";

    private final String cdpEndpoint;

    public MerchantAttributeExtractor() {
        this(DEFAULT_CDP_ENDPOINT);
    }

    public MerchantAttributeExtractor(String cdpEndpoint) {
        this.cdpEndpoint = cdpEndpoint;
        log.info("CDP 端口: {}", cdpEndpoint);
    }

    /**
     * 测试 CDP 连接是否可用
     */
    public void testConnection() {
        try (Playwright playwright = Playwright.create()) {
            log.info("正在连接 CDP: {}", cdpEndpoint);
            Browser browser = playwright.chromium().connectOverCDP(cdpEndpoint);
            List<BrowserContext> contexts = browser.contexts();
            int pageCount = contexts.stream().mapToInt(c -> c.pages().size()).sum();
            log.info("CDP 连接成功！上下文数={}, 标签页数={}", contexts.size(), pageCount);
        } catch (Exception e) {
            throw new RuntimeException("CDP 连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取当前浏览器标签页的所有表单字段
     * 会自动点击每个下拉框获取可选项
     */
    public List<Map<String, Object>> extractFields() {
        List<Map<String, Object>> fields = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            log.info("正在连接 CDP: {}", cdpEndpoint);
            Browser browser = playwright.chromium().connectOverCDP(cdpEndpoint);
            log.info("CDP 连接成功");

            // 获取当前活跃的标签页
            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            List<Page> pages = context.pages();
            if (pages.isEmpty()) {
                log.warn("没有打开的页面");
                return fields;
            }

            // 使用第一个页面（通常是当前活动页）
            Page page = pages.get(0);
            log.info("当前页面: {}", page.url());

            // 执行 JS 提取所有表单项
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawFields = (List<Map<String, Object>>) page.evaluate("() => {\n" +
                    "  const fields = [];\n" +
                    "  // 查找所有表单项容器\n" +
                    "  const items = document.querySelectorAll('.form-item, .attr-item, [class*=\"form-item\"], [class*=\"attr-item\"]');\n" +
                    "  \n" +
                    "  for (const item of items) {\n" +
                    "    // 提取 label\n" +
                    "    const labelEl = item.querySelector('label, [class*=\"label\"], [class*=\"title\"]');\n" +
                    "    if (!labelEl) continue;\n" +
                    "    let label = labelEl.textContent.trim().replace(/\\*|重要/g, '').trim();\n" +
                    "    if (!label) continue;\n" +
                    "    \n" +
                    "    // 判断类型\n" +
                    "    const select = item.querySelector('.ant-select, .el-select, [class*=\"select\"]');\n" +
                    "    const textInput = item.querySelector('input[type=\"text\"], input:not([type]):not([class*=\"select\"]), textarea');\n" +
                    "    const numberInput = item.querySelector('input[type=\"number\"]');\n" +
                    "    const radioGroup = item.querySelectorAll('input[type=\"radio\"]');\n" +
                    "    const checkbox = item.querySelector('input[type=\"checkbox\"]');\n" +
                    "    \n" +
                    "    let type = 'text';\n" +
                    "    if (select) type = 'dropdown';\n" +
                    "    else if (numberInput) type = 'number';\n" +
                    "    else if (radioGroup.length > 0) type = 'radio';\n" +
                    "    else if (checkbox) type = 'checkbox';\n" +
                    "    \n" +
                    "    fields.push({ label, type });\n" +
                    "  }\n" +
                    "  return fields;\n" +
                    "}");

            if (rawFields == null) {
                log.warn("JS 执行未返回结果");
                return fields;
            }

            log.info("提取到 {} 个表单字段", rawFields.size());

            // 逐个处理下拉框：点击展开获取选项
            for (Map<String, Object> field : rawFields) {
                String label = (String) field.get("label");
                String type = (String) field.get("type");

                if ("dropdown".equals(type)) {
                    log.info("  [下拉] 展开获取选项: {}", label);
                    List<String> options = getDropdownOptions(page, label);
                    field.put("options", options);
                    log.info("    → {} 个选项", options.size());
                } else if ("radio".equals(type)) {
                    log.info("  [单选] 获取选项: {}", label);
                    List<String> options = getRadioOptions(page, label);
                    field.put("options", options);
                    log.info("    → {} 个选项", options.size());
                }
            }

            fields.addAll(rawFields);
        } catch (Exception e) {
            log.error("提取属性字段失败", e);
        }

        return fields;
    }

    /**
     * 获取下拉框的所有选项
     */
    @SuppressWarnings("unchecked")
    private List<String> getDropdownOptions(Page page, String label) {
        List<String> options = new ArrayList<>();

        try {
            // 找到对应的下拉框元素
            Object result = page.evaluate("(label) => {\n" +
                    "  // 遍历所有表单项找到对应 label 的下拉框\n" +
                    "  const items = document.querySelectorAll('.form-item, .attr-item, [class*=\"form-item\"], [class*=\"attr-item\"]');\n" +
                    "  for (const item of items) {\n" +
                    "    const labelEl = item.querySelector('label, [class*=\"label\"]');\n" +
                    "    if (!labelEl) continue;\n" +
                    "    const text = labelEl.textContent.trim().replace(/\\*|重要/g, '').trim();\n" +
                    "    if (text === label) {\n" +
                    "      const select = item.querySelector('.ant-select, .el-select, [class*=\"select\"]');\n" +
                    "      return select;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return null;\n" +
                    "}", label);

            // 点击展开
            page.evaluate("(el) => { if (el) el.click(); }", result);
            page.waitForTimeout(800);

            // 提取所有选项
            List<String> opts = (List<String>) page.evaluate("() => {\n" +
                    "  const opts = [];\n" +
                    "  const dropdown = document.querySelector('.ant-select-dropdown, .el-select-dropdown, [class*=\"dropdown\"], [class*=\"picker\"], [role=\"listbox\"]');\n" +
                    "  if (!dropdown) return opts;\n" +
                    "  const items = dropdown.querySelectorAll('.ant-select-item, [class*=\"option\"], [role=\"option\"], li, div');\n" +
                    "  for (const item of items) {\n" +
                    "    const text = item.textContent.trim();\n" +
                    "    if (text && !opts.includes(text)) opts.push(text);\n" +
                    "  }\n" +
                    "  return opts;\n" +
                    "}");

            if (opts != null) options.addAll(opts);

            // 点击关闭下拉框（按 ESC 或点击外部）
            page.keyboard().press("Escape");
            page.waitForTimeout(300);

        } catch (Exception e) {
            log.warn("获取下拉选项失败 [{}]: {}", label, e.getMessage());
            try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
        }

        return options;
    }

    /**
     * 获取单选按钮的选项
     */
    @SuppressWarnings("unchecked")
    private List<String> getRadioOptions(Page page, String label) {
        List<String> options = new ArrayList<>();

        try {
            List<String> opts = (List<String>) page.evaluate("(label) => {\n" +
                    "  const opts = [];\n" +
                    "  const items = document.querySelectorAll('.form-item, .attr-item, [class*=\"form-item\"], [class*=\"attr-item\"]');\n" +
                    "  for (const item of items) {\n" +
                    "    const labelEl = item.querySelector('label, [class*=\"label\"]');\n" +
                    "    if (!labelEl) continue;\n" +
                    "    const text = labelEl.textContent.trim().replace(/\\*|重要/g, '').trim();\n" +
                    "    if (text === label) {\n" +
                    "      const radios = item.querySelectorAll('input[type=\"radio\"]');\n" +
                    "      for (const radio of radios) {\n" +
                    "        const radioLabel = radio.parentElement.textContent.trim();\n" +
                    "        if (radioLabel) opts.push(radioLabel);\n" +
                    "      }\n" +
                    "      break;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return opts;\n" +
                    "}", label);

            if (opts != null) options.addAll(opts);
        } catch (Exception e) {
            log.warn("获取单选选项失败 [{}]: {}", label, e.getMessage());
        }

        return options;
    }

    /**
     * 保存提取结果到 JSON 文件
     */
    public String saveToFile(List<Map<String, Object>> fields, String outputDir, String filename) {
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            Path filePath = dir.resolve(filename);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(fields);
            Files.writeString(filePath, json, java.nio.charset.StandardCharsets.UTF_8);
            log.info("属性字段已保存到: {}", filePath);
            return filePath.toString();
        } catch (IOException e) {
            log.error("保存属性字段失败: {}", e.getMessage());
            return null;
        }
    }
}
