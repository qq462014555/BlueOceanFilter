---
name: browser-signal
description: Java 代码完成后通过信号文件自动触发浏览器操作
---

# Browser Signal Handler

当 Java 代码在 `signal/` 目录写入 JSON 信号文件时，自动接管浏览器执行操作。

## 信号目录

`signal/` （项目根目录）

## 信号格式

```json
{
  "source": "类名.方法名",
  "action": "具体动作",
  "details": "详细说明",
  "jsonPath": "已填值的 JSON 文件路径",
  "productId": "商品标识(可选)"
}
```

## 支持的 action

| action | 行为 |
|--------|------|
| `fill_fields` | 读取 JSON 文件，逐个字段填到千牛商品发布页面 |
| `screenshot` | 截图当前页面状态，展示给用户 |
| `verify_fill` | 检查页面已填值与 JSON 对比，报告差异 |
| `custom` | 按 details 自由描述执行 |

## fill_fields 执行步骤

1. 读取信号文件，解析 action/jsonPath/productId
2. 从 jsonPath 读取 JSON 文件（字段包含 label/type/currentValue/options）
3. 连接 Chrome CDP http://127.0.0.1:9223
4. 找到包含「商品发布」的标签页
5. 逐字段填写：
   - input/measurement → 找 `.default-items-item` 中匹配 label 的行 → 直接 input.fill(value)
   - select/combobox → 找匹配 label 的行 → 点击 `.next-input-control` → 等 overlay → 选 `.options-item` 中精确匹配项
   - checkbox → 同 select，但多选（逗号分隔值逐个勾选）
6. 每个字段之间等待 300ms
7. 填写完成后截图展示给用户
8. 报告成功/失败的字段列表
9. 删除信号文件

## 字段定位

- 行：`.default-items-item` 的 nth 个
- label：`.sell-component-info-wrapper-label [title]` 或 `.sell-component-info-wrapper-label`
- input 控件：`input`
- select 控件：`.next-input-control` 点击后出现 `.options-item` 选项

## 注意事项

- 连接失败时不删除信号文件，等待重试
- 填写失败时停止后续操作，报告具体失败字段
- 使用 CDP connectOverCDP，不要用 `browser.close()`
- 每次只处理一个信号文件，按文件创建时间排序