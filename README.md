# NEXUS：版本化需求、代码和测试知识平台

NEXUS 面向产品、开发和测试团队，将分散在需求文档、Git 代码和测试设计中的信息整理为一套**按项目、版本和功能组织的可追溯知识库**。

平台不再只是“需求存疑 RAG”，而是围绕软件版本持续沉淀以下知识：

- **产品知识**：功能目标、业务规则、版本变化、风险和待确认事项。
- **开发知识**：代码仓库、文件、类、方法、调用关系和 Git commit 证据。
- **测试知识**：验收条件、测试关注点、边界场景和回归范围。
- **原始证据**：需求文档位置、代码符号、版本、提交记录和引用片段。

产品、开发和测试可以通过同一个 Wiki 浏览不同版本的功能全貌，并从结论回到原始需求或代码证据。

从 0.6 起，Codex、Cursor 和其他 MCP 客户端可通过 `/mcp` Streamable HTTP 直接调用需求、
代码、源码、开发方案、Wiki 和版本差异工具。配置与安全说明见
[`docs/mcp-quickstart.md`](docs/mcp-quickstart.md)。

从 0.7 起，代码索引支持 Java、Go、Python 和 TypeScript，并构建项目/commit 隔离的静态符号调用图。
Codex 与 Cursor 可通过 `nexus_code_graph` 查询上下游调用，通过 `nexus_impact_analysis` 获取确定影响、
推测影响、未解析调用和建议回归入口。Kotlin 仅在本机 Tree-sitter 语法能力探测通过时启用。

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

- 功能概览与版本变化
- 可定位到文件、版本和内容哈希的需求来源
- 产品规则、处理流程、数据/配置影响和异常边界
- 真实文件、符号和 commit 对应的静态代码入口
- 验收标准、测试设计和真实测试执行状态
- 风险、缺失证据和质量审核状态
- 功能关系、需求证据与代码证据

页面支持草稿、需求已核验、代码已核验、全部核验、冲突、过期、缺少实现、缺少需求和已驳回等状态。

可为已配置项目整理多个历史版本。版本源定义和生成结果分别保存在：

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

生成过程会校验项目、版本和功能标识，并采用临时目录加原子替换，避免浏览到只生成了一部分的内容。新版 schema 2 以有效需求条目为功能页边界：短但明确的新增需求会保留，协调性问句会跳过，名称接近的不同条目不会自动合并。代码入口只有在目标 commit 中找到保守匹配时才会记录；没有真实测试报告时只显示“没有真实执行快照”。

目标业务版本的正式 Wiki 不再生成按仓库目录罗列的模块页。版本概览只展示需求覆盖率、代码关联率和缺失证据，具体内容进入独立需求功能页。旧 schema 1 页面继续兼容读取。

生成指定版本：

```bash
curl -X POST \
  "http://localhost:8080/api/wiki/generate?projectId=example-service&version=1.1.0"
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
- 在概览、需求、开发、测试和证据页签中阅读同一功能
- 查看需求文件、内容哈希、代码 commit、文件/符号和审核状态
- 明确区分验收标准、测试建议和真实测试执行结果
- 通过版本中心深链接定位指定项目、版本和功能

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
  -F 'version=1.1.0' \
  -F 'documentId=example-requirements'
```

生成需求存疑：

```bash
curl -X POST http://localhost:8080/api/requirements/reviews \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"example-requirements","version":"1.1.0","module":"example-module"}'
```

### 5. 代码知识

代码知识模块支持：

- 后台全量代码索引（页面无需等待长连接）
- 后台索引状态轮询与失败保留旧索引
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
POST /api/code/index/start
GET  /api/code/index/status
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

生成阶段会把本次召回结果登记为请求级证据白名单。需求与代码片段分别获得稳定的 `requirement:*` 和 `code:*` 证据编号；模型只能引用本次白名单中的编号，未知引用会被过滤，缺少引用的结论会标记为“待核实”。系统同时计算结论总数、完整支持、部分支持、未支持和覆盖率：

- 非流式响应在兼容原字段的同时追加 `citations`，包含逐条结论引用、可回查证据和质量汇总。
- SSE 方案事件追加 `evidenceIds` 与 `supportStatus`，结束事件追加 `citationQuality`，引用详情由 `references.evidence` 返回。
- 代码工作台显示“证据支持 / 部分支持 / 待核实”和整体覆盖率；需求证据可以查看受控摘录，代码证据可以跳转到源码片段。
- 证据编号只在当前检索结果中有效，不能用模型输出绕过项目、版本或来源边界。

