# NEXUS 0.8.2 可信文档召回评测规范

> 状态：设计与固定资产准备中；尚未执行测试或生成正式分数。
>
> 日期：2026-07-31

## 1. 为什么需要 v2

0.8.1 的 `Document Recall@10=1.0` 来自一个单需求文件的黄金集。48 个文档 HIT 用例都指向同一个文件，且没有章节或子块位置标签，所以它只能说明 Top-10 中出现了正确文件，不能说明检索找到了正确章节或正确证据子块。

v2 的目标不是先追求更高分，而是让分数能够区分以下情况：

- 找对文件但找错章节；
- 找对章节但返回错误子块；
- 相似文档共享关键词，但真正约束只存在于目标文档；
- `parentText` 含答案而当前返回的 `childText` 不含答案；
- 同一用例重复运行导致质量分母被重复放大。

## 2. 固定资产

| 资产 | 计划路径 | 版本契约 |
|---|---|---|
| 多文档语料 | `src/test/resources/evaluation/document-v2/` | 文件名稳定；内容变更必须更新 manifest 和黄金集 |
| 黄金集 | `src/test/resources/evaluation/retrieval-eval-document-v2.jsonl` | JSONL；case id 唯一；禁止 point/vector id |
| 语料 manifest | `src/test/resources/evaluation/document-v2/manifest.json` | 记录文件 SHA-256、字节数、章节/anchor 清单 |
| 正式运行产物 | `target/retrieval-evaluation/0.8.2-document-v2/` | 后续生成，不纳入版本库 |

初始固定集至少包含 6 个文件、12 个章节和 24 个 HIT case。主题刻意互相接近：审批与发布、权限冻结与撤销、审计保留、发布回滚、配额限流、仓库索引。共享词包括“审批、撤销、保留、恢复、限制、同步”，用于形成 hard negative。

## 3. 黄金标签

```json
{
  "filename": "access-revocation.md",
  "parentOrder": 1,
  "childOrder": 2,
  "mustContain": ["撤销传播必须在五分钟内完成"]
}
```

字段含义：

- `filename`：文件级稳定标识；
- `parentOrder`：父块/章节位置；
- `childOrder`：父块内子块位置；
- `mustContain`：只允许在目标 `childText` 中验证的稳定短语，不允许从 `parentText` 或其他子块补齐。

兼容规则：旧 v1 标签缺少 `parentOrder` / `childOrder` 时仍可加载，但只能进入 File Recall；不得把它当成 Section/Child Recall 样本。

## 4. 指标定义

### 4.1 File Recall@10

Top-10 中出现任一黄金 `filename` 即命中。它回答“有没有找对文件”。

### 4.2 Section Recall@10

仅对提供 `parentOrder` 的黄金标签计分。候选必须同时匹配 `filename + parentOrder`。它回答“有没有找对章节/父块”。

### 4.3 Child Recall@10

仅对提供 `childOrder` 的黄金标签计分。候选必须同时匹配 `filename + parentOrder + childOrder`，并且候选自身 `childText` 满足全部 `mustContain`。它回答“最终证据块是否正确”。

### 4.4 MRR@10

每个 case 使用最严格的可用文档层级：有 child 标签时使用 Child rank，否则有 section 标签时使用 Section rank，否则使用 File rank。MRR 越大越好。

### 4.5 唯一用例与重复执行

- File/Section/Child/Code Recall 和 MRR：按唯一 case 统计；
- P50/P95、BGE 调用、降级、错误波动：按全部执行统计；
- 同一 case 重复三次时，质量分母为 1，延迟样本为 3。

## 5. Hard-negative 分类

| 标签 | 场景 | 预期 |
|---|---|---|
| `same-term-different-policy` | 多文件都有同一术语，但约束值不同 | 只命中含目标约束的文件/子块 |
| `similar-workflow-wrong-stage` | 流程相似但阶段不同 | 错阶段最多算文件命中，不算章节/子块命中 |
| `synonym-cross-document` | 查询使用同义词，多个文件均有相近描述 | 目标 anchor 决定最终 child hit |
| `parent-text-leakage` | 父块含目标短语，返回的子块不含 | Child miss |
| `cross-file-near-duplicate` | 两文件句式相近、责任主体不同 | 不得把近似文件当作目标文件 |
| `negative-no-result` | 查询约束不在固定语料 | 文档和代码结果均为空才算正确 |

## 6. 报告要求

JSON 和 Markdown 至少同时展示：

- 唯一 case 数与执行数；
- File/Section/Child/Code Recall@10 的分子、分母和比率；
- MRR@10；
- no-result accuracy；
- P50/P95；
- hard-negative 分类分布；
- 分层失败归因；
- 数据集与每个语料文件 SHA-256。

旧 `summary.documentRecallAt10` 字段暂时保留给现有比较工具；v2 的正式结论必须优先引用 `uniqueCaseSummary` 中的分层指标。

## 7. 禁止事项

1. 不允许读取整篇 `parentText` 来证明当前 child 命中；
2. 不允许跨 parent 累积 `mustContain`；
3. 不允许按 repetitions 重复计算质量分母；
4. 不允许把 v1 File Recall 与 v2 Child Recall 直接比较；
5. 不允许在生产排序代码中写入 v2 文件名、case id、anchor 或项目特例；
6. 未完成正式运行前，不填写或猜测 0.8.2 分数。

## 8. 实施顺序

1. 冻结语料、黄金集和 manifest；
2. 扩展黄金标签与数据校验；
3. 修复 matcher 并增加三层 rank；
4. 扩展唯一用例摘要与 Markdown；
5. 先运行 matcher/数据集定向回归，再运行完整 Java 21 门禁；
6. 最后才执行 0.8.1 → 0.8.2 正式对照。
