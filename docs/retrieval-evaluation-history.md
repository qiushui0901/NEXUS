# NEXUS 版本测试与检索评测台账

> 最后更新：2026-07-31
> 目的：集中记录每个 NEXUS 版本的测试范围、固定环境、质量指标、性能指标和结论。
> 原则：只有在**语料、黄金集、源码、profile、Top-K、模型和运行环境均固定**时，才允许进行跨版本质量对比。

## 1. 指标口径

| 指标 | 方向 | 含义 |
|---|---|---|
| Document Recall@10 | 越大越好 | 需要文档证据的查询中，黄金文档是否进入前 10 |
| Code Recall@10 | 越大越好 | 需要代码证据的查询中，黄金代码是否进入前 10 |
| MRR@10 | 越大越好 | 首个黄金结果倒数排名的平均值；第一名为 1，未进入前 10 为 0 |
| Mixed both-hit rate | 越大越好 | 同时要求文档和代码的查询中，两类黄金证据均命中的比例 |
| No-result accuracy | 越大越好 | 预期无结果的查询被正确处理的比例 |
| P50 / P95 latency | 越小越好 | 端到端延迟的中位数和第 95 百分位 |
| Infrastructure failures | 越小越好 | 外部依赖、超时、服务不可用等基础设施失败数量 |
| Contamination / leakage | 必须为 0 | 跨项目、跨版本错误召回 |

注意：**MRR 不是 Mean Rank**。MRR 越大越好；Mean Rank 才是越小越好。

## 2. 版本总览

| 版本 / 变体 | 测试分类 | 测试日期 | 质量结论 | 门禁结论 |
|---|---|---|---|---|
| 0.5 | 暂无标准化检索报告 | — | 无可比数据 | 待补 |
| 0.6 | MCP 六工具契约矩阵 | 2026-07-29 | 不属于检索质量对照 | 63 项定向测试通过 |
| 0.7 baseline | 正式同条件检索评测 | 2026-07-29 | 文档召回偏低，代码召回一般 | 作为 0.8 对照基线 |
| 0.8 rerank | 正式同条件检索评测 | 2026-07-29 | 质量与 0.7 完全持平，没有提升 | 非回退门禁通过 |
| 0.8 历史校准 | 非同条件校准 | 2026-07-28 | 修复前后数据改善，但不能与正式 0.7 基线比较 | 仅供诊断参考 |
| 0.8.1 quality | 正式同条件质量收口 | 2026-07-31 | 达到既定 Recall/MRR 门槛；相对同工作树控制组 MRR 提升，Recall 持平 | 正式 comparison PASS |
| 0.9 | 尚未评测 | — | 无数据 | 待补 |
| 1.0 | 尚未评测 | — | 无数据 | 待补 |

## 3. 0.6 MCP 工具契约测试

### 3.1 覆盖范围

覆盖以下六个工具：

1. `nexus_search_requirements`
2. `nexus_search_code`
3. `nexus_get_source`
4. `nexus_development_plan`
5. `nexus_wiki_page`
6. `nexus_version_diff`

每个工具覆盖四类契约：

- 入参校验；
- 认证、角色和项目白名单；
- 预期依赖不可用时的安全降级；
- 数量、文本、范围及全局响应截断。

### 3.2 测试结果

| 测试套件 | 数量 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| `NexusMcpV06ContractTest` | 52 | 0 | 0 | 0 |
| `NexusMcpToolsTest` | 4 | 0 | 0 | 0 |
| `McpResponsePolicyTest` | 6 | 0 | 0 | 0 |
| `McpHttpIntegrationTest` | 1 | 0 | 0 | 0 |
| **合计** | **63** | **0** | **0** | **0** |

结论：0.6 的六工具契约矩阵已经闭环。该结果验证工具边界和降级契约，不代表检索 Recall/MRR 达标。

## 4. 0.7 → 0.8 正式同条件检索评测

### 4.1 固定条件

