# RAG 错误与降级治理

## Goal

区分零命中、可降级故障和核心失败，让 HTTP/SSE 与日志可诊断。

## Requirements

- 定义 SUCCESS、NO_RESULTS、DEGRADED、FAILED。
- 非关键重排失败保留候选并发送 warning；核心失败且无证据返回 502/503。
- SSE 新增 warning 事件，保留现有 retrieval/references/completed/error。
- 记录 stage、原因、耗时和 warning code。

## Acceptance Criteria

- [ ] Qdrant、Embedding、查询改写、BGE、LLM、路由和生成故障各有测试。
- [ ] 真零命中不返回依赖故障状态。
- [ ] 前端现有非 2xx 与 SSE 处理保持兼容。

