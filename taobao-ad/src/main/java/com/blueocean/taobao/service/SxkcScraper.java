package com.blueocean.taobao.service;

import com.blueocean.taobao.enums.TimeRange;
import com.blueocean.taobao.util.EmailUtil;
import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 省心快车页面数据爬虫 - 通过 CDP 连接已运行的 Chrome
 * 保持长连接，避免每次重建 Playwright
 */
@Slf4j
@Component
public class SxkcScraper {

    private static final String CDP_URL = "http://127.0.0.1:9224";
    private static final String TARGET_PAGE = "https://sxkc.wusetech.com/administrator-list";
    private static final String PLAN_MANAGE_PAGE = "https://sxkc.wusetech.com/plan-manage";

    private Playwright playwright;
    private Browser browser;
    private volatile Page cachedPage;

    private final EmailUtil emailUtil;

    public SxkcScraper(EmailUtil emailUtil) {
        this.emailUtil = emailUtil;
    }

    // 每个店铺的独立页面缓存（BrowserContext 保持 token 隔离）
    private final ConcurrentHashMap<String, BrowserContext> shopContexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Page> shopPages = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 启动时不连接，等用户点击启动浏览器后再连
    }

    @PreDestroy
    public void destroy() {
        // 不关闭 Playwright 和 Browser，保持 Chrome 窗口不被关闭
        // 用户手动关闭 Chrome 时才释放资源
    }

    public static class ShopRow {
        public String wangwang;
        public String shopName;
        public String shopVersion;
        public double balance;
        public long impressions;
        public long clicks;
        public double cost;
        public double clickRate;
        public double totalGmv;
        public double roi;
    }

    public static class ScrapeResult {
        public LocalDate startDate;
        public LocalDate endDate;
        public List<ShopRow> shops = new ArrayList<>();
    }

    /**
     * 店铺详情页数据
     */
    public static class ShopDetailResult {
        public String wangwang;
        public String shopName;
        public Map<String, Object> detailData;
        public List<PlanRow> planData = new ArrayList<>();
        public boolean success;
        public String error;
    }

    /**
     * 关键词智能计划表数据
     */
    /** 关键词智能计划表数据 */
    public static class PlanRow {
        public String planId;          // 计划ID
        public String planName;        // 计划名称
        public String planStatus;      // 计划状态（参与推广/暂停推广）
        public double dailyBudget;     // 日预算
        public String timeDiscount;    // 时间折扣
        public int unitCount;          // 单元数量
        public long impressions;       // 展现量
        public long clicks;            // 点击量
        public double cost;            // 花费
        public double clickRate;       // 点击率
        public double avgClickCost;    // 平均点击成本
        public double cpmCost;         // 千次展现成本
        public double potentialConversion; // 潜在转化
        public long favoritedItems;    // 收藏宝贝数
        public long favoritedShops;    // 收藏店铺数
        public long totalFavorites;    // 总收藏数
        public long directCartCount;   // 直接加购数
        public long indirectCartCount; // 间接加购数
        public long totalCartCount;    // 总加购数
        public double cartRate;        // 加购率
        public double itemFavoriteRate; // 收藏率
        public double cartCost;        // 加购成本
        public double conversionRate;  // 转化率
        public double directGmv;       // 直接成交金额
        public long directTransactions; // 直接成交笔数
        public double indirectGmv;     // 间接成交金额
        public long indirectTransactions; // 间接成交笔数
        public double roi;             // 投产比
        public double totalGmv;        // 总成交金额
        public long totalTransactions; // 总成交笔数
        public double ctr;             // 点击率(CTR)
        public String clueInfo;        // 线索信息
        public long wwConsultCount;    // 旺旺咨询量
    }

    /**
     * 获取或建立 CDP 连接
     */
    private Browser getBrowser() {
        if (browser != null) {
            // 复用已有连接
            return browser;
        }
        if (playwright == null) {
            playwright = Playwright.create();
        }
        browser = playwright.chromium().connectOverCDP(CDP_URL);
        log.info("CDP 连接建立成功: {}", CDP_URL);
        return browser;
    }

    /**
     * 获取或创建目标页面
     */
    private Page getPage() {
        Browser br = getBrowser();
        BrowserContext context = br.contexts().isEmpty()
                ? br.newContext()
                : br.contexts().get(0);

        // 检查已有页面是否还有效
        if (cachedPage != null) {
            try {
                String url = cachedPage.url();
                if (url != null && url.contains("sxkc.wusetech.com")) {
                    return cachedPage;
                }
            } catch (Exception e) {
                cachedPage = null; // 页面已失效，清除
            }
        }

        // 找已有 sxkc 页面
        for (Page p : context.pages()) {
            try {
                String url = p.url();
                if (url != null && url.contains("sxkc.wusetech.com")) {
                    cachedPage = p;
                    return p;
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // 创建新页面
        try {
            cachedPage = context.newPage();
            cachedPage.navigate(TARGET_PAGE);
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return cachedPage;
    }

    /**
     * 抓取店铺数据
     */
    public ScrapeResult scrapeShops(String startDate, String endDate) {
        ScrapeResult result = new ScrapeResult();
        try {
            Page page = getPage();

            // 先刷新页面
            page.reload();
            Thread.sleep(1000);
            page.waitForLoadState();
            // 填入日期并触发刷新
            if (startDate != null && !startDate.isEmpty()) {
                setDateOnPage(page, startDate, endDate);
                Thread.sleep(1500);
            }

            page.waitForLoadState();

            // 读取页面日期范围
            @SuppressWarnings("unchecked")
            Map<String, String> dates = (Map<String, String>) page.evaluate(
                    "() => {\n" +
                            "  const startInput = document.querySelector('input[placeholder=\"开始日期\"]');\n" +
                            "  const endInput = document.querySelector('input[placeholder=\"结束日期\"]');\n" +
                            "  return {\n" +
                            "    startDate: startInput ? startInput.value : null,\n" +
                            "    endDate: endInput ? endInput.value : null\n" +
                            "  };\n" +
                            "}");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            if (dates != null && dates.get("startDate") != null) {
                try {
                    result.startDate = LocalDate.parse(dates.get("startDate"), fmt);
                    result.endDate = dates.get("endDate") != null ? LocalDate.parse(dates.get("endDate"), fmt) : result.startDate;
                } catch (Exception e) {
                    LocalDate yesterday = LocalDate.now().minusDays(1);
                    result.startDate = result.endDate = yesterday;
                }
            } else {
                LocalDate yesterday = LocalDate.now().minusDays(1);
                result.startDate = result.endDate = yesterday;
            }
            Thread.sleep(1500);

            // 抓取表格数据（最多重试3次）
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = null;
            for (int retry = 0; retry < 3; retry++) {
                if (retry > 0) {
                    log.warn("表格数据为空，第 {} 次重试...", retry);
                    page.reload();
                    Thread.sleep(2000);
                    page.waitForLoadState();
                    Thread.sleep(1500);
                }

                rows = (List<Map<String, Object>>) page.evaluate(
                        "() => {\n" +
                                "  const trs = document.querySelectorAll('.ant-table-tbody tr');\n" +
                                "  return Array.from(trs).map(tr => {\n" +
                                "    const tds = tr.querySelectorAll('td');\n" +
                                "    if (tds.length < 11) return null;\n" +
                                "    const getText = (sel) => { const el = tr.querySelector(sel); return el ? el.textContent.trim() : ''; };\n" +
                                "    const getNum = (idx) => {\n" +
                                "      const td = tds[idx];\n" +
                                "      if (!td) return 0;\n" +
                                "      const text = td.textContent.trim().replace('%', '');\n" +
                                "      return parseFloat(text) || 0;\n" +
                                "    };\n" +
                                "    return {\n" +
                                "      wangwang: getText('.jump-link'),\n" +
                                "      shopName: getText('td:nth-child(4) > div > div:first-child'),\n" +
                                "      shopVersion: getText('td:nth-child(4) > div > div.fz-12'),\n" +
                                "      balance: getNum(4),\n" +
                                "      impressions: getNum(5),\n" +
                                "      clicks: getNum(6),\n" +
                                "      cost: getNum(7),\n" +
                                "      clickRate: getNum(8),\n" +
                                "      totalGmv: getNum(9),\n" +
                                "      roi: getNum(10)\n" +
                                "    };\n" +
                                "  }).filter(r => r && r.wangwang);\n" +
                                "}");

                if (rows != null && !rows.isEmpty() && rows.size() == 4) {
                    break;
                }
            }

            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    ShopRow shop = new ShopRow();
                    shop.wangwang = (String) row.get("wangwang");
                    shop.shopName = (String) row.get("shopName");
                    shop.shopVersion = (String) row.get("shopVersion");
                    shop.balance = toDouble(row.get("balance"));
                    shop.impressions = toLong(row.get("impressions"));
                    shop.clicks = toLong(row.get("clicks"));
                    shop.cost = toDouble(row.get("cost"));
                    shop.clickRate = toDouble(row.get("clickRate"));
                    shop.totalGmv = toDouble(row.get("totalGmv"));
                    shop.roi = toDouble(row.get("roi"));
                    result.shops.add(shop);
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("connect") || msg.contains("Connection refused"))) {
                throw new RuntimeException("请先启动 Chrome 浏览器（端口 9224）");
            }
            throw new RuntimeException("省心快车数据爬取失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 在 Chrome 页面中设置搜索关键词并触发搜索
     */
    public void setSearchOnPage(String keyword) {
        try {
            Page page = getPage();
            page.fill("input[placeholder*=\"请输入淘宝旺旺、店铺名称或者店铺分组\"]", keyword);
            // 触发 Enter 键触发搜索
            page.keyboard().press("Enter");
            Thread.sleep(1500);
        } catch (Exception e) {
            log.warn("设置搜索关键词失败: {}", e.getMessage());
        }
    }

    /**
     * 抓取店铺详情 - 串行创建窗口，并行采集数据
     */
    public List<ShopDetailResult> scrapeShopDetails(String startDate, String endDate,TimeRange range) {
        // 先用主 Chrome 获取列表页数据
        ScrapeResult listResult = scrapeShops(startDate, endDate);

        List<ShopDetailResult> results = new ArrayList<>();
        if (listResult.shops.isEmpty()) {
            log.warn("列表页没有店铺数据");
            return results;
        }

        // 从主 Chrome 导出登录态到临时文件
        Path storageFile = buildAndSaveStorageState();
        if (storageFile == null) {
            log.warn("未能导出登录态，详情页无法访问");
            return results;
        }

        Browser br = getBrowser();

        // === 第1步：串行创建所有窗口（CDP 并发 newContext 不兼容） ===
        for (ShopRow shop : listResult.shops) {
            String wangwang = shop.wangwang;

            // 检查窗口是否已存在
            if (shopPages.containsKey(wangwang) && shopContexts.containsKey(wangwang)) {
                log.info("[{}] 窗口已存在，跳过创建", wangwang);
                continue;
            }

            try {
                log.info("[{}] 串行创建独立 BrowserContext", wangwang);
                BrowserContext context = br.newContext(new Browser.NewContextOptions()
                        .setStorageStatePath(storageFile));
                shopContexts.put(wangwang, context);

                Page listPage = context.newPage();

                // 导航到列表页，轮询等待表格加载完成
                listPage.navigate(TARGET_PAGE);
                boolean tableLoaded = false;
                for (int i = 0; i < 50; i++) { // 最多等 50*200ms = 10 秒
                    Thread.sleep(200);
                    Boolean hasTable = (Boolean) listPage.evaluate(
                            "() => document.querySelector('.ant-table-tbody') !== null");
                    if (Boolean.TRUE.equals(hasTable)) {
                        tableLoaded = true;
                        break;
                    }
                }
                if (!tableLoaded) {
                    log.warn("[{}] 列表页表格未在 10 秒内加载", wangwang);
                }

                // 设置日期范围
                if (startDate != null && !startDate.isEmpty()) {
                    setDateOnPage(listPage, startDate, endDate);
                    Thread.sleep(2000);
                }

                // 点击店铺超链接（target="_blank" 弹出新标签页）
                log.info("[{}] 点击超链接打开详情页", wangwang);
                // 先用 JS 找到目标元素索引，再用 Playwright 原生点击（能追踪新标签页）
                Integer linkIndex = (Integer) listPage.evaluate(
                        "(name) => {\n" +
                                "  const links = document.querySelectorAll('.jump-link');\n" +
                                "  for (let i = 0; i < links.length; i++) {\n" +
                                "    if (links[i].textContent.trim() === name) return i;\n" +
                                "  }\n" +
                                "  return -1;\n" +
                                "}", wangwang);

                if (linkIndex != null && linkIndex >= 0) {
                    // 用 Playwright 原生 locator + nth().click()，能追踪新标签页
                    listPage.locator(".jump-link").nth(linkIndex).click();
                } else {
                    log.warn("[{}] 找不到超链接元素", wangwang);
                }

                // 轮询等待新标签页弹出
                Page detailPage = null;
                for (int retry = 0; retry < 20; retry++) {
                    Thread.sleep(300);
                    for (Page p : context.pages()) {
                        if (p != listPage) {
                            detailPage = p;
                            break;
                        }
                    }
                    if (detailPage != null) break;
                }

                if (detailPage != null) {
                    log.info("[{}] 详情页已弹出，URL: {}", wangwang, detailPage.url());
                } else {
                    log.warn("[{}] 未捕获到详情页，使用列表页", wangwang);
                    detailPage = listPage;
                }

                // 缓存页面供后续采集使用
                shopPages.put(wangwang, detailPage);

            } catch (Exception e) {
                log.error("[{}] 窗口创建失败: {}", wangwang, e.getMessage());
            }
        }

        // === 第2步：串行采集所有店铺数据（CDP 模式不支持并发） ===
        for (ShopRow shop : listResult.shops) {
            String wangwang = shop.wangwang;
            String shopName = shop.shopName;

            try {
                Page page = shopPages.get(wangwang);
                if (page == null) {
                    log.error("[{}] 页面对象不存在", wangwang);
                    ShopDetailResult errResult = new ShopDetailResult();
                    errResult.wangwang = wangwang;
                    errResult.success = false;
                    errResult.error = "页面对象不存在";
                    results.add(errResult);
                    continue;
                }

                // 导航到计划管理页面并采集
                List<PlanRow> plans = scrapePlanData(page, true, wangwang, range);
                ShopDetailResult result = new ShopDetailResult();
                result.wangwang = wangwang;
                result.shopName = shopName;
                result.planData = plans;
                result.success = true;

                log.info("[{}] 采集完成，共 {} 个计划", wangwang, plans.size());
                results.add(result);

            } catch (Exception e) {
                log.error("[{}] 采集失败: {}", wangwang, e.getMessage());
                ShopDetailResult errResult = new ShopDetailResult();
                errResult.wangwang = wangwang;
                errResult.success = false;
                errResult.error = e.getMessage();
                results.add(errResult);
            }
        }

        log.info("所有店铺详情采集完成，成功 {} 个，失败 {} 个",
                results.stream().filter(r -> r.success).count(),
                results.stream().filter(r -> !r.success).count());

        //只有选择为今天时才发送，筛选"潜力款_"开头的计划，发送邮件
        if(TimeRange.TODAY.equals(range)){
            sendPotentialPlansEmail(results);
        }

        return results;
    }

    /**
     * 筛选"潜力款_"开头的计划，按店铺分组发邮件
     */
    private void sendPotentialPlansEmail(List<ShopDetailResult> results) {
        StringBuilder html = new StringBuilder();
        html.append("<html><head><meta charset='UTF-8'></head><body>");
        html.append("<h2>当日潜力款计划筛选结果</h2>");

        boolean hasData = false;
        for (ShopDetailResult r : results) {
            if (!r.success || r.planData == null || r.planData.isEmpty()) continue;

            List<PlanRow> potentialPlans = r.planData.stream()
                    .filter(p -> p.planName != null && p.planName.startsWith("潜力款_"))
                    .toList();

            if (potentialPlans.isEmpty()) continue;

            hasData = true;
            html.append("<h3>").append(r.shopName).append(" (").append(r.wangwang).append(")</h3>");
            html.append("<table border='1' cellpadding='5' cellspacing='0' style='border-collapse:collapse;font-size:12px;'>");
            html.append("<tr bgcolor='#f2f2f2'>");
            html.append("<th>计划名称</th><th>日限额</th><th>展现</th><th>点击</th><th>花费</th>");
            html.append("<th>点击率</th><th>GMV</th><th>ROI</th><th>加购</th><th>加购率</th><th>成交笔数</th>");
            html.append("</tr>");

            for (PlanRow p : potentialPlans) {
                html.append("<tr>");
                html.append("<td>").append(p.planName).append("</td>");
                html.append("<td>").append(p.dailyBudget).append("</td>");
                html.append("<td>").append(p.impressions).append("</td>");
                html.append("<td>").append(p.clicks).append("</td>");
                html.append("<td>").append(p.cost).append("</td>");
                html.append("<td>").append(p.clickRate).append("%</td>");
                html.append("<td>").append(p.totalGmv).append("</td>");
                html.append("<td>").append(p.roi).append("</td>");
                html.append("<td>").append(p.totalCartCount).append("</td>");
                html.append("<td>").append(p.cartRate).append("%</td>");
                html.append("<td>").append(p.directTransactions).append("</td>");
                html.append("</tr>");
            }
            html.append("</table><br/>");
        }

        html.append("</body></html>");

        if (hasData) {
            emailUtil.sendHtml("当日潜力款计划筛选结果", html.toString());
        } else {
            log.info("没有筛选到'潜力款_'开头的计划");
        }
    }

    /**
     * 从主 Chrome 导出登录态 JSON 文件（手动导出 cookie + localStorage）
     * CDP 模式下 storageState() 不可用，必须手动构建
     */
    private Path buildAndSaveStorageState() {
        try {
            BrowserContext mainContext = getBrowser().contexts().isEmpty()
                    ? getBrowser().newContext()
                    : getBrowser().contexts().get(0);

            log.info("当前 context 有 {} 个页面", mainContext.pages().size());

            // 手动导出 cookie + localStorage
            return buildStorageStateManually(mainContext);
        } catch (Exception e) {
            log.error("导出登录态失败", e);
            return null;
        }
    }

    /**
     * 手动构建 storageState JSON
     */
    private Path buildStorageStateManually(BrowserContext mainContext) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"cookies\":[");

        List<com.microsoft.playwright.options.Cookie> cookies = mainContext.cookies();
        for (int i = 0; i < cookies.size(); i++) {
            com.microsoft.playwright.options.Cookie c = cookies.get(i);
            if (i > 0) sb.append(",");
            sb.append("{");
            sb.append("\"name\":\"").append(escapeJson(c.name)).append("\",");
            sb.append("\"value\":\"").append(escapeJson(c.value)).append("\",");
            sb.append("\"domain\":\"").append(escapeJson(c.domain)).append("\",");
            sb.append("\"path\":\"").append(escapeJson(c.path)).append("\",");
            sb.append("\"httpOnly\":").append(c.httpOnly).append(",");
            sb.append("\"secure\":").append(c.secure).append(",");
            if (c.expires != null) {
                sb.append("\"expires\":").append(c.expires.doubleValue()).append(",");
            }
            if (c.sameSite != null) {
                String ss = c.sameSite.toString();
                // Java 枚举是 STRICT/LAX/NONE，storageState 需要 Strict/Lax/None
                if ("STRICT".equals(ss)) ss = "Strict";
                else if ("LAX".equals(ss)) ss = "Lax";
                else if ("NONE".equals(ss)) ss = "None";
                sb.append("\"sameSite\":\"").append(ss).append("\",");
            }
            // 去掉最后的逗号
            if (sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("}");
        }
        sb.append("],\"origins\":[");

        // 从页面获取 localStorage → Playwright 需要 [{name, value}] 数组格式
        String localStorageArrayJson = "[]";
        if (!mainContext.pages().isEmpty()) {
            try {
                Page page = mainContext.pages().get(0);
                String lsJson = (String) page.evaluate("() => {\n" +
                        "  const arr = [];\n" +
                        "  for (let i = 0; i < localStorage.length; i++) {\n" +
                        "    const key = localStorage.key(i);\n" +
                        "    arr.push({ name: key, value: localStorage.getItem(key) });\n" +
                        "  }\n" +
                        "  return JSON.stringify(arr);\n" +
                        "}");
                if (lsJson != null && !lsJson.isEmpty()) {
                    localStorageArrayJson = lsJson;
                }
            } catch (Exception e) {
                log.warn("获取 localStorage 失败: {}", e.getMessage());
            }
        }
        sb.append("{\"origin\":\"https://sxkc.wusetech.com\",\"localStorage\":").append(localStorageArrayJson).append("}");
        sb.append("]}");

        Path tempFile = Files.createTempFile("playwright-storage-", ".json");
        Files.writeString(tempFile, sb.toString());
        log.info("已手动导出登录态到 {} ({} bytes, {} 个 cookie)", tempFile, Files.size(tempFile), cookies.size());
        return tempFile;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * 在已打开的页面上采集计划管理数据
     * @param page 已打开的页面
     * @param navigateFirst true=首次导航到新页面, false=刷新已有页面
     * @param timeRange 时间范围选项（今天/昨日/过去7天/过去30天/过去90天）
     */
    public List<PlanRow> scrapePlanData(Page page, boolean navigateFirst, String shopName, TimeRange timeRange) {
        List<PlanRow> plans = new ArrayList<>();
        try {
            log.info(">>> [{}] 开始采集计划数据", shopName);

            if (navigateFirst) {
                log.info("[{}] 正在导航到计划管理页...", shopName);
                page.navigate(PLAN_MANAGE_PAGE);
                Thread.sleep(3000);
            } else {
                page.reload();
                Thread.sleep(1000);
            }

            // 检查页面是否有表格
            Boolean hasTable = (Boolean) page.evaluate(
                    "() => document.querySelector('.ant-table-tbody') !== null");
            log.info("[{}] 页面是否有表格: {}", shopName, Boolean.TRUE.equals(hasTable));

            // 点击"关键词智能计划" tab（JS 点击）
            log.info("[{}] 点击关键词智能计划 tab...", shopName);
            page.evaluate("() => {\n" +
                    "  const tabs = document.querySelectorAll('div[role=\"tab\"]');\n" +
                    "  for (const tab of tabs) {\n" +
                    "    if (tab.textContent.trim().includes('关键词智能计划')) {\n" +
                    "      tab.click();\n" +
                    "      return;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}");
            Thread.sleep(1000);

            // 点击时间范围按钮，等待数据加载（JS 点击）
            log.info("[{}] 点击\"{}\"时间按钮...", shopName, timeRange.getButtonText());
            page.evaluate("() => {\n" +
                    "  const btns = document.querySelectorAll('.time-select-button');\n" +
                    "  for (const btn of btns) {\n" +
                    "    if (btn.textContent.trim().includes('" + timeRange.getButtonText() + "')) {\n" +
                    "      btn.click();\n" +
                    "      return;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}");
            Thread.sleep(7000);

            // 4. 抓取表格数据 - 按表头名称匹配提取
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) page.evaluate(
                    "() => {\n" +
                            "  const trs = document.querySelectorAll('.ant-table-tbody tr');\n" +
                            "  const ths = document.querySelectorAll('.ant-table-thead th');\n" +
                            "  const headers = Array.from(ths).map(th => th.textContent.trim());\n" +
                            "  const findIdx = (keywords) => headers.findIndex(h => keywords.some(k => h.includes(k)));\n" +
                            "  const getPlanStatus = (tr) => {\n" +
                            "    const labels = tr.querySelectorAll('.op-label');\n" +
                            "    for (const label of labels) {\n" +
                            "      const text = label.textContent.trim();\n" +
                            "      if ((text === '参与推广' || text === '暂停推广') && label.nextElementSibling && label.nextElementSibling.tagName === 'SPAN') {\n" +
                            "        return text;\n" +
                            "      }\n" +
                            "    }\n" +
                            "    return '';\n" +
                            "  };\n" +
                            "  return Array.from(trs).map(tr => {\n" +
                            "    const tds = tr.querySelectorAll('td');\n" +
                            "    const getText = (i) => i >= 0 && i < tds.length ? tds[i].textContent.trim() : '';\n" +
                            "    const getNum = (i) => { const t = getText(i).replace('%','').replace(',',''); return parseFloat(t) || 0; };\n" +
                            "    const rowKey = tr.getAttribute('data-row-key') || '';\n" +
                            "    const link = tr.querySelector('a[target=\"_blank\"]');\n" +
                            "    let planId = rowKey;\n" +
                            "    if (link && link.href) { const m = link.href.match(/planId=(\\d+)/); if (m) planId = m[1]; }\n" +
                            "    return {\n" +
                            "      planId,\n" +
                            "      planName: getText(findIdx(['推广计划名称','计划名称'])),\n" +
                            "      planStatus: getPlanStatus(tr),\n" +
                            "      dailyBudget: getNum(findIdx(['日限额'])),\n" +
                            "      timeDiscount: getText(findIdx(['分时折扣'])),\n" +
                            "      unitCount: getNum(findIdx(['单元数量'])),\n" +
                            "      impressions: getNum(findIdx(['展现量'])),\n" +
                            "      clicks: getNum(findIdx(['点击量'])),\n" +
                            "      cost: getNum(findIdx(['花费'])),\n" +
                            "      clickRate: getNum(findIdx(['点击率'])),\n" +
                            "      avgClickCost: getNum(findIdx(['平均点击花费'])),\n" +
                            "      cpmCost: getNum(findIdx(['千次展现花费'])),\n" +
                            "      potentialConversion: getNum(findIdx(['潜在转化'])),\n" +
                            "      favoritedItems: getNum(findIdx(['收藏宝贝数'])),\n" +
                            "      favoritedShops: getNum(findIdx(['收藏店铺数'])),\n" +
                            "      totalFavorites: getNum(findIdx(['总收藏数'])),\n" +
                            "      directCartCount: getNum(findIdx(['直接购物车数'])),\n" +
                            "      indirectCartCount: getNum(findIdx(['间接购物车数'])),\n" +
                            "      totalCartCount: getNum(findIdx(['总购物车数'])),\n" +
                            "      cartRate: getNum(findIdx(['加购率'])),\n" +
                            "      itemFavoriteRate: getNum(findIdx(['宝贝收藏率'])),\n" +
                            "      cartCost: getNum(findIdx(['加购成本'])),\n" +
                            "      conversionRate: getNum(findIdx(['成交转化'])),\n" +
                            "      directGmv: getNum(findIdx(['直接成交金额'])),\n" +
                            "      directTransactions: getNum(findIdx(['直接成交笔数'])),\n" +
                            "      indirectGmv: getNum(findIdx(['间接成交金额'])),\n" +
                            "      indirectTransactions: getNum(findIdx(['间接成交笔数'])),\n" +
                            "      roi: getNum(findIdx(['投入产出比'])),\n" +
                            "      totalGmv: getNum(findIdx(['总成交金额'])),\n" +
                            "      totalTransactions: getNum(findIdx(['总成交笔数'])),\n" +
                            "      ctr: getNum(findIdx(['点击转化'])),\n" +
                            "      clueInfo: getText(findIdx(['线索信息'])),\n" +
                            "      wwConsultCount: getNum(findIdx(['旺旺咨询量']))\n" +
                            "    };\n" +
                            "  }).filter(r => r && r.planId);\n" +
                            "}");

            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    PlanRow plan = new PlanRow();
                    plan.planId = (String) row.get("planId");
                    plan.planName = (String) row.get("planName");
                    plan.planStatus = (String) row.get("planStatus");
                    plan.dailyBudget = toDouble(row.get("dailyBudget"));
                    plan.timeDiscount = (String) row.get("timeDiscount");
                    plan.unitCount = toInt(row.get("unitCount"));
                    plan.impressions = toLong(row.get("impressions"));
                    plan.clicks = toLong(row.get("clicks"));
                    plan.cost = toDouble(row.get("cost"));
                    plan.clickRate = toDouble(row.get("clickRate"));
                    plan.avgClickCost = toDouble(row.get("avgClickCost"));
                    plan.cpmCost = toDouble(row.get("cpmCost"));
                    plan.potentialConversion = toDouble(row.get("potentialConversion"));
                    plan.favoritedItems = toLong(row.get("favoritedItems"));
                    plan.favoritedShops = toLong(row.get("favoritedShops"));
                    plan.totalFavorites = toLong(row.get("totalFavorites"));
                    plan.directCartCount = toLong(row.get("directCartCount"));
                    plan.indirectCartCount = toLong(row.get("indirectCartCount"));
                    plan.totalCartCount = toLong(row.get("totalCartCount"));
                    plan.cartRate = toDouble(row.get("cartRate"));
                    plan.itemFavoriteRate = toDouble(row.get("itemFavoriteRate"));
                    plan.cartCost = toDouble(row.get("cartCost"));
                    plan.conversionRate = toDouble(row.get("conversionRate"));
                    plan.directGmv = toDouble(row.get("directGmv"));
                    plan.directTransactions = toLong(row.get("directTransactions"));
                    plan.indirectGmv = toDouble(row.get("indirectGmv"));
                    plan.indirectTransactions = toLong(row.get("indirectTransactions"));
                    plan.roi = toDouble(row.get("roi"));
                    plan.totalGmv = toDouble(row.get("totalGmv"));
                    plan.totalTransactions = toLong(row.get("totalTransactions"));
                    plan.ctr = toDouble(row.get("ctr"));
                    plan.clueInfo = (String) row.get("clueInfo");
                    plan.wwConsultCount = toLong(row.get("wwConsultCount"));
                    plans.add(plan);

                    System.out.println("[计划数据] " + plan.planName +
                            " | 日限额:" + plan.dailyBudget +
                            " | 单元:" + plan.unitCount +
                            " | 展现:" + plan.impressions +
                            " | 点击:" + plan.clicks +
                            " | 花费:" + plan.cost +
                            " | 点击率:" + plan.clickRate + "%" +
                            " | GMV:" + plan.totalGmv +
                            " | ROI:" + plan.roi +
                            " | 加购:" + plan.totalCartCount +
                            " | 加购率:" + plan.cartRate + "%" +
                            " | 成交笔数:" + plan.directTransactions);
                }
            }
            log.info("<<< [{}] 计划数据采集完成，共 {} 条", shopName, plans.size());
        } catch (Exception e) {
            log.error("计划管理页面抓取失败: {}", e.getMessage());
            throw new RuntimeException("计划管理页面抓取失败: " + e.getMessage(), e);
        }
        return plans;
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private void setDateOnPage(Page page, String startDate, String endDate) {
        try {
            java.time.LocalDate yesterday = java.time.LocalDate.now().minusDays(1);
            java.time.LocalDate today = java.time.LocalDate.now();

            if (startDate != null && endDate != null && !startDate.equals(endDate)) {
                setRawDate(page, startDate, endDate);
                return;
            }

            String btnText = null;
            if (startDate != null) {
                java.time.LocalDate s = java.time.LocalDate.parse(startDate);
                java.time.LocalDate e = (endDate != null) ? java.time.LocalDate.parse(endDate) : s;
                if (s.equals(today) && e.equals(today)) btnText = "今日";
                else if (s.equals(yesterday) && e.equals(yesterday)) btnText = "昨日";
                else if (s.equals(yesterday.minusDays(6)) && e.equals(yesterday)) btnText = "近7天";
                else if (s.equals(yesterday.minusDays(29)) && e.equals(yesterday)) btnText = "近30天";
                else if (s.equals(yesterday.minusDays(89)) && e.equals(yesterday)) btnText = "近90天";
            }

            if (btnText != null) {
                page.evaluate("() => {\n" +
                        "  const btns = document.querySelectorAll('.time-select-button');\n" +
                        "  for (const btn of btns) {\n" +
                        "    if (btn.textContent.trim() === '" + btnText + "') {\n" +
                        "      btn.click();\n" +
                        "      return true;\n" +
                        "    }\n" +
                        "  }\n" +
                        "  return false;\n" +
                        "}");
            } else {
                setRawDate(page, startDate, endDate);
            }
        } catch (Exception e) {
            log.warn("设置日期失败: {}", e.getMessage());
        }
    }

    private void setRawDate(Page page, String startDate, String endDate) {
        try {
            // 点击开始日期输入框打开日历，然后直接填值
            page.click("input[placeholder=\"开始日期\"]");
            Thread.sleep(300);
            // 用 keyboard.type 模拟键盘输入，React 能正确识别
            page.keyboard().press("Control+A");
            page.keyboard().type(startDate);
            page.keyboard().press("Enter");

            if (endDate != null && !endDate.isEmpty()) {
                Thread.sleep(300);
                page.click("input[placeholder=\"结束日期\"]");
                Thread.sleep(300);
                page.keyboard().press("Control+A");
                page.keyboard().type(endDate);
                page.keyboard().press("Enter");
            }
        } catch (Exception e) {
            log.warn("设置日期失败: {}", e.getMessage());
        }
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
