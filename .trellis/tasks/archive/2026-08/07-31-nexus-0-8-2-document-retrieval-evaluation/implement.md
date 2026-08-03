# Implementation Plan — NEXUS 0.8.2

1. 更新历史文档，对 0.8.1 `Document Recall@10=1.0` 增加范围声明。
2. 扩展黄金文档结构和数据集校验，保持 v1 兼容。
3. 重构 matcher：实现 file/section/child 三层排名，移除 parentText 泄漏。
4. 扩展 CaseResult/Report，新增唯一 case 的分层质量摘要和 Markdown 展示。
5. 新增 v2 多文档多章节 hard-negative 语料与 JSONL 黄金集。
6. 扩展 setup 使其可选择 v2 fixture 目录，并增加固定语料位置契约测试。
7. 运行定向测试、完整 Java 21 verify、Python 比较工具测试、脚本语法和 diff 检查。
8. 同步 backend spec、路线图/历史文档与任务验收项；保持任务未归档、工作树未提交。
9. 针对 6 文件语料的 @10 饱和，新增唯一 case 的 File/Section/Child
   Recall@1/@3/@5/@10 和 ranking-sensitivity warning；保留旧 @10 字段。
10. 将 corpus 升级到 `document-v2-v2`：18 文件、至少 36 parent、12 个独立语义
    hard negative，并让 corpus 契约测试覆盖全部文件和 manifest schema 2。
11. 优化 child-first parent 代表项选择：保留 BGE parent 顺序，以 child-only 稀疏相似度和
    保守增益阈值选择 sibling；single-parent 快捷路径复用该选择器，并补齐明显提升、并列、
    边际提升和快捷路径回归测试。

## Validation — 2026-08-03

- 25 项定向 Java 测试通过。
- Java 21 `./mvnw -B clean verify` 通过：284 tests，0 failures/errors/skips，JaCoCo 门禁通过。
- `python3 -m unittest tools/test_retrieval_eval_comparison.py`：15 项通过。
- Python 编译、`bash -n scripts/run-shiguang-eval.sh`、task validate 和 `git diff --check` 通过。
- Ollama、BGE 和本机 Qdrant 均已就绪；v2-v2 setup 已向隔离 collection 写入
  18 文件/111 需求块。固定 runner 完成 24 case 真实 calibration，BGE 24/24 成功，
  degradation 与基础设施失败均为 0。
- v2-v2 的 File Recall@1/@3/@5/@10 为
  `0.875000/0.916667/0.916667/1.000000`，Section 为
  `0.833333/0.875000/0.916667/1.000000`，Child 为
  `0.791667/0.833333/0.875000/0.916667`，MRR@10 为 `0.828125`。
- `top10MasksLowerCutoff=true`；新增 hard negative 使 File/Section 的较小 cutoff 不再饱和。
  2 个失败均为 `DOCUMENT_PARENT_AGGREGATION_LOSS`，本任务不编码 case-specific 修复。
- P50/P95 为 `49,594/97,672 ms`，只作单重复 CPU calibration 描述，不作为正式性能门禁。
- 历史 v2-v1 的 6 文件/39 需求块与分数保留在历史文档中，不与 v2-v2 混算。

## Parent representative optimization — 2026-08-03

- `SparseVectorizer` 新增归一化稀疏余弦相似度；child-first 聚合保持 BGE parent 顺序，
  仅在 sibling 的 child-only 分数绝对提升至少 `0.01` 且严格超过当前代表 `10%` 时替换。
- single-parent 快捷路径复用同一选择器；legacy parent-first 路径保持原稳定去重。
- `SparseVectorizerTest` 与 `RetrievalPipelineTest` 覆盖明显提升、并列、边际提升和
  single-parent 选择；Java 21 `clean verify` 通过 289 项测试，0 failures/errors/skips，
  JaCoCo 门禁通过。
- 优化后完整 v2-v2 calibration：24/24 BGE 成功、degradation 与基础设施失败为 0，
  严格失败从 2/24 降为 0/24。
- File 与 Section 各 cutoff 完全保持；Child Recall@1/@3/@5/@10 从
  `0.791667/0.833333/0.875000/0.916667` 提升到
  `0.833333/0.875000/0.916667/1.000000`，MRR@10 从 `0.828125` 提升到 `0.876736`。
- 原失败 `doc-v2-approval-workflow-02` 与 `doc-v2-release-rollback-02` 的 child rank
  分别为 6 和 1；边际分数防回退用例 `doc-v2-access-revocation-04` 保持 child rank 4。
- 有一次完整运行因单次 BGE 121 秒超时产生 1 个 infrastructure failure，该污染运行未
  纳入质量结果；随后同配置完整重跑通过。有效运行 P50/P95 为 `52,073/69,296 ms`，
  仍只作单重复 CPU calibration 描述。
