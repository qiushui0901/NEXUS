# 0.9.3 Phase C：关系、冲突与审核审计

## Goal

把多源关系生产从查询路径迁移到离线/发布前构建任务，新增统一关系表与抽取运行审计表，并提供人工审核 API，使每个关系可追溯到双方 Claim、Evidence、抽取运行与审核动作。

## Requirements

- 新增 `knowledge_relation`：状态（`RULE_PROPOSED / LLM_CONFIRMED / LLM_REJECTED / HUMAN_CONFIRMED / STALE`）、置信度、evidence、抽取/确认方式与原因。
- 新增 `knowledge_extraction_run`：parser/模型/提示词版本/input-output hash/token/状态/耗时审计。
- `MultiSourceSearchService` 查询路径**只读预生成关系**，按当前命中页剪裁一跳邻域；不再查询侧生成/持久化/调用 LLM。
- 提供人工审核 API：确认/拒绝/标记过期关系。
- 保留旧 `multi_source_relation` 作为迁移期只读回退。

## Acceptance Criteria

- [ ] 离线关系构建后可被查询读取，且抽取运行记录为 SUCCESS。
- [ ] LLM 拒绝/确认只改变关系状态与审计字段，不修改原始 Claim。
- [ ] 查询响应只包含当前页 Claim 的一跳关系，且不触发写库或 LLM。
- [ ] 人工审核 API 可更新关系状态为 `HUMAN_CONFIRMED / REJECTED / STALE` 并记录原因。
- [ ] 全量测试通过。