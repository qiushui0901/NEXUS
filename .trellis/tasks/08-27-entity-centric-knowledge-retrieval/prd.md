# 实体中心的多版本知识检索 PRD

## Goal

用户输入自然语言问题（无需先选版本）后，系统由 LLM 提取实体与意图，围绕**稳定实体 entityId**
聚合该实体在所有业务版本下的需求、数值表、测试、代码与证据，以**当前代码实现和当前数值表**为主要事实依据，
返回带引用的结构化证据与可选 AI 回答。

方案：`docs/entity-centric-knowledge-retrieval-implementation.md`（权威设计参考）。

## Requirements

- 实体是跨版本、跨来源统一主索引；版本是实体的时间轴维度；`versions` 为空 = 全部相关版本。
- 实体 ID 不含业务版本与来源类型；`param:/req:/test:` 前缀拆分必须去除，改为实体类型 + 成员角色（truthRole）。
- 同一实体在两个业务版本下返回同一 `entityId`；参数/需求/测试/代码成员可同时挂到同一实体。
- 历史成员不因重新导入最新版本而消失；`buildProject` 不删除未被本次输入覆盖的历史成员。
- 别名：LLM 提议的别名默认不直接成为高置信全局别名；需要 `origin/status/evidence` 支撑（第一版可用
  `normalization_method + confidence` 表达，后续迁移 `origin/status/evidence_ids`）。
- 关系（SUPPORTS/VERIFIES/IMPLEMENTED_BY/SUPERSEDES/REFINES/REPEALS/SAME_FACT）必须带来源、证据与
  `PROPOSED/CONFIRMED/REJECTED` 生命周期；`SUPERSEDES` 不能仅由版本号推断。
- 事实优先级：当前行为 CODE 最高；当前数值 PARAMETER_TABLE 最高；测试验证 TEST_RESULT；需求只解释目标与历史。
- 代码与数值表冲突时不得静默覆盖，必须报告实现偏差（`CODE_PARAMETER_MISMATCH` 等稳定类型）。
- 新增 `POST /api/knowledge/entity-search`（第一版返回结构化证据包，不改变现有 `/api/knowledge/multi-source/search`）。
- 向量只做实体发现与补召回，不决定事实正确性；不把多来源强行混入一个 Qdrant 集合。
- 每个最终结论必须绑定来源证据；无证据返回“无法确定”。

## Acceptance

- 同一实体在两个业务版本下返回同一 `entityId`。
- 参数、需求、测试成员可同时挂到同一实体。
- 历史成员不会因重新导入最新版本而消失。
- 用户问题中的实体能匹配已有 entityId；未命中时返回候选实体而非伪造 ID。
- 查询一个实体能看到所有相关版本；当前代码和数值表始终出现在 `currentFacts`。
- 代码值与数值表值不同时结果明确标记冲突；最新需求不覆盖代码事实。
- LLM 回答的关键结论都有有效 Evidence 引用（服务端校验，不信任模型输出）。

## Non-Goals

- 不让 LLM 直接修改代码、数值表或需求。
- 不用向量分数决定哪个事实正确。
- 不把历史需求和当前代码拼成无来源综合文本。
- 不要求人工逐条维护几十万 Claim 的实体归属。
- 第一阶段不扩大 Qdrant 迁移范围（跨版本聚合走 SQLite 实体成员，向量只做问题实体发现）。
