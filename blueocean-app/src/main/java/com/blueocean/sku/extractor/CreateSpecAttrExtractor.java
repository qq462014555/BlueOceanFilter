package com.blueocean.sku.extractor;

import com.microsoft.playwright.Page;

import java.util.List;

/**
 * 创建规格模式 SKU 属性提取器
 * 页面结构：销售规格标签下，有"+ 创建规格"按钮，点击弹出规格设置面板
 * 需要点击按钮后从弹出框中提取可选的 SKU 属性名称
 */
public class CreateSpecAttrExtractor implements SkuAttrExtractor {

    @Override
    public boolean matches(Page page) {
        try {
            Object result = page.evaluate("""
                    () => {
                      const xpath = "//*[text()='销售规格'] | //*[contains(text(),'销售规格')]";
                      const label = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                      if (!label) return false;
                      // 向上找容器，检查是否有"+ 创建规格"按钮
                      let container = label;
                      for (let i = 0; i < 8 && container.parentElement; i++) {
                        container = container.parentElement;
                        const buttons = container.querySelectorAll('button');
                        for (const btn of buttons) {
                          if (btn.textContent.trim().includes('创建规格')) return true;
                        }
                      }
                      return false;
                    }
                    """);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> extract(Page page) {
        // 1. 直接定位"+ 创建规格"按钮并滚动到可视区域
        Object btnCoords = page.evaluate("""
                () => {
                  const xpath = "//*[contains(text(),'创建规格')]";
                  const btn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                  if (!btn) return null;
                  btn.scrollIntoView({ block: 'center' });
                  // 滚动后再取坐标
                  const rect = btn.getBoundingClientRect();
                  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                }
                """);

        if (btnCoords == null) {
            return List.of();
        }

        // 2. 模拟人工鼠标滑动到按钮并点击
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> coords = (java.util.Map<String, Object>) btnCoords;
            double x = ((Number) coords.get("x")).doubleValue();
            double y = ((Number) coords.get("y")).doubleValue();

            page.waitForTimeout(500);  // 等待滚动完成

            // 鼠标滑动到按钮位置（模拟人类滑动轨迹）
            page.mouse().move(x, y);
            page.waitForTimeout(200);  // 短暂停留
            page.mouse().click(x, y);  // 点击

            // 等待弹出框出现
            page.waitForTimeout(1500);

            // 鼠标滑动到弹出框区域，确保可见
            Object dialogCoords = page.evaluate("() => {\n" +
                    "  const dialog = document.querySelector('.next-dialog, [role=\"dialog\"], .sku-dialog, .sku-spec-popup') || document.body;\n" +
                    "  const rect = dialog.getBoundingClientRect();\n" +
                    "  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };\n" +
                    "}");
            if (dialogCoords != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> dc = (java.util.Map<String, Object>) dialogCoords;
                double dx = ((Number) dc.get("x")).doubleValue();
                double dy = ((Number) dc.get("y")).doubleValue();
                page.mouse().move(dx, dy);
                page.waitForTimeout(300);
            }

            // 点击"分层展示·选择标准属性构建规格"选项
            Object layerOptionCoords = page.evaluate("() => {\n" +
                    "  const radios = document.querySelectorAll('.next-radio-wrapper');\n" +
                    "  for (const radio of radios) {\n" +
                    "    const label = radio.querySelector('.next-radio-label');\n" +
                    "    if (label && label.textContent.trim().includes('分层展示')) {\n" +
                    "      const rect = radio.getBoundingClientRect();\n" +
                    "      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return null;\n" +
                    "}");

            if (layerOptionCoords != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> lc = (java.util.Map<String, Object>) layerOptionCoords;
                double lx = ((Number) lc.get("x")).doubleValue();
                double ly = ((Number) lc.get("y")).doubleValue();

                // 滚动到选项可见
                page.evaluate("""
                        () => {
                          const radios = document.querySelectorAll('.next-radio-wrapper');
                          for (const radio of radios) {
                            const label = radio.querySelector('.next-radio-label');
                            if (label && label.textContent.trim().includes('分层展示')) {
                              radio.scrollIntoView({ block: 'center' });
                              break;
                            }
                          }
                        }
                        """);
                page.waitForTimeout(600);

                // 鼠标滑动过去点击
                page.mouse().move(lx, ly);
                page.waitForTimeout(200);
                page.mouse().click(lx, ly);
                page.waitForTimeout(1000);  // 等待选项切换
            }
        } catch (Exception e) {
            return List.of();
        }

        // 3. 从刷新后的 .multi-sale-props 区域提取已选的 SKU 属性
        @SuppressWarnings("unchecked")
        List<String> props = (List<String>) page.evaluate("""
                () => {
                  // 等待内容刷新
                  const items = document.querySelectorAll('.multi-sale-props .prop-item.selected span');
                  const props = [];
                  for (const item of items) {
                    const text = item.textContent.trim();
                    if (text && text.length > 0 && text.length < 20) {
                      props.push(text);
                    }
                  }
                  return props;
                }
                """);

        return props;
    }
}
