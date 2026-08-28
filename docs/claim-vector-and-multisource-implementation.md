# 需求文档 × 数值表 × 多源知识：存入与召回实现说明

> 范围：`knowledge/multisource/**`（统一知识目录 + 多源检索）、`knowledge/multisource/vector/**`（0.9.6 Claim 向量投影）、
> `requirement/semantic|graph/**`（语义标注 / 需求图）。
> 本文用流程图讲清楚：需求文档、数值表（参数表）、测试用例、存疑等**不同来源的知识如何被统一表达、存储、向量化、并在检索时结合**。

---

## 0. 一图总览：双层架构

本系统是 **“SQLite 权威 + Qdrant 可弃投影”** 的双层结构：

```mermaid
flowchart TB
    subgraph 存入翼["存入（写入侧）"]
        A[导入编排<br/>ImmortalKnowledgeImporter] --> B[统一知识目录<br/>document → version → evidence]
        A --> C[统一 Claim 主表（SQLite）]
        A --> D[业务表<br/>参数/测试/存疑]
        C --> E[语义块向量投影 0.9.6<br/>composer→可选 gpt-5.6-luna→embedding→Qdrant]
        E --> F[(Qdrant<br/>语义块投影)]
    end

    subgraph 召回翼["召回（读取侧）"]
        G[查询] --> H[MultiSourceSearchService<br/>意图→来源→候选→融合→评分]
        H --> D
        H --> C
        H --> F
        H --> I[(SQLite 命中水化<br/>治理字段回读)]
    end

    B -.发布激活.-> C
```

核心原则：

- **SQLite 是权威**：目录、证据、Claim、代际行都落 `multi-source-knowledge.db`，删了还能重建；
- **Qdrant 是可弃投影**：嵌入向量只做近似召回，治理字段（状态、证据位置、生效区间）命中后从 SQLite 回读；
- **检索侧零写入**：查询只读，关系/冲突在构建期预生成，影子评测只记录不影响主路径。

---

## 1. 统一数据模型：一切知识都收敛成 Claim

不同来源（需求文档、参数表、测试用例、存疑）最终都表达为**统一三元组主张**：

```mermaid
flowchart TD
    DOC[文档<br/>项目 · 来源类型 · 权威级别] --> VER[文档版本<br/>业务版本 · 状态 DRAFT→PUBLISHED]
    VER --> EV[证据<br/>位置 + 原文片段，不可变]
    VER --> CL[统一 Claim<br/>三元组主张 · 事实键 · 数值/单位]
    CL --> CLEV[Claim-证据关联<br/>回源证据链]
    ACT[激活版本<br/>项目+业务版本 → 绑定唯一文档版本] --> VER
    CL --> GEN[向量代际<br/>BUILDING→…→ACTIVE/RETIRED]
    GEN --> INPUT[代际输入集合<br/>记录投影了哪些 Claim]
```

关键点：

| 概念 | 作用 |
|---|---|
| `subject / predicate / object_value` | 三元组：“模块—参数—值”/“#需求—必须支持—30秒” |
| **`object_value + value_type + unit`** | **数值的表达**：值为客体，类型与单位结构化可算 |
| `fact_key` | 事实寻址键（`KnowledgeFactKeyGenerator` 生成）；冲突检测按它分组；向量文本组合也把 module 从它提取 |
| `knowledge_active_version` | `(project_id, business_version)` 唯一绑定一个激活文档版本——**一次投影一个文档**（见 §6 限制） |
| `content_hash` | 导入缓存：同内容文件跳过，幂等重导 |

---

## 2. 存入链路 A：导入编排——四类输入汇入统一目录

`ImmortalKnowledgeImporter.importAll(projectId, businessVersion, root)` 按目录批量导入：

```mermaid
flowchart TD
    R[根目录 root] --> C1[immortal-case/*.xlsx<br/>测试用例]
    R --> C2[immortal-data/*.xlsx<br/>数值参数表]
    R --> C3[immortal-qa/*.xlsx<br/>存疑]
    R --> C4[immortal-prd-test/封神/*.html<br/>需求 PRD]

    C1 --> P1[测试用例解析] --> S1[写测试用例表]
    C2 --> P2[参数表解析] --> S2[写参数表]
    C3 --> P3[存疑解析] --> S3[写存疑表]
    C4 --> P4[PRD 导入] --> S4[知识图/语义抽取]

    P1 --> REG{注册文档+版本<br/>内容哈希缓存判重}
    C2 --> REG
    C3 --> REG
    REG -->|缓存命中| SKIP[跳过，幂等]
    REG -->|新内容| EV[注册目录（文档/版本）]
    EV --> SAVE[解析子项<br/>写业务表<br/>写证据: 每条一位置+片段]
    SAVE --> SYNC[归一化到统一 Claim<br/>+ 证据关联]
    SYNC --> DONE[(SQLite 权威库)]
```

