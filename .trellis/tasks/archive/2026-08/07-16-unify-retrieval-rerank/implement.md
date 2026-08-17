# Implementation Plan: 统一检索与重排

## Phase A — Evaluation foundation

- [ ] 定义评测 case、文档 Gold、代码 Gold、outcome 和 tags 的测试侧模型。
- [ ] 实现逐行 JSONL 加载器，包含行号、重复 ID 和合同校验。
- [ ] 写入 2026-07-23 人工确认的首批 10 条 Gold Case。
- [ ] 实现文档/代码匹配和 Recall/MRR 计算的纯函数。
- [ ] 添加默认 CI 测试，覆盖合法数据集和主要非法输入。
- [ ] 实现显式启用的在线当前管线评测入口。
- [ ] 生成 `target/retrieval-evaluation/report.json` 和 `report.md`。

## Phase B — Unified pipeline

- [ ] 使用 CodeGraph 确认 DevelopmentPlanService、DevelopmentPlanStreamService、DoubtReviewService、CodeKnowledgeService 的重复边界。
- [ ] 添加 pipeline request/profile/bundle/options/stage 合同。
- [ ] 用现有 store 和 reranker 构建不改变排序行为的 pipeline。
- [ ] 添加 profile、零命中、单侧失败和阶段诊断测试。

## Phase C — Migration

- [ ] 迁移同步 DevelopmentPlanService，运行 Gold Dataset 比较。
- [ ] 迁移 DevelopmentPlanStreamService，保持 SSE 事件兼容。
- [ ] 迁移 DoubtReviewService，保留 BGE/LLM 重排顺序。
- [ ] 评估 CodeKnowledgeService 是否直接使用 CODE_SEARCH profile。

## Phase D — Expand and verify

- [ ] 将数据集扩展到约 50 条。
- [ ] 保存重构前后本地报告，逐条检查回退。
- [ ] 运行：`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify`。
- [ ] 运行：`git diff --check`。
- [ ] 检查 Git 状态，确保未暂存 Qdrant/vector/runtime 数据。

## Risk and rollback points

- 在线评测依赖本地 Qdrant/Ollama；默认 CI 绝不能隐式启动或依赖这些服务。
- Gold Label 不得绑定 point ID，否则重建 collection 后评测失效。
- 生产迁移前必须保留旧管线基线；没有基线不得删除旧编排路径。
- 生成模型输出不参与第一版检索 Gold 匹配，避免非确定性掩盖召回问题。
