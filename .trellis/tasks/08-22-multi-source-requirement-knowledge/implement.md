# 多源需求知识统一管理与检索 — Implement

> 推荐的执行顺序（每步完成后再继续下一步）。

- [ ] 第 1 步：Phase 0 元数据兼容层（SourceType/Authority/Evidence ID 工具 + 兼容映射测试）
- [ ] 第 2 步：Phase 1 数值表与存疑结构化（ParameterTableLoader + DoubtClaim）
- [ ] 第 3 步：Phase 2 测试用例/结果导入（先 XML/JSON，后源码）
- [ ] 第 4 步：Phase 3 统一 Claim + factKey + 冲突扩展
- [ ] 第 5 步：Phase 4 意图路由 + 多源融合检索 + 解释
- [ ] 第 6 步：Phase 5 Golden Dataset + 灰度

## 当前进行中

- [x] 生成任务文档（PRD/design/implement）
- [x] Phase 0 元数据兼容层（SourceType/Authority + 兼容映射）
- [x] Phase 1 结构化解析层 + SQLite 存储 + 门禁接线
- [x] Phase 2 测试用例/结果导入（JSON/JSONL + JUnit XML）
- [x] Phase 3 统一 Claim（UnifiedKnowledgeClaim）+ factKey + 多源冲突分析器
- [x] Phase 4 意图分类 + 来源过滤 + 多源检索服务 + 解释
- [x] Phase 5 Golden Dataset 离线评估（`multi-source-golden.jsonl` + `MultiSourceGoldenEvalTest`）
- [x] Code Review 整改：REQUIREMENT 适配器、跨源关系抽取、factKey 兜底对齐、旧冲突服务 TEST_CASE 兼容、评分/Top-K/CJK 分词、JUnit skipped/error、Claim 状态门禁、事务性 replaceSnapshot、参数 Evidence 行范围、一致性存疑、意图分类修复
- [ ] 生产加固（留待后续）：Qdrant payload 多源过滤、旧 `TEST` 数据回填清洗、按项目灰度开关、LLM 意图回退、真实 Token usage、CODE 适配器