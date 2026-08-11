# 开源 RAG 引擎对比与 NEXUS 选型建议

> 数据来源：GitHub 官方仓库和项目官方文档。
>
> 查询时间：2026-08-10。Stars 会持续变化，仅代表社区热度，不代表质量或生产成熟度。
>
> 本报告只比较 RAG 引擎、RAG 框架和检索基础组件，不把 Dify、Open WebUI、AnythingLLM、Flowise 等 AI 应用平台作为 RAG 引擎排名。

## 1. 项目范围

以下项目不参与核心引擎排名：

| 类型 | 项目 | 原因 |
|---|---|---|
| AI 应用平台 | Dify、Open WebUI、AnythingLLM、Flowise | 重点是 Agent/应用工作台，不是 RAG 核心引擎 |
| 文档解析层 | Docling、MinerU、PaddleOCR | 负责解析、OCR 和结构化输出，不负责完整检索闭环 |
| 文档问答应用 | Kotaemon、QAnything | 面向最终用户的完整应用，不是中立的 RAG 基础框架 |
| 记忆/关系层 | Zep、Graphiti | 重点是 Agent 记忆和时序知识图谱 |
| 已停止项目 | Verba | 已停止维护，不建议新项目采用 |

## 2. 核心项目排名

| 项目 | Approx. Stars | 类型 | 核心技术亮点 | 适用场景 | License |
|---|---:|---|---|---|---|
| [RAGFlow](https://github.com/infiniflow/ragflow) | 约 87.2k | 完整 RAG 引擎/平台 | DeepDoc、模板化分块、全文+向量混合检索、融合重排、引用、Agent/MCP | 企业文档知识库、复杂 PDF/表格/扫描件 | Apache-2.0 |
| [LlamaIndex](https://github.com/run-llama/llama_index) | 约 51.5k | RAG 开发框架 | Loader、Node Parser、Retriever、Reranker、Property Graph、Agent、海量集成 | 自研 RAG、复杂数据接入 | MIT |
| [LightRAG](https://github.com/HKUDS/LightRAG) | 约 38.7k | Graph RAG 框架/服务 | 图+向量双层检索、local/global/hybrid/mix、增量更新、选择性删除 | 跨文档关系、动态知识库、法律/金融/研究 | MIT |
| [GraphRAG](https://github.com/microsoft/graphrag) | 约 35.4k | Graph RAG 索引管道 | 实体/关系/Claim 抽取、社区发现、社区摘要、Local/Global/DRIFT Search | 全局主题分析、跨文档推理 | MIT |
| [PageIndex](https://github.com/VectifyAI/PageIndex) | 约 35.1k | Vectorless RAG | 基于目录树和章节结构的 LLM 引导检索，不依赖向量相似度 | 长篇规范、技术手册、法规、架构文档 | MIT |
| [Graphiti](https://github.com/getzep/graphiti) | 约 29.7k | 时序知识图谱引擎 | 时间有效性、事实失效、来源 Episode、图/关键词/语义混合检索 | Agent 长期记忆、变化中的实体关系 | Apache-2.0 |
| [Haystack](https://github.com/deepset-ai/haystack) | 约 26.2k | 生产级 RAG 编排框架 | 显式 Pipeline、BM25、Dense、Hybrid、Metadata Filter、Reranker、Agent | 需要强流程控制和可观测性的企业系统 | Apache-2.0 |
| [RAG-Anything](https://github.com/HKUDS/RAG-Anything) | 约 22.8k | 多模态 RAG 框架 | 文本、图片、表格、公式统一处理，图+向量检索 | 技术 PDF、论文、表格、公式、扫描文档 | MIT |
| [txtai](https://github.com/neuml/txtai) | 约 12.8k | Embeddings/搜索框架 | Dense/Sparse、图网络、语义搜索、Pipeline、Workflow、Agent、MCP | 轻量检索服务、嵌入式 RAG、原型系统 | Apache-2.0 |
| [LEANN](https://github.com/StarTrail-org/LEANN) | 约 12.8k | 本地检索存储引擎 | 选择性重算、图索引、减少向量存储、隐私优先 | 本地知识库、代码、邮件、浏览历史 | MIT |
| [R2R](https://github.com/SciPhi-AI/R2R) | 约 8.0k | API-first Retrieval 系统 | 混合检索、RRF、文档管理、Collection、Graph、Agentic RAG、引用 | 快速获得 API 化 RAG 后端 | MIT |
| [FlashRAG](https://github.com/RUC-NLPIR/FlashRAG) | 约 3.5k | RAG 研究/评测框架 | 多种 Retriever、Reranker、Generator、RAG 算法、评测数据集 | 检索实验、算法对比、离线评测 | MIT |

## 3. 分层分析

### 3.1 完整 RAG 引擎

#### RAGFlow

RAGFlow 是当前最完整的开源 RAG 产品之一，重点能力包括：

- 复杂文档解析；
- 可视化和模板化分块；
- 全文、向量和混合检索；
- 融合重排；
- 引用和人工检查；
- Agent、API、MCP 和管理界面。

优点是产品完整、上手快、文档场景覆盖广。缺点是部署较重，通常需要 Elasticsearch、MySQL、MinIO、Redis 等组件，且其核心数据模型仍以文档知识库为中心，不是研发版本证据模型。

#### R2R

R2R 更偏 API-first 后端，提供文档、Collection、混合检索、引用、Graph RAG 和 Agentic RAG。它适合作为应用后端参考，但社区规模和长期维护信号弱于 LlamaIndex、Haystack、RAGFlow。

### 3.2 RAG 开发框架

#### LlamaIndex

LlamaIndex 的核心价值是组件生态：

```text
Loader -> Node Parser -> Index -> Retriever -> Reranker -> Query Engine -> Agent
```

适合快速组合不同数据源、索引、检索器和 Agent。但权限、租户、Evidence、索引版本、审计和发布生命周期都需要应用自行设计。

#### Haystack

Haystack 更强调显式 Pipeline 和生产控制：

```text
Retriever -> Joiner -> Ranker -> Prompt Builder -> Generator
```

相比 LlamaIndex，它更适合需要明确阶段、可观测性、故障降级和自动化测试的企业系统。但它本身不是知识库产品，也不提供研发版本和代码影响分析。

### 3.3 Graph RAG

#### LightRAG

LightRAG 将知识图谱和向量检索结合，支持 local、global、hybrid、naive、mix 等模式，并将增量插入和选择性删除作为重点能力。

它适合关系密集型和持续变化的知识库，但实体/关系抽取依赖 LLM，可能产生实体合并错误、关系遗漏和较高索引成本。

#### GraphRAG

GraphRAG 的典型流程是：

```text
文档 -> Entity / Relation / Claim -> Community Detection -> Community Reports
```

它更适合全局主题、跨文档关系和研究分析。全局检索和索引成本较高，且需要领域 Prompt 调优。官方仓库也明确说明它是方法和示例代码，不是正式支持的 Microsoft 产品。

#### Graphiti

Graphiti 更像时序事实层，而不是传统文档 RAG：

- 事实何时生效；
- 事实何时失效；
- 新旧事实如何共存；
- 关系如何随时间变化；
- 事实来自哪个 Episode。

它适合 Agent 长期记忆和动态关系，但 exact document/page/quote、权限、审计和企业证据封装仍需应用自行完成。

### 3.4 特殊检索范式

#### PageIndex

PageIndex 不依赖传统向量相似度，而是构建目录/章节树，再由 LLM 根据树结构选择相关页面和章节。

它非常适合：

- 法规；
- 技术规范；
- 架构设计；
- 长篇手册；
- 目录结构清晰的 PDF。

它不适合直接处理海量代码符号、commit diff 或高频变更仓库。

#### LEANN

LEANN 主要解决本地检索的存储和隐私问题，通过图结构和选择性重算减少向量存储。它更像检索存储引擎，不是完整的企业 Evidence 平台。

#### FlashRAG

FlashRAG 适合作为实验和评测工具：

- 对比不同 Retriever；
- 测试 chunk 策略；
- 测试 Reranker；
- 运行公开 benchmark；
- 做 RAG 回归评测。

它不适合作为生产知识库控制面。

## 4. 与 NEXUS 对比

| 能力 | RAGFlow | LlamaIndex/Haystack | LightRAG/GraphRAG | PageIndex | NEXUS |
|---|---|---|---|---|---|
| 通用文档问答 | 很强 | 需组装 | 强 | 长文档强 | 中等 |
| 复杂 PDF/表格 | 很强 | 依赖解析器 | 依赖解析器 | 较强 | 当前较弱 |
| 混合检索 | 强 | 可配置 | 支持 | 非核心 | 强 |
| 图关系检索 | 一般 | 可扩展 | 核心能力 | 无 | 代码关系为主 |
| 精确源码 Evidence | 非核心 | 需自建 | 非核心 | 不适合 | 核心能力 |
| Git commit 绑定 | 非核心 | 需自建 | 非核心 | 非核心 | 核心能力 |
| 需求/代码/测试关联 | 非核心 | 需自建 | 需自建 | 不支持 | 核心能力 |
| Claim 级质量门 | 非核心 | 需自建 | 需自建 | 非核心 | 核心能力 |
| Stale 和版本 diff | 一般 | 需自建 | 部分支持 | 有限 | 核心能力 |
| MCP | 支持或可扩展 | 可扩展 | 支持 | 可扩展 | 核心能力 |
| 企业审计/权限 | 需配置或二次开发 | 自建 | 自建 | 自建 | 正在完善 |

NEXUS 的核心对象不是孤立文档块，而是：

```text
项目 + 业务版本 + 需求文档 + Git commit + 代码符号
       + 测试信号 + Wiki 页面 + Evidence + Claim
```

NEXUS 关注的问题是：

- 需求在当前版本是否实现；
- 哪些代码支持当前结论；
- 修改某个符号会影响哪些入口；
- 哪些测试支持当前实现；
- Wiki 是否已经过期；
- 两个版本之间发生了什么变化；
- Agent 使用的上下文是否跨项目、跨版本或未经审核。

## 5. 推荐集成策略

### P0：直接补齐 NEXUS 短板

集成 [Docling](https://github.com/docling-project/docling)、[MinerU](https://github.com/opendatalab/MinerU) 或 [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)，提升 PDF、表格、扫描件和结构化文档解析能力。

### P1：增强检索能力

- 引入 PageIndex 思路处理长篇架构文档、规范和法规；
- 借鉴 LightRAG 的 local/global/mix 检索；
- 将 Graphiti 作为可选的时序关系层；
- 使用 FlashRAG 的方法和数据组织方式增强离线评测。

### P2：评估底层框架替换

如果未来需要重写检索编排，可以评估 Haystack 或 LlamaIndex。但不建议当前直接替换 NEXUS 的 `RetrievalPipeline`，因为 NEXUS 已有自己的状态、Evidence、版本和质量门契约。

## 6. 不建议的选择

- 不建议用 RAGFlow 替换 NEXUS：会丢失研发版本和 Claim 语义。
- 不建议用 GraphRAG 替换 SQLite 代码图：GraphRAG 不能替代精确符号和调用关系。
- 不建议用 LightRAG 替换整个 NEXUS：图索引会增加成本，但不会自动解决研发证据治理。
- 不建议把 QAnything 作为新核心：AGPL-3.0、更新速度和维护风险需要谨慎评估。
- 不建议使用 Verba：项目已停止维护。

## 7. 最终结论

RAGFlow 是最完整的产品参考；LlamaIndex 和 Haystack 是工程框架参考；LightRAG 和 GraphRAG 是图检索参考；PageIndex 是长文档检索参考；FlashRAG 是评测参考。

但没有一个项目天然具备 NEXUS 所需的：

```text
需求 -> 代码 -> 测试 -> Wiki -> 版本 -> Evidence -> Claim -> 影响分析
```

NEXUS 的合理路线不是重新选择一个“大而全 RAG 引擎”，而是保持自己的研发事实层：

```text
Docling / MinerU
        |
        v
NEXUS 文档摄入与版本事实
        |
        +--> Qdrant 混合检索
        +--> SQLite 代码符号图
        +--> 可选 LightRAG / Graphiti 关系层
        +--> 可选 PageIndex 长文档索引
        |
        v
Evidence Registry -> Claims / 影响分析 / Wiki / MCP
```

> NEXUS 的差异化不是“也能做一个 RAG 聊天框”，而是让研发团队和 Agent 基于正确版本、明确来源和可回查证据完成理解、计划、评审和影响分析。

## 8. 官方仓库

- [RAGFlow](https://github.com/infiniflow/ragflow)
- [LlamaIndex](https://github.com/run-llama/llama_index)
- [LightRAG](https://github.com/HKUDS/LightRAG)
- [Microsoft GraphRAG](https://github.com/microsoft/graphrag)
- [PageIndex](https://github.com/VectifyAI/PageIndex)
- [Graphiti](https://github.com/getzep/graphiti)
- [Haystack](https://github.com/deepset-ai/haystack)
- [RAG-Anything](https://github.com/HKUDS/RAG-Anything)
- [txtai](https://github.com/neuml/txtai)
- [LEANN](https://github.com/StarTrail-org/LEANN)
- [R2R](https://github.com/SciPhi-AI/R2R)
- [FlashRAG](https://github.com/RUC-NLPIR/FlashRAG)
- [Docling](https://github.com/docling-project/docling)
- [MinerU](https://github.com/opendatalab/MinerU)
- [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)
