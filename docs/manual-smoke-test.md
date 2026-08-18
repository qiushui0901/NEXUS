# NEXUS 人工冒烟手测手册

> 目的：AI 单测只证明「代码能编译/断言通过」。本手册帮你用**真实业务问题**验证「对自己有没有用」。  
> 预计耗时：首次 1～2 小时；熟练后 20～30 分钟。  
> 相关文档：[用户指南](./user-guide.md) · [MCP 快速入门](./mcp-quickstart.md) · [生态介绍](./nexus-ecosystem.html)

---

## 你要回答的三个问题

测完后只记这三句就够：

1. **需求**：我问熟知的题，Top 结果能不能对上原文？有没有 `requirement:*`？
2. **代码**：搜熟知的类/方法，路径对不对？影响分析有没有乱扩？
3. **Agent**：Cursor 里 MCP 工具能不能调通，返回是否带 `resolved` + 证据编号？

---

## 0. 一次性准备

在项目根目录：

```bash
cd /Users/user/Documents/request-RAG

# Embedding（必做）
ollama pull bge-m3
# 若 ollama 未在跑：ollama serve

# API Key（与 .env 中 AUTH_USER_1_KEY 一致）
export NEXUS_API_KEY="$(grep '^AUTH_USER_1_KEY=' .env | cut -d= -f2-)"
echo "key length: ${#NEXUS_API_KEY}"   # 应 > 0

# 可选：默认代码仓（不配则默认当前目录 `.`）
# 在 .env 中设置，例如：
# CODE_REPOSITORY_PATH=/Users/user/Documents/request-RAG
# CODE_PROJECT_ID=default-project
```

可选重排（没有也能测，只是会降级）：

```bash
./tools/start-bge-reranker.sh
# 健康检查：curl -s http://127.0.0.1:8081/health
```

准备一份**你熟悉内容**的需求文件，例如：

```text
/tmp/nexus-smoke-req.md   # 或 .docx / .pdf
```

下文示例变量（可按需改）：

```bash
export DOC_ID="smoke-req"
export DOC_VER="smoke-v1"
export PROJECT_ID="default-project"   # 若用多项目，改成 PROJECT_1_ID
export QUERY="把这里换成你文档里一定存在的一句话或功能名"
```

---

## 1. 启动与存活（5 分钟）

```bash
./scripts/nexus.sh start
./scripts/nexus.sh status
```

浏览器打开并打勾：

| 入口 | 期望 |
|------|------|
| http://127.0.0.1:8080/ | 首页能开，依赖状态可读 |
| http://127.0.0.1:8080/knowledge | 能看到加载、空状态或真实知识库列表，不应 500 |
| http://127.0.0.1:8080/settings/gitlab | 功能启用时能看到项目列表或接入向导 |
| http://127.0.0.1:8080/monitor | 能看到 Collection / RAG 相关状态 |
| http://127.0.0.1:8080/wiki | 能开（可无业务页，不应 500） |

快捷探活：

```bash
curl -s http://127.0.0.1:8080/actuator/health
curl -s http://127.0.0.1:8080/api/runtime/status \
  -H "X-API-Key: $NEXUS_API_KEY" | python3 -m json.tool | head -80
```

失败时：

```bash
./scripts/nexus.sh logs
# 常见：AUTH_USER_1_KEY 为空 → 启动被 fail-safe 拦住
```

---

## 2. 需求链路（20～40 分钟）

### 2.1 上传

```bash
curl -s -X POST http://127.0.0.1:8080/api/requirements/documents \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -F "file=@/tmp/nexus-smoke-req.md" \
  -F "version=${DOC_VER}" \
  -F "documentId=${DOC_ID}" | python3 -m json.tool
```

### 2.2 开发方案（看引用）

```bash
curl -s -X POST http://127.0.0.1:8080/api/assistant/development-plan \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"${QUERY}\",\"documentId\":\"${DOC_ID}\",\"version\":\"${DOC_VER}\",\"projectId\":\"${PROJECT_ID}\",\"limit\":8}" \
  | tee /tmp/nexus-plan.json | python3 -m json.tool | less
```

检查清单：

- [ ] 响应不是空壳 / 不是纯 500
- [ ] 出现 `requirement:*`（有代码索引时还可能有 `code:*`）
- [ ] 引用摘录能对上你上传的原文
- [ ] 若 BGE 未启，可有 `BGE_RERANK_UNAVAILABLE`，但不应整段无证据
- [ ] 问文档里**没有**的内容时，不应一本正经编造「已确认事实」

