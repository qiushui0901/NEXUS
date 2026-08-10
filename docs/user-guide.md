# NEXUS 用户指南

运行与使用说明。产品定位与架构概览见 [根目录 README](../README.md)。

---

## 目录

1. [环境要求](#1-环境要求)
2. [配置](#2-配置)
3. [启动服务](#3-启动服务)
4. [浏览器入口](#4-浏览器入口)
5. [需求摄入与存疑](#5-需求摄入与存疑)
6. [代码索引与智能分析](#6-代码索引与智能分析)
7. [开发方案与引用](#7-开发方案与引用)
8. [Wiki 与版本知识](#8-wiki-与版本知识)
9. [版本对比](#9-版本对比)
10. [冲突检测与监控](#10-冲突检测与监控)
11. [MCP 客户端](#11-mcp-客户端)
12. [数据与 Git 边界](#12-数据与-git-边界)
13. [当前限制](#13-当前限制)

上手验证效果请优先看：[人工冒烟手测手册](./manual-smoke-test.md)。

---

## 1. 环境要求

- JDK 21
- Maven 3.9+（或使用 `./mvnw`）
- Docker（Qdrant；可选完整 Compose 栈）
- Ollama 及 Embedding 模型（默认 `bge-m3`）
- 可选：提供 `/rerank` 的 BGE 重排服务（缺失时检索会安全降级）

若本机有多套 JDK，可只为当前命令指定 21：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw package -DskipTests
```

---

## 2. 配置

```bash
cp .env.example .env
```

在 `.env` 中填写服务地址与 Token。**不要**把 `.env` 提交到 Git。

首次拉取 Embedding 模型：

```bash
ollama pull bge-m3
```

多项目通过 `app.rag.projects` / `PROJECT_N_*` 环境变量注册（见 `application.yml`）。每个项目应有独立的需求 / 代码 Collection，以及 NEXUS 运行机能解析到的 `repository-path`。

---

## 3. 启动服务

### 本地脚本

```bash
./scripts/nexus.sh start
./scripts/nexus.sh status
./scripts/nexus.sh logs
./scripts/nexus.sh stop
```

脚本会加载 `.env`、检查 JDK 21、启动本地 Qdrant、构建应用并等待就绪。不会误停占用同端口的无关进程。

### Docker Compose

```bash
# 在环境中设置 AUTH_USER_1_KEY 等密钥
docker compose up --build
```

Compose 会拉起 NEXUS、Qdrant、Prometheus、Grafana。请将仓库挂载或同步到配置的 `CODE_REPOSITORY_PATH`（示例默认卷为 `/workspace/repository`）。样例里 Embedding / 重排常指向 `host.docker.internal`，请按实际网络调整。

---

## 4. 浏览器入口

| 页面 | 地址 |
|------|------|
| 首页 / 运行时状态 | http://localhost:8080/ |
| Wiki | http://localhost:8080/wiki |
| 版本中心 | http://localhost:8080/versions |
| 监控 | http://localhost:8080/monitor |

已有生成文件时，Wiki 浏览可不依赖 Qdrant / Ollama / BGE。代码检索与 LLM 方案需要相应依赖（或进入显式降级）。

深链接示例：

```text
/wiki?projectId=...&version=...&featureId=...
```

---

## 5. 需求摄入与存疑

流水线：

```text
解析（Tika）→ 降噪 → Parent/Child 分块 → SHA-256 去重
  → Dense + Sparse 混合检索 → 可选 BGE / LLM 重排
  → 带版本隔离的存疑生成
```

上传：

```bash
curl -X POST http://localhost:8080/api/requirements/documents \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -F 'file=@requirements.docx' \
  -F 'version=1.1.0' \
  -F 'documentId=example-requirements'
```

存疑评审：

```bash
curl -X POST http://localhost:8080/api/requirements/reviews \
  -H "X-API-Key: $NEXUS_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"example-requirements","version":"1.1.0","module":"example-module"}'
```

需求证据按 `documentId + version` 隔离。评审不得擅自拉取未授权的其他版本。

---

## 6. 代码索引与智能分析

### 心智模型

NEXUS 索引的是**服务端仓库路径**。开发仍在本机克隆中改代码（例如 multipow 工作区）。MCP / REST 代码相关接口为**只读**。详见 [multipow × NEXUS — 第 3 节](multipow-nexus-integration.md#3-代码怎么处理核心)。

0.7 支持语言：Java、Go、Python、TypeScript。Kotlin 仅在 Tree-sitter 能力探测通过时启用。

### 索引

```bash
# 前台全量索引
curl -X POST "http://localhost:8080/api/code/index?projectId=example-service" \
  -H "X-API-Key: $NEXUS_API_KEY"

# 后台任务
curl -X POST "http://localhost:8080/api/code/index/start?projectId=example-service" \
  -H "X-API-Key: $NEXUS_API_KEY"

curl "http://localhost:8080/api/code/index/status?projectId=example-service" \
  -H "X-API-Key: $NEXUS_API_KEY"
```

增量（Git 区间或 Webhook）：

```text
POST /api/code/incremental-index?projectId=...&oldSha=...&newSha=...
POST /api/webhooks/gitlab
```

全量索引会写入：

- Qdrant chunk（语义检索）
- SQLite 符号 / 调用图（影响分析，按项目 + commit 隔离）

### 查询 API

```text
POST /api/code/search
POST /api/code/graph              # 旧版语义展示图
POST /api/code/graph/symbols      # 持久化静态符号图
POST /api/code/impact             # symbol 与 fromCommit+toCommit 二选一
GET  /api/code/source
GET  /api/code/status
POST /api/search/cross-project
```

影响分析置信度：

- **确定：** 仅 `EXACT` / `SAME_FILE`  
- **推测：** `HEURISTIC`  
- **未解析：** 动态 / 歧义调用 —— 不计入确定影响  
- 目标 commit 无图 → `NOT_AVAILABLE` + 文件级降级  

首次启动代码 Collection 为空是正常现象，需先跑一次索引。全量索引失败时保留旧索引。

---

## 7. 开发方案与引用

```text
POST /api/assistant/development-plan
POST /api/assistant/development-plan/stream
```

两条路径均走 `RetrievalPipeline`。每次请求建立证据白名单（`requirement:*`、`code:*`）。未知引用会被过滤；缺少支持的结论标为待核实。响应含引用质量，以及可选的 `conflictReport`。

---

## 8. Wiki 与版本知识

页面稳定主键：

```text
projectId + version + featureId
```

路径：

```text
data/wiki-sources/                 结构化源
data/wiki/<projectId>/<version>/   生成后的索引与页面
data/wiki-drafts/...               可审核构建（绝不自动发布）
```

生成：

```bash
curl -X POST \
  "http://localhost:8080/api/wiki/generate?projectId=example-service&version=1.1.0" \
  -H "X-API-Key: $NEXUS_API_KEY"
```

```text
GET  /api/wiki/projects
GET  /api/wiki/versions?projectId=...
GET  /api/wiki/index?projectId=...&version=...
GET  /api/wiki/page?projectId=...&version=...&featureId=...
POST /api/knowledge/build
```

知识构建会对比需求版本（contentHash）、关联候选代码证据，并把草稿写入 `data/wiki-drafts/`。只有已批准草稿可通过 `/api/knowledge/drafts/...` 生命周期接口发布到正式 Wiki 源。

历史代码向 Wiki 回填（可选工具）：

```bash
python3 tools/build-version-wiki.py --repo /absolute/path/to/your-repository
```

需求快照（本机、业务正文默认被 Git 忽略）：

```bash
python3 tools/build-requirement-snapshots.py
```

---

## 9. 版本对比

```text
PUT  /api/versions/manifests
GET  /api/versions/manifests?projectId=...
GET  /api/versions/manifests/{version}?projectId=...
GET  /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
```

版本中心结合 Wiki 索引与可选档案，展示需求 / 代码 / 测试 / Wiki 差异。每个来源标记为 `AVAILABLE` 或 `NOT_AVAILABLE`。缺失数据不得渲染成「无变化」。

---

## 10. 冲突检测与监控

```text
POST /api/knowledge/conflicts/analyze
```

监控：

```text
http://localhost:8080/monitor

GET /actuator/health
GET /actuator/prometheus
GET /api/monitor/status
GET /api/monitor/rag-chain
GET /api/runtime/status
```

---

## 11. MCP 客户端

端点：`http://localhost:8080/mcp`（或经反代后的地址）。

启用认证时需携带 `X-API-Key`：

```bash
export NEXUS_API_KEY='...'
export NEXUS_MCP_URL='http://127.0.0.1:8080/mcp'
```

Cursor / Codex / stdio 桥接完整说明见 [mcp-quickstart.md](mcp-quickstart.md)。

0.8 提供十个工具：`nexus_search_requirements`、`nexus_search_code`、`nexus_get_source`、`nexus_development_plan`、`nexus_wiki_page`、`nexus_version_diff`、`nexus_code_graph`、`nexus_impact_analysis`、`nexus_review_doubts`、`nexus_conflict_check`；同时提供实现需求、评审需求、评估改动影响三个 Prompt。

---

## 12. 数据与 Git 边界

**应提交**

- 应用与测试代码
- 小型结构化夹具
- 打算在 Git 中评审的 Wiki JSON / Markdown
- 配置模板与文档

**不要提交**

- `.env` 与真实凭据
- Qdrant 存储、快照、WAL
- 向量 / 本地模型
- 大型原始文档包 / 私有需求快照（`data/requirement-snapshots/` 已被忽略）

认证：非本地部署应保持 `AUTH_ENABLED=true` 且 Key 非空（配置错误时启动失败关闭）。

---

## 13. 检索状态与降级语义

所有检索入口（REST / SSE / MCP）返回统一四态：

- `SUCCESS`：正常命中；
- `NO_RESULTS`：真正零命中（语料存在但无匹配），HTTP 2xx；
- `DEGRADED`：非关键依赖（BGE/LLM 重排等）失败但保留可用候选，附带 warnings；
- `FAILED`：核心依赖失败且无可用证据，HTTP 503。

每次响应携带 warnings（stage + code + message + durationMs）；稳定 warning code
注册表见 [retrieval-status-contract.md](retrieval-status-contract.md)。大文档正文
超出上下文预算时按模块轮转保留代表块并输出 `CONTEXT_TRUNCATED` 警告（省略块数、
覆盖模块数），不再静默丢弃后部模块。

## 14. 当前限制

细节见 [nexus-improvement-roadmap.md](nexus-improvement-roadmap.md)。摘要：

1. 服务端仍需要文件系统（或卷）上的仓库路径，尚不支持「只填 Git URL」的自助同步。  
2. 重排质量与评测门禁仍在完善（更大黄金集、CI 质量阈值）。  
3. 真实 CI 测试结果接入未完成；缺失时界面显示「没有真实执行快照」。  
4. 静态影响无法解析全部动态分派；未解析边会显式保留。  
5. multipow clone ≠ 自动进入 NEXUS 索引；需对齐 `projectId` 与已索引 commit。

草稿 / 待审核内容在批准发布前，不得视为已确认的产品真相。
