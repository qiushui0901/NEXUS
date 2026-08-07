# NEXUS 0.8 后续改进计划

> 基线：当前 `0.8.0-SNAPSHOT` 代码
> 目标：在不继续扩张功能面的前提下，提高数据完整性、任务可靠性、检索质量、运行效率和交付体验
> 范围：本文不设计权限、身份、角色、项目授权或访问控制

## 1. 改进原则

1. 先保证已有数据不会因失败或并发写入而损坏，再优化召回质量和生成能力。
2. 优先复用现有 Qdrant、SQLite、线程池、缓存、指标和评测设施，不引入新的中间件。
3. 所有降级都必须可见，不能把依赖失败伪装成“没有结果”。
4. 所有索引发布必须可验证、可回滚，并且不能向在线查询暴露半成品。
5. 模型、分块、稀疏分词或排序参数变化必须通过固定评测集证明没有质量回退。

## 2. 优先级总览

| 优先级 | 改进项 | 主要收益 |
|---|---|---|
| P0 | Qdrant 安全索引发布 | 避免删除后写入失败导致数据丢失或半成品上线 |
| P0 | 后台任务统一治理 | 避免裸线程、公共线程池和重复索引造成资源失控 |
| P1 | 检索质量进入 CI 门禁 | 防止分块、模型、重排和参数修改引入静默质量回退 |
| P1 | 降低模型成本与尾延迟 | 减少无收益的 BGE/LLM 调用，改善 P95 延迟 |
| P1 | 索引 schema 与模型指纹 | 防止 embedding 维度、模型和索引版本不一致 |
| P2 | 增强证据充分性判定 | 让 Agentic 补检依据覆盖度，而不只是“是否命中” |
| P2 | 最小本地部署编排 | 降低 Qdrant、BGE 和应用的启动成本 |
| P2 | 配置档位与测试门槛 | 减少错误配置，提高关键数据路径的回归保障 |

## 3. P0：Qdrant 安全索引发布

### 3.1 当前问题

需求索引的 `QdrantHybridStore.replaceVersion` 当前执行顺序是：

```text
生成新 points -> 删除 documentId + version 的旧 points -> 分批写入新 points
```

删除成功后，只要任意写入批次失败，线上版本就会变成空数据或部分数据。代码索引也需要保证全量重建期间，查询始终读取最后一个完整版本。

仓库已有代码索引安全发布设计：

- `docs/superpowers/specs/2026-07-21-qdrant-safe-index-publishing-design.md`
- `docs/superpowers/plans/2026-07-21-qdrant-safe-index-publishing.md`

后续实现应复用该设计，不再创建第二套代码索引发布协议。

### 3.2 最小实施方案

#### 代码索引

按已有设计完成：

1. 每次全量索引写入新的版本化物理 collection。
2. 写入完成后校验点数、唯一 ID、必填 payload 和 named vector schema。
3. 审计通过后使用一次 Qdrant Alias 更新原子切换活动版本。
4. 保留当前和上一个成功版本。
5. 审计或写入失败时 Alias 保持不变。

#### 需求版本索引

需求版本更新先采用更小的幂等方案：

1. 构建全部新 point，并收集新 point ID 集合。
2. 查询当前 `documentId + version` 的旧 point ID。
3. 先 upsert 全部新 point，所有批次使用 `wait=true`。
4. 校验新 ID 均可读取，且数量与预期一致。
5. 删除只存在于旧集合中的过期 ID。
6. 任一步失败时保留旧 point，不执行清理。

稳定 chunk ID 已由内容哈希生成，因此重复执行 upsert 是幂等的，不需要新增事务数据库。

### 3.3 状态与可观测性

索引任务至少记录：

- `projectId`
- `documentId` 或代码 commit
- `runId`
- `phase`
- `expectedPoints`
- `writtenPoints`
- `verifiedPoints`
- `deletedPoints`
- `startedAt` / `finishedAt`
- `published`
- `failureCode`

建议阶段：

```text
PREPARING -> WRITING -> VERIFYING -> PUBLISHING -> COMPLETED
                                              -> FAILED
```

