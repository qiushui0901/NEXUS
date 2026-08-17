# RAG 系统短板治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Each task must pass its focused tests before the next task starts.

**Goal:** 分阶段修复 RAG 检索质量、解析可靠性、错误契约、证据追溯和大文档截断问题。

**Architecture:** 先建立离线质量基线，再抽取统一检索与错误结果模型；随后增加证据白名单、AST shadow index 和 Map-Reduce。每一阶段通过配置开关和独立 collection 保留回滚路径。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring AI 2.0、Qdrant REST、Ollama embedding、JavaParser + Symbol Solver、JUnit 5、脱敏 JSONL。

## Global Constraints

- 保持现有 REST 路径、权限校验、默认请求字段和 CodeChunk/Qdrant payload 兼容。
- 评测集只提交脱敏查询、稳定证据 ID、类别和 `no_answer` 标记。
- 新管线相对旧管线 Recall@10、MRR/nDCG 回退不得超过 5%。
- 延迟或外部模型调用增加超过 30% 必须在报告中说明。
- 不覆盖当前工作区未提交修改；每个子任务有独立回滚点。

## Task 1: 评测基线与统一检索管线

**Files:**

- Create: `src/test/resources/rag/eval/queries.jsonl`
- Create: `src/test/java/com/example/requirementrag/eval/RagEvaluationRunnerTest.java`
- Create: `src/main/java/com/example/requirementrag/retrieval/RetrievalRequest.java`
- Create: `src/main/java/com/example/requirementrag/retrieval/RetrievalProfile.java`
- Create: `src/main/java/com/example/requirementrag/retrieval/RetrievalOutcome.java`
- Create: `src/main/java/com/example/requirementrag/retrieval/RetrievalPipeline.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanService.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Modify: `src/main/java/com/example/requirementrag/service/DoubtReviewService.java`
- Test: `src/test/java/com/example/requirementrag/service/DevelopmentPlanServiceTest.java`

**Interfaces:**

- Consumes existing `RequirementQueryRewriter`, `QdrantHybridStore`, `CodeKnowledgeService`, `BgeReranker` and `RagProperties`.
- Produces `RetrievalOutcome` with ordered candidates, evidence metadata, status, warnings and timings.

- [ ] 从现有文档、历史存疑和代码问题整理 50 条脱敏 JSONL，并人工确认期望证据。
- [ ] 为旧需求路径和新统一路径分别运行评测，保存基线报告。
- [ ] 为 `RetrievalRequest` 定义 query/project/document/version/source/limit 字段，为 `RetrievalProfile` 定义 denseTopK/sparseTopK/hybridTopK/bgeTopK/llmTopK/contextBudget。
- [ ] 将需求评审和开发方案的需求检索改为调用 `RetrievalPipeline.retrieve`，profile 只表达差异。
- [ ] 保留代码检索现有三向量加关键词路径，并把结果转换成统一 outcome。
- [ ] 添加测试验证父块恢复、profile 差异、候选排序和无结果状态。
- [ ] 运行 `./mvnw test -Dtest='*Retrieval*','*DevelopmentPlanService*'`，确认通过后记录新旧指标。

## Task 2: 错误和降级契约

**Files:**

- Create: `src/main/java/com/example/requirementrag/retrieval/DegradationWarning.java`
- Create: `src/main/java/com/example/requirementrag/web/RagErrorResponse.java`
- Modify: `src/main/java/com/example/requirementrag/retrieval/RetrievalOutcome.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Modify: `src/main/java/com/example/requirementrag/web/AssistantController.java`
- Modify: `src/main/java/com/example/requirementrag/web/GlobalExceptionHandler.java`
- Test: `src/test/java/com/example/requirementrag/service/DevelopmentPlanStreamServiceTest.java`

**Interfaces:**

- Consumes `RetrievalOutcome` and existing SSE event model.
- Produces optional JSON `status/warnings` and SSE `warning/error` events; core failures map to 502/503.

- [ ] 为 Qdrant、Embedding、query rewrite、BGE、LLM rerank、routing 和 generation 定义稳定 warning codes。
- [ ] 将当前静默 `catch` 路径改成 outcome warning 或抛出带阶段信息的异常。
- [ ] 为无证据核心失败增加 502/503 映射；保留真实零命中为 2xx `NO_RESULTS`。
- [ ] 为 SSE 添加 `warning` 事件并测试部分输出、空输出和核心失败三种情况。
- [ ] 运行 `./mvnw test -Dtest='*StreamService*','*Exception*'`。

