# NEXUS 0.8.2 可信文档召回评测

## Goal

修正文档召回指标口径，建立多文档、多章节、包含 hard negative 的固定语料，分别报告 File/Section/Child Recall@10，并修复 matcher 通过整段 `parentText` 误判子块命中的泄漏问题。

## Requirements

1. 保留 0.8/0.8.1 历史报告和旧 JSON 字段的可读性，不改写既有正式结果。
2. 文档明确说明 0.8.1 的 `Document Recall@10=1.0` 仅表示单文件、文件级黄金标签全部命中，不代表章节或子块级语义召回达到 100%。
3. 新增版本化 v2 固定语料与黄金集：至少 6 个文档、每个文档至少 2 个可区分章节、至少 24 个唯一 HIT 用例，并包含跨文档同义词、同词异义和近似流程等 hard negative。
4. `GoldDocument` 支持可选 `parentOrder` 与 `childOrder`。旧数据集不提供结构化位置时仍可加载。
5. 报告分别输出 File Recall@10、Section Recall@10、Child Recall@10；只在该层级存在黄金标签时计入分母。
6. 质量指标按唯一 case 统计，重复运行只参与延迟和稳定性统计。报告保留执行级统计以兼容现有比较工具。
7. 子块命中只能由目标 child 的文本满足 `mustContain`；不得因同一 parent 的全文包含目标短语而命中。
8. 章节命中必须匹配 filename + parentOrder；子块命中必须匹配 filename + parentOrder + childOrder。
9. 增加结构化位置、父文本泄漏、唯一用例去重和 v2 数据集约束的回归测试。

## Acceptance Criteria

- [ ] 历史文档明确标注 0.8.1 文档 1.0 的单文件/文件级适用范围和局限。
- [ ] v2 固定语料包含不少于 6 个文件、12 个章节和 24 个唯一文档 HIT 用例，并有明确 hard-negative 标签。
- [ ] v2 黄金标签能够稳定定位 filename、parentOrder、childOrder，数据集校验拒绝不合法 childOrder。
- [ ] JSON 与 Markdown 报告同时展示唯一 case 的 File/Section/Child Recall@10。
- [ ] 现有 `summary.documentRecallAt10` 等字段保持兼容；新增唯一用例统计不把 repetitions 重复计入质量分母。
- [ ] “正确文件但错误章节”“正确章节但错误子块”“父文本含短语但子块不含短语”均有回归测试且不会误判为对应层级命中。
- [ ] 旧 v1 数据集和比较脚本测试继续通过。
- [ ] Java 21 `verify`、Python comparison tests、脚本语法检查与 `git diff --check` 通过。

## Constraints

- 不引入 GraphRAG 或新的外部数据库。
- 不为提升评测分数编码 case-specific 查询、文件名或黄金答案。
- 固定语料不得包含凭据、私有地址或不稳定向量/point ID。
- 本任务先提升评测可信度，不将 v2 初始得分包装为检索算法质量提升。