### 3.4 验收标准

- [ ] 任意写入批次失败时，旧需求版本仍可完整检索。
- [ ] 需求索引重复提交相同内容不会产生重复 point。
- [ ] 代码全量索引失败时活动 Alias 不变。
- [ ] 点数、ID 或 schema 审计失败时禁止发布。
- [ ] 成功发布后可以切回上一个代码索引版本。
- [ ] 故障注入测试覆盖删除失败、部分写入失败、校验失败和 Alias 切换失败。

## 4. P0：后台任务统一治理

### 4.1 当前问题

- GitLab webhook 使用 `new Thread(...)` 启动 daemon 线程。
- 跨项目检索通过未指定 executor 的 `CompletableFuture.supplyAsync` 使用 JVM common pool。
- 索引、检索、模型调用和 Git 操作可能争用线程与 CPU。
- daemon 任务在进程退出时不会等待完成。
- 同一项目连续 push 可能重复执行多个过时索引任务。

### 4.2 实施方案

1. Webhook 统一提交到现有 `CodeIndexJobService`，不直接创建线程。
2. 每个项目同一时间最多运行一个索引任务。
3. 同项目等待中的 push 合并为“当前已发布 commit -> 最新目标 commit”。
4. 任务队列设置固定容量；队列满时返回明确拒绝结果。
5. 跨项目检索显式使用现有有界 retrieval executor。
6. 总超时后取消未完成 future，避免请求结束后继续占用资源。
7. 应用关闭时给正在运行的索引任务有限的优雅退出时间。

暂不引入 Kafka、RabbitMQ 或分布式调度器。当前单实例部署下，有界进程内任务服务足够；只有任务必须跨进程重启恢复时再增加持久化队列。

### 4.3 验收标准

- [ ] 主代码不再为索引任务直接创建 `Thread`。
- [ ] 跨项目检索不使用 `ForkJoinPool.commonPool()`。
- [ ] 同项目两个并发全量索引请求中，第二个被合并或明确拒绝。
- [ ] 不同项目可在配置的并行度内并行索引。
- [ ] 超时后未完成的检索任务被取消。
- [ ] 任务状态可以查询到目标 commit、阶段、排队时间和失败原因。

## 5. P1：检索质量进入 CI 门禁

### 5.1 目标

把现有固定语料、黄金数据集和评测脚本从“可手动运行”提升为每次检索相关修改都必须通过的回归门。

### 5.2 指标

至少固定以下指标：

- Requirement Recall@10
- Code Recall@10
- MRR@10
- File / Section / Child 分层 Recall
- no-result accuracy
- 跨项目污染率
- 跨版本污染率
- 合法引用率
- 无证据断言率
- P50 / P95 检索延迟
- 各阶段降级次数

真实模型评测继续使用显式开关，默认 CI 运行确定性离线评测，避免 CI 依赖本地 Qdrant、Embedding 和 BGE 服务。

### 5.3 变更触发范围

修改以下内容时必须更新或对比评测报告：

- 分块大小和重叠
- 文本清洗
- sparse tokenizer
- embedding 模型
- query expansion
- topK 和候选倍率
- BGE/LLM 重排
- prompt
- 代码语义标注
- 检索缓存键或失效策略

### 5.4 门禁规则

1. Recall 和 MRR 不得低于仓库内固定基线。
2. 跨项目、跨版本污染率必须保持为 0。
3. no-result case 不能通过无关候选“提高召回”。
4. 报告必须记录语料哈希、黄金集哈希、模型、参数和代码 commit。
5. 基线更新必须附带原因和新旧对比，不能只覆盖 JSON 文件。

### 5.5 验收标准

- [ ] 默认 `mvn verify` 执行确定性检索回归。
- [ ] 修改检索参数后，报告能显示相对基线的变化。
- [ ] 任一污染 case 命中错误项目或版本时构建失败。
- [ ] 真实依赖评测输出基础设施失败与质量失败的独立统计。

## 6. P1：降低模型成本与尾延迟

### 6.1 当前链路

典型请求可能依次调用：

