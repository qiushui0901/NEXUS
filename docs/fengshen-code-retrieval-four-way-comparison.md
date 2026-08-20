# 封神代码召回四向对比分析

> 对比对象：NEXUS、codebase-memory MCP（BM25）、RAGFlow、LightRAG
> 测试集：`evaluation/fengshen-code-retrieval-eval-500.jsonl`，500 道代码检索题
> 评测日期：NEXUS 2026-08-14（E10 最终）、codebase-memory MCP 2026-08-13、RAGFlow / LightRAG 2026-08-12
> 目标：比较四套系统在全仓库代码检索中的定位准确性、排序质量、响应速度和结果完整性。

> 本文件替代旧的 `docs/fengshen-code-retrieval-three-way-comparison.md`。旧文档中的 NEXUS 数据为历史基线（Recall@10 93.2%、Recall@1 63.0%），已被下方 E10 最新数据取代。

## 一、结论摘要

在当前测试条件下，**NEXUS（E10）综合表现最好，codebase-memory MCP（BM25）紧随其后**。

- NEXUS 有序 Recall@10 为 **99.6%**，MRR@10 为 **0.9596**，Recall@1 达 **93.6%**；
- codebase-memory MCP（BM25）Recall@10 为 **92.0%**，MRR@10 为 **0.8348**，且单次本地检索延迟极低（P50 约 24ms）；
- RAGFlow Recall@10 为 **78.6%**，MRR 为 **0.4609**；
- LightRAG 复测后有序 Recall@10 为 **46.6%**，MRR 为 **0.225**。

因此，在“全仓库、需要准确定位文件和代码符号”的场景中，当前排序为：

> **NEXUS > codebase-memory MCP（BM25） > RAGFlow > LightRAG**

其中 LightRAG 的 symbol 命中率较高（84.0%@10），但文件定位能力明显不足；不能把 symbol 命中率直接当成代码定位召回率。codebase-memory MCP 的延迟口径与本机 BM25 CLI 相关，不能与含网络/检索链路的 NEXUS 直接画等号。

## 二、总体指标

| 指标 | LightRAG | RAGFlow | codebase-memory MCP（BM25） | NEXUS（E10 最新） |
|---|---:|---:|---:|---:|
| Recall@1 | 15.0%（75/500） | 34.0% | **80.40%**（402/500） | **93.6%**（468/500） |
| Recall@5 | 35.4%（177/500） | 64.4% | **88.00%** | **99.6%** |
| Recall@10 | 46.6%（233/500） | 78.6% | **92.00%** | **99.6%** |
| MRR@10 | 0.225 | 0.4609 | **0.8348** | **0.9596** |
| nDCG@10 | 未记录 | 未记录 | **0.8550** | **0.9688** |
| 命中题平均首命中排名 | 3.78（233 道） | 3.0941（393 道） | 未记录 | 未逐题记录 |
| 空召回/无有效结果 | 53.4%（267/500） | 4.8%（24/500，多为解析失败） | 0.8% | 0.4%（2/500 未进 Top10） |
| 文件命中率@10 | 62.6% | 未记录 | **98.40%** | 98.4%（旧基线 492/500） |
| 符号+文件同时命中@10 | 61.4% | 未记录 | **92.00%** | 99.6% |
| 查询 P50 / P95 | 1.0s / 1.3s | 未记录 | **23.98ms / 28.65ms** | 334ms / 436ms |

> codebase-memory MCP 原始报告：`/Users/user/Desktop/ua/fengshen-code-retrieval-eval-500-mcp-20260813.md`

### 指标口径差异

四份报告并不是完全相同的统计口径，必须区分以下指标：

1. **NEXUS Recall@K（E10 最新）**
   - 按 MCP 返回的有序 `data` 结果列表计算；
   - 只有同一条结果同时包含 gold `symbolName` 和 `filePath`，才算命中；
   - Recall@1/5/10 为 **93.6% / 99.6% / 99.6%**，MRR@10 0.9596，nDCG@10 0.9688；
   - 延迟口径为完整检索链路（Qdrant + RRF + 结构重排 + 可选 BGE），包含网络与序列化。

