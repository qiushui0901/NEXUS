# NEXUS 0.8.5 开发路线

> 目标：在 0.8.4 Module Wiki 闭环和 0.8.1 检索质量基础上，完成一次面向全系统的 RAG 可靠性收口。
>
> 核心结果：检索质量可比较、依赖故障可识别、回答证据可回查、大文档不因截断静默丢失，代码索引具备可回滚升级路径。

## 1. 当前基线

0.8.4 已完成：

- Module Wiki 从事实抽取、Evidence、Claim、质量门到 stale-to-draft 的真实仓库闭环；
- MCP 工具、Wiki 页面和版本差异的结构化交付；
- 统一检索、BGE/LLM 重排、并行召回、缓存和离线评测的基础能力；
- Java 17、Spring Boot 3.4、Spring AI 1.1.2、Qdrant、Tree-sitter、SQLite 符号图；
- 构建、覆盖率和确定性评测已有 CI 基线。

0.8.5 不重复做 Wiki 页面类型扩展，也不提前建设 0.9 的 SSO、PostgreSQL、多仓库管理和完整企业化部署。

当前必须收口的问题：

| 问题 | 影响 | 优先级 |
| --- | --- | --- |
| 需求评审、开发方案、存疑和 MCP 对检索结果的消费契约仍需统一确认 | 同一问题从不同入口可能得到不同召回、排序和降级语义 | P0 |
| 外部依赖故障与真实零命中需要严格区分 | 用户会把 Qdrant/Embedding/BGE 故障误认为知识库没有答案 | P0 |
| 固定评测集和 CI 门禁需要成为默认回归 | 检索改动可能无感知地降低 Recall/MRR | P0 |
| 证据模型需要覆盖同步、SSE、MCP 和前端 | 结论无法稳定回查，入口之间可信度不一致 | P1 |
| 代码索引升级、符号图和影响分析需要可验证回滚 | 错误索引会污染代码检索、Wiki 和 stale 传播 | P1 |
| 大文档处理仍需要批次覆盖和失败可见 | 后部模块可能因上下文截断被静默丢弃 | P1 |

## 2. 版本目标

### 2.1 成功标准

0.8.5 发布候选必须满足：

1. 同一 `RetrievalRequest` 经过不同 `RetrievalProfile`，需求评审、开发方案、存疑和 MCP 复用同一检索管线；
2. 每次检索明确返回 `SUCCESS`、`NO_RESULTS`、`DEGRADED` 或 `FAILED`，依赖故障不得转换为空命中；
3. 固定评测集至少 50 条，默认 CI 检查 Recall@10、MRR/nDCG、no-answer、污染和延迟回归；
4. 同步 JSON、SSE、MCP 和前端使用同一 EvidenceRef / warning 契约；
5. 代码索引升级可以 shadow 对比、版本化发布、验证失败回滚；
6. 超预算文档按批次处理，能报告覆盖模块、批次失败和重试结果；
7. `./mvnw -B verify`、离线评测和契约测试在无外部模型依赖时可重复执行；
8. 现有 REST 路径、权限、旧响应字段和已发布 Wiki 读取行为保持兼容。

### 2.2 不包含

- 不新增 Overview/API/Data/Version 页面类型；
- 不把模型生成内容自动发布为正式知识；
- 不引入 PostgreSQL、SSO、LDAP 或新的企业身份系统；
- 不在本版本完成远程仓库托管、凭据管理和多仓自动同步；
- 不为了统一而重写已有 Qdrant payload 或破坏旧索引读取；
- 不将真实 Qdrant、Embedding、BGE、LLM 服务作为默认 CI 前置条件。

## 3. 实施阶段

### Phase 0：冻结基线和契约

目标：先让后续每次变更都有可比较的事实。

工作项：

