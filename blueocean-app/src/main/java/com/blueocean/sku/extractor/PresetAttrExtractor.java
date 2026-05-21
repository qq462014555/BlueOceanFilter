package com.blueocean.sku.extractor;

import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 预置 SKU 属性提取器
 * "销售属性"标签在 #struct-saleProp，按钮在相邻兄弟的容器里
 * 两个 div 是兄弟关系，不是父子关系
 */
public class PresetAttrExtractor implements SkuAttrExtractor {

    @Override
    public boolean matches(Page page) {
        try {
            Object result = page.evaluate("""
                    () => {
                      const xpath = "//*[text()='销售属性'] | //*[contains(text(),'销售属性')]";
                      const label = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
                      if (!label) return false;

                      // 如果"销售属性"区域有"创建规格"按钮，交给 CreateSpecAttrExtractor 处理
                      let container = label;
                      for (let i = 0; i < 10 && container.parentElement; i++) {
                        container = container.parentElement;
                        const text = container.textContent || '';
                        if (text.includes('创建规格')) return false;
                      }
                      return true;
                    }
                    """);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> extract(Page page) {
        try {
            // 1. 循环删除所有已添加的属性（每次点第一个，等消失后再找下一个）
            for (int i = 0; i < 10; i++) {
                TimeUnit.SECONDS.sleep(1);
                Object firstBtnCoords = page.evaluate("""
                        () => {
                          const salePropArea = document.querySelector('#sell-field-saleProp');
                          if (!salePropArea) return null;
                          const btns = salePropArea.querySelectorAll('button.delete');
                          if (btns.length === 0) return null;
                          const rect = btns[0].getBoundingClientRect();
                          return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                        }
                        """);
                if (firstBtnCoords == null) break;

                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> coord = (java.util.Map<String, Object>) firstBtnCoords;
                double x = ((Number) coord.get("x")).doubleValue();
                double y = ((Number) coord.get("y")).doubleValue();
                page.mouse().click(x, y);
                page.waitForTimeout(300);
            }
        } catch (Exception e) {
            // 删除失败不影响后续提取
        }

        // 2. 从属性按钮容器提取 .next-btn-helper 的文本
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) page.evaluate("""
                () => {
                  const controller = document.querySelector('.sell-component-custom-sale-props-controller');
                  if (!controller) return [];

                  const helpers = controller.querySelectorAll('.next-btn-helper');
                  const props = [];
                  for (const h of helpers) {
                    const text = h.textContent.trim();
                    if (text && text.length > 0 && text.length < 20
                        && !text.includes('申报') && !text.includes('查看')) {
                      props.push(text);
                    }
                  }
                  return props;
                }
                """);
        return result;
    }

    /**
     * 探测所有可用属性的输入框类型
     * @return List of {name, inputType("text"/"dropdown"), important(boolean)}
     */
    public List<Map<String, Object>> detectAttrInputTypes(Page page) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // 1. 先获取所有可用属性按钮名称
            @SuppressWarnings("unchecked")
            List<String> attrNames = (List<String>) page.evaluate("""
                    () => {
                      const container = document.querySelector('#sell-field-customOptionalSaleProp .sell-component-custom-sale-props-controller');
                      if (!container) return [];
                      const helpers = container.querySelectorAll('.next-btn-helper');
                      const names = [];
                      for (const h of helpers) {
                        const text = h.textContent.trim();
                        if (text && text.length > 0 && text.length < 20
                            && !text.includes('申报') && !text.includes('查看')) {
                          names.push(text);
                        }
                      }
                      return names;
                    }
                    """);

            // 2. 依次点击每个按钮，探测输入框类型
            for (String attrName : attrNames) {
                // 点击按钮
                clickAttrButton(page, attrName);
                TimeUnit.MILLISECONDS.sleep(800);

                // 探测刚添加的属性区域的输入框类型和是否有"重要"标记
                String inputType = detectInputType(page, attrName);
                boolean isImportant = detectImportant(page, attrName);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", attrName);
                info.put("inputType", inputType);
                info.put("important", isImportant);
                result.add(info);

                // 删除刚添加的属性，恢复页面状态，继续探测下一个
                removeLastAddedAttr(page);
                TimeUnit.MILLISECONDS.sleep(300);
            }
        } catch (Exception e) {
            // 探测失败
        }
        return result;
    }

    /**
     * 检测指定属性区域是否有"重要"标记
     */
    private boolean detectImportant(Page page, String attrName) {
        try {
            Object result = page.evaluate("""
                    (attrName) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return false;
                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(attrName)) {
                          const tag = wrap.querySelector('.tagItem');
                          if (tag && tag.textContent.trim().includes('重要')) return true;
                          return false;
                        }
                      }
                      return false;
                    }
                    """, attrName);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检测指定属性区域的输入框类型
     * @return "text" 或 "dropdown"
     */
    private String detectInputType(Page page, String attrName) {
        try {
            Object result = page.evaluate("""
                    (attrName) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return 'unknown';
                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(attrName)) {
                          const input = wrap.querySelector('.sell-color-item-container input, .sale-props-auto-crop-pic-common-wrap input');
                          if (input) return 'text';
                          const select = wrap.querySelector('.next-select-inner, .next-select');
                          if (select) return 'dropdown';
                          return 'unknown';
                        }
                      }
                      return 'not-found';
                    }
                    """, attrName);
            return result != null ? result.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 删除最后一个已添加的属性（点击属性区块内的删除按钮）
     */
    private void removeLastAddedAttr(Page page) {
        try {
            Object result = page.evaluate("""
                    () => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return null;
                      // 找到最后一个 common-wrap 中的删除按钮
                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      if (wraps.length === 0) return null;
                      const lastWrap = wraps[wraps.length - 1];
                      const deleteBtn = lastWrap.querySelector('button.delete, .color-delete-icon');
                      if (!deleteBtn) return null;
                      const rect = deleteBtn.getBoundingClientRect();
                      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                    }
                    """);

            if (result == null) return;

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> coord = (java.util.Map<String, Object>) result;
            double x = ((Number) coord.get("x")).doubleValue();
            double y = ((Number) coord.get("y")).doubleValue();
            page.mouse().click(x, y);
            page.waitForTimeout(300);
        } catch (Exception e) {
            // 删除失败
        }
    }

    /**
     * 填写 SKU 选项值到页面（仅文本输入框的属性）
     * @param page 已连接到千牛商品发布页面的 Page 对象
     * @param attrOptions 属性名 → 选项值列表
     */
    public void fillSku(Page page, Map<String, List<String>> attrOptions) {
        try {
            // 1. 点击属性按钮，将属性添加到销售属性区域
            for (String attrName : attrOptions.keySet()) {
                clickAttrButton(page, attrName);
            }
            TimeUnit.MILLISECONDS.sleep(1000);

            // 2. 读取页面上属性的实际顺序（页面可能自动调整了）
            @SuppressWarnings("unchecked")
            List<String> pageOrder = (List<String>) page.evaluate("""
                    () => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return [];
                      const labels = saleProps.querySelectorAll('.props-label');
                      const names = [];
                      for (const label of labels) {
                        const text = label.textContent.trim().replace(/\\(0\\)/g, '').trim();
                        if (text) names.push(text);
                      }
                      return names;
                    }
                    """);

            // 3. 按页面实际顺序填写选项值
            for (String attrName : pageOrder) {
                List<String> options = attrOptions.get(attrName);
                if (options == null || options.isEmpty()) continue;
                fillAttrOptions(page, attrName, options);
            }
        } catch (Exception e) {
            // 填写失败，记录日志
        }
    }

    /**
     * 点击属性按钮
     */
    private void clickAttrButton(Page page, String attrName) {
        try {
            Object result = page.evaluate("""
                    (attrName) => {
                      const container = document.querySelector('#sell-field-customOptionalSaleProp .sell-component-custom-sale-props-controller');
                      if (!container) return { found: false };

                      // 检查该属性是否已经添加到销售属性区域
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (saleProps) {
                        const labels = saleProps.querySelectorAll('.props-label');
                        for (const label of labels) {
                          if (label.textContent.trim().includes(attrName)) {
                            return { found: true, alreadyAdded: true };
                          }
                        }
                      }

                      const buttons = container.querySelectorAll('.sell-component-custom-sale-props-controller-item');
                      for (const btn of buttons) {
                        const helper = btn.querySelector('.next-btn-helper');
                        if (helper && helper.textContent.trim() === attrName) {
                          const rect = btn.getBoundingClientRect();
                          return {
                            found: true,
                            alreadyAdded: false,
                            x: rect.left + rect.width / 2,
                            y: rect.top + rect.height / 2
                          };
                        }
                      }
                      return { found: false };
                    }
                    """, attrName);

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> info = (java.util.Map<String, Object>) result;
            boolean found = Boolean.TRUE.equals(info.get("found"));
            if (!found) return;

            boolean alreadyAdded = Boolean.TRUE.equals(info.get("alreadyAdded"));
            if (alreadyAdded) return;

            double x = ((Number) info.get("x")).doubleValue();
            double y = ((Number) info.get("y")).doubleValue();
            page.mouse().move(x, y);
            page.waitForTimeout(100);
            page.mouse().click(x, y);
            page.waitForTimeout(1000);
        } catch (Exception e) {
            // 点击失败
        }
    }

    /**
     * 填写属性的选项值
     */
    private void fillAttrOptions(Page page, String attrName, List<String> options) {
        try {
            // 先判断是文本输入框还是下拉框
            Object typeResult = page.evaluate("""
                    (attrName) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return 'unknown';
                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(attrName)) {
                          const input = wrap.querySelector('.sell-color-item-container input, .sale-props-auto-crop-pic-common-wrap input');
                          if (input) return 'text';
                          const select = wrap.querySelector('.next-select-inner, .next-select');
                          if (select) return 'dropdown';
                          return 'unknown';
                        }
                      }
                      return 'unknown';
                    }
                    """, attrName);
            String inputType = typeResult != null ? typeResult.toString() : "unknown";

            if ("text".equals(inputType)) {
                // 文本输入框：逐个填写
                for (int i = 0; i < options.size(); i++) {
                    String option = options.get(i);
                    fillSingleOption(page, attrName, option);
                    if (i < options.size() - 1) {
                        clickAddButton(page, attrName);
                    }
                    page.waitForTimeout(300);
                }
            } else if ("dropdown".equals(inputType)) {
                // 下拉框：打开下拉面板，逐个选择
                for (int i = 0; i < options.size(); i++) {
                    String option = options.get(i);
                    selectDropdownOption(page, attrName, option);
                    if (i < options.size() - 1) {
                        clickAddButton(page, attrName);
                    }
                    page.waitForTimeout(500);
                }
            }
        } catch (Exception e) {
            // 填写失败
        }
    }

    /**
     * 下拉框选择一个选项
     */
    private void selectDropdownOption(Page page, String attrName, String option) {
        try {
            // 1. 点击下拉框打开
            Object selectCoords = page.evaluate("""
                    (attrName) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return null;
                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(attrName)) {
                          const select = wrap.querySelector('.next-select-inner, .next-select');
                          if (select) {
                            const rect = select.getBoundingClientRect();
                            return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                          }
                        }
                      }
                      return null;
                    }
                    """, attrName);

            if (selectCoords != null) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> coord = (java.util.Map<String, Object>) selectCoords;
                double x = ((Number) coord.get("x")).doubleValue();
                double y = ((Number) coord.get("y")).doubleValue();
                page.mouse().click(x, y);
                page.waitForTimeout(500);
            }

            // 2. 在选项中匹配并点击（功率属性特殊处理：用正则匹配数字+w模式）
            Object clickResult = page.evaluate("""
                    (params) => {
                      const overlay = document.querySelector('.next-overlay-wrapper, .options-content');
                      if (!overlay) return { found: false };
                      const items = overlay.querySelectorAll('.options-item');
                      const option = params.option;
                      const attrName = params.attrName;
                      const isPower = attrName && attrName.includes('功率');

                      // 功率属性：用正则匹配 数字+w 模式
                      if (isPower) {
                        const powerRegex = /(\\d+[wW])/i;
                        const match = option.match(powerRegex);
                        const powerValue = match ? match[1].toLowerCase() : option.toLowerCase();
                        for (const item of items) {
                          const title = (item.getAttribute('title') || item.textContent || '').toLowerCase();
                          const itemMatch = title.match(powerRegex);
                          const itemValue = itemMatch ? itemMatch[1] : title;
                          if (itemValue === powerValue) {
                            item.click();
                            return { found: true };
                          }
                        }
                      }

                      // 精确匹配
                      for (const item of items) {
                        const title = item.getAttribute('title') || item.textContent || '';
                        if (title.trim() === option || title.trim().includes(option)) {
                          item.click();
                          return { found: true };
                        }
                      }
                      // 模糊匹配
                      for (const item of items) {
                        const title = item.getAttribute('title') || item.textContent || '';
                        if (title.trim().includes(option) || option.includes(title.trim())) {
                          item.click();
                          return { found: true };
                        }
                      }
                      return { found: false };
                    }
                    """, new java.util.LinkedHashMap<String, Object>() {{ put("attrName", attrName); put("option", option); }});

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> info = (java.util.Map<String, Object>) clickResult;
            boolean found = Boolean.TRUE.equals(info.get("found"));
            if (!found) {
                // 点击 Escape 关闭下拉框
                page.keyboard().press("Escape");
            }
        } catch (Exception e) {
            // 选择失败
        }
    }

    /**
     * 填写单个选项值
     */
    private void fillSingleOption(Page page, String attrName, String value) {
        try {
            Object result = page.evaluate("""
                    (attrName) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return null;

                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(attrName)) {
                          // 找到第一个空白的 input
                          const inputs = wrap.querySelectorAll('.sell-color-item-container input, .sale-props-auto-crop-pic-common-wrap input');
                          for (const input of inputs) {
                            if (!input.value || input.value.trim() === '') {
                              const rect = input.getBoundingClientRect();
                              return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                            }
                          }
                        }
                      }
                      return null;
                    }
                    """, attrName);

            if (result == null) return;

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> coord = (java.util.Map<String, Object>) result;
            double x = ((Number) coord.get("x")).doubleValue();
            double y = ((Number) coord.get("y")).doubleValue();

            page.mouse().click(x, y);
            page.waitForTimeout(200);
            // 使用 JS 设置 input 值，比 type 更可靠
            page.evaluate("""
                    (params) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return;
                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(params.attrName)) {
                          const inputs = wrap.querySelectorAll('.sell-color-item-container input, .sale-props-auto-crop-pic-common-wrap input');
                          for (const input of inputs) {
                            if (!input.value || input.value.trim() === '') {
                              input.value = params.value;
                              input.dispatchEvent(new Event('input', { bubbles: true }));
                              input.dispatchEvent(new Event('change', { bubbles: true }));
                              return;
                            }
                          }
                        }
                      }
                    }
                    """, new java.util.LinkedHashMap<String, Object>() {{ put("attrName", attrName); put("value", value); }});
            page.waitForTimeout(200);
        } catch (Exception e) {
            // 填写失败
        }
    }

    /**
     * 点击添加新行按钮
     */
    private void clickAddButton(Page page, String attrName) {
        try {
            Object result = page.evaluate("""
                    (attrName) => {
                      const saleProps = document.querySelector('.sell-component-sale-props');
                      if (!saleProps) return null;

                      const wraps = saleProps.querySelectorAll('.common-wrap');
                      for (const wrap of wraps) {
                        const label = wrap.querySelector('.props-label');
                        if (label && label.textContent.trim().includes(attrName)) {
                          const addBtn = wrap.querySelector('button.add');
                          if (addBtn) {
                            const rect = addBtn.getBoundingClientRect();
                            return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                          }
                        }
                      }
                      return null;
                    }
                    """, attrName);

            if (result == null) return;

            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> coord = (java.util.Map<String, Object>) result;
            double x = ((Number) coord.get("x")).doubleValue();
            double y = ((Number) coord.get("y")).doubleValue();
            page.mouse().click(x, y);
            page.waitForTimeout(500);
        } catch (Exception e) {
            // 点击失败
        }
    }
}