| 项目 | 固定值 |
|---|---|
| 分类 | `formal` |
| 黄金集 | `src/test/resources/evaluation/retrieval-eval-shiguang-v1.jsonl` |
| 唯一 case 数 | 54 |
| 质量执行数 | 162（54 条 × 3 次重复） |
| 黄金集 SHA-256 | `1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18` |
| Profile 分布 | `DEVELOPMENT_PLAN=30`、`REQUIREMENT_REVIEW=12`、`WIKI_BUILD=12` |
| 覆盖场景 | 正常召回、相似功能误召回、跨项目污染、版本泄漏、空结果、依赖降级 |
| 拾光语料 commit | `d29f32589c5bd7c190a23eb3a84f27f0069f312f` |
| 项目 | `shiguang-eval` |
| 文档 / 版本 | `shiguang-eval-requirements` / `shiguang-eval-v1` |
| 最终 Top-K | 10 |
| Dense / Sparse / Hybrid Top-K | 50 / 50 / 40 |
| BGE Top-K | 20 |
| LLM rerank | 关闭 |
| 缓存 | 检索缓存和 Embedding 缓存均关闭 |
| 预热 / 重复 | 预热 1 次，重复 3 次 |
| Python | 3.11.15 |
| PyTorch | 2.11.0 |
| Transformers | 4.57.6 |
| Reranker | `BAAI/bge-reranker-v2-m3`，Python/Transformers 本地服务 |
| Java | OpenJDK 21.0.11 |

说明：两个变体在同一份评测源码和工作树上运行，通过配置切换 `passthrough` 与 `DefaultRequirementReranker`。这里的 `0.7-baseline` 是正式行为基线变体，不应被误解为从历史 0.7 Git 提交直接启动的完整应用。

### 4.2 总体质量结果

| 指标 | 0.7 baseline | 0.8 rerank | 绝对变化 | 判断 |
|---|---:|---:|---:|---|
| Document Recall@10 | 0.354167（51/144） | 0.354167（51/144） | 0.000000 | 未回退，但明显偏低 |
| Code Recall@10 | 0.738095（93/126） | 0.738095（93/126） | 0.000000 | 未回退，仍有提升空间 |
| MRR@10 | 0.425617 | 0.425617 | 0.000000 | 排名质量没有提升 |
| Mixed both-hit rate | 0.333333（42/126） | 0.333333（42/126） | 0.000000 | 偏低，无提升 |
| No-result accuracy | 1.000000（18/18） | 1.000000（18/18） | 0.000000 | 达标 |
| 失败执行数 | 99/162 | 99/162 | 0 | 无改善 |
| 基础设施失败数 | 0 | 0 | 0 | 稳定 |

### 4.3 BGE 执行结果

| 指标 | 0.7 baseline | 0.8 rerank |
|---|---:|---:|
| BGE calls | 0 | 144 |
| BGE successes | 0 | 144 |
| BGE degradations | 0 | 0 |
| No-candidate skips | 0 | 18 |

结论：BGE 服务是健康的，但成功调用没有转化为 Recall 或 MRR 提升。

### 4.4 候选池诊断

对正式报告中的 case 明细统计：

| 候选类型 | 有效执行数 | 候选数量分布 |
|---|---:|---|
| 文档 | 144 | 144 次全部只有 1 个最终候选 |
| 代码 | 126 | 126 次全部返回 10 个最终候选 |

当前文档链路会在 BGE 前按 `parentId` 去重。多个 child chunk 若属于同一 parent，会在重排前折叠为一个候选。BGE 面对单一候选无法改变顺序，因此这是解释 0.7 与 0.8 指标完全相同的关键诊断结果之一。

该诊断仍需在 0.8.1 报告中增加以下分阶段数据后正式量化：

- Dense、Sparse 和 RRF 各阶段 Recall@10/50/100；
- 重排前 child 候选数；
- parent 去重前后候选数；
- BGE 前后黄金排名；
- `DEDUP_LOSS`、`FUSION_LOSS`、`RERANK_DEMOTION` 等失败归因。

### 4.5 真实端到端延迟

