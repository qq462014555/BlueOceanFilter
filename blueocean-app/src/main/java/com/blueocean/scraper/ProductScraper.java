package com.blueocean.scraper;

import com.blueocean.ocr.PaddleOcrService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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

    private final PaddleOcrService ocrService;

    public ProductScraper(PaddleOcrService ocrService) {
        this.ocrService = ocrService;
    }

    @SuppressWarnings("unchecked")
    public List<ProductData> scrapeProducts(List<LinkEntry> links) throws Exception {
        List<ProductData> results = new CopyOnWriteArrayList<>();

        // 加载已完成的 URL 集合
        Set<String> doneUrls = Collections.synchronizedSet(loadDoneUrls());
        log.info("已完成的 URL 数量: {}", doneUrls.size());

        List<LinkEntry> pendingLinks = new ArrayList<>();
        for (LinkEntry link : links) {
            String cleanUrl = UrlCleaner.clean(link.getUrl());
            if (doneUrls.contains(cleanUrl)) {
                // 如果有RPA标题且和现有目录不一致，重新处理
                if (link.getTitle() != null && !link.getTitle().isEmpty()) {
                    String rpaTitle = sanitizeFileName(link.getTitle());
                    String linkFile = com.blueocean.scraper.LinkFileReader.findLatestLinkFile().getParent();
                    String categoryFolder = com.blueocean.scraper.LinkFileReader.getCategoryFolderName(link.getCategoryPath());
                    String expectedDir = linkFile + File.separator + categoryFolder + File.separator + rpaTitle;
                    if (!new File(expectedDir).exists()) {
                        log.info("[重新抓取] RPA标题='{}' 与现有目录不一致，重新处理: {}", link.getTitle(), cleanUrl);
                        pendingLinks.add(link);
                        continue;
                    }
                }
                log.info("[跳过] 已完结: {}", link.getCategoryPath());
            } else {
                pendingLinks.add(link);
            }
        }

        if (pendingLinks.isEmpty()) {
            log.info("所有链接均已完结，无需抓取");
            return results;
        }

        // 单 Playwright + Browser 连接，复用 Chrome 已有 context
        Playwright playwright = Playwright.create();
        try {
            Browser browser = playwright.chromium().connectOverCDP(ScraperConfig.CDP_ENDPOINT);
            log.info("CDP 连接成功: {}", ScraperConfig.CDP_ENDPOINT);

            BrowserContext mainContext = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);

            int concurrency = 1;
            // 间隔 2 秒逐个创建 tab，避免同时弹出触发 1688 风控
            List<Page> pages = new ArrayList<>();
            for (int t = 0; t < concurrency; t++) {
                if (t > 0) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
                pages.add(mainContext.newPage());
                log.info("已创建第 {} 个 tab", t + 1);
            }

            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            java.util.Queue<LinkEntry> queue = new java.util.concurrent.ConcurrentLinkedQueue<>(pendingLinks);
            AtomicInteger processedCount = new AtomicInteger(0);

            for (int t = 0; t < concurrency; t++) {
                final int threadIndex = t;
                final Page page = pages.get(t);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    String threadName = "worker-" + (threadIndex + 1);

                    // 错开初始导航时间，避免多个 tab 同时导航导致 CDP 协议冲突
                    try { Thread.sleep(threadIndex * 2000L); } catch (InterruptedException ie) { return; }

                    try {
                        LinkEntry link;
                        while ((link = queue.poll()) != null) {
                            String cleanUrl = UrlCleaner.clean(link.getUrl());
                            int idx = processedCount.incrementAndGet();
                            log.info("[{}] [{}/{}] 抓取: {} ({})", threadName, idx, pendingLinks.size(), link.getUrl(), link.getCategoryPath());

                            int retries = 0;
                            boolean success = false;
                            while (retries < ScraperConfig.MAX_RETRIES) {
                                try {
                                    ProductData product = scrapeSingleProduct(page, link);
                                    if (product != null) {
                                        results.add(product);
                                        markDone(cleanUrl, product.getTitle());
                                        success = true;
                                    }
                                    break;
                                } catch (Exception e) {
                                    retries++;
                                    log.warn("[{}] 第 {} 次重试失败: {}", threadName, retries, e.getMessage());
                                    if (retries >= ScraperConfig.MAX_RETRIES) {
                                        log.error("[{}] 商品抓取失败，已重试 {} 次: {}", threadName, ScraperConfig.MAX_RETRIES, link.getUrl());
                                    } else {
                                        try { Thread.sleep(retries * 2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                                    }
                                }
                            }
                            if (!success) {
                                // 不关闭页面，导航到空白页重置状态后继续处理下一个
                                try { page.navigate("about:blank"); } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception e) {
                        log.error("[{}] Worker 异常: {}", threadName, e.getMessage());
                    }
                }, executor);

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();

            // 关闭所有创建的 tab，不关闭浏览器
            for (Page pg : pages) {
                try { pg.close(); } catch (Exception ignored) {}
            }
            log.info("采集完成，浏览器保持运行不关闭");
        } catch (Exception e) {
            log.error("采集过程异常", e);
        }
        // 不调用 playwright.close()，保持浏览器进程运行

        return results;
    }

    @SuppressWarnings("unchecked")
    private ProductData scrapeSingleProduct(Page page, LinkEntry link) {
        String url = UrlCleaner.clean(link.getUrl());
        log.info(">>>> RPA标题='{}' 类目='{}'", link.getTitle(), link.getCategoryPath());

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

        log.info("[调试] link.getTitle() = '{}'", link.getTitle());

        // 提取标题
        log.info("[步骤] 提取标题...");
        String title = extractTitle(page);
        product.setTitle(title);
        log.info("[成功] 标题: {}", title);

        // 提取商品属性
        log.info("[步骤] 提取商品属性...");
        Map<String, String> attributes = extractAttributes(page);
        product.setAttributes(attributes);
        log.info("[成功] 属性: {} 个", attributes.size());

        // 提取商品视频
        log.info("[步骤] 提取商品视频...");
        String videoUrl = extractVideoUrl(page);
        product.setVideoUrl(videoUrl);
        if (videoUrl != null && !videoUrl.isEmpty()) {
            log.info("[成功] 视频: {}", videoUrl);
        } else {
            log.info("[无] 该商品无视频");
        }

        // 提取包装信息
        log.info("[步骤] 提取包装信息...");
        List<Map<String, String>> packInfo = extractPackInfo(page);
        product.setPackInfo(packInfo);
        log.info("[成功] 包装信息: {} 条", packInfo.size());

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
        log.info("[成功] 详情图: {} 张", detailImages.size());

        // OCR 过滤详情图（已禁用）
        // filterDetailImagesByOcr(detailImages, title);
        product.setDetailImages(detailImages);
        log.info("[成功] 过滤后详情图: {} 张", detailImages.size());

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
        log.info("[调试] dirTitle 判断: link.getTitle()='{}', 爬取title='{}'", link.getTitle(), title);
        String dirTitle = (link.getTitle() != null && !link.getTitle().isEmpty()) ? link.getTitle() : title;
        log.info("[调试] 最终使用 dirTitle='{}'", dirTitle);
        String productDir = createProductDirectory(link.getCategoryPath(), dirTitle);
        product.setProductDir(productDir);
        log.info("[成功] 目录: {}", productDir);

        // 生成包装信息图片，放在详情图目录中
        log.info("[步骤] 生成包装信息图片...");
        String packImagePath = generatePackInfoImage(product, packInfo);
        log.info("[成功] 包装信息图片已保存: {}", packImagePath);

        // 保存采集到的商品属性到本地（供后续AI映射使用）
        log.info("[步骤] 保存商品属性...");
        com.blueocean.scraper.AttributeSaver.save(productDir, attributes);
        log.info("[成功] 商品属性已保存，共 {} 个", attributes.size());

        // 下载图片
        log.info("[步骤] 下载主图...");
        List<String> mainImagePaths = downloadImages(product, "主图", mainImages, page);
        product.setMainImages(mainImagePaths);
        log.info("[成功] 主图下载完成");

        log.info("[步骤] 下载详情图...");
        List<String> detailImagePaths = downloadImages(product, "详情图", detailImages, page);
        log.info("[成功] 详情图下载完成");

        // 包装信息图加入详情图列表首位（不参与下载，已存在本地）
        if (packImagePath != null) {
            detailImagePaths.add(0, packImagePath);
            product.setDetailImages(detailImagePaths);
            log.info("包装信息图片已加入详情图列表，当前总数: {}", detailImagePaths.size());
        }

        log.info("[步骤] 下载 SKU 图...");
        List<String> skuImageUrls = new ArrayList<>();
        for (SkuData sku : skuList) {
            if (sku.getImageUrl() != null && !sku.getImageUrl().isEmpty()) {
                skuImageUrls.add(sku.getImageUrl());
            } else {
                skuImageUrls.add("");
            }
        }
        List<String> skuImagePaths = downloadImages(product, "SKU图", skuImageUrls, page);
        // 更新 SKU 图片路径
        for (int i = 0; i < skuList.size() && i < skuImagePaths.size(); i++) {
            skuList.get(i).setImageUrl(skuImagePaths.get(i));
        }
        log.info("[成功] SKU 图下载完成");

        // 下载视频
        if (videoUrl != null && !videoUrl.isEmpty()) {
            log.info("[步骤] 下载视频...");
            String localVideoPath = downloadVideo(product, videoUrl);
            if (localVideoPath != null) {
                product.setVideoUrl(localVideoPath);
            }
            log.info("[成功] 视频下载完成");
        }

        // 生成价格表 CSV
        log.info("[步骤] 生成价格表 CSV...");
        generatePriceCsv(product);
        log.info("[成功] 价格表 CSV 已生成");

        // 保存完整商品数据为 JSON（供前端加载）
        log.info("[步骤] 保存完整商品数据 JSON...");
        saveProductJson(product, skuList);
        log.info("[成功] 完整商品数据 JSON 已保存");

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

            // 2. 行业版SKU（D型）- .industry-pro-sku-selection
             el = page.querySelector(".industry-pro-sku-selection");
            if (el != null) return "D";

            // 3. 模块型SKU（B型）- .module-od-sku-selection
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
        String cleaned = name.replaceAll("\\s*-\\s*阿里巴巴\\s*$", "").trim();
        return cleaned.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ==================== Attributes Extraction ====================

    @SuppressWarnings("unchecked")
    private Map<String, String> extractAttributes(Page page) {
        Map<String, String> attrs = new LinkedHashMap<>();
        try {
            Object result = page.evaluate("() => {\n" +
                    "  const container = document.querySelector('#productAttributes');\n" +
                    "  if (!container) return {};\n" +
                    "  const labels = container.querySelectorAll('.ant-descriptions-item-label span');\n" +
                    "  const values = container.querySelectorAll('.ant-descriptions-item-content .field-value');\n" +
                    "  const map = {};\n" +
                    "  for (let i = 0; i < labels.length && i < values.length; i++) {\n" +
                    "    map[labels[i].textContent.trim()] = values[i].textContent.trim();\n" +
                    "  }\n" +
                    "  return map;\n" +
                    "}");
            if (result instanceof Map) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) result).entrySet()) {
                    attrs.put(entry.getKey(), entry.getValue().toString());
                }
            }
        } catch (Exception e) {
            log.debug("商品属性提取失败: {}", e.getMessage());
        }
        return attrs;
    }

    // ==================== Pack Info Extraction ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> extractPackInfo(Page page) {
        List<Map<String, String>> packList = new ArrayList<>();
        try {
            Object result = page.evaluate("() => {\n" +
                    "  const container = document.querySelector('#productPackInfo');\n" +
                    "  if (!container) return [];\n" +
                    "  const ths = container.querySelectorAll('thead th');\n" +
                    "  const headers = Array.from(ths).map(th => th.textContent.trim());\n" +
                    "  const rows = container.querySelectorAll('tbody tr');\n" +
                    "  const data = [];\n" +
                    "  for (const row of rows) {\n" +
                    "    const tds = row.querySelectorAll('td');\n" +
                    "    const item = {};\n" +
                    "    for (let i = 0; i < headers.length && i < tds.length; i++) {\n" +
                    "      item[headers[i]] = tds[i].textContent.trim();\n" +
                    "    }\n" +
                    "    data.push(item);\n" +
                    "  }\n" +
                    "  return data;\n" +
                    "}");
            if (result instanceof List) {
                for (Object item : (List<?>) result) {
                    if (item instanceof Map) {
                        Map<String, String> map = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : ((Map<String, Object>) item).entrySet()) {
                            map.put(e.getKey(), e.getValue().toString());
                        }
                        packList.add(map);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("包装信息提取失败: {}", e.getMessage());
        }
        return packList;
    }

    /**
     * 将包装信息渲染为图片，保存在产品目录的"详情图"子目录下，名为 详情图_00.jpg
     * 返回生成图片的绝对路径，如果未生成则返回 null
     */
    private String generatePackInfoImage(ProductData product, List<Map<String, String>> packList) {
        if (packList == null || packList.isEmpty()) return null;

        String dir = product.getProductDir() + File.separator + "详情图";
        String fileName = "详情图_00.jpg";
        File outFile = new File(dir, fileName);

        // 如果已存在同名文件则跳过
        if (outFile.exists()) {
            log.info("包装信息图片已存在: {}", outFile.getAbsolutePath());
            return outFile.getAbsolutePath();
        }

        List<String> headers = new ArrayList<>(packList.get(0).keySet());
        int colCount = headers.size();
        int rowCount = packList.size() + 1;

        Font font = new Font("Microsoft YaHei", Font.PLAIN, 14);
        Font headerFont = new Font("Microsoft YaHei", Font.BOLD, 14);

        BufferedImage tmpImg = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tmpG = tmpImg.createGraphics();
        FontMetrics dataFm = tmpG.getFontMetrics(font);
        FontMetrics headerFm = tmpG.getFontMetrics(headerFont);
        tmpG.dispose();

        int[] colWidths = new int[colCount];
        for (int c = 0; c < colCount; c++) {
            colWidths[c] = headerFm.stringWidth(headers.get(c)) + 24;
        }
        for (Map<String, String> row : packList) {
            for (int c = 0; c < colCount; c++) {
                String val = row.get(headers.get(c));
                int w = dataFm.stringWidth(val != null ? val : "") + 24;
                colWidths[c] = Math.max(colWidths[c], w);
            }
        }

        int paddingX = 20;
        int paddingY = 16;
        int cellHeight = 28;

        int imgWidth = paddingX * 2;
        for (int w : colWidths) imgWidth += w;
        int imgHeight = paddingY * 2 + rowCount * cellHeight;

        BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, imgWidth, imgHeight);

        int y = paddingY;
        int x = paddingX;

        g.setFont(headerFont);
        g.setColor(new Color(245, 245, 245));
        g.fillRect(x, y, imgWidth - paddingX * 2, cellHeight);
        g.setColor(Color.BLACK);
        for (int c = 0; c < colCount; c++) {
            int w = colWidths[c];
            g.drawString(headers.get(c), x + 12, y + 19);
            g.setColor(new Color(200, 200, 200));
            g.drawRect(x, y, w, cellHeight);
            g.setColor(Color.BLACK);
            x += w;
        }
        y += cellHeight;

        g.setFont(font);
        for (Map<String, String> row : packList) {
            x = paddingX;
            for (int c = 0; c < colCount; c++) {
                int w = colWidths[c];
                String val = row.get(headers.get(c));
                g.drawString(val != null ? val : "", x + 12, y + 19);
                g.setColor(new Color(220, 220, 220));
                g.drawRect(x, y, w, cellHeight);
                g.setColor(Color.BLACK);
                x += w;
            }
            y += cellHeight;
        }

        g.dispose();

        try {
            ImageIO.write(image, "jpg", outFile);
            log.info("包装信息图片已生成: {}", outFile.getAbsolutePath());
            return outFile.getAbsolutePath();
        } catch (IOException e) {
            log.error("保存包装信息图片失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== OCR 过滤详情图 ====================

    /**
     * 从第一张详情图开始 OCR 判断：
     * 如果图片包含「定制/工厂」等字样，且不含商品相关文字 → 过滤
     * 遇到一张不需要过滤的图，后续图片全部保留不再判断
     */
    void filterDetailImagesByOcr(List<String> detailImages, String title) {
        if (ocrService == null) {
            log.info("OCR 服务未启用，跳过详情图过滤");
            return;
        }
        if (detailImages.isEmpty()) return;

        // 从标题提取商品关键词（取前3个词或整个标题）
        List<String> productKeywords = new ArrayList<>();
        if (title != null && !title.isEmpty()) {
            // 标题按空格/分隔符拆分，取前几个关键词
            String[] parts = title.split("[\\s\\-–_]+");
            for (int i = 0; i < Math.min(3, parts.length); i++) {
                if (parts[i].length() >= 2) {
                    productKeywords.add(parts[i].toLowerCase());
                }
            }
            // 也加入完整标题作为关键词
            productKeywords.add(title.toLowerCase());
        }

        // 工厂/定制相关关键词
        List<String> factoryKeywords = List.of("定制", "工厂", "厂家", "定制款", "工厂店", "生产", "加工");

        int filterCount = 0;
        int firstKept = -1;

        for (int i = 0; i < detailImages.size(); i++) {
            String imgUrl = detailImages.get(i);
            String ocrText = ocrService.recognizeText(imgUrl);
            log.info("[OCR过滤] 第{}张: {}", i + 1, ocrText.isEmpty() ? "(无文字)" : ocrText.substring(0, Math.min(50, ocrText.length())));

            // 遇到不需要过滤的图，后续全部保留
            if (firstKept >= 0) {
                break;
            }

            if (ocrText.isEmpty()) {
                // 无文字，继续下一张
                continue;
            }

            String textLower = ocrText.toLowerCase();

            // 判断是否含定制/工厂信息
            boolean hasFactory = factoryKeywords.stream().anyMatch(textLower::contains);
            // 判断是否含商品相关文字
            boolean hasProduct = productKeywords.stream().anyMatch(kw -> textLower.contains(kw.toLowerCase()));

            if (hasFactory && !hasProduct) {
                log.info("[OCR过滤] 第{}张过滤: 含定制/工厂信息，不含商品文字", i + 1);
                filterCount++;
                // 不移除，继续判断下一张
            } else {
                // 不需要过滤，从此位置开始后续全部保留
                firstKept = i;
                log.info("[OCR过滤] 第{}张保留，后续图片无需判断", i + 1);
            }
        }

        // 移除被过滤的图片
        if (filterCount > 0) {
            for (int i = 0; i < filterCount && !detailImages.isEmpty(); i++) {
                detailImages.remove(0);
            }
            log.info("[OCR过滤] 共过滤 {} 张图片，剩余 {} 张", filterCount, detailImages.size());
        } else {
            log.info("[OCR过滤] 无需过滤");
        }
    }

    // ==================== Video Extraction ====================

    private String extractVideoUrl(Page page) {
        try {
            Object result = page.evaluate("() => {\n" +
                    "  const videos = document.querySelectorAll('video.lib-video');\n" +
                    "  for (const v of videos) {\n" +
                    "    const src = v.getAttribute('src') || '';\n" +
                    "    if (src && (src.includes('taobao.com') || src.includes('alicdn') || src.includes('mp4'))) {\n" +
                    "      return src.startsWith('//') ? 'https:' + src : src;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return null;\n" +
                    "}");
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Exception e) {
            log.debug("视频URL提取失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Video Download ====================

    private String downloadVideo(ProductData product, String videoUrl) {
        String dir = product.getProductDir();
        String fileName = sanitizeFileName(product.getTitle()) + ".mp4";
        Path target = Paths.get(dir, fileName);

        try {
            java.net.URI uri = URI.create(videoUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://detail.1688.com/");
            try (java.io.InputStream is = conn.getInputStream()) {
                Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("视频已下载: {}", fileName);
                return target.toString();
            }
        } catch (Exception e) {
            log.warn("视频下载失败: {} - {}", videoUrl, e.getMessage());
        }
        return null;
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

        // 规则1: img.preview-img, .active-preview-img
        try {
            List<ElementHandle> imgElements = page.querySelectorAll("img.preview-img, .active-preview-img");
            for (ElementHandle el : imgElements) {
                String src = el.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    images.add(UrlCleaner.clean(src));
                }
            }
        } catch (Exception e) {
            log.debug("主图规则1失败: {}", e.getMessage());
        }

        // 规则2: img.detail-gallery-img
        if (images.isEmpty()) {
            try {
                List<ElementHandle> imgElements = page.querySelectorAll("img.detail-gallery-img");
                for (ElementHandle el : imgElements) {
                    String src = el.getAttribute("src");
                    if (src != null && !src.isEmpty()) {
                        images.add(UrlCleaner.clean(src));
                    }
                }
            } catch (Exception e) {
                log.debug("主图规则2失败: {}", e.getMessage());
            }
        }

        // 规则3: JS evaluation fallback
        if (images.isEmpty()) {
            try {
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
            } catch (Exception e) {
                log.debug("主图规则3(JS降级)失败: {}", e.getMessage());
            }
        }

        return images;
    }

    // ==================== Detail Image Extraction ====================

    @SuppressWarnings("unchecked")
    private List<String> extractDetailImages(Page page, String layout) {
        List<String> images = new ArrayList<>();

        // 规则1: A型 - shadow DOM strict mode
        if ("A".equals(layout)) {
            images = extractDetailImagesFromShadowDOM(page, true);
        }

        // 规则2: shadow DOM loose mode (always try if rule1 was empty or not A layout)
        if (images.isEmpty()) {
            images = extractDetailImagesFromShadowDOM(page, false);
        }

        // 规则3: .layout-two-columns-main JS evaluation
        if (images.isEmpty()) {
            try {
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
            } catch (Exception e) {
                log.debug("详情图规则3失败: {}", e.getMessage());
            }
        }

        // 规则4: .sdmap-dynamic-offer-list img[usemap]
        if (images.isEmpty()) {
            try {
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
            } catch (Exception e) {
                log.debug("详情图规则4失败: {}", e.getMessage());
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

        // 依次尝试三种 SKU 解析规则，有结果即停止

        // 规则1: 表格型 .ant-table-thead
        Object hasTableSku = page.evaluate("() => document.querySelector('.ant-table-thead') !== null");
        if (Boolean.TRUE.equals(hasTableSku)) {
            skuList = parseTableSku(page);
        }

        // 规则2: 模块型 .module-od-sku-selection
        if (skuList.isEmpty()) {
            Object hasModuleSku = page.evaluate("() => document.querySelector('.module-od-sku-selection') !== null");
            if (Boolean.TRUE.equals(hasModuleSku)) {
                skuList = parseModuleSku(page);
            }
        }

        // 规则3: 简单型 .pc-sku-wrapper / .sku-item-wrapper
        if (skuList.isEmpty()) {
            skuList = parseSimpleSku(page);
        }

        // 规则4: 行业版SKU (.industry-pro-sku-selection + .ant-table-tbody 单行)
        if (skuList.isEmpty()) {
            skuList = parseIndustrySku(page);
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

    // ==================== SKU Parsing Rules ====================

    @SuppressWarnings("unchecked")
    private List<SkuData> parseTableSku(Page page) {
        List<SkuData> skuList = new ArrayList<>();
        log.info("尝试规则1: 表格型SKU (.ant-table-thead)");

        Object result = page.evaluate("() => {\n" +
                "  const thead = document.querySelector('.ant-table-thead');\n" +
                "  const tbody = document.querySelector('.ant-table-tbody');\n" +
                "  if (!thead || !tbody) return null;\n" +
                "  const headers = [];\n" +
                "  const ths = thead.querySelectorAll('th.ant-table-cell');\n" +
                "  for (const th of ths) { headers.push(th.textContent.trim()); }\n" +
                "  const skus = [];\n" +
                "  const rows = tbody.querySelectorAll('tr.ant-table-row');\n" +
                "  for (const row of rows) {\n" +
                "    const cells = row.querySelectorAll('.ant-table-cell');\n" +
                "    const values = [];\n" +
                "    let imgUrl = '', price = 0, stock = 0;\n" +
                "    const detailMap = {};\n" +
                "    for (let i = 0; i < cells.length; i++) {\n" +
                "      const cell = cells[i];\n" +
                "      const img = cell.querySelector('img');\n" +
                "      if (img && !imgUrl) imgUrl = img.src || '';\n" +
                "      const text = cell.textContent.trim();\n" +
                "      if (!text) continue;\n" +
                "      if (cell.querySelector('.gyp-pro-table-price')) {\n" +
                "        const spans = cell.querySelectorAll('.gyp-pro-table-price span');\n" +
                "        if (spans.length >= 2) {\n" +
                "          const priceText = spans[0].textContent.trim();\n" +
                "          const stockText = spans[1].textContent.trim();\n" +
                "          const pm = priceText.match(/[¥￥]([\\d.]+)/);\n" +
                "          if (pm) price = parseFloat(pm[1]);\n" +
                "          stock = parseInt(stockText) || 0;\n" +
                "          if (i < headers.length) {\n" +
                "            detailMap[headers[i]] = priceText + ' | ' + stockText;\n" +
                "          }\n" +
                "        }\n" +
                "        continue;\n" +
                "      }\n" +
                "      if (cell.querySelector('.ant-input-number')) continue;\n" +
                "      if (i < headers.length) detailMap[headers[i]] = text;\n" +
                "      values.push(text);\n" +
                "    }\n" +
                "    const specName = values.join(' / ');\n" +
                "    const detailStr = Object.entries(detailMap)\n" +
                "      .filter(([k,v]) => k !== '价格 | 库存(个)' && k !== '进货数量')\n" +
                "      .map(([k,v]) => k + '=' + v).join(', ');\n" +
                "    skus.push({ specName, detailFields: detailStr, imageUrl: imgUrl, price, stock });\n" +
                "  }\n" +
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
        log.info("规则1解析结果: {} 个SKU", skuList.size());
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
        return skuList;
    }

    @SuppressWarnings("unchecked")
    private List<SkuData> parseModuleSku(Page page) {
        List<SkuData> skuList = new ArrayList<>();
        log.info("尝试规则2: 模块型SKU (.module-od-sku-selection)");

        Object result = page.evaluate("() => {\n" +
                "  const skus = [];\n" +
                "  const featureItems = document.querySelectorAll('.module-od-sku-selection .feature-item');\n" +
                "  if (featureItems.length === 0) return skus;\n" +
                "  const colorButtons = featureItems[0].querySelectorAll('.sku-filter-button');\n" +
                "  const colors = [];\n" +
                "  for (const btn of colorButtons) {\n" +
                "    const img = btn.querySelector('img');\n" +
                "    const nameEl = btn.querySelector('.label-name');\n" +
                "    colors.push({ name: nameEl ? nameEl.textContent.trim() : '', image: img ? (img.src || '') : '' });\n" +
                "  }\n" +
                "  const expandItems = document.querySelectorAll('.expand-view-item');\n" +
                "  if (expandItems.length === 0 && colors.length > 0) {\n" +
                "    for (let i = 0; i < colors.length; i++) {\n" +
                "      skus.push({ specName: colors[i].name, imageUrl: colors[i].image, price: 0, stock: 0 });\n" +
                "    }\n" +
                "  } else {\n" +
                "    for (const item of expandItems) {\n" +
                "      const labelEl = item.querySelector('.item-label');\n" +
                "      const expandName = labelEl ? (labelEl.getAttribute('title') || labelEl.textContent.trim()) : '';\n" +
                "      const priceStockEls = item.querySelectorAll('.item-price-stock');\n" +
                "      let price = 0, stock = 0;\n" +
                "      for (const ps of priceStockEls) {\n" +
                "        const text = ps.textContent.trim();\n" +
                "        const pm = text.match(/[¥￥]([\\d.]+)/);\n" +
                "        const sm = text.match(/库存(\\d+)/);\n" +
                "        if (pm) price = parseFloat(pm[1]);\n" +
                "        if (sm) stock = parseInt(sm[1]);\n" +
                "      }\n" +
                "      if (colors.length > 0) {\n" +
                "        for (const color of colors) {\n" +
                "          skus.push({ specName: color.name + ' / ' + expandName, imageUrl: color.image, price, stock });\n" +
                "        }\n" +
                "      } else {\n" +
                "        const imgEl = item.querySelector('.item-image-icon img, .ant-image-img');\n" +
                "        const imgUrl = imgEl ? (imgEl.src || '') : '';\n" +
                "        skus.push({ specName: expandName, imageUrl: imgUrl, price, stock });\n" +
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
        log.info("规则2解析结果: {} 个SKU", skuList.size());
        return skuList;
    }

    @SuppressWarnings("unchecked")
    private List<SkuData> parseSimpleSku(Page page) {
        List<SkuData> skuList = new ArrayList<>();
        log.info("尝试规则3: 简单型SKU (.pc-sku-wrapper / .sku-item-wrapper)");

        Object result = page.evaluate("() => {\n" +
                "  const skus = [];\n" +
                "  let wrappers = document.querySelectorAll('.pc-sku-wrapper .sku-item-wrapper');\n" +
                "  if (wrappers.length === 0) wrappers = document.querySelectorAll('.sku-item-wrapper');\n" +
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
                "    skus.push({ specName, imageUrl: imgUrl, price, stock });\n" +
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
        log.info("规则3解析结果: {} 个SKU", skuList.size());
        return skuList;
    }

    @SuppressWarnings("unchecked")
    private List<SkuData> parseIndustrySku(Page page) {
        List<SkuData> skuList = new ArrayList<>();
        log.info("尝试规则4: 行业版SKU (.industry-pro-sku-selection)");

        Object result = page.evaluate("() => {\n" +
                "  const skus = [];\n" +
                "  \n" +
                "  // 从上方 filter 区域提取 SKU 名称（只取第一个 li）\n" +
                "  const firstLi = document.querySelector('.industry-pro-sku-selection-props-panel ul li');\n" +
                "  const skuImg = document.querySelector('.industry-pro-sku-selection-props-panel img');\n" +
                "  let specName = '';\n" +
                "  if (firstLi) {\n" +
                "    const spans = firstLi.querySelectorAll('span');\n" +
                "    if (spans.length >= 2) {\n" +
                "      specName = spans[1].textContent.trim();\n" +
                "    }\n" +
                "  }\n" +
                "  const imgUrl = skuImg ? (skuImg.src || '') : '';\n" +
                "  \n" +
                "  // 从下方 ant-table-tbody 单行提取价格/库存\n" +
                "  const priceCell = document.querySelector('.gyp-pro-table-price span');\n" +
                "  let price = 0, stock = 0;\n" +
                "  if (priceCell) {\n" +
                "    const priceText = priceCell.textContent.trim();\n" +
                "    const pm = priceText.match(/[¥￥]([\\d.]+)/);\n" +
                "    if (pm) price = parseFloat(pm[1]);\n" +
                "  }\n" +
                "  const allSpans = document.querySelectorAll('.gyp-pro-table-price span');\n" +
                "  for (const span of allSpans) {\n" +
                "    const text = span.textContent.trim();\n" +
                "    const sm = text.match(/库存\\s*(\\d+)/);\n" +
                "    if (sm) stock = parseInt(sm[1]);\n" +
                "  }\n" +
                "  \n" +
                "  skus.push({ specName: specName, imageUrl: imgUrl, price, stock });\n" +
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
        log.info("规则4解析结果: {} 个SKU", skuList.size());
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
        String safeTitle = sanitizeFileName(title);
        String productDir = linkFile + File.separator + categoryFolder + File.separator + safeTitle;

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

    private List<String> downloadImages(ProductData product, String type, List<String> images, Page page) {
        String dir = product.getProductDir() + File.separator + type;
        int total = images.size();
        AtomicInteger downloaded = new AtomicInteger(0);
        List<String> localPaths = Collections.synchronizedList(new ArrayList<>());

        // 使用线程池并行下载
        ExecutorService executor = Executors.newFixedThreadPool(10);
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
                            synchronized (localPaths) {
                                // Ensure we have enough slots
                                while (localPaths.size() <= idx) localPaths.add(null);
                                localPaths.set(idx, target.toString());
                            }
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
        return localPaths;
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
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

    // ==================== JSON Save ====================

    private void saveProductJson(ProductData product, List<SkuData> skus) {
        try {
            Path jsonPath = Paths.get(product.getProductDir(), "商品数据.json");
            String dirName = Paths.get(product.getProductDir()).getFileName().toString();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", product.getTitle()); // scraped title from 1688
            data.put("dirTitle", dirName); // directory name
            data.put("categoryPath", product.getCategoryPath());
            data.put("url", product.getUrl());
            data.put("layout", product.getLayout());
            data.put("mainImages", product.getMainImages());
            data.put("detailImages", product.getDetailImages());
            data.put("attributes", product.getAttributes());
            data.put("packInfo", product.getPackInfo());
            data.put("videoUrl", product.getVideoUrl());

            // Also save packInfo as separate file
            Path packPath = Paths.get(product.getProductDir(), "包装信息.json");
            String packJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(product.getPackInfo());
            Files.writeString(packPath, packJson, java.nio.charset.StandardCharsets.UTF_8);
            data.put("shippingFee", product.getShippingFee());
            data.put("productDir", product.getProductDir());

            // SKU data with price calculations
            List<Map<String, Object>> skuData = new ArrayList<>();
            for (SkuData sku : skus) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("skuId", sku.getSkuId());
                s.put("specName", sku.getSpecName());
                s.put("detailFields", sku.getDetailFields());
                s.put("originalPrice", sku.getOriginalPrice());
                s.put("shippingFee", sku.getShippingFee());
                s.put("finalPrice", sku.getFinalPrice());
                double discountPrice = sku.getFinalPrice() * 0.8;
                double profit = discountPrice - sku.getOriginalPrice();
                s.put("discountPrice", discountPrice);
                s.put("profit", profit);
                s.put("stock", sku.getStock());
                s.put("imageUrl", sku.getImageUrl());
                skuData.add(s);
            }
            data.put("skus", skuData);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(jsonPath, json, java.nio.charset.StandardCharsets.UTF_8);
            log.info("商品数据JSON已保存: {}", jsonPath);
        } catch (Exception e) {
            log.warn("保存商品数据JSON失败: {}", e.getMessage());
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

    // ==================== Done Tracking ====================

    // 标记文件目录：与链接文件同级的 .done 目录
    private Path getDoneDir() {
        File linkFile = LinkFileReader.findLatestLinkFile().getParentFile();
        return Paths.get(linkFile.getAbsolutePath(), ".done");
    }

    // 加载已完结的 URL 集合
    private Set<String> loadDoneUrls() {
        Set<String> doneUrls = new HashSet<>();
        Path doneDir = getDoneDir();
        if (!Files.exists(doneDir)) return doneUrls;
        try (java.util.stream.Stream<Path> stream = Files.list(doneDir)) {
            stream.forEach(path -> {
                if (path.getFileName().toString().startsWith("done_")) {
                    try {
                        String content = Files.readString(path).trim();
                        if (!content.isEmpty()) doneUrls.add(content);
                    } catch (IOException ignored) {}
                }
            });
        } catch (IOException ignored) {}
        return doneUrls;
    }

    // 标记单个 URL 为已完成
    private void markDone(String url, String title) {
        try {
            Path doneDir = getDoneDir();
            Files.createDirectories(doneDir);
            String hash = Integer.toHexString(url.hashCode());
            Path marker = doneDir.resolve("done_" + hash);
            Files.writeString(marker, url + "\n" + (title != null ? title : ""), StandardCharsets.UTF_8);
            log.info("标记已完结: {}", url);
        } catch (IOException e) {
            log.warn("写入完成标记失败: {} - {}", url, e.getMessage());
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
