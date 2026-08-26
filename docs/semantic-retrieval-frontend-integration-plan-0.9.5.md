# 语义分块召回前端接入实现方案

**版本：** 0.9.5  
**状态：** 待实施  
**更新时间：** 2026-08-25  
**适用项目：** request-RAG

---

## 1. 背景

当前系统同时存在两条需求检索链路：

### 1.1 传统 Chunk 检索链路

```text
知识库前端
  -> POST /api/knowledge-bases/{id}/retrieval-tests
  -> KnowledgeManagementController
  -> RetrievalPipeline
  -> QdrantHybridStore
  -> 原有父子 Chunk / ChunkRecord
```

该链路已经接入 `/knowledge` 前端页面，适合测试原始文档分块、稠密向量、稀疏向量和重排效果。

### 1.2 最新语义分块/语义标注链路

```text
需求 Chunk / Window
  -> RequirementSemanticBuildService
  -> LLM 结构化标注
  -> SQLiteRequirementSemanticStore
  -> RequirementSemanticCandidateAdapter
  -> MultiSourceSearchService
  -> POST /api/knowledge/multi-source/search
```

该链路产生实体、条件、事件、数值事实、问题和 Claim，并参与多源事实检索。目前它与旧的 Qdrant Chunk 检索并行存在，尚未接入现有前端“检索测试”页面。

### 1.3 当前问题

1. 用户在前端点击“检索测试”时，实际测试的是旧 Chunk 召回；
2. 语义构建状态、active generation 和 source revision 无法在前端查看；
3. 语义 Claim、证据、跨源关系、冲突和存疑没有可视化入口；
4. 无法并排比较传统 Chunk 召回与最新语义召回；
5. 当前前端无法直接对召回结果进行“相关 / 不相关 / 漏召回”人工标记；
6. 语义模块默认关闭，用户无法知道当前查询是否真的使用了语义候选。

---

## 2. 建设目标

### 2.1 主要目标

在现有知识库页面中新增语义召回测试能力，并保留传统 Chunk 检索，形成以下三种测试模式：

```text
传统 Chunk 检索
语义 Claim 检索
对比检索
```

### 2.2 用户可以直观看到

- 当前使用的是哪条检索链路；
- 当前项目、版本和语义构建代际；
- 语义 Claim 的来源类型；
- subject、predicate、value、unit、factKey；
- 原始证据位置；
- 关联的需求、参数表、测试用例、测试结果、存疑和代码；
- 冲突和页外冲突；
- 检索警告和降级原因；
- Top K 结果中的相关、部分相关和无关项。

### 2.3 非目标

本版本不做以下事情：

1. 不删除或替换原有 `RetrievalPipeline`；
2. 不改变旧接口 `/api/knowledge-bases/{id}/retrieval-tests` 的语义；
3. 不在本版本实现 semantic_text 的 Qdrant 向量索引；
4. 不在查询侧重新调用 LLM 生成关系；
5. 不把传统 Chunk 和语义 Claim 强行合并为同一种数据结构；
6. 不将 OPEN 存疑自动提升为规范确认事实；
7. 不在第一阶段实现完整在线评测平台，先完成前端人工评测闭环。

---

## 3. 现有实现基线

### 3.1 传统检索前端

```text
/Users/user/Documents/request-RAG/src/main/resources/static/knowledge.html
/Users/user/Documents/request-RAG/src/main/resources/static/assets/knowledge-app.js
/Users/user/Documents/request-RAG/src/main/resources/static/assets/knowledge-api.js
```

现有前端入口：

```text
/knowledge
/knowledge/{baseId}/retrieval
```

现有 API：

```text
POST /api/knowledge-bases/{id}/retrieval-tests
```

请求字段：

```json
{
  "query": "成长基金奖励什么资源？",
  "version": "5.1",
  "limit": 10
}
```

返回字段主要包括：

```text
status
hits
codeHits
warnings
stageDiagnostics
```

### 3.2 语义构建 API

代码位置：

```text
/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/web/RequirementSemanticBuildController.java
```

接口：

```text
POST /api/requirement-semantic/builds
GET  /api/requirement-semantic/builds/latest
```

构建请求：

```json
{
  "projectId": "immortal-game-service",
  "documentId": "fengshen",
  "requirementVersion": "5.1",
  "collection": "需求知识库 collection",
  "retryFailedOnly": false
}
```

构建状态必须区分：