| 指标 | 0.7 baseline | 0.8 rerank | 变化 |
|---|---:|---:|---:|
| P50 | 177 ms | 4,149 ms | +3,972 ms |
| P95 | 277 ms | 5,131 ms | +4,854 ms |
| `DEVELOPMENT_PLAN` P95 | 291 ms | 5,150 ms | +4,859 ms |
| `REQUIREMENT_REVIEW` P95 | 209 ms | 4,877 ms | +4,668 ms |
| `WIKI_BUILD` P95 | 298 ms | 5,237 ms | +4,939 ms |

解释：0.8 使用本地 CPU BGE reranker，真实端到端延迟显著增加。该数据不能用受控假依赖并行基准替代，两类指标必须分开披露。

### 4.6 受控并行召回性能基准

| 项目 | 结果 |
|---|---:|
| 分支数 | 3 |
| 每分支固定延迟 | 100 ms |
| 预热 / 重复 | 2 / 10 |
| 顺序执行 P95 | 312 ms |
| 并行执行 P95 | 106 ms |
| P95 降幅 | 66.03% |
| 要求 | 至少下降 30% |
| 结果 | PASS |

这是 `controlled-fake-dependency` 基准，只用于验证并行召回调度，不代表真实 BGE 端到端延迟。

### 4.7 正式验收结论

正式 comparison 的 acceptance checks 为 PASS，含义是：

- 报告契约有效；
- 0.7 baseline 确实绕过 BGE；
- 0.8 rerank 确实健康调用 BGE；
- Recall@10 和 MRR@10 没有低于基线；
- 受控并行召回 P95 达到性能门槛。

**该 PASS 只表示“可复现、基础设施健康、质量不回退”，不表示 0.8 已提升检索质量，也不表示当前召回质量优秀。**

## 5. 2026-07-28 的 0.8 历史校准

该记录使用 12 条黄金数据做修复前后校准。它不是正式的 0.7 → 0.8 同条件对照，不能与上一节的数据直接合并或据此声称版本提升。

| 指标 | 修复前 0.8 | 修复后 0.8 | 变化 |
|---|---:|---:|---:|
| Document Recall@10 | 0.900（9/10） | 1.000（10/10） | +0.100 |
| Code Recall@10 | 0.500（5/10） | 1.000（10/10） | +0.500 |
| MRR@10 | 0.516 | 0.863 | +0.347 |
| Mixed both-hit | 0.500（4/8） | 1.000（8/8） | +0.500 |
| P50 | 3,777 ms | 2,933 ms | -844 ms（-22.3%） |
| P95 | 8,617 ms | 5,888 ms | -2,729 ms（-31.7%） |
| 基础设施失败 | 10 | 10 | 独立 reranker endpoint 当时不可用 |

限制：

- 当时独立 BGE `/rerank` endpoint 尚不可用；
- Ollama `/api/embed` 只能返回 embedding，不满足 `index`/`score` 重排契约；
- 历史 0.7 提交没有完全相同的拾光 profile、脱敏语料和黄金数据集；
- 12 条校准集较小，结果只适合定位配置和标签问题。

## 6. 0.8 → 0.8.1 正式质量收口（2026-07-31）

### 6.1 固定条件

| 项目 | 固定值 |
|---|---|
| 分类 | `formal` |
| 源码 commit / 工作树 | `f392f5ffeb863b47d8b01f9a425dae616498ab00` / dirty；完整评测源码指纹 `ca5f7870a825ac9a537c0acc6074e05399619207350930231b38a833550e5b2b` |
| 黄金集 | `src/test/resources/evaluation/retrieval-eval-shiguang-v1.jsonl` |
| 黄金集 SHA-256 | `1ff996579588bfc5b859b5a483427c255325265b211e452af5eaff6471a61b18` |
| 唯一 case / 执行数 | 54 / 162（预热 1 次，重复 3 次） |
| Profile 分布 | `DEVELOPMENT_PLAN=30`、`REQUIREMENT_REVIEW=12`、`WIKI_BUILD=12` |
| 拾光语料 | commit `d29f32589c5bd7c190a23eb3a84f27f0069f312f`；需求块 5，代码块 923 |
| Dense / Sparse / Hybrid / BGE / Final Top-K | 50 / 50 / 40 / 20 / 10 |
| 缓存 | 检索结果缓存关闭；Embedding 缓存 TTL 3600 秒、最多 512 项 |
| Reranker | `BAAI/bge-reranker-v2-m3`，Python/Transformers，CPU，max length 384，batch size 4 |
| 超时 | BGE connect 2 秒、read 30 秒 |
| 运行时 | OpenJDK 21.0.11；Python 3.11.15；PyTorch 2.11.0；Transformers 4.57.6 |

