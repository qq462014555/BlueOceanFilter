# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

# BlueOceanFilter - 蓝海词筛选系统

电商蓝海词 AI 筛选工具，上传 Excel 文件，通过通义千问（DashScope）大模型自动过滤不合规词，输出合规/剔除两份结果。

## 技术栈

- **Java 17** + **Spring Boot 3.2.5**
- **MyBatis-Plus 3.5.7** + **MySQL 8**
- **DashScope SDK 2.22.14**（通义千问 qwen3-max）
- **Apache POI 5.2.3**（Excel 读写）
- **FastJSON2 2.0.51**
- **Spring Boot Mail**（任务完成/失败邮件通知）

## 邮件通知配置

任务完成或失败时自动发送邮件通知。需设置以下环境变量：

| 变量 | 说明 | 默认值 |
|---|---|---|
| `MAIL_FROM` | 发件人邮箱 | 无（必须设置） |
| `MAIL_TO` | 收件人邮箱 | 无（必须设置） |
| `MAIL_HOST` | SMTP 服务器地址 | smtp.qq.com |
| `MAIL_PORT` | SMTP 端口 | 587 |
| `MAIL_USERNAME` | SMTP 用户名 | 同 MAIL_FROM |
| `MAIL_PASSWORD` | SMTP 授权码 | 无（必须设置） |

示例（Windows CMD）：
```cmd
set MAIL_FROM=your@qq.com
set MAIL_TO=your@qq.com
set MAIL_PASSWORD=your_smtp_auth_code
bash mvnw spring-boot:run
```

## 常用命令

```bash
bash mvnw spring-boot:run              # 开发运行（需要 MySQL 已启动）
bash mvnw clean package                # 构建 jar
bash mvnw dependency:resolve           # 下载依赖
bash mvnw test                         # 运行测试（当前无测试）
java -jar target/BlueOceanFilter-1.0-SNAPSHOT.jar  # 生产运行
```

## 数据库初始化

MySQL 需已运行，连接信息在 `application.yml`。创建数据库和表：

```sql
CREATE DATABASE blue;
USE blue;

CREATE TABLE filter_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    status VARCHAR(20),
    originalFileName VARCHAR(255),
    keptFileName VARCHAR(255),
    excludedFileName VARCHAR(255),
    totalWords INT DEFAULT 0,
    processedWords INT DEFAULT 0,
    create_time DATETIME,
    finish_time DATETIME
);

CREATE TABLE history_keyword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    word VARCHAR(255),
    create_time DATETIME
);
```

## 项目结构

```
src/main/java/com/blueocean/
├── BlueOceanApplication.java      # 启动类，@EnableAsync + @MapperScan
├── ExcelWriter.java               # Excel 输出（静态方法，带样式）
├── config/
│   ├── AppProperties.java         # app.* 配置（apiKey/批量大小/重试/休眠/输出目录）
│   └── AsyncConfig.java           # 单线程池 taskExecutor（防并发限流）
├── controller/
│   └── FilterController.java      # REST API（上传/状态/下载/历史）
├── entity/
│   ├── FilterTask.java            # 任务表 filter_task
│   ├── KeywordHistory.java        # 历史词库表 history_keyword
│   └── KeywordResult.java         # 单次筛选结果（非实体）
├── mapper/
│   ├── FilterTaskMapper.java      # 继承 BaseMapper
│   └── KeywordHistoryMapper.java  # 含 @Insert 批量插入
└── service/
    ├── DashScopeClient.java       # 调用 qwen3-max API 做词筛选
    ├── ExcelReader.java           # 读取 xlsx 第一列关键词
    └── KeywordProcessingService.java  # 核心编排：上传→读词→AI筛选→去重→输出
src/main/resources/
├── application.yml                # 数据库/文件上传/AI 配置
├── static/index.html              # 前端页面
└── mapper/*.xml                   # MyBatis XML（如有）
input/                             # 示例输入文件
output/                            # 结果输出目录
```

## 数据库表

