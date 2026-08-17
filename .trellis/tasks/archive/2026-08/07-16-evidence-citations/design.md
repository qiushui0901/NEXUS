# Technical Design

## Architecture

新增独立 `evidence` 包作为同步接口、SSE 和前端之间唯一的引用契约所有者：

```text
RetrievalBundle
  -> EvidenceRegistry (request-scoped whitelist)
  -> prompt context with evidenceId
  -> model output evidenceIds
  -> EvidenceCitationService validation
  -> sync CitationBundle / SSE enriched payload
  -> monitor.html evidence chips and drawers
```

`EvidenceRegistry` 不持久化，不访问向量库，只将当前检索结果转换为安全、受限、确定性的 `EvidenceRef` 列表和 ID 映射。

## Data Contracts

### EvidenceRef

统一字段：

- `evidenceId`
- `type`: `REQUIREMENT` / `CODE`
- `projectId`
- `version`
- `title`
- `source`
- `location`
- `excerpt`
- `commitSha`
- `startLine`
- `endLine`
- `chunkId`

不适用字段为 `null`。`source` 必须是安全的文件名或仓库相对路径，`excerpt` 必须有长度上限。

### CitedText

- `text`
- `evidenceIds`: 仅包含白名单中的去重 ID
- `supportStatus`: `SUPPORTED` / `PARTIAL` / `UNSUPPORTED`

判定规则：

- 模型请求 ID 非空且全部合法：`SUPPORTED`
- 模型请求 ID 非空但只有部分合法：`PARTIAL`
- 未提供 ID，或提供的 ID 全部非法：`UNSUPPORTED`

### CitationQuality

- `totalClaims`
- `supportedClaims`
- `partialClaims`
- `unsupportedClaims`
- `coverageRate`
- `status`: `VERIFIED` / `REVIEW_REQUIRED` / `INSUFFICIENT_EVIDENCE`

覆盖率按 `(supported + 0.5 * partial) / total` 计算并限制在 0-1。无结论时为 0。

### PlanCitationBundle

作为 `DevelopmentPlanResponse` 的新增尾字段，平行保存摘要、列表项、环节和风险的引用，不替换现有字符串字段，以保持客户端兼容。环节引用使用稳定的列表索引关联，不修改现有 `PlanSection` 构造契约。

## Stable Evidence IDs

优先使用检索块 ID，并加类型命名空间：

- `requirement:<sanitized chunk id>`
- `code:<sanitized chunk id>`

若块 ID 为空，使用关键来源字段的 SHA-256 前 16 位生成确定性后缀。模型只看到注册表生成的 ID，不允许自行构造跨请求引用。

## Validation Ownership

- `EvidenceRegistry`：构建白名单、清洗来源和摘录、查找引用。
- `EvidenceCitationService`：校验模型 ID、产生 `CitedText`、warning 和质量统计。
- `DevelopmentPlanService`：将模型草稿转换为现有响应字段和新增引用包。
- `DevelopmentPlanStreamService`：在发送每个模型事件前调用同一校验服务，并在终端事件返回同一引用结构。
- 浏览器只渲染服务端已验证的数据，不自行判断 ID 合法性。

## Sync Data Flow

1. 检索得到 `RetrievalBundle`。
2. 建立 `EvidenceRegistry`，prompt 中每段证据带稳定 ID。
3. 模型草稿的每个结论返回 `text + evidenceIds`；环节返回环节级 `evidenceIds`。
4. 服务端将草稿文本投影到旧字段，同时构建 `PlanCitationBundle`。
5. 任何缺失/非法引用产生去重 warning；整体响应状态降级但仍返回可用方案。

模型失败使用规则化回退时，不伪造支持关系；不能安全匹配到证据的结论标记为 `UNSUPPORTED`。

## SSE Data Flow

1. 检索后建立同样的注册表，prompt 上下文标记证据 ID。
2. 解析模型事件后，仅对可展示结论类型做引用校验。
3. 事件 payload 新增清洗后的 `evidenceIds` 与 `supportStatus`。
4. `section` 事件先完成引用校验，再执行现有代码目标匹配补充。
5. `references` 事件新增 `evidence` 数组；旧 `documents`/`code` 保留。
6. `completed` 事件合并引用 warning、质量摘要和最终 `DEGRADED` 状态。

允许的模型事件类型保持固定白名单，未知类型丢弃并产生安全 warning，避免模型注入未定义的客户端状态转换。

## Frontend Compatibility

`monitor.html` 的 reducer 同时接受旧字符串和新的 `{text, evidenceIds, supportStatus}` 条目。模板通过统一 helper 读取文本和引用，不使用 `innerHTML`。

需求证据使用只读侧边面板展示来源、位置和受限摘录。代码证据转换为现有 `loadSource` 所需字段，继续调用 `/api/code/source`。

## Warnings

建议稳定代码：

- `INVALID_EVIDENCE_REFERENCE`
- `MISSING_EVIDENCE_REFERENCE`
- `UNKNOWN_PLAN_EVENT_TYPE`

消息不包含模型原始非法 ID、绝对路径或异常文本。warning 在单次响应内按 `code + message` 去重。

## Compatibility and Rollback

- 所有 REST 新字段均为追加字段。
- 所有 SSE 现有事件名称和旧 payload 字段保留。
- 旧前端可忽略新字段；新前端可显示旧流。
- 回滚时可移除新增引用字段和页面组件，不影响检索、冲突报告和原有开发方案主体。
