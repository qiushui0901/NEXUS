# Qdrant 安全索引发布与完整性审计设计

## 背景

当前代码索引会直接写入配置的 `code_chunks` 集合。自动健康检查和手动索引可以并发执行，且发布过程没有版本隔离、快照保护或发布前完整性门禁。最近一次 AST v2 重建覆盖了原有代码索引，同时两个任务并发提交了不同数量的 chunk，暴露出以下风险：

- 活动集合在重建期间可能处于不完整状态；
- 新索引失败时无法快速回滚；
- 自动重建和手动重建会互相覆盖；
- 日志中的提交数量与 Qdrant 最终唯一点数不一致时缺少诊断；
- 当前集合没有快照，误覆盖后无法恢复。

## 目标

1. 为当前正式集合立即建立可恢复快照。
2. 使用版本化物理集合和稳定 Alias 发布代码索引。
3. 同一项目同一时间只允许一个全量索引任务。
4. 发布前执行可机器判定的 AST 索引完整性审计。
5. 发布失败时保持当前活动集合不变。
6. 每个项目保留最近两个已发布代码索引版本。

## 非目标

- 不实现跨 Qdrant 集群的数据复制。
- 不引入外部分布式锁服务。
- 不重新设计代码扫描、语义标注或检索排序算法。
- 不自动删除审计失败的构建集合；失败集合保留用于诊断。
- 不把 `code_chunks_v2_temp` 视为正式版本。

## 方案选择

采用“版本化物理集合 + 稳定 Alias”。固定蓝绿集合不利于版本审计；原地覆盖即使增加快照，仍会向在线集合暴露中间状态。

默认代码 Alias 为 `<基础集合>_active`。以基础集合 `code_chunks` 为例：

```text
code_chunks                       # 迁移前现有物理集合，作为首个保留版本
code_chunks_20260721_153000_ab12  # 新构建物理集合
code_chunks_active                # 检索与健康检查使用的稳定 Alias
```

版本名包含 UTC/本地时间戳和短随机后缀，避免同秒并发任务重名。Alias 初始指向现有 `code_chunks`，后续仅在审计成功后切换。

## 组件边界

### `CodeIndexCoordinator`

负责项目级索引互斥和任务状态，不负责扫描或 Qdrant 协议。

- 锁键为 `projectId`；
- 使用进程内并发映射和 `ReentrantLock`；
- 手动重复请求快速失败并映射为 HTTP 409；
- `DataHealthChecker` 遇到正在运行的任务时记录并跳过；
- 无论成功或失败都必须在 `finally` 中释放锁；
- 暴露当前阶段、目标集合、开始时间和最近结果供监控读取。

当前部署为单应用实例，因此进程内锁满足范围要求。若未来部署多个写入实例，需要把锁升级为外部租约；本次不引入该复杂度。

### `CodeIndexPublisher`

负责版本化集合的构建和发布编排：

1. 解析基础集合和活动 Alias；
2. 保证 Alias 已指向当前正式集合；
3. 创建新的版本化物理集合；
4. 从活动集合读取语义标注缓存；
5. 将完整新索引写入构建集合；
6. 调用完整性审计；
7. 为旧活动集合创建切换前快照；
8. 原子切换 Alias；
9. 为新活动集合创建发布快照；
10. 清理超过最近两个已发布版本的旧物理集合。

发布器不直接实现扫描、标注和 embedding，而是编排现有 `JavaAstCodeScanner`、`CodeSemanticAnnotator` 和 `CodeQdrantStore`。

### `CodeIndexIntegrityAuditor`

输入扫描结果、准备写入的 chunks、目标集合和项目 ID，输出结构化 `CodeIndexAuditReport`。

强制发布门禁：

- 内存中 chunk ID 数量等于 chunk 数量，不允许重复 ID；
- Qdrant 对项目的精确点数等于预期唯一 ID 数；
- 每个 chunk 的 `symbolType` 只能是 `class` 或 `method`；
- `symbolName`、`className`、`filePath` 非空；
- `startLine > 0` 且 `endLine >= startLine`；
- AST 必填字段覆盖率为 100%；
- 扫描到的每个成功解析文件至少产生一个 chunk；
- 集合 schema 同时包含 `code_dense`、`desc_dense` 和 `sparse`；
- 随机/确定性抽样点同时包含两种稠密向量和稀疏向量。

质量告警但不阻止发布：

- `businessDescCn` 或 `businessDescEn` 非空覆盖率低于 95%；
- `callRelation` 覆盖率相较上一个已发布版本显著下降；
- 某个文件产生异常多的 method chunk。

门禁失败时报告必须包含失败项、实际值、期望值、最多 20 个示例 ID/文件，并禁止 Alias 切换。

### `QdrantSnapshotService`

封装集合快照 API：

- 创建快照并返回名称、大小、创建时间和校验状态；
- 查询集合快照；
- 发布流程中快照失败视为发布失败，不切换 Alias；
- 当前数据的首次快照作为独立运维步骤执行；
- 不在应用中自动恢复快照，恢复仍需显式运维操作。

## 数据流

