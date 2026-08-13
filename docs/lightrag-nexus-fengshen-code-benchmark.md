# 封神代码召回对比报告(LightRAG × NEXUS)

> 日期：2026-08-12(全仓库补充实验:2026-08-12)
> 测试集：`evaluation/fengshen-code-retrieval-eval-500.jsonl`(500 例,gold = `filePath#symbolName`,query 自带符号名)
> 判定口径(两侧统一)：检索返回内容包含 `symbolName`(方法名精确 token);`filePath` 为辅助指标
> LightRAG：1.5.6,mix 模式;语料 = **全仓库 2139 文件**(签名+注释摘要,约 200 万字符,8 批合并文档入库)
> NEXUS：0.8.5-SNAPSHOT,`nexus_search_code` MCP 工具;语料 = **全仓库 2139 文件**(Tree-sitter 符号索引);本次复测记录排名与延迟

## 一、总体结果(全仓库公平对比,2026-08-12 复测)

> 口径(两侧一致):按检索返回顺序取前 K 项,判定是否**同时包含 `symbolName` 与 `filePath`**,首个同时命中的排名记位;未进入前 10 记 0。LightRAG 用 `aquery_data` 的结构化 chunks 判定,NEXUS 用 MCP evidence 顺序判定。

### 1.1 命中率(500 例)

| 指标 | LightRAG (全仓库) | NEXUS (全仓库) |
|---|---:|---:|
| symbolName 命中率 | 84.0%(420/500) | **97.8%**(489/500) |
| filePath 命中率(文件定位) | 62.6%(313/500) | **98.4%**(492/500) |
| 符号+文件同时命中 | 61.4%(307/500) | **96.4%**(482/500) |

### 1.2 排名指标(symbol+file 同命中,500 例)

| 指标 | LightRAG (全仓库) | NEXUS (全仓库) |
|---|---:|---:|
| Recall@1 | 15.0%(75/500) | **63.0%**(315/500) |
| Recall@5 | 35.4%(177/500) | **90.8%**(454/500) |
| Recall@10 | 46.6%(233/500) | **93.2%**(466/500) |
| MRR@10 | 0.225 | **0.7508** |
| 平均首命中排名 | 3.78 | **1.64** |
| 空召回率 | 53.4% | **6.8%** |

### 1.3 延迟与成本

| 指标 | LightRAG (全仓库) | NEXUS (全仓库) |
|---|---:|---:|
| 查询延迟 P50 / P95 | 1.0s / 1.3s | **351ms / 656ms** |
| 索引成本 | 2139 文件摘要 + **LLM 抽取数小时**(本次约 3.5 小时,含网关失败重试) | 无 LLM,纯解析 |

> 附:82 文件开卷实验(symbol 99.6% / filePath 98.2%)见文末附录。开卷(只喂 gold 文件)与全仓库的差距,正是本次复测的核心发现。

## 二、核心发现

1. **排名能力差距是决定性的**:Recall@10 上 NEXUS 93.2% vs LightRAG 46.6%——近 2 倍差距;Recall@1 更是 63.0% vs 15.0%(4.2 倍)。LightRAG 找到目标后平均要排到第 3.78 位,NEXUS 1.64 位。**过半(53.4%)的 LightRAG 查询前 10 chunk 里根本不含目标文件+符号**。
2. **全仓库下文本检索失效**:开卷(symbol 99.6%)到全仓库(symbol@10 84.0%、filePath@10 62.6%)的下跌说明:搜索空间变大后,LightRAG 的向量检索被大量同名符号、相似代码片段稀释;而 NEXUS 的符号表检索不受文本噪声影响。
3. **延迟差距 3 倍**:NEXUS 351ms/656ms vs LightRAG 1.0s/1.3s(结构化 `aquery_data` 已去掉 LLM 生成,仍慢 3 倍;若保留 LLM 生成则 4.6s)。
4. **索引成本差距是确定性的**:NEXUS 无 LLM 索引;LightRAG 全仓库摘要 + LLM 抽取约 3.5 小时(且受网关稳定性影响,失败需重试)。

## 三、结论

- **公平条件下,代码检索 NEXUS 全面领先**:Recall@10 领先 46.6 个百分点,MRR 3.3 倍,延迟 3 倍,索引零 LLM 成本。
- **symbol 指标需谨慎解读**:单独 symbol 命中率(LightRAG 84%)不能衡量定位能力——要求"符号+文件"同命中后 LightRAG 跌到 61.4%,Recall@10 只有 46.6%。
- **选型结论**:代码检索用 NEXUS;LightRAG 用于文档检索(需求召回对比见另一份报告)。LightRAG 作为"代码文本 RAG"在小范围(如单模块)或符号名提示下可用,但不具备符号表精度与排名质量。

## 附录:82 文件开卷实验(上限参考)

| 指标 | LightRAG (82 文件开卷) | NEXUS (全仓库) |
|---|---:|---:|
| symbolName 命中 | 99.6% | 97.8% |
| filePath 命中 | 98.2% | 98.4% |
| 延迟 P50/P95 | 4.4s/7.9s | 351ms/656ms |

开卷条件(只索引 gold 文件)下 LightRAG 与 NEXUS 持平,证明其文本检索在小范围+符号名提示下可用;该实验对 LightRAG 有利,不代表全仓库表现。
- **建议**:代码检索继续用 NEXUS;LightRAG 用于需求/文档检索(前一份报告)。若团队想验证 LightRAG 全仓库代码能力,跑全量索引实验,预计 LightRAG 符号命中率会明显低于 99.6%(符号冲突 + 检索噪声)。

> Recall@K/MRR@10 口径：NEXUS 按 MCP `data` 返回顺序判断前 K 项是否同时包含 `symbolName` 与 `filePath`，取首个同时命中的排名；未进入前 10 记 0。LightRAG 用 `aquery_data` 返回的最终有序 chunks 按同样规则判定（内容含 `# 文件: <path>` 标题与符号名）。

## 四、可复现命令

```bash
# LightRAG 侧(全仓库: 分批索引,再查询;失败批次需清理后重试)
cd /Users/user/Documents/LightRAG
set -a; source /Users/user/Documents/request-RAG/.env; set +a
export GENERATION_MODEL=gpt-5.6-sol LLM_TIMEOUT=600
.venv/bin/python benchmark/insert_code_all.py          # 全仓库代码→摘要→8 批入库
.venv/bin/python benchmark/cleanup_status.py benchmark/rag_storage_code_all  # 失败批次清理后重跑
CODE_STORAGE=benchmark/rag_storage_code_all .venv/bin/python benchmark/fengshen_code_eval.py  # 500 例查询

# 82 文件开卷版
.venv/bin/python benchmark/insert_code_82.py
.venv/bin/python benchmark/fengshen_code_eval.py

# NEXUS 侧(应用在 8080,全仓库代码已索引)
/Users/user/Documents/LightRAG/.venv/bin/python /Users/user/Documents/request-RAG/tools/fengshen-code-eval.py
```
