# NEXUS 0.8.2 可信文档召回评测规范

> 状态：实现与首次真实 calibration 已完成；尚未生成三重复正式延迟结论。
>
> 日期：2026-08-03

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
| calibration 产物 | `target/retrieval-evaluation/0.8.2-document-v2/` | 本地生成，不纳入版本库 |
| 固定 runner | `scripts/run-document-v2-eval.sh` | setup + 真实依赖契约 + calibration |

当前 `document-v2-v2` 固定集包含 18 个文件、至少 36 个生产 parent 和 24 个结构化 HIT
case。6 个黄金文件覆盖审批、权限撤销、审计保留、发布回滚、配额限流和仓库索引；每个主题
另配 2 个独立语义 hard negative，共 12 个干扰文件。manifest schema 2 使用
`role=gold|hard-negative` 和 `hardNegativeFor` 显式记录关系。

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

### 4.1 File Recall@1/@3/@5/@10

对应 Top-K 中出现任一黄金 `filename` 即命中。它回答“有没有找对文件”，并用较小 cutoff
暴露正确文件虽然进入 Top-10、但排序仍然靠后的情况。

### 4.2 Section Recall@1/@3/@5/@10

仅对提供 `parentOrder` 的黄金标签计分。候选必须同时匹配 `filename + parentOrder`。它回答“有没有找对章节/父块”。

### 4.3 Child Recall@1/@3/@5/@10

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
- File/Section/Child Recall@1/@3/@5/@10 的分子、分母和比率；
- 兼容字段 File/Section/Child/Code Recall@10；
- MRR@10；
- no-result accuracy；
- P50/P95；
- hard-negative 分类分布；
- 分层失败归因；
- 数据集与每个语料文件 SHA-256。

旧 `summary.documentRecallAt10` 以及 `uniqueCaseSummary` 中原有的扁平 @10 字段继续保留给现有
比较工具；新增 `fileRecallByCutoff`、`sectionRecallByCutoff`、`childRecallByCutoff`。当任一文档层
@10 满分而较小 cutoff 仍有 miss 时，报告输出 ranking-sensitivity warning。

历史 `document-v2-v1` 的 6 文件结果只用于校准评测器和定位排序问题。当前 v2-v2 已扩展到
18 个独立文件，超过 Top-10 cutoff，并为每类黄金主题加入两个语义相近、结论不同的 hard
negative。机械复制或只改文件名不计入独立语料；v2-v1 与 v2-v2 的分数不得直接混写。

## 7. 禁止事项

1. 不允许读取整篇 `parentText` 来证明当前 child 命中；
2. 不允许跨 parent 累积 `mustContain`；
3. 不允许按 repetitions 重复计算质量分母；
4. 不允许把 v1 File Recall 与 v2 Child Recall 直接比较；
5. 不允许在生产排序代码中写入 v2 文件名、case id、anchor 或项目特例；
6. 未完成正式运行前，不填写或猜测 0.8.2 分数。

## 8. 实施顺序

1. [x] 冻结语料、黄金集和 manifest；
2. [x] 扩展黄金标签与数据校验；
3. [x] 修复 matcher 并增加三层 rank；
4. [x] 扩展唯一用例摘要与 Markdown；
5. [x] 运行 matcher/数据集定向回归和完整 Java 21 门禁；
6. [x] 启动固定 Qdrant、Ollama 和 BGE 依赖，执行 0.8.2 初始真实 calibration。
7. [x] 将小语料升级为 `document-v2-v2` 的 18 文件独立 hard-negative corpus。
8. [x] 重建隔离 collection 并完成 v2-v2 的 24 case 真实 calibration。

## 9. 2026-08-03 实施与验证状态

已完成：

- v2-v2 固定语料包含 18 个文件、至少 36 个父块和 24 个唯一结构化 HIT case；
- 12 个新增文件分别映射到 6 个黄金主题，内容规则、角色、阶段和数值均独立，不是复制文本；
- `GoldDocument` 增加可选 `parentOrder` / `childOrder`，旧三参数构造和 v1 JSONL 保持兼容；
- matcher 独立计算 file、section、child rank，最严格可用层级决定文档 MRR；
- child anchor 只检查候选自己的 `childText`，不再借用 `parentText`；
- JSON/Markdown 新增 `uniqueCaseSummary` 和文档分层 @1/@3/@5/@10，质量分母按唯一 case，
  延迟和依赖统计仍按执行次数；
- setup 支持单文件或目录 fixture，并记录逐文件指纹；`document-v2-eval` 使用隔离 collection；
- 25 项定向 Java 测试、Java 21 全量 `clean verify`（284 项）、15 项 Python comparison
  测试、Python 编译、shell 语法、任务上下文校验和 `git diff --check` 均通过。

