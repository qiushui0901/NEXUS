# 自进化 RAG M1-M4 技术设计

## 1. 范围

新增 `com.example.requirementrag.evolution.*` 子包，覆盖：

- `experience`：经验事件模型、采集器、JSONL 存储
- `mining`：失败挖掘、候选模型、候选存储
- `evaluation`：候选审核、数据集注册表、实验运行器、报告模型
- `policy`：策略模型、注册表、生命周期、Promotion Gate
- `scheduling`：每日失败挖掘调度
- `web.EvolutionController`：REST API

## 2. 数据流

```text
AgenticOrchestrator.execute()
  -> EvolutionTrace 采集每跳策略/候选/反思
  -> RetrievalExperienceRecorder.recordAsync()
  -> FileRetrievalExperienceStore.append(JSONL)

EvolutionScheduler / API 触发
  -> RetrievalFailureMiner.scan()
  -> EvaluationCandidateStore 落盘候选
  -> EvaluationCaseReviewService 审核
  -> EvaluationDatasetRegistry 发布不可变数据集版本

EvolutionExperimentRunner
  -> 读取数据集 + 基线/候选 RetrievalPolicy
  -> 对每个 case 调用 RetrievalPipeline/AgenticOrchestrator
  -> 生成 ExperimentReport
  -> PolicyPromotionGate 判断是否通过
  -> PolicyLifecycleService 更新策略状态
```

## 3. 配置

`app.rag.evolution` 新增：

```yaml
app:
  rag:
    evolution:
      enabled: false
      experience-recording-enabled: false
      success-sample-rate: 0.1
      failure-sample-rate: 1.0
      queue-capacity: 1000
      query-preview-enabled: false
      retention-days: 30
      experience-root-path: data/evolution/experiences
      candidate-root-path: data/evolution/candidates
      dataset-root-path: data/evolution/datasets
      policy-root-path: data/evolution/policies
```

在 `RagProperties` 中新增 `Evolution` record，并提供默认值解析方法。

## 4. 核心数据模型

### 4.1 RetrievalExperience

```java
public record RetrievalExperience(
    int schemaVersion,
    String experienceId,
    Instant occurredAt,
    String projectId,
    String documentId,
    String version,
    String queryHash,
    String queryPreview,
    String retrievalProfile,
    String selectedStrategy,
    List<String> executedStrategies,
    int hops,
    List<CandidateSnapshot> candidates,
    List<String> finalRanking,
    List<String> evidenceIds,
    String reflectionVerdict,
    String reflectionReasonCode,
    String outcomeStatus,
    List<String> warningCodes,
    List<StageSnapshot> diagnostics,
    long latencyMs,
    Long tokenCost,
    List<String> degradedStages,
    UserFeedback feedback,
    String policyVersion,
    String configHash,
    String indexVersion,
    String datasetVersion
) {}
```

嵌套类型：

- `CandidateSnapshot(candidateId, channel, originalRank, finalRank, score)`
- `StageSnapshot(stage, status, warningCode, durationMs)`
- `UserFeedback(rating, rejectedEvidenceIds, commentPreview, feedbackAt)`

### 4.2 EvaluationCandidate

```java
public record EvaluationCandidate(
    String candidateId,
    String sourceExperienceId,
    String queryHash,
    String failureType,
    String failureReason,
    List<String> predictedRelevantIds,
    double priorityScore,
    ReviewStatus reviewStatus,
    String reviewer,
    Instant reviewedAt
) {}
```

`ReviewStatus`: `DRAFT`, `IN_REVIEW`, `APPROVED`, `REJECTED`, `PUBLISHED`, `ROLLED_BACK`

### 4.3 RetrievalPolicy

```java
public record RetrievalPolicy(
    String policyId,
    String version,
    PolicyStatus status,
    Map<String, String> selectorRules,
    Map<String, Double> rankingWeights,
    Map<String, Integer> thresholds,
    Map<String, Boolean> featureFlags,
    String parentVersion,
    String experimentId,
    String checksum,
    Instant createdAt
) {}
```

