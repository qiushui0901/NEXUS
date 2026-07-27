# Design

## Runtime contract

新增只读 `GET /api/runtime/status`。响应包含平台总状态、服务检查项和每个项目的数据状态。Qdrant、Ollama 为代码检索必需依赖；BGE 为可降级依赖；Wiki 文件不依赖这些服务。

探测使用短连接/读取超时，失败转换为状态字段，不向页面抛出 5xx。项目统计复用 `ProjectRegistry`、`QdrantHybridStore` 和 `CodeQdrantStore`，异常时返回不可用而不是阻塞整个响应。

## Page contract

新增 `home.html`。根路由改为重定向首页，`/monitor` 保持原行为。首页只依赖 runtime status 接口；接口失败时仍展示三个模块入口和故障说明。

## Local process contract

`scripts/nexus.sh` 提供 start/stop/status/restart/logs：
- 加载 `.env`；
- 自动寻找 JDK 21；
- 使用 Maven Wrapper 构建并通过 `target/NEXUS-*.jar` 发现产物；
- 默认 `SERVER_ADDRESS=127.0.0.1`；
- 优先复用已运行的 Qdrant，否则启动 bundled binary；
- 对项目自有的 Qdrant/NEXUS 假存活进程执行受控恢复；其他进程占用端口时失败关闭并报告 PID；
- 启动超时或子进程退出后清理 PID 和新进程，不删除 Qdrant 存储；
- PID 和日志保存在已忽略的 `tools/` 中。

不自动启动或下载 Ollama/BGE，不自动覆盖/迁移向量数据。


## Background code-index contract

新增 `POST /api/code/index/start` 与 `GET /api/code/index/status`。后台任务按项目串行去重，状态仅公开安全消息；同步 `/api/code/index` 保留给脚本和兼容客户端。完整索引先完成扫描与向量生成，再替换项目数据，因此失败不会清空旧索引。工作台复用现有定时刷新轮询状态，不保持超长 HTTP 请求。

Java 方法按嵌入安全长度切分，类级文本只保留有限上下文，避免把整个类和其中每个方法重复向量化。嵌入请求使用受控批次，失败批次二分定位问题输入。