```text
latestRunStatus
latestRunId
generationActive
activeGenerationBuildId
activeGenerationSourceRevision
activeGenerationStatus
```

只有以下条件同时满足时，语义候选才允许进入检索：

```text
requirement-semantic.enabled = true
candidate-retrieval-enabled = true
存在 SUCCESS 的 active generation
项目、文档和版本匹配
```

### 3.3 多源检索 API

代码位置：

```text
/Users/user/Documents/request-RAG/src/main/java/com/example/requirementrag/web/MultiSourceKnowledgeController.java
```

接口：

```text
POST /api/knowledge/multi-source/search
```

请求：

```json
{
  "projectId": "immortal-game-service",
  "version": "5.1",
  "query": "成长基金奖励什么资源？",
  "intent": "PARAMETER",
  "limit": 20,
  "page": 0
}
```

响应当前包含：

```text
query
intent
answerStatus
claims
evidence
conflicts
doubts
explanations
warnings
relations
total
page
limit
hasMore
hasConflictsOutsidePage
```

### 3.4 当前配置限制

`application.yml` 中语义能力默认关闭：

```yaml
app:
  rag:
    requirement-semantic:
      enabled: false
      candidate-retrieval-enabled: false
      normative-retrieval-enabled: false
      vector-index-enabled: false
```

配置位置：

```text
/Users/user/Documents/request-RAG/src/main/resources/application.yml
```

第一阶段允许通过环境变量或本地配置开启，但生产环境必须继续默认关闭，避免未完成语义构建时改变规范检索结果。

---

## 4. 总体方案

### 4.1 前端模式设计

在现有“检索测试”页面上增加模式切换：

```text
[传统 Chunk] [语义 Claim] [对比检索]
```

#### 传统 Chunk 模式

继续调用：

```text
POST /api/knowledge-bases/{id}/retrieval-tests
```

用于观察原始 Chunk 的召回效果。

#### 语义 Claim 模式

调用：

```text
POST /api/knowledge/multi-source/search
```

只展示语义 Claim 和多源事实结果。

#### 对比检索模式

同一个查询并行调用两个接口：

```text
旧 Chunk 检索
语义 Claim 检索
```

左右两栏展示结果，帮助判断：

- 传统 Chunk 是否能找到原文；
- 语义 Claim 是否抽取出结构化事实；
- 两条链路是否命中同一事实；
- 语义 Claim 是否产生错误合并或错误冲突；
- 语义召回是否比旧 Chunk 更完整。

### 4.2 不修改原有检索协议

语义检索必须使用新增的前端 API 方法，不应把语义字段塞进旧的 `RetrievalTestResponse`。

原因：

1. 旧页面已经依赖 `hits` 和 `codeHits`；
2. 语义 Claim 与 Chunk 的字段模型不同；
3. 两种链路的状态、证据和冲突含义不同；
4. 保持接口隔离，便于灰度、回滚和对比评测。

---

## 5. 前端详细设计

### 5.1 API 封装

修改文件：

```text
/Users/user/Documents/request-RAG/src/main/resources/static/assets/knowledge-api.js
```

新增方法：

```javascript
semanticSearch(body) {
  return request('/api/knowledge/multi-source/search', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

semanticBuildStatus(params) {
  return request('/api/requirement-semantic/builds/latest' + query(params));
}

buildSemantic(body) {
  return request('/api/requirement-semantic/builds', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}
```

注意：必须继续复用 `NexusApi.request`，不能在页面代码中自行实现 `fetch`，以保证：

- API Key 注入一致；
- 超时处理一致；
- 错误结构一致；
- 认证失败提示一致。

### 5.2 页面状态

修改文件：

```text
/Users/user/Documents/request-RAG/src/main/resources/static/assets/knowledge-app.js
```

建议新增状态：

```javascript
retrievalMode: 'legacy',
semanticRetrieval: {
  query: '',
  intent: '',
  version: '',
  limit: 20,
  page: 0,
  loading: false,
  response: null,
  elapsedMs: null,
  buildStatus: null,
  error: null
}
```

建议增加计算属性：

```text
semanticClaims
semanticDoubts
semanticRelations
semanticConflicts
semanticWarnings
semanticBuildReady
semanticScope
```

### 5.3 页面结构

修改文件：

```text
/Users/user/Documents/request-RAG/src/main/resources/static/knowledge.html
```

建议结构：

