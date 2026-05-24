import json
import time
from playwright.sync_api import sync_playwright

with open(r"G:\BlueOceanFilter\signal\fill_fields_可穿戴纸箱机器人儿童手工 DIY 拼装涂色机甲铠甲趣味益智玩具_102581f1.json", "r", encoding="utf-8") as f:
    signal = json.load(f)

with open(signal["jsonPath"], "r", encoding="utf-8") as f:
    data = json.load(f)

fields = data.get("fields", [])
print(f"共 {len(fields)} 个字段需要填写")

with sync_playwright() as pw:
    browser = pw.chromium.connect_over_cdp("http://127.0.0.1:9223")
    context = browser.contexts[0]
    pages = context.pages

    # 找到商品发布标签页
    target_page = None
    for page in pages:
        if "商品发布" in page.title():
            target_page = page
            break
    if not target_page:
        print("未找到商品发布页面")
        exit(1)

    target_page.bring_to_front()
    time.sleep(1)

    results = []
    for field in fields:
        label = field["label"]
        field_type = field["type"]
        value = field["currentValue"]
        options = field.get("options", [])

        print(f"填写: {label} ({field_type}) = {value}")

        try:
            # 找到包含该 label 的 .default-items-item
            items = target_page.locator(".default-items-item").all()
            target_item = None
            for item in items:
                try:
                    label_el = item.locator(".sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label")
                    if label_el.count() > 0:
                        item_label = label_el.first.inner_text(timeout=500).strip()
                        if item_label == label:
                            target_item = item
                            break
                except:
                    continue

            if not target_item:
                results.append(f"❌ {label}: 未找到对应的字段行")
                continue

            if field_type == "select":
                # 点击下拉控件
                control = target_item.locator(".next-input-control").first
                control.click()
                time.sleep(0.5)

                # 选择匹配的选项
                option_selector = f"xpath=//*[contains(text(), '{value}')]"
                option = target_page.locator(f".options-item:has-text('{value}')").first
                if option.count() > 0:
                    option.click()
                    time.sleep(0.5)
                    results.append(f"✅ {label}: 已选择 '{value}'")
                else:
                    results.append(f"❌ {label}: 找不到选项 '{value}'")

            elif field_type == "combobox":
                # 输入框类型
                input_el = target_item.locator("input").first
                input_el.click()
                input_el.fill(value)
                time.sleep(0.3)
                input_el.press("Enter")
                time.sleep(0.3)
                results.append(f"✅ {label}: 已输入 '{value}'")

            elif field_type == "checkbox":
                values = [v.strip() for v in value.split(",")]
                control = target_item.locator(".next-input-control").first
                control.click()
                time.sleep(0.5)

                for v in values:
                    option = target_page.locator(f".options-item:has-text('{v}')").first
                    if option.count() > 0:
                        option.click()
                        time.sleep(0.3)
                results.append(f"✅ {label}: 已勾选 '{value}'")

            time.sleep(0.3)

        except Exception as e:
            results.append(f"❌ {label}: 填写失败 - {str(e)}")
            break

    target_page.screenshot(path="G:\\BlueOceanFilter\\signal\\filled_screenshot.png")
    print("\n=== 填写结果 ===")
    for r in results:
        print(r)

    browser.close()