```text
Embedding -> Dense/Sparse Search -> BGE Rerank -> LLM Rerank -> Generation
```

不是每个请求都需要完整链路。优化目标是减少“没有改变最终排序”的模型调用，而不是简单关闭重排。

### 6.2 条件执行规则

先实现可解释的确定性规则：

1. 候选为 0 时直接返回 `NO_RESULTS`。
2. 候选为 1 时跳过 BGE 和 LLM 重排，并记录 singleton skip。
3. BGE 后 top1 与后续候选分差超过评测确定的阈值时，跳过 LLM 重排。
4. `REQUIREMENT_REVIEW` 只执行需求分支。
5. 纯代码查询只执行代码分支。
6. 只有需要需求与代码联合证据的开发问题才运行双分支。
7. 任何跳过规则都必须进入阶段诊断和指标。

阈值不得凭经验直接写死，必须从固定评测集推导，并允许通过配置回退到完整链路。

### 6.3 缓存正确性

检索结果缓存键至少包含：

```text
query + projectId + documentId + version + profile
+ collectionGeneration + retrievalFingerprint
```

Embedding 缓存键至少包含：

```text
provider + model + dimension + normalizationVersion + text
```

发布新索引、修改检索参数或更换模型后，旧缓存必须自然失效。

### 6.4 验收标准

- [ ] 固定评测质量不下降。
- [ ] BGE 和 LLM 重排调用次数分别可观测。
- [ ] P95 延迟和每请求平均模型调用数相对基线下降。
- [ ] 新索引发布后不会命中旧 generation 的检索缓存。
- [ ] 更换 embedding 模型后不会复用旧模型向量缓存。

## 7. P1：索引 Schema 与模型指纹

### 7.1 当前问题

Embedding 缓存当前主要以实现类名区分模型。同一个 Spring AI 客户端类可以指向不同模型、网关和向量维度，因此类名不足以作为模型身份。

### 7.2 索引元数据

每个物理 collection 或索引 generation 记录：

- `schemaVersion`
- `embeddingProvider`
- `embeddingModel`
- `denseDimension`
- `embeddingNormalizationVersion`
- `sparseTokenizerVersion`
- `chunkingVersion`
- `annotationPromptVersion`
- `retrievalFingerprint`
- `projectId`
- `sourceVersion` 或 `commitSha`
- `createdAt`

### 7.3 启动与写入检查

1. 首次写入后记录实际向量维度。
2. 后续写入前读取 collection schema 和索引元数据。
3. 模型、维度或 named vector 不匹配时拒绝覆盖现有索引。
4. 提示创建新 generation 并执行安全发布。
5. 检索响应诊断中回显使用的索引 generation 和模型指纹。

### 7.4 验收标准

- [ ] 同一客户端类切换到不同模型时缓存键发生变化。
- [ ] 向量维度不匹配在写入前被发现。
- [ ] sparse tokenizer 版本变化要求重建索引。
- [ ] 每次评测报告可以追溯到具体索引 generation。

## 8. P2：增强 Agentic 证据充分性判定

### 8.1 当前问题

当前反思规则主要依据“核心阶段是否失败”和“需求证据是否至少命中一条”。它无法区分一条高质量证据与多条重复、偏题或只覆盖部分问题的证据。

### 8.2 第一阶段：确定性信号

暂不增加 LLM reflector，先加入以下低成本信号：

- 唯一父块数量
- 唯一来源文件数量
- 需求和代码双侧覆盖情况
- top1 与后续候选分差
- 查询关键词或实体覆盖率
- 重复证据比例
- 所有引用是否通过证据白名单校验
- 是否存在跨版本或跨项目证据

反思结果继续保持有界：

```text
CONFIDENT
INSUFFICIENT
NOT_RETRIEVABLE
```

每个结果增加稳定 reason code，供评测和监控聚合。

### 8.3 第二阶段触发条件

只有在确定性规则无法改善评测结果时，才考虑一次 LLM 反思。若引入，必须满足：

- 固定 JSON Schema
- 最多一次补检
- 总 token 和时间预算
- 失败时回退确定性规则
- 评测证明 Recall、MRR 或引用覆盖率有实际提升

