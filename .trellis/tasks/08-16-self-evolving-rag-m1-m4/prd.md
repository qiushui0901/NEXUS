# 自进化 RAG M1-M4 受控闭环

## Goal

实现 `docs/self-evolving-rag-implementation-plan.md` 的 M1-M4：

- M1：Experience Recorder（在线经验采集）
- M2：Failure Miner 与人工审核（评测集演进）
- M3：Experiment Runner（离线回放与对比）
- M4：Policy Registry 与 Promotion Gate（策略注册表与质量门禁）

不实现 M5 灰度/自动回滚和 M6 参数搜索/Bandit。

## Requirements

- 新增 `app.rag.evolution.*` 配置，默认全部关闭；关闭时现有检索行为与性能不变。
- `AgenticOrchestrator` 在执行检索时采集每跳策略、候选、反思结果、最终排序、状态、延迟和版本信息；采集不得阻塞或改变在线检索结果。
- 经验事件以 JSONL 落盘到 `data/evolution/experiences/`，支持按天轮转、保留期清理、脱敏和采样。
- 失败挖掘器从经验事件中按规则发现失败，生成 `EvaluationCandidate`，支持去重、优先级排序和人工审核。
- 未审核的候选不能进入正式评测集；正式评测集按不可变版本发布，可回滚。
- Experiment Runner 使用固定数据集、固定策略版本和固定索引版本运行实验，输出 Recall/MRR/nDCG/延迟等报告。
- 策略注册表支持不可变策略版本、参数 allowlist、校验、状态机（Draft → Evaluating → Approved/Rejected），生产激活通过原子引用切换。
- Promotion Gate 根据离线实验报告决定候选策略是否可批准；未通过门禁不能进入生产激活。
- 新增 `EvolutionController` 提供候选审核、数据集、策略和实验的 REST API。
- 所有新增功能有单元/集成测试，`./mvnw -B verify` 必须通过。

## Acceptance Criteria

- [ ] 默认 `app.rag.evolution.enabled=false`，旧配置无需新增字段即可启动。
- [ ] 开启经验采集后，每次 `AgenticOrchestrator.execute()` 能生成完整 `RetrievalExperience` 事件；关闭时零开销（除开关判断）。
- [ ] Recorder 写入失败、队列满或磁盘不可写时不影响检索响应。
- [ ] Failure Miner 能从经验 JSONL 生成候选，重复样本被聚合/去重。
- [ ] 候选必须经 `DRAFT → IN_REVIEW → APPROVED → PUBLISHED` 流程才能进入正式数据集；未审核样本无法被 Experiment Runner 使用。
- [ ] 数据集版本不可变，发布后可回滚到上一版本。
- [ ] Experiment Runner 可对基线策略和候选策略运行同一数据集，输出可比较报告。
- [ ] 非法策略参数不能注册；未通过 Promotion Gate 的策略不能 `APPROVED` 或激活。
- [ ] `./mvnw -B verify` 通过，`git diff --check` 通过。

## Notes

- 本任务只做 M1-M4，不实现线上灰度、自动回滚和策略自动生成。
- 实现时遵守 `.trellis/spec/backend/retrieval-and-version-knowledge.md` 和 `error-handling.md`。
- 所有用户可见 warning/message 必须是安全、稳定的文本，不暴露内部异常、绝对路径或凭据。