`VersionKnowledgeBuildPipeline` 可以读取目标需求版本和基线版本的 Qdrant payload，以 `contentHash` 识别新增、修改和删除的父块，再关联候选代码证据，生成待产品、开发和测试审核的知识草稿。

```text
POST /api/assistant/development-plan
POST /api/assistant/development-plan/stream
POST /api/knowledge/build
```

构建请求示例：

```json
{
  "projectId": "example-service",
  "version": "1.1.0",
  "baseVersion": "1.0.0",
  "documentId": "example-requirements",
  "baseCodeCommit": "836abbd7...",
  "codeCommit": "f7e0e22b..."
}
```

构建结果只写入 `data/wiki-drafts/<project>/<version>/<buildId>/`：

- `build.json`：功能事实草稿、缺代码/缺测试状态、冲突和安全警告。
- `wiki-source.json`：可在人工审核后整理到正式 Wiki 源定义的草稿。
- 草稿不会自动覆盖 `data/wiki-sources/` 或 `data/wiki/`。
- 草稿不包含向量、Qdrant point、snapshot、WAL 或凭据。

### 7. 版本档案与多来源差异分析

NEXUS 使用独立于向量库的版本档案，将一个业务版本对应的需求、代码、测试和 Wiki 基线关联起来：

```text
data/version-manifests/<projectId>/<version>.json
```

每份档案只保存小型、可评审的结构化元数据，例如需求文档版本、Git commit SHA、真实测试快照、Wiki 版本和构建编号。档案不保存向量、embedding、Qdrant point、snapshot、WAL、storage 数据或凭据。

主要 API：

```text
PUT /api/versions/manifests
GET /api/versions/manifests?projectId=...
GET /api/versions/manifests/{version}?projectId=...
GET /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
```

版本比较报告分别返回：

- **需求差异**：父块新增、修改和删除，附带有限长度的原文摘录。
- **代码差异**：Git commit 之间的文件新增、修改、删除、重命名及分类统计。
- **测试差异**：档案中真实测试快照的汇总变化、用例增删和状态变化。
- **Wiki 差异**：页面增删、审核状态、摘要和证据数量变化。

每个来源都明确标记 `AVAILABLE` 或 `NOT_AVAILABLE`。单个非关键来源缺失时，报告以安全 warning 降级返回，不会把缺失数据伪装成“没有变化”。当前代码比较是可靠的**文件级差异**，尚不宣称提供 AST 或符号级影响分析；测试比较只读取真实快照，不把建议测试点当成测试执行结果。

### 8. 需求版本链与受控快照

版本档案解析会把业务版本、需求基线、代码 commit 和 Wiki 版本连接起来。没有人工保存独立档案时，系统会使用 Wiki 版本索引和 `data/requirement-snapshots/` 中的受控需求快照合成只读档案；人工档案仍具有最高优先级，缺失的需求引用可以由可信快照补齐。

需求快照保存来源文件、来源位置、生成时间、正文、顺序和 SHA-256，不保存 embedding、向量、Qdrant point、storage、snapshot、WAL 或凭据。需求文档按**增量**解释：系统沿 `baseRequirementVersion` 累计历史有效需求，当前版本没有重复出现的旧需求仍然有效；同一稳定条目再次出现时执行新增或更新，只有结构化 `REMOVE` 操作才会删除历史需求。正文中普通的“删除、取消、移除”等业务描述不会被当成删除标记。

版本差异优先比较两端累计后的完整需求视图。只有快照缺失时才回退到现有 Qdrant payload，并且兼容回退不会根据条目缺席推断删除。没有可靠材料的版本继续标记为不可用，不会伪装成“没有变化”。

历史快照可以重复生成：

```bash
python3 tools/build-requirement-snapshots.py
```

默认从仓库内的历史需求表和本地可选产品文档包提取事实，输出到：

```text
data/requirement-snapshots/<projectId>/<requirementVersion>.json
```

大型原始文档包和生成后的需求快照都只保留在本机，并由 Git 忽略；仓库只提交生成器、数据模型、配置和不含业务正文的测试夹具。需要浏览真实需求差异时，在本地运行生成器重建快照。

### 9. 版本中心与差异浏览

应用启动后访问：

```text
http://localhost:8080/versions
```

版本中心直接读取已生成的 Wiki 版本索引，不再要求历史版本先手工创建独立 manifest；因此只要版本 Wiki 已经生成，就可以选择两个版本进行比较。若存在独立版本档案，比较接口仍会优先使用其中的需求和真实测试快照。页面提供：

- Git 基线、目标 commit、Wiki 页面数量和版本时间线
- 需求、代码、测试、Wiki 四类独立差异页签
- 单一来源不可用时的降级状态和安全 warning
- 测试缺少真实执行记录时明确显示“没有真实执行快照”
- Wiki 差异回到具体项目、版本和功能页面的深链接