- 扩展脱敏 JSONL 评测集至至少 50 条，覆盖正常查询、中文别名、精确配置、跨模块、代码定位、无答案、版本/项目污染和依赖降级；
- 同一语料分别记录旧管线和当前管线的 Recall@10、MRR/nDCG、no-answer、延迟、模型调用次数；
- 固定 `RetrievalOutcome`、EvidenceRef、DegradationWarning 和阶段 timing 的 JSON 契约；
- 为每次评测写入数据、代码、配置和模型指纹，不记录密钥或私有原文；
- 将评测 runner 接入 Maven profile 或单独的一条 CI 命令。

验收：

- 一条命令输出 JSON 评测报告；
- 相同输入可重复得到相同排序和指标；
- 评测集不包含源码片段、业务原文或凭据；
- 基线报告进入 `target/`，不把临时真实业务数据提交到 Git。

### Phase 1：统一检索与重排

目标：所有面向用户和 Agent 的检索入口共享一条可诊断管线。

工作项：

- 统一查询改写、Dense/Sparse 混合召回、RRF、父块恢复、BGE/LLM 重排、上下文预算和 Evidence 注册；
- 需求评审、开发方案、存疑评审、Wiki 构建和 MCP 只通过 profile 表达差异；
- 保留代码检索的源码向量、业务描述向量、稀疏向量和关键词召回；
- 并行执行互不依赖的需求、版本语料和代码召回；
- 为缓存键纳入 `projectId`、`version`、索引版本、profile/config fingerprint 和查询规范化结果；
- 保留可关闭的 rerank、cache 和 parallel 开关，支持回滚对照。

验收：

- 入口层不再复制召回、重排或证据拼装逻辑；
- 固定评测集质量相对基线无超过 5% 的回退；
- P95 受控并行链路相对串行基线不恶化，目标降低至少 30%；
- 缓存命中不会跨项目、版本、索引或配置污染；
- 每个 profile 都有独立行为测试。

### Phase 2：错误、降级和超时治理

目标：系统能明确告诉调用方“没搜到”还是“搜不了”。

工作项：

- 为 routing、query rewrite、embedding、Qdrant、BGE、LLM rerank、generation 定义稳定 warning code；
- 清理静默 `catch`、无日志 `ignored` 和空集合降级路径；
- 为 BGE、Embedding、LLM、Qdrant 设置连接、读取、总预算和取消策略；
- 核心依赖失败且没有可用证据时返回 502/503；
- 非关键重排器失败时返回候选并标记 `DEGRADED`；
- 真正零命中返回 2xx + `NO_RESULTS`；
- SSE 增加 `warning` 事件，核心失败发送 `error` 后结束；
- MCP 和 REST 返回同一 warning/status 语义。

验收：

- 模拟每个外部依赖失败，响应都能定位阶段和原因；
- 无结果和依赖异常在 HTTP、SSE、MCP 三个入口可区分；
- 超时测试不会留下未取消的任务或线程；
- 日志包含 project、version、request correlation id、stage、status、durationMs，不包含密钥和源码全文。

### Phase 3：证据闭环

目标：所有关键结论都能从统一证据注册表回查。

工作项：

- 统一需求、代码、测试和 Wiki 的 EvidenceRef 字段；
- 同步响应、SSE section、MCP 工具和前端引用展示复用同一模型；
- 服务端校验模型输出的 evidenceIds，只允许引用本次请求白名单；
- 代码证据至少包含 project、commit、relative file path、symbol、line range、chunk id；
- 需求证据至少包含 project、collection、document、version、filename、parent id 和 excerpt 定位；
- 无效引用被丢弃并生成 warning，不阻断仍可用的其他结果；
- Module Wiki 的需求证据接入作为本阶段的跨域验收样例。

验收：

- 每个生成的结构化 section 都能回查至少一个合法 EvidenceRef；
- 非法、跨项目、跨版本、跨 commit 引用均无法出现在最终响应；
- 前端点击引用可定位到仓库相对源码或需求文档；
- 同步和 SSE 对同一请求返回等价 evidence 集合。

### Phase 4：代码索引可靠性和影响分析

目标：代码检索、源码回查、Wiki stale 和影响分析共享可靠的符号事实。

