# 双体系检索 PRD — 文档与代码分离

## Goal
代码和文档用两套可独立演进的检索体系，文档侧支持 HTML 结构化+图文理解+PageIndex树导航，代码侧保持 Tree-sitter+符号图，各自独立调参与缓存，统一证据层仍满足 EvidenceRegistry 契约。

## Requirements
- 文档/代码各自独立的 topK/超时/重排开关，指纹分离，缓存按业务项目+仓库范围隔离
- RetrievalPipeline 保留统一编排入口，内部委托 DocumentRetrievalService / CodeRetrievalService
- 文档 Loader 用 Jsoup 保留标题/表格/alt，图走 Vision caption 并注入 KnowledgeEntry
- PageIndex 为可选层，缺目录或 LLM 失败回退 hybridSearch
- 代码侧保持 EXACT/SAME_FILE 确定性影响与 code-aware 稀疏

## Acceptance
- RetrievalProfile 四画像隔离不变
- RagOutcome/DEGRADED/SUCCESS/NO_RESULTS/Warning 契约不变
- 现有 RetrievalPipeline* / Qdrant* / Mcp* / Wiki* 测试绿
