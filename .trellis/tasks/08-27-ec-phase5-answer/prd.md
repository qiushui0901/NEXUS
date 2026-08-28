# Phase 5 AI 带证据回答 PRD

## Goal

`KnowledgeAnswerService` 基于受限证据包（实体检索响应）生成可审计回答：输出
`answer / sections(title,text,evidenceIds) / status / citationQuality`，模型返回的 Evidence ID
必须经服务端校验（Evidence Registry 语义），代码/数值表冲突时回答同时报告两者与实现偏差，
无足够证据返回“无法确定”。LLM 不可用时用确定性模板降级。

## Requirements（对照 dev md §11、§14 Phase 5）

1. 证据包分节输入：CURRENT_CODE / CURRENT_PARAMETER_TABLE / TEST_RESULT / LATEST_REQUIREMENT /
   HISTORICAL_REQUIREMENT / RELATIONS / CONFLICTS / WARNINGS（有界，不把整库文本喂给 LLM）。
2. 系统 Prompt 约束：只基于证据回答；当前行为优先引用 CURRENT_CODE；当前数值优先 CURRENT_PARAMETER_TABLE；
   TEST_RESULT 只说明验证是否通过；REQUIREMENT 不得覆盖当前代码事实；代码与数值表不一致必须同时报告；
   每个关键结论附证据 ID；证据不足回答“无法确定”。
3. 服务端校验模型引用：section.evidenceIds 必须 ∈ 允许集（响应 citations 的 evidence/claim 引用）；
   非法引用丢弃并标记 citationQuality=PARTIAL；允许集过少 → UNVERIFIED；全部合法 → VERIFIED。
4. 确定性模板：LLM 不可用/失败时——有实现偏差 → 偏差模板；无数值/代码 → 缺失来源提示；否则“无法确定”。
5. 状态：存在 CONFLICTED/REVIEW_REQUIRED 偏差 → status=REVIEW_REQUIRED；否则 CONFIRMED。
6. API：`POST /api/knowledge/entity-answer`（projectId/query/... 同 entity-search），内部跑 search → answer。

## Acceptance

- 关键结论都有有效引用（服务端校验，不信任模型输出）。
- 代码与数值表冲突时回答同时展示两者（模板层保证）。
- 模型无法判断/LLM 不可用时不编造结论。
- `./mvnw test` 全绿（新增答案服务/引用校验/模板降级/控制器测试）。