工作项：

- 完成 Java AST shadow scanner，覆盖 record、重载、嵌套类型、注解、继承、实现和构造器；
- 对旧解析器与 AST 结果做符号、范围、关系差异报告；
- 使用版本化 collection 或索引版本发布 AST 结果，验证通过后再切换；
- 旧 collection 和旧解析结果保留可回滚窗口；
- 统一多语言 scanner 注册和扩展名映射；
- 影响分析输出静态关系、启发式关系和未解析关系的置信度；
- 索引任务支持取消、重试、并发互斥和失败后保留旧版本。

验收：

- Java fixture 覆盖全部结构场景；
- 至少 Java、Go、Python、TypeScript 代码索引和回查通过；
- 索引发布失败不会破坏当前 live collection；
- 给定 commit diff 能输出受影响符号、入口和建议回归范围；
- 图谱缺失时明确降级到文件级，不伪造符号影响。

### Phase 5：大文档 Map-Reduce

目标：大版本评审覆盖全部文档模块，单批失败可见且不静默丢失。

工作项：

- 小文档保留预算内快速路径；
- 超预算文档优先按路径模块分组，无模块时按 parentOrder 批处理；
- Map 阶段生成结构化候选和 evidenceIds，并记录覆盖范围；
- Reduce 阶段去重、合并证据、按模块排序和限制最终结果；
- 每批具备超时、有限重试、取消和失败状态；
- 部分失败返回 `DEGRADED` 和未覆盖模块；
- 全部失败遵循核心依赖失败契约；
- 将模块覆盖率和批次失败信息暴露给 REST、SSE 和 MCP。

验收：

- 大文档后部模块不会因字符截断而消失；
- 单批失败有明确 warning 和覆盖报告；
- Reduce 不重复输出同一问题或同一证据；
- 小文档性能不因引入 Map-Reduce 明显回退。

### Phase 6：全链路发布门

目标：让 0.8.5 的质量要求成为默认工程门禁。

工作项：

- 运行全量单元、契约、集成和离线评测；
- CI 固定执行 Enforcer、测试、JaCoCo、`git diff --check`、质量评测和依赖扫描；
- 报告代码版本、评测集指纹、索引版本、模型配置和运行时信息；
- 检查 REST、SSE、MCP、Wiki、权限、缓存隔离和降级契约；
- 为统一管线、索引迁移、Map-Reduce 保留独立回滚开关；
- 更新 README、用户指南、MCP 文档和运维排障说明。

发布门：

- `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -B verify` 通过；
- 离线评测所有 acceptance checks 通过；
- Recall@10、MRR/nDCG 不得相对基线回退超过 5%；
- no-answer 查询不出现明显虚构命中增长；
- 核心依赖失败、部分降级、真实零命中三类契约测试全部通过；
- 不提交私有文档、向量、Qdrant storage、凭据或临时业务数据。

## 4. 交付顺序

建议按以下顺序拆分 Trellis 子任务，每个子任务独立提交和验证：

1. 评测基线与统一检索管线；
2. 错误、降级、超时和取消治理；
3. 证据模型跨 REST/SSE/MCP/前端统一；
4. Java AST shadow 与索引发布回滚；
5. 大文档 Map-Reduce；
6. 全链路质量门和文档收口。

每个子任务必须保留：

- 设计说明和影响范围；
- 聚焦测试；
- 可回滚配置或独立提交；
- 评测前后对比报告；
- changelog 条目。

## 5. 后续版本边界

0.8.5 完成后再进入 0.9 企业化：

- Docker/Compose 一键部署和镜像发布；
- PostgreSQL 元数据与审核状态存储；
- SSO、Key 轮转、吊销和访问审计；
- 用户/项目限流与配额；
- 远程仓库注册、凭据和自动同步；
- 多副本部署、备份恢复和 SLO。

0.8.5 不以“页面类型更多”为完成标准，而以“同一事实在所有入口可检索、可诊断、可回查、可回归”为完成标准。
