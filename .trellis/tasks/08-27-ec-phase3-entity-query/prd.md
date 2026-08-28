# Phase 3 实体中心证据查询 PRD

## Goal

用户不选版本也能查询实体的全时间轴证据：`POST /api/knowledge/entity-search` 按实体聚合所有版本
的需求/参数/测试/代码/证据/关系/冲突，强制当前参数表与当前代码上下文，返回结构化证据包
（第一版不改动现有 `/api/knowledge/multi-source/search`）。

## Requirements（对照 dev md §8、§12.1、§16 最小闭环）

1. `EntityQueryService` + `POST /api/knowledge/entity-search`（请求：projectId/query/versions/includeHistory/
   includeCode/includeParameters/includeTests/limit）。
2. `EntityEvidenceAggregator`：对每个解析到的实体聚合——所有版本需求 Claim / 参数表 Claim / 测试 /
   测试结果 / 当前代码上下文与代码 Evidence / 别名 / Claim-Evidence 关系 / 跨源关系 / 同 factKey 冲突 /
   生效区间与 supersedes 关系。
3. `versions` 为空 = 全部相关版本；填写 = 缩小时间轴，不改变实体解析。
4. 强制的当前事实：当前参数表（最新版本 findParameters 命中实体子集）与当前代码（CODE 成员 + commitSha）；
   缺少时返回稳定告警（CODE_CONTEXT_UNAVAILABLE / PARAMETER_TABLE_UNAVAILABLE），不伪造 commit。
5. 响应按实体分组：currentFacts（code/parameterTables/testResults）+ timeline（按 businessVersion）+ relations +
   conflicts + warnings；`factAssessment` 预留骨架（Phase 4 填充）。
6. 分页与数量上限：每实体的版本块与 claim 数量有上限，避免把全量 Claim 一把丢给前端。
7. 不改变现有 multi-source/search 行为。

## Acceptance

- 查询一个实体能看到所有相关版本的数据；`currentFacts` 区分 code/parameterTables/testResults。
- 未指定版本时展示全部相关版本时间轴；指定版本时只返回该版本块。
- 无代码索引时返回 CODE_CONTEXT_UNAVAILABLE 告警且不编造 commit；无数值表时返回 PARAMETER_TABLE_UNAVAILABLE。
- 同 factKey 不同值 → conflicts 中标记 CONFLICTED（确定性，不依赖向量分数）。
- 关系（含 PROPOSED 状态位）随实体返回，证据 ID 可回源。
- `./mvnw test` 全绿（新增聚合/服务/控制器测试）。