### 6.2 质量与性能结果

> **口径勘误（2026-07-31）**：下表中的 `Document Recall@10=1.000000` 只适用于 `retrieval-eval-shiguang-v1.jsonl` 的**单需求文件、文件级黄金标签**。48 个文档 HIT 用例全部指向 `shiguang-eval-requirements.md`，且没有 `parentOrder` / `childOrder` 标签；候选中出现该文件即可满足当前文件级判断。因此该数值不能解释为“章节级或子块级语义召回达到 100%”，也不能证明多文档 hard-negative 场景已解决。0.8.2 将以 File / Section / Child 三层 Recall 和多文档固定语料替代这一宽松口径。

0.8.1 的产品验收门槛来自 2026-07-29 的历史 0.8 正式结果。为了同时隔离本轮开关效果，2026-07-31 的 runner 还在同一工作树、同一语料和同一运行环境中执行了 `0.8-rerank` 行为控制组。两种比较口径必须分开披露。

| 指标 | 历史 0.8 正式值 | 0.8.1 candidate | 相对历史值变化 | 目标 | 结果 |
|---|---:|---:|---:|---:|---|
| Document Recall@10 | 0.354167 | 1.000000（144/144） | +0.645833 | ≥ 0.504167 | PASS |
| Code Recall@10 | 0.738095 | 0.809524（102/126） | +0.071429 | ≥ 0.788095 | PASS |
| MRR@10 | 0.425617 | 0.823951 | +0.398334 | ≥ 0.525617 | PASS |
| Mixed both-hit rate | 0.333333 | 0.809524（102/126） | +0.476191 | — | 提升 |
| No-result accuracy | 1.000000 | 1.000000（18/18） | 0 | 1.000000 | PASS |
| P50 | 4,149 ms | 16 ms | -4,133 ms | — | 降低 |
| P95 | 5,131 ms | 34 ms | -5,097 ms | ≤ 5,131 ms；质量达标后目标 ≤ 4,500 ms | PASS |
| 基础设施失败 | 0 | 0 | 0 | 0 | PASS |
| 跨项目/版本污染 | 0 | 0 | 0 | 0 | PASS |

同工作树控制组用于识别本轮候选生命周期、精排和 singleton 快路径的增量效果：

| 指标 | `0.8-rerank` 控制组 | `0.8.1-quality` | 变化 |
|---|---:|---:|---:|
| Document Recall@10 | 1.000000 | 1.000000 | 0 |
| Code Recall@10 | 0.809524 | 0.809524 | 0 |
| MRR@10 | 0.806636 | 0.823951 | +0.017315（+2.15%） |
| P50 | 4,107 ms | 16 ms | -4,091 ms（-99.61%） |
| P95 | 7,126 ms | 34 ms | -7,092 ms（-99.52%） |
| BGE calls / successes / degradations | 144 / 144 / 0 | 0 / 0 / 0 | 144 次无排序价值的调用被安全跳过 |
| No-candidate / singleton skips | 18 / 0 | 18 / 144 | 所有 162 次决策均已记账 |

因此，**0.8.1 整体方案达到了相对历史 0.8 的绝对质量门槛，但不能把同工作树控制组中 Recall 持平写成 reranker 带来的 Recall 提升**。同工作树 A/B 能直接证明的是 MRR 提升和无效 singleton 推理消除；更大的 Recall 改善来自本轮共享的候选生命周期、代码召回/精排和语料重建修复。

### 6.3 BGE singleton 安全快路径

