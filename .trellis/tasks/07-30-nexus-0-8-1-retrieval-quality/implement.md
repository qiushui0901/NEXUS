# NEXUS 0.8.1 Implementation Plan

## Phase 1 — Baseline and diagnostics

- [x] 固定/复制 0.8 正式基线产物，验证数据集、语料和运行环境指纹。
- [x] 为 requirement/code 各阶段增加候选数量、顺序变化和黄金排名诊断。
- [x] 增加报告与 comparison 工具的向后兼容测试。

## Phase 2 — Requirement quality

- [x] 将 requirement parent 去重从 BGE 前移动到 BGE 后。
- [x] 构造包含 filename/parent/child 的有界 BGE passage。
- [x] 增加 child-first、parent aggregation、fallback 和截断测试。
- [x] 运行 0.8.1 A/B，按 Document Recall/MRR 结果调节候选上限和 passage。

## Phase 3 — Code quality

- [x] 分离代码 candidate limit 与 final limit。
- [x] 加入有界代码重排/精排，并保护精确符号、路径 lexical 信号。
- [x] 增加代码排序、降级和延迟预算测试。
- [x] 运行 A/B，按 Code Recall/MRR 结果迭代。

## Phase 4 — Performance and quality convergence

- [x] 在固定环境运行 54-case、预热 1 次、重复 3 次正式对照。
- [x] 未达门槛时根据阶段诊断逐项调整，不更改黄金集或成功条件。
- [x] 达到 Document/Code Recall、MRR、污染率和 P95 门槛。

## Phase 5 — Verification and documentation

- [x] 运行定向 Java/Python 测试、Python compile、shell syntax。
- [x] 运行 Java 21 `./mvnw -B verify` 与 `git diff --check`。
- [x] 更新评测台账、路线图、任务验收项和 retrieval spec。
- [x] 记录实际报告路径、环境指纹、限制与剩余失败分布。

## Validation Commands

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify
.venv-bge-reranker/bin/python -m unittest tools/test_bge_reranker_service.py tools/test_retrieval_eval_comparison.py
.venv-bge-reranker/bin/python -m py_compile tools/bge-reranker-service.py tools/retrieval-eval-comparison.py
bash -n scripts/run-shiguang-eval.sh tools/start-bge-reranker.sh
git diff --check
```

## Rollback Points

1. diagnostics-only changes；
2. requirement child-first rerank；
3. code candidate/rerank changes；
4. evaluation/report updates。