```text
索引请求
  → CodeIndexCoordinator 获取项目锁
  → AST 扫描
  → 从 code_chunks_active 读取标注缓存
  → 语义标注
  → 创建版本化构建集合
  → 生成双稠密向量和稀疏向量并全量写入
  → CodeIndexIntegrityAuditor
      ├─ 失败：保留旧 Alias，记录报告，保留失败集合
      └─ 成功：旧集合快照 → Alias 原子切换 → 新集合快照
  → 仅保留最近两个已发布版本
  → 释放项目锁
```

标注缓存从活动集合读取，只复用 `contentHash/enrichedHash` 匹配的 LLM 标注结果。目标集合是新集合，因此 embedding 会重新生成，确保新集合自身完整，不依赖旧集合的点。

## Alias 迁移与兼容性

新增配置：

```yaml
rag:
  code:
    collection: code_chunks
    active-alias: code_chunks_active
    retained-versions: 2
    snapshot-before-publish: true
```

兼容规则：

- 如果 Alias 不存在且基础集合存在，首次发布前创建 Alias 指向基础集合；
- 检索、计数、健康检查默认通过 Alias 访问；
- 构建、审计和清理只操作物理集合；
- 多项目配置使用各自基础集合派生 Alias，避免项目间切换互相影响；
- 旧配置未提供 `active-alias` 时使用 `<collection>_active`；
- 不删除现有 `code_chunks`，它作为迁移后的首个可回滚版本保留。

## 并发与错误处理

- 项目锁获取失败：手动接口返回 409，响应包含当前任务阶段和开始时间；
- 自动健康检查获取失败：记录 `SKIPPED_ALREADY_RUNNING`，不启动第二个线程；
- 扫描、标注、写入或审计失败：Alias 不变，任务标记失败；
- 旧集合快照失败：Alias 不变；
- Alias 切换使用一次 Qdrant aliases update 请求同时删除旧映射和创建新映射；
- Alias 切换成功但新快照失败：活动版本保持新集合，任务标记 `PUBLISHED_WITH_SNAPSHOT_ERROR` 并禁止清理旧版本，以保留回滚路径；
- 清理失败不回滚发布，只记录告警；
- 任意异常都释放项目锁。

## 快照操作

代码改动开始前，显式为以下集合创建快照并校验快照列表：

- `code_chunks`
- `requirement_chunks`
- `requirement_chunks_v2`

快照名称由 Qdrant 返回并记录到变更日志或操作记录。`code_chunks_v2_temp` 不创建正式快照。

## 版本保留策略

- 每个项目保留最近两个已成功发布的物理集合；
- 当前 Alias 指向的集合永不删除；
- 上一个已发布集合作为快速回滚版本保留；
- 基础迁移集合 `code_chunks` 参与“最近两个版本”计算，但只有在已有两个更新的成功版本且其快照可用时才允许删除；
- 构建失败集合不自动删除，也不计入两个正式版本，需要人工确认后清理。

## 可观测性

监控状态至少包含：

- `projectId`
- `runId`
- `phase`：`SCANNING`、`ANNOTATING`、`WRITING`、`AUDITING`、`SNAPSHOTTING`、`SWITCHING_ALIAS`、`COMPLETED`、`FAILED`
- `sourceCollection`
- `targetCollection`
- `expectedChunks`
- `actualPoints`
- `duplicateIds`
- `astCoverage`
- `descriptionCoverage`
- `startedAt` / `finishedAt`
- `published`
- `snapshotNames`
- `failures` / `warnings`

日志必须携带 `projectId`、`runId` 和目标集合，避免多个索引任务的日志混淆。

## 测试策略

采用测试驱动开发：

1. `CodeIndexCoordinatorTest`
   - 同项目第二个任务被拒绝；
   - 不同项目可以并行；
   - 异常后锁被释放；
   - 健康检查重复任务被跳过。
2. `CodeIndexIntegrityAuditorTest`
   - 重复 ID 阻止发布；
   - 点数不一致阻止发布；
   - AST 字段缺失阻止发布；
   - schema 缺少命名向量阻止发布；
   - 描述覆盖率不足只产生告警；
   - 合格报告允许发布。
3. `CodeIndexPublisherTest`
   - 审计失败时 Alias 不变；
   - 旧集合快照失败时 Alias 不变；
   - 成功路径按快照、切换、快照顺序执行；
   - 切换后快照失败时不清理旧集合；
   - 只保留最近两个已发布版本。
4. Qdrant 协议测试
   - Alias 更新请求是单次原子操作；
   - 快照响应正确解析；
   - 精确点数和 schema 检查正确解析。
5. 回归测试
   - 现有代码搜索、健康检查、手动索引和多项目路由仍通过；
   - 全量测试通过后才允许创建提交。

## 验收标准

- 当前三个正式集合均存在至少一个可见快照；
- 手动索引和健康检查无法同时重建同一项目；
- 新索引始终先写入独立物理集合；
- 任一强制审计失败都不会改变活动 Alias；
- 成功发布通过一次原子 Alias 更新生效；
- 发布后可从上一个保留版本快速切回；
- 每个项目最多自动保留两个成功发布版本；
- 审计报告能解释预期 chunk 数与实际唯一点数的差异；
- 现有检索接口不需要感知具体物理版本名。

## 回滚

回滚不重新生成向量，只执行 Alias 原子切换：

1. 选择保留的上一个成功版本；
2. 校验集合状态为 green 且快照存在；
3. 将 `<collection>_active` 原子切回旧集合；
4. 记录回滚操作和原因；
5. 不自动删除失败的新版本。
