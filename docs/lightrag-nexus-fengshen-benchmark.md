# 封神需求文档召回对比报告(LightRAG × NEXUS)

> 日期：2026-08-12（NEXUS 复测）
> 测试集：`evaluation/fengshen-document-retrieval-eval-200.jsonl`(200 例,封神项目官方题库)
> 语料：封神需求快照 20 版本(`data/requirement-snapshots/immortal-game-service/`,来源 `封神版本问题整理.xlsx`)——两侧使用同一份语料
> 判定口径：**检索返回内容包含标准答案原文(子串)**;两侧完全一致
> LightRAG：1.5.6,mix 模式,`only_need_context`;模型 `gpt-5.6-sol`(抽取/关键词)+ `text-embedding-v4`(嵌入)
> NEXUS：0.8.5-SNAPSHOT,`nexus_search_requirements` MCP 工具(REQUIREMENT_REVIEW profile,limit=10,BGE 重排在线)

## 一、总体结果（2026-08-12 排名复测）

> 排名口径（两侧一致）：按检索返回顺序取前 K 项证据/chunk，命中 = 该项文本包含标准答案原文；未进入前 10 记 0。LightRAG 用 `aquery_data` 的有序 chunks 判定，NEXUS 用 MCP 有序 evidence 判定。分母为 200 全量（22 例 PENDING 天然不命中，已单列）。

### 1.1 命中率

| 指标 | LightRAG (mix) | NEXUS (0.8.5) |
|---|---:|---:|
| 全量 200 例命中率 | 70.5%(141/200) | **73.5%**(147/200) |
| ANSWERED 178 例 | 79.2%(141/178) | **82.6%**(147/178) |
| 可命中上限子集 151 例 * | 93.4%(141/151) | **97.4%**(147/151) |
| PENDING_PRODUCT_CONFIRMATION 22 例 | 0%(0/22) | 0%(0/22) |
| REQUIREMENT 类 135 例 | **78.5%**(106/135) | 75.6%(102/135) |
| BUSINESS_TERM 类 65 例 | 53.8%(35/65) | **69.2%**(45/65) |

### 1.2 排名指标（200 全量分母）

| 指标 | LightRAG (mix) | NEXUS (0.8.5) |
|---|---:|---:|
| Recall@1 | 57.0%(114/200) | **71.0%**(142/200) |
| Recall@5 | 69.5%(139/200) | **73.0%**(146/200) |
| Recall@10 | 70.5%(141/200) | **73.0%**(146/200) |
| MRR@10 | 0.622 | **0.719** |
| 平均首命中排名 | 1.38(141 道) | **1.03**(146 道) |
| 空召回率 | 29.5%(59/200) | **27.0%**(54/200) |
| 查询延迟 P50 / P95 | **577ms / 831ms** | 14.5s / 18.1s |

\* 178 例 ANSWERED 中仅 151 例的标准答案原文存在于快照语料中,其余 27 例答案在任何检索下都无法命中——151 是双方的理论上限。

## 二、结论

1. **文档召回两者接近,NEXUS 略优但差距远小于代码侧**:Recall@10 73.0% vs 70.5%、MRR 0.719 vs 0.622;可命中子集上双方都超过 93%。类型上各有胜负:REQUIREMENT 类 LightRAG 略高(78.5% vs 75.6%),BUSINESS_TERM 类 NEXUS 明显更高(69.2% vs 53.8%,术语类查询 NEXUS 的混合检索+重排优势)。
2. **与代码侧对比是核心洞察**:同一测试方法论下,代码检索 NEXUS 碾压(Recall@10 93.2% vs 46.6%),文档检索只是小幅领先(73.0% vs 70.5%)——**NEXUS 的相对优势集中在结构化(符号)检索,文本检索两者相当**。
3. **延迟反转**:文档侧 LightRAG 快 25 倍(577ms vs 14.5s)!NEXUS 的 REQUIREMENT_REVIEW 流程带 BGE 重排 + agentic 补检 + LLM,文档检索管道远比代码检索(351ms)重。若 NEXUS 需要在线低延迟,应评估关闭补检或分级重排。
4. **PENDING 22 例双方均为 0**:标准答案是不存在的(元描述"待产品确认"),语料中无对应原文,该维度需按"识别为待确认"另行评测(如 NEXUS 的 no-result accuracy 体系)。
5. **模型敏感**:两侧共用网关;本次 LightRAG 用 `gpt-5.6-sol`(此前 claude-sonnet-5 的 Vertex 上游被禁用)。抽取模型直接影响 LightRAG 图谱质量与召回。

## 三、评测方法

- 测试集 200 例:`{query, answerStatus, answer, goldDocument{sheet}}`,覆盖封神 1.1~5.1 共 24 个 sheet。
- LightRAG:语料为 20 个版本快照(文档级),`aquery_data(mode="mix", top_k=60, chunk_top_k=10)`,取最终有序 chunks 判定。
- NEXUS:语料为 20 版本快照合成单 md 上传(`documentId=fengshen-doc-eval, version=all`),经 `nexus_search_requirements` 检索(limit=10)。注意:NEXUS 检索 filter 要求 documentId+version 双字段,缺任一返回空。
- 命中 = 单项候选(evidence/chunk)文本包含标准答案原文子串;Recall@K 按候选顺序取前 K 项,MRR 取首个命中排名(未进前 10 记 0)。两侧判定语义一致,载体不同(NEXUS 结构化 evidence、LightRAG 文本 chunk)。
- PENDING 22 例答案(元描述)不存在于语料,天然不命中,已单列。
- 副产品:两套脚本均支持断点续传与逐例诊断(`target/fengshen-retrieval/nexus-fengshen-report.json`、`benchmark/lightrag-fengshen-report.json`)。

## 四、可复现命令

```bash
# LightRAG 侧
cd /Users/user/Documents/LightRAG
set -a; source /Users/user/Documents/request-RAG/.env; set +a
export GENERATION_MODEL=gpt-5.6-sol LLM_TIMEOUT=600
.venv/bin/python benchmark/fengshen_doc_eval.py

# NEXUS 侧(需应用在 8080 运行,语料已上传)
/Users/user/Documents/LightRAG/.venv/bin/python /Users/user/Documents/request-RAG/tools/fengshen-eval.py ""
```