要点：

- **每类文件 = 一个 `document_version`**；`registerIfAbsent` 按内容 hash 判重，重导不产生重复；
- 业务表（`multi_source_parameter/doubt/test_case/test_result`）保留**结构化增强字段**（数值的 min/max/unit、测试的步骤/预期），
  同时 `syncClaims` 把它们**归一化成统一 claim** 挂到 `knowledge_claim`——这就是“多源同表、结构保留”的结合基础；
- 每条 claim 都有 `evidence_location + excerpt_hash`，可回溯到原始文件的具体行/sheet。

---

## 3. 存入链路 B：数值表是怎么变成“数”的

以参数表（xlsx）为例，看“5 分钟 / 30秒 / 0.8~1.2”这类数值如何被结构化、再被语义化：

```mermaid
flowchart LR
    X[xlsx 参数表<br/>模块|参数|值|单位|版本] --> XR[读取表格]
    XR --> H[解析表头<br/>列角色映射]
    H --> ROW[逐行解析]
    ROW --> PS[结构化参数 Claim<br/>值/归一化值/min/max<br/>单位/精度/边界/类型/事实键]
    PS --> PT[(参数表<br/>按项目+版本全量召回)]
    PS --> SYN[归一化到统一 Claim<br/>主体=参数名 · 客体=数值<br/>类型/单位结构化]
    SYN --> EV[证据<br/>值 + 位置 sheet/行号]

    SYN --> COMP[文本组合器<br/>类型化渲染]
    COMP --> TXT[类型化检索文本<br/>参数名/用途/类型/单位/值/范围<br/>固定字段顺序，不含原始行号]
    TXT --> EMB[嵌入 text-embedding-v4<br/>via ai-gateway.momo.com]
    EMB --> Q[(Qdrant 语义块投影<br/>可选 gpt-5.6-luna 增强)]
```

**数值的三个层次**：

| 层 | 载体 | 用途 |
|---|---|---|
| 结构化数值 | `multi_source_parameter`（min/max/unit/precision/边界含否） | 精确数值问答、校验、参数对比 |
| 统一 Claim | `knowledge_claim.object_value + value_type + unit` | 跨源冲突检测（同 fact_key 数值不一致 → CONFLICTED） |
| 语义化向量 | composer 渲染的类型化文本 → 嵌入 → Qdrant 点 | 语义召回（问“权限撤销大概多久生效”） |

> 注意：**值-only 参数**（只有 Name 没 Purpose/Unit/ValueType）不建点——避免“孤立数值”主导嵌入文本。

---

## 4. 存入链路 C：需求文档的三条加工路径

需求文档（PRD HTML）不止进统一目录，还有三条独立加工线，产物都作为**检索候选**接入多源检索：

```mermaid
flowchart TB
    PRD[需求文档 html] --> BASE[基础抽取<br/>目录+evidence+requirement claim]
    BASE --> SEM[语义标注路径<br/>RequirementSemanticAnnotationService]
    BASE --> GR[需求图路径<br/>RequirementGraphBuildService+document/]
    BASE --> DRAFT[版本草稿路径<br/>VersionKnowledgeBuildPipeline]

    SEM --> SEMO[LLM 结构化标注<br/>entities/conditions/numericFacts/claims<br/>Validator 十项校验]
    SEM --> SEMS[(SQLite 语义标注<br/>代际化: 仅成功代际激活)]
    SEMO -->|候选| SEARCH

    GR --> GRO[窗口化抽取<br/>LogicalUnitPlanner→LocalEntityExtractor<br/>→CrossWindowVerifier/Integrator]
    GRO --> GRS[(需求图 store)]
    GRO -->|候选| SEARCH

    DRAFT --> DRAFTO[增量差异→草稿状态机<br/>build.json/wiki-source.json]
    DRAFTO -->|人工审核后发布| CATALOG[统一目录/claim 发布]
    CATALOG --> SEARCH

    SEARCH[MultiSourceSearchService 多源检索]
```

- **语义标注**：`REQUIREMENT_SEMANTIC` 来源，结果默认只是候选（Authority ≤ SECONDARY、EXTRACTED 状态），未经人工审核不成为确认事实；
- **需求图**：`REQUIREMENT` 来源，窗口化 + 跨窗验证，persist 证据与不确定/冲突声明；
- **草稿**：永不自动上线，差异改动人审后才发布为正式 claim。

---

## 5. 存入链路 D：Claim 向量投影（0.9.6）——如何把“结合后的知识”整体打成向量索引

投影的输入就是统一 `knowledge_claim`（需求+参数+测试+存疑四类），一次构建 = 一个代际 = 一批 Qdrant 物理集合 + 一个 alias。

### 5.1 构建流水线（两遍流式）