正式 candidate 的 144 次文档执行在 parent 聚合后均只剩 1 个可返回候选。单候选调用 BGE 不可能改变排序，因此 0.8.1 在 child-first 模式下记录 `bge.rerank.singleton_skip` 并保留原结果，而不是执行无排序价值的 CPU 推理。该优化不是全局关闭 BGE：

- runner 在正式评测前分别完成 Python `/health` 和 Java → BGE `/rerank` 实时契约验证；
- 多候选路径仍调用真实 BGE，只有 singleton 决策被跳过；
- baseline 兼容测试证明关闭 0.8.1 开关后，单候选仍会调用 BGE；
- candidate 中 `18 no-candidate + 144 singleton = 162`，全部决策均有结构化计数，意外 degradation 为 0。

### 6.4 分阶段诊断与剩余失败

按 54 个唯一 case 统计：

- 文档黄金结果：48/48 全部命中且排名保持；6 条为预期空结果；
- 代码黄金结果：20 条被提升、12 条保持、2 条下降但仍命中、7 条候选召回缺失、1 条在精排后丢失；
- 代码排序在 42 条需要代码证据的 case 中有 39 条发生顺序变化；
- 文档原始 child 候选通常为 5，聚合后为 1；代码候选池从最多 50 收口到最终 10；
- 24 次失败执行来自 8 个唯一 case 的 3 次重复，不是 24 个不同问题；
- 唯一失败归因：`CODE_CANDIDATE_RECALL_MISS=7`、`CODE_RERANK_LOSS=1`；重复执行口径分别为 21 和 3，归因覆盖率 100%。

在 v1 的单文件、文件级口径内，剩余可见失败集中在代码候选覆盖；但文档侧尚未经过多文件、章节级和子块级 hard-negative 验证，不能据此认定文档召回瓶颈已经消失。0.8.2 应先修复文档评测口径，再决定检索算法的优化优先级。

### 6.5 验收结论

`target/retrieval-evaluation/comparison.json` 的全部 acceptance checks 为 PASS：Document Recall@10、Code Recall@10、MRR@10、no-result accuracy、真实 P95、固定语料重建、BGE 决策健康和受控并行召回门槛均通过。正式产物可复现且 `manifest.json` 明确记录 `workingTreeDirty=true`、逐文件哈希、运行环境和 `secretsRecorded=false`。

该结果允许把 `0.8.1-quality` 作为 **v1 单文件评测口径下** 的兼容基线；它不能直接作为多文档章节召回的质量基线。仍需保留 8 个唯一代码失败 case，并在 0.8.2 新增多文档结构化黄金集。

## 7. 0.8.2 可信文档召回评测（准备中，2026-07-31）

0.8.2 不预设“指标一定提升”，先解决评测分数是否可信的问题。固定条件和结果只有在代码实现与正式运行后才能填写；本节当前只冻结口径。

### 7.1 分层指标

| 指标 | 命中条件 | 分母 | 说明 |
|---|---|---|---|
| File Recall@10 | `filename` 命中 | 具有文档黄金标签的唯一 case | 判断是否找对文件，不读取整篇父文本 |
| Section Recall@10 | `filename + parentOrder` 命中 | 提供 `parentOrder` 的唯一 case | 判断是否找对父块/章节 |
| Child Recall@10 | `filename + parentOrder + childOrder` 命中，且目标 `childText` 满足 `mustContain` | 提供 `childOrder` 的唯一 case | 判断最终证据子块是否正确；禁止借用 `parentText` |
| Code Recall@10 | 稳定项目、相对路径、符号名命中 | 具有代码黄金标签的唯一 case | 保持现有稳定标签契约 |
| MRR@10 | 使用 case 最严格可用层级的首个正确排名 | 唯一有黄金标签的 case-item | 越大越好；重复运行不扩大质量分母 |

### 7.2 固定语料目标

- 不少于 6 个文件、12 个可区分章节和 24 个唯一文档 HIT case；
- 同一业务词在多个文件出现，覆盖同义词、同词异义、相似流程、错误阶段和跨文件近似答案；
- 每个结构化黄金标签包含稳定的 `filename`、`parentOrder`、`childOrder` 和短文本 anchor；
- repetitions 只用于延迟和稳定性统计，File/Section/Child Recall 与 MRR 按唯一 case 计算；
- 初始 v2 分数不得与 v1 的 `Document Recall@10` 直接纵向比较。