## Task 3: 证据级引用

**Files:**

- Create: `src/main/java/com/example/requirementrag/model/EvidenceRef.java`
- Create: `src/main/java/com/example/requirementrag/service/EvidenceRegistry.java`
- Modify: `src/main/java/com/example/requirementrag/model/DevelopmentPlanResponse.java`
- Modify: `src/main/java/com/example/requirementrag/model/DevelopmentPlanStreamEvent.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanService.java`
- Modify: `src/main/java/com/example/requirementrag/service/DevelopmentPlanStreamService.java`
- Modify: `src/main/java/com/example/requirementrag/service/DoubtReviewService.java`
- Modify: `src/main/resources/static/monitor.html`
- Test: `src/test/java/com/example/requirementrag/service/PlanSectionEvidenceMatcherTest.java`

- [ ] 为文档 parent、代码 symbol 建立稳定 evidence ID 和白名单 registry。
- [ ] 扩展同步 response、SSE section 和 references，保留现有字段。
- [ ] 校验模型输出的每个 `evidenceId` 都存在于本次 registry，非法 ID 被丢弃并记录 warning。
- [ ] 前端把 evidence ID 渲染为可点击标签，继续支持旧 references 结构。
- [ ] 运行 `./mvnw test -Dtest='*Evidence*','*AssistantController*'`。

## Task 4: AST 影子索引与迁移

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/example/requirementrag/code/JavaAstScanner.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeKnowledgeService.java`
- Modify: `src/main/java/com/example/requirementrag/code/CodeQdrantStore.java`
- Modify: `src/main/java/com/example/requirementrag/config/RagProperties.java`
- Test: `src/test/java/com/example/requirementrag/code/JavaAstScannerTest.java`
- Test: `src/test/java/com/example/requirementrag/code/CodeIndexMigrationTest.java`

- [ ] 添加 JavaParser 和 Symbol Solver 依赖及索引版本配置。
- [ ] 先为 record、重载、嵌套类型、注解、继承和实现写失败测试。
- [ ] 实现 AST scanner，输出现有 `CodeChunk` 所需的 symbol、范围和关系字段。
- [ ] shadow 模式对比旧 scanner，记录差异但不切换默认 collection。
- [ ] 将 AST 结果写入 `code_chunks_v2`，执行 payload 和查询回归。
- [ ] 通过评测后切换 ProjectRegistry collection，保留旧 collection 回滚。
- [ ] 运行 `./mvnw test -Dtest='*Ast*','*CodeIndex*'`。

## Task 5: 大文档 Map-Reduce

**Files:**

- Create: `src/main/java/com/example/requirementrag/service/DocumentBatchPlanner.java`
- Create: `src/main/java/com/example/requirementrag/service/ReviewMapReduceService.java`
- Modify: `src/main/java/com/example/requirementrag/service/DoubtReviewService.java`
- Modify: `src/main/java/com/example/requirementrag/config/RagProperties.java`
- Test: `src/test/java/com/example/requirementrag/service/DocumentBatchPlannerTest.java`
- Test: `src/test/java/com/example/requirementrag/service/ReviewMapReduceServiceTest.java`

- [ ] 为小文档定义上下文预算内的快速路径测试。
- [ ] 实现按路径模块分组、无模块时按 parentOrder 批次兜底的 planner。
- [ ] Map 阶段每批生成结构化候选和 evidenceIds，记录覆盖模块、失败和重试。
- [ ] Reduce 阶段按归一化问题去重、合并证据、按模块排序并限制最终条数。
- [ ] 单批失败时输出 warning，不静默丢弃；全部失败时遵循 R3 核心失败契约。
- [ ] 运行 `./mvnw test -Dtest='*BatchPlanner*','*MapReduce*','*DoubtReview*'`。

## Final Integration Gate

- [ ] 运行 `./mvnw test`。
- [ ] 运行评测 runner，对比旧管线基线，确认核心指标回退不超过 5%。
- [ ] 检查同步 JSON、SSE、错误响应、引用回查和前端兼容。
- [ ] 检查 AST 新旧 collection 回滚开关及大文档单批失败行为。
- [ ] 使用 Trellis quality check，完成 spec 更新和最终提交。