```mermaid
flowchart TD
    A[开始构建 项目+业务版本] --> B[第 1 遍流式读已发布 Claims<br/>分页 500/页]
    B --> C[来源过滤 + 组合检索文本]
    C --> INPUT[轻量输入集合<br/>claim / 文档版本 / 文本哈希]
    INPUT --> FP[计算输入指纹<br/>SHA-256]
    FP --> REUSE{同指纹已有<br/>已激活代际?}
    REUSE -->|是| SKIP[跳过重建，返回线上代际]
    REUSE -->|否| DEL[清理同指纹失败残留<br/>避免唯一约束卡重试]
    DEL --> START[落库为构建中<br/>manifest + 输入集合 单事务]
    START --> P2[第 2 遍流式逐页组合文本]
    P2 --> ALIGN{逐项对齐校验<br/>防等量替换漂移}
    ALIGN -->|漂移| FAIL1[标记失败]
    ALIGN -->|一致| CHUNK[分块嵌入<br/>64/块 · 走网关]
    CHUNK --> WRITE[惰性建集合<br/>逐块写入物理集合]
    WRITE --> VERIFY[点数校验]
    VERIFY --> SUCC[标记成功]
    SUCC --> ALIAS[切换 alias 指向新集合<br/>原子替换 · 保留旧集合窗口]
    ALIAS --> MARK[激活代际 单事务<br/>退役旧激活]
    MARK --> PRUNE[裁剪超期退役代际]
    PRUNE --> ACTIVE[发布完成]
```

生产路径在组合文本前先按 `sourceType + canonical_module` 聚合同一玩法的多版本页、
子页、优化页和支撑表；一个玩法/系统在实体层只建立一张玩法卡，原始 Claim、数值、版本和 Evidence 逐条保留。
Qdrant 语义块仅在组合文本超过 `block-max-chars` 时按稳定顺序切分，切分只改变召回粒度，命中后仍批量水化全部原始事实。

### 5.2 代际状态机与失败保护

```mermaid
stateDiagram-v2
    [*] --> 构建中: 开始构建
    构建中 --> 校验中: 写入 + 点数校验通过
    校验中 --> 成功: 标记成功
    成功 --> 激活: 权威提交（在最后）
    成功 --> 失败: alias 切换失败
    构建中 --> 失败: 嵌入/写入/漂移失败
    校验中 --> 失败: 点数不符
    激活 --> 已退役: 新代际激活时退役
    已退役 --> 激活: 回滚
```

关键失败保护（历次 review 固化）：

- **alias 三态语义**：查询失败（未知）≠ 确认不存在——未知时保留集合不动 alias，留人工对账（防悬空 alias）；
- **markActive 失败**：代际强制 FAILED，绝不残留 `SUCCESS + physical_collection=null` 被 `findReusableGeneration` 误复用；
- **同指纹重试**：先 `deleteSupersededGenerations` 清失败残留，`unique(scope+fingerprint+...)` 不阻塞重试；
- **回滚目标校验**：`collectionExists` 确认目标物理集合还在 retain 窗口内，否则明确拒绝，不把 alias 指向已删集合；
- **并发锁**：build / rollback / rollback-to 共用同 scope 条带锁，杜绝 SQLite/Qdrant 分叉。

### 5.3 文本组合器：不同类型“各说各话”，但格式稳定

| 来源 | 渲染字段（固定顺序，空字段跳过） |
|---|---|
| Requirement | `[Requirement]` Subject / Predicate / Value / Module / Fact key |
| Parameter | `[Parameter]` Name / Purpose / Value type / Unit / Value / Scope(Version) / Fact key |
| Test Case | `[Test Case]` Title / Preconditions / Expected result / Module / Fact key |
| Doubt | `[Doubt]` Question / Answer / Module / Fact key |

同一 claim 的文本不随行序/填充漂移（确定性），指纹才能命中复用；`textHash=SHA-256` 进入 manifest 与 Qdrant payload，构建侧与检索侧校验一致。

---

## 6. 召回链路：多源检索怎么把“结合后的知识”拿出来

单入口 `MultiSourceSearchService.search(projectId, version, query, intent?, limit, page)`：

```mermaid
flowchart TD
    Q[查询] --> G{项目开关已启用?}
    G -->|关| W1[返回空 + 禁用告警]
    G -->|开| I[意图分类<br/>规则优先 + 兜底时 LLM 回退]
    I --> FILTER[意图→来源白名单<br/>DOUBT 只查存疑/需求/测试<br/>其余含向量+语义来源]
    FILTER --> LOAD[加载候选<br/>结构化全量: 参数/测试/存疑<br/>适配器: 需求图/语义/代码<br/>单源失败→告警不拖垮]
    LOAD --> FC{向量融合路径?}
    FC -->|是| V[向量候选一次<br/>嵌入→检索→权威回填<br/>融合: 向量/词法/策略/精确加权]
    FC -->|否| GATE
    V --> GATE[状态门禁统一过滤]
    GATE --> CONFLICT[冲突分析: 按事实键分组<br/>全候选集口径，翻页不变]
    CONFLICT --> SCORE[评分: 融合分优先<br/>否则字段加权词法分<br/>稳定排序]
    SCORE --> PAGE[分页 ≤50/页<br/>续页 + 页外冲突提示]
    PAGE --> R[响应<br/>命中/证据/冲突/存疑<br/>+ 告警/关系/代际快照]
```