### filter_task
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | 主键 |
| status | VARCHAR | PENDING/RUNNING/COMPLETED/FAILED |
| originalFileName | VARCHAR | 上传原始文件名 |
| keptFileName | VARCHAR | 合规结果文件名（UUID_kept.xlsx） |
| excludedFileName | VARCHAR | 剔除结果文件名（UUID_excluded.xlsx） |
| totalWords | INT | 总词数 |
| processedWords | INT | 已处理词数 |
| create_time | DATETIME | 创建时间 |
| finish_time | DATETIME | 完成时间 |

### history_keyword
| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | 主键 |
| word | VARCHAR | 关键词 |
| create_time | DATETIME | 创建时间 |

## API 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/filter/upload` | 上传 .xlsx 文件，返回 taskId |
| GET | `/api/filter/status/{taskId}` | 查询任务进度 |
| GET | `/api/filter/download/{taskId}/{type}` | 下载结果，type=kept/excluded |
| GET | `/api/filter/history?page=1&size=20` | 历史词库分页 |

## 核心流程

```
用户上传 Excel
    │
    ▼
Controller: file.getBytes() → 创建 FilterTask → 异步调用
    │
    ▼
KeywordProcessingService.processSync()
    ├── ① 字节数组写入临时文件
    ├── ② ExcelReader 读取关键词列表（跳过首行）
    ├── ③ 从 DB 加载历史词库（去重用）
    ├── ④ 分批调用 DashScopeClient.filterBatch()（默认每批150词）
    │       失败重试 3 次，批次间休眠 1s
    ├── ⑤ 与历史词库去重
    ├── ⑥ 新词批量写入 history_keyword（每批500条）
    ├── ⑦ ExcelWriter 生成两份结果 Excel
    └── ⑧ 更新任务状态 COMPLETED，清理临时文件
```

## 关键注意事项

- **异步上传陷阱**：`MultipartFile` 的临时文件在 HTTP 请求结束后被 Tomcat 清理，所以 Controller 里先读成 `byte[]` 再传给异步服务
- **单线程池**：`AsyncConfig` 配置 corePoolSize=1、maxPoolSize=1，防止并发调用 AI API 触发限流
- **AI 模型**：使用 `qwen3-max`，System Prompt 内嵌了 12 条筛选规则（服装/化妆品/食品/品牌/违禁/低价等）
- **输出文件命名**：结果文件使用 `UUID_kept.xlsx` / `UUID_excluded.xlsx`，下载时浏览器显示为中文名
- **结果下载 Content-Type**：`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- **历史去重**：已保留的新词会写入 `history_keyword`，后续上传遇到相同词会标记为"历史词库中已存在"

## 前端入口

启动后访问 `http://localhost:8080/index.html`。静态页面放在 `src/main/resources/static/`。

---

# 开发工作流规范（重要）

> 以下规则适用于所有 AI 辅助开发。目的是解决：需求不清晰就开写 → AI 写出大文件 → 上下文爆炸 → 出 bug。

## 🧠 角色一：需求分析师（Product Manager）

### 职责
- 在任何代码改动前，先完成需求分析
- **掌握项目现有业务模块**，知道哪些已有、哪些能复用、哪些要新建

### 需求分析模板
当用户提出一个需求时，先输出以下结构化的分析，**得到用户确认后才能进入编码阶段**：

```markdown
## 需求分析

### 业务背景
（解决什么问题？用户是谁？为什么需要这个功能？）

### 功能清单
1. （功能点描述）
2. ...

### 涉及范围
- 新增文件：
- 修改文件：
- 无需改动：

### 数据流
输入 → 处理步骤 → 输出

### 验收标准
- [ ] （可验证的标准）
- [ ] ...

### 不在此次范围
（明确排除什么，防止 scope creep）
```

### 产出物要求
- 需求分析文档输出后，用户说"可以做"才能进入编码
- 编码前必须**列出本次要改的所有文件 + 估算行数**
- 如果有 UI 改动，需要描述交互流程（不需要图，文字即可）

### 📦 需求文档归档（重要）
确认后的需求分析文档**必须保存到文件**，防止上下文压缩后丢失：

