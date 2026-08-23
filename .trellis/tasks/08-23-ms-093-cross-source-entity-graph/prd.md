# 0.9.3 跨源总实体关系图

## Goal

合并 PRD / DATA / QA / CASE（及代码）为主实体-关系图：规则实体聚合 + 确定性关系 + 可选 LLM 跨源语义关系补充，持久化 `knowledge_entity` / `knowledge_entity_relation` 并提供查询 API。

## Requirements

- 新增 `knowledge_entity` / `knowledge_entity_relation` 表。
- `KnowledgeGraphBuildService` 聚合统一 Claim：
  - REQUIREMENT → FEATURE 实体（subject）
  - PARAMETER_TABLE → CONFIG_TABLE 实体（fact_key module）
  - TEST_CASE → TEST_MODULE 实体（fact_key module）
  - DOUBT → RISK_AREA 实体（fact_key module，缺失归入“QA存疑”）
  - CODE → CODE 实体（通过 `CodeEntitySource` SPI 接入）
- 规则关系：按规范化名称匹配生成 `SUPPORTS / VERIFIES / RAISES_DOUBT / IMPLEMENTED_BY`。
- 可选 LLM：`LlmGraphExtractor` SPI 补充 `SEMANTIC_RELATED` 等语义边。
- API：查询图 + 构建图。

## Acceptance Criteria

- [ ] 图可幂等重建（先清空再写入）。
- [ ] 实体与关系可按项目/版本查询。
- [ ] 代码通过 `CodeEntitySource` 可并入（SPI 已定义）。
- [ ] LLM 语义边可插拔（默认关闭）。
- [ ] 全量测试通过。