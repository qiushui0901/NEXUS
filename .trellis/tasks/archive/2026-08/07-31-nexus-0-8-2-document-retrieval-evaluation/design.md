# Design — NEXUS 0.8.2 可信文档召回评测

## 1. Compatibility strategy

- 扩展 `GoldDocument` 为 `filename, parentOrder, childOrder, mustContain`；Jackson 对缺失 `childOrder` 使用 `null`，旧 v1 JSONL 无需迁移。
- `CaseResult.documentRank` 保持为兼容主排名：优先 child，其次 section，最后 file。
- 新增 `documentFileRank`、`documentSectionRank`、`documentChildRank`，让报告和失败诊断可以区分层级。
- 现有 `Summary` 字段不删除；新增 `UniqueCaseSummary`，以 case id 去重后计算质量。

## 2. Matching contract

- File：候选 `filename` 相等即命中，不读取 parentText。
- Section：黄金标签有 `parentOrder` 时，候选必须匹配 filename + parentOrder；若提供 `mustContain`，只在该候选所属 section 的 childText 证据中判断，不拼接其他 parent。
- Child：黄金标签有 `childOrder` 时，候选必须匹配 filename + parentOrder + childOrder；`mustContain` 只检查该候选 `childText`。
- Legacy：旧黄金标签无结构化位置时，`documentRank` 采用 file rank，避免继续把整篇 parentText 当作子块语义命中。

## 3. Unique-case quality

- `summary` 保持执行级兼容统计。
- `uniqueCaseSummary` 按 `caseId` 选择最小 repetition 的一条结果；统计 File/Section/Child/Code Recall、MRR、no-result accuracy 与失败数。
- repetitions 的全部记录仍用于 P50/P95、BGE 调用、降级和稳定性观测。

## 4. V2 frozen corpus

- `document-v2-v2` 目录资产包含 18 个 Markdown 文件：6 个结构化黄金文件，以及围绕审批、
  权限、保留、恢复、限流和索引六类主题各 2 个独立 hard negative。
- 每个文件长度和边界设计为至少 2 个 parent，并在目标 child 放置稳定 anchor。
- 新黄金集 `retrieval-eval-document-v2.jsonl`：至少 24 个 HIT；同一术语在多个文档出现，但只有目标流程/约束 anchor 满足标签。
- Setup IT 支持由环境变量选择 fixture 目录、dataset version/documentId，并对目录内文件排序后批量导入；默认保持 v1 行为。
- manifest schema 2 标记 `gold` / `hard-negative` 角色和 `hardNegativeFor` 映射。
- 新增纯单元契约测试，使用真实 `ParentChildChunker` 验证每个 v2 gold 在全部 18 个文件中
  仍唯一映射到固定位置，并校验目录文件集、逐文件 hash、字符数和 parent/child 数。

## 5. Report presentation

Markdown 新增“唯一用例质量”表：File/Section/Child/Code Recall@10 与 MRR@10。若某层无黄金标签，显示 `N/A`。现有总体摘要继续输出，避免破坏 comparison parser。

## 6. Failure behavior

- 数据集拒绝负数 `parentOrder`/`childOrder`。
- `childOrder` 存在但 `parentOrder` 缺失时拒绝加载。
- 对同一个 case 的重复执行结果不一致时，质量摘要仍选择最小 repetition；稳定性问题由执行明细保留，后续可新增一致性指标。

## 7. Parent representative selection

- child-first 模式先按 BGE 排名建立有序 parent 分组，parent 的最终顺序由每组第一次出现的位置决定。
- 组内代表项仅使用 `query` 与候选自身 `childText` 的归一化稀疏余弦相似度比较，
  不读取共享 `parentText`，也不再调用外部 reranker。
- BGE 的首个 sibling 是默认代表。后续 sibling 必须同时满足绝对提升至少 `0.01`、
  且分数严格高于当前代表的 `1.10` 倍才可替换；并列或边际提升保留 BGE 选择。
- single-parent CPU 快捷路径在提前收口前复用同一代表选择器，避免固定保留首个 RRF child。
- legacy parent-first 模式继续用稳定 `putIfAbsent` parent 去重，不受本优化影响。
