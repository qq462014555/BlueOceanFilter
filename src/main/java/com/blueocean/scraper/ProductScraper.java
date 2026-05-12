package com.blueocean.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProductScraper {

    private static final Logger log = LoggerFactory.getLogger(ProductScraper.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public List<ProductData> scrapeProducts(List<LinkEntry> links) throws Exception {
        List<ProductData> results = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP(ScraperConfig.CDP_ENDPOINT);
            BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);

            for (int i = 0; i < links.size(); i++) {
                LinkEntry link = links.get(i);
                log.info("[{}/{}] 抓取: {} ({})", i + 1, links.size(), link.getUrl(), link.getCategoryPath());

                int retries = 0;
                while (retries < ScraperConfig.MAX_RETRIES) {
                    try {
                        Page page = context.newPage();
                        ProductData product = scrapeSingleProduct(page, link);
                        if (product != null) {
                            results.add(product);
                        }
                        page.close();
                        break;
                    } catch (Exception e) {
                        retries++;
                        log.warn("第 {} 次重试失败: {}", retries, e.getMessage());
                        if (retries >= ScraperConfig.MAX_RETRIES) {
                            log.error("商品抓取失败，已重试 {} 次: {}", ScraperConfig.MAX_RETRIES, link.getUrl());
                        } else {
                            Thread.sleep(retries * 2000L);
                        }
                    }
                }
            }

            browser.close();
        }

        return results;
    }

    @SuppressWarnings("unchecked")
    private ProductData scrapeSingleProduct(Page page, LinkEntry link) {
        String url = UrlCleaner.clean(link.getUrl());

        // 设置 API 拦截器捕获 SKU 定价数据
        AtomicReference<String> skuApiData = new AtomicReference<>();
        page.onResponse(response -> {
            try {
                if (response.url().contains("mtop.1688.wosc.queryofferskuselectormodel")) {
                    byte[] body = response.body();
                    if (body != null) {
                        skuApiData.set(new String(body, StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        });

        page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(ScraperConfig.PAGE_LOAD_TIMEOUT));
        // 等待关键元素出现，最多等3秒，比固定5秒快
        try {
            page.waitForSelector("img.preview-img, .active-preview-img, img.detail-gallery-img, .module-od-sku-selection, .pc-sku-wrapper, .ant-table-thead",
                    new Page.WaitForSelectorOptions().setTimeout(3000));
        } catch (Exception e) {
            // 超时也没关系，继续执行
        }

        // 检测布局
        String layout = detectLayout(page);
        if (layout == null) {
            log.warn("无法检测页面布局，跳过: {}", url);
            return null;
        }
        log.info("检测到布局: {}", layout);

        ProductData product = new ProductData();
        product.setUrl(url);
        product.setCategoryPath(link.getCategoryPath());
        product.setLayout(layout);

        // 提取标题
        log.info("[步骤] 提取标题...");
        String title = extractTitle(page);
        product.setTitle(title);
        log.info("[成功] 标题: {}", title);

        // 滚动触发懒加载
        log.info("[步骤] 滚动页面触发懒加载...");
        scrollPage(page);
        log.info("[成功] 滚动完成");

        // 提取主图
        log.info("[步骤] 提取主图...");
        List<String> mainImages = extractMainImages(page, layout);
        product.setMainImages(mainImages);
        log.info("[成功] 主图: {} 张", mainImages.size());

        // 提取详情图
        log.info("[步骤] 提取详情图...");
        List<String> detailImages = extractDetailImages(page, layout);
        product.setDetailImages(detailImages);
        log.info("[成功] 详情图: {} 张", detailImages.size());

        // 提取 SKU 基础信息（规格名+规格图）
        log.info("[步骤] 提取 SKU 信息...");
        List<SkuData> skuList = extractSkuBaseInfo(page, layout);

        // 合并 API 定价数据
        if (skuApiData.get() != null) {
            log.info("[成功] SKU API 定价数据获取成功，共 {} 个SKU", skuList.size());
            mergeSkuPricing(skuList, skuApiData.get());
        } else {
            log.warn("[降级] SKU API 未拦截到，使用 DOM 降级定价");
            // 降级：从 DOM 解析价格
            fallbackSkuPricing(page, layout, skuList);
        }

        // 提取运费
        log.info("[步骤] 提取运费...");
        double shippingFee = extractShippingFee(page);
        product.setShippingFee(shippingFee);
        log.info("[成功] 运费: ¥{}", shippingFee);

        // 计算最终价格
        for (SkuData sku : skuList) {
            sku.setShippingFee(shippingFee);
            double finalPrice = (sku.getOriginalPrice() * ScraperConfig.PRICE_MULTIPLIER + ScraperConfig.PRICE_ADDITION)
                    + shippingFee * ScraperConfig.SHIPPING_MARKUP;
            sku.setFinalPrice(Math.round(finalPrice * 100.0) / 100.0);
        }
        product.setSkus(skuList);
        log.info("[成功] SKU 价格计算完成: {} 个", skuList.size());

        // 创建输出目录
        log.info("[步骤] 创建输出目录...");
        String productDir = createProductDirectory(link.getCategoryPath(), title);
        product.setProductDir(productDir);
        log.info("[成功] 目录: {}", productDir);

        // 下载图片
        log.info("[步骤] 下载主图...");
        downloadImages(product, "主图", mainImages, page);
        log.info("[成功] 主图下载完成");

        log.info("[步骤] 下载详情图...");
        downloadImages(product, "详情图", detailImages, page);
        log.info("[成功] 详情图下载完成");

        log.info("[步骤] 下载 SKU 图...");
        List<String> skuImageUrls = new ArrayList<>();
        for (SkuData sku : skuList) {
            if (sku.getImageUrl() != null && !sku.getImageUrl().isEmpty()) {
                skuImageUrls.add(sku.getImageUrl());
            } else {
                skuImageUrls.add("");
            }
        }
        downloadImages(product, "SKU图", skuImageUrls, page);
        log.info("[成功] SKU 图下载完成");

        // 生成价格表 CSV
        log.info("[步骤] 生成价格表 CSV...");
        generatePriceCsv(product);
        log.info("[成功] 价格表 CSV 已生成");

        log.info("商品采集完成: {} - {} 张主图, {} 张详情图, {} 个SKU",
                title, mainImages.size(), detailImages.size(), skuList.size());

        // 校验：主图和详情图至少各有一张
        if (mainImages.isEmpty()) {
            throw new RuntimeException("采集失败: " + title + " - 未提取到主图");
        }
        if (detailImages.isEmpty()) {
            throw new RuntimeException("采集失败: " + title + " - 未提取到详情图");
        }

        return product;
    }

    // ==================== Layout Detection ====================

    private String detectLayout(Page page) {
        try {
            // 1. 表格型SKU（A型）- 最独特
            ElementHandle el = page.querySelector(".ant-table-thead");
            if (el != null) return "A";

            // 2. 模块型SKU（B型）- .module-od-sku-selection
            el = page.querySelector(".module-od-sku-selection");
            if (el != null) return "B";

            // 3. 标准移动版（A型）- 有 v-detail shadow DOM
            el = page.querySelector("img.preview-img, .active-preview-img");
            if (el != null) return "A";

            // 4. 分销版/consign版（C型）
            el = page.querySelector("img.detail-gallery-img, .pc-sku-wrapper, div.layout-two-columns-main");
            if (el != null) return "C";

            // 5. URL fallback
            String url = page.url();
            if (url.contains("consign") || url.contains("amug_biz=distribution")) {
                return "C";
            }
        } catch (Exception e) {
            log.debug("布局检测异常: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Title Extraction ====================

    private String extractTitle(Page page) {
        try {
            ElementHandle el = page.querySelector("h1.offer-title, .offer-title, .detail-title, .mod-title");
            if (el != null) {
                String text = el.textContent().trim();
                if (!text.isEmpty()) return sanitizeFileName(text);
            }
        } catch (Exception e) {
            // ignore
        }
        return sanitizeFileName(page.title());
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ==================== Scroll ====================

    private void scrollPage(Page page) {
        // 快速滚到底部一次，触发懒加载
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
        page.waitForTimeout(1500);
        // 滚回顶部
        page.evaluate("window.scrollTo(0, 0)");
        page.waitForTimeout(300);
    }

    // ==================== Main Image Extraction ====================

    @SuppressWarnings("unchecked")
    private List<String> extractMainImages(Page page, String layout) {
        List<String> images = new ArrayList<>();

        if ("A".equals(layout)) {
            try {
                List<ElementHandle> imgElements = page.querySelectorAll("img.preview-img, .active-preview-img");
                for (ElementHandle el : imgElements) {
                    String src = el.getAttribute("src");
                    if (src != null && !src.isEmpty()) {
                        images.add(UrlCleaner.clean(src));
                    }
                }
            } catch (Exception e) {
                // fallback to JS
            }
        } else if ("B".equals(layout)) {
            // 布局B: 主图通常在顶部轮播区域
            try {
                List<ElementHandle> imgElements = page.querySelectorAll("img.detail-gallery-img");
                for (ElementHandle el : imgElements) {
                    String src = el.getAttribute("src");
                    if (src != null && !src.isEmpty()) {
                        images.add(UrlCleaner.clean(src));
                    }
                }
            } catch (Exception e) {
                log.debug("B型主图提取失败: {}", e.getMessage());
            }
        } else {
            // 布局C
            try {
                List<ElementHandle> imgElements = page.querySelectorAll("img.detail-gallery-img");
                for (ElementHandle el : imgElements) {
                    String src = el.getAttribute("src");
                    if (src != null && !src.isEmpty()) {
                        images.add(UrlCleaner.clean(src));
                    }
                }
            } catch (Exception e) {
                // fallback
            }
        }

        // Fallback: try JS evaluation
        if (images.isEmpty()) {
            Object result = page.evaluate("() => {\n" +
                    "  const imgs = document.querySelectorAll('img');\n" +
                    "  const urls = [];\n" +
                    "  for (const img of imgs) {\n" +
                    "    const src = img.src || '';\n" +
                    "    if (src.includes('alicdn') && src.includes('cbu01') &&\n" +
                    "        (src.includes('O1CN') || src.includes('img/ib/'))) {\n" +
                    "      if (!urls.includes(src)) urls.push(src);\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return urls.slice(0, 10);\n" +
                    "}");
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item instanceof String) {
                        images.add(UrlCleaner.clean((String) item));
                    }
                }
            }
        }

        return images;
    }

    // ==================== Detail Image Extraction ====================

    @SuppressWarnings("unchecked")
    private List<String> extractDetailImages(Page page, String layout) {
        List<String> images = new ArrayList<>();

        if ("A".equals(layout)) {
            images = extractDetailImagesFromShadowDOM(page, true);
        } else {
            // B/C: 先从 shadow DOM 提取（宽松模式）
            images = extractDetailImagesFromShadowDOM(page, false);
            // 如果没有找到，降级使用 A 型逻辑（img[usemap]）
            if (images.isEmpty()) {
                images = extractDetailImagesFromShadowDOM(page, true);
            }
            // 如果还是没有，降级到 layout-two-columns-main
            if (images.isEmpty()) {
                Object result = page.evaluate("() => {\n" +
                        "  const container = document.querySelector('.layout-two-columns-main');\n" +
                        "  if (!container) return [];\n" +
                        "  const imgs = container.querySelectorAll('img');\n" +
                        "  return Array.from(imgs).map(img => img.src).filter(src => src && src.includes('alicdn'));\n" +
                        "}");
                if (result instanceof List) {
                    for (Object item : (List<?>) result) {
                        if (item instanceof String) {
                            images.add(UrlCleaner.clean((String) item));
                        }
                    }
                }
            }
            // 第三种逻辑：从 .sdmap-dynamic-offer-list 提取 img[usemap]（排除 backup 图）
            if (images.isEmpty()) {
                Object result = page.evaluate("() => {\n" +
                        "  const container = document.querySelector('.sdmap-dynamic-offer-list');\n" +
                        "  if (!container) return [];\n" +
                        "  const imgs = container.querySelectorAll('img[usemap]');\n" +
                        "  return Array.from(imgs).filter(img => !img.classList.contains('dynamic-backup-img') && !(img.title && img.title.includes('预览状态下无法点击'))).map(img => img.src);\n" +
                        "}");
                if (result instanceof List) {
                    for (Object item : (List<?>) result) {
                        if (item instanceof String) {
                            images.add(UrlCleaner.clean((String) item));
                        }
                    }
                }
            }
        }

        return images;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractDetailImagesFromShadowDOM(Page page, boolean strict) {
        List<String> images = new ArrayList<>();
        String evalScript;
        if (strict) {
            // A型严格模式: 只提取 img[usemap]，排除特定元素
            evalScript = "() => {\n" +
                    "  const images = [];\n" +
                    "  const allEls = document.querySelectorAll('*');\n" +
                    "  const detailEls = [];\n" +
                    "  for (const el of allEls) {\n" +
                    "    if (el.tagName.toLowerCase().startsWith('v-detail')) {\n" +
                    "      detailEls.push(el);\n" +
                    "    }\n" +
                    "  }\n" +
                    "  for (const detailEl of detailEls) {\n" +
                    "    if (!detailEl.shadowRoot) continue;\n" +
                    "    const imgs = detailEl.shadowRoot.querySelectorAll('img[usemap]');\n" +
                    "    for (const img of imgs) {\n" +
                    "      if (img.closest('.sdmap-dynamic-offer-list')) continue;\n" +
                    "      if (img.style.display === 'none' || getComputedStyle(img).display === 'none') continue;\n" +
                    "      if (img.classList.contains('dynamic-backup-img')) continue;\n" +
                    "      const src = img.src || img.dataset.src || '';\n" +
                    "      if (src && !images.includes(src)) images.push(src);\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return images;\n" +
                    "}";
        } else {
            // B/C型宽松模式: 提取 shadow DOM 中所有 alicdn 图片
            evalScript = "() => {\n" +
                    "  const images = [];\n" +
                    "  const allEls = document.querySelectorAll('*');\n" +
                    "  const detailEls = [];\n" +
                    "  for (const el of allEls) {\n" +
                    "    if (el.tagName.toLowerCase().startsWith('v-detail')) {\n" +
                    "      detailEls.push(el);\n" +
                    "    }\n" +
                    "  }\n" +
                    "  for (const detailEl of detailEls) {\n" +
                    "    if (!detailEl.shadowRoot) continue;\n" +
                    "    const imgs = detailEl.shadowRoot.querySelectorAll('img');\n" +
                    "    for (const img of imgs) {\n" +
                    "      if (img.style.display === 'none' || getComputedStyle(img).display === 'none') continue;\n" +
                    "      const src = img.src || img.dataset.src || '';\n" +
                    "      if (src && src.includes('alicdn') && !images.includes(src)) images.push(src);\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return images;\n" +
                    "}";
        }
        try {
            Object result = page.evaluate(evalScript);
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item instanceof String) {
                        images.add(UrlCleaner.clean((String) item));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Shadow DOM 详情图提取失败: {}", e.getMessage());
        }
        return images;
    }

    // ==================== SKU Base Info Extraction ====================

    @SuppressWarnings("unchecked")
    private List<SkuData> extractSkuBaseInfo(Page page, String layout) {
        List<SkuData> skuList = new ArrayList<>();

        // 三种SKU结构判断：
        // A: 表格型 .ant-table-thead
        // B: 模块型 .module-od-sku-selection（.feature-item + .expand-view-item）
        // C: 简单型 .pc-sku-wrapper / .sku-item-wrapper

        Object hasTableSku = page.evaluate("() => document.querySelector('.ant-table-thead') !== null");
        Object hasModuleSku = page.evaluate("() => document.querySelector('.module-od-sku-selection') !== null");

        if (Boolean.TRUE.equals(hasTableSku)) {
            // 多动态表格型SKU
            Object result = page.evaluate("() => {\n" +
                    "  const thead = document.querySelector('.ant-table-thead');\n" +
                    "  const tbody = document.querySelector('.ant-table-tbody');\n" +
                    "  if (!thead || !tbody) return null;\n" +
                    "  \n" +
                    "  // 提取表头字段名\n" +
                    "  const headers = [];\n" +
                    "  const ths = thead.querySelectorAll('th.ant-table-cell');\n" +
                    "  for (const th of ths) {\n" +
                    "    headers.push(th.textContent.trim());\n" +
                    "  }\n" +
                    "  console.log('[SKU表格] 字段: ' + headers.join(' | '));\n" +
                    "  \n" +
                    "  const skus = [];\n" +
                    "  const rows = tbody.querySelectorAll('tr.ant-table-row');\n" +
                    "  for (const row of rows) {\n" +
                    "    const cells = row.querySelectorAll('.ant-table-cell');\n" +
                    "    const values = [];\n" +
                    "    let imgUrl = '';\n" +
                    "    let price = 0, stock = 0;\n" +
                    "    const detailMap = {};\n" +
                    "    for (let i = 0; i < cells.length; i++) {\n" +
                    "      const cell = cells[i];\n" +
                    "      const img = cell.querySelector('img');\n" +
                    "      if (img && !imgUrl) imgUrl = img.src || '';\n" +
                    "      const text = cell.textContent.trim();\n" +
                    "      if (!text) continue;\n" +
                    "      if (i < headers.length) detailMap[headers[i]] = text;\n" +
                    "      // 判断是否是价格/库存列\n" +
                    "      if (cell.querySelector('.gyp-pro-table-price')) {\n" +
                    "        const pm = text.match(/[¥￥]([\\d.]+)/);\n" +
                    "        const sm = text.match(/(\\d+)/);\n" +
                    "        if (pm) price = parseFloat(pm[1]);\n" +
                    "        if (sm) stock = parseInt(sm[1]);\n" +
                    "        continue;\n" +
                    "      }\n" +
                    "      if (cell.querySelector('.ant-input-number')) continue;\n" +
                    "      values.push(text);\n" +
                    "    }\n" +
                    "    const specName = values.join(' / ');\n" +
                    "    // 打印每个SKU的所有字段\n" +
                    "    const detailStr = Object.entries(detailMap)\n" +
                    "      .filter(([k,v]) => k !== '价格 | 库存(个)' && k !== '进货数量')\n" +
                    "      .map(([k,v]) => k + '=' + v)\n" +
                    "      .join(', ');\n" +
                    "    console.log('[SKU表格] 字段: ' + detailStr + ' | 价格=' + price + ' | 库存=' + stock);\n" +
                    "    skus.push({ specName: specName, detailFields: detailStr, imageUrl: imgUrl, price: price, stock: stock });\n" +
                    "  }\n" +
                    "  console.log('[SKU表格] 共 ' + skus.length + ' 个SKU');\n" +
                    "  return skus;\n" +
                    "}");
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) item;
                        SkuData sku = new SkuData();
                        sku.setSpecName(getStr(map, "specName"));
                        sku.setDetailFields(getStr(map, "detailFields"));
                        sku.setImageUrl(UrlCleaner.clean(getStr(map, "imageUrl")));
                        Double price = getDouble(map, "price");
                        if (price != null && price > 0) sku.setOriginalPrice(price);
                        Integer stock = getInt(map, "stock");
                        if (stock != null && stock > 0) sku.setStock(stock);
                        skuList.add(sku);
                    }
                }
            }
            log.info("多动态表格SKU解析结果: {} 个", skuList.size());
            // 展示SKU名称组合的字段
            if (!skuList.isEmpty()) {
                String firstDetail = skuList.get(0).getDetailFields();
                if (firstDetail != null && !firstDetail.isEmpty()) {
                    String[] parts = firstDetail.split(", ");
                    String[] fieldNames = new String[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        int eqIdx = parts[i].indexOf('=');
                        fieldNames[i] = eqIdx > 0 ? parts[i].substring(0, eqIdx) : parts[i];
                    }
                    log.info("SKU名称字段组合: {}", String.join(" / ", fieldNames));
                }
            }
        } else if (Boolean.TRUE.equals(hasModuleSku)) {
            // B型: 模块型（.module-od-sku-selection）
            // 第一个 .feature-item = 颜色/图片规格（.sku-filter-button 带图+名称）
            // 第二个 .feature-item = SKU明细（.expand-view-item 名称、价格、库存）
            Object result = page.evaluate("() => {\n" +
                    "  const skus = [];\n" +
                    "  const featureItems = document.querySelectorAll('.module-od-sku-selection .feature-item');\n" +
                    "  if (featureItems.length === 0) return skus;\n" +
                    "  \n" +
                    "  // 从第一个 feature-item 提取颜色选项（带图）\n" +
                    "  const colorButtons = featureItems[0].querySelectorAll('.sku-filter-button');\n" +
                    "  const colors = [];\n" +
                    "  for (const btn of colorButtons) {\n" +
                    "    const img = btn.querySelector('img');\n" +
                    "    const nameEl = btn.querySelector('.label-name');\n" +
                    "    colors.push({\n" +
                    "      name: nameEl ? nameEl.textContent.trim() : '',\n" +
                    "      image: img ? (img.src || '') : ''\n" +
                    "    });\n" +
                    "  }\n" +
                    "  console.log('[SKU模块B] 颜色选项: ' + colors.map(c => c.name).join(', '));\n" +
                    "  \n" +
                    "  // 从后续 feature-item 提取 expand-view-item（价格/库存/规格）\n" +
                    "  const expandItems = document.querySelectorAll('.expand-view-item');\n" +
                    "  if (expandItems.length === 0 && colors.length > 0) {\n" +
                    "    // 如果只有颜色没有expand，每个颜色就是一个SKU\n" +
                    "    for (let i = 0; i < colors.length; i++) {\n" +
                    "      skus.push({ specName: colors[i].name, imageUrl: colors[i].image, price: 0, stock: 0 });\n" +
                    "    }\n" +
                    "  } else {\n" +
                    "    for (const item of expandItems) {\n" +
                    "      const labelEl = item.querySelector('.item-label');\n" +
                    "      const expandName = labelEl ? (labelEl.getAttribute('title') || labelEl.textContent.trim()) : '';\n" +
                    "      \n" +
                    "      // 价格/库存\n" +
                    "      const priceStockEls = item.querySelectorAll('.item-price-stock');\n" +
                    "      let price = 0, stock = 0;\n" +
                    "      for (const ps of priceStockEls) {\n" +
                    "        const text = ps.textContent.trim();\n" +
                    "        const pm = text.match(/[¥￥]([\\d.]+)/);\n" +
                    "        const sm = text.match(/库存(\\d+)/);\n" +
                    "        if (pm) price = parseFloat(pm[1]);\n" +
                    "        if (sm) stock = parseInt(sm[1]);\n" +
                    "      }\n" +
                    "      \n" +
                    "      // 与颜色组合\n" +
                    "      if (colors.length > 0) {\n" +
                    "        for (const color of colors) {\n" +
                    "          const specName = color.name + ' / ' + expandName;\n" +
                    "          console.log('[SKU模块B] ' + specName + ' | 价格=' + price + ' | 库存=' + stock);\n" +
                    "          skus.push({ specName: specName, imageUrl: color.image, price: price, stock: stock });\n" +
                    "        }\n" +
                    "      } else {\n" +
                    "        // 没有颜色选项时，每个 expand-view-item 自带图片\n" +
                    "        const imgEl = item.querySelector('.item-image-icon img, .ant-image-img');\n" +
                    "        const imgUrl = imgEl ? (imgEl.src || '') : '';\n" +
                    "        console.log('[SKU模块B] ' + expandName + ' | 图片=' + imgUrl + ' | 价格=' + price + ' | 库存=' + stock);\n" +
                    "        skus.push({ specName: expandName, imageUrl: imgUrl, price: price, stock: stock });\n" +
                    "      }\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return skus;\n" +
                    "}");
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) item;
                        SkuData sku = new SkuData();
                        sku.setSpecName(getStr(map, "specName"));
                        sku.setImageUrl(UrlCleaner.clean(getStr(map, "imageUrl")));
                        Double price = getDouble(map, "price");
                        if (price != null && price > 0) sku.setOriginalPrice(price);
                        Integer stock = getInt(map, "stock");
                        if (stock != null && stock > 0) sku.setStock(stock);
                        skuList.add(sku);
                    }
                }
            }
            log.info("模块型SKU解析结果: {} 个", skuList.size());
        } else {
            // C型: 简单型（.pc-sku-wrapper 或 .sku-item-wrapper）
            Object result = page.evaluate("() => {\n" +
                    "  const skus = [];\n" +
                    "  let wrappers = document.querySelectorAll('.pc-sku-wrapper .sku-item-wrapper');\n" +
                    "  if (wrappers.length === 0) {\n" +
                    "    wrappers = document.querySelectorAll('.sku-item-wrapper');\n" +
                    "  }\n" +
                    "  for (const wrapper of wrappers) {\n" +
                    "    const imgDiv = wrapper.querySelector('.sku-item-image');\n" +
                    "    let imgUrl = '';\n" +
                    "    if (imgDiv) {\n" +
                    "      const style = imgDiv.getAttribute('style') || '';\n" +
                    "      const match = style.match(/url\\([\"']?([^\"')]+)[\"']?\\)/);\n" +
                    "      if (match) imgUrl = match[1];\n" +
                    "    }\n" +
                    "    const nameEl = wrapper.querySelector('.sku-item-name');\n" +
                    "    const priceEl = wrapper.querySelector('.discountPrice-price');\n" +
                    "    const stockEl = wrapper.querySelector('.sku-item-sale-num');\n" +
                    "    const specName = nameEl ? nameEl.textContent.trim() : '';\n" +
                    "    const priceText = priceEl ? priceEl.textContent.trim() : '';\n" +
                    "    const stockText = stockEl ? stockEl.textContent.trim() : '';\n" +
                    "    const priceMatch = priceText.match(/([\\d.]+)/);\n" +
                    "    const stockMatch = stockText.match(/(\\d+)/);\n" +
                    "    const price = priceMatch ? parseFloat(priceMatch[1]) : 0;\n" +
                    "    const stock = stockMatch ? parseInt(stockMatch[1]) : 0;\n" +
                    "    console.log('[SKU] ' + specName + ' | 价格=' + price + ' | 库存=' + stock);\n" +
                    "    skus.push({ specName: specName, imageUrl: imgUrl, price: price, stock: stock });\n" +
                    "  }\n" +
                    "  return skus;\n" +
                    "}");
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) item;
                        SkuData sku = new SkuData();
                        sku.setSpecName(getStr(map, "specName"));
                        sku.setImageUrl(UrlCleaner.clean(getStr(map, "imageUrl")));
                        Double price = getDouble(map, "price");
                        if (price != null && price > 0) sku.setOriginalPrice(price);
                        Integer stock = getInt(map, "stock");
                        if (stock != null && stock > 0) sku.setStock(stock);
                        skuList.add(sku);
                    }
                }
            }
            log.info("简单型SKU解析结果: {} 个", skuList.size());
        }

        // 对齐打印SKU结果
        int maxNameLen = 0;
        for (SkuData s : skuList) {
            int len = s.getSpecName().length();
            if (len > maxNameLen) maxNameLen = len;
        }
        StringBuilder logSb = new StringBuilder();
        for (int i = 0; i < skuList.size(); i++) {
            SkuData s = skuList.get(i);
            String name = String.format("%-" + maxNameLen + "s", s.getSpecName());
            String priceStr = String.format("%-8.2f", s.getOriginalPrice());
            String imgStr = s.getImageUrl() != null && !s.getImageUrl().isEmpty() ? "有" : "无";
            logSb.append(String.format("  SKU %-3d | 名称: %s | 价格: %s | 库存: %-6d | 图片: %s",
                    i + 1, name, priceStr, s.getStock(), imgStr));
            log.info(logSb.toString());
            logSb.setLength(0);
            if (s.getDetailFields() != null && !s.getDetailFields().isEmpty()) {
                log.info("         字段: {}", s.getDetailFields());
            }
        }

        return skuList;
    }

    // ==================== SKU Pricing from API ====================

    private void mergeSkuPricing(List<SkuData> skuList, String apiJson) {
        try {
            JsonNode root = mapper.readTree(apiJson);
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode()) {
                // try nested data field
                String dataStr = root.path("data").asText();
                if (!dataStr.isEmpty()) {
                    dataNode = mapper.readTree(dataStr);
                }
            }
            JsonNode skuInfoMap = dataNode.path("originalSkuInfoMap");
            if (skuInfoMap.isMissingNode() || skuInfoMap.isEmpty()) return;

            Iterator<Map.Entry<String, JsonNode>> fields = skuInfoMap.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String skuId = entry.getKey();
                JsonNode skuNode = entry.getValue();

                double price = skuNode.path("multiPrice").asDouble(0);
                int stock = skuNode.path("canBookCount").asInt(0);

                // 尝试匹配已有的 SKU
                for (SkuData sku : skuList) {
                    if (sku.getSkuId() == null || sku.getSkuId().isEmpty()) {
                        sku.setSkuId(skuId);
                        sku.setOriginalPrice(price);
                        sku.setStock(stock);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析SKU定价API失败: {}", e.getMessage());
        }
    }

    // ==================== Fallback SKU Pricing from DOM ====================

    private void fallbackSkuPricing(Page page, String layout, List<SkuData> skuList) {
        try {
            Object result = page.evaluate("() => {\n" +
                    "  const prices = [];\n" +
                    "  // 尝试从 expand-view-item 提取\n" +
                    "  const expandItems = document.querySelectorAll('.expand-view-item');\n" +
                    "  for (const item of expandItems) {\n" +
                    "    const text = item.textContent.trim();\n" +
                    "    const match = text.match(/[¥￥]([\\d.]+)/);\n" +
                    "    if (match) prices.push(parseFloat(match[1]));\n" +
                    "  }\n" +
                    "  return prices;\n" +
                    "}");

            if (result instanceof List) {
                List<?> priceList = (List<?>) result;
                for (int i = 0; i < Math.min(skuList.size(), priceList.size()); i++) {
                    if (skuList.get(i).getOriginalPrice() <= 0) {
                        try {
                            skuList.get(i).setOriginalPrice(Double.parseDouble(priceList.get(i).toString()));
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }
            }

            // 最后尝试从页面提取起购价
            if (skuList.stream().anyMatch(s -> s.getOriginalPrice() <= 0)) {
                String bodyText = page.textContent("body");
                Matcher m = Pattern.compile("[¥￥]([\\d.]+)起").matcher(bodyText);
                if (m.find()) {
                    double basePrice = Double.parseDouble(m.group(1));
                    for (SkuData sku : skuList) {
                        if (sku.getOriginalPrice() <= 0) {
                            sku.setOriginalPrice(basePrice);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("SKU降级定价失败: {}", e.getMessage());
        }
    }

    // ==================== Shipping Fee ====================

    private double extractShippingFee(Page page) {
        try {
            String bodyText = page.textContent("body");
            Matcher m = Pattern.compile("运费[：:]?\\s*[¥￥]\\s*([\\d.]+)").matcher(bodyText);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception e) {
            // ignore
        }
        return ScraperConfig.DEFAULT_SHIPPING_FEE;
    }

    // ==================== Directory Creation ====================

    private String createProductDirectory(String categoryPath, String title) {
        String linkFile = LinkFileReader.findLatestLinkFile().getParent();
        String categoryFolder = LinkFileReader.getCategoryFolderName(categoryPath);
        String productDir = linkFile + File.separator + categoryFolder + File.separator + title;

        Path path = Paths.get(productDir);
        try {
            Files.createDirectories(path.resolve("主图"));
            Files.createDirectories(path.resolve("详情图"));
            Files.createDirectories(path.resolve("SKU图"));
        } catch (IOException e) {
            log.error("创建目录失败: {}", productDir, e);
        }

        return productDir;
    }

    // ==================== Image Download ====================

    private void downloadImages(ProductData product, String type, List<String> images, Page page) {
        String dir = product.getProductDir() + File.separator + type;
        int total = images.size();
        AtomicInteger downloaded = new AtomicInteger(0);

        // 使用线程池并行下载
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                final int idx = i;
                final String url = images.get(i);
                if (url == null || url.isEmpty()) {
                    log.warn("跳过{} 第{}张: URL为空", type, i + 1);
                    continue;
                }
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    String filename = String.format("%s_%02d.jpg", type, idx + 1);
                    Path target = Paths.get(dir, filename);
                    try {
                        byte[] data = downloadImage(url);
                        if (data != null && data.length > 0) {
                            Files.write(target, data);
                            downloaded.incrementAndGet();
                        } else {
                            log.warn("下载{} 第{}张为空, URL: {}", type, idx + 1, url);
                        }
                    } catch (Exception e) {
                        log.warn("下载{} 第{}张失败: {} - {}", type, idx + 1, url, e.getMessage());
                    }
                }, executor);
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }
        log.info("[成功] {} 下载完成: {}/{} 张", type, downloaded.get(), total);
    }

    @SuppressWarnings("unchecked")
    private byte[] downloadViaBrowser(String url, Page page) {
        try {
            Object result = page.evaluate("async (url) => {\n" +
                    "  try {\n" +
                    "    // 使用 XMLHttpRequest 绕过 CORS\n" +
                    "    return new Promise((resolve) => {\n" +
                    "      const xhr = new XMLHttpRequest();\n" +
                    "      xhr.open('GET', url, true);\n" +
                    "      xhr.responseType = 'blob';\n" +
                    "      xhr.onload = function() {\n" +
                    "        if (xhr.status === 200) {\n" +
                    "          const reader = new FileReader();\n" +
                    "          reader.onloadend = () => resolve(reader.result);\n" +
                    "          reader.readAsDataURL(xhr.response);\n" +
                    "        } else {\n" +
                    "          resolve({ error: xhr.status });\n" +
                    "        }\n" +
                    "      };\n" +
                    "      xhr.onerror = function() { resolve({ error: 'XHR error' }); };\n" +
                    "      xhr.send();\n" +
                    "    });\n" +
                    "  } catch (e) {\n" +
                    "    return { error: e.message };\n" +
                    "  }\n" +
                    "}", url);
            if (result instanceof String) {
                String dataUrl = (String) result;
                int commaIdx = dataUrl.indexOf(',');
                if (commaIdx > 0) {
                    String base64 = dataUrl.substring(commaIdx + 1);
                    return Base64.getDecoder().decode(base64);
                }
            } else if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> err = (Map<String, Object>) result;
            } else {
                log.warn("downloadViaBrowser unexpected result type: {}", result != null ? result.getClass().getName() : "null");
            }
        } catch (Exception e) {
            log.warn("downloadViaBrowser exception: {} - {}", e.getMessage(), url);
        }
        return null;
    }

    private byte[] downloadImage(String url) {
        try {
            java.net.URI uri = URI.create(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://detail.1688.com/");
            try (java.io.InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("downloadImage error: {} - {}", url, e.getMessage());
            return null;
        }
    }

    // ==================== CSV Generation ====================

    private void generatePriceCsv(ProductData product) {
        String csvPath = product.getProductDir() + File.separator + "价格表_temp_.csv";
        String finalPath = product.getProductDir() + File.separator + "价格表.csv";

        StringBuilder sb = new StringBuilder();

        // 从第一个SKU的detailFields提取字段名，追加到SKU名称后面
        String detailHeader = "";
        if (!product.getSkus().isEmpty()) {
            String firstDetail = product.getSkus().get(0).getDetailFields();
            if (firstDetail != null && !firstDetail.isEmpty()) {
                String[] parts = firstDetail.split(", ");
                String[] fieldNames = new String[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    int eqIdx = parts[i].indexOf('=');
                    fieldNames[i] = eqIdx > 0 ? parts[i].substring(0, eqIdx) : parts[i];
                }
                detailHeader = "（" + String.join(" / ", fieldNames) + "）";
            }
        }

        sb.append("SKU_ID,SKU名称").append(detailHeader).append(",原价(批发价),运费,加价公式,修改后价格,8折价格,最终利润,库存\n");

        int index = 1;
        for (SkuData sku : product.getSkus()) {
            String skuId = sku.getSkuId() != null && !sku.getSkuId().isEmpty()
                    ? sku.getSkuId() : String.format("SKU_%03d", index);
            String formula = String.format("(%.1f×%.1f+%.1f)+%.1f×%.1f",
                    sku.getOriginalPrice(), ScraperConfig.PRICE_MULTIPLIER, ScraperConfig.PRICE_ADDITION,
                    sku.getShippingFee(), ScraperConfig.SHIPPING_MARKUP);
            double discountPrice = sku.getFinalPrice() * 0.8;
            double profit = discountPrice - sku.getOriginalPrice();

            sb.append(String.format("%s,%s,%.1f,%.1f,%s,%.2f,%.2f,%.2f,%d\n",
                    skuId, sku.getSpecName(), sku.getOriginalPrice(),
                    sku.getShippingFee(), formula, sku.getFinalPrice(),
                    discountPrice, profit, sku.getStock()));
            index++;
        }

        try {
            Files.write(Paths.get(csvPath), sb.toString().getBytes(StandardCharsets.UTF_8));
            // 原子重命名避免 Windows 文件锁
            Files.move(Paths.get(csvPath), Paths.get(finalPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("生成价格表CSV失败: {}", e.getMessage());
        }
    }

    // ==================== Helpers ====================

    private String getStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return val != null ? Double.parseDouble(val.toString()) : null; } catch (NumberFormatException e) { return null; }
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        try { return val != null ? Integer.parseInt(val.toString()) : null; } catch (NumberFormatException e) { return null; }
    }
}
