# 需求存疑 RAG MVP

基于 Spring Boot 4.1、Spring AI 2.0 和 Qdrant 的需求存疑 MVP。上传产品文档后，系统执行降噪、Parent-Child Chunk、去重、Hybrid Search 和双重重排，最终生成最新版本中仍未回答、存在歧义或冲突的原子化存疑。

## 能力边界

- 支持 Tika 可解析的 PDF、DOC/DOCX、PPT/PPTX、HTML、TXT、Markdown。
- 只生成存疑，不生成答案、方案、负责人和风险描述。
- 按 `documentId + version` 隔离版本，评审时仅以指定版本正文为事实源。
- 文档及 Dense/Sparse 向量持久化在 Qdrant，应用重启不会丢失。
- Child 用于高精度检索，命中后回填 Parent，避免上下文残缺。

## 检索链

```text
Tika解析
  → 文本降噪
  → Parent(约2000字符) / Child(约500字符、80字符重叠)
  → SHA-256父块与子块去重
  → Qdrant dense + sparse named vectors
  → Qdrant RRF Hybrid Search Top40
  → BGE reranker Top20
  → Parent去重与扩展
  → LLM reranker Top10
  → 存疑生成与全文答案回查
```

Sparse 检索采用中英文词项哈希向量，Qdrant collection 启用 IDF modifier。Dense 向量由 Spring AI `EmbeddingModel` 生成。

## 启动

要求 Java 21、Maven 3.9+、Qdrant，以及提供 `/rerank` 接口的 BGE reranker 服务。推荐模型为 `BAAI/bge-reranker-v2-m3`。

如果电脑主环境仍需保留 Java 8，不需要修改全局 `JAVA_HOME`。本项目启动脚本会优先通过 `/usr/libexec/java_home -v 21` 查找 Java 21；手动执行 Maven 时也可以只在当前命令指定 Java 21：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn package -DskipTests
```

启动持久化 Qdrant：

```bash
./scripts/start-qdrant.sh
```

`compose.yml` 使用命名卷 `qdrant_data`，删除或重启容器不会删除数据；执行 `docker compose down -v` 才会删除该卷。

复制配置模板，在 `.env` 中填写真实 Token。Spring Boot 会自动读取项目根目录的 `.env`，不需要执行 `source`：

```bash
cp .env.example .env
mvn spring-boot:run
```

本地 Embedding 默认使用 Ollama。首次使用前拉取模型：

```bash
ollama pull bge-m3
```

OpenAI 兼容网关只负责 Chat，不负责 Embedding；产品文档的向量化内容不会发送给 Chat 网关。

两次 LLM 调用会在请求级显式指定模型：`deepseek-v4-flash` 负责第二阶段重排，`claude-sonnet-4.6` 负责最终存疑生成。

## 1. 上传最新版本文档

```bash
curl -X POST http://localhost:8080/api/requirements/documents \
  -F 'file=@产品需求.docx' \
  -F 'version=5.1' \
  -F 'documentId=fengshen'
```

响应：

```json
{"documentId":"fengshen","version":"5.1","chunks":12}
```

## 2. 生成存疑

```bash
curl -X POST http://localhost:8080/api/requirements/reviews \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"fengshen","version":"5.1","module":"同盟"}'
```

响应示例：

```json
{
  "doubts": [
    {
      "module": "同盟",
      "feature": "解除同盟",
      "question": "解除同盟后的冷却时间是多少？",
      "type": "CONFIGURATION",
      "status": "UNANSWERED",
      "sourceLocation": "同盟-解除同盟"
    }
  ]
}
```

## BGE 服务协议

默认调用：

```http
POST ${BGE_RERANK_URL}/rerank
Content-Type: application/json

{"query":"检索目标","texts":["候选1","候选2"],"truncate":true}
```

返回值：

```json
[
  {"index": 1, "score": 0.96},
  {"index": 0, "score": 0.31}
]
```

如服务路径或鉴权不同，可配置 `BGE_RERANK_PATH`、`BGE_RERANK_API_KEY`。

## 生产化注意事项

1. Qdrant 开启 API Key、TLS、快照和异地备份。
2. 为 `documentId`、`version`、`parentId` 建立 payload index。
3. 增加版本覆盖关系，不依赖调用者手动指定最新版本。
4. 将历史人工存疑作为独立 collection，只用于检查角度召回。
5. 对大文档将全文回查改成按模块分批 Map-Reduce，避免上下文截断。
6. 增加离线评测：人工问题召回率、Top 10 采纳率、已有答案重复提问率。

## 链路监控

应用暴露以下端点：

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/metrics
GET /actuator/prometheus
```

Prometheus 配置和告警规则位于 `monitoring/prometheus.yml`、`monitoring/alerts.yml`。同时采集应用 `:8080/actuator/prometheus` 和 Qdrant `:6333/metrics`。

自定义指标：

```text
rag_stage_seconds                  各阶段耗时及 P50/P95/P99
rag_stage_failures_total           各阶段失败次数
rag_stage_items_count/sum          输入、输出、去重和问题数量
rag_events_total                   文档摄取及评审完成次数
gen_ai_client_operation_seconds    Spring AI 模型调用耗时
gen_ai_client_token_usage_total    LLM Token 用量
```

阶段包括：`document.parse`、`text.clean`、`parent_child.chunk`、`content.deduplicate`、`qdrant.upsert`、`qdrant.scroll`、`qdrant.hybrid_search`、`bge.rerank`、`llm.rerank`、`llm.generate`。

控制台默认输出 Logstash JSON，每条阶段日志包含 `stage`、`documentId`、`version`、`status`、`durationMs`。Prometheus 标签不包含 documentId 和 version，避免高基数。

如需将 Trace 发送到 Jaeger、Tempo 或 OpenTelemetry Collector：

```dotenv
OTLP_TRACING_ENABLED=true
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
```