v2-v2（18 文件）真实 calibration：

- setup 向隔离 Qdrant collection 写入 18 个文件、111 个需求块；24 个唯一 case 各执行
  一次，BGE 24/24 成功，degradation 与 infrastructure failure 均为 0；
- File Recall@1/@3/@5/@10 为
  `0.875000 / 0.916667 / 0.916667 / 1.000000`；
- Section Recall@1/@3/@5/@10 为
  `0.833333 / 0.875000 / 0.916667 / 1.000000`；
- Child Recall@1/@3/@5/@10 为
  `0.791667 / 0.833333 / 0.875000 / 0.916667`，MRR@10 `0.828125`；
- `top10MasksLowerCutoff=true`：File/Section 虽在 @10 命中 24/24，但 @1 仅为
  21/24 和 20/24。扩展后的曲线不再在较小 cutoff 饱和，能够观察硬负样本造成的排序差异；
- 2 个严格 child miss 均为 `DOCUMENT_PARENT_AGGREGATION_LOSS`：
  `doc-v2-approval-workflow-02` 与 `doc-v2-release-rollback-02`；
- P50 `49,594 ms`、P95 `97,672 ms` 仅描述本次单重复 CPU calibration，不是性能门禁。

parent 代表项优化后的同语料 calibration：

- child-first 模式保持 BGE 的 parent 顺序，只在同 parent sibling 的 `childText` 稀疏
  相似度绝对提升至少 `0.01`、且严格超过当前代表 `10%` 时替换；并列和边际提升保留
  BGE 代表，single-parent 快捷路径复用相同选择器；
- File 与 Section 的 @1/@3/@5/@10 均与上述优化前基线相同；
- Child Recall@1/@3/@5/@10 提升为
  `0.833333 / 0.875000 / 0.916667 / 1.000000`，MRR@10 提升为 `0.876736`；
- 严格失败由 2/24 降为 0/24；BGE 24/24 成功，degradation 与 infrastructure failure
  均为 0；
- 原两个聚合丢失 case 的 child rank 分别恢复为 6 和 1；边际分数防回退 case 仍为
  rank 4，说明保守阈值没有用词面小幅提升覆盖原 BGE 选择；
- P50 `52,073 ms`、P95 `69,296 ms` 仍只描述单重复 CPU calibration。

验证期间曾有一次 BGE 请求在约 121 秒后降级，评测器将该运行标记为 infrastructure
contaminated 并拒绝发布分数；同配置完整重跑通过后才记录上述结果。

历史 v2-v1（6 文件）真实 calibration：

- runner 固定 Qdrant `1.15.4`、Ollama `bge-m3`、BGE `BAAI/bge-reranker-v2-m3`
  CPU / max length 384 / batch size 4、branch timeout 30 秒、BGE read timeout 120 秒；
- setup 写入 6 个文件、39 个需求块；24 个唯一 case 各执行一次，BGE 24/24 成功，
  infrastructure failure 为 0；
- File Recall@1/@3/@5/@10 为 `0.958333 / 1.000000 / 1.000000 / 1.000000`；
- Section Recall@1/@3/@5/@10 为 `0.916667 / 1.000000 / 1.000000 / 1.000000`；
- Child Recall@1/@3/@5/@10 为 `0.833333 / 0.875000 / 0.875000 / 0.875000`，
  MRR@10 `0.854167`；
- File/Section 的 @10 已出现小语料饱和，不能再用两个 `1.0` 证明排序质量达到 100%；
- 3 个 child miss 均为 `DOCUMENT_PARENT_AGGREGATION_LOSS`：正确 child 进入 BGE 输出，
  但最终 parent 聚合保留了同 parent 的另一个 child；
- 新 cutoff 报告重跑的 P50 `54,828 ms`、P95 `78,905 ms` 只属于单重复 calibration，
  BGE 24/24 成功且 degradation / infrastructure failure 均为 0；延迟不作为稳定性能门禁，
  也不能与 v1 文件级 Document Recall 直接比较。

失败 case：

| Case | 查询 | BGE 输出中的 gold rank | 最终结果 |
|---|---|---:|---|
| `doc-v2-approval-workflow-02` | 发布前要核对哪两项内容？ | 20 | 同 parent 的 child 0 被保留 |
| `doc-v2-audit-retention-02` | 导出的审计文件多久失效？ | 2 | 同 parent 的其他 child 被保留 |
| `doc-v2-release-rollback-02` | 健康检查失败几次才自动回滚？ | 2 | 同 parent 的其他 child 被保留 |
