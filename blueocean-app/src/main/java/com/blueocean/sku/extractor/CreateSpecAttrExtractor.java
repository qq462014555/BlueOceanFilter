package com.blueocean.sku.extractor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 创建规格模式 SKU 属性提取器
 * 页面结构：销售规格标签下，有"+ 创建规格"按钮，点击弹出规格设置面板
 * 需要点击按钮后从弹出框中提取可选的 SKU 属性名称
 */
public class CreateSpecAttrExtractor implements SkuAttrExtractor {

    @Override
    public boolean matches(Page page) {
        try {
            // 查找包含"销售规格"文本的元素，向上找是否有"+ 创建规格"按钮
            com.microsoft.playwright.Locator saleSpecLabel = page.getByText("销售规格").first();
            if (saleSpecLabel.count() == 0) return false;

            // 用 Playwright 查找页面上是否有"创建规格"按钮
            com.microsoft.playwright.Locator createBtn = page.getByText("创建规格").first();
            return createBtn.count() > 0 && createBtn.isVisible();
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
                    "    if (label && label.textContent.trim().includes('选择标准属性构建规格')) {\n" +
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

    /**
     * 填充 SKU 数据到创建规格页面
     * @param page 页面
     * @param aiResultJsonPath sku-ai-result.json 文件路径
     */
    public void fillSku(Page page, String aiResultJsonPath) {
        try {
            // 1. 读取 AI 结果
            String json = Files.readString(Path.of(aiResultJsonPath), StandardCharsets.UTF_8);
            JSONObject aiResult = JSON.parseObject(json);
            JSONObject multi = aiResult.getJSONObject("multi");
            if (multi == null) return;
            JSONArray levels = multi.getJSONArray("levels");
            // 提取需要填写的属性名和选项
            java.util.List<String> targetAttrs = new java.util.ArrayList<>();
            java.util.Map<String, java.util.List<String>> attrOptions = new java.util.LinkedHashMap<>();

            if (levels == null || levels.isEmpty()) {
                // levels 为空说明页面只有一个默认属性，复用 extract 方法获取
                List<String> extracted = extract(page);
                if (extracted.isEmpty()) return;
                // 从 single 数组提取 name 作为选项值
                JSONArray singles = aiResult.getJSONArray("single");
                java.util.List<String> opts = new java.util.ArrayList<>();
                if (singles != null) {
                    for (int i = 0; i < singles.size(); i++) {
                        JSONObject single = singles.getJSONObject(i);
                        String name = single.getString("name");
                        if (name != null && !name.trim().isEmpty()) opts.add(name);
                    }
                }
                if (opts.isEmpty()) opts.add("");
                for (String attr : extracted) {
                    targetAttrs.add(attr);
                    attrOptions.put(attr, opts);
                }
            } else {
                for (int i = 0; i < levels.size(); i++) {
                    JSONObject level = levels.getJSONObject(i);
                    String name = level.getString("name");
                    JSONArray options = level.getJSONArray("options");
                    if (name != null && options != null) {
                        targetAttrs.add(name);
                        java.util.List<String> opts = new java.util.ArrayList<>();
                        for (int j = 0; j < options.size(); j++) {
                            String o = options.getString(j);
                            if (o != null && !o.trim().isEmpty()) opts.add(o);
                        }
                        attrOptions.put(name, opts);
                    }
                }
            }
            if (targetAttrs.isEmpty()) return;

            // 2. 已经进入 没必要（点击"+ 创建规格"按钮（复用已有逻辑）)

            //属性超过2个才会进入
            if(levels!=null && levels.size()>1) {

                // 3. 选择"选择标准属性构建规格"
                selectStandardSpecRadio(page);

                // 4. 依次勾选 AI 结果中存在的属性
                selectTargetAttrs(page, targetAttrs);
            }

            // 6. 页面动态换位后，重新获取属性布局
            page.waitForTimeout(1500);
            java.util.List<String> currentAttrs = getCurrentAttrLayout(page);

            // 7. 填写所有 SKU 值
            fillAllRows(page, currentAttrs, attrOptions);

            // 8. 点击确认创建按钮
            clickConfirmCreateBtn(page);

        } catch (IOException e) {
            throw new RuntimeException("读取 AI 结果文件失败: " + aiResultJsonPath, e);
        }
    }

    /**
     * 点击"+ 创建规格"按钮
     */
    private void clickCreateSpecBtn(Page page) {
        Object btnCoords = page.evaluate("""
                () => {
                  const xpath = "//*[contains(text(),'创建规格')]";
                  const btn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                  if (!btn) return null;
                  btn.scrollIntoView({ block: 'center' });
                  const rect = btn.getBoundingClientRect();
                  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                }
                """);
        if (btnCoords == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> coords = (Map<String, Object>) btnCoords;
        double x = ((Number) coords.get("x")).doubleValue();
        double y = ((Number) coords.get("y")).doubleValue();
        page.waitForTimeout(500);
        page.mouse().move(x, y);
        page.waitForTimeout(200);
        page.mouse().click(x, y);
        page.waitForTimeout(1500);
    }

    /**
     * 选择"选择标准属性构建规格"单选
     */
    private void selectStandardSpecRadio(Page page) {
        Object radioCoords = page.evaluate("""
                () => {
                  const radios = document.querySelectorAll('.next-radio-wrapper');
                  for (const radio of radios) {
                    const label = radio.querySelector('.next-radio-label');
                    if (label && label.textContent.trim().includes('选择标准属性构建规格')) {
                      const rect = radio.getBoundingClientRect();
                      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                    }
                  }
                  return null;
                }
                """);
        if (radioCoords == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> coords = (Map<String, Object>) radioCoords;
        double x = ((Number) coords.get("x")).doubleValue();
        double y = ((Number) coords.get("y")).doubleValue();
        page.waitForTimeout(300);
        page.mouse().move(x, y);
        page.waitForTimeout(200);
        page.mouse().click(x, y);
        page.waitForTimeout(1000);
    }

    /**
     * 勾选目标属性
     */
    private void selectTargetAttrs(Page page, List<String> targetAttrs) {
        for (String attr : targetAttrs) {
            page.evaluate("""
                    (attrName) => {
                      const propItems = document.querySelectorAll('.multi-sale-props .prop-item');
                      for (const item of propItems) {
                        const span = item.querySelector('span');
                        if (span && span.textContent.trim() === attrName) {
                          // 如果未选中则点击
                          const parentRow = item.closest('tr, .prop-row, .prop-item');
                          const isSelected = parentRow ? parentRow.classList.contains('selected') : false;
                          if (!isSelected) {
                            const cb = item.querySelector('.next-checkbox input, input[type="checkbox"], .prop-item-check');
                            if (cb && !cb.checked) { cb.click(); }
                            else { item.click(); }
                          }
                          return true;
                        }
                      }
                      return false;
                    }
                    """, attr);
            page.waitForTimeout(500);
        }
    }

    /**
     * 点击弹窗确定按钮
     */
    private void clickDialogConfirm(Page page) {
        page.evaluate("""
                () => {
                  const dialog = document.querySelector('.next-dialog, [role="dialog"], .sku-dialog, .sku-spec-popup');
                  if (!dialog) return false;
                  const btns = dialog.querySelectorAll('.next-btn, .dialog-footer button, button');
                  for (const btn of btns) {
                    const text = btn.textContent.trim();
                    if (text.includes('确定') || text.includes('确认') || text.includes('完成')) {
                      const rect = btn.getBoundingClientRect();
                      btn.scrollIntoView({ block: 'center' });
                      btn.click();
                      return true;
                    }
                  }
                  return false;
                }
                """);
        page.waitForTimeout(1500);
    }

    /**
     * 获取当前页面属性布局（点击确定后）
     */
    private List<String> getCurrentAttrLayout(Page page) {
        @SuppressWarnings("unchecked")
        List<String> attrs = (List<String>) page.evaluate("""
                () => {
                  const saleProps = document.querySelector('.sell-component-sale-props');
                  if (!saleProps) return [];
                  const labels = saleProps.querySelectorAll('.props-label');
                  const result = [];
                  for (const label of labels) {
                    const text = label.textContent.trim().replace(/\\(\\d+\\)/g, '').trim();
                    if (text && text.length > 0 && text.length < 20) {
                      result.push(text);
                    }
                  }
                  return result;
                }
                """);
        return attrs;
    }

    /**
     * 填写所有 SKU 值：循环每个属性，对每个属性循环其值，按 index 定位输入框
     */
    private void fillAllRows(Page page, List<String> currentAttrs, Map<String, java.util.List<String>> attrOptions) {
        for (String attrName : currentAttrs) {
            java.util.List<String> options = attrOptions.get(attrName);
            if (options == null || options.isEmpty()) continue;

            // 第一个值填入 index=0 的输入框
            fillInputByIndex(page, attrName, options.get(0), 0);
            System.out.println("[" + attrName + "] 填写第 1 行: " + options.get(0));
            page.waitForTimeout(300);

            // 后续值依次点+后再填 index=1, 2, ...
            for (int i = 1; i < options.size(); i++) {
                // 填完后光标在输入框最后，然后失焦，+按钮才会出现
                page.evaluate("() => { const inp = document.querySelector('.color-sub-items input:focus'); if (inp) inp.blur(); }");
                page.waitForTimeout(500);

                // 点击+前记录当前input数量
                int beforeCount = getInputCount(page, attrName);

                // 点击+，带重试（最多3次）
                boolean added = false;
                for (int retry = 0; retry < 3; retry++) {
                    if (retry > 0) {
                        // 重试前先失焦
                        page.evaluate("() => { const inp = document.querySelector('.color-sub-items input:focus'); if (inp) inp.blur(); }");
                        page.waitForTimeout(500);
                    }
                    clickAddBtnForAttr(page, attrName);
                    page.waitForTimeout(1200);

                    // 校验input数量是否增加
                    int afterCount = getInputCount(page, attrName);
                    if (afterCount > beforeCount) {
                        added = true;
                        break;
                    }
                    System.out.println("["+attrName+"] 第"+(retry+1)+"次点击+后input数量未变，重试");
                }

                if (!added) {
                    System.out.println("["+attrName+"] 重试3次仍无法添加新行，跳过");
                    continue;
                }

                fillInputByIndex(page, attrName, options.get(i), i);
                System.out.println("[" + attrName + "] 填写第 " + (i + 1) + " 行: " + options.get(i));
                page.waitForTimeout(300);
            }
        }
        // 全部填完后点击页面空白失焦
        page.evaluate("() => { document.body.click(); }");
        page.waitForTimeout(500);
    }

    /**
     * 按 index 定位 .color-sub-items 下的 input 并填写（绕过 React setter），填完后光标在最后然后失焦
     */
    private void fillInputByIndex(Page page, String attrName, String value, int index) {
        page.evaluate("""
                (data) => {
                  const { attrName, value, index } = data;
                  const saleProps = document.querySelector('.sell-component-sale-props');
                  if (!saleProps) return false;
                  const wraps = saleProps.querySelectorAll('.common-wrap');
                  for (const wrap of wraps) {
                    const label = wrap.querySelector('.props-label');
                    if (label && label.textContent.trim().includes(attrName)) {
                      const inputs = wrap.querySelectorAll('.color-sub-items input');
                      if (inputs.length > index) {
                        const input = inputs[index];
                        input.scrollIntoView({ block: 'center' });
                        const nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                        nativeSetter.call(input, value);
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        // 光标定位到最后
                        input.focus();
                        input.setSelectionRange(input.value.length, input.value.length);
                        return true;
                      }
                    }
                  }
                  return false;
                }
                """, Map.of("attrName", attrName, "value", value, "index", index));
    }

    /**
     * 点击指定属性区域的"+"按钮（先滚动到可视区域）
     */
    private void clickAddBtnForAttr(Page page, String attrName) {
        Object btnCoords = page.evaluate("""
                (attrName) => {
                  const saleProps = document.querySelector('.sell-component-sale-props');
                  if (!saleProps) return null;
                  const wraps = saleProps.querySelectorAll('.common-wrap');
                  for (const wrap of wraps) {
                    const label = wrap.querySelector('.props-label');
                    if (label && label.textContent.trim().includes(attrName)) {
                      const addBtn = wrap.querySelector('button.add');
                      if (addBtn) {
                        addBtn.scrollIntoView({ block: 'center' });
                        const rect = addBtn.getBoundingClientRect();
                        if (rect.width > 0 && rect.height > 0) {
                          return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                        }
                      }
                    }
                  }
                  return null;
                }
                """, attrName);

        if (btnCoords != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> dc = (Map<String, Object>) btnCoords;
            double bx = ((Number) dc.get("x")).doubleValue();
            double by = ((Number) dc.get("y")).doubleValue();
            page.mouse().move(bx, by);
            page.waitForTimeout(200);
            page.mouse().click(bx, by);
        }
    }

    /**
     * 点击"确认创建"按钮
     */
    private void clickConfirmCreateBtn(Page page) {
        page.waitForTimeout(1000);
        Object btnCoords = page.evaluate("""
                () => {
                  const btns = document.querySelectorAll('.next-btn-primary');
                  for (const btn of btns) {
                    if (btn.textContent.trim().includes('确认创建')) {
                      btn.scrollIntoView({ block: 'center' });
                      const rect = btn.getBoundingClientRect();
                      if (rect.width > 0 && rect.height > 0) {
                        return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                      }
                    }
                  }
                  return null;
                }
                """);
        if (btnCoords == null) {
            System.out.println("未找到'确认创建'按钮");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> coords = (Map<String, Object>) btnCoords;
        double x = ((Number) coords.get("x")).doubleValue();
        double y = ((Number) coords.get("y")).doubleValue();
        page.mouse().move(x, y);
        page.waitForTimeout(200);
        page.mouse().click(x, y);
        System.out.println("已点击'确认创建'按钮");
        page.waitForTimeout(3000);
    }

    /**
     * 获取指定属性当前有多少个 input（用于校验+按钮是否生效）
     */
    private int getInputCount(Page page, String attrName) {
        Object count = page.evaluate("""
                (attrName) => {
                  const saleProps = document.querySelector('.sell-component-sale-props');
                  if (!saleProps) return 0;
                  const wraps = saleProps.querySelectorAll('.common-wrap');
                  for (const wrap of wraps) {
                    const label = wrap.querySelector('.props-label');
                    if (label && label.textContent.trim().includes(attrName)) {
                      return wrap.querySelectorAll('.color-sub-items input').length;
                    }
                  }
                  return 0;
                }
                """, attrName);
        return count instanceof Number ? ((Number) count).intValue() : 0;
    }
}
