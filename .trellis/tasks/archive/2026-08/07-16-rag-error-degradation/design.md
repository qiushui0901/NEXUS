# RAG 错误与降级治理设计

## Contract

新增统一结果语义：

- `SUCCESS`：阶段成功且存在可用结果。
- `NO_RESULTS`：依赖调用成功，但没有命中。
- `DEGRADED`：非关键阶段失败，系统使用可用回退继续。
- `FAILED`：核心阶段失败且没有可用证据，不能继续生成可信结果。

公开 warning 只包含稳定的 `stage`、`code`、安全消息和耗时，不暴露异常原文、内部 URL 或密钥。内部异常继续交给 `RagObservability` 记录。

## Data Flow

1. 路由、文档检索、代码检索分别返回带状态、数据、warnings、阶段耗时的内部 `RagOutcome<T>`。
2. 同步开发方案聚合各阶段：
   - 真零命中为 `NO_RESULTS`；
   - 单侧检索或路由回退为 `DEGRADED`；
   - 文档与代码核心检索均失败且无证据时抛出 RAG unavailable 异常，由 API 映射为 503；
   - 生成成功后在现有响应末尾追加 `status`、`warnings`、`stageDiagnostics`，旧字段不变。
3. SSE 保留现有事件，降级时额外发送 `warning`；核心失败发送现有 `error` 并结束；`retrieval/references/completed` 结构保持兼容。
4. `RagObservability` 增加 outcome/warning 指标与安全阶段事件；监控状态探测失败返回 `DOWN` 而不是伪装 `UP`。

## Compatibility

- REST 路径、请求结构和原响应字段不删除、不改名；只追加字段。
- SSE 现有事件不删除；新客户端可消费 `warning`，旧客户端可忽略未知事件。
- 显式 projectId 路由保持成功；自动路由失败使用默认项目并标记降级。

## Rollback

新模型与接入修改可整体回滚；数据层无迁移。
