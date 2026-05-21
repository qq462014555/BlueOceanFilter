package com.blueocean.sku.extractor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 千牛 SKU 属性提取策略接口
 */
public interface SkuAttrExtractor {

    /**
     * 从千牛页面提取 SKU 属性
     * @param page 已连接到千牛商品发布页面的 Page 对象
     * @return SKU 属性名称列表
     */
    List<String> extract(Page page);

    /**
     * 判断当前页面是否匹配此策略
     */
    boolean matches(Page page);

    /**
     * 通用方法：填写 SKU 表格的价格和库存
     * 数据源：从 single 数组按名称匹配 price/stock 字段
     * 策略：JS 端 async 滚动+填写，避免跨语言延迟
     * @param page 页面
     * @param aiResultJsonPath sku-ai-result.json 文件路径
     */
    default void fillPriceAndStock(Page page, String aiResultJsonPath) {
        try {
            String json = Files.readString(Path.of(aiResultJsonPath), StandardCharsets.UTF_8);
            JSONObject aiResult = JSON.parseObject(json);

            com.alibaba.fastjson2.JSONArray singles = aiResult.getJSONArray("single");
            if (singles == null || singles.isEmpty()) {
                System.out.println("single 数组为空，跳过价格和库存填写");
                return;
            }

            // 滚到顶部
            page.locator(".ver-scroll-wrap").first().evaluate("el => el.scrollTo(0, 0)");
            page.waitForTimeout(800);

            int filledCount = 0;
            java.util.Set<String> filledNames = new java.util.HashSet<>();

            for (int i = 0; i < singles.size(); i++) {
                JSONObject item = singles.getJSONObject(i);
                String name = item.getString("name");
                String price = item.getString("price");
                if (price == null || price.isEmpty()) price = "0";
                String stock = item.getString("stock");
                if (stock == null || stock.isEmpty()) stock = "0";

                if (name == null || name.isEmpty()) continue;
                if (filledNames.contains(name)) continue;

                // 第7个之后，先滚动再查找
                if (filledCount >= 7) {
                    page.locator(".ver-scroll-wrap").first().evaluate("el => el.scrollBy(0, 40)");
                    page.waitForTimeout(800);
                }

                // 用 Playwright getByText 按 SKU 名称定位 span
                com.microsoft.playwright.Locator nameSpan = page.getByText(name).first();

                // 找不到就滚动重试
                boolean found = false;
                for (int attempt = 0; attempt < 15; attempt++) {
                    if (nameSpan.isVisible()) {
                        // 用 XPath ancestor 向上找最近的 .sku-table-row，不依赖 DOM 层级
                    com.microsoft.playwright.Locator row = nameSpan.locator("xpath=ancestor::tr[contains(@class, 'sku-table-row')]").first();

                        // 先找到 price 对应的 td（id 包含 -skuPrice），再在里面找 input
                        com.microsoft.playwright.Locator priceCell = row.locator("[id$='-skuPrice']").first();
                        if (priceCell.count() > 0) {
                            com.microsoft.playwright.Locator priceInput = priceCell.locator("input").first();
                            if (priceInput.count() > 0) {
                                priceInput.click();
                                page.waitForTimeout(100);
                                priceInput.fill(price);
                            }
                        }

                        // 找到 stock 对应的 td（id 包含 -skuStock），再在里面找 input
                        com.microsoft.playwright.Locator stockCell = row.locator("[id$='-skuStock']").first();
                        if (stockCell.count() > 0) {
                            com.microsoft.playwright.Locator stockInput = stockCell.locator("input").first();
                            if (stockInput.count() > 0) {
                                stockInput.click();
                                page.waitForTimeout(100);
                                stockInput.fill(stock);
                            }
                        }

                        filledNames.add(name);
                        filledCount++;
                        found = true;
                        break;
                    }

                    // 没找到，继续滚动
                    page.locator(".ver-scroll-wrap").first().evaluate("el => el.scrollBy(0, 100)");
                    page.waitForTimeout(300);
                }

                if (!found) {
                    System.out.println("未找到: " + name);
                } else {
                    // 每填写一行，等待 1 秒
                    page.waitForTimeout(1000);
                }
            }

            System.out.println("JS 执行结果: filledCount=" + filledCount + ", rowCount=" + singles.size());

        } catch (IOException e) {
            System.out.println("读取 AI 结果失败，无法填写价格和库存: " + e.getMessage());
        }
    }

