# RAG 错误与降级治理实施计划

- [x] 新增 `RagOutcomeStatus`、`RagWarning`、`RagStageDiagnostic`、`RagOutcome<T>` 与核心不可用异常。
- [x] 扩展 `QueryRouter`，保留旧 `route` 并新增可诊断路由结果。
- [x] 接入同步 `DevelopmentPlanService`：区分空结果、降级和失败，追加兼容字段。
- [x] 接入 `DevelopmentPlanStreamService`：发送 warning，核心失败发送 error，保留既有事件。
- [x] 扩展 `RagObservability` 指标并修复监控应用状态误报。
- [x] 添加路由、文档/代码检索故障、零命中、SSE warning/error/完成兼容测试。
- [x] 运行 Java 21 下完整 `./mvnw -B verify`。
