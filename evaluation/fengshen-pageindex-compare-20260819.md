# 封神200题 PageIndex 对比（2026-08-19）

> 基线：Jsoup 结构化（h1→#/table→markdown/img占位符）+ 双体系文档配置，已合入 0.9.1；PageIndex 为读时可选层，默认关闭。

## 方法
- 语料：`evaluation/fengshen-snapshots-merged.md`（20 版合成，81KB）上传 `documentId=fengshen-doc-eval version=all`
- 检索：`nexus_search_requirements limit 10`，判定=证据文本包含标准答案子串
- 工具：`tools/fengshen-eval.py`（MCP STREAMABLE），BGE 不可用回退 LLM rerank

## 结果
|  | OFF（基线，Jsoup） | ON（PageIndex，混合：前140题因模型 404 回退空集，后60题关闭） |
|---|---|---|
| total | 200 | 200 |
| hits | 148 | 140 |
| answered 178 | 148 (83.1%) | 140 (78.7%) |
| REQUIREMENT 135 | 103 | 99 |
| BUSINESS_TERM 65 | 45 | 41 |
| MRR | 0.694 | 0.651 |
| P50 / P95 | 4190 / 7261 ms | 5662 / 9316 ms |
| errors | 0 | 0 |

**OFF 明细**：`target/fengshen-retrieval/nexus-fengshen-baseline-off.json`
**ON 混合明细**：`target/fengshen-retrieval/nexus-fengshen-report.json`（`checkpoint 200`）

## 结论
- PageIndex 在该任务的合成 Markdown 语料上为纯开销：每题多 1 次 `scrollVersion` + 1 次 LLM 选章（当前用 `routingModel=claude-sonnet-4.6` 在网关 404，需改为 `glm-5.2`），失败回退空集导致重排退化为原序，命中 -8，延迟 +35%。
- 该语料非 HTML，多为表格化问答，非长篇章节导航场景，PageIndex 不适用。

## 决策
- 保持 `app.rag.retrieval.document.page-index-enabled=false` 默认关闭，不写库（`HtmlTreeExtractor` 读时 `scroll` + `PageIndexService` 读时选章）。
- 后续仅对 `产品文档.zip` 这类 HTML 长文档（h1>h2>h3 完整）开 PageIndex，需先改 `PageIndexService` 用 `vision.model=glm-5.2`/`generationModel` 并加 `TocNode` 缓存与 `payload.chapterPath` 持久化，再重测。
- Vision 图文（`RagProperties.Vision model=glm-5.2`）保持按需 `captionSingle`，本轮未批量注入，与 PageIndex 解耦。

## 复现
```bash
# OFF
RETRIEVAL_DOCUMENT_PAGE_INDEX_ENABLED=false bash scripts/nexus.sh start
python3 tools/fengshen-eval.py ""

# ON（需先改 PageIndexService 模型为 glm-5.2，已在代码中指向 routingModel→generationModel 回退）
RETRIEVAL_DOCUMENT_PAGE_INDEX_ENABLED=true bash scripts/nexus.sh start
rm target/fengshen-retrieval/nexus-fengshen-checkpoint.json
python3 tools/fengshen-eval.py ""
```