2. **codebase-memory MCP（BM25）Recall@K**
   - 检索器为 `codebase-memory-mcp search_graph`（本地 BM25），`limit=10`、`include_connected=false`；
   - 只有同时命中 Gold 文件和符号才算命中，判定口径与 NEXUS 对齐；
   - Recall@1/5/10 为 **80.40% / 88.00% / 92.00%**，MRR@10 0.8348，nDCG@10 0.8550；
   - 延迟口径为**单次 CLI 调用端到端**（本地读索引 + 检索 + 序列化），不含网络与多路检索，因此不能与 NEXUS 的端到端延迟直接比较。

3. **LightRAG Recall@K（本次复测）**
   - 用 `aquery_data` 获取最终保留的有序 chunks（前 10 个）；
   - 只有同一个 chunk 的文本同时包含 `# 文件: <path>` 标题与 gold `symbolName`，才算命中；
   - 判定语义与 NEXUS 对齐（符号+文件同命中），但载体是文本而非结构化字段。

4. **RAGFlow**
   - 直接使用报告中的 `recall@1` / `recall@5` / `recall@10` 和 `first_hit_rank`；
   - gold 为 `路径#符号`，需要路径和符号边界共同满足；
   - 500 次请求中 476 次解析成功，24 次没有解析出结果。

## 三、各系统结果分析

### 3.1 NEXUS（E10 最新）

NEXUS 使用全仓库 Tree-sitter 符号索引，测试语料约 2139 个文件。检索结果带有结构化文件路径和代码符号信息，因此可以进行精确的文件级、符号级匹配。

E10（开关全开、五轮评审整改后）最终结果：

- 有序 Recall@1/5/10：**93.6% / 99.6% / 99.6%**
- MRR@10：**0.9596**
- nDCG@10：**0.9688**（0.8083 → 0.9688，+0.161）
- P50 / P95：**334ms / 436ms**
- 相对基线 A3：Recall@1 +29.6pp、Recall@5 +7.4pp、Recall@10 +5.6pp、MRR +0.196
- 185 条排名变化中 157 条升至 Top1；9 条从 Top1 掉至 2-5 位（仍在 Top5）
- 仅 2 条未进 Top10（`HeroService.putOn`、`MarchPluginCommon.repatriateDeadToHomeBuild`）

分查询类型（E10 最终）：

| 查询类型 | 样本数 | Recall@1 | Recall@10 |
|---|---:|---:|---:|
| BUSINESS_TERM | 125 | 100.0% | 100.0% |
| SYMBOL | 125 | 100.0% | 100.0% |
| BEHAVIOR | 125 | 84.8% | 98.4% |
| REQUIREMENT_TO_CODE | 125 | 89.6% | 100.0% |

主要优势是**结构化定位 + 精确符号通道 + 类名限定召回**。对于 `handle`、`init`、`get` 等高频同名方法，NEXUS 可以依靠符号表和类名范围区分具体文件，而不是只判断文本中是否出现了方法名。

### 3.2 codebase-memory MCP（BM25）

来源：`/Users/user/Desktop/ua/fengshen-code-retrieval-eval-500-mcp-20260813.md`
检索器：`codebase-memory-mcp search_graph`（本地 BM25），`limit=10`

结果：

- Recall@1/5/10：**80.40% / 88.00% / 92.00%**
- MRR@10：**0.8348**
- nDCG@10：**0.8550**
- 文件命中率@10：**98.40%**
- 符号+文件同时命中@10：**92.00%**
- 真正 no-result：**0.8%**
- 平均召回时间：24.65ms，P50 / P95 / P99：23.98ms / 28.65ms / 32.30ms

分查询类型：