```text
检索测试
├── 模式切换
│   ├── 传统 Chunk
│   ├── 语义 Claim
│   └── 对比检索
├── 查询条件
│   ├── 查询文本
│   ├── 项目
│   ├── 版本
│   ├── 查询意图
│   └── Top K
├── 语义构建状态
├── 结果摘要
├── Claim 结果列表
├── 证据列表
├── 跨源关系
├── 冲突和存疑
└── 人工相关性标记
```

### 5.4 语义状态提示

在语义模式下，页面必须显式显示构建状态。

#### SUCCESS 且 active

```text
语义构建：已发布
构建 ID：xxx
来源版本：xxx
```

#### 没有 active generation

```text
语义构建尚未发布，当前无法使用语义 Claim 检索。
请先执行语义构建并确认构建成功。
```

#### PARTIAL_FAILURE

```text
语义构建部分失败，本次结果仅供调试，不建议作为正式评测结果。
```

#### FAILED

```text
语义构建失败，不能把失败伪装成无召回结果。
```

#### 语义候选加载异常

应显示后端返回的稳定错误码，例如：

```text
SEMANTIC_CANDIDATE_LOAD_FAILED
```

不显示异常堆栈、数据库路径、模型地址或原始异常信息。

### 5.5 Claim 结果卡片

每个结果至少展示：

```text
排名
来源类型
claimId
factKey
subject
predicate
value
valueType
unit
status
certainty
version
证据位置
```

推荐显示示例：

```text
#1 需求语义
factKey: growth_fund.reward_currency
成长基金 · reward_currency · 灵玉
状态：SUPPORTED
证据：fengshen / 5.1 / 需求条目 xxx
```

来源类型建议使用颜色标签：

```text
REQUIREMENT
REQUIREMENT_SEMANTIC
PARAMETER_TABLE
TEST_CASE
TEST_RESULT
DOUBT
CODE
```

### 5.6 证据展示

点击 Claim 后展开证据详情：

- sourceType；
- sourceFile；
- documentId；
- entryId；
- sectionPath；
- quote；
- evidenceLocation；
- 是否支持该 Claim。

如果证据不可用，必须明确显示：

```text
证据不可回查
```

不能显示成普通空白，也不能把推断结果展示为确认事实。

### 5.7 关系、冲突和存疑

#### 跨源关系

展示：

```text
需求 Claim -> 参数 Claim
需求 Claim -> 测试用例
需求 Claim -> 代码事实
测试用例 -> 测试结果
```

#### 冲突

展示：

```text
需求值：30
参数表值：40
代码默认值：30
```

并显示：

```text
CONFLICTED
REVIEW_REQUIRED
```

#### 存疑

展示：

- doubtId；
- question；
- answer；
- status；
- owner；
- severity；
- evidenceLocation。

OPEN 存疑需要使用单独的警示样式，不得与确认事实混排成相同颜色。

---

## 6. 对比检索设计

### 6.1 调用策略

对比模式下，前端并行调用：

```text
legacyResponse = /api/knowledge-bases/{id}/retrieval-tests
semanticResponse = /api/knowledge/multi-source/search
```

两个请求必须使用相同的：

```text
projectId
version
query
limit
```

### 6.2 展示布局

桌面端：

```text
┌──────────────────────┬──────────────────────┐
│ 传统 Chunk 召回       │ 语义 Claim 召回       │
├──────────────────────┼──────────────────────┤
│ Top K 文本分块        │ Top K 结构化事实      │
│ 来源文件              │ 来源类型              │
│ 章节                  │ factKey              │
│ 验收条件              │ evidence              │
│ 阶段诊断              │ relations/conflicts  │
└──────────────────────┴──────────────────────┘
```

移动端改为上下排列。

### 6.3 对比结果判断

前端第一阶段只做人工观察，不自动宣称哪条链路更好。

人工判断维度：

```text
传统 Chunk 是否命中原文
语义 Claim 是否命中事实
语义 Claim 是否有准确证据
是否出现错误合并
是否出现错误冲突
是否召回了不应该出现的 OPEN 存疑
```

---

## 7. 人工评测闭环

### 7.1 结果标记

每个召回结果增加四个操作：

```text
相关
部分相关
不相关
漏召回
```

其中“漏召回”不直接绑定某个结果，而绑定当前查询。

### 7.2 第一阶段存储

第一阶段可以使用浏览器 `localStorage`，避免立即新增数据库表。

推荐结构：

