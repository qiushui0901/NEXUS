# NEXUS 0.8.2 可信文档召回评测

## Goal

修正文档召回指标口径，建立多文档、多章节、包含 hard negative 的固定语料，分别报告 File/Section/Child Recall@10，并修复 matcher 通过整段 `parentText` 误判子块命中的泄漏问题。

## Requirements

1. 保留 0.8/0.8.1 历史报告和旧 JSON 字段的可读性，不改写既有正式结果。
2. 文档明确说明 0.8.1 的 `Document Recall@10=1.0` 仅表示单文件、文件级黄金标签全部命中，不代表章节或子块级语义召回达到 100%。
3. 新增版本化 v2 固定语料与黄金集：至少 18 个文档、每个文档至少 2 个可区分章节、
   至少 24 个唯一 HIT 用例；6 个黄金文档的每个主题至少配 2 个独立语义 hard negative。
4. `GoldDocument` 支持可选 `parentOrder` 与 `childOrder`。旧数据集不提供结构化位置时仍可加载。
5. 报告分别输出 File/Section/Child Recall@1/@3/@5/@10；只在该层级存在黄金标签时计入分母，
   并保留原有扁平 @10 字段。
6. 质量指标按唯一 case 统计，重复运行只参与延迟和稳定性统计。报告保留执行级统计以兼容现有比较工具。
7. 子块命中只能由目标 child 的文本满足 `mustContain`；不得因同一 parent 的全文包含目标短语而命中。
8. 章节命中必须匹配 filename + parentOrder；子块命中必须匹配 filename + parentOrder + childOrder。
9. 增加结构化位置、父文本泄漏、唯一用例去重和 v2 数据集约束的回归测试。
10. 优化 child-first 模式的 parent 代表项选择：保持 BGE 决定的 parent 顺序，只在同 parent
    的候选 child 自身与查询的稀疏相似度显著提升时替换代表项；不得编码 case-specific 规则，
    不得改变 legacy parent-first 行为。

## Acceptance Criteria

- [x] 历史文档明确标注 0.8.1 文档 1.0 的单文件/文件级适用范围和局限。
- [x] v2-v2 固定语料包含 18 个文件、至少 36 个 parent 和 24 个唯一文档 HIT 用例；
  其中 12 个文件是带 `hardNegativeFor` 映射的独立语义 hard negative。
- [x] v2 黄金标签能够稳定定位 filename、parentOrder、childOrder，数据集校验拒绝不合法 childOrder。
- [x] JSON 与 Markdown 报告同时展示唯一 case 的 File/Section/Child Recall@10。
- [x] 报告新增 File/Section/Child Recall@1/@3/@5/@10；@10 满分但较小 cutoff 仍 miss 时输出
  小语料排序敏感度提示，原有 @10 JSON 字段保持兼容。
- [x] 现有 `summary.documentRecallAt10` 等字段保持兼容；新增唯一用例统计不把 repetitions 重复计入质量分母。
- [x] “正确文件但错误章节”“正确章节但错误子块”“父文本含短语但子块不含短语”均有回归测试且不会误判为对应层级命中。
- [x] 旧 v1 数据集和比较脚本测试继续通过。
- [x] Java 21 `verify`、Python comparison tests、脚本语法检查与 `git diff --check` 通过。
- [x] parent 聚合能够修复已确认的代表项丢失，同时保留 BGE parent 顺序、并列/边际提升代表项
  和单 parent 快捷路径；定向测试与 v2-v2 真实 calibration 均无质量回退。

## Constraints

- 不引入 GraphRAG 或新的外部数据库。
- 不为提升评测分数编码 case-specific 查询、文件名或黄金答案。
- 固定语料不得包含凭据、私有地址或不稳定向量/point ID。
- 本任务先提升评测可信度，不将 v2 初始得分包装为检索算法质量提升。