| 分类 | 题数 | R@1 | R@5 | R@10 | MRR@10 | nDCG@10 |
|---|---:|---:|---:|---:|---:|---:|
| BEHAVIOR | 125 | 63.20% | 77.60% | 85.60% | 0.6910 | 0.7298 |
| BUSINESS_TERM | 125 | 97.60% | 97.60% | 97.60% | 0.9760 | 0.9760 |
| REQUIREMENT_TO_CODE | 125 | 63.20% | 79.20% | 87.20% | 0.6963 | 0.7380 |
| SYMBOL | 125 | 97.60% | 97.60% | 97.60% | 0.9760 | 0.9760 |

优势：

- 本地 BM25 无需远程网关/Embedding，延迟极低；
- 文件命中率 98.4%，说明 BM25 在“找出正确文件”上很强；
- BEHAVIOR / REQUIREMENT_TO_CODE 的中文语义描述题是主要短板（R@1 约 63%）。

注意：该延迟是本地 CLI 端到端口径，与 NEXUS 的“Qdrant + RRF + 重排”端到端链路不可直接对比；但作为对比参考，它表明本地结构化/关键词图检索在速度上有明显优势。

### 3.3 RAGFlow

RAGFlow 使用以下评测配置：

- `page_size=10`
- `threshold/similarity_threshold=0.2`
- `vector_weight/vector_similarity_weight=0.3`
- `keyword=true`
- `top_k=100`
- gold 为“文件路径#代码符号”

结果：

- Recall@1：34.0%
- Recall@5：64.4%
- Recall@10：78.6%
- MRR：0.4609
- 命中题平均首命中排名：3.0941
- 有数值首命中排名的题目：393/500
- 解析失败：24/500
- 报告未记录请求耗时

RAGFlow 的 Recall@10（78.6%）明显高于 LightRAG（46.6%），说明它在代码文档切片、关键词检索和向量检索结合后，能够召回相当一部分正确代码。但 Recall@1 只有 34.0%，排序不足以支撑“直接定位”场景。

### 3.4 LightRAG

LightRAG 使用全仓库代码摘要语料，约 2139 个文件，分 8 批合并入库。索引过程需要 LLM 抽取，报告记录索引耗时约 3.5 小时（含网关失败重试）。

结果（`aquery_data` 结构化候选，前 10 chunk 判定）：

- symbolName 命中率：84.0%（420/500）
- filePath 命中率：62.6%（313/500）
- symbol+file 同时命中：61.4%（307/500）
- 有序 Recall@1/5/10：15.0% / 35.4% / 46.6%
- MRR@10：0.225
- 平均首命中排名：3.78（233 道）
- 空召回率：53.4%（267/500 前 10 chunk 无目标）
- P50 / P95：1.0s / 1.3s

LightRAG 的 symbol 命中率看似不低，但查询本身包含 `className.methodName`，向量检索很容易找到某个包含方法名的文本块。真正困难的是定位正确文件：全仓库存在大量同名方法（`handle` / `init` / `get` / `refresh` / `build`），LightRAG 可能召回包含目标方法名的其他文件。

## 四、Recall 曲线分析

```text
Recall@1   15.0%   |  34.0%  |  80.4%  |  93.6%   (LightRAG | RAGFlow | MCP-BM25 | NEXUS)
Recall@5   35.4%   |  64.4%  |  88.0%  |  99.6%
Recall@10  46.6%   |  78.6%  |  92.0%  |  99.6%
```

- **NEXUS**：@1 即 93.6%，@5 已收敛到 99.6%，说明「精确符号通道 + 结构重排」把正确结果压到了前几位；
- **codebase-memory MCP（BM25）**：@1 80.4%、@10 92.0%，曲线平坦且靠前；BEHAVIOR / 需求映射类查询是主要丢分点；
- **RAGFlow**：@1→@10 增长 44.6 个百分点，正确结果常在候选集里但排序靠后；
- **LightRAG**：@1→@10 仅增长 31.6 个百分点且 @10 只有 46.6%，超过半数查询前 10 内找不到目标。

## 五、速度与成本分析

### 查询延迟

| 系统 | P50 | P95 | 口径 |
|---|---:|---:|---|
| codebase-memory MCP（BM25） | **23.98ms** | 28.65ms | 本地 CLI 端到端（不含网络） |
| NEXUS（E10） | 334ms | 436ms | 完整检索链路（Qdrant+RRF+重排） |
| LightRAG | 1.0s | 1.3s | `aquery_data` 结构化模式 |
| RAGFlow | 未记录 | 未记录 | — |

