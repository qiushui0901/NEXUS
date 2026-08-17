# 统一检索与重排

## Goal

建立需求评审、开发方案和代码检索共用的可配置检索管线，并通过可版本化的脱敏金标集证明重构前后检索质量没有不可接受的回退。

## Background

当前开发方案同步接口、SSE 接口、需求评审和代码检索分别编排路由、需求召回、代码召回与重排，行为容易漂移。重构前必须先固化可重复执行的检索质量基线，避免仅凭主观体验判断新管线是否更好。

2026-07-23 已从现有 `fengshen` 5.1 知识库和 `immortal-game-service` 代码索引中人工确认首批 10 条 Gold Case：7 条需求/混合题、2 条纯代码题、1 条真实无结果题。Gold Label 使用稳定业务标识，不使用 Qdrant point ID。

## Requirements

### Evaluation dataset

- 在 `src/test/resources/evaluation/retrieval-eval-v1.jsonl` 保存评测数据，一行一条记录。
- 首批纳入 10 条已确认 Gold Case；后续扩展到约 50 条脱敏真实查询。
- 文档 Gold Label 使用 `filename`、可选 `parentOrder` 和 `mustContain`。
- 代码 Gold Label 使用 `projectId`、`filePath` 和 `symbolName`。
- 明确区分 `HIT` 与 `NO_RESULTS`，无结果题不得通过语义相近但不能回答问题的材料判为命中。
- 数据文件只能包含评测元数据，不包含向量、Qdrant point ID、凭据或运行时数据。

### Evaluation execution

- 提供 JSONL 加载、结构校验和稳定 Gold Label 匹配能力。
- 默认 Maven `verify` 只运行确定性的格式/匹配单元测试，不依赖 Qdrant、Ollama、BGE 或外部模型。
- 实际在线评测必须显式启用，复用当前检索实现生成重构前基线。
- 在线评测报告写入被忽略的 `target/retrieval-evaluation/`，至少生成 JSON 和 Markdown。
- 第一版报告包含文档 Recall@10、代码 Recall@10、MRR@10、混合 both-hit、no-result accuracy、逐条结果和耗时；样本较少时同时报告失败条数。

### Unified pipeline

- 抽取 `RetrievalRequest`、`RetrievalProfile`、`RetrievalBundle` 和 `RetrievalPipeline`。
- 管线编排现有 Qdrant、代码索引和重排组件，不替换底层存储实现。
- 通过 profile 表达 `DEVELOPMENT_PLAN`、`REQUIREMENT_REVIEW`、`CODE_SEARCH` 的来源和重排差异。
- 统一保留父块恢复、BGE/LLM 可选重排、上下文预算、阶段诊断和 `RagOutcome` 语义。
- 迁移顺序为同步开发方案、SSE 开发方案、需求评审；每一步通过同一评测集比较基线。

## Acceptance Criteria

- [ ] 首批 10 条已确认样例以合法 JSONL 保存并由默认测试校验。
- [ ] 非法 ID、空查询、无 Gold 的 HIT、带 Gold 的 NO_RESULTS、重复样例和不稳定 point ID 会被拒绝。
- [ ] 可选在线运行器能够在本地依赖可用时执行当前实现，并生成 JSON/Markdown 基线报告。
- [ ] 默认 `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify` 不要求外部服务且通过。
- [ ] 评测集扩展到约 50 条后，旧管线基线可重复生成。
- [ ] 统一 RetrievalPipeline 的 Recall@10、MRR@10 回退不超过 5%，并报告具体回退样例数。
- [ ] 需求评审、开发方案和代码搜索 profile 差异有测试覆盖。
- [ ] 零命中、候选排序和降级语义测试通过。

## Out of Scope

- 不把向量、Qdrant storage、snapshot、日志或 PID 文件提交到 Git。
- 首批评测不评估最终自然语言答案的文风；仅记录检索证据和明显的无答案/过度推断现象。
- 故障注入继续由单元测试覆盖，不混入检索质量 Gold Dataset。