**归档规则：**
- 保存路径：`docs/requirements/YYYY-MM-DD-简短功能名.md`
- 文件名用中文 + 日期，如 `2026-06-20-导出CSV功能.md`
- 内容包含完整的需求分析 + 用户确认的全部细节
- 同时在 `docs/requirements/INDEX.md` 中追加一条索引记录
- 在记忆系统 `memory/` 中记录索引

**INDEX 机制：**
- `docs/requirements/INDEX.md` 是索引文件，只记录概要（日期、功能、状态）
- **新会话启动时，先读取 INDEX.md** 获取全部历史需求概览
- 当需要回顾某个历史需求时，通过 INDEX 找到文件名，再单独读取该文件
- 这样跨会话也不会断档：INDEX 在 → 能找到对应的归档文件 → 细节全回来

**归档示例：**
```markdown
# docs/requirements/2026-06-20-导出CSV功能.md

## 需求分析（确认版）

### 业务背景
...
### 功能清单
...
### 验收标准
...
### 用户确认日期
2026-06-20
### 状态
🚧 开发中 / ✅ 已完成 / 💡 已规划 / ❌ 已取消
```

### 需求状态管理
INDEX.md 中有"状态"列，归档时按实际情况填写：

| 状态 | 含义 |
|---|---|
| 💡 已规划 | 需求已确认，暂未开发 |
| 🚧 开发中 | 正在编码 |
| ✅ 已完成 | 已开发上线 |
| ❌ 已取消 | 决定不做 |

状态变更时（如开发完成），更新 INDEX.md 中的对应行。

---

## 👷 角色二：技术负责人（Tech Lead / 架构师）

### 职责
保证代码质量：控制文件大小、遵守架构、减少 bug。

### 文件行数硬约束
| 类型 | 上限        | 超限处理 |
|---|-----------|---|
| Java Service 类 | **800 行** | 拆出辅助类 / Utils |
| Java Controller | **520 行** | 拆出公共逻辑 |
| Java Entity / Mapper | **100 行** | 不拆 |
| HTML 文件 | **400 行** | 拆出 JS/CSS 片段或组件 |
| JS 文件 | **300 行** | 拆模块 |
| 其他文件 | **300 行** | 按职责拆分 |

### 代码质量控制
1. **先审文件清单**：编码前必须列出本次要改/新增的所有文件及估算行数
2. **遵循现有模式**：新增功能必须参考项目中同类文件的结构（如 KeywordProcessingService 的模式）
3. **单次改动不过大**：单次 PR/提交，总 diff 建议不超过 500 行
4. **不重复造轮子**：先检查项目里有没有现成的工具方法
5. **中文日志优先**：用户是中文本地，业务日志用中文，方便排查

### 🛡️ 防bug六大机制
1. **改前先查** — 新增函数/方法前，必须全局搜索确认不存在同名函数。JS 用 `grep function 名称`，Java 用 `grep 方法名`
2. **改后即编** — 每改完一个 Java 文件，立即运行 `mvn compile` 验证编译通过；JS/HMTL 改完检查控制台无报错
3. **小步快跑** — 一次最多改 3 个文件，改完编译验证通过后才能改下一个文件。禁止一次改 5 个以上文件不编译
4. **前后端分离** — 改 Java 后端时只关注后端，改前端时只关注前端。前后端同时改动时先改后端编译通过，再改前端
5. **回归检查** — 改完旧功能时，检查原有逻辑是否被破坏（如改 `showStatus` 要看所有调用它的地方）
6. **编译检查** — 编码阶段最后一步必须是编译验证，编译失败不交付

### 拆分策略示例
```
KeywordProcessingService.java (~380行) → 已接近上限
  ├── KeywordDeduplicator.java      ← 拆出去重逻辑
  ├── KeywordExcludeChecker.java    ← 拆出 EXCLUDED_TERMS 匹配
  └── KeywordProcessingService.java ← 保留编排逻辑
```

---

## 🔄 完整开发工作流

### Step 0：项目现状摸底（按需执行）

在新项目首次对接、或不熟悉当前代码状态时执行。**输出项目业务模块地图，后续所有需求分析都基于此地图讨论。**

