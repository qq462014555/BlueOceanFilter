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
