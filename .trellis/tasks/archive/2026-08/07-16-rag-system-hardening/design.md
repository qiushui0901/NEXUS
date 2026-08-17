# RAG 系统短板治理技术设计

## 目标

在保持现有 REST、权限和 SSE 兼容的前提下，把需求 RAG、代码 RAG、错误降级、证据引用和大文档处理拆成可替换的边界，并用固定评测集验证每次切换。

## 边界与组件

### 1. 评测层

新增脱敏 JSONL 评测集和离线 runner。runner 通过可替换的检索接口执行旧管线和新管线，输出 Recall@K、MRR/nDCG、no-answer、延迟、token/调用次数。评测集只保存查询、证据 ID、类别和 `no_answer`，不保存原文。

### 2. 统一检索层

新增统一管线接口，概念签名为：

```text
RetrievalOutcome retrieve(RetrievalRequest request, RetrievalProfile profile)
```

管线内部固定顺序为：查询改写 → Dense/Sparse 召回 → RRF → 父块恢复 → 可选 BGE/LLM 重排 → 证据登记 → 上下文预算。`RetrievalProfile` 控制需求评审、开发方案和代码检索的 TopK、重排层级和上下文预算，不改变 Qdrant payload 读取格式。

### 3. 错误与降级

所有阶段返回结构化状态：`SUCCESS`、`NO_RESULTS`、`DEGRADED`、`FAILED`。非关键重排器失败时保留可用候选并添加 warning；Qdrant、Embedding、路由或生成等核心依赖失败且没有证据时返回 502/503。SSE 新增 `warning` 事件，保留既有 `retrieval`、`references`、`completed` 和 `error` 事件。

### 4. 证据模型

统一 `EvidenceRef`：需求证据保存 project/collection、documentId、version、filename、parentId、excerpt；代码证据保存 projectId、commitSha、filePath、symbol、行号、chunkId。每次检索建立允许引用的证据 ID 白名单，模型输出只接受白名单中的 `evidenceIds`。同步和 SSE 使用同一引用结构，末尾保留完整 references。

### 5. AST 索引迁移

引入 JavaParser + Symbol Solver 作为 Java 符号解析器。旧正则解析和 AST 影子运行并比较符号、范围和关系；AST 结果写入版本化 collection（例如 `code_chunks_v2`），通过评测和索引一致性检查后切换 ProjectRegistry 配置，旧 collection 保留回滚。

### 6. 大文档 Map-Reduce

小文档继续使用快速路径。超过上下文预算时，按文件路径模块优先分组；无模块目录时按父块顺序批次分组。Map 阶段每批产生候选问题和证据，Reduce 阶段做问题归一化、证据合并、模块排序和最终数量限制。每批记录状态、覆盖范围、重试和失败原因。

## 数据流

```text
用户问题
  → QueryRouter
  → RetrievalPipeline(profile)
  → RetrievalOutcome + EvidenceRegistry
  → Plan/Review Prompt Builder
  → schema 校验的 LLM 输出
  → evidenceIds 白名单校验
  → 同步 JSON 或 SSE 事件
```

## 兼容性

- 保留现有 `/api/requirements/*`、`/api/assistant/*`、`/api/code/*` 路径和权限注解。
- 现有响应字段继续保留；新增 `status`、`warnings`、`evidenceIds` 为可选字段。
- 旧 collection 和旧 CodeChunk payload 可读；新索引通过 collection 配置切换。
- 前端非 2xx 仍按现有逻辑处理，SSE 新增事件不会影响未知事件的忽略逻辑。

## 回滚与观测

- 每个阶段独立开关，默认关闭新管线，评测通过后再打开。
- AST 切换保留旧 collection；Map-Reduce 失败时回退小文档/原有单批路径。
- 每阶段记录 stage、status、durationMs、candidateCount、evidenceCount、warningCode 和 modelCalls。
- 任何阶段不得把依赖异常转换成普通零命中。

## 关键取舍

- 先做相对基线而不是绝对质量阈值：Recall@10、MRR/nDCG 回退不得超过 5%。
- 采用条目级证据引用而不是首期强制自然语言行内编号，保证模型结构化输出稳定。
- 采用 JavaParser 而不是 Eclipse JDT，降低依赖和迁移复杂度。