```json
{
  "caseId": "fengshen-doc-001",
  "query": "成长基金奖励什么资源？",
  "mode": "SEMANTIC",
  "projectId": "immortal-game-service",
  "version": "5.1",
  "resultId": "claim-001",
  "rank": 1,
  "judgement": "RELEVANT",
  "note": "证据覆盖完整奖励事实",
  "createdAt": "2026-08-25T23:00:00+08:00"
}
```

### 7.3 第二阶段落库

稳定后增加评测数据表：

```text
evaluation_case
retrieval_evaluation_run
retrieval_evaluation_judgement
```

建议保存：

- 查询；
- 金标 ID；
- 检索模式；
- 项目和版本；
- 构建 ID；
- sourceRevision；
- 返回结果 ID 和排名；
- 人工判断；
- 评测人；
- 评测时间；
- 备注。

评测结果必须绑定构建代际，否则不同语义构建之间无法公平比较。

---

## 8. 后端兼容性要求

### 8.1 保留旧接口

禁止删除或改变：

```text
POST /api/knowledge-bases/{id}/retrieval-tests
```

传统检索页面仍然依赖该接口。

### 8.2 多源接口保持兼容

新增字段时采用向后兼容方式：

- 只增加字段；
- 不删除 `claims`、`evidence`、`conflicts`、`warnings`；
- 不修改枚举含义；
- 空集合与失败必须区分；
- `hasConflictsOutsidePage` 继续保留。

### 8.3 后端建议补充的可观测字段

当前多源响应按照 `claims` 顺序返回结果，但没有公开每条 Claim 的得分。

后续可以新增：

```json
{
  "claimHits": [
    {
      "rank": 1,
      "score": 12.5,
      "claim": {}
    }
  ]
}
```

该字段应采用新增字段方式，不替换原有 `claims`，以避免破坏已有客户端。

建议记录但不直接暴露内部细节：

```text
sourcePriority
factKeyMatch
subjectMatch
predicateMatch
valueMatch
conflictPenalty
```

第一阶段如果暂不增加后端字段，前端至少展示稳定排名，不要伪造分数。

---

## 9. 配置和发布策略

### 9.1 本地测试

本地测试允许使用：

```bash
REQUIREMENT_SEMANTIC_ENABLED=true
REQUIREMENT_SEMANTIC_CANDIDATE_RETRIEVAL_ENABLED=true
REQUIREMENT_SEMANTIC_NORMATIVE_RETRIEVAL_ENABLED=true
```

### 9.2 灰度环境

建议先只开启：

```text
语义构建
语义候选召回
对比检索页面
```

不改变默认规范回答链路。

### 9.3 生产环境

生产环境需要满足以下条件后再打开规范语义召回：

1. 有成功且 active 的语义构建；
2. sourceRevision 与目标版本匹配；
3. 证据可回查率达到门槛；
4. 语义 Claim 金标评测通过；
5. 冲突和存疑过滤测试通过；
6. 有明确的回滚开关。

### 9.4 回滚

如果语义召回质量下降：

```text
关闭 candidate-retrieval-enabled
保留旧 Chunk 检索
保留语义构建数据
保留人工评测记录
```

不得删除 SQLite 语义库或覆盖旧 Qdrant 索引。

---

## 10. 实施阶段

### Phase 1：前端 API 和模式骨架

目标：前端能够切换传统 Chunk 和语义 Claim 模式。

任务：

- [ ] 在 `knowledge-api.js` 增加语义查询 API；
- [ ] 在 `knowledge-app.js` 增加 `retrievalMode`；
- [ ] 在 `knowledge.html` 增加模式切换；
- [ ] 保持传统检索路径完全不变；
- [ ] 对语义查询增加 loading、empty、error 状态；
- [ ] 增加语义构建状态展示。

验收：

- 传统模式结果不受影响；
- 语义模式能够正确调用多源接口；
- 语义模块关闭时显示明确提示；
- 没有 active generation 时不会显示“无结果”假成功。

### Phase 2：Claim、证据和多源关系展示

任务：

- [ ] 展示 Claim 字段；
- [ ] 展示证据位置；
- [ ] 展示来源类型；
- [ ] 展示关系；
- [ ] 展示冲突；
- [ ] 展示存疑；
- [ ] 展示 warnings 和 explanations；
- [ ] 增加分页和页外冲突提示。

验收：

- 每个 Claim 都能回查来源；
- OPEN 存疑与确认事实视觉上明确区分；
- 冲突不会因为 Top K 分页而静默消失；
- 页外冲突时显示明确提示。

