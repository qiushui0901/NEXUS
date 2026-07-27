# Design: 统一知识冲突检测

## Boundary

新增 `com.example.requirementrag.conflict` 作为唯一冲突领域所有者：

- `KnowledgeConflictModels`：声明、证据引用、冲突、报告、请求以及枚举。
- `KnowledgeConflictService`：规范化、去重、版本污染检测、同事实键冲突检测和报告汇总。
- `KnowledgeConflictController`：权限保护的即时分析接口。

RAG 服务仅把已经检索到的需求/代码证据映射为来源声明并调用该服务，不复制检测规则。Wiki 后续可直接提交结构化声明调用同一服务。

## Data Flow

```text
Structured claims / retrieval evidence
  -> normalize and validate
  -> deduplicate by source + evidenceId + factKey + normalizedValue
  -> detect target-version mismatch
  -> group by project + version + factKey
  -> compare values and source combinations
  -> classify severity/type
  -> return report without mutating evidence
```

## Contracts

- `SourceType`: REQUIREMENT, CODE, TEST, WIKI
- `Authority`: PRIMARY, DERIVED
- `ConflictType`: REQUIREMENT_CODE, REQUIREMENT_TEST, CODE_TEST, WIKI_PRIMARY, SOURCE_INTERNAL, VERSION_CONTAMINATION, WIKI_MISSING_PRIMARY_EVIDENCE
- `Severity`: INFO, WARNING, ERROR, BLOCKING
- `ResolutionStatus`: OPEN (first version only)
- `ReportStatus`: CLEAR, REVIEW_REQUIRED, BLOCKED

`KnowledgeClaim` uses a caller-provided stable `factKey`; conflict service does not infer semantic equivalence from free text. Evidence excerpts are bounded before returning.

## RAG Integration

`DevelopmentPlanResponse` adds a trailing `conflictReport` field. A compatibility constructor preserving the previous parameter list delegates with an empty report.

The development-plan retrieval integration creates evidence-presence/version claims only. It can safely detect version contamination and missing source categories, but it must not claim semantic requirement-code conflicts without structured facts.

## Compatibility and Safety

- JSON response only adds fields.
- Empty or absent claim lists produce `CLEAR` rather than an exception.
- Blank required claim fields are ignored and reported as warnings, not echoed unsafely.
- Excerpts are bounded and normalized.
- No persistence and no vector payload serialization.