版本中心主要依赖：

```text
GET /api/wiki/projects
GET /api/wiki/versions?projectId=...
GET /api/versions/compare?projectId=...&fromVersion=...&toVersion=...
```

### 10. 历史版本 Wiki 实质内容构建

针对已有 Git 历史但 Wiki 只有占位页的项目，可以使用第二阶段构建器，从真实 commit 快照补齐代码结构、模块边界和版本变更证据：

```bash
python3 tools/build-version-wiki.py \
  --repo /absolute/path/to/your-repository
```

构建器会为每个版本生成版本概览、代码结构页和模块页，记录受控文件、Git 新增/修改/删除、Java 类型/方法名、配置文件和代码证据；有基线的版本还会记录两次 commit 之间的文件级差异。没有需求原文或真实测试执行记录时，页面会明确标注缺失，不把代码推断写成产品规则或测试结果。构建结果只写入 `data/wiki-sources/` 和 `data/wiki/`，不读取或写入向量库、Qdrant 数据、WAL、凭据或完整源码。

Wiki 页面也提供“版本中心”入口；从版本中心进入 Wiki 时，可以使用以下查询参数定位页面：

```text
/wiki?projectId=...&version=...&featureId=...
```

### 11. 统一知识冲突检测

需求 RAG、代码 RAG、真实测试证据和 Wiki 不再被静默拼成一个结论。NEXUS 先把可比较的信息表达为带项目、业务版本、事实键、事实值和证据标识的结构化声明，再执行确定性的冲突检测：

- 需求、代码、测试属于原始来源；Wiki 属于派生来源，不能覆盖原始证据。
- 同一项目、同一版本、同一事实键出现不同值时，按来源组合标记冲突。
- 跨项目或跨版本证据会被阻断，避免旧版本或其他项目内容污染当前回答。
- Wiki 缺少原始证据，或与原始证据不一致时，报告为阻断问题并要求重新审核。
- 第一版只比较调用方明确提供的结构化事实，不使用模型猜测两段自由文本是否语义冲突。

即时分析 API：

```text
POST /api/knowledge/conflicts/analyze
```

非流式开发方案响应会追加 `conflictReport`。当前检索接入可稳定发现项目和版本污染；需求、代码、测试与 Wiki 的业务语义冲突需要先由抽取或构建流程提供相同的稳定 `factKey`。冲突报告只提示和阻断，不会自动修改需求、代码、测试记录或 Wiki。

### 12. 监控与数据健康

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

### 3. 一键启动

```bash
./scripts/nexus.sh start
```

脚本会加载 `.env`、检查 JDK 21、启动本地 Qdrant、构建当前版本并等待 NEXUS 就绪。服务默认只监听 `127.0.0.1`。如果项目自带的 Qdrant 或 NEXUS 进程仍占用端口但接口已经失效，脚本会识别并自动恢复；如果端口属于其他程序，则只给出明确提示，不会误停外部服务。已有 Qdrant 存储不会被删除。

常用命令：

```bash
./scripts/nexus.sh status
./scripts/nexus.sh logs
./scripts/nexus.sh stop
```

启动后：

```text
平台首页：http://localhost:8080/
监控工作台：http://localhost:8080/monitor
版本化 Wiki：http://localhost:8080/wiki
版本中心：http://localhost:8080/versions
```

BGE reranker 未运行时检索会降级，但 Wiki 和版本中心仍可独立使用。代码集合首次为空是正常状态，需要在代码工作台执行一次“建立代码索引”。完整索引在后台运行，页面会持续显示运行、完成或失败状态；索引过程中和失败后，已有索引仍可继续检索。Qdrant 数据保存在本地运行目录中，不提交到 Git。

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

当前 `0.7.0-SNAPSHOT` 已具备统一证据检索、版本知识、Codex/Cursor MCP 接入、多语言代码索引与符号级影响分析基础能力，但仍需要继续完善：

1. 将需求评审的 BGE/LLM 重排也迁移到统一 RetrievalPipeline，并使用 Gold Dataset 比较质量。
2. 接入真实测试执行结果，把“建议测试点”升级为可追溯的测试证据。
3. 增加草稿在线审核、评论、拆分/合并和审批发布流程。
4. 扩充跨模块和框架动态调用适配；当前无法静态确定的调用会明确标记为 `UNRESOLVED`。
5. 将检索评测集从首批 10 条扩展到约 50 条，并持续评估版本串线和相似功能误召回。

自动构建只产生 `DRAFT / PENDING_REVIEW` 内容。未经原始需求、代码或测试结果确认的业务规则不能作为已确认事实发布。
