# 实体中心的多版本知识检索 — 实施计划

## 任务树

- `08-27-entity-centric-knowledge-retrieval`（父：本 PRD/设计）
  - `08-27-ec-phase1-entity-base` — 跨版本实体基础（**本批次实施**）
  - `08-27-ec-phase2-llm-extraction` — LLM 实体提取与归一化
  - `08-27-ec-phase3-entity-query` — EntityQueryService + `/api/knowledge/entity-search`
  - `08-27-ec-phase4-fact-priority` — 事实优先级与实现偏差
  - `08-27-ec-phase5-answer` — AI 带证据回答
  - `08-27-ec-phase6-lightrag-graph` — 局部图 + 向量优化（评测驱动）

## 串行约束

- **严格串行实施**：一次只做一个子任务，完成（含测试）后再进入下一个（用户套餐并发数为 1）。
- 子任务顺序：1 → 2 → 3 → 4 → 5；6 由评测数据决定是否开展。
- 每个子任务完成后：`./mvnw test` 全绿 + CHANGELOG 同 commit 更新。

## 验证命令

```bash
cd /Users/user/Documents/request-RAG
./mvnw -q -Dtest=BusinessConceptServiceTest test   # Phase 1
./mvnw -q -Dtest='*Entity*Test' test                # Phase 3+
./mvnw test                                         # 全量回归
node --check <改动的 js>                             # 前端改动
```

## 质量门（dev md §15.3）

跨项目泄漏 = 0；错误版本泄漏 = 0；无 Evidence 的确定结论 = 0；代码/参数冲突静默丢失 = 0；
无来源的 LLM 实体自动落库 = 0。

## 评审节奏

每完成一个子任务，自跑 review（对照 dev md 验收）→ 修复 High/Medium → 再评审，直到无 High/Medium。