> codebase-memory MCP 的延迟口径与 NEXUS 不同：它没有远程 Embedding、Qdrant、RRF 和可选 BGE 的网络成本，因此速度优势不能直接视为“检索质量优势”。

### 索引成本

- NEXUS：Tree-sitter 解析和向量索引，不依赖 LLM；
- codebase-memory MCP：本地图/关键词索引，无 LLM 抽取；
- LightRAG：代码摘要和知识图谱抽取依赖 LLM，索引成本高（约数小时）；
- RAGFlow：本报告没有记录完整索引耗时。

## 六、评测数据质量与公平性问题

1. **查询包含符号名，放大文本检索优势**：500 道题 query 自带类名和方法名，对 BM25/向量检索较有利；真实代码问答通常只描述业务语义。
2. **排名数据载体不完全一致**：NEXUS / codebase-memory MCP 是结构化命中，LightRAG 是文本块包含路径标题与符号名，判定语义对齐但载体不同，MRR 不能视为完全同口径。
3. **RAGFlow 存在 24 道解析失败**：正式比较应至少给出“全部 500 题”和“排除解析失败后”两组结果。
4. **“空召回率”不是同一概念**：RAGFlow 的 24/500 多为未解析响应，NEXUS 的 0.4% 是前 10 内无 symbol+file 同命中，不能混为一谈。

## 七、最终选型建议

### 代码检索（精度优先）

优先使用 **NEXUS（E10）**：

- 结构化符号定位准确；
- Recall@1 93.6% / Recall@10 99.6% 最高；
- MRR 0.9596、nDCG 0.9688；
- 无需 LLM 参与在线查询。

### 延迟敏感 / 本地轻量检索

**codebase-memory MCP（BM25）** 是很好的补充：

- P50 约 24ms，远快于其余系统；
- 文件命中率 98.4%，在“先找到文件”场景有优势；
- BEHAVIOR / 需求映射这类中文语义题排序弱于 NEXUS，可做混合/兜底。

### 通用代码/文档 RAG

RAGFlow 可作为通用方案，但需要补齐：真实请求耗时、解析失败率、代码路径和符号级 reranker、同名符号消歧、有效响应与接口失败分离统计。

### 知识图谱和跨文档关系查询

LightRAG 仍有一定价值，尤其是跨文档关系和实体关联查询；但对于全仓库代码精确文件定位，应以 symbol+file 同时命中的有序 Recall@K 为主要指标，不能只看 symbol 命中率。

## 八、后续评测建议

1. 四套系统统一返回前 10 个结构化候选（`filePath`、`symbolName`、`score`、`rank`）；
2. 客户端统一记录 `elapsed_ms`，区分本地 CLI 与远程链路口径；
3. 同时报告 Recall@1/5/10、MRR@10、nDCG@10、文件命中率、符号+文件同命中率、解析失败率、真正 no-result、P50/P95/P99；
4. 将 query 分为“带符号名定位题”和“仅业务语义无符号名”两组；
5. 对四套系统使用同一份全仓库语料和同一批 gold。

## 九、最终判断

在当前封神 500 道代码题和现有评测口径下：

- **NEXUS（E10）：精度最优，适合生产代码检索**（Recall@10 99.6%、MRR 0.9596、P50 334ms）；
- **codebase-memory MCP（BM25）：速度最优、文件命中强，语义排序次之**（Recall@10 92.0%、MRR 0.8348、P50 24ms）；
- **RAGFlow：召回居中**（Recall@10 78.6%、MRR 0.461），排序和结果解析仍需加强；
- **LightRAG：代码定位和排序最弱**（Recall@10 46.6%、MRR 0.225）。

NEXUS 和 LightRAG 的核心差别不在“文本里有没有出现方法名”，而在于能否把正确的**文件、类和符号**作为结构化对象稳定地排到前几位。
