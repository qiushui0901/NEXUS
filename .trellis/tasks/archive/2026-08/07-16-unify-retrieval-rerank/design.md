# Design: 统一检索与重排

## Delivery phases

1. **Evaluation foundation and old-pipeline baseline**：定义 JSONL 合同、首批 10 条 Gold、默认校验测试、可选在线运行器和报告。
2. **Unified RetrievalPipeline**：引入统一请求、profile、bundle、阶段执行与 outcome 聚合，不改变底层召回实现。
3. **Consumer migration**：依次迁移同步开发方案、SSE 开发方案和需求评审。
4. **Dataset expansion and comparison**：扩展到约 50 条，比较重构前后指标与逐条回退。

## Evaluation contracts

建议包：

```text
src/test/java/com/example/requirementrag/evaluation/
├── RetrievalEvaluationCase.java
├── RetrievalEvaluationDataset.java
├── RetrievalEvaluationMatcher.java
├── RetrievalEvaluationReport.java
└── RetrievalEvaluationTest.java
```

数据文件：

```text
src/test/resources/evaluation/retrieval-eval-v1.jsonl
```

核心记录：

- `id`：稳定、小写、类别前缀的唯一 ID。
- `query`：真实用户问题。
- `profile`：`DEVELOPMENT_PLAN`、`REQUIREMENT_REVIEW` 或 `CODE_SEARCH`。
- `projectId`、`documentId`、`version`：运行上下文。
- `expectedOutcome`：`HIT` 或 `NO_RESULTS`。
- `goldDocuments`：稳定文档标签。
- `goldCode`：稳定代码标签。
- `tags`：用于按类型切片统计。
- `notes`：人工确认的简短业务答案或已知基线缺陷，不参与匹配。

JSONL 由 Jackson 逐行读取。错误必须包含行号和稳定、无敏感信息的原因。默认测试只验证本地资源和纯匹配逻辑。

## Matching rules

### Document

候选 filename 必须与 Gold filename 一致；如果 Gold 指定 `parentOrder`，候选也必须相等；每个 `mustContain` 片段都必须出现在规范化后的候选文本中。

### Code

`projectId`、规范化后的 `filePath` 和 `symbolName` 必须匹配。路径统一使用 `/`，不依赖绝对仓库路径。

### Metrics

- Recall@10：至少一个对应 Gold 在前 10 命中。
- MRR@10：第一个正确命中的倒数排名。
- Mixed both-hit：同时包含文档和代码 Gold 的样例，两侧均命中才算成功。
- No-result accuracy：NO_RESULTS 样例没有可回答该问题的候选才算成功；首版在线适配器允许记录当前实现的假命中并由人工 Gold 判定失败。
- Latency：逐条总耗时，报告 P50/P95；10 条样本同时显示原始毫秒值。

## Online runner

在线运行器作为显式启用的集成测试或独立测试入口：

```bash
RUN_RETRIEVAL_EVAL=true \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./mvnw -Dtest=RetrievalEvaluationIT test
```

它调用当前 Spring Bean，而不是通过外部 HTTP，分别使用现有 `QdrantHybridStore` 和 `CodeKnowledgeService` 收集证据，避免生成模型影响检索基线。依赖不可用时应明确失败，不能写成零命中成功报告。

报告：

```text
target/retrieval-evaluation/report.json
target/retrieval-evaluation/report.md
```

## Future RetrievalPipeline boundary

```text
src/main/java/com/example/requirementrag/retrieval/pipeline/
├── RetrievalPipeline.java
├── RetrievalRequest.java
├── RetrievalBundle.java
├── RetrievalProfile.java
├── RetrievalOptions.java
└── RetrievalStage.java
```

`RetrievalPipeline.execute(RetrievalRequest)` 返回 `RagOutcome<RetrievalBundle>`。同步与 SSE 消费者只负责输出形态，不再各自复制路由和召回聚合。

## Compatibility and rollback

- 第一阶段只增加测试侧评测设施，不修改生产检索行为。
- 在线报告位于 `target/`，不会进入 Git。
- 管线迁移按消费者逐个进行；任一步可回退到原服务编排，Gold Dataset 与基线报告不受影响。
