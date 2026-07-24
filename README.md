# NEXUS：版本化需求、代码和测试知识平台

NEXUS 面向产品、开发和测试团队，将分散在需求文档、Git 代码和测试设计中的信息整理为一套**按项目、版本和功能组织的可追溯知识库**。

平台不再只是“需求存疑 RAG”，而是围绕软件版本持续沉淀以下知识：

- **产品知识**：功能目标、业务规则、版本变化、风险和待确认事项。
- **开发知识**：代码仓库、文件、类、方法、调用关系和 Git commit 证据。
- **测试知识**：验收条件、测试关注点、边界场景和回归范围。
- **原始证据**：需求文档位置、代码符号、版本、提交记录和引用片段。

产品、开发和测试可以通过同一个 Wiki 浏览不同版本的功能全貌，并从结论回到原始需求或代码证据。

## 项目目标

```text
原始需求文档 ─┐
历史版本资料 ─┼─→ 版本化事实层 ─→ Wiki 生成器 ─→ 产品 / 开发 / 测试知识页面
Git 代码仓库 ─┤                         │
测试关注点 ───┘                         └─→ JSON API / Markdown / 浏览页面

需求与代码索引 ─→ RetrievalPipeline ─→ 检索、存疑、开发方案和证据回查
```

NEXUS 的目标是建立一套“版本化的功能真相”：

1. 每项知识都明确属于哪个项目、哪个版本和哪个功能。
2. 产品规则、代码实现和测试内容可以相互对应。
3. 结论必须保留来源，不把模型生成内容当作无依据的事实。
4. 不同版本可以独立浏览，避免使用新版本内容回答旧版本问题。
5. 名称接近但实际不同的功能使用独立 `featureId`，防止知识串用。

## 当前能力

### 1. 版本化知识模型

Wiki 页面使用以下稳定主键：

```text
projectId + version + featureId
```

每个功能页面可以包含：

- 功能概览
- 产品规则
- 开发实现
- 测试点与验收条件
- 风险和存疑
- 功能关系
- 需求证据与代码证据
- 审核状态和更新时间

页面支持草稿、需求已核验、代码已核验、全部核验、冲突、过期、缺少实现、缺少需求和已驳回等状态。

当前已经整理 `immortal-game-service` 从 **0.1 到 5.1 的 64 个历史版本**。版本源定义和生成结果分别保存在：

```text
data/wiki-sources/                     版本化结构化事实源
data/wiki/<projectId>/<version>/       生成后的 JSON、Markdown 和版本索引
```

### 2. Wiki 生成器

Wiki 生成器把版本化 JSON 源转换为：

```text
index.json                 版本和页面索引
pages/<featureId>.json     浏览页面使用的机器可读数据
pages/<featureId>.md       适合阅读、评审和 Git 管理的 Markdown
```

生成过程会校验项目、版本和功能标识，并采用临时目录加原子替换，避免浏览到只生成了一部分的内容。

生成指定版本：

```bash
curl -X POST \
  "http://localhost:8080/api/wiki/generate?projectId=immortal-game-service&version=5.1"
```

### 3. 知识库浏览页面

应用启动后访问：

```text
http://localhost:8080/wiki
```

页面支持：

- 选择项目和历史版本
- 搜索功能页面
- 按状态和分类筛选
- 分别查看产品、开发、测试和证据视图
- 查看关联功能、代码 commit、需求来源和审核状态

Wiki 浏览不依赖 Qdrant、Ollama 或 BGE 服务，已有知识文件可以独立读取。

主要 API：

```text
GET  /api/wiki/projects
GET  /api/wiki/versions?projectId=...
GET  /api/wiki/index?projectId=...&version=...
GET  /api/wiki/page?projectId=...&version=...&featureId=...
POST /api/wiki/generate?projectId=...&version=...
```

### 4. 需求知识与存疑分析

系统支持上传 Tika 可解析的 PDF、DOC/DOCX、PPT/PPTX、HTML、TXT 和 Markdown，并执行：

```text
文档解析
  → 文本降噪
  → Parent / Child 分块
  → SHA-256 内容去重
  → Dense + Sparse Hybrid Search
  → BGE reranker
  → Parent 回填和证据扩展
  → LLM reranker
  → 存疑生成和全文答案回查
```

需求数据按 `documentId + version` 隔离。评审指定版本时，仅允许该版本及明确授权的历史证据参与回答。

上传需求文档：

```bash
curl -X POST http://localhost:8080/api/requirements/documents \
  -F 'file=@产品需求.docx' \
  -F 'version=5.1' \
  -F 'documentId=fengshen'
```

生成需求存疑：

```bash
curl -X POST http://localhost:8080/api/requirements/reviews \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"fengshen","version":"5.1","module":"同盟"}'
```

### 5. 代码知识

代码知识模块支持：

- 全量代码索引
- 基于 Git 变更的增量索引
- 代码语义检索
- 文件、类、方法和符号定位
- 调用关系图
- 源码证据片段读取
- GitLab Push Webhook 自动触发增量更新
- 多项目和同组项目联合检索