### 2.3（可选）存疑

```bash
curl -s -X POST http://127.0.0.1:8080/api/requirements/reviews \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"documentId\":\"${DOC_ID}\",\"version\":\"${DOC_VER}\",\"projectId\":\"${PROJECT_ID}\"}" \
  | python3 -m json.tool | head -100
```

---

## 3. 代码链路（20～40 分钟）

默认 `CODE_REPOSITORY_PATH=.` 时可先对本仓库索引。

### 3.1 建索引

```bash
curl -s -X POST "http://127.0.0.1:8080/api/code/index/start?projectId=${PROJECT_ID}" \
  -H "X-API-Key: $NEXUS_API_KEY" | python3 -m json.tool

# 轮询直到完成/失败
watch -n 3 "curl -s \"http://127.0.0.1:8080/api/code/index/status?projectId=${PROJECT_ID}\" \
  -H \"X-API-Key: $NEXUS_API_KEY\" | python3 -m json.tool"
```

没有 `watch` 时手动重复 status 即可。

### 3.2 语义搜索

换成你仓库里真实存在的符号：

```bash
curl -s -X POST http://127.0.0.1:8080/api/code/search \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"AuthConfigurationValidator\",\"projectId\":\"${PROJECT_ID}\",\"limit\":5}" \
  | python3 -m json.tool
```

检查清单：

- [ ] 命中文件路径是仓库相对路径
- [ ] `language` 字段合理（若有）
- [ ] 不是完全无关文件刷屏

### 3.3 读源码

```bash
curl -s "http://127.0.0.1:8080/api/code/source?projectId=${PROJECT_ID}&filePath=src/main/java/com/example/requirementrag/config/AuthConfigurationValidator.java&startLine=1&endLine=40" \
  -H "X-API-Key: $NEXUS_API_KEY" | python3 -m json.tool
```

### 3.4 影响分析（需已建符号图）

```bash
curl -s -X POST http://127.0.0.1:8080/api/code/impact \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -H "Content-Type: application/json" \
  -d "{\"projectId\":\"${PROJECT_ID}\",\"symbol\":\"AuthConfigurationValidator\",\"depth\":2,\"limit\":30}" \
  | python3 -m json.tool
```

检查清单：

- [ ] `AVAILABLE` 或明确的 `NOT_AVAILABLE` + 原因（未索引时）
- [ ] 确定影响与 `UNRESOLVED` 分开，不把推测写成铁板事实

### 3.5 知识库管理页面

打开 `http://127.0.0.1:8080/knowledge`：

- [ ] 列表状态同时有图标和中文文本，不只依赖颜色。
- [ ] 能进入知识库文档列表，并打开一个文档详情和分块抽屉。
- [ ] 文档详情能看到阶段轨道、来源、父子块关系、哈希和索引验证状态。
- [ ] 检索测试能返回正式检索结果或明确的 `NO_RESULTS` / `DEGRADED` 状态。
- [ ] 失败请求出现就地重试；移动端长路径、commit 和错误摘要不溢出。

重启应用后再次打开页面：

- [ ] 历史任务仍可查询。
- [ ] 重启前未结束的任务显示 `INTERRUPTED`，不伪装为仍在运行。

### 3.6 GitLab 管理页面

需要提前设置 `GITLAB_INTEGRATION_ENABLED=true`、`GITLAB_UI_ENABLED=true` 和有效加密密钥。
打开 `http://127.0.0.1:8080/settings/gitlab`：

- [ ] 五步向导的连接失败不会进入下一步。
- [ ] 项目 ID、分支和 collection 在创建前完成校验。
- [ ] 创建后可观察同步任务时间线以及 `lastIndexedSha` / `targetSha`。
- [ ] 失败但存在旧 commit 时明确显示“旧索引仍可用”。
- [ ] Webhook 状态可见；Secret 轮换后只显示一次。
- [ ] PAT 和 Secret 不出现在地址栏、页面刷新后的字段或浏览器 `localStorage`。
- [ ] 直接打开 `/settings/gitlab/{projectId}`，浏览器前进/后退仍能正确切换页面。

### 3.7 0.9.1 统一前端

依次打开首页、知识库、GitLab、Wiki 和代码工作台：

