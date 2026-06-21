# 项目业务模块地图

> 生成日期：2026-06-20
> 说明：按业务功能分组，非目录结构。用于需求分析师了解项目现状。

---

## 业务模块 1：蓝海词 AI 筛选

**概述**：核心功能。用户上传 Excel 关键词文件，通过通义千问（qwen3-max/qwen3.6-plus）AI 逐个判定是否符合蓝海词筛选规则，输出合规/剔除两份 Excel。

| 项目 | 内容 |
|---|---|
| 入口 | `FilterController.java`（558行，偏大） |
| 核心类 | `KeywordProcessingService.java`（~400行，需拆分），`DashScopeClient.java`（~450行） |
| 数据库 | `filter_task`（任务表），`history_keyword`（历史词库），`trend_keywords`（趋势词） |
| 数据流 | 上传 Excel → 读词 → 去重 → AI 批量判定 → 对比历史词库 → 写库 → 生成结果 Excel |
| 关键技术 | DashScope HTTP API（阿里云通义千问），Apache POI |
| 涉及文件 | FilterController, KeywordProcessingService, DashScopeClient, ExcelReader, ExcelWriter, FilterTaskMapper, KeywordHistoryMapper, TrendKeywordMapper, TrendKeyword, TrendWordInfo, FilterTask, KeywordHistory, KeywordResult |
| 状态 | ✅ 稳定运行 |

---

## 业务模块 2：1688 商品采集（Playwright 自动化）

**概述**：通过 Playwright + CDP 连接本地 Chrome，从 1688 采集商品的主图、详情图、SKU、属性、视频、包装信息，批量下载图片，生成 Excel 汇总。

| 项目 | 内容 |
|---|---|
| 入口 | `ScraperController.java`（217行） |
| 核心类 | `ProductScraper.java`（**~1200行，过大，需要拆分**） |
| 调试端口 | Chrome CDP 9222（1688 采集专用） |
| 数据流 | 读 `_链接.txt` → Playwright 并发抓取 → OCR 区域检测 → 下载主图/详情图/SKU图/视频 → 解析价格 → 生成 JSON/CSV → 保存到 RPA 目录 |
| 关键技术 | Playwright（CDP 连接），PaddleOCR（本地 HTTP 服务），POI Excel |
| 涉及文件 | ScraperController, ProductScraper, ProductData, SkuData, ScraperConfig, ExcelReporter, LinkFileReader, LinkEntry, UrlCleaner, PaddleOcrService, ChromeLauncher, ChromeDebugConfig, ChromePortMonitorController |
| 状态 | ✅ 稳定运行 |

---

## 业务模块 3：商品属性 AI 填充与映射

**概述**：通过 AI（qwen-vl-max + 截图）自动分析商品属性，将有对应关系的属性映射到商家后台字段。

| 项目 | 内容 |
|---|---|
| 入口 | `AttributeAutoFillController.java`（57行），`AttributeMappingController.java`（185行） |
| 核心类 | `AttributeAutoFillService.java`（~350行），`AttributeMappingService.java`（~100行） |
| 数据流 | 按标题查找商品目录 → 读采集属性 JSON → 截图 → 调用 qwen-vl-max 多模态识别 → 生成映射结果 → 可选填写到浏览器 |
| 关键技术 | qwen-vl-max（多模态视觉 AI），CDP 浏览器连接 |
| 涉及文件 | AttributeAutoFillController, AttributeAutoFillService, AttributeMappingController, AttributeMappingService, MerchantAttributeExtractor, AttributeSaver |
| 状态 | ✅ 稳定运行 |

---

## 业务模块 4：千牛商品发布（属性 + SKU）

**概述**：连接到千牛发布页面（Chrome 9223 端口），提取页面属性字段和 SKU 属性，AI 分析填充后自动填写到浏览器。

| 项目 | 内容 |
|---|---|
| 入口 | `QianniuAttributeController.java`（165行），`SkuFillController.java`（140行） |
| 核心类 | `QianniuAttributeScraper.java`（~200行），`SkuFillService.java`（~400行），`SkuAttrExtractor.java`（381行） |
| 调试端口 | Chrome CDP 9223（千牛发布专用） |
| 数据流 | 连接千牛页面 → 提取属性字段 → AI 分析填充 → 信号通知 Claude 填写 → 提取 SKU 属性名 → AI 生成 SKU 结构 → 填写 SKU + 价格库存 + 图片 |
| 关键技术 | Playwright CDP，信号文件（signal/ 目录轮询），策略模式（SkuAttrExtractorFactory） |
| 涉及文件 | QianniuAttributeController, QianniuAttributeScraper, SkuFillController, SkuFillService, SkuAttrExtractor, SkuAttrExtractorFactory, PresetAttrExtractor, CreateSpecAttrExtractor |
| 状态 | ✅ 稳定运行 |