### 6.1 数值类问题的两路召回

问“**权限撤销后传播时间是多少**”→ 意图 `PARAMETER`，两路并行：

```mermaid
flowchart LR
    Q["权限撤销后传播时间是几分钟?"] --> CLS[意图分类<br/>含 多少/上限/阈值/单位/范围 → 参数意图]
    CLS --> SRC[来源: 参数表/需求/语义/向量]
    SRC --> S1[结构化: 参数表全量<br/>词法精确命中<br/>如 参数名=传播时间]
    SRC --> S2[向量: 类型化文本→嵌入→检索<br/>语义近似召回<br/>'权限撤销'也能命中]
    S1 --> F[融合: 向量相似度参与排序<br/>词法相关门槛前置]
    S2 --> F
    F --> OUT[返回命中<br/>参数名+值+单位+证据位置<br/>+关联冲突/存疑]
```

数值的“精确”和“语义”由此互补：**结构化的 min/max/unit 保证精确；向量化保证“绕着说”也能找到**。

### 6.2 需求文档与数值表在检索层的“结合”点

1. **同表同源**：需求 claim 与参数 claim 都来自 `knowledge_claim`，同一检索结果集内按 `sourceType` 区分；
2. **fact_key 对齐**：`module` 从 fact_key 提取，需求与参数同模块可互见；冲突检测按 fact_key 分组（同一事实、多来源数值不一致 → CONFLICTED 扣分并提示）；
3. **一致性对比**：`CONSISTENCY` 意图同时召回需求、测试、参数、代码——测试覆盖/参数确认 与 需求规范碰撞即产生冲突提示；
4. **融合加权**：向量命中给**排序分**不给**准入权**——词法不相关或被状态门禁过滤的候选不会因一次向量命中混入结果；
5. **证据回读**：命中的 claim 治理字段（状态、authority、evidenceLocation）从 SQLite 权威回读，Qdrant payload 只是坐标。

---

## 7. 发布语义与已知限制

```mermaid
flowchart LR
    D[草稿导入] --> P[发布文档版本<br/>单事务: 标记已发布 + 写入激活绑定]
    P --> BASE[基础检索可用]
    P --> V[向量投影构建]
    V -->|发现问题| RB[回滚<br/>alias 切回旧集合<br/>SQLite 恢复旧激活]
```

- **一次投影一个文档版本**：`knowledge_active_version` 主键 `(project_id, business_version)` 一对一；`findPublishedClaimsByProjectVersionPage` 只投影该绑定文档的 claim（Phase C Review 3 治理边界，防同版本多文档混入）。**要让全部 199 个文档的 claim 一起进向量投影**，需要放宽该查询到“该 business_version 下所有 PUBLISHED 文档”并配套批量发布机制——这是有意为之的决策，改动会推翻既有契约，需产品确认。
- **传统结构化检索不受此限**：`multi_source_parameter` 等按 `(project, version)` 全量聚合，与 DRAFT/PUBLISHED 无关，多文档数据天然一起召回。
- **语义/图路径也按 version 聚合**（aggregate 显示 `activeDocumentCount`），不受单文档绑定限制。

---

## 8. 运维入口速查

| 动作 | 接口 |
|---|---|
| 触发 Claim 向量构建 | `POST /api/knowledge/multi-source/claim-vector/build {projectId, businessVersion}` |
| 查询代际状态 | `GET .../claim-vector/status?projectId=&businessVersion=` |
| 质量门 | `GET .../claim-vector/quality-gate?projectId=&businessVersion=` |
| 回滚到上一代 / 指定代际 | `POST .../claim-vector/rollback` / `.../rollback-to` |
| 多源检索 | `POST /api/knowledge/multi-source/search {projectId, version, query, intent?, limit, page}` |
| 语义构建 | `POST /api/requirement-semantic/builds`（页面暂无按钮，需管理端调用） |

> 模型拓扑备忘：**嵌入 = text-embedding-v4 @ ai-gateway.momo.com**（OpenAI 兼容网关，1024 维）；
> **BGE = 本地重排器 :8081**（仅 rerank 阶段）；Ollama bge-m3 仅备选。查嵌入问题看网关，不看 :8081。