- [ ] 五页顶部品牌、一级导航、项目上下文、服务状态和连接入口一致。
- [ ] 任意页面一次操作可进入其他四个模块。
- [ ] 点击“连接”可保存/清除 API Key；页面头部不再重复出现 API Key 输入框。
- [ ] 初始化失败只在内容区显示一次；通知不遮挡标题，并且不显示 HTML、堆栈或本机绝对路径。
- [ ] 首页首屏为运行总览，没有巨型 Hero 和五张模块营销卡。
- [ ] Wiki 无右上角固定“管理知识库”按钮；窄屏可以返回目录并打开证据。
- [ ] 核心导航和首页不显示版本比较入口；旧 `/versions` 地址仍可直接访问。
- [ ] 代码工作台控制区、图画布和信息栏均为浅色，首屏控制分为模式、查询和图谱选项。

使用浏览器开发者工具分别设置：

```text
1440 × 900
1280 × 720
390 × 844
```

- [ ] 三个视口均无横向滚动条或标题/通知遮挡。
- [ ] 390px 下菜单包含总览、知识库、Wiki、代码和 GitLab。
- [ ] 390px 下知识库/GitLab 使用结构化列表，不显示宽表格。
- [ ] 390px 下代码工作台只显示图谱或一个侧栏 Tab。

---

## 4. Cursor MCP（15～30 分钟）

1. 在**启动 Cursor 的同一个终端环境**里保证有 `NEXUS_API_KEY`（或系统环境已配置）。
2. 用本仓库打开 Cursor（项目内已有 `.cursor/mcp.json`）。
3. Settings → MCP → 批准 / 启用 `nexus`。
4. 在对话中试：

```text
请调用 nexus_search_requirements，query=……，documentId=smoke-req，version=smoke-v1
请调用 nexus_search_code，query=AuthConfigurationValidator
请调用 nexus_impact_analysis，symbol=……
```

检查清单：

- [ ] 能看到约 9～10 个 `nexus_*` 工具
- [ ] 返回含 `resolved`（最终用的 project/version）
- [ ] 有证据或明确 warnings / truncated
- [ ] 无 Key 时失败语义清晰（不是挂死）

排障见 [mcp-quickstart.md](./mcp-quickstart.md)。

---

## 5. 刻意找茬（10 分钟）

| # | 操作 | 期望 |
|---|------|------|
| 1 | 不带 `X-API-Key` 调受保护 API | 401 |
| 2 | 错误 Key | 401 |
| 3 | 停 BGE（8081）再跑 development-plan | 有重排降级 warning，仍可能有结果 |
| 4 | 问文档中不存在的内容 | 低覆盖 / 待核实，而不是伪造「已确认」 |
| 5 | 索引未完成就 impact | `NOT_AVAILABLE` 或等价明确提示 |

```bash
# 1) 无 Key
curl -s -o /tmp/nexus-noauth.txt -w "%{http_code}\n" \
  http://127.0.0.1:8080/api/runtime/status

# 2) 错 Key
curl -s -o /tmp/nexus-badkey.txt -w "%{http_code}\n" \
  http://127.0.0.1:8080/api/runtime/status \
  -H "X-API-Key: definitely-wrong"
```

---

## 6. 效果记录表（建议复制到笔记）

准备 5～10 道**你已知答案**的题：

| 题号 | 问题 | 期望出处（文件/段落） | 实际 Top 命中 | 引用是否正确 | 备注 |
|------|------|----------------------|---------------|--------------|------|
| 1 | | | | 是 / 否 | |
| 2 | | | | 是 / 否 | |
| 3 | | | | 是 / 否 | |

粗判：

| 信号 | 含义 |
|------|------|
| 需求题 Top3 命中已知段落过半 | 检索勉强可用 |
| 方案里引用能对回原文 | 证据链可用 |
| 影响分析对熟方法不乱扩 | 代码智能可用 |
| 经常串版本 / 一本正经瞎编 | 先别推广，记案例再修 |

---

## 7. 今日最小路径（没空就跑这个）

```text
1. ./scripts/nexus.sh start → 打开首页 + monitor
2. 上传 1 份熟需求 → development-plan → 盯引用
3. Cursor 批准 nexus MCP → 口头问 2～3 个真问题
```

---

## 8. 和 AI 自动化测试的分工

| 谁 | 证明什么 | 证明不了什么 |
|----|----------|--------------|
| `./mvnw verify` | 回归、契约、权限、降级逻辑 | 你的业务问答好不好用 |
| 本手册 | 真实文档/仓库上的体感与证据质量 | 全量边界与 CI 门禁 |
| 评测集 / shiguang-eval | 可重复的召回指标 | 替代你对业务正确性的判断 |

停服务：

```bash
./scripts/nexus.sh stop
```