### Phase 3：对比检索

任务：

- [ ] 并行调用旧 Chunk 和语义 Claim 接口；
- [ ] 双栏展示结果；
- [ ] 显示两个请求的耗时和状态；
- [ ] 支持单独展开证据；
- [ ] 对比项目、版本和构建代际；
- [ ] 增加失败一侧的降级提示。

验收：

- 一侧失败不会覆盖另一侧结果；
- 旧链路失败和语义链路失败能够分别识别；
- 两侧使用相同查询、项目和版本；
- 刷新和切换模式不会残留上一次结果。

### Phase 4：人工评测

任务：

- [ ] 增加相关、部分相关、不相关按钮；
- [ ] 增加漏召回标记；
- [ ] 记录 query、mode、buildId、sourceRevision；
- [ ] 初期写入 localStorage；
- [ ] 增加评测结果导出 JSON；
- [ ] 支持从固定评测集加载查询。

验收：

- 页面刷新后标记不丢失；
- 不同构建代际的结果可以区分；
- 能导出人工标注结果；
- 可根据导出结果计算 Recall@K 和 MRR。

### Phase 5：评分和自动评测

任务：

- [ ] 增加 `claimHits` 排名响应；
- [ ] 展示稳定 score；
- [ ] 增加金标 Claim ID 匹配；
- [ ] 计算 Recall@1、Recall@3、Recall@5、Recall@10；
- [ ] 计算 MRR；
- [ ] 计算误召回率；
- [ ] 计算证据可回查率；
- [ ] 计算跨版本污染率。

验收：

- 自动指标与人工复核结果一致；
- 同一个数据集能够对比旧链路和新链路；
- 报告中明确区分“召回了相关实体”和“召回了完整证据”。

---

## 11. 测试方案

### 11.1 前端单元测试

如果当前项目暂时没有前端测试框架，第一阶段至少增加浏览器级 smoke test：

- 模式切换；
- 请求参数校验；
- 语义构建状态渲染；
- 空结果渲染；
- 失败结果渲染；
- warnings 展示；
- 分页；
- 相关性标记持久化。

### 11.2 后端接口测试

需要覆盖：

- 语义模块关闭时返回明确状态；
- candidate retrieval 关闭时不加载语义候选；
- normative retrieval 开关生效；
- active generation 过滤正确；
- 不同 sourceRevision 不串数据；
- 构建失败不能成为 active generation；
- 语义候选加载失败不能伪装成空结果；
- 候选截断返回 warning；
- 分页外冲突可以被发现；
- OPEN 存疑不会进入普通规范确认结果。

### 11.3 人工测试集

建议至少覆盖：

| 场景 | 目标 |
|---|---|
| 单一实体 | 验证实体召回 |
| 条件规则 | 验证条件与行为是否完整 |
| 数值单位 | 验证数值、单位和边界 |
| 跨窗口事实 | 验证语义窗口合并能力 |
| 需求与参数冲突 | 验证冲突识别 |
| 需求与代码漂移 | 验证代码事实优先策略 |
| OPEN 存疑 | 验证存疑不会伪装成事实 |
| 无结果查询 | 验证 NO_RESULT |
| 依赖失败 | 验证 FAILED / DEGRADED 区分 |
| 版本隔离 | 验证不同版本不串召回 |

### 11.4 关键指标

第一阶段人工记录：

```text
Recall@1
Recall@3
Recall@5
Recall@10
MRR
误召回率
证据可回查率
冲突识别准确率
版本污染率
语义链路失败率
```

其中必须分别统计：

```text
实体召回
事实召回
完整证据召回
跨源关联召回
```

不能只用“返回了相关文本”作为成功标准。

---

## 12. 风险与处理

### 风险 1：语义 Claim 与旧 Chunk 结果不一致

处理：

- 对比模式中并列展示，不自动覆盖旧结果；
- 以代码事实为最终实现基线；
- 需求文档只作为意图和漂移检测来源；
- 冲突保留为可审查状态。

### 风险 2：语义模块未构建却显示无结果

处理：

- 查询前读取 latest build 状态；
- 无 active generation 显示“未发布”；
- 不把构建缺失显示为普通 NO_RESULT。

### 风险 3：语义候选被截断

处理：

- 前端展示 `SEMANTIC_CANDIDATE_TRUNCATED`；
- 显示“结果可能不完整”；
- 后续增加 FTS5/BM25 或更稳定的候选排序。

