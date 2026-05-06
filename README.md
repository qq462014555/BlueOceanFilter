# 蓝海词过滤工具 - 使用说明

## 环境要求
- Java 11 或以上
- Maven 3.6 或以上
- Anthropic API Key（去 https://console.anthropic.com 获取）

---

## 使用步骤

### 第一步：放入输入文件
把你的蓝海词 xlsx 文件放到项目的 `input/` 目录下，命名为 `蓝海词.xlsx`

```
BlueOceanFilter/
├── input/
│   └── 蓝海词.xlsx   ← 放这里
├── output/            ← 结果自动生成到这里
├── src/
└── pom.xml
```

### 第二步：填写 API Key
打开 `src/main/java/com/blueocean/Main.java`，找到配置区：

```java
private static final String API_KEY = "sk-ant-xxxx";  // ← 改成你的 API Key
```

### 第三步：编译打包
在项目根目录（有 pom.xml 的地方）执行：

```bash
mvn clean package -q
```

### 第四步：运行
```bash
java -jar target/BlueOceanFilter-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### 第五步：查看结果
运行完毕后，在 `output/` 目录找到：
- `合规蓝海词.xlsx` — 可直接选品的纯净蓝海词
- `剔除蓝海词.xlsx` — 被过滤掉的词 + 每条剔除原因

---

## 配置参数说明（Main.java 顶部）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| API_KEY | sk-ant-xxxx | 你的 Anthropic API Key |
| INPUT_FILE | input/蓝海词.xlsx | 输入文件路径 |
| OUT_KEPT | output/合规蓝海词.xlsx | 合规词输出路径 |
| OUT_EXCLUDED | output/剔除蓝海词.xlsx | 剔除词输出路径 |
| BATCH_SIZE | 50 | 每批发给AI的词数（建议40-60） |
| RETRY_TIMES | 3 | API失败自动重试次数 |
| SLEEP_MS | 1000 | 批次间隔毫秒（防止API限速） |

---

## 费用估算
- 3269个词 / 50个每批 = 约66批
- 每批约 800 input tokens + 600 output tokens
- 总计约 93,000 tokens，使用 claude-sonnet-4
- 大约花费：**$0.3 ~ $0.5 美元**

---

## 常见问题

**Q: API Key 在哪里获取？**
A: 去 https://console.anthropic.com → API Keys → Create Key

**Q: 运行报 401 错误？**
A: API Key 填错了，检查是否完整复制（以 sk-ant- 开头）

**Q: 运行报 429 错误？**
A: API 限速，把 SLEEP_MS 改大一点，比如 2000

**Q: 输入文件格式？**
A: xlsx格式，词分布在任意单元格均可，程序会自动读取所有非空单元格