### 7.3 需要防止的误判

1. 正确文件、错误章节：File hit，Section/Child miss；
2. 正确章节、错误子块：File/Section hit，Child miss；
3. 父块全文含目标短语、返回子块不含：Child miss；
4. 同一 case 重复 3 次：质量分母计 1，延迟样本计 3；
5. 近似文档出现在 Top-10、目标文档未出现：不得因共享术语判为命中。

## 8. 后续版本记录模板

每次发布或检索策略变更后，复制以下模板追加，不覆盖历史记录：

```markdown
## X.Y / variant-name

- 测试日期：YYYY-MM-DD
- 分类：formal / calibration / controlled-fake-dependency
- 源码 commit：
- 工作树是否干净：
- 语料 commit：
- 黄金集路径：
- 黄金集 SHA-256：
- 唯一 case 数：
- Profile 分布：
- Dense / Sparse / Hybrid / BGE / Final Top-K：
- Embedding 模型：
- Reranker 模型与运行方式：
- Java / Python / PyTorch / Transformers：
- 缓存、预热和重复次数：

| 指标 | 上一正式版本 | 当前版本 | 变化 | 是否达标 |
|---|---:|---:|---:|---|
| File Recall@10（unique case） | | | | |
| Section Recall@10（unique case） | | | | |
| Child Recall@10（unique case） | | | | |
| Code Recall@10（unique case） | | | | |
| MRR@10 | | | | |
| Mixed both-hit rate | | | | |
| No-result accuracy | | | | |
| P50 | | | | |
| P95 | | | | |
| Infrastructure failures | | | | |
| Contamination / leakage | | | | |

### 分阶段诊断

- Gold-in-corpus rate：
- Dense Recall@10/50/100：
- Sparse Recall@10/50/100：
- Dense ∪ Sparse Oracle Recall：
- RRF Recall@10/50：
- 重排前候选数：
- 去重前后候选数：
- Reranker order-change rate：
- Gold promoted / unchanged / demoted：
- 失败归因分布：

### 结论

- 质量是否提升：
- 性能是否满足预算：
- 是否存在污染或基础设施失败：
- 是否允许替换当前正式基线：
```

## 9. 原始报告索引

| 产物 | 路径 |
|---|---|
| 正式对照 Markdown | `target/retrieval-evaluation/comparison.md` |
| 正式对照 JSON | `target/retrieval-evaluation/comparison.json` |
| 可复现环境清单 | `target/retrieval-evaluation/manifest.json` |
| 0.7 baseline 报告 | `target/retrieval-evaluation/0.7-baseline/report.md` |
| 0.7 baseline 原始 JSON | `target/retrieval-evaluation/0.7-baseline/report.json` |
| 0.8 rerank 报告 | `target/retrieval-evaluation/0.8-rerank/report.md` |
| 0.8 rerank 原始 JSON | `target/retrieval-evaluation/0.8-rerank/report.json` |
| 0.8.1 quality 报告 | `target/retrieval-evaluation/0.8.1-quality/report.md` |
| 0.8.1 quality 原始 JSON | `target/retrieval-evaluation/0.8.1-quality/report.json` |
| 并行召回基准 | `target/retrieval-evaluation/parallel-recall-benchmark.json` |
| 拾光评测操作与历史校准 | `docs/shiguang-evaluation.md` |

## 10. 维护规则

1. 不覆盖历史版本数据，只追加新版本或勘误说明；
2. 正式对比必须固定黄金集 SHA、语料 commit 和关键配置；
3. 唯一查询质量指标与重复运行性能指标应分别披露；
4. 校准、模拟依赖和正式报告不得混为同一种分类；
5. 不得把“非回退 PASS”写成“质量提升 PASS”；
6. 若工作树不干净，必须记录评测源码文件哈希或生成 manifest；
7. 所有结论必须能回溯到 JSON/Markdown 原始报告。