主要 API：

```text
POST /api/code/index
POST /api/code/incremental-index
POST /api/code/search
POST /api/code/graph
GET  /api/code/status
GET  /api/code/source
POST /api/search/cross-project
POST /api/webhooks/gitlab
```

### 6. 开发方案、统一检索与版本知识草稿

需求检索和代码检索通过统一 `RetrievalPipeline` 完成项目路由、双源召回、证据去重、数量控制、阶段诊断和降级处理。同步开发方案与 SSE 流式开发方案使用同一条证据管线，不再各自维护检索逻辑。

`VersionKnowledgeBuildPipeline` 可以读取目标需求版本和基线版本的 Qdrant payload，以 `contentHash` 识别新增、修改和删除的父块，再关联候选代码证据，生成待产品、开发和测试审核的知识草稿。

```text
POST /api/assistant/development-plan
POST /api/assistant/development-plan/stream
POST /api/knowledge/build
```

构建请求示例：

```json
{
  "projectId": "immortal-game-service",
  "version": "5.1",
  "baseVersion": "5.0.2",
  "documentId": "fengshen",
  "baseCodeCommit": "836abbd7...",
  "codeCommit": "f7e0e22b..."
}
```

构建结果只写入 `data/wiki-drafts/<project>/<version>/<buildId>/`：

- `build.json`：功能事实草稿、缺代码/缺测试状态、冲突和安全警告。
- `wiki-source.json`：可在人工审核后整理到正式 Wiki 源定义的草稿。
- 草稿不会自动覆盖 `data/wiki-sources/` 或 `data/wiki/`。
- 草稿不包含向量、Qdrant point、snapshot、WAL 或凭据。

### 7. 监控与数据健康

访问监控工作台：

```text
http://localhost:8080/monitor
```

监控内容包括：

- 需求和代码 Collection 状态
- 索引与知识初始化状态
- RAG 各阶段运行状态
- 检索预览和健康历史
- Spring Boot Actuator、Prometheus 和 OpenTelemetry 指标

常用端点：

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/metrics
GET /actuator/prometheus
GET /api/monitor/status
GET /api/monitor/rag-chain
```

## 技术架构

- Java 21
- Spring Boot 4.1
- Spring AI 2.0
- Qdrant Dense/Sparse 向量检索
- BGE Reranker
- Ollama 本地 Embedding
- OpenAI 兼容 Chat 模型网关
- Markdown + JSON 版本化 Wiki
- Maven、Docker Compose、Prometheus、OpenTelemetry

## 本地启动

### 1. 环境要求

- JDK 21
- Maven 3.9+
- Docker（用于 Qdrant）
- Ollama（默认用于本地 Embedding）
- 提供 `/rerank` 接口的 BGE reranker 服务

如果电脑还需要保留其他 Java 版本，可以只为当前命令指定 JDK 21：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw package -DskipTests
```

### 2. 配置本地环境

```bash
cp .env.example .env
```

在 `.env` 中填写本机服务地址和 Token。`.env` 仅供本地使用，不得提交到 GitHub。

首次使用 Ollama Embedding：

```bash
ollama pull bge-m3
```

### 3. 启动 Qdrant

```bash
./scripts/start-qdrant.sh
```

Qdrant 数据保存在 Docker 命名卷或本地运行目录中，不提交到 Git。执行 `docker compose down -v` 会删除对应命名卷，请谨慎使用。

### 4. 启动应用

```bash
./mvnw spring-boot:run
```

启动后：

```text
监控工作台：http://localhost:8080/monitor
版本化 Wiki：http://localhost:8080/wiki
```

## 数据与版本管理原则

应提交到 Git：

- 业务代码和测试代码
- 小型需求结构化数据
- Wiki JSON/Markdown
- 版本索引和审核状态
- 配置模板和项目文档

不得提交到 Git：

- `.env` 和真实凭据
- Qdrant 数据目录、Collection、Snapshot 和 WAL
- Dense/Sparse 向量数据
- 本地模型和二进制文件
- Maven 缓存、IDE 个人状态和临时工作区
- 大型原始 ZIP 文档

## 当前阶段边界

当前版本已经具备统一证据检索、版本增量知识草稿、版本化 Wiki 生成和浏览基础能力，但仍需要继续完善：

1. 将需求评审的 BGE/LLM 重排也迁移到统一 RetrievalPipeline，并使用 Gold Dataset 比较质量。
2. 接入真实测试执行结果，把“建议测试点”升级为可追溯的测试证据。
3. 增加草稿在线审核、评论、拆分/合并和审批发布流程。
4. 增加代码 commit 差异、影响分析和回归范围推荐。
5. 将检索评测集从首批 10 条扩展到约 50 条，并持续评估版本串线和相似功能误召回。

自动构建只产生 `DRAFT / PENDING_REVIEW` 内容。未经原始需求、代码或测试结果确认的业务规则不能作为已确认事实发布。