---

## 业务模块 5：文件管理与图片/SKU 编辑

**概述**：RPA 工作目录下的文件管理，包括图片查看/替换/重排、SKU 增删改排序、视频操作、属性文件读写。

| 项目 | 内容 |
|---|---|
| 入口 | `FileController.java`（**752行，过大，需要拆分**），`FileManagerController.java`（199行） |
| 核心类 | `ImageFileService.java`（~120行），`MainImageService.java`（~70行），`DetailImageService.java`（~50行），`SkuImageService.java`（~50行），`VideoService.java`（~50行），`SkuRenameService.java`（~100行） |
| 数据流 | 前端请求 → FileController → 各 Service（图片/SKU/视频） → 操作磁盘文件 → 返回结果 |
| 关键技术 | 文件系统读写，JSON 属性文件同步，CSV 价格表同步 |
| 涉及文件 | FileController, FileManagerController, ImageFileService, MainImageService, DetailImageService, SkuImageService, VideoService, SkuRenameService |
| 状态 | ✅ 稳定运行 |

---

## 业务模块 6：基础设施与工具

**概述**：Chrome 调试浏览器管理、信号机制、OpenClaw 网关管理、其他辅助工具。

| 项目 | 内容 |
|---|---|
| 核心类 | `OpenClawController.java`（199行），`ClaudeSignal.java`（134行），`SignalConsumer.java`（~180行） |
| 数据流 | OpenClaw: WSL 进程管理 → 检测端口 → 启停控制；Signal: 写 signal/ JSON → 3秒轮询 → Playwright 执行 |
| 涉及文件 | OpenClawController, ClaudeSignal, SignalConsumer, InspectSxkc, ChromeLauncher, ChromeDebugConfig, ChromePortMonitorController, ChromePortMonitor, MailConfig, EmailNotificationService |
| 状态 | ✅ 稳定运行 |

---

## 文件行数健康度

| 文件 | 行数 | 结论 |
|---|---|---|
| `ProductScraper.java` | ~1200 | 🔴 严重超限，需拆分为多个类 |
| `FileController.java` | 752 | 🔴 严重超限，需拆出公共逻辑 |
| `FilterController.java` | 558 | 🟡 偏大，建议拆分 |
| `DashScopeClient.java` | ~450 | 🟡 偏大，建议拆分 |
| `KeywordProcessingService.java` | ~400 | 🟡 接近上限 |
| `SkuFillService.java` | ~400 | 🟡 接近上限 |
| `SkuAttrExtractor.java` | 381 | 🟡 接近上限 |

---

## 总体架构

```
用户前端 (HTML/JS)
    |
    ├── FilterController  ──>  KeywordProcessingService  ──>  DashScopeClient (AI 筛选)
    │                             |
    │                             +-> ExcelReader / ExcelWriter (POI)
    │                             +-> FilterTaskMapper / KeywordHistoryMapper
    │
    ├── ScraperController  ──>  ProductScraper  ──>  PaddleOcrService (OCR)
    │                            |                  +-> Playwright CDP :9222 (1688)
    │                            +-> ChromeLauncher
    │                            +-> ExcelReporter / LinkFileReader
    │
    ├── QianniuAttributeController  ──>  QianniuAttributeScraper (CDP :9223)
    │                                   +-> AttributeAutoFillService (AI + 截图)
    │                                          +-> ClaudeSignal (信号通知)
    │
    ├── SkuFillController  ──>  SkuFillService  ──>  SkuAttrExtractorFactory
    │                            |                    +-> PresetAttrExtractor
    │                            |                    +-> CreateSpecAttrExtractor
    │                            +-> Playwright CDP :9223
    │
    ├── AttributeMappingController  ──>  AttributeMappingService + MerchantAttributeExtractor
    │
    ├── FileController  ──>  MainImageService / DetailImageService / SkuImageService / VideoService
    │                       +-> SkuRenameService / ImageFileService
    │
    ├── FileManagerController  (简易文件浏览)
    │
    ├── OpenClawController  (WSL OpenClaw 网关管理)
    │
    └── ChromePortMonitorController  (9224 端口自动保活)
```

**共 6 个业务模块，40 个 Java 文件。**

核心模式：**Spring Boot REST 后端 + Playwright CDP 浏览器自动化 + DashScope AI API**。
操作目录统一为 `C:\Users\46201\Documents\无极RPA文件处理`。