    /**
     * 通用方法：上传 SKU 图片
     * 数据源：从 single 数组按名称匹配 image 字段（图片文件路径）
     * 策略：用 page.waitForFileChooser() 拦截文件选择器，自动设置文件
     * @param page 页面
     * @param aiResultJsonPath sku-ai-result.json 文件路径
     */
    default void fillSkuImage(Page page, String aiResultJsonPath,String productDir) {
        try {
            String json = Files.readString(Path.of(aiResultJsonPath), StandardCharsets.UTF_8);
            JSONObject aiResult = JSON.parseObject(json);

            com.alibaba.fastjson2.JSONArray singles = aiResult.getJSONArray("single");
           if (singles == null || singles.isEmpty()) {
                System.out.println("single 数组为空，跳过 SKU 图片上传");
                return;
            }

            Path productDirPath = Path.of(productDir);
            if (!Files.isDirectory(productDirPath)) {
                System.out.println("商品目录不存在: " + productDir);
                return;
            }

            // 扫描 SKU图 子目录下所有图片文件
            Path skuImageDir = productDirPath.resolve("SKU图");
            if (!Files.isDirectory(skuImageDir)) {
                System.out.println("SKU图目录不存在: " + skuImageDir);
                return;
            }

            java.util.List<Path> skuImages;
            try (java.util.stream.Stream<Path> files = Files.list(skuImageDir)) {
                skuImages = files
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
            }
            if (skuImages.isEmpty()) {
                System.out.println("SKU图目录下未找到图片: " + skuImageDir);
                return;
            }

            System.out.println("找到 SKU 图图片数量: " + skuImages.size());

            // 滚到顶部
            page.locator(".ver-scroll-wrap").first().evaluate("el => el.scrollTo(0, 0)");
            page.waitForTimeout(800);

            // ===== 阶段一：上传所有图片（只做一次） =====
            System.out.println("=== 阶段一：上传所有 SKU 图片 ===");

            // 声明弹窗引用，阶段二复用
            final com.microsoft.playwright.FrameLocator[] uploadFrameHolder = new com.microsoft.playwright.FrameLocator[1];

            // 找到第一行的图片上传 cell
            com.microsoft.playwright.Locator firstImageCell = null;
            for (int i = 0; i < singles.size(); i++) {
                String n = singles.getJSONObject(i).getString("name");
                if (n == null || n.isEmpty()) continue;
                com.microsoft.playwright.Locator nameSpan = page.getByText(n).first();
                for (int attempt = 0; attempt < 15; attempt++) {
                    if (nameSpan.isVisible()) {
                        com.microsoft.playwright.Locator row = nameSpan.locator("xpath=ancestor::tr[contains(@class, 'sku-table-row')]").first();
                        firstImageCell = row.locator("[id$='-skuPicture']").first();
                        break;
                    }
                    page.locator(".ver-scroll-wrap").first().evaluate("el => el.scrollBy(0, 100)");
                    page.waitForTimeout(300);
                }
                break;
            }
            if (firstImageCell == null || firstImageCell.count() == 0) {
                System.out.println("未找到第一行图片上传区域");
                return;
            }

            // 点击打开上传弹窗
            System.out.println("1.[上传] 点击第一行上传区域");
            firstImageCell.click(new com.microsoft.playwright.Locator.ClickOptions().setPosition(30, 30));

            // 等待弹窗出现，找到 iframe
            boolean btnFound = false;
            for (int waitAttempt = 0; waitAttempt < 20; waitAttempt++) {
                page.waitForTimeout(500);
                try {
                    java.util.List<com.microsoft.playwright.Locator> allIframes = page.locator("iframe[src^='https://market.m.taobao.com/']").all();
                    for (com.microsoft.playwright.Locator iframe : allIframes) {
                        if (iframe.count() == 0) continue;
                        try {
                            com.microsoft.playwright.FrameLocator frame = iframe.contentFrame();
                            com.microsoft.playwright.Locator uploadBtn = frame.getByText("本地上传").first();
                            if (uploadBtn.count() > 0 && uploadBtn.isVisible() && uploadBtn.isEnabled()) {
                                uploadFrameHolder[0] = frame;
                                btnFound = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (btnFound) break;
                } catch (Exception ignored) {}
            }
            if (!btnFound) {
                System.out.println("[上传] 未找到本地上传按钮");
                return;
            }
            System.out.println("2.[上传] 找到本地上传按钮");

            // 一次性上传所有图片
            com.microsoft.playwright.FileChooser fileChooser = page.waitForFileChooser(() ->
                    uploadFrameHolder[0].getByText("本地上传").first().click());
            fileChooser.setFiles(skuImages.toArray(new Path[0]));
            System.out.println("3.[上传] 所有图片已选择，等待上传完成 (" + skuImages.size() + " 张)");

            // 循环等待上传完成：监听 "上传成功" 提示
            boolean uploadSuccess = false;
            for (int waitAttempt = 0; waitAttempt < 120; waitAttempt++) {
                page.waitForTimeout(1000);
                try {
                    com.microsoft.playwright.Locator successText = uploadFrameHolder[0].locator("text=上传成功").first();
                    if (successText.count() > 0) {
                        System.out.println("3.1[上传] 上传成功，耗时约 " + (waitAttempt + 1) + " 秒");
                        uploadSuccess = true;
                        break;
                    }
                    com.microsoft.playwright.Locator uploadingText = uploadFrameHolder[0].locator("text=上传中").first();
                    if (uploadingText.count() > 0) {
                        System.out.println("3.2[上传] 文件上传中... 等待 " + (waitAttempt + 1) + " 秒");
                    }
                } catch (Exception e) {}
            }
            if (!uploadSuccess) {
                System.out.println("[上传] 上传超时，继续尝试");
            }

            // 点击完成
            page.waitForTimeout(1000);
            com.microsoft.playwright.Locator doneBtn = uploadFrameHolder[0].locator("button.next-btn-primary:has(span.next-btn-helper:has-text('完成'))").first();
            if (doneBtn.count() > 0 && doneBtn.isVisible()) {
                doneBtn.click();
                System.out.println("4.[上传] 已点击完成");
                page.waitForTimeout(1000);
            }

            System.out.println("=== 阶段二：按文件名顺序循环选择图片 ===");

            // 找到上传弹窗iframe
            com.microsoft.playwright.FrameLocator selectFrame = null;
            for (int waitAttempt = 0; waitAttempt < 20; waitAttempt++) {
                page.waitForTimeout(500);
                try {
                    java.util.List<com.microsoft.playwright.Locator> allIframes = page.locator("iframe[src^='https://market.m.taobao.com/']").all();
                    for (com.microsoft.playwright.Locator iframe : allIframes) {
                        if (iframe.count() == 0) continue;
                        try {
                            com.microsoft.playwright.FrameLocator frame = iframe.contentFrame();
                            if (frame.locator("#sucai_tu_selector_scrollMain").first().count() > 0) {
                                selectFrame = frame;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (selectFrame != null) break;
                } catch (Exception ignored) {}
            }
            if (selectFrame == null) {
                System.out.println("[赋值] 未找到上传弹窗iframe");
                return;
            }

            // 弹窗已经打开了，直接在里面循环选择图片
            for (int imgIdx = 0; imgIdx < skuImages.size(); imgIdx++) {
                String targetFileName = skuImages.get(imgIdx).getFileName().toString();
                System.out.println("5.[赋值] 第 " + (imgIdx + 1) + "/" + skuImages.size() + " 轮，选择 " + targetFileName);

                // 按千牛文件名定位图片
                com.microsoft.playwright.Locator targetImg = selectFrame.locator(".PicList_tip_title__aQfti span:text(\"" + targetFileName + "\")").first();
                if (targetImg.count() > 0) {
                    // 找到外层可点击的图片容器
                    com.microsoft.playwright.Locator clickTarget = targetImg.locator("xpath=ancestor::div[contains(@class, 'PicList_PicturesShow_main-show')]").first();
                    // 循环滚动直到找到目标图片（一直慢慢滚）
                    boolean imgClicked = false;
                    for (int scrollAttempt = 0; scrollAttempt < 100; scrollAttempt++) {
                        if (targetImg.isVisible()) {
                            clickTarget.click();
                            System.out.println("6.[赋值] 已点击图片: " + targetFileName);
                            imgClicked = true;
                            break;
                        }
                        // 一直慢慢往下滚
                        selectFrame.locator("#sucai_tu_selector_scrollWrap").first().evaluate("el => el.scrollBy(0, 50)");
                        page.waitForTimeout(200);
                    }
                    if (!imgClicked) {
                        System.out.println("[赋值] 未找到可见的图片: " + targetFileName);
                    }
                } else {
                    System.out.println("[赋值] 未找到图片: " + targetFileName);
                }

                page.waitForTimeout(1400);
            }

            // 所有图片选完后，最后点一次确定
            page.waitForTimeout(500);
            com.microsoft.playwright.Locator selectConfirmBtn = page.locator("button:has-text('确定')").first();
            if (selectConfirmBtn.count() > 0) {
                selectConfirmBtn.waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(3000));
                selectConfirmBtn.click();
                System.out.println("7.[赋值] 已点击确定");
            }

            System.out.println("SKU 图片处理完成: " + skuImages.size() + " 张");

        } catch (IOException e) {
            System.out.println("读取 AI 结果失败，无法上传 SKU 图片: " + e.getMessage());
        }
    }

    /**
     * 定位指定行指定 cell 的 input 并填写值
     */
    private void fillCellInput(Page page, int rowIndex, String cellSuffix, String value) {
        page.evaluate("""
                (data) => {
                  const rows = document.querySelectorAll('.sku-table-row');
                  if (rows.length <= data.rowIndex) return;
                  const cell = rows[data.rowIndex].querySelector('[id$="-' + data.suffix + '"]');
                  if (!cell) return;
                  const input = cell.querySelector('input');
                  if (!input) return;
                  const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                  nativeSetter.call(input, data.value);
                  input.dispatchEvent(new Event('input', { bubbles: true }));
                  input.dispatchEvent(new Event('change', { bubbles: true }));
                }
                """, Map.of("rowIndex", rowIndex, "suffix", cellSuffix, "value", value));
    }

    /**
     * 内部类：价格和库存数据
     */
    class PriceStock {
        final String price;
        final String stock;
        PriceStock(String price, String stock) {
            this.price = price;
            this.stock = stock;
        }
    }
}