### 8.4 验收标准

- [ ] 重复命中不会被误判为证据充分。
- [ ] 需要双源证据的请求在只命中一侧时触发补检或明确降级。
- [ ] 每次反思都有稳定 reason code。
- [ ] Agentic 循环次数有硬上限。

## 9. P2：最小本地部署编排

### 9.1 目标

让新开发者不需要分别研究 Qdrant、BGE 和 NEXUS 的启动命令，即可运行最小可用环境。

### 9.2 范围

新增一个仅用于本地开发的 Compose 文件，包含：

- NEXUS
- Qdrant
- BGE reranker
- 持久化 volume
- 健康检查
- 明确的端口和环境变量示例

Ollama 作为可选 profile，不要求所有开发者下载本地生成模型。默认仍可连接 OpenAI Compatible 网关。

### 9.3 非目标

- 不把本地 Compose 当作生产部署模板。
- 不引入 Kubernetes manifests。
- 不在 Compose 中保存真实业务文档或密钥。
- 不自动下载大模型到 Git 仓库目录。

### 9.4 验收标准

- [ ] 干净机器按文档可启动应用、Qdrant 和 BGE。
- [ ] 所有容器都有健康检查。
- [ ] volume 重建容器后索引仍存在。
- [ ] 停止 BGE 后 NEXUS 返回可见降级，而不是请求无限挂起。

## 10. P2：配置档位与测试门槛

### 10.1 配置档位

将运行差异收敛到少量 profile：

- `local`：本地 Qdrant/BGE，适合开发和演示
- `evaluation`：固定语料、固定参数、关闭非确定性缓存影响
- `production`：显式外部服务地址、超时、持久化路径和观测配置

本文不涉及这些 profile 的身份或权限配置。

### 10.2 配置校验

为关键配置增加启动期校验：

- URL 合法
- collection 名非空
- topK 均大于 0，且阶段间关系合理
- timeout 大于 0
- cache TTL 与最大条目数非负
- embedding 模型名非空
- repository path 在启用代码索引时存在
- Wiki、快照和图数据库目录可写

错误配置应在启动时失败，不应推迟到首次请求。

### 10.3 测试门槛

保留整体 JaCoCo 门槛，同时提高关键数据路径的分支覆盖：

- Qdrant 安全发布
- 索引任务协调
- 版本发布和回滚
- MCP 响应边界
- SSE 超时、断连和降级
- collection schema 校验

重点补充故障测试，而不是为简单 DTO 增加无价值测试。

### 10.4 验收标准

- [ ] 三个 profile 的用途和启动命令有文档。
- [ ] 关键错误配置在 Spring context 启动阶段失败。
- [ ] Qdrant 写入、发布和回滚均有故障注入测试。
- [ ] SSE 客户端断开后停止上游生成或释放相关资源。
- [ ] `mvn verify` 同时执行覆盖率与确定性检索质量门禁。

## 11. 建议实施顺序

### 阶段一：数据与任务安全

1. 完成代码索引 Alias 安全发布方案。
2. 将需求版本替换改为先写、校验、后清理。
3. Webhook 接入 `CodeIndexJobService`。
4. 跨项目检索改用有界 executor，并实现超时取消。

### 阶段二：质量和效率

1. 固定检索基线并加入 CI。
2. 完善缓存指纹和索引 generation。
3. 增加重排条件跳过规则。
4. 用固定评测验证质量、延迟和模型调用数。

### 阶段三：工程交付

1. 增加本地 Compose。
2. 收敛运行 profile 和配置校验。
3. 提高关键数据路径测试门槛。
4. 根据评测结果增强 Agentic 证据判定。

## 12. 明确不做

本计划暂不实施：

- 权限、身份、角色和项目授权设计
- 新的消息队列
- 新的分布式锁服务
- 新的图数据库
- PostgreSQL 元数据迁移
- 无评测依据的模型替换
- 无限循环或多 Agent 自主规划
- 为本地开发提前建设生产 Kubernetes 平台

这些能力只有在单实例、有界任务和现有存储明确无法满足实际负载时再评估。