`PolicyStatus`: `DRAFT`, `EVALUATING`, `APPROVED`, `REJECTED`, `ACTIVE`, `ROLLED_BACK`

参数 allowlist 由 `PolicyParameterValidator` 维护，首期支持：

- `selector.requirement-review-strategy`
- `selector.code-intent-strategy`
- `orchestrator.max-hops`
- `reflector.min-requirement-hits`
- `weights.dense`
- `weights.sparse`
- `weights.descDense`
- `rerank.bge-enabled`

### 4.4 ExperimentManifest / ExperimentReport

```java
public record ExperimentManifest(
    String experimentId,
    String baselinePolicyVersion,
    String candidatePolicyVersion,
    String datasetVersion,
    String indexVersion,
    String modelVersion,
    long randomSeed,
    int repetitions,
    Instant createdAt
) {}

public record ExperimentReport(
    String experimentId,
    ExperimentManifest manifest,
    List<CaseResult> cases,
    MetricSummary baseline,
    MetricSummary candidate,
    boolean passedGate
) {}
```

## 5. 存储布局

```text
data/evolution/
├── experiences/
│   └── 2026-08-16.jsonl
├── candidates/
│   └── <candidateId>.json
├── datasets/
│   ├── <datasetVersion>.json
│   └── active.json
└── policies/
    ├── <policyId>-<version>.json
    └── active.json
```

- JSONL 写入采用追加 + 按天轮转；清理按 `retentionDays`。
- 数据集和策略使用临时文件 + 原子替换，保证单实例下不出现半写文件。
- 所有路径校验必须防止目录穿越，参照 `KnowledgeDraftLifecycleService` 的 `below()` 模式。

## 6. 集成点

### 6.1 AgenticOrchestrator

- 新增可选依赖 `ObjectProvider<RetrievalExperienceRecorder>`，避免循环依赖和强制启用。
- `execute()` 内创建 `EvolutionTrace`，逐 hop 记录：
  - 策略名
  - `StrategyResult` 的候选 ID / 排名 / 分数
  - `EvidenceReflector.ReflectionResult`
  - 最终 outcome、warnings、diagnostics、latency
- `finally` 中调用 `recorder.recordAsync(trace.finish())`，Recorder 内部异步，不抛异常到主链路。

### 6.2 RetrievalStrategySelector

- 保留 `RuleBasedRetrievalStrategySelector` 作为默认回退。
- 新增 `PolicyDrivenRetrievalStrategySelector`，从 `RetrievalPolicyRegistry.activePolicy()` 读取规则；策略缺失或解析失败时回退规则版。
- 首期只影响首跳策略名和 `max-hops` 等参数。

### 6.3 EvidenceReflector

- 增加 `minRequirementHits` 可从 `RetrievalPolicy` 阈值覆盖；未配置时保持现有默认值。

### 6.4 RagObservability

- 新增 evolution 相关指标：
  - `nexus.evolution.experience.written`
  - `nexus.evolution.experience.write_failures`
  - `nexus.evolution.experience.dropped`
  - `nexus.evolution.candidates.created`
  - `nexus.evolution.policy.activated`

## 7. 兼容性

- evolution 默认关闭，`AgenticOrchestrator` 现有构造器保持不变。
- 新增字段全部追加在响应/配置中，不删除旧字段。
- 旧配置文件缺少 `evolution` 节点时可启动，默认关闭。
- 策略动态选择只在启用 evolution 且存在 active policy 时生效；否则走原规则选择器。
- 不修改正式评测脚本；Experiment Runner 输出格式与 `RetrievalEvaluationReport` 对齐。

## 8. 回滚

- 所有新增代码位于独立包，可整体回滚。
- 文件型存储无数据库迁移；删除 `data/evolution` 或关闭配置即可恢复原行为。
- 策略激活引用损坏时回退到内置规则基线，不阻塞检索。