触发方式：你说 **"摸底项目现状"**

执行步骤：
1. 扫描 `src/main/java/com/blueocean/` 全部 Java 文件，按业务功能分组（不是按目录分组）
2. 对每个模块理解：入口类、核心逻辑、数据流、输出
3. 输出 `docs/architecture/existing-modules.md` — 业务模块地图

**业务模块地图模板：**
```markdown
# 项目业务模块地图

## 模块一：关键词筛选（核心流程）
| 项目 | 内容 |
|---|---|
| 入口 | FilterController.upload() |
| 核心类 | KeywordProcessingService（380行） |
| 数据流 | 上传 Excel → 读词 → 去重 → AI 筛选 → 输出两份 Excel |
| 涉及文件 | ExcelReader, DashScopeClient, ExcelWriter |
| 状态 | ✅ 稳定运行 |

## 模块二：文件管理
...
```

**产出物：** `docs/architecture/existing-modules.md` 随项目迭代持续更新。

---

### 一句话概括：**用户只对接产品经理 → 产品经理下发系统负责人 → 系统负责人编码**

```
你提需求
    │  你只跟「产品经理」对话
    ▼
Step 1 ── 产品经理（需求分析师）
    │       追问细节，理清需求
    │       输出：需求分析文档（背景/功能/范围/验收标准）
    │       你要做：确认 → 或 补充修改
    │       确认后进入下一步
    ▼
Step 1.5 ── 产品经理归档需求
    │       保存到 docs/requirements/日期-功能名.md
    │       INDEX.md 追加索引行
    │       memory 记录索引
    │       然后自动下发到「系统负责人」
    ▼
Step 2 ── 系统负责人（技术负责人）
    │       接收产品经理下发需求
    │       输出：文件清单（改哪些文件 + 各多少行）
    │       有问题 → 反馈给产品经理 → 产品经理找你确认
    │       没问题 → 确认后进入编码
    ▼
Step 3 ── 系统负责人开始编码
    │       ① 改前先查：搜索确认无同名函数/方法
    │       ② 每次最多改3个文件
    │       ③ 每改完1个文件 → 编译验证（Java）或检查控制台（JS）
    │       ④ 全部改完 → 最终编译验证
    │       ⑤ 受行数约束，超限自动拆分
    ▼
完成 ── 你验收，反馈问题
```

### 你只需要记住一句话：
> **"先做需求分析"** — 每次提需求时，用这 5 个字开头，AI 会自动执行上面的流程。

你的对话对象始终只有 **产品经理**，系统负责人由产品经理内部调度，你不用直接指挥他。

---

## 🔁 中途需求变更流程

编码开发中，如果需求有变化，按以下流程处理：

```
你: "需求变了，XXX 要改一下"
                    ↓
AI: 读取对应的归档文件（通过 INDEX 查找）
                    ↓
    输出变更分析：
      - 原需求是什么
      - 变更为
      - 影响范围（哪些文件已写好的要改）
      - 是否要重新列文件清单
                    ↓
你: "确认变更"
                    ↓
AI: 更新归档文件中的内容
    （追加"变更记录"章节，保留原需求历史）
    更新 INDEX 状态（如有必要）
    继续编码
```

**变更记录示例：**
```markdown
### 变更记录
| 日期 | 变更内容 | 原因 |
|---|---|---|
| 2026-06-22 | 导出格式从 CSV 改为 Excel | 用户要求 |
| 2026-06-25 | 增加日期范围筛选 | 补充需求 |
```

这样即使需求变了，**历史记录完整可追溯**，不会丢失上下文。你只需要说 **"需求变了"** 就能触发变更流程。

---

## ⚡ 会话启动指令（每次新会话自动执行）

新会话启动时，按以下顺序执行：
1. 读取 `docs/requirements/INDEX.md` — 加载历史需求索引，避免跨会话断档
2. 检查 `docs/architecture/existing-modules.md` 是否存在，存在则读取 — 了解现有业务模块
3. 根据 INDEX 和业务模块地图，了解项目上下文，准备就绪
