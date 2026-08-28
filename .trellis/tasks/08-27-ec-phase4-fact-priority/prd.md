# Phase 4 事实优先级与实现偏差 PRD

## Goal

系统能区分“要求是什么”和“现在实际是什么”：按问题类型选择事实视图（当前行为 CODE 最高、当前数值
PARAMETER_TABLE 最高），输出分区的 `factAssessment`，并对代码/数值表/需求/测试的不一致生成确定性
实现偏差信号（`CODE_PARAMETER_MISMATCH` / `REQUIREMENT_IMPLEMENTATION_GAP` / `REQUIREMENT_PARAMETER_MISMATCH`）。
不自动修改任何来源事实、不做来源仲裁。

## Requirements（对照 dev md §9）

1. `EntityFactPriorityService`：按 `EntityQueryPlan` 的 asks* 标志选主证据视图，填充 `FactAssessment`
   （currentBehavior/currentValues/validation/requirementTarget/implementationGaps）。
2. 当前行为优先级 `CODE > TEST_RESULT > PARAMETER_TABLE > REQUIREMENT`；当前数值 `PARAMETER_TABLE > CODE > TEST_RESULT > REQUIREMENT`。
3. 实现偏差（确定性、可审计）：
   - 最新需求目标 ≠ 当前数值表 → `REQUIREMENT_PARAMETER_MISMATCH`（CONFLICTED）。
   - 实体存在 FAILED 测试 → `REQUIREMENT_IMPLEMENTATION_GAP`（REVIEW_REQUIRED）。
   - 代码成员 + 数值表 + FAILED 测试并存 → `CODE_PARAMETER_MISMATCH`（REVIEW_REQUIRED，标注“存在代码实现、测试失败可能未实现参数值”）。
4. 不自动修改来源；冲突不自动仲裁（不改代码/不覆盖需求/不删历史）。
5. `EntityQueryService` 接线：响应 `factAssessment` 从空骨架变为按实体评估结果。

## Acceptance

- 代码值与数值表值不同（经测试失败信号体现）时结果明确标记实现偏差。
- 最新需求不覆盖代码事实（currentBehavior 仍以 CODE 优先）。
- 历史需求按时间轴保留（timeline 不动）。
- 数值可比较时（需求目标 vs 参数值）输出结构化 mismatch 条目。
- `./mvnw test` 全绿（新增事实优先级/实现偏差测试）。