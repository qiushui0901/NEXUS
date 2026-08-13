# LightRAG × NEXUS 实测对比报告

> 日期：2026-08-11
> 语料：`evaluation/shiguang/shiguang-eval-requirements.md`（脱敏需求，8KB，同一份）
> 查询集：`src/test/resources/evaluation/retrieval-eval-shiguang-v1.jsonl`（54 例冻结集，同一份）
> 嵌入模型：`text-embedding-v4`（同一网关，同一模型，维度 1024）
> LightRAG：`lightrag-hku 1.5.6`，mix 模式，`only_need_context=True`（不调生成 LLM）
> NEXUS：`0.8.1-quality` 变体，54 例 × 3 次重复（官方评测脚本 `run-shiguang-eval.sh`）

## 一、召回指标对比

| 指标 | LightRAG (mix) | NEXUS (0.8.1-quality) | 判定口径 |
|---|---:|---:|---|
| 文档 Recall（全部 54 例） | **0.889**（48/54） | **1.000** | LightRAG：检索返回上下文含 gold 片段；NEXUS：top-10 chunk 含 gold 片段（更严格） |
| 文档 Recall（46 例非空结果类） | **1.000**（46/46） | **1.000** | 排除 empty-result 用例 |
| 空结果判定（8 例 empty-result） | 2/8 正确 | **8/8 正确** | 语料中不存在的内容应返回空 |
| MRR@10 | 未测量（无排序输出） | 0.807 | 仅 NEXUS 可测 |
| 代码检索 | **不支持** | Code Recall@10 = 0.738 | NEXUS 有 Tree-sitter 代码索引 |

结论：**在"内容确实存在于语料中"的查询上，LightRAG 与 NEXUS 的文档召回能力持平（均为满分）**；差异全部集中在结构性能力上（下节）。

## 二、结构性能力差异（LightRAG 缺失）

| 能力 | LightRAG | NEXUS | 实测表现 |
|---|---|---|---|
| 安全降级（empty-result） | 无 | 有 | 6 个"语料中不存在"的查询（量子计算、卫星轨道、其他项目权限等），LightRAG 全部返回无关知识图谱实体（"关注与取消关注"、"搜索服务"等），NEXUS 全部正确返回空 |
| 版本隔离（version-leakage） | 无版本概念 | 有（projectId+version 主键） | 4 例 version-leakage 查询，LightRAG 2 例命中（对"不存在版本中的内容"会检索到其他版本内容） |
| 跨项目隔离（cross-project-contamination） | 无 | 有 | 5 例中 2 例 LightRAG 误命中（检索到本项目内容回答问题） |
| 代码检索 | 无 | 有（Recall@10=0.738） | 42/54 查询含代码 gold，LightRAG 无对应能力 |
| 引用/证据白名单 | 无（返回内容不可定位） | 有（requirement:/code: 编号） | 结构差异，未量化 |

## 三、延迟对比

| 指标 | LightRAG (mix) | NEXUS |
|---|---:|---:|
| 查询 P50 | 2.6s | — |
| 查询 P95 | 4.2s | 报告 P95 = 0（本次运行未记录，无法对比） |
| 纯检索流水线 P95 | — | 112ms（parallel benchmark，含 BGE 重排） |

说明：LightRAG 查询含 LLM 关键词提取 + 图遍历 + 向量检索；NEXUS 纯检索阶段无 LLM 调用。LightRAG 的图构建还需在**索引阶段**为每个 chunk 调用一次 LLM（实体抽取），NEXUS 索引阶段无需 LLM（仅 Tika 解析 + 分块 + 嵌入）。

## 四、对比过程中修复的 NEXUS 缺陷

1. **Qdrant 1.15 兼容 bug**（已修，`retrieval/QdrantHybridStore.java`）：`verifyVersion` 使用 `$point_id + match.any`，Qdrant ≥1.13 拒绝该结构（实测 400）。改为 Qdrant 官方 `has_id` 条件，语义等价。**这是 NEXUS 在 Qdrant 1.15 上无法建索引的阻塞性 bug**（SetupIT 全部失败）。
2. **评测脚本与配置校验漂移**（未修，仅运行时绕过）：`run-shiguang-eval.sh` 设 `RETRIEVAL_CACHE_TTL_SECONDS=-1`，而 `RagConfigValidator` 要求 ≥0。脚本需同步为 0（0=禁用缓存的当前约定）。

## 五、结论与建议

1. **检索能力层面**：LightRAG 不是 NEXUS 的对手，是替代品候选——文档召回质量持平，但缺少 NEXUS 的全部产品化能力（安全降级、版本/项目隔离、代码检索、证据白名单、MCP）。
2. **可借鉴点**：LightRAG 的知识图谱（实体-关系）对"跨文档关系查询"（如"X 功能依赖哪些模块"）有增量价值；若 NEXUS 需要该能力，可在现有检索管线上叠加图谱层，而非引入 LightRAG 全栈。
3. **不建议替换**：NEXUS 的核心契约（版本隔离、证据可回查、空结果降级）LightRAG 均不具备，替换会直接破坏评测中的 12 个隔离/降级用例。
4. 若引入 LightRAG 做对比参考，其 6 个"无降级"误答恰好是 NEXUS 设计原则（"来源缺失标为不可用，绝不伪装"）的价值证明。
