# 证据级引用

## Goal

让每个开发环节、风险和存疑问题都能回查到本次检索证据。

## Requirements

- 定义统一 EvidenceRef 和 EvidenceRegistry。
- 文档证据含 project/version/file/parentId/excerpt；代码证据含 project/commit/file/symbol/line/chunkId。
- 同步和 SSE 条目携带 evidenceIds，末尾保留 references。
- 服务端只接受本次检索白名单中的证据 ID。
- 前端将 evidenceIds 渲染为可点击标签。

## Acceptance Criteria

- [ ] 非法证据 ID 被过滤并产生 warning。
- [ ] 同步 JSON 和 SSE 使用同一引用结构。
- [ ] 前端可从标签打开文档摘录或代码行号。

