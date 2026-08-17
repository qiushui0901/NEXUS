# 自进化 RAG M1-M4 实施计划

## 实施顺序

- [x] Phase 0：配置与模型
  - [ ] `RagProperties` 增加 `Evolution` record，`application.yml` 增加默认关闭配置。
  - [ ] 新增 `evolution/experience/RetrievalExperience.java` 及嵌套 record。
  - [ ] 新增 `evolution/policy/RetrievalPolicy.java`、`PolicyStatus.java`。
  - [ ] 新增 `evolution/mining/EvaluationCandidate.java`、`ReviewStatus.java`。
  - [ ] 新增 `evolution/evaluation/ExperimentManifest.java`、`ExperimentReport.java`、`EvaluationDataset.java`。
  - [ ] 新增 `PolicyParameterValidator` 和 checksum 工具。

- [x] Phase 1：Experience Recorder
  - [ ] 新增 `RetrievalExperienceStore`、`FileRetrievalExperienceStore`（JSONL 追加/轮转/清理/安全路径）。
  - [ ] 新增 `RetrievalExperienceRecorder`（异步、采样、队列、指标、失败不抛出）。
  - [ ] 新增 `EvolutionTrace` 并在 `AgenticOrchestrator` 内采集每跳信息。
  - [ ] `AgenticOrchestrator` 接入 recorder（`ObjectProvider`，默认关闭无行为变化）。
  - [ ] 单元测试：recorder 写文件、采样、失败隔离、trace 字段完整性。

- [x] Phase 2：Failure Miner 与候选审核
  - [ ] 新增 `RetrievalFailureMiner`、`FailureRule`、`FailureClusterer`。
  - [ ] 新增 `EvaluationCandidateStore`（JSON 落盘、状态读取、去重）。
  - [ ] 新增 `EvaluationCaseReviewService`（状态机 DRAFT→IN_REVIEW→APPROVED/REJECTED→PUBLISHED/ROLLED_BACK）。
  - [ ] 新增 `EvaluationDatasetRegistry`（不可变版本、active 引用、回滚）。
  - [ ] 新增 `EvolutionScheduler`（每日扫描，默认关闭或由配置控制）。
  - [ ] 单元测试：失败分类、去重、状态机非法转换、数据集发布/回滚。

- [x] Phase 3：Experiment Runner
  - [ ] 新增 `EvolutionExperimentRunner`。
  - [ ] 支持从数据集加载 case，对基线和候选策略各运行一次检索并计算 Recall/MRR/nDCG。
  - [ ] 新增 `RetrievalMetrics`（与现有评测口径一致）。
  - [ ] 输出 `ExperimentReport` 并落盘到 `data/evolution/experiments/`。
  - [ ] 单元测试：固定数据集、固定策略产生可比较报告。

- [x] Phase 4：Policy Registry 与 Promotion Gate
  - [ ] 新增 `RetrievalPolicyRegistry`（CRUD、校验、active 原子引用）。
  - [ ] 新增 `PolicyLifecycleService`（Draft→Evaluating→Approved/Rejected，审计）。
  - [ ] 新增 `PolicyPromotionGate`（按 ExperimentReport 阈值判断）。
  - [ ] `RetrievalStrategySelector` 增加 `PolicyDrivenRetrievalStrategySelector` 并在启用时使用。
  - [ ] `EvidenceReflector` 支持 policy 阈值覆盖。
  - [ ] 单元测试：非法参数拒绝、门禁边界、激活/回退。

- [x] API 与集成
  - [ ] 新增 `EvolutionController`：
    - `GET/POST /api/evolution/candidates`
    - `POST /api/evolution/candidates/{id}/review`
    - `GET/POST /api/evolution/datasets`
    - `POST /api/evolution/datasets/{version}/rollback`
    - `GET/POST /api/evolution/policies`
    - `POST /api/evolution/policies/{policyId}/{version}/evaluate`
    - `POST /api/evolution/policies/{policyId}/{version}/approve`
    - `POST /api/evolution/experiments`
  - [ ] 权限校验：读操作 `PUBLIC_READ`，写/审核/发布 `WRITE`，策略批准 `OPERATE`。

- [x] 质量验证
  - [ ] `./mvnw -B verify` 通过。
  - [ ] `git diff --check` 通过。
  - [ ] CHANGELOG.md 记录本次变更。

## 验证命令

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
git diff --check
```

## 回滚点

- 每个 Phase 完成后保持可编译；若中途失败可回退到上一提交点。
- 新模块默认关闭，不影响现有检索。