### 风险 4：前端把 Claim 当作最终答案

处理：

- Claim 卡片必须展示证据和状态；
- `INFERRED`、`UNKNOWN` 和 OPEN 存疑使用警示样式；
- 不在前端把 Claim 直接渲染成“确认结论”。

### 风险 5：两种模式状态串联

处理：

- 切换模式时清空对应 response；
- 每次结果保存 mode、projectId、version 和 buildId；
- 不复用不同接口的同名字段；
- 使用独立的响应投影函数。

---

## 13. 目录和文件变更清单

### 第一阶段预计修改

```text
src/main/resources/static/knowledge.html
src/main/resources/static/assets/knowledge-app.js
src/main/resources/static/assets/knowledge-api.js
```

### 第二阶段可能修改

```text
src/main/resources/static/assets/knowledge-app.css
src/main/resources/static/assets/components.css
```

### 后端可选修改

```text
src/main/java/com/example/requirementrag/knowledge/multisource/MultiSourceKnowledgeModels.java
src/main/java/com/example/requirementrag/knowledge/multisource/MultiSourceSearchService.java
src/main/java/com/example/requirementrag/web/MultiSourceKnowledgeController.java
```

后端修改只用于补充排名、构建代际和评测字段，不用于替换旧 Chunk 检索协议。

### 测试文件

```text
src/test/java/com/example/requirementrag/web/MultiSourceKnowledgeControllerTest.java
src/test/java/com/example/requirementrag/knowledge/multisource/MultiSourceSearchServiceTest.java
src/test/java/com/example/requirementrag/knowledge/multisource/RequirementSemanticCandidateRetrievalTest.java
```

如果引入前端测试框架，再增加：

```text
src/test/frontend/knowledge-retrieval.test.js
src/test/frontend/knowledge-semantic-retrieval.test.js
```

---

## 14. 推荐第一批实现范围

为了尽快看到效果，建议第一批只实现以下内容：

1. 在现有“检索测试”页面增加“传统 Chunk / 语义 Claim / 对比检索”切换；
2. 接入 `POST /api/knowledge/multi-source/search`；
3. 接入 `GET /api/requirement-semantic/builds/latest`；
4. 展示 Claim、来源类型、证据、状态和 warnings；
5. 语义未发布时显示明确提示；
6. 保持原有 Chunk 检索完全不变；
7. 增加 10 条人工查询的相关性标记；
8. 暂不增加数据库评测表；
9. 暂不增加 semantic_text 向量索引；
10. 暂不改变生产默认开关。

完成这批工作后，就可以从浏览器直观看到：

```text
旧 Chunk 找到了什么
新语义 Claim 找到了什么
两者是否命中同一事实
语义结果是否有证据
需求、参数、测试和代码是否形成关联
```

---

## 15. 最终验收标准

### 功能验收

- [ ] 现有传统检索页面功能不回归；
- [ ] 语义模式可以查询 Claim；
- [ ] 对比模式可以同时展示两条链路；
- [ ] 语义构建状态可见；
- [ ] 证据、关系、冲突和存疑可见；
- [ ] 结果支持分页；
- [ ] 结果支持人工相关性标记。

### 正确性验收

- [ ] 无 active generation 时不显示语义成功；
- [ ] 不同项目和版本不串数据；
- [ ] OPEN 存疑不会进入普通规范事实；
- [ ] 构建失败不会发布为 active；
- [ ] 候选加载失败不伪装成空结果；
- [ ] 证据不可回查时明确降级；
- [ ] 页外冲突有明确提示。

### 评测验收

- [ ] 能加载固定测试查询；
- [ ] 能保存人工判断；
- [ ] 能导出人工标注；
- [ ] 能区分旧链路和新链路；
- [ ] 能按 projectId、version、buildId、sourceRevision 追踪评测结果；
- [ ] 能计算至少 Recall@1、Recall@5 和 MRR。

---

## 16. 结论

本方案不把最新语义分块强行替换到传统 Qdrant Chunk 链路中，而是采用“并行接入、前端对比、逐步灰度”的方式：

```text
旧 Chunk 检索保留
语义 Claim 检索新增
前端提供对比模式
人工评测验证质量
通过后再考虑融合排序
```

这样可以避免在语义模块尚未完成向量索引、证据评测和质量验证之前，直接影响现有检索结果，也能让开发人员从前端清楚地观察最新语义分块到底带来了什么收益和问题。
