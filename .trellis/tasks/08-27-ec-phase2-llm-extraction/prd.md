# Phase 2 LLM 实体提取与归一化 PRD

## Goal

从**来源**（Claim/参数/测试/存疑）与**用户问题**自动识别实体、别名与候选关系：
规则/确定性提取优先并先落库（来源事实不丢失），LLM 只做结构化候选与**受限选择**，
未命中的实体必须返回候选并标记 `NEEDS_REVIEW`，绝不伪造 entityId。

## Requirements（对照 dev md §6、§7、§14 Phase 2）

1. **来源级实体提取器**：确定性优先（subject/module → 实体候选 + 别名 origin=SOURCE_EXPLICIT）；
   可选 LLM 提议额外别名（origin=LLM_PROPOSED、status=PROPOSED，不得自动成为高置信全局别名）
   与关系候选（matchMethod=LLM_PROPOSED、status=PROPOSED、carry confidence/evidence）。
2. **问题实体提取器**：规则优先从问题提取 mentions + 意图 + 版本条件；LLM 辅助只做受限补召回。
3. **候选实体受限选择 Prompt**：LLM 只能从系统提供的候选 entityId 中选择，不能返回未注册实体 ID。
4. **LLM 提议关系的状态和 Evidence**：`SUPERSEDES/REFINES/REPEALS` 等不得仅由版本号推断，必须
   matchMethod/confidence/evidenceIds/status + PROPOSED 生命周期；不直写权威表。
5. **JSON Schema/Java record 校验**：实体名非空、数量上限、fact 的 sourceClaimId 必须真实存在且属于
   输入批次、relationType 白名单、代码位置不接受伪造。
6. **低置信合并 → NEEDS_REVIEW**：置信度低于阈值或歧义时返回多个候选实体并标记，不做自动合并。
7. **LLM 失败时精确实体检索仍然可用**：规则链（规范化精确 → 已确认别名 → 成员名 → factKey/subject/
   列名 → 代码符号）不依赖 LLM。

## Acceptance

- 用户问题中的实体能匹配已有 entityId（规则路径即可）。
- 未命中时返回候选实体列表 + `NEEDS_REVIEW`，不会伪造 ID。
- LLM 不可用/解析失败时规则提取结果完整可用（错误码稳定、不抛异常吞掉规则结果）。
- LLM 提议别名默认 status=PROPOSED、origin=LLM_PROPOSED，不影响精确匹配（只匹配 CONFIRMED）。
- LLM 输出中非法 claimId/非法 relationType/超数量上限被拒绝并返回结构化错误。
- `./mvnw test` 全绿（新增规则提取、受限选择校验、NEEDS_REVIEW、LLM 失败降级等测试）。