# Design: 自动版本知识构建与统一证据检索

## 1. RetrievalPipeline

新增包 `com.example.requirementrag.retrieval.pipeline`：

```text
RetrievalProfile
RetrievalRequest
RetrievalBundle
RetrievalPipeline
```

`RetrievalPipeline.execute` 返回 `RagOutcome<RetrievalBundle>`。外层 outcome 是唯一的状态、警告和诊断载体；bundle 只保存解析后的请求上下文以及需求、代码证据。

执行顺序：

1. 规范化 document/version/limit。
2. 使用显式 projectId 或 `QueryRouter` 完成路由。
3. 按 profile 调用需求与代码召回。
4. 需求按 `parentId` 去重，代码按 `id`（缺失时按路径+符号+行号）去重。
5. 合并阶段 diagnostics/warnings。
6. 有 warning 且仍有证据返回 `DEGRADED`；无 warning 且无证据返回 `NO_RESULTS`；无证据且核心阶段失败抛 `RagUnavailableException`。

本次迁移同步和 SSE 开发方案。两者仍分别负责最终 LLM 调用与输出格式，但不再复制检索编排。

## 2. VersionKnowledgeBuildPipeline

新增包 `com.example.requirementrag.knowledge.build`：

```text
KnowledgeBuildModels
VersionKnowledgeBuildPipeline
KnowledgeBuildController
```

构建算法：

1. 校验项目和安全标识。
2. `scrollVersion` 读取目标版本全部 payload；若指定 baseVersion，同样读取基线 payload。
3. 以 `contentHash` 为主、规范化父块文本哈希为回退，识别目标版本新增/变化父块。
4. 按 filename 分组，每个源文件生成一个候选功能；featureId 使用文件名中的安全英文 token，无法得到可读 token 时使用稳定 SHA-256 短 ID。
5. 对每个候选调用 `RetrievalPipeline(WIKI_BUILD)` 补充代码命中；原始变化父块始终作为主要需求证据。
6. 生成 `FeatureFactDraft` 和可供现有生成器审核后采用的 `WikiModels.VersionSource` 草稿。
7. 写入随机 staging 目录，完成后原子移动到 `data/wiki-drafts/<project>/<version>/<buildId>`。

所有生成页面状态固定为 `DRAFT`。需求摘录只表示“发现了来源”，不表示业务规则已人工核验；代码命中只表示候选关联。测试点为待实现建议，因此第一版 `missingTests` 等于草稿功能数。

## 3. Storage and security

- `WikiProperties` 增加 `draftPath`，默认 `data/wiki-drafts`，保留双参数构造器兼容现有测试。
- 所有目录段经 `WikiPathPolicy.identifier` 校验。
- 草稿序列化前后均执行禁用字段扫描。
- 草稿证据只保存文本摘录、文件名、版本、代码路径和符号，不保存 point ID、向量或底层异常。
- 正式 source root 和 published root 在构建流程中只读/不访问。

## 4. API

```http
POST /api/knowledge/build
Content-Type: application/json
```

请求：

```json
{
  "projectId": "immortal-game-service",
  "version": "5.1",
  "baseVersion": "5.0.2",
  "documentId": "fengshen",
  "baseCodeCommit": "...",
  "codeCommit": "..."
}
```

返回 `BuildResult`：buildId、status、功能数、冲突数、缺代码数、缺测试数、草稿目录、生成时间、warnings。

## 5. Compatibility and rollback

- Controller 是新增 API，不改变现有 Wiki API。
- 草稿不会自动发布，删除某个 build 目录即可回滚构建结果。
- RetrievalPipeline 迁移只改变内部编排，DevelopmentPlanResponse 和 SSE event 合同不变。
